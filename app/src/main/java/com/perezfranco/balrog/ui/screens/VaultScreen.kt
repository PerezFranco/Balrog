package com.perezfranco.balrog.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import com.perezfranco.balrog.data.PasswordEntry
import com.perezfranco.balrog.data.SecureNote
import com.perezfranco.balrog.ui.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    onOpenPassword: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenGenerator: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val items by viewModel.items.collectAsState()
    val query by viewModel.query.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val passwords = items.filterIsInstance<PasswordEntry>()
    val notes = items.filterIsInstance<SecureNote>()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Balrog") },
                actions = {
                    IconButton(onClick = onOpenGenerator) {
                        Icon(Icons.Filled.Casino, contentDescription = "Password generator")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { viewModel.lock() }) {
                        Icon(Icons.Filled.Lock, contentDescription = "Lock vault")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (selectedTab == 0) onOpenPassword(0L) else onOpenNote(0L)
                }
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription =
                        if (selectedTab == 0) "Add password" else "Add note",
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Passwords (${passwords.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Notes (${notes.size})") },
                )
            }
            if (selectedTab == 0) {
                ItemList(
                    empty = "No passwords yet. Tap + to add one.",
                    content = {
                        items(passwords, key = { it.id }) { entry ->
                            ListItem(
                                headlineContent = {
                                    Text(entry.title.ifBlank { "(untitled)" })
                                },
                                supportingContent = {
                                    if (entry.username.isNotBlank()) Text(entry.username)
                                },
                                leadingContent = {
                                    Icon(Icons.Filled.Key, contentDescription = null)
                                },
                                trailingContent = {
                                    if (entry.totp.isNotEmpty()) {
                                        Icon(
                                            Icons.Filled.Shield,
                                            contentDescription = "Has two-factor code",
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { onOpenPassword(entry.id) },
                            )
                        }
                    },
                    isEmpty = passwords.isEmpty(),
                )
            } else {
                ItemList(
                    empty = "No secure notes yet. Tap + to add one.",
                    content = {
                        items(notes, key = { it.id }) { note ->
                            ListItem(
                                headlineContent = {
                                    Text(note.title.ifBlank { "(untitled)" })
                                },
                                leadingContent = {
                                    Icon(Icons.Filled.Description, contentDescription = null)
                                },
                                modifier = Modifier.clickable { onOpenNote(note.id) },
                            )
                        }
                    },
                    isEmpty = notes.isEmpty(),
                )
            }
        }
    }
}

@Composable
private fun ItemList(
    empty: String,
    isEmpty: Boolean,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    if (isEmpty) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(empty, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), content = content)
    }
}
