package io.github.xomel45.openschool

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Developer mode: whether it is on, the changes it shows ([Overlay]) and the profile photo. Everything lives on this phone only.
 * While the mode is off the app shows the real data and ignores the changes (they are kept for the next time).
 */
class DevStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("dev", Context.MODE_PRIVATE)
    private val photoFile = File(context.filesDir, "dev-photo")

    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set
    var overlay by mutableStateOf(Overlay.fromJson(prefs.getString("overlay", null)))
        private set
    var photo by mutableStateOf(loadPhoto())
        private set

    /** The lesson / homework exactly as the server sent it (set by the data holder); the edit dialogs compare with it. */
    var rawLesson: (String) -> uniffi.openschool_bridge.Lesson? = { null }
    var rawHomework: (String) -> uniffi.openschool_bridge.Homework? = { null }

    /** What the screens use: the changes while the mode is on, nothing while it is off. */
    val active: Overlay get() = if (enabled) overlay else Overlay()
    val activePhoto: ImageBitmap? get() = if (enabled) photo else null

    fun enable(v: Boolean) { enabled = v; prefs.edit().putBoolean("enabled", v).apply() }

    fun update(o: Overlay) { overlay = o; prefs.edit().putString("overlay", o.toJson().toString()).apply() }

    fun reset() { update(Overlay()); clearPhoto() }

    private fun loadPhoto(): ImageBitmap? = photoFile.takeIf { it.exists() }?.let { f ->
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / sample > 512 || o.outHeight / sample > 512) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }

    /** Returns an error text, or null. */
    fun setPhoto(uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Не удалось открыть файл."
            if (bytes.size > 10 * 1024 * 1024) return "Файл больше 10 МБ."
            photoFile.writeBytes(bytes)
            photo = loadPhoto()
            if (photo == null) { photoFile.delete(); "Это не картинка." } else null
        } catch (e: Exception) { "Не удалось прочитать файл." }
    }

    fun clearPhoto() { photoFile.delete(); photo = null }
}
