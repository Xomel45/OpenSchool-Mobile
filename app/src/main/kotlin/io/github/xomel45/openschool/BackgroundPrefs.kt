package io.github.xomel45.openschool

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.io.File

const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

/** The page background: the style's own, one colour, a two-colour gradient, or a picture. The keys are the desktop app's, so settings files are shared. */
data class Bg(
    val mode: String = "default", // default | solid | gradient | media
    val color: String = "#E2E8F0",
    val a: String = "#FF9966",
    val b: String = "#FF5E62",
    val angle: Int = 160,
    val dim: Int = 35,
    val blur: Int = 0,
)

private val HEX = Regex("^#[0-9a-fA-F]{6}$")
fun parseHex(s: String): Color? = if (HEX.matches(s)) Color((0xFF000000L or s.substring(1).toLong(16)).toInt()) else null

/** Anything unexpected (junk in storage, a foreign file) falls back to the default. */
fun cleanBg(o: JSONObject?): Bg {
    if (o == null) return Bg()
    val d = Bg()
    fun hex(k: String, def: String) = o.optString(k).takeIf { HEX.matches(it) } ?: def
    fun num(k: String, lo: Int, hi: Int, def: Int) = if (o.has(k) && !o.isNull(k)) o.optInt(k, def).coerceIn(lo, hi) else def
    val mode = o.optString("mode").takeIf { it in listOf("default", "solid", "gradient", "media") } ?: d.mode
    return Bg(mode, hex("color", d.color), hex("a", d.a), hex("b", d.b), num("angle", 0, 360, d.angle), num("dim", 0, 80, d.dim), num("blur", 0, 20, d.blur))
}

fun Bg.toJson(): JSONObject = JSONObject().put("mode", mode).put("color", color).put("a", a).put("b", b).put("angle", angle).put("dim", dim).put("blur", blur).put("kind", "image")

class BackgroundPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "background")

    var bg by mutableStateOf(cleanBg(runCatching { JSONObject(prefs.getString("bg", "{}") ?: "{}") }.getOrNull()))
        private set
    /** The stored picture, scaled down for the screen; `null` while none is chosen. */
    var image by mutableStateOf<ImageBitmap?>(loadImage())
        private set

    fun update(v: Bg) { bg = v; prefs.edit().putString("bg", v.toJson().toString()).apply() }

    private fun file() = dir.listFiles()?.firstOrNull { it.name.startsWith("bg.") }

    private fun loadImage(): ImageBitmap? = file()?.let { f ->
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }

    /** Copy the chosen picture into the app (the original may be moved or deleted). Returns an error text, or null. */
    fun setPicture(uri: Uri): String? {
        val tmp = File(context.cacheDir, "bg-new")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMAGE_BYTES) return "Файл больше 20 МБ."
                        out.write(buf, 0, n)
                    }
                }
            } ?: return "Не удалось открыть файл."
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0) return "Это не картинка."
            return store(tmp)
        } catch (e: Exception) {
            return "Не удалось прочитать файл."
        } finally {
            tmp.delete()
        }
    }

    /** Take a picture's bytes from a settings file (already size-checked by the caller). */
    fun setPictureBytes(bytes: ByteArray): String? {
        val tmp = File(context.cacheDir, "bg-new")
        return try {
            tmp.writeBytes(bytes)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0) "Это не картинка." else store(tmp)
        } finally { tmp.delete() }
    }

    private fun store(tmp: File): String? {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        tmp.copyTo(File(dir, "bg.img"), overwrite = true)
        image = loadImage()
        update(bg.copy(mode = "media"))
        return if (image == null) "Не удалось показать картинку." else null
    }

    fun clearPicture() {
        dir.listFiles()?.forEach { it.delete() }
        image = null
        if (bg.mode == "media") update(bg.copy(mode = "default"))
    }

    /** The picture's bytes for an export, or null. */
    fun pictureBytes(): ByteArray? = file()?.readBytes()
}
