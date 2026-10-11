package io.github.xomel45.openschool

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Colours of the marks; set by the theme (the styles differ a little), read wherever a mark is drawn. */
object MarkColors {
    var five = Color(0xFF2F9E44); var four = Color(0xFF5FA52B); var three = Color(0xFFF08C00); var two = Color(0xFFD63A3A); var other = Color(0xFF8FA3BF)
    var ink = Color.White
}

/** One look: the palette the desktop app uses for the style and the scheme (light or dark). */
internal class Look(
    val bg: Color, val panel: Color, val ink: Color, val muted: Color, val line: Color, val accent: Color, val accentInk: Color, val soft: Color,
    val radius: Int, val dark: Boolean, val mono: Boolean = false, val gradient: List<Color>? = null,
    val marks: List<Color>, val markInk: Color,
)

internal fun look(style: StylePref, dark: Boolean): Look = when (style) {
    StylePref.Terminal -> Look(
        Color(0xFF2E3440), Color(0xFF3B4252), Color(0xFFD8DEE9), Color(0xFF8D98AD), Color(0xFF4C566A), Color(0xFF88C0D0), Color(0xFF2E3440), Color(0xFF434C5E),
        2, true, mono = true, marks = listOf(Color(0xFFA3BE8C), Color(0xFFB6C98F), Color(0xFFEBCB8B), Color(0xFFBF616A), Color(0xFF7B88A1)), markInk = Color(0xFF2E3440),
    )
    StylePref.Sunrise -> if (dark) Look(
        Color(0xFF1B1F3B), Color(0xFF262B57), Color(0xFFEDEBFF), Color(0xFFA9A7C9), Color(0x1FFFFFFF), Color(0xFFFFB454), Color(0xFF1B1F3B), Color(0x12FFFFFF),
        20, true, gradient = listOf(Color(0xFF171A38), Color(0xFF262B57), Color(0xFF3A3563), Color(0xFF4B3C62)),
        marks = listOf(Color(0xFF46C27A), Color(0xFF86C24A), Color(0xFFF5A94A), Color(0xFFEF6A5F), Color(0xFF7E86AD)), markInk = Color(0xFF171A38),
    ) else Look(
        Color(0xFFFDEBD0), Color(0xB8FFFFFF), Color(0xFF1B2A41), Color(0xFF5B6B82), Color(0x1F1B2A41), Color(0xFFE8890C), Color.White, Color(0x99FFFFFF),
        20, false, gradient = listOf(Color(0xFFFBD9B8), Color(0xFFFDEBD0), Color(0xFFCFE6F7), Color(0xFFEAF4FB)),
        marks = listOf(Color(0xFF2F9E44), Color(0xFF5FA52B), Color(0xFFF08C00), Color(0xFFD63A3A), Color(0xFF8FA3BF)), markInk = Color.White,
    )
    StylePref.Classic -> if (dark) Look(
        Color(0xFF14171E), Color(0xFF1C2029), Color(0xFFE8EAF0), Color(0xFF9AA2B2), Color(0xFF2A303C), Color(0xFF5B8CFF), Color(0xFF0E1220), Color(0xFF242936),
        14, true, marks = listOf(Color(0xFF3FB36B), Color(0xFF76B93F), Color(0xFFF2A33A), Color(0xFFE5534B), Color(0xFF6F7F9A)), markInk = Color(0xFF0E1220),
    ) else Look(
        Color(0xFFEDF0F6), Color.White, Color(0xFF151A26), Color(0xFF667085), Color(0xFFE1E5EE), Color(0xFF2563EB), Color.White, Color(0xFFF3F5FA),
        14, false, marks = listOf(Color(0xFF2F9E44), Color(0xFF5FA52B), Color(0xFFF08C00), Color(0xFFD63A3A), Color(0xFF8FA3BF)), markInk = Color.White,
    )
}

/** Whether the scheme is dark: the terminal style is always dark. */
@Composable
fun isDark(ui: UiPrefs): Boolean = ui.style == StylePref.Terminal || when (ui.scheme) {
    SchemePref.Dark -> true
    SchemePref.Light -> false
    SchemePref.System -> isSystemInDarkTheme()
}

