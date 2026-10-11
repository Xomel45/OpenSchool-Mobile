package io.github.xomel45.openschool

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SOLID_SWATCHES = listOf("#E2E8F0", "#FDEBD0", "#CFE6F7", "#D6EAD8", "#F3D9E6", "#1B1F3B", "#14171E", "#2E3440")
private val GRADIENTS = listOf(
    Triple("#FF9966", "#FF5E62", 160), Triple("#36D1DC", "#5B86E5", 160), Triple("#A8E063", "#56AB2F", 160),
    Triple("#8E2DE2", "#4A00E0", 160), Triple("#232526", "#414345", 160), Triple("#FBD9B8", "#CFE6F7", 180),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundSettings(bgp: BackgroundPrefs) {
    val bg = bgp.bg
    val context = LocalContext.current
    var error by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    picking?.let { which ->
        ColorPickerDialog(
            when (which) { "a" -> "Цвет 1"; "b" -> "Цвет 2"; else -> "Свой цвет" },
            when (which) { "a" -> bg.a; "b" -> bg.b; else -> bg.color }, onDismiss = { picking = null },
            onDone = { hex -> bgp.update(when (which) { "a" -> bg.copy(a = hex); "b" -> bg.copy(b = hex); else -> bg.copy(color = hex) }); picking = null },
        )
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) error = bgp.setPicture(uri) ?: ""
    }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("default" to "По стилю", "solid" to "Цвет", "gradient" to "Градиент", "media" to "Картинка").forEach { (m, t) ->
                FilterChip(bg.mode == m, { bgp.update(bg.copy(mode = m)) }, label = { Text(t) })
            }
        }
        when (bg.mode) {
            "solid" -> {
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SOLID_SWATCHES.forEach { c -> Swatch(Brush.linearGradient(listOf(parseHex(c)!!, parseHex(c)!!)), bg.color.equals(c, true)) { bgp.update(bg.copy(color = c)) } }
                }
                OutlinedButton({ picking = "solid" }, Modifier.padding(top = 8.dp)) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(parseHex(bg.color) ?: Color.Gray)); Text("  Свой цвет  ${bg.color}")
                }
            }
            "gradient" -> {
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GRADIENTS.forEach { (a, b, angle) ->
                    Swatch(Brush.linearGradient(listOf(parseHex(a)!!, parseHex(b)!!)), bg.a.equals(a, true) && bg.b.equals(b, true)) { bgp.update(bg.copy(a = a, b = b, angle = angle)) }
                }
            }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ picking = "a" }) { Box(Modifier.size(18.dp).clip(CircleShape).background(parseHex(bg.a) ?: Color.Gray)); Text("  Цвет 1") }
                    OutlinedButton({ picking = "b" }) { Box(Modifier.size(18.dp).clip(CircleShape).background(parseHex(bg.b) ?: Color.Gray)); Text("  Цвет 2") }
                }
            }
            "media" -> {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (bgp.image == null) "Выбрать картинку" else "Другая картинка") }
                    if (bgp.image != null) OutlinedButton({ bgp.clearPicture() }) { Text("Убрать") }
                }
                if (bgp.image != null) {
                    Text("Затемнение: ${bg.dim}%", Modifier.padding(top = 8.dp), fontSize = 13.sp)
                    Slider(bg.dim.toFloat(), { bgp.update(bg.copy(dim = it.toInt())) }, valueRange = 0f..80f)
                    Text("Размытие: ${bg.blur}", fontSize = 13.sp)
                    Slider(bg.blur.toFloat(), { bgp.update(bg.copy(blur = it.toInt())) }, valueRange = 0f..20f)
                }
                Text("PNG, JPG, WebP, GIF, AVIF до 20 МБ. Видео на телефоне пока не поддерживается. Файл копируется в память приложения.", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (error.isNotEmpty()) Text(error, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Swatch(brush: Brush, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(brush)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
            .clickable(onClick = onClick),
    )
}
