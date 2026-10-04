package com.childlock.screentime.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object CryptoKdfHelper {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val PREFIX = "scrypt:v1:"

    private val secureRandom = SecureRandom()

    /**
     * Derives a cryptographic hash from a PIN using a 16-byte secure random salt.
     * Returns "scrypt:v1:<salt_hex>:<hash_hex>".
     */
    fun hashPin(pin: String): String {
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)

        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val skf = SecretKeyFactory.getInstance(ALGORITHM)
        val hash = skf.generateSecret(spec).encoded

        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = hash.joinToString("") { "%02x".format(it) }

        // Clean in-memory sensitive material
        spec.clearPassword()
        SovereignKeyStore.secureWipe(salt)
        SovereignKeyStore.secureWipe(hash)

        return "$PREFIX$saltHex:$hashHex"
    }

    /**
     * Constant-time timing-safe verification against stored hash.
     */
    fun verifyPin(candidatePin: String, storedHash: String): Boolean {
        if (!storedHash.startsWith(PREFIX)) return false
        val payload = storedHash.substring(PREFIX.length)
        val parts = payload.split(":")
        if (parts.size != 2) return false

        val saltHex = parts[0]
        val expectedHashHex = parts[1]

        val salt = hexToBytes(saltHex)
        val spec = PBEKeySpec(candidatePin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val skf = SecretKeyFactory.getInstance(ALGORITHM)
        val candidateHash = skf.generateSecret(spec).encoded

        val candidateHashHex = candidateHash.joinToString("") { "%02x".format(it) }

        // Clean memory
        spec.clearPassword()
        SovereignKeyStore.secureWipe(salt)
        SovereignKeyStore.secureWipe(candidateHash)

        // Constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
            candidateHashHex.toByteArray(Charsets.UTF_8),
            expectedHashHex.toByteArray(Charsets.UTF_8)
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
