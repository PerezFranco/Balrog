package com.perezfranco.balrog.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.perezfranco.balrog.crypto.VaultBackup
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
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Writes backup files, and keeps the automatic backup up to date.
 *
 * Automatic backup maintains a single file at a location the user picked
 * once through the system file picker — which may be a cloud folder (Google
 * Drive, OneDrive, …) whose own app then syncs it. Balrog only ever writes a
 * local document; it needs no account, token, or internet permission.
 *
 * The file is rewritten whenever the vault changes. The vault can only
 * change while it is unlocked and in use, so this keeps the backup at least
 * as fresh as any timer would. If a write can't happen (vault locked in the
 * meantime, provider unavailable), it is retried at the next unlock.
 */
class BackupManager(
    context: Context,
    private val keyManager: VaultKeyManager,
    private val repository: VaultRepository,
) {
    data class Status(
        /** Whether automatic backup is on. */
        val enabled: Boolean = false,
        /** File name of the destination, for display. */
        val destinationName: String = "",
        /** When the destination was last written successfully, or 0. */
        val lastSuccessAt: Long = 0,
        /** Why the most recent attempt failed, or null if it succeeded. */
        val lastError: String? = null,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Conflated: a burst of changes results in one write of the final state.
    private val requests = Channel<Unit>(Channel.CONFLATED)

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        scope.launch { for (request in requests) runAutoBackup() }
    }

    /**
     * Writes a backup of the current vault to [destination] and returns the
     * number of items in it. The vault must be unlocked.
     */
    suspend fun writeTo(destination: Uri): Int = withContext(Dispatchers.IO) {
        val dek = SessionManager.requireKey()
        val material = checkNotNull(keyManager.exportKeyMaterial()) { "No vault" }
        val records = repository.exportRecords()
        val bytes = VaultBackup.encode(
            material, records, dek, tombstones = repository.exportTombstones(),
        ).encodeToByteArray()
        val resolver = appContext.contentResolver
        // "wt" truncates, so a shrinking vault can't leave stale bytes behind;
        // a few providers only understand plain "w".
        val stream = try {
            resolver.openOutputStream(destination, "wt")
        } catch (e: FileNotFoundException) {
            resolver.openOutputStream(destination, "w")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(destination, "w")
        }
        if (stream == null) throw IOException("Cannot open the destination")
        stream.use { it.write(bytes) }
        records.size
    }

    /**
     * Turns automatic backup on, targeting [destination] (a document the user
     * just created in the file picker), and writes the first backup.
     *
     * @return false if the location can't be written to later without asking
     *         the user again, in which case nothing is changed.
     */
    fun enableAutoBackup(destination: Uri): Boolean {
        try {
            appContext.contentResolver.takePersistableUriPermission(destination, URI_FLAGS)
        } catch (e: SecurityException) {
            return false
        }
        releaseDestination()
        prefs.edit()
            .clear()
            .putString(KEY_DESTINATION, destination.toString())
            .putString(KEY_DESTINATION_NAME, displayName(destination))
            .putBoolean(KEY_DIRTY, true)
            .apply()
        _status.value = readStatus()
        requests.trySend(Unit)
        return true
    }

    fun disableAutoBackup() {
        releaseDestination()
        prefs.edit().clear().apply()
        _status.value = readStatus()
    }

    /** Call after anything changes the vault's contents or master password. */
    fun onVaultChanged() {
        if (!_status.value.enabled) return
        prefs.edit().putBoolean(KEY_DIRTY, true).apply()
        requests.trySend(Unit)
    }

    /** Call when the vault is unlocked: finishes any backup that is still owed. */
    fun onUnlocked() {
        if (_status.value.enabled && prefs.getBoolean(KEY_DIRTY, false)) {
            requests.trySend(Unit)
        }
    }

    private suspend fun runAutoBackup() {
        val destination = prefs.getString(KEY_DESTINATION, null)?.let(Uri::parse) ?: return
        if (!prefs.getBoolean(KEY_DIRTY, false)) return
        // Locked again already: the change stays marked and is picked up on unlock.
        if (SessionManager.keyOrNull() == null) return
        // Cleared before writing, so a change landing mid-write is not lost.
        prefs.edit().putBoolean(KEY_DIRTY, false).apply()
        try {
            writeTo(destination)
            prefs.edit()
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .apply()
        } catch (e: Exception) {
            val reason = when (e) {
                is SecurityException, is FileNotFoundException ->
                    "The backup file is no longer accessible. Choose a location again."
                is IllegalStateException -> null // locked mid-way; retried on unlock
                else -> "Could not write the backup file. It will be retried."
            }
            val edit = prefs.edit().putBoolean(KEY_DIRTY, true)
            if (reason != null) edit.putString(KEY_LAST_ERROR, reason)
            edit.apply()
        }
        _status.value = readStatus()
    }

    private fun releaseDestination() {
        val old = prefs.getString(KEY_DESTINATION, null) ?: return
        try {
            appContext.contentResolver.releasePersistableUriPermission(Uri.parse(old), URI_FLAGS)
        } catch (ignored: SecurityException) {
        }
    }

    private fun displayName(uri: Uri): String {
        val name = try {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return name ?: "backup file"
    }

    private fun readStatus() = Status(
        enabled = prefs.contains(KEY_DESTINATION),
        destinationName = prefs.getString(KEY_DESTINATION_NAME, "").orEmpty(),
        lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0),
        lastError = prefs.getString(KEY_LAST_ERROR, null),
    )

    private companion object {
        const val PREFS_NAME = "balrog_backup"
        const val KEY_DESTINATION = "destination"
        const val KEY_DESTINATION_NAME = "destination_name"
        const val KEY_DIRTY = "dirty"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
        const val URI_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
