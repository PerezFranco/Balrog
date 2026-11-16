package com.perezfranco.balrog.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level cryptographic primitives used by the vault.
 *
 * All symmetric encryption is AES-256-GCM with a random 96-bit IV prepended
 * to the ciphertext. The master password is stretched with PBKDF2-HMAC-SHA256.
 */
object CryptoEngine {

    const val KDF_ITERATIONS = 310_000
    const val SALT_LENGTH_BYTES = 16
    const val KEY_LENGTH_BYTES = 32

    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val AES_GCM = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    fun randomBytes(length: Int): ByteArray =
        ByteArray(length).also { secureRandom.nextBytes(it) }

    /** Derives a 256-bit AES key from the master password. */
    fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = KDF_ITERATIONS,
    ): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BYTES * 8)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = factory.generateSecret(spec).encoded
            try {
                return SecretKeySpec(keyBytes, "AES")
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    /** Returns IV || ciphertext+tag. */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        val iv = randomBytes(GCM_IV_LENGTH_BYTES)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Decrypts a blob produced by [encrypt].
     *
     * @throws javax.crypto.AEADBadTagException if the key is wrong or the
     *         blob has been tampered with.
     */
    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > GCM_IV_LENGTH_BYTES) { "Ciphertext blob too short" }
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, blob, 0, GCM_IV_LENGTH_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(blob, GCM_IV_LENGTH_BYTES, blob.size - GCM_IV_LENGTH_BYTES)
    }
}
