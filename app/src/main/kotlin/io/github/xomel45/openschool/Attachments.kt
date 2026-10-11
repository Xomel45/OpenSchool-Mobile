package io.github.xomel45.openschool

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.openschool_bridge.BridgeException
import uniffi.openschool_bridge.Download
import uniffi.openschool_bridge.Material
import uniffi.openschool_bridge.attachmentFileName
import uniffi.openschool_bridge.canOpen
import java.io.File

/** Where attachments come from: the Bridge in the app (it checks the address, the size and the kind of answer); nothing in the demo. */
fun interface FileSource {
    suspend fun download(link: String): Download
}

val LocalFiles = staticCompositionLocalOf<FileSource?> { null }

/**
 * A tap on an attachment downloads the file into the app's cache and hands it to the system: documents, pictures and media are
 * opened by a viewer, any other kind (programs, archives, unknown) is only offered for sharing/saving, never opened.
 * Nothing is requested from the user's storage; the viewer gets a one-file read permission through a FileProvider.
 */
object Attachments {
    private const val DIR = "attachments"
    private const val KEEP_MS = 24L * 60 * 60 * 1000

    /** Removes older downloads (and, with [all], every one). */
    fun clean(context: Context, all: Boolean = false) {
        val root = File(context.cacheDir, DIR)
        val limit = System.currentTimeMillis() - KEEP_MS
        runCatching { root.listFiles()?.filter { all || it.lastModified() < limit }?.forEach { it.deleteRecursively() } }
    }

    /** What the user reads when it did not work. */
    fun errorText(e: Throwable): String = when {
        e is BridgeException.Auth -> "Сессия не принимается. Выйдите и войдите снова."
        e is BridgeException.Network && e.v1.startsWith("HTTP ") -> "Сервер не отдаёт файл (${e.v1.take(8).trim()})."
        e is BridgeException.Network -> if (e.v1.contains("redirect")) "Сервер ходит по кругу: файл не получить." else "Нет связи с Госуслугами: файл не скачать."
        e is BridgeException.Parse && e.v1.contains("file too big") -> "Файл больше 50 МБ: не скачиваю."
        e is BridgeException.Parse && e.v1.contains("not a file") -> "Сервер вернул веб-страницу вместо файла. Возможно, нужно войти заново."
        e is BridgeException.Parse -> "Ссылка вложения выглядит небезопасной: не открываю."
        e is ActivityNotFoundException -> "Нет приложения, которое откроет этот файл."
        else -> "Не удалось скачать файл."
    }

    /** The media type for the viewer, from the extension of the cleaned name (not from what the server claims). */
    fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Download and hand over. Returns a message for the user, or `null` when a viewer/share sheet was opened. */
    suspend fun open(context: Context, files: FileSource?, m: Material): String? {
        if (files == null) return "В демо-режиме вложения не скачиваются."
        if (m.link.isBlank()) return "У этого вложения нет ссылки."
        return try {
            val file = withContext(Dispatchers.IO) {
                clean(context)
                val d = AiLog.timed("download") { files.download(m.link) }
                val name = attachmentFileName(m.name, d.fileName)
                // one folder per download, so a name never collides and an old file is never replaced
                val dir = File(File(context.cacheDir, DIR), System.currentTimeMillis().toString()).also { it.mkdirs() }
                File(dir, name).also { it.writeBytes(d.bytes) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = if (canOpen(file.name)) {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeFor(file.name))
            } else {
                Intent(Intent.ACTION_SEND).setType(mimeFor(file.name)).putExtra(Intent.EXTRA_STREAM, uri)
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            withContext(Dispatchers.Main) { context.startActivity(Intent.createChooser(intent, m.name.ifBlank { "Вложение" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            null
        } catch (e: BridgeException) {
            errorText(e)
        } catch (e: ActivityNotFoundException) {
            errorText(e)
        } catch (e: java.io.IOException) {
            "Не удалось сохранить файл."
        }
    }
}

/** The attachments of one homework as chips; a tap downloads and opens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentRow(materials: List<Material>) {
    if (materials.isEmpty()) return
    val context = LocalContext.current
    val files = LocalFiles.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(bottom = 6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            materials.forEachIndexed { i, m ->
                AssistChip(
                    onClick = {
                        if (busy == null) {
                            busy = i; message = "Скачиваю…"
                            scope.launch { message = Attachments.open(context, files, m); busy = null }
                        }
                    },
                    enabled = busy == null || busy == i,
                    label = { Text("📎 " + m.name.ifBlank { "файл" }, maxLines = 1, fontSize = 12.sp) },
                )
            }
        }
        message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
