package io.github.xomel45.openschool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * A small picture of what a style looks like right now (with the chosen theme; the terminal is always dark): the page,
 * a panel with two lines of text and four marks. It is drawn from the same palette the real theme uses.
 */
@Composable
fun StylePreview(style: StylePref, dark: Boolean, modifier: Modifier = Modifier) {
    val l = look(style, dark || style == StylePref.Terminal)
    val page = l.gradient?.let { Brush.verticalGradient(it) } ?: Brush.verticalGradient(listOf(l.bg, l.bg))
    val r = (l.radius / 2).coerceAtLeast(2).dp
    Box(modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(r + 4.dp)).background(page).padding(10.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(r)).background(l.panel).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.fillMaxWidth(0.55f).height(7.dp).clip(RoundedCornerShape(3.dp)).background(l.ink.copy(alpha = 0.85f)))
            Box(Modifier.fillMaxWidth(0.35f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(l.muted))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("5" to 0, "4" to 1, "3" to 2, "2" to 3).forEach { (_, i) ->
                    Box(Modifier.size(width = 26.dp, height = 18.dp).clip(RoundedCornerShape((r.value / 2).coerceAtLeast(2f).dp)).background(l.marks[i]))
                }
                Box(Modifier.weight(1f))
                Box(Modifier.width(26.dp).height(18.dp).clip(RoundedCornerShape((r.value / 2).coerceAtLeast(2f).dp)).background(l.accent))
            }
        }
    }
}
