package com.perezfranco.balrog.ui.screens

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.perezfranco.balrog.crypto.VaultBackup
import com.perezfranco.balrog.ui.VaultViewModel.BackupResult
import java.time.LocalDate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.perezfranco.balrog.crypto.BiometricMode
import com.perezfranco.balrog.crypto.SecondFactor
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.width
import com.perezfranco.balrog.security.BiometricHelper
import com.perezfranco.balrog.security.BiometricUnlock
import com.perezfranco.balrog.ui.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VaultViewModel,
    activity: FragmentActivity,
    onBack: () -> Unit,
    onVaultDestroyed: () -> Unit,
) {
    val keyManager = viewModel.keyManager
    var biometricMode by remember { mutableStateOf(keyManager.biometricMode()) }
    var biometricError by remember { mutableStateOf<String?>(null) }
    val capability = remember { BiometricHelper.capability(activity) }
    // Asked before weaker biometrics are accepted, since it is a real trade-off.
    var confirmAnyMode by remember { mutableStateOf(false) }

    // Backup: file pickers hand back a location; the work happens after.
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupFailed by remember { mutableStateOf(false) }
    var restoreSource by remember { mutableStateOf<Uri?>(null) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        if (destination != null) {
            viewModel.exportBackup(destination) { result ->
                backupFailed = result !is BackupResult.Success
                backupMessage = when (result) {
                    is BackupResult.Success ->
                        "Backup saved (${result.count} ${plural(result.count, "item")})."
                    is BackupResult.Failure -> result.message
                    BackupResult.WrongPassword -> null
                }
            }
        }
    }
    val autoBackup by viewModel.backupManager.status.collectAsState()
    val autoBackupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        if (destination != null && !viewModel.backupManager.enableAutoBackup(destination)) {
            backupFailed = true
            backupMessage = "That location can't be used for automatic backups. " +
                "Try another folder or app."
        }
    }
    val sync by viewModel.syncManager.status.collectAsState()
    var syncUrl by remember { mutableStateOf("") }
    var syncToken by remember { mutableStateOf("") }

    var importMessage by remember { mutableStateOf<String?>(null) }
    var importFailed by remember { mutableStateOf(false) }
    val bitwardenPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { source ->
        if (source != null) {
            importMessage = "Importing…"
            importFailed = false
            viewModel.importBitwarden(source) { result ->
                importFailed = result !is BackupResult.Success
                importMessage = when (result) {
                    is BackupResult.Success ->
                        "Imported ${result.count} ${plural(result.count, "item")}" +
                            (if (result.skipped > 0) {
                                ", skipped ${result.skipped} already in the vault."
                            } else ".") +
                            " Now delete the export file — it is not encrypted."
                    is BackupResult.Failure -> result.message
                    BackupResult.WrongPassword -> null
                }
            }
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { source -> if (source != null) restoreSource = source }

    var showChangePassword by remember { mutableStateOf(false) }
    var showDestroy by remember { mutableStateOf(false) }

    var secondFactor by remember { mutableStateOf(keyManager.biometricSecondFactor()) }
    // Set while the user is choosing a PIN or pattern: the enrollment to do after.
    var choosing by remember { mutableStateOf<Pair<BiometricMode, SecondFactor>?>(null) }

    fun refreshBiometricState() {
        biometricMode = keyManager.biometricMode()
        secondFactor = keyManager.biometricSecondFactor()
    }

    // Every change to biometric settings is a fresh enrollment: a new key,
    // and a biometric prompt to confirm it is really the owner asking.
    fun enroll(mode: BiometricMode, factor: SecondFactor?, secret: CharArray?) {
        viewModel.prepareBiometricPayload(factor, secret) { payload ->
            BiometricUnlock.enroll(
                activity = activity,
                keyManager = keyManager,
                mode = mode,
                payload = payload,
                onEnrolled = {
                    refreshBiometricState()
                    biometricError = null
                },
                onFailure = { message ->
                    refreshBiometricState()
                    if (message != null) biometricError = message
                },
            )
        }
    }

    /** Enrolls in [mode], first asking for the PIN or pattern if [factor] needs one. */
    fun enableBiometric(mode: BiometricMode, factor: SecondFactor? = secondFactor) {
        if (factor != null) choosing = mode to factor else enroll(mode, null, null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Security", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Biometric unlock")
                    Text(
                        when {
                            capability.any -> "Open the vault with ${capability.label}"
                            capability.noneEnrolled ->
                                "Add a fingerprint or face in Android settings first"
                            else -> "No biometric hardware available on this device"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = biometricMode != null,
                    enabled = capability.any || biometricMode != null,
                    onCheckedChange = { wanted ->
                        when {
                            !wanted -> {
                                keyManager.disableBiometric()
                                refreshBiometricState()
                            }
                            capability.strong -> enableBiometric(BiometricMode.STRONG)
                            // Only a weaker biometric (typically face) is enrolled.
                            else -> confirmAnyMode = true
                        }
                    },
                )
            }
            if (capability.any) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow weaker biometrics")
                        Text(
                            "Needed for face unlock on most phones, where Android " +
                                "rates face recognition below \"strong\". Off: only " +
                                "strong biometrics, enforced by secure hardware.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = biometricMode == BiometricMode.ANY,
                        // Without a strong biometric there is nothing to fall back to.
                        enabled = biometricMode != BiometricMode.ANY || capability.strong,
                        onCheckedChange = { wanted ->
                            if (wanted) confirmAnyMode = true
                            else enableBiometric(BiometricMode.STRONG)
                        },
                    )
                }
            }
            biometricMode?.let { mode ->
                Spacer(Modifier.height(12.dp))
                Text("Second step")
                Text(
                    "Ask for a PIN or a pattern after your ${capability.label}. " +
                        "Protects the vault if someone gets hold of your finger " +
                        "or face — or a copy of them.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    listOf(
                        null to "None",
                        SecondFactor.PIN to "PIN",
                        SecondFactor.PATTERN to "Pattern",
                    ).forEach { (factor, name) ->
                            FilterChip(
                                selected = secondFactor == factor,
                                // Tapping the current PIN/pattern lets you change it.
                                onClick = {
                                    if (factor != null || secondFactor != null) {
                                        enableBiometric(mode, factor)
                                    }
                                },
                                label = { Text(name) },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                }
            }
            biometricError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { showChangePassword = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Change master password")
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "A backup is one encrypted file holding everything in the vault. " +
                    "Keep it somewhere other than this phone. It opens only with " +
                    "the master password you have today — even if you change " +
                    "the password later.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    backupMessage = null
                    exportPicker.launch(
                        "balrog-backup-${LocalDate.now()}.${VaultBackup.FILE_EXTENSION}"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save a backup")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    backupMessage = null
                    importPicker.launch(BACKUP_OPEN_TYPES)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import from a backup")
            }
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic backup")
                    Text(
                        if (autoBackup.enabled) {
                            "Keeping \"${autoBackup.destinationName}\" up to date. " +
                                if (autoBackup.lastSuccessAt > 0) {
                                    "Last saved " + DateUtils.getRelativeTimeSpanString(
                                        autoBackup.lastSuccessAt
                                    ) + "."
                                } else "Not saved yet."
                        } else {
                            "Keeps one backup file up to date every time the vault " +
                                "changes. Pick a folder that syncs — Google Drive, " +
                                "OneDrive — to have it leave the phone on its own."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    autoBackup.lastError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Switch(
                    checked = autoBackup.enabled,
                    onCheckedChange = { wanted ->
                        backupMessage = null
                        if (wanted) {
                            autoBackupPicker.launch("balrog-backup.${VaultBackup.FILE_EXTENSION}")
                        } else {
                            viewModel.backupManager.disableAutoBackup()
                        }
                    },
                )
            }
            backupMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    color = if (backupFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))
            Text("Sync", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (sync.enabled) {
                Text(sync.url, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        sync.syncing -> "Syncing…"
                        sync.lastSuccessAt > 0 -> "In sync as of " +
                            DateUtils.getRelativeTimeSpanString(sync.lastSuccessAt) + "."
                        else -> "Not synced yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                sync.lastError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { viewModel.syncManager.syncNow() }, enabled = !sync.syncing) {
                        Text("Sync now")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.syncManager.disable() }) {
                        Text("Turn off")
                    }
                }
            } else {
                Text(
                    "Keeps this vault in step with a copy on your own website, so " +
                        "you can also use it from a computer. Only the encrypted " +
                        "vault leaves the phone. Upload balrog-sync.php to your site " +
                        "first, then enter its address and the token you set in it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = syncUrl,
                    onValueChange = { syncUrl = it },
                    label = { Text("Address of balrog-sync.php") },
                    placeholder = { Text("https://example.com/balrog/balrog-sync.php") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = syncToken,
                    onValueChange = { syncToken = it },
                    label = "Sync token",
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.syncManager.enable(syncUrl, syncToken)
                        syncToken = ""
                    },
                    enabled = syncUrl.trim().startsWith("https://") && syncToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Turn on sync")
                }
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))
            Text("Import from Bitwarden", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "In Bitwarden, open Settings → Vault options → Export vault, " +
                    "choose the file format \".json\" and save the file. Then pick " +
                    "it here. Logins come across with their two-factor codes; " +
                    "cards and identities become secure notes.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    importMessage = null
                    bitwardenPicker.launch(BACKUP_OPEN_TYPES)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose Bitwarden export file")
            }
            importMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    color = if (importFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))
            Text("Danger zone", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showDestroy = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Destroy vault")
            }
            Text(
                "Permanently deletes every password and note on this device.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    choosing?.let { (mode, factor) ->
        val onConfirm = { secret: CharArray ->
            choosing = null
            enroll(mode, factor, secret)
        }
        when (factor) {
            SecondFactor.PIN -> SetPinDialog(onConfirm, onDismiss = { choosing = null })
            SecondFactor.PATTERN -> SetPatternDialog(onConfirm, onDismiss = { choosing = null })
        }
    }

    if (confirmAnyMode) {
        AlertDialog(
            onDismissRequest = { confirmAnyMode = false },
            title = { Text("Allow weaker biometrics?") },
            text = {
                Text(
                    "Balrog will accept any fingerprint or face enrolled on this " +
                        "device, including face unlock that Android considers easier " +
                        "to fool.\n\nIn this mode the vault key is protected by the " +
                        "device instead of being released by the biometric match " +
                        "itself, and anyone who can add their own face or fingerprint " +
                        "to this phone could open the vault."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAnyMode = false
                    enableBiometric(BiometricMode.ANY)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAnyMode = false }) { Text("Cancel") }
            },
        )
    }

    restoreSource?.let { source ->
        RestoreBackupDialog(
            viewModel = viewModel,
            source = source,
            onDismiss = { restoreSource = null },
            onRestored = { imported, skipped ->
                restoreSource = null
                backupFailed = false
                backupMessage = "Imported $imported ${plural(imported, "item")}" +
                    if (skipped > 0) ", skipped $skipped already in the vault." else "."
            },
        )
    }

    if (showChangePassword) {
        ChangePasswordDialog(
            viewModel = viewModel,
            onDismiss = { showChangePassword = false },
        )
    }

    if (showDestroy) {
        AlertDialog(
            onDismissRequest = { showDestroy = false },
            title = { Text("Destroy vault?") },
            text = {
                Text(
                    "All passwords and secure notes will be permanently deleted " +
                        "from this device. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDestroy = false
                    viewModel.destroyVault { onVaultDestroyed() }
                }) { Text("Destroy everything") }
            },
            dismissButton = {
                TextButton(onClick = { showDestroy = false }) { Text("Cancel") }
            },
        )
    }
}

private fun plural(count: Int, noun: String) = if (count == 1) noun else noun + "s"

@Composable
private fun ChangePasswordDialog(
    viewModel: VaultViewModel,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val canSubmit = !working && current.isNotEmpty() &&
        new.length >= 10 && confirm == new

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change master password") },
        text = {
            Column {
                PasswordField(
                    value = current,
                    onValueChange = { current = it; error = null },
                    label = "Current password",
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = new,
                    onValueChange = { new = it },
                    label = "New password (10+ characters)",
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = "Confirm new password",
                    isError = confirm.isNotEmpty() && confirm != new,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    working = true
                    viewModel.changePassword(
                        current.toCharArray(), new.toCharArray()
                    ) { ok ->
                        working = false
                        if (ok) onDismiss() else error = "Current password is wrong"
                    }
                },
            ) { Text(if (working) "Changing…" else "Change") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
