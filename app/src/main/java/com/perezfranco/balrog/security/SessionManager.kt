package com.perezfranco.balrog.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey

/**
 * Holds the unlocked vault key in memory only. Locking drops the key; every
 * screen observing [isUnlocked] falls back to the unlock flow.
 */
object SessionManager {

    /** Lock the vault after this long in the background. */
    const val BACKGROUND_LOCK_TIMEOUT_MS = 60_000L

    @Volatile
    private var dek: SecretKey? = null

    private var backgroundedAtElapsed: Long? = null

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun unlock(key: SecretKey) {
        dek = key
        _isUnlocked.value = true
    }

    fun requireKey(): SecretKey =
        dek ?: throw IllegalStateException("Vault is locked")

    fun keyOrNull(): SecretKey? = dek

    fun lock() {
        dek = null
        backgroundedAtElapsed = null
        _isUnlocked.value = false
    }

    /** Called when the app moves to the background. */
    fun onAppBackgrounded() {
        if (dek != null) backgroundedAtElapsed = SystemClock.elapsedRealtime()
    }

    /** Called when the app returns to the foreground; locks if the grace period expired. */
    fun onAppForegrounded() {
        val since = backgroundedAtElapsed
        backgroundedAtElapsed = null
        if (since != null &&
            SystemClock.elapsedRealtime() - since > BACKGROUND_LOCK_TIMEOUT_MS
        ) {
            lock()
        }
    }
}
