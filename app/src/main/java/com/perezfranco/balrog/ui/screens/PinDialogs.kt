package com.perezfranco.balrog.ui.screens

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.perezfranco.balrog.crypto.PinWrap

private fun String.digitsOnly() = filter { it.isDigit() }.take(12)

/** Asks for the PIN that completes a biometric unlock. */
@Composable
fun EnterPinDialog(
    error: String?,
    working: Boolean,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Enter your PIN") },
        text = {
            PasswordField(
                value = pin,
                onValueChange = { pin = it.digitsOnly() },
                label = "PIN",
                isError = error != null,
                supportingText = error,
                keyboardType = KeyboardType.NumberPassword,
            )
        },
        confirmButton = {
            TextButton(
                enabled = !working && pin.length >= PinWrap.MIN_PIN_LENGTH,
                onClick = {
                    onSubmit(pin.toCharArray())
                    pin = ""
                },
            ) { Text(if (working) "Checking…" else "Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text("Use master password")
            }
        },
    )
}

/** Lets the user choose the PIN that biometric unlock will also require. */
@Composable
fun SetPinDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = pin.isNotEmpty() && pin.length < PinWrap.MIN_PIN_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != pin

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a PIN") },
        text = {
            Column {
                Text(
                    "Unlocking will take your fingerprint or face and then this " +
                        "PIN. After ${PinWrap.MAX_ATTEMPTS} wrong PINs, biometric " +
                        "unlock switches off and only the master password works. " +
                        "Don't reuse your phone's screen-lock PIN.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                PasswordField(
                    value = pin,
                    onValueChange = { pin = it.digitsOnly() },
                    label = "PIN (${PinWrap.MIN_PIN_LENGTH} or more digits)",
                    isError = tooShort,
                    keyboardType = KeyboardType.NumberPassword,
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it.digitsOnly() },
                    label = "Confirm PIN",
                    isError = mismatch,
                    supportingText = if (mismatch) "PINs do not match" else null,
                    keyboardType = KeyboardType.NumberPassword,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length >= PinWrap.MIN_PIN_LENGTH && confirm == pin,
                onClick = { onConfirm(pin.toCharArray()) },
            ) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
