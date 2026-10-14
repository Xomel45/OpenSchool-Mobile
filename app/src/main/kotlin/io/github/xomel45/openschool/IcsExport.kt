package io.github.xomel45.openschool

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The lessons from [monday] to the last published day as calendar events (with tests, topic, homework, my notes, teacher). */
fun icsEvents(data: SchoolData, monday: LocalDate): List<IcsEvent> =
    generateSequence(monday) { it.plusDays(1) }.takeWhile { it <= data.max }.flatMap { d -> data.buildDay(d).rows.asSequence() }.map { r ->
        val l = r.lesson
        val test = lessonTest(r)
        val lines = buildList {
            test?.let { add(it) }
            l.theme?.takeIf { it.isNotBlank() }?.let { add("Тема: $it") }
            r.homework.forEach { add("Д/з: ${it.description.ifBlank { "без текста" }}") }
            data.notes.of(l.id).filter { !it.done }.forEach { add("Заметка: ${it.text}") }
            teacherName(l.teacher.lastName, l.teacher.firstName, l.teacher.patronymic).takeIf { it.isNotBlank() }?.let { add("Ведёт: $it") }
        }
        IcsEvent(l.id, l.start, l.end, if (test != null) "${l.subjectName} · $test" else l.subjectName, if (l.room.isNotBlank()) "Каб. ${l.room}" else "", lines.joinToString("\n"))
    }.toList()

/** «В календарь»: the week of [day] and all the following published weeks into a .ics file the user saves where they like. */
@Composable
fun IcsButton(data: SchoolData, day: LocalDate, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val text = pending
        pending = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false) }
            onMessage(if (ok) "Сохранено. Откройте файл календарём; повторный импорт обновит те же уроки." else "Не удалось сохранить файл.")
        }
    }
    TextButton({
        scope.launch {
            val monday = day.with(DayOfWeek.MONDAY)
            data.loadWeeks(monday, data.max)
            val events = icsEvents(data, monday)
            if (events.isEmpty()) { onMessage("В этих неделях нет уроков: экспортировать нечего."); return@launch }
            pending = buildIcs(events, java.time.LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")))
            save.launch("uroki_${monday}_${data.max}.ics")
        }
    }) { Text("В календарь") }
}
