package com.childlock.screentime.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AuditLedger(context: Context) {

    private val logFile = File(context.filesDir, "sovereign_audit_ledger.dat")
    private val ledgerHmacKey = "SOVEREIGN_AUDIT_INTEGRITY_KEY_V1".toByteArray(Charsets.UTF_8)

    companion object {
        const val GENESIS_ROOT = "SOVEREIGN_GENESIS_ROOT_V1"
        const val EVENT_LOCK_STARTED = "LOCK_STARTED"
        const val EVENT_LOCK_STOPPED = "LOCK_STOPPED"
        const val EVENT_APP_BLOCKED = "APP_BLOCKED"
        const val EVENT_FACE_ENROLLED = "FACE_ENROLLED"
        const val EVENT_PIN_CHANGED = "PIN_CHANGED"
        const val EVENT_PIN_ATTEMPT_FAILED = "PIN_ATTEMPT_FAILED"
        const val EVENT_PIN_VERIFIED = "PIN_VERIFIED"
        const val EVENT_ADULT_AUTHORIZED = "ADULT_AUTHORIZED"
    }

    @Synchronized
    fun logEvent(event: String, details: String) {
        val entries = loadLedger()
        val prevHash = if (entries.length() == 0) {
            GENESIS_ROOT
        } else {
            entries.getJSONObject(entries.length() - 1).getString("entryHash")
        }

        val timestamp = System.currentTimeMillis()
        val canonicalPayload = "$prevHash:$timestamp:$event:$details"
        val entryHash = calculateHmacSha256(canonicalPayload)

        val entry = JSONObject().apply {
            put("timestamp", timestamp)
            put("event", event)
            put("details", details)
            put("prevHash", prevHash)
            put("entryHash", entryHash)
        }

        entries.put(entry)
        saveLedger(entries)
    }

    private fun calculateHmacSha256(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ledgerHmacKey, "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    private fun loadLedger(): JSONArray {
        if (!logFile.exists()) return JSONArray()
        return try {
            val encrypted = logFile.readBytes()
            val decrypted = SovereignKeyStore.decrypt(encrypted)
            JSONArray(String(decrypted, Charsets.UTF_8))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveLedger(entries: JSONArray) {
        try {
            val plaintext = entries.toString().toByteArray(Charsets.UTF_8)
            val encrypted = SovereignKeyStore.encrypt(plaintext)
            logFile.writeBytes(encrypted)
            SovereignKeyStore.secureWipe(plaintext)
        } catch (e: Exception) {
            // Write failed
        }
    }

    /**
     * Verifies the cryptographic integrity of the entire audit chain.
     * Returns true if 100% untampered.
     */
    fun verifyIntegrity(): Boolean {
        val entries = loadLedger()
        var expectedPrev = GENESIS_ROOT

        for (i in 0 until entries.length()) {
            val obj = entries.getJSONObject(i)
            val prevHash = obj.getString("prevHash")
            if (prevHash != expectedPrev) return false

            val timestamp = obj.getLong("timestamp")
            val event = obj.getString("event")
            val details = obj.getString("details")
            val entryHash = obj.getString("entryHash")

            val canonicalPayload = "$prevHash:$timestamp:$event:$details"
            val calculatedHash = calculateHmacSha256(canonicalPayload)

            if (!MessageDigest.isEqual(calculatedHash.toByteArray(), entryHash.toByteArray())) {
                return false
            }

            expectedPrev = entryHash
        }

        return true
    }
}
