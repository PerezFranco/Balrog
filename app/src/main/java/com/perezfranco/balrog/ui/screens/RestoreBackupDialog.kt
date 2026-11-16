package com.perezfranco.balrog.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.perezfranco.balrog.ui.VaultViewModel
import com.perezfranco.balrog.ui.VaultViewModel.BackupResult

/** MIME types offered when picking a backup; providers rarely know ".balrog". */
val BACKUP_OPEN_TYPES = arrayOf("*/*")

/**
 * Asks for the password of the backup file at [source] and restores it.
 *
 * @param onRestored called with the number of items added and the number
 *        skipped because the vault already had them.
 */
@Composable
fun RestoreBackupDialog(
    viewModel: VaultViewModel,
    source: Uri,
    onDismiss: () -> Unit,
    onRestored: (imported: Int, skipped: Int) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Restore backup") },
        text = {
            Column {
                Text(
                    "Enter the master password that was in use when this " +
                        "backup was made.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                PasswordField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = "Backup's master password",
                    isError = error != null,
                    supportingText = error,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && password.isNotEmpty(),
                onClick = {
                    working = true
                    viewModel.restoreBackup(source, password.toCharArray()) { result ->
                        working = false
                        when (result) {
                            is BackupResult.Success -> onRestored(result.count, result.skipped)
                            BackupResult.WrongPassword ->
                                error = "That password does not open this backup"
                            is BackupResult.Failure -> error = result.message
                        }
                    }
                },
            ) { Text(if (working) "Restoring…" else "Restore") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") }
        },
    )
}
