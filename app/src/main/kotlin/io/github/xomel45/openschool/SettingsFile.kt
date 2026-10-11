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

/** Values the file may change in the notifications; `null` = not in the file (keep what is set). */
data class NotificationsPatch(val marks: Boolean?, val homework: Boolean?, val absences: Boolean?, val detail: Boolean?, val intervalMin: Int?)

/** What was read from a settings file: the checked values and the picture, if the file had one. */
class ImportedSettings(
    val style: StylePref?, val scheme: SchemePref?, val accent: Accent?, val accentHex: String?, val accentGiven: Boolean, val zoom: Float?,
    val bg: Bg?, val notifications: NotificationsPatch?, val network: NetMode?, val picture: ByteArray?,
)

/** The nearest interval this phone can run (15, 30 or 60 minutes). */
fun clampInterval(minutes: Int): Int = NEWS_INTERVALS.minByOrNull { Math.abs(it - minutes) * 2 + if (it < minutes) 1 else 0 } ?: 15

/**
 * A settings file (`.cfg`) is the format shared by every OpenSchool app (Linux, Windows, Android): a zip with `config.json` and,
 * optionally, `background.<ext>`. The rules are in OpenSchool-Bridge/docs/cfg/format.md; reading is tolerant: a value that is not
 * allowed is skipped, the rest still applies.
 */
object SettingsFile {
    private val FILE_INTERVALS = listOf(5, 15, 30, 60)

    /** The `config.json` of the current choices. */
    fun config(
        style: StylePref, scheme: SchemePref, accent: Accent?, accentHex: String?, zoom: Float, bg: Bg, notifications: NewsSettings, network: NetMode,
    ): JSONObject = JSONObject()
        .put("format", "openschool-config").put("version", 1)
        .put("style", style.name.lowercase()).put("scheme", scheme.name.lowercase())
        .put("accent", accentHex ?: accent?.name?.lowercase() ?: "default")
        .put("zoom", zoom.toString().toDouble()) // 1.3f is written as 1.3, not as 1.2999999523162842
        .put("bg", bg.toJson())
        .put("notifications", JSONObject().put("marks", notifications.marks).put("homework", notifications.homework).put("absences", notifications.absences).put("interval_min", notifications.intervalMin).put("detail", notifications.detail))
        .put("network", JSONObject().put("mode", network.name.lowercase()))

    fun archive(config: JSONObject, picture: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(CONFIG_NAME)); z.write(config.toString(2).toByteArray()); z.closeEntry()
            picture?.let { bytes ->
                // The extension is only a hint for the other side; the bytes decide what the file is.
                val ext = when { bytes.take(4) == listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47) -> "png"; bytes.take(2) == listOf<Byte>(0xFF.toByte(), 0xD8.toByte()) -> "jpg"; else -> "png" }
                z.putNextEntry(ZipEntry("$MEDIA_PREFIX$ext")); z.write(bytes); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun build(ui: UiPrefs, bg: BackgroundPrefs, news: NewsStore, net: NetPolicy): ByteArray =
        archive(config(ui.style, ui.scheme, ui.accent, ui.accentHex, ui.zoom, bg.bg, news.settings, net.mode), bg.pictureBytes())

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
                } // a video (mp4, webm) and everything else: this phone does not show it, the other settings still apply
            }
        }
        val o = try { JSONObject(configText ?: throw IllegalArgumentException("В архиве нет config.json")) } catch (e: org.json.JSONException) { throw IllegalArgumentException("config.json не разобрать") }
        if (o.optString("format") != "openschool-config") throw IllegalArgumentException("Это не файл настроек OpenSchool")
        val accentRaw = o.optString("accent")
        val accent = Accent.entries.firstOrNull { it.name.equals(accentRaw, true) }
        fun bool(j: JSONObject, k: String): Boolean? = if (j.has(k) && j.get(k) is Boolean) j.getBoolean(k) else null
        val notifications = o.optJSONObject("notifications")?.let { n ->
            val interval = (if (n.has("interval_min")) n.opt("interval_min") else null).let { v -> (v as? Number)?.toInt()?.takeIf { it in FILE_INTERVALS && v.toDouble() == it.toDouble() } }
            NotificationsPatch(bool(n, "marks"), bool(n, "homework"), bool(n, "absences"), bool(n, "detail"), interval?.let(::clampInterval))
                .takeIf { it.marks != null || it.homework != null || it.absences != null || it.detail != null || it.intervalMin != null }
        }
        return ImportedSettings(
            style = StylePref.entries.firstOrNull { it.name.equals(o.optString("style"), true) },
            scheme = SchemePref.entries.firstOrNull { it.name.equals(o.optString("scheme"), true) },
            accent = accent,
            accentHex = accentRaw.takeIf { parseHex(it) != null }?.uppercase(),
            accentGiven = accentRaw == "default" || accent != null || parseHex(accentRaw) != null,
            zoom = o.optDouble("zoom", Double.NaN).toFloat().takeIf { z -> ZOOMS.any { Math.abs(it - z) < 0.001f } }?.let { z -> ZOOMS.first { Math.abs(it - z) < 0.001f } },
            bg = o.optJSONObject("bg")?.let(::cleanBg),
            notifications = notifications,
            network = o.optJSONObject("network")?.optString("mode")?.let { m -> NetMode.entries.firstOrNull { it.name.equals(m, true) } },
            picture = picture,
        )
    }

    /** Take over what was read. Returns an error text for the picture, or null. */
    fun apply(s: ImportedSettings, ui: UiPrefs, bgp: BackgroundPrefs, news: NewsStore, net: NetPolicy): String? {
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
        s.notifications?.let { p ->
            val cur = news.settings
            news.update(cur.copy(marks = p.marks ?: cur.marks, homework = p.homework ?: cur.homework, absences = p.absences ?: cur.absences, detail = p.detail ?: cur.detail, intervalMin = p.intervalMin ?: cur.intervalMin))
        }
        s.network?.let(net::choose)
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
