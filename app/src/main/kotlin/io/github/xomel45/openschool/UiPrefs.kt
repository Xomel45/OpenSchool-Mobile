package io.github.xomel45.openschool

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val ZOOMS = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f)

enum class StylePref(val title: String, val note: String) {
    Classic("Классический", "Спокойный и аккуратный"),
    Terminal("Терминал", "Моноширинный шрифт, всегда тёмный"),
    Sunrise("Рассвет", "Мягкий градиент и стекло"),
}

enum class SchemePref(val title: String) { System("Системная"), Light("Светлая"), Dark("Тёмная") }

/** Accent presets: name, colour on light themes, colour on dark themes (the same set as the desktop app). */
enum class Accent(val title: String, val light: Color, val dark: Color) {
    Blue("Синий", Color(0xFF2563EB), Color(0xFF5B8CFF)),
    Teal("Бирюзовый", Color(0xFF0D9488), Color(0xFF2DD4BF)),
    Green("Зелёный", Color(0xFF16A34A), Color(0xFF4ADE80)),
    Amber("Янтарный", Color(0xFFD97706), Color(0xFFFBBF24)),
    Red("Красный", Color(0xFFDC2626), Color(0xFFF87171)),
    Pink("Розовый", Color(0xFFDB2777), Color(0xFFF472B6)),
    Purple("Фиолетовый", Color(0xFF7C3AED), Color(0xFFA78BFA)),
}

/** Black or white, whichever reads better on the colour (WCAG contrast). */
fun inkFor(c: Color): Color {
    val l = c.luminance()
    return if (1.05 / (l + 0.05) >= (l + 0.05) / 0.05) Color.White else Color.Black
}

/** Look-and-feel choices, kept on the phone and read by the theme. */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var style by mutableStateOf(runCatching { StylePref.valueOf(prefs.getString("style", "") ?: "") }.getOrDefault(StylePref.Classic))
        private set
    var scheme by mutableStateOf(runCatching { SchemePref.valueOf(prefs.getString("scheme", "") ?: "") }.getOrDefault(SchemePref.System))
        private set
    /** `null` = the default colour of the app. */
    var accent by mutableStateOf(runCatching { Accent.valueOf(prefs.getString("accent", "") ?: "") }.getOrNull())
        private set

    var zoom by mutableStateOf(prefs.getFloat("zoom", 1f).takeIf { it in ZOOMS } ?: 1f)
        private set

    /** A colour of the user's own (`#RRGGBB`); wins over the presets. */
    var accentHex by mutableStateOf(prefs.getString("accentHex", "")?.takeIf { parseHex(it) != null })
        private set

    /** The launcher icon the user chose (a developer-only one is used only while the developer mode is on, see [AppIcon.effective]). */
    var icon by mutableStateOf(AppIcon.fromId(prefs.getString("icon", null)) ?: AppIcon.DEFAULT)
        private set

    fun chooseIcon(v: AppIcon) { icon = v; prefs.edit().putString("icon", v.id).apply() }

    fun chooseAccentHex(hex: String?) { accentHex = hex?.takeIf { parseHex(it) != null }; prefs.edit().putString("accentHex", accentHex ?: "").apply() }

    fun chooseZoom(v: Float) { zoom = v; prefs.edit().putFloat("zoom", v).apply() }
    fun chooseStyle(v: StylePref) { style = v; prefs.edit().putString("style", v.name).apply() }
    fun chooseScheme(v: SchemePref) { scheme = v; prefs.edit().putString("scheme", v.name).apply() }
    fun chooseAccent(v: Accent?) { accent = v; prefs.edit().putString("accent", v?.name ?: "").apply(); chooseAccentHex(null) }
}
