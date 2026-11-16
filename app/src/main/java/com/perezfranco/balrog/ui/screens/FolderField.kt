package com.perezfranco.balrog.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Chooses the folder an item is filed under: type a new name, or pick one of
 * the [existing] folders from the drop-down. Empty means no folder.
 */
@Composable
fun FolderField(
    value: String,
    onValueChange: (String) -> Unit,
    existing: List<String>,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Folder") },
            placeholder = { Text("None") },
            singleLine = true,
            trailingIcon = {
                if (existing.isNotEmpty()) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose a folder")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    menuOpen = false
                    onValueChange("")
                },
            )
            existing.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder) },
                    onClick = {
                        menuOpen = false
                        onValueChange(folder)
                    },
                )
            }
        }
    }
}
