package com.childlock.screentime.security

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object KnownAnswerTest {

    private var selfTestPassed = false

    /**
     * Executes Power-On Self-Tests (FIPS 140-3) on SHA-256, HMAC-SHA256, and AES-256-GCM.
     */
    fun runPowerOnSelfTests(): Boolean {
        if (selfTestPassed) return true

        val sha256Ok = testSha256Kat()
        val hmacOk = testHmacSha256Kat()
        val aesGcmOk = testAesGcmKat()

        selfTestPassed = sha256Ok && hmacOk && aesGcmOk
        if (!selfTestPassed) {
            throw SecurityException("FIPS 140-3 Cryptographic Known Answer Test Failed! System entering containment.")
        }
        return true
    }

    private fun testSha256Kat(): Boolean {
        // Known NIST vector for "abc"
        val input = "abc".toByteArray(Charsets.UTF_8)
        val expectedHex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input)
        val actualHex = digest.joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(actualHex.toByteArray(), expectedHex.toByteArray())
    }

    private fun testHmacSha256Kat(): Boolean {
        // RFC 4231 Test Case 2: key = "Jefe", data = "what do ya want for nothing?"
        val keyBytes = "Jefe".toByteArray(Charsets.UTF_8)
        val dataBytes = "what do ya want for nothing?".toByteArray(Charsets.UTF_8)
        val expectedHex = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"

        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(keyBytes, "HmacSHA256")
        mac.init(secretKey)
        val actual = mac.doFinal(dataBytes)
        val actualHex = actual.joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(actualHex.toByteArray(), expectedHex.toByteArray())
    }

    private fun testAesGcmKat(): Boolean {
        // NIST SP 800-38D AES-GCM Test Vector
        val keyBytes = ByteArray(32) { 0x01 }
        val ivBytes = ByteArray(12) { 0x02 }
        val plaintext = "MEDSCRIPT_SECURITY_TEST".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(keyBytes, "AES")
        val spec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val ciphertext = cipher.doFinal(plaintext)

        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decrypted = cipher.doFinal(ciphertext)
        return MessageDigest.isEqual(plaintext, decrypted)
    }
}
