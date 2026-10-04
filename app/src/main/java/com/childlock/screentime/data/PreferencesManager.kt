package com.childlock.screentime.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.childlock.screentime.security.AuditLedger
import com.childlock.screentime.security.CryptoKdfHelper
import com.childlock.screentime.security.RateLimiter
import com.childlock.screentime.security.SovereignKeyStore
import org.json.JSONArray
import org.json.JSONObject

data class ChildFaceProfile(
    val id: String,
    val name: String,
    val timestamp: Long = System.currentTimeMillis()
)

class PreferencesManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val rateLimiter: RateLimiter = RateLimiter(context)
    val auditLedger: AuditLedger = AuditLedger(context)

    companion object {
        const val MAX_CHILD_FACES = 5
        private const val PREFS_NAME = "sovereign_child_lock_secure_prefs"
        private const val KEY_PARENT_PIN_KDF = "parent_pin_kdf"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_IS_LOCK_ACTIVE = "is_lock_active"
        private const val KEY_LOCK_END_TIME = "lock_end_time"
        private const val KEY_ENCRYPTED_FACE_EMBEDDING = "child_face_embedding_enc"
        private const val KEY_CHILD_PROFILES_META = "child_profiles_meta"
        private const val KEY_CHILD_EMB_PREFIX = "child_emb_enc_"
        private const val KEY_FACE_GUARD_ENABLED = "face_guard_enabled"
        private const val KEY_LOCK_DURATION_MINUTES = "lock_duration_minutes"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted"
        private const val KEY_ENCRYPTED_RECOVERY_KEY = "recovery_key_enc"
        private const val KEY_BLOCK_SETTINGS_ENABLED = "block_settings_enabled"
        private const val KEY_TELEMETRY_SINKHOLE_ENABLED = "telemetry_sinkhole_enabled"

        // Temporary Adult Authorization (Allows other users to use device when non-child face is recognized)
        @Volatile
        var adultAuthorizedPackage: String? = null
        @Volatile
        var adultAuthorizedExpiry: Long = 0L
    }

    fun isTermsAccepted(): Boolean {
        return prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
    }

    fun setTermsAccepted(accepted: Boolean) {
        prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, accepted).commit()
    }

    /**
     * Blueprint §3: Zero Default Credentials.
     * Returns false if parent has not completed First-Time Sovereign Setup.
     */
    fun isPinConfigured(): Boolean {
        return prefs.contains(KEY_PARENT_PIN_KDF) &&
                !prefs.getString(KEY_PARENT_PIN_KDF, "").isNullOrBlank()
    }

    /**
     * Blueprint §4: Constant-time timing-safe verification using memory-hard KDF.
     * Enforces anti-brute-force rate limiting and logs to audit ledger.
     */
    fun verifyPin(pin: String): Boolean {
        val status = rateLimiter.checkStatus()
        if (status.isLockedOut) {
            return false
        }

        val storedKdf = prefs.getString(KEY_PARENT_PIN_KDF, "") ?: ""
        if (storedKdf.isBlank()) return false

        val isMatch = CryptoKdfHelper.verifyPin(pin, storedKdf)
        if (isMatch) {
            rateLimiter.recordSuccess()
        } else {
            rateLimiter.recordFailure()
            auditLedger.logEvent(AuditLedger.EVENT_PIN_ATTEMPT_FAILED, "Failed PIN verification attempt")
        }
        return isMatch
    }

    /**
     * Sets or updates Parent PIN using memory-hard scrypt KDF.
     */
    fun setParentPin(pin: String) {
        val kdfHash = CryptoKdfHelper.hashPin(pin)
        prefs.edit().putString(KEY_PARENT_PIN_KDF, kdfHash).apply()
        auditLedger.logEvent(AuditLedger.EVENT_PIN_CHANGED, "Parent PIN configured/updated")
    }

    // Allowed Packages Whitelist
    fun getAllowedPackages(): Set<String> {
        val stored = prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        return stored + context.packageName
    }

    fun setAllowedPackages(packages: Set<String>) {
        // Must make a defensive copy and ensure our own app is always included
        val setWithOwnApp = HashSet(packages).apply { add(context.packageName) }
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, setWithOwnApp).commit()
    }

    fun isPackageAllowed(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == context.packageName || packageName == "com.childlock.screentime") return true
        val allowed = getAllowedPackages()
        return allowed.contains(packageName)
    }

    // Active Lock Session
    fun isLockActive(): Boolean {
        val active = prefs.getBoolean(KEY_IS_LOCK_ACTIVE, false)
        if (!active) return false
        val endTime = getLockEndTime()
        if (System.currentTimeMillis() >= endTime) {
            setLockActive(false, 0L)
            return false
        }
        return true
    }

    fun setLockActive(active: Boolean, durationMinutes: Long = 30L) {
        val endTime = if (active) System.currentTimeMillis() + (durationMinutes * 60 * 1000L) else 0L
        prefs.edit()
            .putBoolean(KEY_IS_LOCK_ACTIVE, active)
            .putLong(KEY_LOCK_END_TIME, endTime)
            .putLong(KEY_LOCK_DURATION_MINUTES, durationMinutes)
            .apply()

        if (active) {
            auditLedger.logEvent(AuditLedger.EVENT_LOCK_STARTED, "Lock duration: $durationMinutes min (${formatDuration(durationMinutes * 60 * 1000L)})")
        } else {
            auditLedger.logEvent(AuditLedger.EVENT_LOCK_STOPPED, "Lock session concluded")
        }
    }

    fun getLockEndTime(): Long {
        return prefs.getLong(KEY_LOCK_END_TIME, 0L)
    }

    fun getRemainingMillis(): Long {
        val endTime = getLockEndTime()
        val diff = endTime - System.currentTimeMillis()
        return if (diff > 0) diff else 0L
    }

    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "00:00:00"
        val totalSec = millis / 1000
        val days = totalSec / 86400
        val remSecAfterDays = totalSec % 86400
        val hrs = remSecAfterDays / 3600
        val mins = (remSecAfterDays % 3600) / 60
        val secs = remSecAfterDays % 60

        return when {
            days >= 365 -> {
                val years = days / 365
                val remDays = days % 365
                String.format("%d yr %d d, %02d:%02d:%02d", years, remDays, hrs, mins, secs)
            }
            days > 0 -> {
                String.format("%d d, %02d:%02d:%02d", days, hrs, mins, secs)
            }
            else -> {
                String.format("%02d:%02d:%02d", hrs, mins, secs)
            }
        }
    }

    // Face Guard Enabled
    fun isFaceGuardEnabled(): Boolean {
        return prefs.getBoolean(KEY_FACE_GUARD_ENABLED, true)
    }

    fun setFaceGuardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FACE_GUARD_ENABLED, enabled).apply()
    }

    // Multi-Face Profiles (Up to 5 pre-stored child faces)
    fun getChildProfiles(): List<ChildFaceProfile> {
        val metaJson = prefs.getString(KEY_CHILD_PROFILES_META, null)
        if (!metaJson.isNullOrBlank()) {
            val list = mutableListOf<ChildFaceProfile>()
            try {
                val array = JSONArray(metaJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        ChildFaceProfile(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
                return list
            } catch (e: Exception) {
                // fall through
            }
        }

        // Automatic legacy migration if single face was previously saved
        if (prefs.contains(KEY_ENCRYPTED_FACE_EMBEDDING)) {
            val legacyEmb = getLegacyFaceEmbedding()
            if (legacyEmb != null) {
                val migratedProfile = ChildFaceProfile("child_1", "Child 1", System.currentTimeMillis())
                saveChildFace("Child 1", legacyEmb, customId = "child_1")
                return listOf(migratedProfile)
            }
        }

        return emptyList()
    }

    fun hasChildFaceRegistered(): Boolean {
        return getChildProfiles().isNotEmpty()
    }

    fun getChildFacesCount(): Int {
        return getChildProfiles().size
    }

    fun saveChildFace(name: String, embedding: FloatArray, customId: String? = null): Boolean {
        val existing = getChildProfiles().toMutableList()
        if (customId == null && existing.size >= MAX_CHILD_FACES) {
            return false // Max 5 faces limit reached
        }

        val profileId = customId ?: "child_${System.currentTimeMillis()}"
        val stringRep = embedding.joinToString(",") { it.toString() }
        val rawBytes = stringRep.toByteArray(Charsets.UTF_8)
        val encryptedBytes = SovereignKeyStore.encrypt(rawBytes)
        val base64Encrypted = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

        prefs.edit().putString(KEY_CHILD_EMB_PREFIX + profileId, base64Encrypted).apply()
        SovereignKeyStore.secureWipe(rawBytes)

        val existingIdx = existing.indexOfFirst { it.id == profileId }
        val updatedProfile = ChildFaceProfile(profileId, name.ifBlank { "Child ${existing.size + 1}" })
        if (existingIdx >= 0) {
            existing[existingIdx] = updatedProfile
        } else {
            existing.add(updatedProfile)
        }

        saveProfilesMeta(existing)
        auditLedger.logEvent(
            AuditLedger.EVENT_FACE_ENROLLED,
            "Child biometric profile enrolled: ${updatedProfile.name} (Total: ${existing.size}/$MAX_CHILD_FACES)"
        )
        return true
    }

    fun deleteChildFaceProfile(id: String): Boolean {
        val existing = getChildProfiles().toMutableList()
        val profileToRemove = existing.find { it.id == id } ?: return false

        existing.remove(profileToRemove)
        prefs.edit().remove(KEY_CHILD_EMB_PREFIX + id).apply()
        saveProfilesMeta(existing)

        if (existing.isEmpty()) {
            prefs.edit().remove(KEY_ENCRYPTED_FACE_EMBEDDING).apply()
        }
        auditLedger.logEvent(AuditLedger.EVENT_FACE_ENROLLED, "Child biometric profile removed: ${profileToRemove.name}")
        return true
    }

    fun clearChildFaceEmbedding() {
        val existing = getChildProfiles()
        val editor = prefs.edit()
        for (profile in existing) {
            editor.remove(KEY_CHILD_EMB_PREFIX + profile.id)
        }
        editor.remove(KEY_CHILD_PROFILES_META)
        editor.remove(KEY_ENCRYPTED_FACE_EMBEDDING)
        editor.apply()
        auditLedger.logEvent(AuditLedger.EVENT_FACE_ENROLLED, "All child biometric face profiles zeroized")
    }

    fun getAllChildEmbeddings(): List<Pair<ChildFaceProfile, FloatArray>> {
        val profiles = getChildProfiles()
        val result = mutableListOf<Pair<ChildFaceProfile, FloatArray>>()

        for (profile in profiles) {
            val base64 = prefs.getString(KEY_CHILD_EMB_PREFIX + profile.id, null) ?: continue
            try {
                val encBytes = Base64.decode(base64, Base64.NO_WRAP)
                val decBytes = SovereignKeyStore.decrypt(encBytes)
                val str = String(decBytes, Charsets.UTF_8)
                SovereignKeyStore.secureWipe(decBytes)
                val emb = str.split(",").map { it.toFloat() }.toFloatArray()
                result.add(Pair(profile, emb))
            } catch (e: Exception) {
                // skip corrupted
            }
        }
        return result
    }

    fun findMatchingChild(candidateEmbedding: FloatArray, faceEmbeddingHelper: com.childlock.screentime.face.FaceEmbeddingHelper): ChildFaceProfile? {
        val all = getAllChildEmbeddings()
        for ((profile, storedEmb) in all) {
            if (faceEmbeddingHelper.isMatch(candidateEmbedding, storedEmb)) {
                return profile
            }
        }
        return null
    }

    fun isFaceOfAnyChild(candidateEmbedding: FloatArray, faceEmbeddingHelper: com.childlock.screentime.face.FaceEmbeddingHelper): Boolean {
        return findMatchingChild(candidateEmbedding, faceEmbeddingHelper) != null
    }

    private fun saveProfilesMeta(profiles: List<ChildFaceProfile>) {
        val array = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("timestamp", p.timestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_CHILD_PROFILES_META, array.toString()).commit()
    }

    // Legacy compatibility helpers
    fun saveChildFaceEmbedding(embedding: FloatArray) {
        saveChildFace("Child 1", embedding)
    }

    fun getChildFaceEmbedding(): FloatArray? {
        val all = getAllChildEmbeddings()
        if (all.isNotEmpty()) return all[0].second
        return getLegacyFaceEmbedding()
    }

    private fun getLegacyFaceEmbedding(): FloatArray? {
        val base64Encrypted = prefs.getString(KEY_ENCRYPTED_FACE_EMBEDDING, null) ?: return null
        if (base64Encrypted.isEmpty()) return null

        return try {
            val encryptedBytes = Base64.decode(base64Encrypted, Base64.NO_WRAP)
            val decryptedBytes = SovereignKeyStore.decrypt(encryptedBytes)
            val stringRep = String(decryptedBytes, Charsets.UTF_8)
            SovereignKeyStore.secureWipe(decryptedBytes)
            stringRep.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            null
        }
    }

    // Adult Authorization Management (Allows others to freely use device)
    fun grantAdultAccess(packageName: String, durationMillis: Long = 10 * 60 * 1000L) {
        adultAuthorizedPackage = packageName
        adultAuthorizedExpiry = System.currentTimeMillis() + durationMillis
        auditLedger.logEvent(AuditLedger.EVENT_PIN_VERIFIED, "Adult authorized for: $packageName (expires in ${durationMillis/60000} min)")
    }

    fun isAdultAuthorized(packageName: String): Boolean {
        if (System.currentTimeMillis() > adultAuthorizedExpiry) {
            adultAuthorizedPackage = null
            return false
        }
        return adultAuthorizedPackage == packageName || adultAuthorizedPackage == "*"
    }

    fun revokeAdultAccess() {
        adultAuthorizedPackage = null
        adultAuthorizedExpiry = 0L
    }

    // Master Recovery Key Management (Hardware Encrypted)
    fun getOrCreateRecoveryKey(): String {
        val existingEnc = prefs.getString(KEY_ENCRYPTED_RECOVERY_KEY, null)
        if (!existingEnc.isNullOrBlank()) {
            return try {
                val bytes = Base64.decode(existingEnc, Base64.NO_WRAP)
                val decrypted = SovereignKeyStore.decrypt(bytes)
                val key = String(decrypted, Charsets.UTF_8)
                SovereignKeyStore.secureWipe(decrypted)
                key
            } catch (e: Exception) {
                generateAndSaveRecoveryKey()
            }
        }
        return generateAndSaveRecoveryKey()
    }

    private fun generateAndSaveRecoveryKey(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Exclude confusing chars like 0, O, 1, I
        val random = java.security.SecureRandom()
        val part1 = (0 until 4).map { chars[random.nextInt(chars.length)] }.joinToString("")
        val part2 = (0 until 4).map { chars[random.nextInt(chars.length)] }.joinToString("")
        val key = "RCV-$part1-$part2"

        val raw = key.toByteArray(Charsets.UTF_8)
        val encrypted = SovereignKeyStore.encrypt(raw)
        val base64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(KEY_ENCRYPTED_RECOVERY_KEY, base64).apply()
        SovereignKeyStore.secureWipe(raw)

        return key
    }

    fun verifyRecoveryKey(candidateKey: String): Boolean {
        val normalizedCandidate = candidateKey.trim().uppercase()
        val actualKey = getOrCreateRecoveryKey()
        val isMatch = java.security.MessageDigest.isEqual(
            normalizedCandidate.toByteArray(Charsets.UTF_8),
            actualKey.toByteArray(Charsets.UTF_8)
        )
        if (isMatch) {
            rateLimiter.recordSuccess()
            auditLedger.logEvent(AuditLedger.EVENT_LOCK_STOPPED, "Emergency Master Recovery Key verified")
        }
        return isMatch
    }

    // Preference Toggles
    fun isBlockSettingsEnabled(): Boolean {
        return prefs.getBoolean(KEY_BLOCK_SETTINGS_ENABLED, true)
    }

    fun setBlockSettingsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SETTINGS_ENABLED, enabled).apply()
    }

    fun isTelemetrySinkholeEnabled(): Boolean {
        return prefs.getBoolean(KEY_TELEMETRY_SINKHOLE_ENABLED, true)
    }

    fun setTelemetrySinkholeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEMETRY_SINKHOLE_ENABLED, enabled).apply()
    }

    fun resetAllSettings() {
        setLockActive(false, 0L)
        prefs.edit().clear().apply()
        auditLedger.logEvent(AuditLedger.EVENT_LOCK_STOPPED, "All application settings and profiles reset")
    }
}
