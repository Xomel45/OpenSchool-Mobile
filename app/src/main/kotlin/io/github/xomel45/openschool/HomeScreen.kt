package io.github.xomel45.openschool

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Mark
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private sealed interface DayState {
    data object Loading : DayState
    data object Ready : DayState
    data class Failed(val error: LoadFailure) : DayState
}

private val RU = Locale("ru")

/** Colour of a mark, the same scale as the desktop app. */
fun markColor(value: String): Color = when (value.lowercase()) {
    "5" -> MarkColors.five
    "4" -> MarkColors.four
    "3" -> MarkColors.three
    "2" -> MarkColors.two
    else -> MarkColors.other
}

@Composable
fun MarkChip(value: String, size: Int = 30) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(markColor(value)), contentAlignment = Alignment.Center) {
        Text(value, color = MarkColors.ink, fontWeight = FontWeight.Bold, fontSize = (size * 0.5).sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(data: SchoolData, news: NewsStore, onBell: () -> Unit, onSearch: () -> Unit, onExpired: () -> Unit) {
    val day = data.homeDay
    var state by remember { mutableStateOf<DayState>(DayState.Loading) }
    var retry by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<LessonRow?>(null) }
    var testOpen by remember { mutableStateOf<Pair<LessonRow, LocalDate>?>(null) }

    // The day's week, then (quietly) the weeks before it so the "recent marks" strip has something to show.
    LaunchedEffect(day, retry) {
        state = DayState.Loading
        state = try {
            data.loadWeek(day)
            DayState.Ready
        } catch (e: LoadFailure) {
            if (e.expired) onExpired()
            DayState.Failed(e)
        }
    }
    LaunchedEffect(Unit) {
        for (i in 1..5) try { data.loadWeek(data.today.minusWeeks(i.toLong())) } catch (_: LoadFailure) { }
    }

    @Suppress("UNUSED_EXPRESSION") data.revision // read: redraw when more weeks arrive
    val dayData = data.buildDay(day)
    val recent = data.recentMarks()

    LazyColumn(
        Modifier.fillMaxWidth().swipePages(day > data.min, day < data.max, onPrev = { data.homeDay = day.minusDays(1) }, onNext = { data.homeDay = day.plusDays(1) }),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StudentCard(data, news, onBell, onSearch) }
        item { NowBanner(data) }
        item {
            Text("Последние оценки", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (recent.isEmpty()) Text("Оценок пока нет", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.id }) { RecentMark(it) }
            }
        }
        item { DayNav(day, data, onDay = { data.homeDay = it }) }
        when (val s = state) {
            DayState.Loading -> if (dayData.rows.isEmpty()) item { Text("Загрузка…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            is DayState.Failed -> item {
                Column {
                    Text(s.error.text)
                    Button(onClick = { retry++ }, Modifier.padding(top = 8.dp)) { Text("Повторить") }
                }
            }
            DayState.Ready -> {}
        }
        if (state != DayState.Loading || dayData.rows.isNotEmpty()) {
            if (state is DayState.Ready && dayData.rows.isEmpty() && dayData.orphanHomework.isEmpty()) item {
                Text(emptyText(day, data.today), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(dayData.rows, key = { it.lesson.id }) { row -> Box(Modifier.animateItem()) { LessonCard(row, data, onOpen = { selected = row }) } }
            if (data.dev.enabled) item { AddLessonButton(day) }
            if (dayData.orphanHomework.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Задания без урока", fontWeight = FontWeight.SemiBold)
                        dayData.orphanHomework.forEach { HomeworkRow(it, data, prefix = it.subjectName.takeIf(String::isNotBlank)?.let { n -> "$n: " } ?: "") }
                    }
                }
            }
        }
        item { UpcomingTestsCard(data, onOpen = { row, d -> testOpen = row to d }) }
    }
    testOpen?.let { (row, d) -> ModalBottomSheet(onDismissRequest = { testOpen = null }) { LessonDetails(row, d, onChanged = { testOpen = null }) } }

    selected?.let { row ->
        ModalBottomSheet(onDismissRequest = { selected = null }) { LessonDetails(row, day, onChanged = { selected = null }) }
    }
}

internal fun emptyText(day: LocalDate, today: LocalDate) = when {
    day.dayOfWeek.value >= 6 -> "Выходной день"
    day.isAfter(today) -> "Расписание на этот день ещё не опубликовано"
    else -> "Уроков нет"
}

