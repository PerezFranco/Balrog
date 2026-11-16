package com.perezfranco.balrog.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import com.perezfranco.balrog.security.PassphraseGenerator
import com.perezfranco.balrog.security.WordList
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

private val SEPARATORS = listOf("-" to "Hyphen", "." to "Period", "_" to "Underscore", " " to "Space")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val words = remember { WordList.load(context) }
    var passphraseMode by remember { mutableStateOf(false) }
    // Bumped by "Regenerate" to force a fresh draw with unchanged options.
    var nonce by remember { mutableIntStateOf(0) }

    // Random-password options
    var length by remember { mutableFloatStateOf(20f) }
    var useLower by remember { mutableStateOf(true) }
    var useUpper by remember { mutableStateOf(true) }
    var useDigits by remember { mutableStateOf(true) }
    var useSymbols by remember { mutableStateOf(true) }

    // Passphrase options
    var wordCount by remember { mutableFloatStateOf(PassphraseGenerator.DEFAULT_WORDS.toFloat()) }
    var separator by remember { mutableStateOf("-") }
    var capitalize by remember { mutableStateOf(false) }
    var includeNumber by remember { mutableStateOf(false) }

    val anyClassEnabled = useLower || useUpper || useDigits || useSymbols
    val canGenerate = passphraseMode || anyClassEnabled

    // Any change to the options below produces a new secret straight away.
    val output = remember(
        passphraseMode, nonce,
        length.roundToInt(), useLower, useUpper, useDigits, useSymbols,
        wordCount.roundToInt(), separator, capitalize, includeNumber,
    ) {
        when {
            passphraseMode -> PassphraseGenerator.generate(
                words = words,
                wordCount = wordCount.roundToInt(),
                separator = separator,
                capitalize = capitalize,
                includeNumber = includeNumber,
            )
            anyClassEnabled -> PasswordGenerator.generate(
                length = length.roundToInt(),
                useLowercase = useLower,
                useUppercase = useUpper,
                useDigits = useDigits,
                useSymbols = useSymbols,
            )
            else -> ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generator") },
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
                .verticalScroll(rememberScrollState())
        ) {
            TabRow(selectedTabIndex = if (passphraseMode) 1 else 0) {
                Tab(
                    selected = !passphraseMode,
                    onClick = { passphraseMode = false },
                    text = { Text("Password") },
                )
                Tab(
                    selected = passphraseMode,
                    onClick = { passphraseMode = true },
                    text = { Text("Passphrase") },
                )
            }
            Column(Modifier.padding(16.dp)) {
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        output.ifEmpty { "Enable at least one character type" },
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = {
                            ClipboardHelper.copySecret(
                                context, if (passphraseMode) "passphrase" else "password", output,
                            )
                        },
                        enabled = canGenerate,
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { nonce++ }, enabled = canGenerate) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Regenerate")
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (passphraseMode) {
                    Text("Words: ${wordCount.roundToInt()}")
                    Slider(
                        value = wordCount,
                        onValueChange = { wordCount = it },
                        valueRange = PassphraseGenerator.MIN_WORDS.toFloat()..
                            PassphraseGenerator.MAX_WORDS.toFloat(),
                        steps = PassphraseGenerator.MAX_WORDS - PassphraseGenerator.MIN_WORDS - 1,
                    )
                    Text("Separator")
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        SEPARATORS.forEach { (value, name) ->
                            FilterChip(
                                selected = separator == value,
                                onClick = { separator = value },
                                label = { Text(name) },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    CharClassRow("Capitalize each word", capitalize) { capitalize = it }
                    CharClassRow("Include a number", includeNumber) { includeNumber = it }
                    Spacer(Modifier.height(12.dp))
                    val bits = PassphraseGenerator.entropyBits(
                        words.size, wordCount.roundToInt(), includeNumber,
                    )
                    Text(
                        "About ${bits.roundToInt()} bits of entropy — " +
                            passphraseStrengthLabel(bits) + ". Words are drawn from the " +
                            "EFF list of ${words.size} words.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Length: ${length.roundToInt()}")
                    Slider(
                        value = length,
                        onValueChange = { length = it },
                        valueRange = 8f..64f,
                    )
                    CharClassRow("Lowercase (a–z)", useLower) { useLower = it }
                    CharClassRow("Uppercase (A–Z)", useUpper) { useUpper = it }
                    CharClassRow("Digits (0–9)", useDigits) { useDigits = it }
                    CharClassRow("Symbols (!@#…)", useSymbols) { useSymbols = it }
                }
            }
        }
    }
}

private fun passphraseStrengthLabel(bits: Double): String = when {
    bits < 50 -> "fine for sites that limit guesses, weak as a master password"
    bits < 64 -> "good"
    bits < 77 -> "strong"
    else -> "very strong"
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
