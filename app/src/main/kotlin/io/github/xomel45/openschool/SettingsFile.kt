package io.github.xomel45.openschool

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val CONFIG_NAME = "config.json"
private const val MEDIA_PREFIX = "background."
private const val MAX_CONFIG = 64 * 1024
private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "avif")

/** What was read from a settings file: the checked values and the picture, if the file had one. */
class ImportedSettings(val style: StylePref?, val scheme: SchemePref?, val accent: Accent?, val accentHex: String?, val accentGiven: Boolean, val zoom: Float?, val bg: Bg?, val picture: ByteArray?)

/** A settings file (`.cfg`) is the desktop app's format: a zip with `config.json` and, optionally, `background.<ext>`. */
object SettingsFile {
    fun build(ui: UiPrefs, bg: BackgroundPrefs): ByteArray {
        val config = JSONObject()
            .put("format", "openschool-config").put("version", 1)
            .put("style", ui.style.name.lowercase()).put("scheme", ui.scheme.name.lowercase())
            .put("accent", ui.accentHex ?: ui.accent?.name?.lowercase() ?: "default")
            .put("zoom", ui.zoom.toDouble())
            .put("bg", bg.bg.toJson())
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(CONFIG_NAME)); z.write(config.toString(2).toByteArray()); z.closeEntry()
            bg.pictureBytes()?.let { bytes ->
                // The extension is only a hint for the other side; the bytes decide what the file is.
                val ext = when { bytes.take(4) == listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47) -> "png"; bytes.take(2) == listOf<Byte>(0xFF.toByte(), 0xD8.toByte()) -> "jpg"; else -> "png" }
                z.putNextEntry(ZipEntry("$MEDIA_PREFIX$ext")); z.write(bytes); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Read and check an archive. Only `config.json` and one image `background.<ext>` are looked at; anything else inside is ignored. */
    fun read(data: ByteArray): ImportedSettings {
        var configText: String? = null
        var picture: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(data)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                if (name == CONFIG_NAME) {
                    if (configText != null) throw IllegalArgumentException("В архиве больше одного config.json")
                    val bytes = z.readLimited(MAX_CONFIG) ?: throw IllegalArgumentException("config.json слишком большой")
                    configText = bytes.toString(Charsets.UTF_8)
                } else if (name.startsWith(MEDIA_PREFIX) && !name.contains('/') && name.substringAfterLast('.').lowercase() in IMAGE_EXT) {
                    if (picture != null) throw IllegalArgumentException("В архиве больше одной картинки")
                    picture = z.readLimited(MAX_IMAGE_BYTES.toInt()) ?: throw IllegalArgumentException("Картинка больше 20 МБ")
                }
            }
        }
        val o = try { JSONObject(configText ?: throw IllegalArgumentException("В архиве нет config.json")) } catch (e: org.json.JSONException) { throw IllegalArgumentException("config.json не разобрать") }
        if (o.optString("format") != "openschool-config") throw IllegalArgumentException("Это не файл настроек OpenSchool")
        val accentRaw = o.optString("accent")
        val accent = Accent.entries.firstOrNull { it.name.equals(accentRaw, true) }
        return ImportedSettings(
            style = StylePref.entries.firstOrNull { it.name.equals(o.optString("style"), true) },
            scheme = SchemePref.entries.firstOrNull { it.name.equals(o.optString("scheme"), true) },
            accent = accent,
            accentHex = accentRaw.takeIf { parseHex(it) != null }?.uppercase(),
            accentGiven = accentRaw == "default" || accent != null || parseHex(accentRaw) != null,
            zoom = o.optDouble("zoom", Double.NaN).toFloat().takeIf { z -> ZOOMS.any { Math.abs(it - z) < 0.001f } }?.let { z -> ZOOMS.first { Math.abs(it - z) < 0.001f } },
            bg = o.optJSONObject("bg")?.let(::cleanBg),
            picture = picture,
        )
    }

    fun apply(s: ImportedSettings, ui: UiPrefs, bgp: BackgroundPrefs): String? {
        s.style?.let(ui::chooseStyle)
        s.scheme?.let(ui::chooseScheme)
        if (s.accentGiven) { ui.chooseAccent(s.accent); if (s.accentHex != null) ui.chooseAccentHex(s.accentHex) }
        s.zoom?.let(ui::chooseZoom)
        var problem: String? = null
        if (s.picture != null) problem = bgp.setPictureBytes(s.picture)
        s.bg?.let { b ->
            val hasPicture = bgp.image != null
            bgp.update(if (b.mode == "media" && !hasPicture) b.copy(mode = "default") else b) // the archive had no picture and none is stored
        }
        return problem
    }
}

private fun ZipInputStream.readLimited(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (out.size() + n > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
