package io.github.xomel45.openschool

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** The developer store while the mode is on (screens show their edit buttons when it is not null). */
val LocalDev = staticCompositionLocalOf<DevStore?> { null }

@Composable
fun devOn(): DevStore? = LocalDev.current?.takeIf { it.enabled }

private val TIME = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")
private fun normTime(s: String) = s.trim().let { if (it.length == 4) "0$it" else it }

/** The bar that is always visible while the mode is on: what is shown here is not what the server said. */
@Composable
fun DevBanner(dev: DevStore) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (dev.overlay.isEmpty) "Режим разработчика" else "Режим разработчика: часть данных изменена и показана только здесь",
            Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer,
        )
        TextButton({ dev.enable(false) }) { Text("Выключить", fontSize = 12.sp) }
    }
}

/** The student's avatar: the developer mode's photo when there is one, otherwise the initials. */
@Composable
fun Avatar(initials: String, size: Int, container: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color, fontSize: Int) {
    val photo = LocalDev.current?.activePhoto
    Box(Modifier.size(size.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        if (photo != null) Image(photo, null, Modifier.size(size.dp), contentScale = ContentScale.Crop)
        else Text(initials, color = ink, fontWeight = FontWeight.Bold, fontSize = fontSize.sp)
    }
}

/** Seven taps on the version line open the developer mode (like Android's own). */
@Composable
fun rememberVersionTapper(dev: DevStore): () -> Unit {
    val context = LocalContext.current
    var taps by remember { mutableStateOf(0) }
    var last by remember { mutableStateOf(0L) }
    return {
        val now = System.currentTimeMillis()
        taps = if (now - last > 2000) 1 else taps + 1
        last = now
        if (!dev.enabled && taps in 4..6) Toast.makeText(context, "До режима разработчика: ${7 - taps} ${plural(7 - taps, "нажатие", "нажатия", "нажатий")}", Toast.LENGTH_SHORT).show()
        if (taps >= 7) {
            taps = 0
            dev.enable(!dev.enabled)
            Toast.makeText(context, if (dev.enabled) "Режим разработчика включён" else "Режим разработчика выключен", Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------- the settings page ----------

@Composable
fun DevSettings(data: SchoolData, dev: DevStore) {
    val o = dev.overlay
    var confirmReset by remember { mutableStateOf(false) }
    var photoMsg by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photoMsg = dev.setPhoto(uri) ?: "" }
    val real = data.realProfile()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Режим разработчика", fontWeight = FontWeight.SemiBold)
                Text("Правки видны только на этом телефоне, на сервер ничего не уходит. Оценки не меняются.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(dev.enabled, dev::enable)
        }
        Section("Профиль") {
            listOf("lastName" to "Фамилия", "firstName" to "Имя", "middleName" to "Отчество", "schoolName" to "Школа", "classNumber" to "Класс (номер)", "classLetter" to "Буква класса", "region" to "Код региона").forEach { (key, label) ->
                OutlinedTextField(
                    o.profile[key] ?: real[key].orEmpty(),
                    { v -> dev.update(o.copy(profile = if (v == real[key].orEmpty()) o.profile - key else o.profile + (key to v))) },
                    Modifier.fillMaxWidth().padding(bottom = 6.dp), label = { Text(label) }, singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (dev.photo == null) "Выбрать фото" else "Другое фото") }
                if (dev.photo != null) OutlinedButton({ dev.clearPhoto() }) { Text("Убрать фото") }
            }
            if (photoMsg.isNotEmpty()) Text(photoMsg, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
        val subjects = data.knownSubjects()
        Section("Предметы") {
            if (subjects.isEmpty()) Text("Пока ни один предмет не загружен: откройте расписание.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            subjects.forEach { name ->
                OutlinedTextField(
                    o.subjects[name] ?: name,
                    { v -> dev.update(o.copy(subjects = if (v == name || v.isBlank()) o.subjects - name else o.subjects + (name to v))) },
                    Modifier.fillMaxWidth().padding(bottom = 6.dp), label = { Text(name) }, singleLine = true,
                )
            }
        }
        val teachers = data.knownTeachers()
        Section("Учителя") {
            if (teachers.isEmpty()) Text("Учителя ещё не загружены.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            teachers.forEach { t ->
                val key = teacherKey(t)
                OutlinedTextField(
                    o.teachers[key] ?: teacherText(t),
                    { v -> dev.update(o.copy(teachers = if (v == teacherText(t) || v.isBlank()) o.teachers - key else o.teachers + (key to v))) },
                    Modifier.fillMaxWidth().padding(bottom = 6.dp), label = { Text(teacherText(t)) }, singleLine = true,
                )
            }
            Text("Фамилия Имя Отчество, через пробел.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section("Уроки и задания") {
            Text("Правятся прямо в расписании: нажмите на урок, там кнопки «Изменить урок» и «Добавить задание». Рядом с домашним заданием появляется карандаш. Кнопка «+ Урок» внизу дня добавляет урок.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Изменено уроков: ${o.lessons.size}, добавлено: ${o.addedLessons.size}. Заданий изменено: ${o.homework.size}, добавлено: ${o.addedHomework.size}.", fontSize = 13.sp)
        }
        OutlinedButton({ confirmReset = true }, Modifier.fillMaxWidth()) { Text("Сбросить все изменения", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false }, title = { Text("Сбросить все изменения?") },
        text = { Text("Профиль, фото, названия предметов и учителей, правки уроков и заданий будут стёрты. Режим разработчика останется включённым.") },
        confirmButton = { TextButton({ confirmReset = false; dev.reset() }) { Text("Сбросить") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Отмена") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

// ---------- editing a lesson / homework (buttons appear only while the mode is on) ----------

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, error: Boolean = false, multiline: Boolean = false) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().padding(bottom = 6.dp), label = { Text(label) }, singleLine = !multiline, isError = error)
}

/** Change a lesson the server sent (or one that was added here). */
@Composable
fun LessonEditDialog(row: LessonRow, date: LocalDate, dev: DevStore, onDone: (changed: Boolean) -> Unit) {
    val l = row.lesson
    val o = dev.overlay
    val added = o.addedLessons.firstOrNull { it.id == l.id }
    var subject by remember { mutableStateOf(l.subjectName) }
    var start by remember { mutableStateOf(hm(l.start).let { if (it.length == 4) "0$it" else it }) }
    var end by remember { mutableStateOf(hm(l.end).let { if (it.length == 4) "0$it" else it }) }
    var room by remember { mutableStateOf(l.room) }
    var teacher by remember { mutableStateOf(teacherText(l.teacher)) }
    var theme by remember { mutableStateOf(l.theme.orEmpty()) }
    val valid = subject.isNotBlank() && TIME.matches(start.trim()) && TIME.matches(end.trim())
    AlertDialog(
        onDismissRequest = { onDone(false) }, title = { Text("Изменить урок") },
        text = {
            Column {
                Field("Предмет", subject, { subject = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field("Начало, чч:мм", start, { start = it }, error = !TIME.matches(start.trim())) }
                    Box(Modifier.weight(1f)) { Field("Конец, чч:мм", end, { end = it }, error = !TIME.matches(end.trim())) }
                }
                Field("Кабинет", room, { room = it })
                Field("Учитель", teacher, { teacher = it })
                Field("Тема урока", theme, { theme = it }, multiline = true)
            }
        },
        confirmButton = {
            TextButton({
                val s = normTime(start); val e = normTime(end)
                if (added != null) {
                    dev.update(o.copy(addedLessons = o.addedLessons.map { if (it.id == added.id) it.copy(subject = subject.trim(), start = s, end = e, room = room, teacher = teacher, theme = theme) else it }))
                } else {
                    // Only what differs from the baseline is stored: what the server said, with the page-wide renaming rules.
                    val orig = dev.rawLesson(l.id) ?: l
                    fun diff(new: String, base: String) = new.takeIf { it != base }
                    val edit = LessonEdit(
                        subject = diff(subject.trim(), o.subjects[orig.subjectName] ?: orig.subjectName), start = diff(s, normTime(hm(orig.start))), end = diff(e, normTime(hm(orig.end))),
                        room = diff(room, orig.room), teacher = diff(teacher.trim(), o.teachers[teacherKey(orig.teacher)] ?: teacherText(orig.teacher)), theme = diff(theme, orig.theme.orEmpty()),
                        deleted = o.lessons[l.id]?.deleted == true,
                    )
                    dev.update(o.copy(lessons = if (edit == LessonEdit()) o.lessons - l.id else o.lessons + (l.id to edit)))
                }
                onDone(true)
            }, enabled = valid) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton({
                    dev.update(if (added != null) o.copy(addedLessons = o.addedLessons.filter { it.id != added.id }) else o.copy(lessons = o.lessons + (l.id to (o.lessons[l.id] ?: LessonEdit()).copy(deleted = true))))
                    onDone(true)
                }) { Text(if (added != null) "Удалить" else "Скрыть урок", color = MaterialTheme.colorScheme.error) }
                TextButton({ onDone(false) }) { Text("Отмена") }
            }
        },
    )
}

@Composable
fun AddLessonDialog(date: LocalDate, dev: DevStore, onDone: (changed: Boolean) -> Unit) {
    var subject by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("08:30") }
    var end by remember { mutableStateOf("09:15") }
    var room by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    val valid = subject.isNotBlank() && TIME.matches(start.trim()) && TIME.matches(end.trim())
    AlertDialog(
        onDismissRequest = { onDone(false) }, title = { Text("Новый урок, ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}") },
        text = {
            Column {
                Field("Предмет", subject, { subject = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field("Начало", start, { start = it }, error = !TIME.matches(start.trim())) }
                    Box(Modifier.weight(1f)) { Field("Конец", end, { end = it }, error = !TIME.matches(end.trim())) }
                }
                Field("Кабинет", room, { room = it })
                Field("Учитель", teacher, { teacher = it })
            }
        },
        confirmButton = {
            TextButton({
                val id = "dev-l-${System.currentTimeMillis()}"
                dev.update(dev.overlay.copy(addedLessons = dev.overlay.addedLessons + AddedLesson(id, date.toString(), normTime(start), normTime(end), subject.trim(), room, teacher, "")))
                onDone(true)
            }, enabled = valid) { Text("Добавить") }
        },
        dismissButton = { TextButton({ onDone(false) }) { Text("Отмена") } },
    )
}

/** Change or remove one homework entry. */
@Composable
fun HomeworkEditDialog(h: uniffi.openschool_bridge.Homework, dev: DevStore, onDone: (changed: Boolean) -> Unit) {
    val o = dev.overlay
    val added = o.addedHomework.firstOrNull { it.id == h.id }
    var text by remember { mutableStateOf(h.description) }
    AlertDialog(
        onDismissRequest = { onDone(false) }, title = { Text("Изменить задание") },
        text = { Field("Текст", text, { text = it }, multiline = true) },
        confirmButton = {
            TextButton({
                if (added != null) dev.update(o.copy(addedHomework = o.addedHomework.map { if (it.id == added.id) it.copy(text = text) else it }))
                else {
                    val orig = dev.rawHomework(h.id) ?: h
                    dev.update(o.copy(homework = if (text == orig.description) o.homework - h.id else o.homework + (h.id to HwEdit(text = text))))
                }
                onDone(true)
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton({
                    dev.update(if (added != null) o.copy(addedHomework = o.addedHomework.filter { it.id != added.id }) else o.copy(homework = o.homework + (h.id to HwEdit(deleted = true))))
                    onDone(true)
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                TextButton({ onDone(false) }) { Text("Отмена") }
            }
        },
    )
}

@Composable
fun AddHomeworkDialog(row: LessonRow, date: LocalDate, dev: DevStore, onDone: (changed: Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(false) }, title = { Text("Новое задание: ${row.lesson.subjectName}") },
        text = { Field("Текст", text, { text = it }, multiline = true) },
        confirmButton = {
            TextButton({
                val id = "dev-h-${System.currentTimeMillis()}"
                dev.update(dev.overlay.copy(addedHomework = dev.overlay.addedHomework + AddedHomework(id, date.toString(), row.lesson.subjectName, text, row.lesson.id)))
                onDone(true)
            }, enabled = text.isNotBlank()) { Text("Добавить") }
        },
        dismissButton = { TextButton({ onDone(false) }) { Text("Отмена") } },
    )
}

/** The pencil next to a homework entry, while the mode is on. */
@Composable
fun HomeworkPencil(h: uniffi.openschool_bridge.Homework) {
    val dev = devOn() ?: return
    var open by remember { mutableStateOf(false) }
    IconButton({ open = true }, Modifier.size(32.dp)) { Icon(Icons.Filled.Edit, contentDescription = "Изменить задание", Modifier.size(18.dp)) }
    if (open) HomeworkEditDialog(h, dev) { open = false }
}

/** "+ Урок" under a day's list, while the mode is on. */
@Composable
fun AddLessonButton(date: LocalDate) {
    val dev = devOn() ?: return
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }) { Text("+ Урок") }
    if (open) AddLessonDialog(date, dev) { open = false }
}

@Composable
fun ProvideDev(dev: DevStore, content: @Composable () -> Unit) = CompositionLocalProvider(LocalDev provides dev, content = content)
