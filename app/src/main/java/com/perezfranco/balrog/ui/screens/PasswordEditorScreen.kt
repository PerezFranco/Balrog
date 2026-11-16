package com.perezfranco.balrog.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.perezfranco.balrog.data.PasswordEntry
import com.perezfranco.balrog.security.ClipboardHelper
import com.perezfranco.balrog.security.PassphraseGenerator
import com.perezfranco.balrog.security.PasswordGenerator
import com.perezfranco.balrog.security.WordList
import com.perezfranco.balrog.ui.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordEditorScreen(
    viewModel: VaultViewModel,
    itemId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(itemId == 0L) }
    var title by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var recipe by remember { mutableStateOf("") }
    var deterministicOpen by remember { mutableStateOf(false) }
    var generateMenuOpen by remember { mutableStateOf(false) }
    // New items start in the folder being viewed.
    var folder by remember {
        mutableStateOf(if (itemId == 0L) viewModel.selectedFolder.value.orEmpty() else "")
    }
    val folders by viewModel.folders.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        if (itemId != 0L) {
            (viewModel.getItem(itemId) as? PasswordEntry)?.let {
                title = it.title
                username = it.username
                password = it.password
                url = it.url
                notes = it.notes
                totp = it.totp
                recipe = it.recipe
                folder = it.folder
            }
            loaded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (itemId == 0L) "New password" else "Edit password") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (itemId != 0L) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete entry")
                        }
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
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username / email") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        ClipboardHelper.copySecret(context, "username", username)
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy username")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            PasswordField(
                value = password,
                onValueChange = {
                    // An edited password no longer comes from the recipe.
                    if (it != password) recipe = ""
                    password = it
                },
                label = "Password",
                supportingText = if (recipe.isNotEmpty()) {
                    "From the deterministic generator; its recipe is saved with this entry"
                } else null,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = {
                    ClipboardHelper.copySecret(context, "password", password)
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    OutlinedButton(onClick = { generateMenuOpen = true }) {
                        Icon(Icons.Filled.Casino, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Generate")
                    }
                    DropdownMenu(
                        expanded = generateMenuOpen,
                        onDismissRequest = { generateMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Random password") },
                            onClick = {
                                generateMenuOpen = false
                                password = PasswordGenerator.generate(20)
                                recipe = ""
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Passphrase") },
                            onClick = {
                                generateMenuOpen = false
                                password = PassphraseGenerator.generate(
                                    WordList.load(context), capitalize = true,
                                )
                                recipe = ""
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Deterministic…") },
                            onClick = {
                                generateMenuOpen = false
                                deterministicOpen = true
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TotpSection(
                totp = totp,
                onConfigured = { config ->
                    totp = config.toUri()
                    // A scanned code knows who it is for; use that if we don't.
                    if (title.isBlank()) title = config.issuer
                    if (username.isBlank()) username = config.account
                },
                onRemoved = { totp = "" },
            )
            Spacer(Modifier.height(4.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Website") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            FolderField(value = folder, onValueChange = { folder = it }, existing = folders)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    viewModel.saveItem(
                        PasswordEntry(
                            id = itemId,
                            title = title,
                            username = username,
                            password = password,
                            url = url,
                            notes = notes,
                            totp = totp,
                            recipe = recipe,
                            folder = folder.trim(),
                        )
                    ) { onBack() }
                },
                enabled = loaded &&
                    (title.isNotBlank() || password.isNotBlank() || totp.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }

    if (deterministicOpen) {
        Dialog(
            onDismissRequest = { deterministicOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Deterministic password",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { deterministicOpen = false }) { Text("Cancel") }
                    }
                    Spacer(Modifier.height(8.dp))
                    DeterministicForm(
                        viewModel = viewModel,
                        initialRecipe = recipe,
                        onUse = { generated, usedRecipe ->
                            password = generated
                            recipe = usedRecipe
                            deterministicOpen = false
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("\"${title.ifBlank { "(untitled)" }}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteItem(itemId) { onBack() }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