@Composable
fun AppTheme(ui: UiPrefs, bgPrefs: BackgroundPrefs, content: @Composable () -> Unit) {
    val dark = isDark(ui)
    val base0 = look(ui.style, dark)
    val bg = bgPrefs.bg
    // A chosen background replaces the style's own; the panels keep the style's colours (translucent ones show it through).
    val custom = bg.mode != "default" && (bg.mode != "media" || bgPrefs.image != null)
    val l = if (custom) base0.withBackdrop() else base0
    // The accent chosen by the user wins over the style's own (a preset has a light and a dark variant).
    val own = ui.accentHex?.let(::parseHex)
    val accent = own ?: ui.accent?.let { if (dark) it.dark else it.light } ?: l.accent
    val onAccent = if (own != null || ui.accent != null) inkFor(accent) else l.accentInk
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme: ColorScheme = base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 0.25f), onPrimaryContainer = l.ink,
        secondaryContainer = accent.copy(alpha = 0.25f), onSecondaryContainer = l.ink,
        background = if (l.gradient != null) Color.Transparent else l.bg, onBackground = l.ink,
        surface = if (l.gradient != null) Color.Transparent else l.bg, onSurface = l.ink, onSurfaceVariant = l.muted,
        surfaceVariant = l.soft, outline = l.line, outlineVariant = l.line,
        // Cards are the style's panel (glass on the gradient styles); sheets, dialogs and bars must not be see-through.
        surfaceContainerLowest = l.panel, surfaceContainerHighest = l.panel,
        surfaceContainerLow = if (l.gradient != null) l.bg else l.panel, surfaceContainer = if (l.gradient != null) l.bg else l.panel, surfaceContainerHigh = if (l.gradient != null) l.bg else l.panel,
        surfaceTint = Color.Transparent,
    )
    MarkColors.five = l.marks[0]; MarkColors.four = l.marks[1]; MarkColors.three = l.marks[2]; MarkColors.two = l.marks[3]; MarkColors.other = l.marks[4]; MarkColors.ink = l.markInk
    val r = l.radius.dp
    val shapes = Shapes(extraSmall = RoundedCornerShape(r / 2), small = RoundedCornerShape(r / 1.5f), medium = RoundedCornerShape(r), large = RoundedCornerShape(r), extraLarge = RoundedCornerShape(r))
    val type = if (l.mono) MaterialTheme.typography.let { t -> Typography().mapFonts(FontFamily.Monospace) } else Typography()
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * ui.zoom, d.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = type) {
            val page: Modifier = when {
                custom && bg.mode == "solid" -> Modifier.background(parseHex(bg.color) ?: l.bg)
                custom && bg.mode == "gradient" -> Modifier.background(angled(parseHex(bg.a) ?: l.bg, parseHex(bg.b) ?: l.bg, bg.angle))
                else -> Modifier.background(l.gradient?.let { Brush.verticalGradient(it) } ?: Brush.verticalGradient(listOf(l.bg, l.bg)))
            }
            Box(Modifier.fillMaxSize().then(page)) {
                val img = bgPrefs.image
                if (custom && bg.mode == "media" && img != null) {
                    androidx.compose.foundation.Image(
                        img, null, Modifier.fillMaxSize().then(if (bg.blur > 0) Modifier.blur(bg.blur.dp) else Modifier),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = bg.dim / 100f)))
                }
                content()
            }
        }
    }
}

/** A linear gradient at a CSS angle (0 = to top, 90 = to right, 180 = to bottom). */
private fun angled(a: Color, b: Color, degrees: Int): Brush {
    val r = Math.toRadians(degrees.toDouble())
    val dx = Math.sin(r).toFloat(); val dy = -Math.cos(r).toFloat()
    val big = 2000f
    return Brush.linearGradient(listOf(a, b), start = androidx.compose.ui.geometry.Offset(big / 2 - dx * big / 2, big / 2 - dy * big / 2), end = androidx.compose.ui.geometry.Offset(big / 2 + dx * big / 2, big / 2 + dy * big / 2))
}

private fun Look.withBackdrop() = Look(bg, panel, ink, muted, line, accent, accentInk, soft, radius, dark, mono, null, marks, markInk)

private fun Typography.mapFonts(f: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)
