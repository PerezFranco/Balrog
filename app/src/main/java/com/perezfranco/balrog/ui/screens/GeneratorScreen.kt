package com.perezfranco.balrog.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.perezfranco.balrog.security.ClipboardHelper
import com.perezfranco.balrog.security.PasswordGenerator
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var length by remember { mutableFloatStateOf(20f) }
    var useLower by remember { mutableStateOf(true) }
    var useUpper by remember { mutableStateOf(true) }
    var useDigits by remember { mutableStateOf(true) }
    var useSymbols by remember { mutableStateOf(true) }

    fun generate(): String = PasswordGenerator.generate(
        length = length.roundToInt(),
        useLowercase = useLower,
        useUppercase = useUpper,
        useDigits = useDigits,
        useSymbols = useSymbols,
    )

    var password by remember { mutableStateOf(generate()) }
    val anyClassEnabled = useLower || useUpper || useDigits || useSymbols

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password generator") },
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
        ) {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    password,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = {
                        ClipboardHelper.copySecret(context, "password", password)
                    },
                    enabled = anyClassEnabled,
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { if (anyClassEnabled) password = generate() },
                    enabled = anyClassEnabled,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Regenerate")
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Length: ${length.roundToInt()}")
            Slider(
                value = length,
                onValueChange = { length = it },
                onValueChangeFinished = { if (anyClassEnabled) password = generate() },
                valueRange = 8f..64f,
            )
            CharClassRow("Lowercase (a–z)", useLower) {
                useLower = it
                if (useLower || useUpper || useDigits || useSymbols) password = generate()
            }
            CharClassRow("Uppercase (A–Z)", useUpper) {
                useUpper = it
                if (useLower || useUpper || useDigits || useSymbols) password = generate()
            }
            CharClassRow("Digits (0–9)", useDigits) {
                useDigits = it
                if (useLower || useUpper || useDigits || useSymbols) password = generate()
            }
            CharClassRow("Symbols (!@#…)", useSymbols) {
                useSymbols = it
                if (useLower || useUpper || useDigits || useSymbols) password = generate()
            }
        }
    }
}

@Composable
private fun CharClassRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}
