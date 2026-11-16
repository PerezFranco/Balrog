package com.perezfranco.balrog.security

import androidx.fragment.app.FragmentActivity
import com.perezfranco.balrog.crypto.BiometricMode
import com.perezfranco.balrog.crypto.VaultKeyManager

/**
 * The two biometric flows — turning biometric unlock on, and using it —
 * for either [BiometricMode]. Both stop short of the vault key itself:
 * enrolling takes a payload from [VaultKeyManager.prepareBiometricPayload],
 * and unlocking hands what the biometric released to
 * [VaultKeyManager.openBiometricPayload], which is where a PIN, if required,
 * comes in. Failure callbacks receive a message to show,
 * or null if the user simply dismissed the prompt.
 */
object BiometricUnlock {

    fun enroll(
        activity: FragmentActivity,
        keyManager: VaultKeyManager,
        mode: BiometricMode,
        payload: VaultKeyManager.BiometricPayload,
        onEnrolled: () -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        val title = "Enable biometric unlock"
        val subtitle = "Confirm your identity to protect the vault key"
        when (mode) {
            BiometricMode.STRONG -> {
                val cipher = try {
                    keyManager.prepareBiometricEnrollCipher()
                } catch (e: Exception) {
                    onFailure("Could not prepare the biometric key")
                    return
                }
                BiometricHelper.authenticateStrong(
                    activity, title, subtitle, "Cancel", cipher,
                    onSuccess = { authorized ->
                        try {
                            keyManager.finishBiometricEnroll(authorized, payload)
                            onEnrolled()
                        } catch (e: Exception) {
                            keyManager.disableBiometric()
                            onFailure("Could not enable biometric unlock")
                        }
                    },
                    onFailure = { message ->
                        keyManager.disableBiometric()
                        onFailure(message)
                    },
                )
            }
            BiometricMode.ANY -> BiometricHelper.authenticateAny(
                activity, title, subtitle, "Cancel",
                onSuccess = {
                    try {
                        keyManager.enrollAnyBiometric(payload)
                        onEnrolled()
                    } catch (e: Exception) {
                        keyManager.disableBiometric()
                        onFailure("Could not enable biometric unlock")
                    }
                },
                onFailure = onFailure,
            )
        }
    }

    fun unlock(
        activity: FragmentActivity,
        keyManager: VaultKeyManager,
        onReleased: (ByteArray) -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        val title = "Unlock Balrog"
        val subtitle = "Confirm your identity to open the vault"
        val unavailable = "Biometric unlock unavailable — use your master password"
        val failed = "Biometric unlock failed — use your master password"
        when (keyManager.biometricMode()) {
            BiometricMode.STRONG -> {
                val cipher = keyManager.prepareBiometricUnlockCipher()
                    ?: return onFailure(unavailable)
                BiometricHelper.authenticateStrong(
                    activity, title, subtitle, "Use master password", cipher,
                    onSuccess = { authorized ->
                        val released = keyManager.finishBiometricUnlock(authorized)
                        if (released != null) onReleased(released) else onFailure(failed)
                    },
                    onFailure = onFailure,
                )
            }
            BiometricMode.ANY -> BiometricHelper.authenticateAny(
                activity, title, subtitle, "Use master password",
                onSuccess = {
                    val released = keyManager.unlockWithAnyBiometric()
                    if (released != null) onReleased(released) else onFailure(failed)
                },
                onFailure = onFailure,
            )
            null -> onFailure(unavailable)
        }
    }
}
