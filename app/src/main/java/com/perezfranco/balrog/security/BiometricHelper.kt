package com.perezfranco.balrog.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.perezfranco.balrog.crypto.BiometricMode
import javax.crypto.Cipher

/** Thin wrapper around BiometricPrompt for fingerprint and face unlock. */
object BiometricHelper {

    /** What the device can offer right now. */
    data class Capability(
        /** A Class 3 biometric is enrolled — [BiometricMode.STRONG] will work. */
        val strong: Boolean,
        /** Some biometric (Class 2 or 3) is enrolled — [BiometricMode.ANY] will work. */
        val any: Boolean,
        /** The device has biometric hardware but the user hasn't enrolled anything. */
        val noneEnrolled: Boolean,
        val hasFingerprintSensor: Boolean,
        val hasFaceSensor: Boolean,
    ) {
        /** Human name for what the prompt will accept, e.g. "fingerprint or face". */
        val label: String
            get() = when {
                hasFingerprintSensor && hasFaceSensor -> "fingerprint or face"
                hasFingerprintSensor -> "fingerprint"
                hasFaceSensor -> "face"
                else -> "biometrics"
            }

        fun supports(mode: BiometricMode): Boolean = when (mode) {
            BiometricMode.STRONG -> strong
            BiometricMode.ANY -> any
        }
    }

    fun capability(context: Context): Capability {
        val manager = BiometricManager.from(context)
        val strong = manager.canAuthenticate(BIOMETRIC_STRONG)
        val weak = manager.canAuthenticate(BIOMETRIC_WEAK)
        val pm = context.packageManager
        return Capability(
            strong = strong == BiometricManager.BIOMETRIC_SUCCESS,
            any = weak == BiometricManager.BIOMETRIC_SUCCESS,
            noneEnrolled = weak == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            hasFingerprintSensor = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT),
            hasFaceSensor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                pm.hasSystemFeature(PackageManager.FEATURE_FACE),
        )
    }

    /**
     * [BiometricMode.STRONG]: shows the prompt bound to [cipher]. On success
     * the cipher is authorized for a single crypto operation and passed to
     * [onSuccess]. Only Class 3 biometrics are accepted.
     */
    fun authenticateStrong(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButton: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : Callback(onFailure) {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (authorized != null) onSuccess(authorized) else onFailure(null)
                }
            }
        )
        prompt.authenticate(
            promptInfo(title, subtitle, negativeButton, BIOMETRIC_STRONG),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    /**
     * [BiometricMode.ANY]: shows the prompt accepting whatever biometric the
     * user has enrolled, face or fingerprint, strong or weak.
     */
    fun authenticateAny(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButton: String,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : Callback(onFailure) {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )
        prompt.authenticate(promptInfo(title, subtitle, negativeButton, BIOMETRIC_WEAK))
    }

    private fun promptInfo(
        title: String,
        subtitle: String,
        negativeButton: String,
        authenticators: Int,
    ) =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButton)
            .setAllowedAuthenticators(authenticators)
            // Face unlock would otherwise wait for an extra "Confirm" tap.
            .setConfirmationRequired(false)
            .build()

    /** Reports errors to [onFailure]; the message is null if the user just backed out. */
    private abstract class Callback(
        private val onFailure: (String?) -> Unit,
    ) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            val userCancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                errorCode == BiometricPrompt.ERROR_CANCELED
            onFailure(if (userCancelled) null else errString.toString())
        }
    }
}
