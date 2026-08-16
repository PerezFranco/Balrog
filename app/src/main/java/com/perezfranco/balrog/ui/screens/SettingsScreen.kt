package com.perezfranco.balrog.ui.screens

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
import com.perezfranco.balrog.security.BiometricHelper
import com.perezfranco.balrog.security.SessionManager
import com.perezfranco.balrog.ui.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VaultViewModel,
    activity: FragmentActivity,
    onBack: () -> Unit,
    onVaultDestroyed: () -> Unit,
) {
    var biometricEnabled by remember {
        mutableStateOf(viewModel.keyManager.isBiometricEnabled())
    }
    var biometricError by remember { mutableStateOf<String?>(null) }
    val biometricSupported = remember { BiometricHelper.canUseBiometrics(activity) }

    var showChangePassword by remember { mutableStateOf(false) }
    var showDestroy by remember { mutableStateOf(false) }

    fun enableBiometric() {
        val cipher = try {
            viewModel.keyManager.prepareBiometricEnrollCipher()
        } catch (e: Exception) {
            biometricError = "Could not prepare biometric key"
            return
        }
        BiometricHelper.authenticate(
            activity = activity,
            title = "Enable biometric unlock",
            subtitle = "Confirm your identity to protect the vault key",
            cipher = cipher,
            onSuccess = { authorized ->
                viewModel.keyManager.finishBiometricEnroll(
                    authorized, SessionManager.requireKey()
                )
                biometricEnabled = true
                biometricError = null
            },
            onFailure = { message -> if (message != null) biometricError = message },
        )
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
                        if (biometricSupported)
                            "Open the vault with fingerprint or face"
                        else
                            "No strong biometrics available on this device",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = biometricEnabled,
                    enabled = biometricSupported,
                    onCheckedChange = { wanted ->
                        if (wanted) {
                            enableBiometric()
                        } else {
                            viewModel.keyManager.disableBiometric()
                            biometricEnabled = false
                        }
                    },
                )
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
