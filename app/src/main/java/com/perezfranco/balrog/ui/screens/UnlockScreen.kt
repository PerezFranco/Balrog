package com.perezfranco.balrog.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.perezfranco.balrog.security.BiometricHelper
import com.perezfranco.balrog.security.SessionManager
import com.perezfranco.balrog.ui.VaultViewModel

@Composable
fun UnlockScreen(
    viewModel: VaultViewModel,
    activity: FragmentActivity,
    onUnlocked: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val biometricAvailable = remember {
        viewModel.keyManager.isBiometricEnabled() &&
            BiometricHelper.canUseBiometrics(activity)
    }

    fun tryBiometric() {
        val cipher = viewModel.keyManager.prepareBiometricUnlockCipher() ?: run {
            error = "Biometric unlock unavailable — use your master password"
            return
        }
        BiometricHelper.authenticate(
            activity = activity,
            title = "Unlock Balrog",
            subtitle = "Confirm your identity to open the vault",
            cipher = cipher,
            onSuccess = { authorized ->
                val dek = viewModel.keyManager.finishBiometricUnlock(authorized)
                if (dek != null) {
                    SessionManager.unlock(dek)
                    onUnlocked()
                } else {
                    error = "Biometric unlock failed — use your master password"
                }
            },
            onFailure = { message -> if (message != null) error = message },
        )
    }

    // Offer biometrics immediately on arrival, like a system lock screen.
    LaunchedEffect(Unit) {
        if (biometricAvailable) tryBiometric()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Balrog", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text("Vault locked", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        PasswordField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = "Master password",
            isError = error != null,
            supportingText = error,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                working = true
                viewModel.unlockWithPassword(password.toCharArray()) { ok ->
                    working = false
                    if (ok) {
                        password = ""
                        onUnlocked()
                    } else {
                        error = "Wrong master password"
                    }
                }
            },
            enabled = !working && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (working) "Unlocking…" else "Unlock")
        }
        if (biometricAvailable) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { tryBiometric() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Unlock with biometrics")
            }
        }
    }
}
