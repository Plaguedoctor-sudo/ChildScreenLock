package com.childlock.screentime.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SovereignKeyStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "sovereign_child_lock_master_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    private val secureRandom = SecureRandom()

    init {
        KnownAnswerTest.runPowerOnSelfTests()
        ensureMasterKeyExists()
    }

    private fun ensureMasterKeyExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("Sovereign master key not found in AndroidKeyStore")
        return entry.secretKey
    }

    /**
     * Authenticated AES-256-GCM encryption with 12-byte random IV.
     * Output format: [12-byte IV][Ciphertext + 16-byte Auth Tag]
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        val byteBuffer = ByteBuffer.allocate(iv.size + ciphertext.size)
        byteBuffer.put(iv)
        byteBuffer.put(ciphertext)
        return byteBuffer.array()
    }

    /**
     * Authenticated AES-256-GCM decryption.
     * Validates GCM auth tag; throws AEADBadTagException if ciphertext was tampered with.
     */
    fun decrypt(packed: ByteArray): ByteArray {
        if (packed.size < IV_LENGTH + 16) {
            throw IllegalArgumentException("Invalid encrypted payload size")
        }

        val iv = ByteArray(IV_LENGTH)
        val ciphertextLength = packed.size - IV_LENGTH
        val ciphertext = ByteArray(ciphertextLength)

        System.arraycopy(packed, 0, iv, 0, IV_LENGTH)
        System.arraycopy(packed, IV_LENGTH, ciphertext, 0, ciphertextLength)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Physical in-memory buffer scrub (NIST SP 800-88 / DoD 5220.22-M 3-pass zeroization).
     */
    fun secureWipe(buffer: ByteArray) {
        // Pass 1: Cryptographic pseudo-random overwrite
        secureRandom.nextBytes(buffer)
        // Pass 2: Bitwise inversion
        for (i in buffer.indices) {
            buffer[i] = (buffer[i].toInt() xor 0xFF).toByte()
        }
        // Pass 3: Zeroization
        buffer.fill(0)
    }

    fun secureWipe(buffer: CharArray) {
        buffer.fill('0')
    }
}
