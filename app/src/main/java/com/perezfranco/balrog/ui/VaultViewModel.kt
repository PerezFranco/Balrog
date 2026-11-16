package com.perezfranco.balrog.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.perezfranco.balrog.BalrogApplication
import com.perezfranco.balrog.crypto.SecondFactor
import com.perezfranco.balrog.crypto.VaultBackup
import com.perezfranco.balrog.crypto.VaultKeyManager
import com.perezfranco.balrog.crypto.VaultKeyManager.BiometricOpenResult
import com.perezfranco.balrog.data.PasswordEntry
import com.perezfranco.balrog.importer.BitwardenImport
import com.perezfranco.balrog.data.VaultItem
import com.perezfranco.balrog.security.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BalrogApplication
    val keyManager get() = app.keyManager
    private val repository get() = app.repository
    val backupManager get() = app.backupManager

    val isUnlocked: StateFlow<Boolean> = SessionManager.isUnlocked

    private val searchQuery = MutableStateFlow("")
    val query: StateFlow<String> = searchQuery.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<VaultItem>> = SessionManager.isUnlocked
        .flatMapLatest { unlocked ->
            if (unlocked) repository.observeItems() else flowOf(emptyList())
        }
        .combine(searchQuery) { items, q ->
            if (q.isBlank()) items
            else items.filter { item ->
                item.title.contains(q, ignoreCase = true) ||
                    (item is PasswordEntry &&
                        (item.username.contains(q, true) || item.url.contains(q, true)))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        searchQuery.value = q
    }

    // ---- Vault lifecycle -----------------------------------------------------

    fun vaultExists(): Boolean = keyManager.vaultExists()

    /** Creates the vault; runs the KDF off the main thread. */
    fun createVault(password: CharArray, onDone: () -> Unit) {
        viewModelScope.launch {
            val dek = withContext(Dispatchers.Default) { keyManager.createVault(password) }
            password.fill('\u0000')
            SessionManager.unlock(dek)
            onDone()
        }
    }

    /** Attempts a password unlock; [onResult] receives success. */
    fun unlockWithPassword(password: CharArray, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val dek = withContext(Dispatchers.Default) {
                keyManager.unlockWithPassword(password)
            }
            password.fill('\u0000')
            if (dek != null) SessionManager.unlock(dek)
            onResult(dek != null)
        }
    }

    fun changePassword(
        current: CharArray,
        new: CharArray,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) {
                keyManager.changePassword(current, new)
            }
            current.fill('\u0000')
            new.fill('\u0000')
            onResult(ok)
        }
    }

    /** Builds the payload for biometric enrollment (a KDF, if there is a PIN). */
    fun prepareBiometricPayload(
        factor: SecondFactor?,
        pin: CharArray?,
        onReady: (VaultKeyManager.BiometricPayload) -> Unit,
    ) {
        viewModelScope.launch {
            val dek = SessionManager.keyOrNull() ?: return@launch
            val payload = withContext(Dispatchers.Default) {
                keyManager.prepareBiometricPayload(dek, factor, pin)
            }
            pin?.fill('\u0000')
            onReady(payload)
        }
    }

    /**
     * Finishes a biometric unlock: [released] is what the biometric step
     * produced, [pin] the PIN if one is required. Unlocks the session on success.
     */
    fun finishBiometricUnlock(
        released: ByteArray,
        pin: CharArray?,
        onResult: (BiometricOpenResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                keyManager.openBiometricPayload(released, pin)
            }
            pin?.fill('\u0000')
            if (result is BiometricOpenResult.Unlocked) {
                released.fill(0)
                SessionManager.unlock(result.dek)
            }
            onResult(result)
        }
    }

    fun lock() = SessionManager.lock()

    // ---- Item CRUD -----------------------------------------------------------

    suspend fun getItem(id: Long): VaultItem? = repository.getItem(id)

    fun saveItem(item: VaultItem, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.saveItem(item)
            backupManager.onVaultChanged()
            onDone()
        }
    }

    fun deleteItem(id: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteItem(id)
            backupManager.onVaultChanged()
            onDone()
        }
    }

    // ---- Backup ----------------------------------------------------------------

    sealed interface BackupResult {
        /** Export: [count] items written. Restore: [count] added, [skipped] already present. */
        data class Success(val count: Int, val skipped: Int = 0) : BackupResult
        data object WrongPassword : BackupResult
        data class Failure(val message: String) : BackupResult
    }

    /** Writes an encrypted backup of the whole vault to [destination]. */
    fun exportBackup(destination: Uri, onResult: (BackupResult) -> Unit) {
        viewModelScope.launch {
            val result = try {
                BackupResult.Success(backupManager.writeTo(destination))
            } catch (e: Exception) {
                BackupResult.Failure("Could not write the backup file")
            }
            onResult(result)
        }
    }

    /**
     * Reads the backup at [source] and opens it with [password] (the master
     * password it was made under). With no vault on this device yet, the
     * backup becomes the vault; otherwise its items are merged into the
     * unlocked vault.
     */
    fun restoreBackup(source: Uri, password: CharArray, onResult: (BackupResult) -> Unit) {
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openInputStream(source)
                        ?: throw IOException("Cannot open the file")
                    val bytes = stream.use { it.readNBytesCompat(MAX_BACKUP_BYTES + 1) }
                    if (bytes.size > MAX_BACKUP_BYTES) {
                        throw VaultBackup.InvalidBackupException("This is not a Balrog backup file")
                    }
                    val backup = VaultBackup.parse(bytes.decodeToString())
                    val backupKey = VaultBackup.open(backup, password)
                    if (!keyManager.vaultExists()) {
                        keyManager.adoptVault(backup.keyMaterial)
                        SessionManager.unlock(backupKey)
                    }
                    val summary = repository.importRecords(backup.records, backupKey)
                    if (summary.imported > 0) backupManager.onVaultChanged()
                    BackupResult.Success(summary.imported, summary.skipped)
                }
            } catch (e: VaultBackup.WrongPasswordException) {
                BackupResult.WrongPassword
            } catch (e: VaultBackup.InvalidBackupException) {
                BackupResult.Failure(e.message ?: "The backup file is damaged")
            } catch (e: Exception) {
                BackupResult.Failure("Could not read the backup file")
            }
            password.fill('\u0000')
            onResult(result)
        }
    }

    /** Imports a Bitwarden export file (.json or .csv) into the unlocked vault. */
    fun importBitwarden(source: Uri, onResult: (BackupResult) -> Unit) {
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openInputStream(source)
                        ?: throw IOException("Cannot open the file")
                    val bytes = stream.use { it.readNBytesCompat(MAX_BACKUP_BYTES + 1) }
                    if (bytes.size > MAX_BACKUP_BYTES) {
                        throw BitwardenImport.ImportException("This file is too large")
                    }
                    val summary = repository.importItems(
                        BitwardenImport.parse(bytes.decodeToString())
                    )
                    if (summary.imported > 0) backupManager.onVaultChanged()
                    BackupResult.Success(summary.imported, summary.skipped)
                }
            } catch (e: BitwardenImport.ImportException) {
                BackupResult.Failure(e.message ?: "This is not a Bitwarden export file")
            } catch (e: Exception) {
                BackupResult.Failure("Could not read the file")
            }
            onResult(result)
        }
    }

    /** Wipes every record and the key material — a factory reset of the vault. */
    fun destroyVault(onDone: () -> Unit) {
        viewModelScope.launch {
            // First, so the emptied vault is never written over the backup.
            backupManager.disableAutoBackup()
            repository.deleteAll()
            keyManager.destroyVault()
            SessionManager.lock()
            onDone()
        }
    }

    companion object {
        private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

        /** Reads at most [limit] bytes (InputStream.readNBytes needs API 33). */
        private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        fun passwordStrengthLabel(password: String): String = when {
            password.length < 8 -> "Too short"
            password.length < 12 -> "Weak"
            password.length < 16 -> "Good"
            else -> "Strong"
        }
    }
}
