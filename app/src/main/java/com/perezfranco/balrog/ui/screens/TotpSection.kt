package com.perezfranco.balrog.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.perezfranco.balrog.security.ClipboardHelper
import com.perezfranco.balrog.security.Totp
import kotlinx.coroutines.delay

/**
 * The two-factor part of a password entry: shows the live code if the entry
 * has an authenticator set up, otherwise offers to add one by scanning the
 * site's QR code or typing its setup key.
 *
 * @param totp the entry's stored `otpauth://` URI, or empty.
 * @param onConfigured called with the parsed setup when the user adds one.
 * @param onRemoved called when the user removes the authenticator.
 */
@Composable
fun TotpSection(
    totp: String,
    onConfigured: (Totp.Config) -> Unit,
    onRemoved: () -> Unit,
) {
    val config = remember(totp) { if (totp.isEmpty()) null else Totp.parseOrNull(totp) }
    var error by remember { mutableStateOf<String?>(null) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        try {
            onConfigured(Totp.parse(contents))
            error = null
        } catch (e: Totp.InvalidTotpException) {
            error = if (contents.startsWith("otpauth://", ignoreCase = true)) e.message
            else "That QR code is not a two-factor setup code"
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Two-factor code", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (config != null) {
            TotpCode(config = config, onRemove = { confirmRemove = true })
        } else {
            if (totp.isNotEmpty()) {
                // Stored value no longer parses; don't silently hide it.
                Text(
                    "The saved two-factor setup is unreadable. Add it again to replace it.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row {
                OutlinedButton(onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Point the camera at the site's two-factor QR code")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Scan QR")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showKeyDialog = true }) {
                    Icon(Icons.Filled.Keyboard, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Enter key")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Turn this entry into its own authenticator: when the site offers " +
                    "two-factor setup, scan its QR code or type the setup key.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showKeyDialog) {
        SetupKeyDialog(
            onDismiss = { showKeyDialog = false },
            onConfigured = {
                showKeyDialog = false
                error = null
                onConfigured(it)
            },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove two-factor code?") },
            text = {
                Text(
                    "Balrog will stop generating codes for this entry once you " +
                        "save. If the site still requires them, you could be locked " +
                        "out — turn off two-factor on the site first."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemoved()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }
}

/** The current code, ticking, with a ring that drains as it nears expiry. */
@Composable
private fun TotpCode(config: Totp.Config, onRemove: () -> Unit) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis(), config) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    val code = remember(config, now / 1000 / config.periodSeconds) {
        Totp.generate(config, now)
    }
    val remaining = Totp.secondsRemaining(config, now)
    val expiring = remaining <= 5

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        ) {
            CircularProgressIndicator(
                progress = { remaining / config.periodSeconds.toFloat() },
                color = if (expiring) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatCode(code),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (expiring) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text("${remaining}s left", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = {
                ClipboardHelper.copySecret(context, "two-factor code", code)
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy two-factor code")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove two-factor code")
            }
        }
    }
}

/** "123456" → "123 456", "12345678" → "1234 5678". */
internal fun formatCode(code: String): String {
    val half = (code.length + 1) / 2
    return code.take(half) + " " + code.drop(half)
}

@Composable
private fun SetupKeyDialog(
    onDismiss: () -> Unit,
    onConfigured: (Totp.Config) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter setup key") },
        text = {
            Column {
                Text(
                    "Type or paste the key the site shows next to its QR code " +
                        "(often under \"Can't scan?\"). An otpauth:// link works too.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("Setup key") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = {
                    try {
                        onConfigured(Totp.parse(input))
                    } catch (e: Totp.InvalidTotpException) {
                        error = e.message
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
