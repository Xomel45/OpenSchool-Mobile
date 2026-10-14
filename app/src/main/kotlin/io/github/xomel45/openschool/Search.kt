package io.github.xomel45.openschool

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate

/** One searchable thing: what it is, its day and lesson (if known), the subject and the text. */
data class Found(val kind: String, val date: String, val lessonId: String?, val title: String, val text: String, val value: String? = null)

fun normSearch(s: String) = s.lowercase().replace('ё', 'е')

/** Every word must be in the subject, the text or the kind; newest first. Pure: tested on the JVM. */
fun searchIn(items: List<Found>, query: String): List<Found> {
    val words = normSearch(query).split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return items.filter { f -> val hay = normSearch("${f.title} ${f.text} ${f.kind}"); words.all { it in hay } }.sortedByDescending { it.date }
}

fun searchItems(data: SchoolData): List<Found> {
    val out = mutableListOf<Found>()
    val lessons = data.allLessons()
    val ids = lessons.map { it.id }.toSet()
    for (l in lessons) {
        l.theme?.takeIf { it.isNotBlank() }?.let { out += Found("Тема урока", l.start.take(10), l.id, l.subjectName, it) }
        data.notes.of(l.id).forEach { out += Found("Заметка", l.start.take(10), l.id, l.subjectName, it.text) }
    }
    for (h in data.allHomework()) {
        val lid = h.dueLessonId?.takeIf { it in ids }
        out += Found("Задание", h.dueDate, lid, h.subjectName, h.description)
        h.materials.forEach { out += Found("Файл", h.dueDate, lid, h.subjectName, it.name.ifBlank { "файл" }) }
    }
    for (m in data.allMarks()) {
        val lid = m.lessonId?.takeIf { it in ids }
        out += Found("Оценка", m.date, lid, m.subjectName, listOfNotNull(m.workName, m.workType.ifBlank { null }, m.comment).joinToString(" · "), m.value)
    }
    return out
}

private fun highlight(text: String, words: List<String>, mark: Color) = buildAnnotatedString {
    val low = normSearch(text)
    val hits = words.flatMap { w -> generateSequence(low.indexOf(w).takeIf { it >= 0 }) { i -> low.indexOf(w, i + w.length).takeIf { it >= 0 } }.map { it until it + w.length }.toList() }.sortedBy { it.first }
    var at = 0
    for (r in hits) {
        if (r.first < at || r.last >= text.length) continue
        append(text.substring(at, r.first))
        withStyle(SpanStyle(background = mark, fontWeight = FontWeight.SemiBold)) { append(text.substring(r.first, r.last + 1)) }
        at = r.last + 1
    }
    append(text.substring(at))
}

/** Full-screen search over the school year so far; the first opening loads the missing weeks. A result opens its lesson. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(data: SchoolData, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf<String?>("Загружаю недели учебного года…") }
    var open by remember { mutableStateOf<Pair<LessonRow, LocalDate>?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        data.loadWeeks(data.min, data.max) { n, all -> loading = "Загружаю недели учебного года: $n из $all…" }
        loading = null
    }
    @Suppress("UNUSED_EXPRESSION") data.revision
    val words = normSearch(q).split(Regex("\\s+")).filter { it.isNotBlank() }
    val results = remember(q, data.revision, data.notes.all) { searchIn(searchItems(data), q) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true, placeholder = { Text("Контрольная, §5, презентация…") })
                    TextButton(onClose) { Text("Закрыть") }
                }
                loading?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                Text(
                    when { words.isEmpty() -> "Задания, темы уроков, оценки, заметки и файлы за весь учебный год."; results.isEmpty() -> "Ничего не найдено."; else -> "Найдено: ${results.size}" + if (results.size > 150) ", показаны первые 150" else "" },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                )
                val mark = MaterialTheme.colorScheme.primary.copy(alpha = .25f)
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(results.take(150)) { f ->
                        val d = runCatching { LocalDate.parse(f.date.take(10)) }.getOrNull()
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = f.lessonId != null && d != null) {
                                val row = data.buildDay(d!!).rows.firstOrNull { it.lesson.id == f.lessonId }
                                if (row != null) open = row to d
                            }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(f.kind.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 76.dp, max = 76.dp))
                            f.value?.let { MarkChip(it, 26) }
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(highlight(f.title, words, mark), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(highlight(f.text.ifBlank { "без текста" }, words, mark), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            d?.let { Text("${it.dayOfMonth} ${MONTHS[it.monthValue - 1].take(3)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
        open?.let { (row, d) -> ModalBottomSheet(onDismissRequest = { open = null }) { LessonDetails(row, d, onChanged = { open = null }) } }
    }
}
