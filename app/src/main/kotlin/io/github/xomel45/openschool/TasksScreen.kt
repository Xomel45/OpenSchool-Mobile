package io.github.xomel45.openschool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val TASK_DAYS = 4 // days on one page; the arrows turn the page by the same amount
private val RU = Locale("ru")

/**
 * The days of one page: upcoming pages go forward from today, past pages go back from yesterday (newest first).
 * Days the server does not cover are left out.
 */
private fun taskDays(data: SchoolData, upcoming: Boolean, page: Int): List<LocalDate> =
    (0 until TASK_DAYS).map { i ->
        if (upcoming) data.today.plusDays((page * TASK_DAYS + i).toLong()) else data.today.minusDays((1 + page * TASK_DAYS + i).toLong())
    }.filter { it >= data.min && it <= data.max }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(data: SchoolData, onExpired: () -> Unit) {
    var upcoming by rememberSaveable { mutableStateOf(true) }
    var filesTab by rememberSaveable { mutableStateOf(false) }
    var upPage by rememberSaveable { mutableIntStateOf(0) }
    var pastPage by rememberSaveable { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<LoadFailure?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Pair<LessonRow, LocalDate>?>(null) }
    val page = if (upcoming) upPage else pastPage
    val days = taskDays(data, upcoming, page)

    LaunchedEffect(upcoming, page, retry) {
        loading = true
        failure = null
        if (days.isNotEmpty()) try {
            // One week before the first day too: homework due this week is sometimes sent with the week it was issued in.
            val first = days.min().with(DayOfWeek.MONDAY).minusWeeks(1)
            var m = first
            while (m <= days.max()) { data.loadWeek(m); m = m.plusWeeks(1) }
        } catch (e: LoadFailure) {
            if (e.expired) onExpired()
            failure = e
        }
        loading = false
    }

    @Suppress("UNUSED_EXPRESSION") data.revision

    /** «‹» goes towards the past, «›» towards the future; from the first upcoming page «‹» continues into the past and back. */
    fun step(dir: Int) {
        if (upcoming) {
            if (dir < 0 && upPage == 0) { upcoming = false; pastPage = 0 } else upPage += dir
        } else {
            if (dir > 0 && pastPage == 0) { upcoming = true; upPage = 0 } else pastPage -= dir
        }
    }
    val canPrev = if (upcoming) (taskDays(data, false, 0).isNotEmpty() || upPage > 0) else taskDays(data, false, pastPage + 1).isNotEmpty()
    val canNext = if (upcoming) taskDays(data, true, upPage + 1).isNotEmpty() else true

    val label = if (days.isEmpty()) "" else {
        val lo = days.min(); val hi = days.max()
        if (lo == hi) "${lo.dayOfMonth} ${MONTHS[lo.monthValue - 1]}"
        else if (lo.month == hi.month) "${lo.dayOfMonth} – ${hi.dayOfMonth} ${MONTHS[hi.monthValue - 1]}"
        else "${lo.dayOfMonth} ${MONTHS[lo.monthValue - 1].take(3)} – ${hi.dayOfMonth} ${MONTHS[hi.monthValue - 1].take(3)}"
    }

    // The summary is counted from what is on the page.
    val shown = days.map { d -> d to data.buildDay(d) }
    val allHw = shown.flatMap { (_, day) -> day.rows.flatMap { it.homework } + day.orphanHomework }
    val left = allHw.count { !data.isDone(it.id) }

    LazyColumn(Modifier.fillMaxWidth().swipePages(canPrev && !filesTab, canNext && !filesTab, onPrev = { step(-1) }, onNext = { step(1) }), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Задания", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(upcoming && !filesTab, { filesTab = false; upcoming = true; upPage = 0 }, label = { Text("Предстоящие") })
                FilterChip(!upcoming && !filesTab, { filesTab = false; upcoming = false; pastPage = 0 }, label = { Text("Прошедшие") })
                FilterChip(filesTab, { filesTab = true }, label = { Text("Файлы") })
            }
        }
        if (filesTab) item { DownloadsList() }
        if (!filesTab) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ step(-1) }, enabled = canPrev, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("‹", fontSize = 22.sp) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (upcoming) (if (upPage == 0) "ближайшие дни" else "через ${upPage * TASK_DAYS} дн. и позже") else (if (pastPage == 0) "последние дни" else "${pastPage * TASK_DAYS + 1} дн. назад и раньше"),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton({ step(1) }, enabled = canNext, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("›", fontSize = 22.sp) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Только невыполненные", Modifier.weight(1f))
                Switch(data.tasksOpenOnly, data::updateTasksOpenOnly)
            }
            if (allHw.isNotEmpty()) Text("Не выполнено $left из ${allHw.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        failure?.let { f -> item { Column { Text(f.text); Button({ retry++ }, Modifier.padding(top = 8.dp)) { Text("Повторить") } } } }
        if (loading && failure == null) item { Text("Загрузка заданий…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (days.isEmpty()) item { Text("Нет данных за этот период.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (failure == null && !(loading && allHw.isEmpty())) shown.forEach { (d, day) ->
            item(key = "task-$d") { TaskDay(d, day, data, onOpen = { selected = it to d }) }
        }
        }
    }
    selected?.let { (row, day) -> ModalBottomSheet(onDismissRequest = { selected = null }) { LessonDetails(row, day, onChanged = { selected = null }) } }
}

@Composable
private fun TaskDay(d: LocalDate, day: DayData, data: SchoolData, onOpen: (LessonRow) -> Unit) {
    val onlyOpen = data.tasksOpenOnly
    val cards = day.rows.filter { it.homework.isNotEmpty() || data.notes.of(it.lesson.id).isNotEmpty() }.filter { !onlyOpen || it.homework.any { h -> !data.isDone(h.id) } || data.notes.of(it.lesson.id).any { n -> !n.done } }
    val orphans = day.orphanHomework.filter { !onlyOpen || !data.isDone(it.id) }
    val any = day.rows.any { it.homework.isNotEmpty() || data.notes.of(it.lesson.id).isNotEmpty() } || day.orphanHomework.isNotEmpty()
    val word = when (ChronoUnit.DAYS.between(data.today, d).toInt()) { 0 -> " · сегодня"; 1 -> " · завтра"; -1 -> " · вчера"; else -> "" }
    val wd = d.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$wd, ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}$word", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp),
            color = if (d == data.today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        cards.forEach { row ->
            val visible = if (onlyOpen) row.copy(homework = row.homework.filter { !data.isDone(it.id) }) else row
            LessonCard(visible, data, onOpen = { onOpen(row) })
        }
        if (orphans.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Задания без урока", fontWeight = FontWeight.SemiBold)
                orphans.forEach { HomeworkRow(it, data, prefix = it.subjectName.takeIf(String::isNotBlank)?.let { n -> "$n: " } ?: "") }
            }
        }
        if (cards.isEmpty() && orphans.isEmpty()) Text(
            when {
                any -> "Всё выполнено"
                day.rows.isEmpty() -> if (d.dayOfWeek.value >= 6) "Выходной день" else if (d > data.today) "Расписание ещё не опубликовано" else "Уроков нет"
                else -> "Заданий нет"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
