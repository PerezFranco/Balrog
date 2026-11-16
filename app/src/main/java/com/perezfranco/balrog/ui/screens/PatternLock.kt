package com.perezfranco.balrog.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.perezfranco.balrog.crypto.PinWrap
import com.perezfranco.balrog.security.PatternLogic

/**
 * The nine-dot grid. Reports the drawn pattern through [onPattern] when the
 * finger lifts; the drawing stays visible until the next touch.
 */
@Composable
fun PatternLock(
    onPattern: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val selected = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val currentOnPattern by rememberUpdatedState(onPattern)

    val idle = MaterialTheme.colorScheme.outline
    val active = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Pattern grid" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun dotAt(position: Offset): Int? {
                    val cell = size.width / 3f
                    for (dot in 0..8) {
                        val center = Offset(cell * (dot % 3 + 0.5f), cell * (dot / 3 + 0.5f))
                        if ((position - center).getDistance() < cell * 0.3f) return dot
                    }
                    return null
                }
                fun touch(position: Offset) {
                    finger = position
                    val dot = dotAt(position) ?: return
                    val extended = PatternLogic.extend(selected, dot)
                    if (extended.size != selected.size) {
                        selected.clear()
                        selected.addAll(extended)
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    selected.clear()
                    touch(down.position)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes
                            .firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        touch(change.position)
                        change.consume()
                    }
                    finger = null
                    if (selected.isNotEmpty()) currentOnPattern(selected.toList())
                }
            }
    ) {
        val cell = size.width / 3f
        fun center(dot: Int) = Offset(cell * (dot % 3 + 0.5f), cell * (dot / 3 + 0.5f))
        val stroke = cell * 0.06f

        val points = selected.map(::center) + listOfNotNull(finger.takeIf { selected.isNotEmpty() })
        for (i in 1 until points.size) {
            drawLine(active, points[i - 1], points[i], strokeWidth = stroke, cap = StrokeCap.Round)
        }
        for (dot in 0..8) {
            val on = dot in selected
            drawCircle(
                color = if (on) active else idle,
                radius = cell * if (on) 0.11f else 0.07f,
                center = center(dot),
            )
        }
    }
}

/** Asks for the pattern that completes a biometric unlock. */
@Composable
fun EnterPatternDialog(
    error: String?,
    working: Boolean,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var tooShort by remember { mutableStateOf(false) }
    val message = if (tooShort) "Connect at least ${PinWrap.MIN_PIN_LENGTH} dots" else error

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Draw your pattern") },
        text = {
            Column {
                PatternLock(
                    enabled = !working,
                    isError = message != null,
                    onPattern = { pattern ->
                        tooShort = pattern.size < PinWrap.MIN_PIN_LENGTH
                        if (!tooShort) onSubmit(PatternLogic.encode(pattern))
                    },
                )
                Text(
                    message ?: if (working) "Checking…" else " ",
                    color = if (message != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text("Use master password")
            }
        },
    )
}

/** Lets the user choose the pattern that biometric unlock will also require. */
@Composable
fun SetPatternDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by remember { mutableStateOf<List<Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped to hand the user a clean grid for the next drawing.
    var round by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (first == null) "Draw a pattern" else "Draw it again") },
        text = {
            Column {
                Text(
                    "Unlocking will take your fingerprint or face and then this " +
                        "pattern. After ${PinWrap.MAX_ATTEMPTS} wrong patterns, " +
                        "biometric unlock switches off and only the master password " +
                        "works. Don't reuse your phone's screen-lock pattern.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                key(round) {
                    PatternLock(
                        isError = error != null,
                        onPattern = { pattern ->
                            val chosen = first
                            when {
                                pattern.size < PinWrap.MIN_PIN_LENGTH ->
                                    error = "Connect at least ${PinWrap.MIN_PIN_LENGTH} dots"
                                chosen == null -> {
                                    first = pattern
                                    error = null
                                    round++
                                }
                                pattern == chosen -> onConfirm(PatternLogic.encode(pattern))
                                else -> {
                                    first = null
                                    error = "The patterns did not match — start again"
                                }
                            }
                        },
                    )
                }
                Text(
                    error ?: " ",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
