package io.github.xomel45.openschool

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val RU = Locale("ru")

/** One day at a time: swipe sideways, use the arrows, the week strip or the calendar to move between days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(data: SchoolData, onExpired: () -> Unit) {
    val days = ChronoUnit.DAYS.between(data.min, data.max).toInt() + 1
    fun pageOf(d: LocalDate) = ChronoUnit.DAYS.between(data.min, d).toInt().coerceIn(0, days - 1)
    val pager = rememberPagerState(initialPage = pageOf(data.scheduleDay)) { days }
    val scope = rememberCoroutineScope()
    var calendar by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<LessonRow, LocalDate>?>(null) }

    val day = data.min.plusDays(pager.currentPage.toLong())
    // Remember the day for the next visit.
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { data.scheduleDay = data.min.plusDays(it.toLong()) } }

    val diff = ChronoUnit.DAYS.between(data.today, day).toInt()
    val sub = when { diff == 0 -> "сегодня"; diff == 1 -> "завтра"; diff == -1 -> "вчера"; diff > 0 -> "через $diff дн."; else -> "${-diff} дн. назад" }
    val monday = day.with(DayOfWeek.MONDAY)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("Расписание", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("‹", fontSize = 22.sp) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val wd = day.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() }
                    Text("$wd, ${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, fontSize = 15.sp)
                    Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton({ scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < days - 1, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("›", fontSize = 22.sp) }
            }
            // The week of the shown day: a tap goes to that day.
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val d = monday.plusDays(i.toLong())
                    val inRange = d >= data.min && d <= data.max
                    val isSel = d == day
                    Surface(
                        Modifier.weight(1f).clickable(enabled = inRange) { scope.launch { pager.animateScrollToPage(pageOf(d)) } },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (inRange) 0.6f else 0.25f),
                        border = if (d == data.today && !isSel) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val ink = if (isSel) MaterialTheme.colorScheme.onPrimary else if (inRange) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() }, fontSize = 11.sp, color = ink)
                            Text("${d.dayOfMonth}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ink)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                if (diff != 0) TextButton({ scope.launch { pager.animateScrollToPage(pageOf(data.today)) } }) { Text("Сегодня") }
                TextButton({ calendar = true }) { Text("Календарь") }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { page ->
            DayPage(data.min.plusDays(page.toLong()), data, onExpired, onOpen = { row, d -> selected = row to d })
        }
    }

    if (calendar) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return d >= data.min && d <= data.max
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { calendar = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { ms -> scope.launch { pager.scrollToPage(pageOf(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())) } }
                    calendar = false
                }) { Text("Показать") }
            },
            dismissButton = { TextButton({ calendar = false }) { Text("Отмена") } },
        ) { DatePicker(state) }
    }
    selected?.let { (row, d) -> ModalBottomSheet(onDismissRequest = { selected = null }) { LessonDetails(row, d, onChanged = { selected = null }) } }
}

@Composable
private fun DayPage(d: LocalDate, data: SchoolData, onExpired: () -> Unit, onOpen: (LessonRow, LocalDate) -> Unit) {
    var failure by remember(d) { mutableStateOf<LoadFailure?>(null) }
    var loading by remember(d) { mutableStateOf(true) }
    var retry by remember(d) { mutableIntStateOf(0) }
    LaunchedEffect(d, retry) {
        loading = true
        failure = null
        try {
            // The previous week too: homework due this week is sometimes sent with the week it was issued in.
            data.loadWeek(d)
            data.loadWeek(d.minusWeeks(1))
        } catch (e: LoadFailure) {
            if (e.expired) onExpired()
            failure = e
        }
        loading = false
    }
    @Suppress("UNUSED_EXPRESSION") data.revision
    val day = data.buildDay(d)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        failure?.let { f -> item { Column { Text(f.text); Button({ retry++ }, Modifier.padding(top = 8.dp)) { Text("Повторить") } } } }
        if (loading && failure == null && day.rows.isEmpty()) item { Text("Загрузка…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!loading && failure == null && day.rows.isEmpty() && day.orphanHomework.isEmpty()) item { Text(emptyText(d, data.today), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(day.rows.size, key = { day.rows[it].lesson.id }) { i -> LessonCard(day.rows[i], data, onOpen = { onOpen(day.rows[i], d) }) }
        if (data.dev.enabled) item { AddLessonButton(d) }
        if (day.orphanHomework.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Задания без урока", fontWeight = FontWeight.SemiBold)
                    day.orphanHomework.forEach { HomeworkRow(it, data, prefix = it.subjectName.takeIf(String::isNotBlank)?.let { n -> "$n: " } ?: "") }
                }
            }
        }
    }
}
