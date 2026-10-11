package io.github.xomel45.openschool

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** `#rrggbb` -> hue 0..360, saturation 0..1, value 0..1. */
fun hexToHsv(hex: String): Triple<Float, Float, Float>? {
    val c = parseHex(hex) ?: return null
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val d = max - minOf(r, g, b)
    var h = 0f
    if (d > 0f) {
        h = when (max) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        if (h < 0f) h += 360f
    }
    return Triple(h, if (max == 0f) 0f else d / max, max)
}

/** The inverse of [hexToHsv]; the result is upper-case `#RRGGBB`. */
fun hsvToHex(h: Float, s: Float, v: Float): String {
    fun f(n: Int): Int {
        val k = (n + h / 60f) % 6f
        val x = v - v * s * maxOf(0f, minOf(k, 4f - k, 1f))
        return Math.round(x * 255f).coerceIn(0, 255)
    }
    return "#%02X%02X%02X".format(f(5), f(3), f(1))
}

/**
 * A colour picker drawn by the app (no system dialog): a plane of saturation and brightness, a hue bar and a hex field.
 * The hex string is the result, so what is shown is exactly what is saved.
 */
@Composable
fun ColorPickerDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val start = hexToHsv(initial) ?: Triple(210f, 0.8f, 0.9f)
    var hue by remember { mutableFloatStateOf(start.first) }
    var sat by remember { mutableFloatStateOf(start.second) }
    var value by remember { mutableFloatStateOf(start.third) }
    var typed by remember { mutableStateOf<String?>(null) } // what is in the field while the user types
    val hex = hsvToHex(hue, sat, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                val hueColor = parseHex(hsvToHex(hue, 1f, 1f))!!
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            fun set(o: Offset) { sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); typed = null }
                            detectTapGestures { set(it) }
                        }
                        .pointerInput(Unit) { detectDragGestures(onDragStart = { }) { change, _ -> change.consume(); val o = change.position; sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); typed = null } },
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val p = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, 11.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black, 12.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                }
                Canvas(
                    Modifier.fillMaxWidth().padding(top = 14.dp).height(28.dp).clip(RoundedCornerShape(14.dp))
                        .pointerInput(Unit) { detectTapGestures { hue = (it.x / size.width).coerceIn(0f, 1f) * 360f; typed = null } }
                        .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume(); hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f; typed = null } },
                ) {
                    drawRect(Brush.horizontalGradient((0..6).map { parseHex(hsvToHex(it * 60f, 1f, 1f))!! }))
                    val x = hue / 360f * size.width
                    drawCircle(Color.White, 12.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black, 13.dp.toPx(), Offset(x, size.height / 2), style = Stroke(1.dp.toPx()))
                }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(parseHex(hex)!!))
                    OutlinedTextField(
                        typed ?: hex,
                        { text ->
                            val t = text.uppercase().take(7)
                            typed = t
                            hexToHsv(t)?.let { (h, s, v) -> hue = h; sat = s; value = v }
                        },
                        Modifier.padding(start = 12.dp).fillMaxWidth(), singleLine = true, label = { Text("#RRGGBB") },
                        isError = typed != null && parseHex(typed!!) == null,
                    )
                }
            }
        },
        confirmButton = { TextButton({ onDone(typed?.takeIf { parseHex(it) != null } ?: hex) }) { Text("Готово") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}
