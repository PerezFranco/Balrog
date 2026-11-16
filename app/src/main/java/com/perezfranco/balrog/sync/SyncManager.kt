package com.perezfranco.balrog.sync

import android.content.Context
import android.util.Base64
import com.perezfranco.balrog.crypto.CryptoEngine
import com.perezfranco.balrog.crypto.VaultKeyManager
import com.perezfranco.balrog.data.VaultRepository
import com.perezfranco.balrog.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Keeps this device's vault in step with the copy on the user's own website
 * (see server/balrog-sync.php), and through it with their other devices.
 *
 * Only ciphertext leaves the device: the server stores the same encrypted
 * vault file a backup is. Syncing needs the unlocked vault key — to check
 * that the server's copy is genuine and to seal what is sent — so it runs
 * when the vault is unlocked, after every change, and on request. The sync
 * token is itself kept encrypted under the vault key.
 */
class SyncManager(
    context: Context,
    private val keyManager: VaultKeyManager,
    private val repository: VaultRepository,
    /** Called when a sync brought in changes made on another device. */
    private val onPulled: () -> Unit,
) {
    data class Status(
        val enabled: Boolean = false,
        val url: String = "",
        val syncing: Boolean = false,
        /** When this device last agreed with the server, or 0. */
        val lastSuccessAt: Long = 0,
        /** Why the most recent sync failed, or null if it worked. */
        val lastError: String? = null,
    )

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Conflated: a burst of changes results in one sync of the final state.
    private val requests = Channel<Unit>(Channel.CONFLATED)

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        scope.launch { for (request in requests) runSync() }
    }

    /** Turns sync on (the vault must be unlocked) and runs the first sync. */
    fun enable(url: String, token: String) {
        val sealedToken = CryptoEngine.encrypt(
            SessionManager.requireKey(), token.trim().encodeToByteArray()
        )
        prefs.edit()
            .clear()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, Base64.encodeToString(sealedToken, Base64.NO_WRAP))
            .apply()
        _status.value = readStatus()
        requests.trySend(Unit)
    }

    /** Stops syncing. Nothing is deleted, here or on the server. */
    fun disable() {
        prefs.edit().clear().apply()
        _status.value = readStatus()
    }

    /** Call after anything changes the vault's contents or master password. */
    fun onVaultChanged() = syncNow()

    /** Call when the vault is unlocked: catches up with the other devices. */
    fun onUnlocked() = syncNow()

    fun syncNow() {
        if (_status.value.enabled) requests.trySend(Unit)
    }

    private fun runSync() {
        val url = prefs.getString(KEY_URL, null) ?: return
        val dek = SessionManager.keyOrNull() ?: return // locked: next unlock syncs
        _status.value = _status.value.copy(syncing = true)
        val error: String? = try {
            val token = CryptoEngine.decrypt(
                dek, Base64.decode(prefs.getString(KEY_TOKEN, ""), Base64.NO_WRAP)
            ).decodeToString()
            val outcome = VaultSync.sync(SyncClient(url, token), dek, device)
            if (outcome.pulled) onPulled()
            null
        } catch (e: SyncClient.SyncException) {
            e.message
        } catch (e: VaultSync.DifferentVaultException) {
            "The server holds a different vault. Turn sync off, and either restore " +
                "from the server on a fresh install or point sync at an empty address."
        } catch (e: IllegalStateException) {
            null // locked mid-way; the next unlock syncs
        } catch (e: Exception) {
            "Sync failed unexpectedly (${e.javaClass.simpleName})"
        }
        val edit = prefs.edit()
        if (error == null) {
            edit.putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).remove(KEY_LAST_ERROR)
        } else {
            edit.putString(KEY_LAST_ERROR, error)
        }
        edit.apply()
        _status.value = readStatus()
    }

    /** This device, as the shared sync logic sees it. */
    private val device = object : VaultSync.Device {
        override fun state(): VaultMerge.State = runBlocking {
            VaultMerge.State(
                checkNotNull(keyManager.exportKeyMaterial()) { "No vault" },
                repository.exportRecords(),
                repository.exportTombstones(),
            )
        }

        override fun apply(merged: VaultMerge.State) = runBlocking {
            repository.applyMerged(merged)
            // The master password was changed on another device.
            val local = checkNotNull(keyManager.exportKeyMaterial()) { "No vault" }
            if (merged.keyMaterial.updatedAt > local.updatedAt) {
                keyManager.replaceKeyMaterial(merged.keyMaterial)
            }
        }
    }

    private fun readStatus() = Status(
        enabled = prefs.contains(KEY_URL),
        url = prefs.getString(KEY_URL, "").orEmpty(),
        lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0),
        lastError = prefs.getString(KEY_LAST_ERROR, null),
    )

    private companion object {
        const val PREFS_NAME = "balrog_sync"
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
    }
}
