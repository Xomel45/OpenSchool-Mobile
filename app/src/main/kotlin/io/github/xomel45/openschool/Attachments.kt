package io.github.xomel45.openschool

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.openschool_bridge.BridgeException
import uniffi.openschool_bridge.Download
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Material
import uniffi.openschool_bridge.attachmentFileName
import uniffi.openschool_bridge.canOpen
import java.io.File

/** Where attachments come from: the Bridge in the app (it checks the address, the size and the kind of answer); nothing in the demo. */
fun interface FileSource {
    suspend fun download(link: String): Download
}

val LocalFiles = staticCompositionLocalOf<FileSource?> { null }

/** One downloaded attachment. [path] is relative to the downloads folder. */
data class Saved(val id: String, val link: String, val path: String, val size: Long, val at: Long, val subject: String, val due: String, val hw: String) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * The list of downloaded files (`index.json` in [root]). Files the user can no longer reach are dropped from it; only paths
 * written here are ever opened or deleted (an entry is found by its id). Plain JVM code: tested without a device.
 */
class DownloadIndex(val root: File) {
    private val file get() = File(root, "index.json")

    private fun read(): List<Saved> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Saved(o.getString("id"), o.getString("link"), o.getString("path"), o.getLong("size"), o.getLong("at"), o.optString("subject"), o.optString("due"), o.optString("hw"))
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<Saved>) {
        root.mkdirs()
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("link", it.link).put("path", it.path).put("size", it.size).put("at", it.at).put("subject", it.subject).put("due", it.due).put("hw", it.hw)) }
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    /** A path from the list, never outside [root]. */
    fun fileOf(s: Saved): File? = File(root, s.path).canonicalFile.takeIf { it.path.startsWith(root.canonicalPath + File.separator) }

    /** Newest first, without the files that are gone. */
    @Synchronized fun list(): List<Saved> {
        val all = read()
        val live = all.filter { fileOf(it)?.isFile == true }
        if (live.size != all.size) write(live)
        return live.sortedByDescending { it.at }
    }

    /** Save the bytes in a folder of their own (a name never collides, an old file is never replaced) and write them down. */
    @Synchronized fun add(link: String, name: String, bytes: ByteArray, subject: String, due: String, hw: String, now: Long): Saved {
        val id = "${now.toString(36)}${(0..9999).random().toString(36)}"
        val dir = File(root, id).also { it.mkdirs() }
        File(dir, name).writeBytes(bytes)
        val s = Saved(id, link, "$id/$name", bytes.size.toLong(), now, subject, due, hw)
        write(read() + s)
        return s
    }

    @Synchronized fun delete(id: String): Boolean {
        val all = read()
        val s = all.find { it.id == id } ?: return false
        fileOf(s)?.parentFile?.takeIf { it != root.canonicalFile }?.deleteRecursively()
        write(all - s)
        return true
    }

    @Synchronized fun clear() { root.deleteRecursively() }
}

/** The coloured badge of a file: the kind at a glance. */
fun fileKind(name: String): Pair<String, Long> {
    val e = name.substringAfterLast('.', "").lowercase().takeIf { name.contains('.') && !name.startsWith(".") } ?: ""
    return when (e) {
        "pdf" -> "PDF" to 0xFFD9453B
        "doc", "docx", "odt", "rtf", "txt" -> "DOC" to 0xFF2F6FD6
        "xls", "xlsx", "ods", "csv" -> "XLS" to 0xFF1F8A4C
        "ppt", "pptx", "odp" -> "PPT" to 0xFFD9702B
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic" -> "IMG" to 0xFF8B5CF6
        "mp3", "wav", "ogg", "m4a", "flac" -> "AUD" to 0xFF0E9AA7
        "mp4", "webm", "mkv", "avi", "mov" -> "VID" to 0xFFC2417F
        "zip", "rar", "7z", "tar", "gz" -> "ZIP" to 0xFF8A6D3B
        "" -> "ФАЙЛ" to 0xFF7A8394
        else -> e.take(4).uppercase() to 0xFF7A8394
    }
}

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${(b + 512) / 1024} КБ"
    else -> String.format(java.util.Locale.ROOT, "%.1f МБ", b / 1024.0 / 1024.0).replace('.', ',')
}

/**
 * Attachments: downloaded into the app's own files (kept until deleted or logout), opened by a viewer for documents, pictures and
 * media, otherwise only offered for sharing/saving. Nothing is requested from the user's storage; a viewer gets a one-file read
 * permission through a FileProvider.
 */
object Attachments {
    private const val DIR = "attachments"
    private var index: DownloadIndex? = null

    /** What is downloaded now (the screens redraw when it changes). */
    var saved by mutableStateOf<List<Saved>>(emptyList())
        private set

    fun index(context: Context): DownloadIndex = index ?: DownloadIndex(File(context.filesDir, DIR)).also { index = it }

    fun refresh(context: Context) { saved = index(context).list() }

