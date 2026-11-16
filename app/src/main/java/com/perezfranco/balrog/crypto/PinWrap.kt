package com.perezfranco.balrog.crypto

import java.security.GeneralSecurityException

/**
 * The PIN half of "biometric + PIN" unlock.
 *
 * The vault key is first encrypted under a key derived from the PIN, and
 * only that result is handed to the biometric-protected Keystore key. So the
 * PIN is not a screen in front of the vault: without it, even a successful
 * biometric match yields nothing usable, and a wrong PIN simply fails
 * authentication.
 *
 * A PIN is short, so this layer is only as strong as the limit on guesses.
 * That limit comes from the outer layer (the PIN-sealed blob is never stored
 * outside the Keystore wrap) and from the attempt counter in VaultKeyManager.
 */
object PinWrap {

    const val MIN_PIN_LENGTH = 4
    const val MAX_ATTEMPTS = 5
    const val KDF_ITERATIONS = 100_000

    class Sealed(val blob: ByteArray, val salt: ByteArray, val iterations: Int)

    fun seal(secret: ByteArray, pin: CharArray, iterations: Int = KDF_ITERATIONS): Sealed {
        val salt = CryptoEngine.randomBytes(CryptoEngine.SALT_LENGTH_BYTES)
        val key = CryptoEngine.deriveKey(pin, salt, iterations)
        return Sealed(CryptoEngine.encrypt(key, secret), salt, iterations)
    }

    /** Returns the secret, or null if [pin] is wrong. */
    fun open(blob: ByteArray, salt: ByteArray, iterations: Int, pin: CharArray): ByteArray? {
        val key = CryptoEngine.deriveKey(pin, salt, iterations)
        return try {
            CryptoEngine.decrypt(key, blob)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
