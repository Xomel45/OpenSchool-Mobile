package io.github.xomel45.openschool

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

private const val NOTE_MAX = 300

/** The signed-in data, for cards that are opened from several screens (the lesson card). */
val LocalSchoolData = androidx.compose.runtime.staticCompositionLocalOf<SchoolData?> { null }

/** My notes for lessons, kept in this phone's settings as one JSON list. The evening summary reads them from there. */
class NotesStore(context: Context) {
    private val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)

    var all by mutableStateOf(read(prefs.getString("list", "[]")))
        private set

    fun of(lessonId: String) = all.filter { it.lesson == lessonId }

    fun add(lessonId: String, text: String) {
        val t = text.trim().take(NOTE_MAX)
        if (t.isEmpty()) return
        save(all + Note("${System.currentTimeMillis().toString(36)}${(0..999).random()}", lessonId, t, false))
    }
    fun setDone(id: String, done: Boolean) = save(all.map { if (it.id == id) it.copy(done = done) else it })
    fun remove(id: String) = save(all.filter { it.id != id })

    private fun save(list: List<Note>) {
        all = list
        prefs.edit().putString("list", write(list)).apply()
    }

    companion object {
        fun read(json: String?): List<Note> = runCatching {
            val a = JSONArray(json ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.map { Note(it.getString("id"), it.getString("lesson"), it.getString("text"), it.optBoolean("done")) }
        }.getOrDefault(emptyList())

        fun write(list: List<Note>): String = JSONArray().also { a -> list.forEach { a.put(JSONObject().put("id", it.id).put("lesson", it.lesson).put("text", it.text).put("done", it.done)) } }.toString()

        /** For the background work: the notes without a screen. */
        fun load(context: Context): List<Note> = read(context.getSharedPreferences("notes", Context.MODE_PRIVATE).getString("list", "[]"))
    }
}

/** A small red tag: «Контрольная», «Тест», … */
@Composable
fun TestBadge(label: String?) {
    if (label == null) return
    val c = MaterialTheme.colorScheme.error
    Text(
        label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c, maxLines = 1,
        modifier = Modifier.padding(start = 6.dp).border(1.dp, c.copy(alpha = .45f), RoundedCornerShape(50)).background(c.copy(alpha = .1f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The notes of a lesson as rows under its homework: a tick, the text, ×. */
@Composable
fun NoteRows(lessonId: String, data: SchoolData) {
    data.notes.of(lessonId).forEach { n ->
        Row(Modifier.fillMaxWidth().clickable { data.notes.setDone(n.id, !n.done) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(n.done, { data.notes.setDone(n.id, it) })
            Text(
                "📝 ${n.text}", Modifier.weight(1f), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = if (n.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (n.done) TextDecoration.LineThrough else null,
            )
            IconButton({ data.notes.remove(n.id) }) { Text("×", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** «Мои заметки» in the lesson card: the list and a field to add one. */
@Composable
fun NotesSection(lessonId: String, data: SchoolData) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 12.dp)) {
        Text("Мои заметки", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (data.notes.of(lessonId).isEmpty()) Text("Пока нет. Например: «взять форму» или «сдать тетрадь».", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        NoteRows(lessonId, data)
        val add = { data.notes.add(lessonId, text); text = "" }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(
                text, { text = it.take(NOTE_MAX) }, Modifier.weight(1f), placeholder = { Text("Новая заметка") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
            )
            OutlinedButton(add, Modifier.padding(start = 8.dp), enabled = text.isNotBlank()) { Text("Добавить") }
        }
    }
}

/** Tests of the coming two weeks (today included, finished lessons left out), from the weeks loaded so far. */
fun upcomingTests(data: SchoolData, days: Int = 14): List<Pair<LocalDate, LessonRow>> {
    val now = LocalDateTime.now()
    return (0 until days).flatMap { i ->
        val d = data.today.plusDays(i.toLong())
        data.buildDay(d).rows.filter { lessonTest(it) != null && (runCatching { LocalDateTime.parse(it.lesson.end.take(19).replace(' ', 'T')) }.getOrNull()?.isAfter(now) ?: true) }.map { d to it }
    }
}

/** The home page's list of coming tests; a tap opens the lesson. */
@Composable
fun UpcomingTestsCard(data: SchoolData, onOpen: (LessonRow, LocalDate) -> Unit) {
    LaunchedEffect(Unit) { for (i in 1..2) try { data.loadWeek(data.today.plusWeeks(i.toLong())) } catch (_: LoadFailure) { } }
    // Counted again only when the data or the notes change, not on every redraw of the home page (slow phones).
    val list = remember(data.revision, data.notes.all) { upcomingTests(data) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Скоро контрольные", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list.isEmpty()) Text("В ближайшие две недели не найдено. Контрольные узнаются по теме урока и тексту задания.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEach { (d, row) ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(row, d) }) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TestBadge(lessonTest(row))
                    Text(row.lesson.subjectName, Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val word = when (java.time.temporal.ChronoUnit.DAYS.between(data.today, d).toInt()) { 0 -> "сегодня, "; 1 -> "завтра, "; else -> "" }
                    Text("$word${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}, ${row.number}-й урок", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** «Сейчас: Алгебра, каб. 21 · до конца 12 минут» on the home page, redrawn every 30 seconds. */
@Composable
fun NowBanner(data: SchoolData) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }
    @Suppress("UNUSED_EXPRESSION") data.revision
    val state = nowState(data.buildDay(now.toLocalDate()).rows.map { it.lesson }, now) ?: return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(state.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(state.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
