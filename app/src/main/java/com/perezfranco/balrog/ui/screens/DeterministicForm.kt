package com.perezfranco.balrog.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.rememberCoroutineScope
import com.perezfranco.balrog.security.RecipeQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.perezfranco.balrog.data.Pepper
import com.perezfranco.balrog.security.ClipboardHelper
import com.perezfranco.balrog.security.DeterministicGenerator
import com.perezfranco.balrog.security.DeterministicGenerator.Recipe
import com.perezfranco.balrog.security.DeterministicGenerator.Type
import com.perezfranco.balrog.ui.VaultViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private fun qrOptions(prompt: String) = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt(prompt)
    .setBeepEnabled(false)
    .setOrientationLocked(false)

/** "a...L - Name", as in the Generator app's Pepper list. */
private fun Pepper.label(): String =
    DeterministicGenerator.hint(value)?.let { "$it - $title" } ?: title

/**
 * Peppers ordered by the first character of their value: digits and letters
 * in natural order, anything else after them.
 */
private fun List<Pepper>.inDisplayOrder(): List<Pepper> = sortedBy { pepper ->
    val first = pepper.value.firstOrNull() ?: return@sortedBy Int.MAX_VALUE
    if (first.isLetterOrDigit()) first.code else first.code + 0x110000
}

/**
 * The deterministic generator: fill in a recipe (Base, Case, Salt, Pepper,
 * Type, Trim) and get the one password that recipe always produces.
 *
 * @param initialRecipe "Full Copy" text to start from, e.g. an entry's saved recipe.
 * @param onUse if set, a "Use this password" button hands back the password
 *        and its recipe (for the entry editor).
 */