@Composable
private fun StudentCard(data: SchoolData, news: NewsStore, onBell: () -> Unit, onSearch: () -> Unit) {
    val s = data.student
    val c = data.classInfo
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar((s.firstName.take(1) + s.lastName.take(1)).uppercase(), 54, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, 19)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("${titleCase(s.firstName)} ${titleCase(s.lastName)}".trim(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (c != null) {
                val school = c.schoolName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                Text("${c.classNumber} «${c.classLetter}»$school", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        androidx.compose.material3.IconButton(onClick = onSearch) { androidx.compose.material3.Icon(Icons.Filled.Search, contentDescription = "Поиск") }
        // The badge springs when something new arrives.
        val pop = remember { androidx.compose.animation.core.Animatable(1f) }
        var lastUnread by remember { mutableStateOf(news.unread) }
        LaunchedEffect(news.unread) {
            if (news.unread > lastUnread) { pop.snapTo(0.4f); pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f)) }
            lastUnread = news.unread
        }
        androidx.compose.material3.BadgedBox(badge = { if (news.unread > 0) androidx.compose.material3.Badge(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) { Text(if (news.unread > 99) "99+" else "${news.unread}") } }) {
            androidx.compose.material3.IconButton(onClick = onBell) { androidx.compose.material3.Icon(Icons.Filled.Notifications, contentDescription = "Что нового") }
        }
    }
}

@Composable
private fun RecentMark(m: Mark) {
    Column(Modifier.widthIn(max = 84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MarkChip(m.value, 40)
        Text(m.subjectName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(shortDate(m.date), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayNav(day: LocalDate, data: SchoolData, onDay: (LocalDate) -> Unit) {
    val diff = java.time.temporal.ChronoUnit.DAYS.between(data.today, day).toInt()
    val sub = when {
        diff == 0 -> "сегодня"; diff == 1 -> "завтра"; diff == -1 -> "вчера"
        diff > 0 -> "через $diff дн."; else -> "${-diff} дн. назад"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onDay(day.minusDays(1)) }, enabled = day > data.min, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("‹", fontSize = 22.sp) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            val wd = day.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() }
            Text("$wd, ${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, fontSize = 15.sp)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (diff != 0) TextButton(onClick = { onDay(data.today) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Сегодня", fontSize = 13.sp) }
        }
        OutlinedButton(onClick = { onDay(day.plusDays(1)) }, enabled = day < data.max, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("›", fontSize = 22.sp) }
    }
}

@Composable
internal fun LessonCard(row: LessonRow, data: SchoolData, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            // A click on the lesson opens it; the homework rows below take their own clicks (only "done" toggles there).
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${row.number}", Modifier.widthIn(min = 22.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.lesson.subjectName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        TestBadge(lessonTest(row))
                    }
                    Text("${hm(row.lesson.start)}–${hm(row.lesson.end)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                row.marks.forEach { Box(Modifier.padding(start = 4.dp)) { MarkChip(it.value, 28) } }
            }
            row.homework.forEach { HomeworkRow(it, data) }
            NoteRows(row.lesson.id, data)
            if (row.homework.isNotEmpty() || data.notes.of(row.lesson.id).isNotEmpty()) Box(Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
internal fun HomeworkRow(h: Homework, data: SchoolData, prefix: String = "") {
    val done = data.isDone(h.id)
    Column {
    Row(Modifier.fillMaxWidth().clickable { data.setDone(h.id, !done) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = done, onCheckedChange = { data.setDone(h.id, it) })
        val ink by androidx.compose.animation.animateColorAsState(if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, androidx.compose.animation.core.tween(250), label = "hw")
        Text(
            prefix + h.description.ifBlank { "Без текста" },
            Modifier.weight(1f), fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
            color = ink,
            textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        )
        HomeworkPencil(h)
    }
    AttachmentCount(h)
    }
}

@Composable
internal fun LessonDetails(row: LessonRow, day: LocalDate, onChanged: () -> Unit = {}) {
    val l = row.lesson
    val state = lessonState(l)
    val dev = devOn()
    var editLesson by remember { mutableStateOf(false) }
    var addHomework by remember { mutableStateOf(false) }
    if (dev != null && editLesson) LessonEditDialog(row, day, dev) { editLesson = false; if (it) onChanged() }
    if (dev != null && addHomework) AddHomeworkDialog(row, day, dev) { addHomework = false; if (it) onChanged() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        if (dev != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            OutlinedButton({ editLesson = true }) { Text("Изменить урок") }
            OutlinedButton({ addHomework = true }) { Text("Добавить задание") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.subjectName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            TestBadge(lessonTest(row))
        }
        Text("${day.dayOfMonth} ${MONTHS[day.monthValue - 1]} · $state", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Fact("Номер урока", "${row.number}-й")
        Fact("Время", "${hm(l.start)}–${hm(l.end)}")
        Fact("Ведёт", teacherName(l.teacher.lastName, l.teacher.firstName, l.teacher.patronymic).ifBlank { "—" })
        Fact("Кабинет", l.room.ifBlank { "—" })
        Section("Тема урока") { Text(l.theme?.takeIf { it.isNotBlank() } ?: "Не указана") }
        Section("Домашнее задание") {
            if (row.homework.isEmpty()) Text("Не задано")
            row.homework.forEach { h ->
                Text(h.description.ifBlank { "Без текста" })
                AttachmentList(h)
            }
        }
        LocalSchoolData.current?.let { NotesSection(l.id, it) }
        Section("Оценка") {
            if (row.marks.isEmpty()) Text("Пока нет")
            row.marks.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    MarkChip(m.value, 44)
                    Text(m.workName ?: m.workType, Modifier.padding(start = 12.dp))
                }
            }
            l.absence?.let { Text("Пропуск: ${it.description.ifBlank { it.code.ifBlank { "без пояснения" } }}", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Fact(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.6f))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(top = 4.dp)) { content() }
    }
}

internal fun lessonState(l: uniffi.openschool_bridge.Lesson): String {
    val now = java.time.LocalDateTime.now()
    return when {
        parseDateTime(l.end) < now -> "урок прошёл"
        parseDateTime(l.start) <= now -> "идёт сейчас"
        else -> "ещё впереди"
    }
}