    /** Logout: the files belong to the account. The old 24-hour cache folder goes too. */
    fun clean(context: Context, all: Boolean = false) {
        runCatching { File(context.cacheDir, DIR).deleteRecursively() }
        if (all) { index(context).clear(); saved = emptyList() }
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

    /** Download and write down. Returns the saved file or a message for the user. */
    suspend fun download(context: Context, files: FileSource?, m: Material, h: Homework): Result<Saved> {
        if (files == null) return Result.failure(IllegalStateException("В демо-режиме вложения не скачиваются."))
        if (m.link.isBlank()) return Result.failure(IllegalStateException("У этого вложения нет ссылки."))
        return try {
            val s = withContext(Dispatchers.IO) {
                val d = AiLog.timed("download") { files.download(m.link) }
                index(context).add(m.link, attachmentFileName(m.name, d.fileName), d.bytes, h.subjectName, h.dueDate, h.id, System.currentTimeMillis())
            }
            refresh(context)
            Result.success(s)
        } catch (e: BridgeException) {
            Result.failure(IllegalStateException(errorText(e)))
        } catch (e: java.io.IOException) {
            Result.failure(IllegalStateException("Не удалось сохранить файл."))
        }
    }

    /** Open with a viewer (documents, pictures, media) or offer to share/save (any other kind). Returns a message or null. */
    fun open(context: Context, s: Saved, share: Boolean = false): String? {
        val file = index(context).fileOf(s)?.takeIf { it.isFile } ?: run { refresh(context); return "Файла больше нет." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = if (!share && canOpen(file.name)) Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeFor(file.name))
        else Intent(Intent.ACTION_SEND).setType(mimeFor(file.name)).putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(intent, s.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            null
        } catch (e: ActivityNotFoundException) { errorText(e) }
    }

    fun delete(context: Context, s: Saved) { index(context).delete(s.id); refresh(context) }
}

@Composable
private fun KindBadge(name: String, size: Int = 36) {
    val (label, color) = fileKind(name)
    Box(Modifier.size(size.dp).background(Color(color), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = (size / 3.6).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The file list of one homework: kind, name, «Скачан · size», and Открыть / Скачать / Поделиться. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentList(h: Homework) {
    if (h.materials.isEmpty()) return
    val context = LocalContext.current
    val files = LocalFiles.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Attachments.refresh(context) }
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        h.materials.forEach { m ->
            val got = Attachments.saved.firstOrNull { it.link == m.link }
            val name = got?.name ?: m.name.ifBlank { "файл" }
            val openable = canOpen(name)
            fun get(thenOpen: Boolean) {
                if (busy != null) return
                busy = m.link; message = "Скачиваю…"
                scope.launch {
                    val r = Attachments.download(context, files, m, h)
                    busy = null
                    message = r.fold({ s -> if (thenOpen) Attachments.open(context, s) else "Сохранено в приложении: «Задания» → «Файлы»." }, { it.message })
                }
            }
            Row(
                Modifier.fillMaxWidth().border(1.dp, if (got != null) MaterialTheme.colorScheme.primary.copy(alpha = .5f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KindBadge(name)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when { busy == m.link -> "Скачиваю…"; got != null -> "Скачан · ${fmtSize(got.size)}"; m.link.isBlank() -> "Нет ссылки"; else -> "Не скачан" },
                        fontSize = 12.sp, color = if (got != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (m.link.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
                if (got != null) {
                    if (openable) OutlinedButton({ message = Attachments.open(context, got) }) { Text("Открыть") }
                    TextButton({ message = Attachments.open(context, got, share = true) }) { Text("Поделиться") }
                } else {
                    if (openable) OutlinedButton({ get(true) }, enabled = busy == null) { Text("Открыть") }
                    TextButton({ get(false) }, enabled = busy == null) { Text("Скачать") }
                }
            }
        }
        message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** «📎 2 · скачано 1» under a homework in the lists (the full list is in the lesson card). */
@Composable
fun AttachmentCount(h: Homework) {
    if (h.materials.isEmpty()) return
    val got = h.materials.count { m -> Attachments.saved.any { it.link == m.link } }
    Text("📎 ${h.materials.size}" + if (got > 0) " · скачано $got" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, bottom = 4.dp))
}

/** «Задания» → «Файлы»: everything downloaded, newest first. */
@Composable
fun DownloadsList() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Attachments.refresh(context) }
    val list = Attachments.saved
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (list.isEmpty()) "Скачанных файлов пока нет. Откройте урок и нажмите «Скачать» или «Открыть» у файла задания."
            else "Файлов: ${list.size} · ${fmtSize(list.sumOf { it.size })}. Хранятся в приложении до удаления или выхода из аккаунта.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        list.forEach { s ->
            Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindBadge(s.name, 40)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val due = runCatching { java.time.LocalDate.parse(s.due) }.getOrNull()
                        Text(listOfNotNull(s.subject.ifBlank { null }, due?.let { "задание на ${it.dayOfMonth} ${MONTHS[it.monthValue - 1]}" }).joinToString(" · ").ifBlank { "Без задания" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val at = java.time.Instant.ofEpochMilli(s.at).atZone(java.time.ZoneId.systemDefault())
                        Text("${fmtSize(s.size)} · скачан ${at.dayOfMonth} ${MONTHS[at.monthValue - 1]}, ${"%02d:%02d".format(at.hour, at.minute)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (canOpen(s.name)) OutlinedButton({ message = Attachments.open(context, s) }) { Text("Открыть") }
                    TextButton({ message = Attachments.open(context, s, share = true) }) { Text("Поделиться") }
                    TextButton({
                        if (armed == s.id) { Attachments.delete(context, s); armed = null; message = "Файл удалён." }
                        else { armed = s.id; scope.launch { delay(4000); if (armed == s.id) armed = null } }
                    }) { Text(if (armed == s.id) "Точно удалить?" else "Удалить", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