@Composable
fun DeterministicForm(
    viewModel: VaultViewModel,
    modifier: Modifier = Modifier,
    initialRecipe: String = "",
    onUse: ((password: String, recipe: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val peppers by viewModel.peppers.collectAsState()

    var base by remember { mutableStateOf("") }
    var case by remember { mutableStateOf("") }
    var salt by remember { mutableStateOf("") }
    var trim by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(Type.SPECIAL) }
    var pepperId by remember { mutableStateOf<Long?>(null) }
    // From a pasted/scanned/saved recipe: what the result should look like.
    var expectedSane by remember { mutableStateOf<String?>(null) }
    var expectedPepper by remember { mutableStateOf<String?>(null) }

    var generating by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var resultRecipe by remember { mutableStateOf("") }
    var checksOut by remember { mutableStateOf<Boolean?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    var pepperMenuOpen by remember { mutableStateOf(false) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    var addingPepper by remember { mutableStateOf(false) }
    var managingPeppers by remember { mutableStateOf(false) }

    val selectedPepper = peppers.firstOrNull { it.id == pepperId }

    fun say(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    fun applyRecipe(text: String, source: String): Boolean {
        val recipe = Recipe.parse(text) ?: run {
            say("That is not a generator recipe.", error = true)
            return false
        }
        base = recipe.base
        case = recipe.case
        salt = recipe.salt
        recipe.trim?.let { trim = it.toString() }
        recipe.type?.let { type = it }
        expectedSane = recipe.sane
        expectedPepper = recipe.pepperHint
        password = ""
        resultRecipe = ""
        checksOut = null
        // Pick the Pepper for them when exactly one fits the recipe's hint.
        val matches = peppers.filter { DeterministicGenerator.hint(it.value) == recipe.pepperHint }
        pepperId = if (recipe.pepperHint != null && matches.size == 1) matches[0].id else null
        say(
            when {
                recipe.pepperHint == null -> "Recipe $source."
                matches.size == 1 -> "Recipe $source. Pepper \"${matches[0].title}\" selected."
                else -> "Recipe $source. Select the Pepper ${recipe.pepperHint} before generating."
            }
        )
        return true
    }

    // Load the starting recipe once, when the form first appears. Peppers
    // arrive a moment after the first frame, so give them a chance to load
    // before trying to match the recipe's Pepper.
    LaunchedEffect(initialRecipe) {
        if (initialRecipe.isNotBlank()) {
            withTimeoutOrNull(500) { viewModel.peppers.first { it.isNotEmpty() } }
            applyRecipe(initialRecipe, "loaded")
        }
    }

    val recipeScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { applyRecipe(it, "scanned") }
    }

    // Saving the recipe QR code: the file picker returns a location, then the image is written.
    val scope = rememberCoroutineScope()
    var qrRecipe by remember { mutableStateOf("") }
    val qrSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { destination ->
        if (destination != null) {
            val recipeText = qrRecipe
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { writeQrPng(context, destination, recipeText) }.isSuccess
                }
                say(if (saved) "QR code saved." else "Could not save the QR code.", error = !saved)
            }
        }
    }

    fun generate() {
        val cleanBase = base.trim()
        val cleanCase = case.trim()
        val cleanSalt = salt.trim()
        base = cleanBase
        case = cleanCase
        salt = cleanSalt
        var length = trim.toIntOrNull() ?: 0
        if (cleanBase.isEmpty() || cleanSalt.isEmpty() || length <= 0) {
            say("Base, Salt and Trim are required.", error = true)
            return
        }
        if (length > type.maxTrim) length = type.maxTrim
        trim = length.toString()

        val pepper = selectedPepper
        val pepperHint = pepper?.let { DeterministicGenerator.hint(it.value) }
        val wanted = expectedPepper
        if (wanted != null && pepper == null) {
            say("This recipe expects the Pepper $wanted. Select it before generating.", error = true)
            return
        }
        if (wanted != null && pepperHint != wanted) {
            say(
                "Pepper \"${pepper?.title}\" ($pepperHint) is not the one this recipe " +
                    "expects ($wanted).",
                error = true,
            )
            return
        }

        // The previous result goes at once, so it can't be copied or turned into a
        // QR code while a new one is being worked out.
        password = ""
        resultRecipe = ""
        checksOut = null
        generating = true
        message = null
        val usedType = type
        viewModel.generateDeterministic(
            cleanBase, cleanCase, cleanSalt, pepper?.value.orEmpty(), length, usedType,
        ) { result ->
            generating = false
            password = result
            revealed = false
            val sane = DeterministicGenerator.hint(result)
            checksOut = expectedSane?.let { it == sane }
            // Snapshot of what actually produced this password.
            resultRecipe = Recipe(
                cleanBase, cleanCase, cleanSalt, length, usedType, pepperHint, sane,
            ).toText()
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row {
            OutlinedButton(onClick = {
                val text = clipboard.getText()?.text.orEmpty()
                if (text.isEmpty()) say("The clipboard is empty.", error = true)
                else applyRecipe(text, "pasted")
            }) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Paste recipe")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                recipeScanner.launch(qrOptions("Scan a recipe QR code"))
            }) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Scan QR")
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = base,
            onValueChange = { base = it },
            label = { Text("Base") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = case,
            onValueChange = { case = it },
            label = { Text("Case (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = salt,
            onValueChange = { salt = it },
            label = { Text("Salt") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Box {
            OutlinedButton(onClick = { pepperMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Pepper: " + (selectedPepper?.label() ?: "(No Pepper)"),
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = pepperMenuOpen, onDismissRequest = { pepperMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("(No Pepper)") },
                    onClick = {
                        pepperMenuOpen = false
                        pepperId = null
                    },
                )
                peppers.inDisplayOrder().forEach { pepper ->
                    DropdownMenuItem(
                        text = { Text(pepper.label()) },
                        onClick = {
                            pepperMenuOpen = false
                            pepperId = pepper.id
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("+ Add new Pepper…") },
                    onClick = {
                        pepperMenuOpen = false
                        addingPepper = true
                    },
                )
                if (peppers.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Manage Peppers…") },
                        onClick = {
                            pepperMenuOpen = false
                            managingPeppers = true
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box {
            OutlinedButton(onClick = { typeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Type: ${type.label}", modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                Type.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            typeMenuOpen = false
                            type = option
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = trim,
            onValueChange = { text -> trim = text.filter { it.isDigit() }.take(2) },
            label = { Text("Trim (length)") },
            supportingText = { Text("Up to ${type.maxTrim} for this type") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { generate() },
            enabled = !generating && base.isNotBlank() && salt.isNotBlank() &&
                (trim.toIntOrNull() ?: 0) > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (generating) "Generating…" else "Generate")
        }
        message?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                color = if (messageIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (password.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                ) {
                    Text(
                        if (revealed) password
                        else DeterministicGenerator.hint(password).orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 16.dp),
                    )
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealed) "Hide password" else "Show password",
                        )
                    }
                }
            }
            checksOut?.let { ok ->
                Text(
                    if (ok) "Checks out!" else "Does not match the recipe's check ($expectedSane)!",
                    color = if (ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = {
                    ClipboardHelper.copySecret(context, "password", password)
                    say("Password copied.")
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy PW")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    ClipboardHelper.copySecret(context, "recipe", resultRecipe)
                    say("Recipe copied.")
                }) {
                    Text("Copy Recipe")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                qrRecipe = resultRecipe
                qrSaver.launch("balrog-recipe-qr.png")
            }) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Download QR")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "The recipe and its QR code are as sensitive as the password itself " +
                    "unless you use a Pepper: anyone who has them can recreate it.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (onUse != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onUse(password, resultRecipe) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Use this password")
                }
            }
        }
    }

    if (addingPepper) {
        PepperDialog(
            title = "Add a Pepper",
            existingNames = peppers.map { it.title },
            onSave = { name, value ->
                addingPepper = false
                viewModel.saveItem(Pepper(title = name, value = value))
                say("Pepper \"$name\" stored in the vault. Select it from the list to use it.")
            },
            onDismiss = { addingPepper = false },
        )
    }

    if (managingPeppers) {
        ManagePeppersDialog(
            viewModel = viewModel,
            peppers = peppers,
            onDismiss = { managingPeppers = false },
        )
    }
}

/** Name + value for a new Pepper. The value can be typed, pasted or scanned. */
@Composable
private fun PepperDialog(
    title: String,
    existingNames: List<String>,
    onSave: (name: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val duplicate = existingNames.any { it.equals(name.trim(), ignoreCase = true) }
    // Straight from the camera into the field: a scanned value never
    // passes through the clipboard.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { value = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = if (duplicate) {
                        { Text("A Pepper with this name already exists") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(value = value, onValueChange = { value = it }, label = "Value")
                TextButton(onClick = { scanner.launch(qrOptions("Scan the Pepper's QR code")) }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Scan value from QR code")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && value.isNotEmpty() && !duplicate,
                onClick = { onSave(name.trim(), value) },
            ) { Text("Store") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ManagePeppersDialog(
    viewModel: VaultViewModel,
    peppers: List<Pepper>,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Pepper?>(null) }
    var deleting by remember { mutableStateOf<Pepper?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Peppers") },
        text = {
            Column {
                if (peppers.isEmpty()) Text("No Peppers stored.")
                peppers.forEach { pepper ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pepper.title, modifier = Modifier.weight(1f))
                        IconButton(onClick = { renaming = pepper }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Rename ${pepper.title}")
                        }
                        IconButton(onClick = { deleting = pepper }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${pepper.title}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )

    renaming?.let { pepper ->
        var name by remember(pepper.id) { mutableStateOf(pepper.title) }
        val taken = peppers.any { it.id != pepper.id && it.title.equals(name.trim(), true) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename Pepper") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = taken,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !taken,
                    onClick = {
                        viewModel.saveItem(pepper.copy(title = name.trim()))
                        renaming = null
                    },
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            },
        )
    }

    deleting?.let { pepper ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${pepper.title}\"?") },
            text = {
                Text(
                    "Passwords generated with this Pepper cannot be recreated " +
                        "without it. Make sure you have its value somewhere else."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteItem(pepper.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

/** Draws [recipe] as a QR code and writes it as a PNG to [destination]. */
private fun writeQrPng(context: Context, destination: Uri, recipe: String) {
    val matrix = RecipeQr.encode(recipe)
    val scale = RecipeQr.scaleFor(matrix)
    val size = matrix.width * scale
    val bitmap = Bitmap.createBitmap(RecipeQr.pixels(matrix, scale), size, size, Bitmap.Config.ARGB_8888)
    val stream = context.contentResolver.openOutputStream(destination, "wt")
        ?: throw IOException("Cannot open the destination")
    stream.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
