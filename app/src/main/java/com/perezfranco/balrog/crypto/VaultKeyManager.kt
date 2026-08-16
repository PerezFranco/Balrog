package com.perezfranco.balrog.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the vault's key hierarchy.
 *
 * A random 256-bit Data Encryption Key (DEK) encrypts every vault record.
 * The DEK itself is stored only in wrapped form:
 *
 *  - wrapped by a Key Encryption Key derived from the master password
 *    (PBKDF2), and, optionally,
 *  - wrapped by a hardware-backed Android Keystore key that requires
 *    biometric authentication for every use.
 *
 * Unwrapping with the wrong password fails GCM authentication, which doubles
 * as master-password verification — no password hash is ever stored.
 */
class VaultKeyManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun vaultExists(): Boolean = prefs.contains(KEY_WRAPPED_DEK)

    fun isBiometricEnabled(): Boolean = prefs.contains(KEY_BIOMETRIC_WRAPPED_DEK)

    /** Creates a brand-new vault protected by [masterPassword]. */
    fun createVault(masterPassword: CharArray): SecretKey {
        check(!vaultExists()) { "Vault already exists" }
        val salt = CryptoEngine.randomBytes(CryptoEngine.SALT_LENGTH_BYTES)
        val kek = CryptoEngine.deriveKey(masterPassword, salt)
        val dekBytes = CryptoEngine.randomBytes(CryptoEngine.KEY_LENGTH_BYTES)
        val wrapped = CryptoEngine.encrypt(kek, dekBytes)
        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putInt(KEY_ITERATIONS, CryptoEngine.KDF_ITERATIONS)
            .putString(KEY_WRAPPED_DEK, encode(wrapped))
            .apply()
        val dek = SecretKeySpec(dekBytes, "AES")
        dekBytes.fill(0)
        return dek
    }

    /** Returns the DEK, or null if [masterPassword] is wrong. */
    fun unlockWithPassword(masterPassword: CharArray): SecretKey? {
        val salt = decode(prefs.getString(KEY_SALT, null) ?: return null)
        val iterations = prefs.getInt(KEY_ITERATIONS, CryptoEngine.KDF_ITERATIONS)
        val wrapped = decode(prefs.getString(KEY_WRAPPED_DEK, null) ?: return null)
        val kek = CryptoEngine.deriveKey(masterPassword, salt, iterations)
        return try {
            val dekBytes = CryptoEngine.decrypt(kek, wrapped)
            SecretKeySpec(dekBytes, "AES").also { dekBytes.fill(0) }
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** Re-wraps the DEK under a new master password. */
    fun changePassword(currentPassword: CharArray, newPassword: CharArray): Boolean {
        val dek = unlockWithPassword(currentPassword) ?: return false
        val salt = CryptoEngine.randomBytes(CryptoEngine.SALT_LENGTH_BYTES)
        val kek = CryptoEngine.deriveKey(newPassword, salt)
        val wrapped = CryptoEngine.encrypt(kek, dek.encoded)
        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putInt(KEY_ITERATIONS, CryptoEngine.KDF_ITERATIONS)
            .putString(KEY_WRAPPED_DEK, encode(wrapped))
            .apply()
        return true
    }

    // ---- Biometric unlock ----------------------------------------------------

    /**
     * Returns a cipher ready to wrap the DEK, to be authorized through a
     * BiometricPrompt CryptoObject. Generates the Keystore key if needed.
     */
    fun prepareBiometricEnrollCipher(): Cipher {
        val keystoreKey = getOrCreateKeystoreKey()
        return Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, keystoreKey)
        }
    }

    /** Completes biometric enrollment with the cipher authorized by the prompt. */
    fun finishBiometricEnroll(authorizedCipher: Cipher, dek: SecretKey) {
        val wrapped = authorizedCipher.doFinal(dek.encoded)
        prefs.edit()
            .putString(KEY_BIOMETRIC_IV, encode(authorizedCipher.iv))
            .putString(KEY_BIOMETRIC_WRAPPED_DEK, encode(wrapped))
            .apply()
    }

    /**
     * Returns a cipher ready to unwrap the DEK via BiometricPrompt, or null if
     * biometrics are not enrolled or the Keystore key was invalidated (e.g. a
     * new fingerprint was added on the device).
     */
    fun prepareBiometricUnlockCipher(): Cipher? {
        if (!isBiometricEnabled()) return null
        val iv = decode(prefs.getString(KEY_BIOMETRIC_IV, null) ?: return null)
        return try {
            val keystoreKey = loadKeystoreKey() ?: return null
            Cipher.getInstance(AES_GCM).apply {
                init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(128, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBiometric()
            null
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** Completes biometric unlock with the cipher authorized by the prompt. */
    fun finishBiometricUnlock(authorizedCipher: Cipher): SecretKey? {
        val wrapped = decode(prefs.getString(KEY_BIOMETRIC_WRAPPED_DEK, null) ?: return null)
        return try {
            val dekBytes = authorizedCipher.doFinal(wrapped)
            SecretKeySpec(dekBytes, "AES").also { dekBytes.fill(0) }
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** Removes all key material; the vault can be set up again from scratch. */
    fun destroyVault() {
        disableBiometric()
        prefs.edit().clear().apply()
    }

    fun disableBiometric() {
        prefs.edit()
            .remove(KEY_BIOMETRIC_IV)
            .remove(KEY_BIOMETRIC_WRAPPED_DEK)
            .apply()
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(KEYSTORE_ALIAS)
        } catch (ignored: GeneralSecurityException) {
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        loadKeystoreKey()?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0, KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun loadKeystoreKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS_NAME = "balrog_vault_meta"
        const val KEY_SALT = "kdf_salt"
        const val KEY_ITERATIONS = "kdf_iterations"
        const val KEY_WRAPPED_DEK = "wrapped_dek"
        const val KEY_BIOMETRIC_IV = "biometric_iv"
        const val KEY_BIOMETRIC_WRAPPED_DEK = "biometric_wrapped_dek"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "balrog_biometric_dek_wrapper"
        const val AES_GCM = "AES/GCM/NoPadding"
    }
}
