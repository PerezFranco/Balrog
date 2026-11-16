package com.perezfranco.balrog.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.perezfranco.balrog.security.PassphraseGenerator
import com.perezfranco.balrog.security.WordList
import com.perezfranco.balrog.ui.VaultViewModel

private const val MIN_MASTER_PASSWORD_LENGTH = 10

@Composable
fun SetupScreen(viewModel: VaultViewModel, onVaultCreated: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var suggestion by remember { mutableStateOf<String?>(null) }

    var restoreSource by remember { mutableStateOf<Uri?>(null) }
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { source -> if (source != null) restoreSource = source }

    val tooShort = password.isNotEmpty() && password.length < MIN_MASTER_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != password
    val canCreate = !working &&
        password.length >= MIN_MASTER_PASSWORD_LENGTH && confirm == password

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Balrog", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose a master password or passphrase. It is the only way to open your vault — " +
                "it is never stored, and it cannot be recovered if forgotten.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = "Master password",
            isError = tooShort,
            supportingText = when {
                tooShort -> "At least $MIN_MASTER_PASSWORD_LENGTH characters"
                password.isNotEmpty() -> VaultViewModel.passwordStrengthLabel(password)
                else -> null
            },
        )
        TextButton(onClick = {
            suggestion = PassphraseGenerator.generate(WordList.load(context), wordCount = 6)
        }) {
            Text(if (suggestion == null) "Suggest a passphrase" else "Suggest another")
        }
        suggestion?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Text(
                "Six random words: strong, and easier to remember than it looks. " +
                    "Type it above if you want to use it.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it },
            label = "Confirm master password",
            isError = mismatch,
            supportingText = if (mismatch) "Passwords do not match" else null,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                working = true
                viewModel.createVault(password.toCharArray()) { onVaultCreated() }
            },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (working) "Creating vault…" else "Create vault")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { restorePicker.launch(BACKUP_OPEN_TYPES) }, enabled = !working) {
            Text("Restore from a backup instead")
        }
    }

    restoreSource?.let { source ->
        RestoreBackupDialog(
            viewModel = viewModel,
            source = source,
            onDismiss = { restoreSource = null },
            onRestored = { _, _ ->
                restoreSource = null
                onVaultCreated()
            },
        )
    }
}
