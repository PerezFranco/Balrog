package com.perezfranco.balrog.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
 *  - wrapped by a hardware-backed Android Keystore key that is released by
 *    a biometric prompt (see [BiometricMode]).
 *
 * Unwrapping with the wrong password fails GCM authentication, which doubles
 * as master-password verification — no password hash is ever stored.
 */
/** How biometric unlock is secured; see the notes in [VaultKeyManager]. */
enum class BiometricMode {
    /** Class 3 biometrics only; the Keystore key is hardware-gated on the match. */
    STRONG,

    /** Any enrolled biometric, including Class 2 face unlock; app-enforced. */
    ANY,
}

/** What, if anything, biometric unlock asks for after the biometric itself. */
enum class SecondFactor { PIN, PATTERN }

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

    // ---- Backup ----------------------------------------------------------------

    /** The password-wrapped vault key and its KDF parameters, for a backup file. */
    fun exportKeyMaterial(): VaultBackup.KeyMaterial? {
        val salt = prefs.getString(KEY_SALT, null) ?: return null
        val wrapped = prefs.getString(KEY_WRAPPED_DEK, null) ?: return null
        return VaultBackup.KeyMaterial(
            salt = decode(salt),
            iterations = prefs.getInt(KEY_ITERATIONS, CryptoEngine.KDF_ITERATIONS),
            wrappedDek = decode(wrapped),
        )
    }

    /**
     * Sets this device's vault up from a backup's key material, so the vault
     * opens with the master password the backup was made under.
     */
    fun adoptVault(material: VaultBackup.KeyMaterial) {
        check(!vaultExists()) { "Vault already exists" }
        prefs.edit()
            .putString(KEY_SALT, encode(material.salt))
            .putInt(KEY_ITERATIONS, material.iterations)
            .putString(KEY_WRAPPED_DEK, encode(material.wrappedDek))
            .apply()
    }

    // ---- Biometric unlock ----------------------------------------------------
    //
    // Two modes, chosen by the user in Settings:
    //
    //  STRONG  The Keystore key can only be used after a Class 3 ("strong")
    //          biometric match, enforced in secure hardware: the prompt hands
    //          back a cipher that is authorized for exactly one operation.
    //
    //  ANY     Also accepts Class 2 ("weak") biometrics — which is what face
    //          unlock is on most phones. Android refuses to bind Keystore keys
    //          to those, so the key is device-bound but not biometric-gated;
    //          the prompt is enforced by the app rather than by hardware.

    /** The active biometric mode, or null if biometric unlock is off. */
    fun biometricMode(): BiometricMode? {
        if (!isBiometricEnabled()) return null
        return when (prefs.getString(KEY_BIOMETRIC_MODE, null)) {
            BiometricMode.ANY.name -> BiometricMode.ANY
            else -> BiometricMode.STRONG // vaults enrolled before modes existed
        }
    }

    /**
     * STRONG mode, step 1: returns a cipher ready to wrap the DEK, to be
     * authorized through a BiometricPrompt CryptoObject. Always starts from a
     * fresh Keystore key so a previously invalidated one can't get in the way.
     */
    fun prepareBiometricEnrollCipher(): Cipher {
        deleteKeystoreKey()
        val keystoreKey = createKeystoreKey(BiometricMode.STRONG)
        return Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, keystoreKey)
        }
    }

    /** STRONG mode, step 2: wraps the payload with the cipher the prompt authorized. */
    fun finishBiometricEnroll(authorizedCipher: Cipher, payload: BiometricPayload) {
        storeBiometricWrap(authorizedCipher, payload, BiometricMode.STRONG)
    }

    /**
     * ANY mode: wraps the payload under a device-bound key. Call only after
     * the user has passed a biometric prompt.
     */
    fun enrollAnyBiometric(payload: BiometricPayload) {
        deleteKeystoreKey()
        val keystoreKey = createKeystoreKey(BiometricMode.ANY)
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, keystoreKey)
        }
        storeBiometricWrap(cipher, payload, BiometricMode.ANY)
    }

    /**
     * STRONG mode: returns a cipher ready to unwrap the DEK via BiometricPrompt,
     * or null if the Keystore key is gone or was invalidated (e.g. a new
     * fingerprint was added on the device) — in which case biometric unlock is
     * switched off until the user re-enables it.
     */
    fun prepareBiometricUnlockCipher(): Cipher? {
        if (biometricMode() != BiometricMode.STRONG) return null
        return try {
            unlockCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBiometric()
            null
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /**
     * STRONG mode: completes the biometric step with the cipher authorized by
     * the prompt. The result goes to [openBiometricPayload].
     */
    fun finishBiometricUnlock(authorizedCipher: Cipher): ByteArray? =
        unwrapBiometricPayload(authorizedCipher)

    /**
     * ANY mode: completes the biometric step. Call only after the user has
     * passed a biometric prompt. The result goes to [openBiometricPayload].
     */
    fun unlockWithAnyBiometric(): ByteArray? {
        if (biometricMode() != BiometricMode.ANY) return null
        return try {
            unlockCipher()?.let(::unwrapBiometricPayload)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private fun unlockCipher(): Cipher? {
        val iv = decode(prefs.getString(KEY_BIOMETRIC_IV, null) ?: return null)
        val keystoreKey = loadKeystoreKey() ?: return null
        return Cipher.getInstance(AES_GCM).apply {
            init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(128, iv))
        }
    }

    private fun storeBiometricWrap(cipher: Cipher, payload: BiometricPayload, mode: BiometricMode) {
        val wrapped = cipher.doFinal(payload.bytes)
        val edit = prefs.edit()
            .putString(KEY_BIOMETRIC_IV, encode(cipher.iv))
            .putString(KEY_BIOMETRIC_WRAPPED_DEK, encode(wrapped))
            .putString(KEY_BIOMETRIC_MODE, mode.name)
            .remove(KEY_PIN_FAILURES)
        if (payload.pinSalt != null) {
            edit.putString(KEY_PIN_SALT, encode(payload.pinSalt))
                .putInt(KEY_PIN_ITERATIONS, payload.pinIterations)
                .putString(KEY_PIN_KIND, payload.factor?.name)
        } else {
            edit.remove(KEY_PIN_SALT).remove(KEY_PIN_ITERATIONS).remove(KEY_PIN_KIND)
        }
        edit.apply()
    }

    private fun unwrapBiometricPayload(cipher: Cipher): ByteArray? {
        val wrapped = decode(prefs.getString(KEY_BIOMETRIC_WRAPPED_DEK, null) ?: return null)
        return try {
            cipher.doFinal(wrapped)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    // ---- Biometric + PIN -----------------------------------------------------
    //
    // Optionally the biometric unlock also demands a PIN or a nine-dot pattern
    // (a pattern is treated as a PIN made of its dot numbers). The vault key is
    // then sealed under it (see [PinWrap]) *before* the Keystore wraps it, so
    // what a biometric match releases is still useless without it.

    /** What gets wrapped by the Keystore key: the DEK, PIN-sealed if a PIN is set. */
    class BiometricPayload internal constructor(
        internal val bytes: ByteArray,
        internal val pinSalt: ByteArray?,
        internal val pinIterations: Int,
        internal val factor: SecondFactor?,
    )

    sealed interface BiometricOpenResult {
        class Unlocked(val dek: SecretKey) : BiometricOpenResult
        class WrongPin(val attemptsLeft: Int) : BiometricOpenResult

        /** Too many wrong PINs: biometric unlock has been switched off. */
        data object LockedOut : BiometricOpenResult
    }

    /** The PIN or pattern biometric unlock also asks for, or null if neither. */
    fun biometricSecondFactor(): SecondFactor? {
        if (!isBiometricEnabled() || !prefs.contains(KEY_PIN_SALT)) return null
        return when (prefs.getString(KEY_PIN_KIND, null)) {
            SecondFactor.PATTERN.name -> SecondFactor.PATTERN
            else -> SecondFactor.PIN
        }
    }

    /**
     * Builds what biometric enrollment will protect. [pin] is the PIN, or the
     * encoded pattern, for [factor]. With one this runs a
     * key derivation, so call it off the main thread.
     */
    fun prepareBiometricPayload(
        dek: SecretKey,
        factor: SecondFactor?,
        pin: CharArray?,
    ): BiometricPayload {
        if (factor == null || pin == null) return BiometricPayload(dek.encoded, null, 0, null)
        val sealed = PinWrap.seal(dek.encoded, pin)
        return BiometricPayload(sealed.blob, sealed.salt, sealed.iterations, factor)
    }

    /**
     * Turns what the biometric step released into the vault key, checking the
     * [pin] (or encoded pattern) if one is required. Wrong ones are counted across app restarts;
     * after [PinWrap.MAX_ATTEMPTS] biometric unlock is switched off and only
     * the master password opens the vault. Runs a key derivation when a PIN
     * is required, so call it off the main thread.
     */
    fun openBiometricPayload(released: ByteArray, pin: CharArray?): BiometricOpenResult {
        if (biometricSecondFactor() == null) {
            return BiometricOpenResult.Unlocked(SecretKeySpec(released, "AES"))
        }
        val salt = decode(prefs.getString(KEY_PIN_SALT, null).orEmpty())
        val iterations = prefs.getInt(KEY_PIN_ITERATIONS, PinWrap.KDF_ITERATIONS)
        // Count the attempt before checking it, and synchronously, so killing
        // the app mid-check can't be used to get free guesses.
        val failures = prefs.getInt(KEY_PIN_FAILURES, 0) + 1
        prefs.edit().putInt(KEY_PIN_FAILURES, failures).commit()

        val dekBytes = if (pin == null) null else PinWrap.open(released, salt, iterations, pin)
        if (dekBytes != null) {
            prefs.edit().remove(KEY_PIN_FAILURES).apply()
            return BiometricOpenResult.Unlocked(
                SecretKeySpec(dekBytes, "AES").also { dekBytes.fill(0) }
            )
        }
        if (failures >= PinWrap.MAX_ATTEMPTS) {
            disableBiometric()
            return BiometricOpenResult.LockedOut
        }
        return BiometricOpenResult.WrongPin(PinWrap.MAX_ATTEMPTS - failures)
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
            .remove(KEY_BIOMETRIC_MODE)
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_ITERATIONS)
            .remove(KEY_PIN_KIND)
            .remove(KEY_PIN_FAILURES)
            .apply()
        deleteKeystoreKey()
    }

    private fun deleteKeystoreKey() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(KEYSTORE_ALIAS)
        } catch (ignored: GeneralSecurityException) {
        }
    }

    private fun createKeystoreKey(mode: BiometricMode): SecretKey {
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
        when (mode) {
            BiometricMode.STRONG -> {
                builder.setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setUserAuthenticationParameters(
                        0, KeyProperties.AUTH_BIOMETRIC_STRONG
                    )
                } else {
                    @Suppress("DEPRECATION")
                    builder.setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            BiometricMode.ANY -> {
                // Can't be biometric-gated (see above); at least refuse to
                // work while the phone itself is locked.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setUnlockedDeviceRequired(true)
                }
            }
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
        const val KEY_BIOMETRIC_MODE = "biometric_mode"
        const val KEY_PIN_SALT = "biometric_pin_salt"
        const val KEY_PIN_ITERATIONS = "biometric_pin_iterations"
        const val KEY_PIN_KIND = "biometric_pin_kind"
        const val KEY_PIN_FAILURES = "biometric_pin_failures"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "balrog_biometric_dek_wrapper"
        const val AES_GCM = "AES/GCM/NoPadding"
    }
}
