package io.github.xomel45.openschool

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Mark
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One point of the running average: the mark, its value and the average after it. */
data class AvgPoint(val mark: Mark, val date: LocalDate, val value: Double, val average: Double)

/** Numeric marks only, oldest first (by date, then id): the average after each one. Pure: JVM-tested. */
fun runningAverage(marks: Collection<Mark>): List<AvgPoint> {
    var sum = 0.0
    var n = 0
    return marks.sortedWith(compareBy({ it.date }, { it.id })).mapNotNull { m ->
        val v = toNumber(m.value) ?: return@mapNotNull null
        val d = runCatching { LocalDate.parse(m.date.take(10)) }.getOrNull() ?: return@mapNotNull null
        sum += v; n += 1
        AvgPoint(m, d, v, sum / n)
    }
}

/** Lessons with an absence per month of [start]..[end] (months with none are 0), with the subjects. */
data class MonthAbsences(val year: Int, val month: Int, val count: Int, val subjects: Map<String, Int>)

fun absencesByMonth(lessons: Collection<Lesson>, start: LocalDate, end: LocalDate): List<MonthAbsences> {
    val months = generateSequence(start.withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { it <= end }.toList()
    val hit = lessons.filter { it.absence != null }.mapNotNull { l -> runCatching { LocalDate.parse(l.start.take(10)) }.getOrNull()?.takeIf { it in start..end }?.let { it to l.subjectName } }
    return months.map { m ->
        val inMonth = hit.filter { it.first.year == m.year && it.first.monthValue == m.monthValue }
        MonthAbsences(m.year, m.monthValue, inMonth.size, inMonth.groupingBy { it.second }.eachCount())
    }
}

/** An absent lesson with the homework given that day for its subject. */
data class Missed(val lesson: Lesson, val date: LocalDate, val homework: List<uniffi.openschool_bridge.Homework>)

/** «Что я пропустил»: absent lessons of [start]..[end], newest first. Pure: JVM-tested. */
fun missedLessons(lessons: Collection<Lesson>, homework: Collection<uniffi.openschool_bridge.Homework>, start: LocalDate, end: LocalDate): List<Missed> =
    lessons.filter { it.absence != null }.mapNotNull { l ->
        val d = runCatching { LocalDate.parse(l.start.take(10)) }.getOrNull()?.takeIf { it in start..end } ?: return@mapNotNull null
        Missed(l, d, homework.filter { h -> h.issueDate.take(10) == d.toString() && (if (h.subjectId != null) h.subjectId == l.subjectId else h.subjectName == l.subjectName) })
    }.sortedByDescending { it.lesson.start }

private val MONTH_NOM = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
private val MONTH_SHORT = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle, center: Boolean = false, right: Boolean = false) {
    val r = tm.measure(text, style)
    val dx = if (center) r.size.width / 2f else if (right) r.size.width.toFloat() else 0f
    drawText(r, topLeft = Offset(x - dx, y - r.size.height / 2f))
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChartsSection(data: SchoolData, start: LocalDate, end: LocalDate) {
    @Suppress("UNUSED_EXPRESSION") data.revision
    val marks = data.allMarks().filter { it.date.take(10) in start.toString()..end.toString() }
    val subjects = marks.map { it.subjectName }.filter { it.isNotBlank() }.distinct().sorted()
    var subject by rememberSaveable { mutableStateOf("") }
    if (subject.isNotEmpty() && subject !in subjects) subject = ""
    val pts = runningAverage(marks.filter { subject.isEmpty() || it.subjectName == subject })
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Средний балл по датам", fontWeight = FontWeight.SemiBold)
                LazyRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(subject.isEmpty(), { subject = "" }, label = { Text("Все") }) }
                    items(subjects) { s -> FilterChip(subject == s, { subject = s }, label = { Text(s) }) }
                }
                AverageChart(pts, start, minOf(end, data.today.plusDays(1)))
                Text(pts.lastOrNull()?.let { "Сейчас ${formatAverage(it.average)} по ${pts.size} оценкам. Нажмите на график, чтобы увидеть оценку." } ?: "Оценок пока нет.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val months = absencesByMonth(data.allLessons(), start, minOf(end, data.today))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Пропуски по месяцам", fontWeight = FontWeight.SemiBold)
                AbsenceChart(months)
                val total = months.sumOf { it.count }
                Text(if (total > 0) "Всего пропусков за год: $total. Нажмите на столбец, чтобы увидеть предметы." else "Пропусков нет.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val missed = missedLessons(data.allLessons(), data.allHomework(), start, minOf(end, data.today))
        var open by remember { mutableStateOf<Missed?>(null) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Что я пропустил", fontWeight = FontWeight.SemiBold)
                if (missed.isEmpty()) Text("Пропусков нет.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                missed.forEach { m ->
                    Column(Modifier.fillMaxWidth().clickable { open = m }.padding(vertical = 4.dp)) {
                        Text(m.lesson.subjectName, fontWeight = FontWeight.SemiBold)
                        Text("${m.date.dayOfMonth} ${MONTHS[m.date.monthValue - 1]}, ${hm(m.lesson.start)}" + (m.lesson.absence?.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Тема: ${m.lesson.theme?.takeIf { it.isNotBlank() } ?: "не указана"}", fontSize = 13.sp)
                        m.homework.forEach { h -> Text("Задали: ${h.description.ifBlank { "без текста" }} (на ${shortDate(h.dueDate)})", fontSize = 13.sp) }
                    }
                }
            }
        }
        open?.let { m ->
            val row = data.buildDay(m.date).rows.firstOrNull { it.lesson.id == m.lesson.id }
            if (row == null) open = null
            else androidx.compose.material3.ModalBottomSheet(onDismissRequest = { open = null }) { LessonDetails(row, m.date, onChanged = { open = null }) }
        }
    }
}

@Composable
private fun AverageChart(pts: List<AvgPoint>, start: LocalDate, end: LocalDate) {
    val tm = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    var picked by remember(pts) { mutableStateOf<Int?>(null) }
    val days = ChronoUnit.DAYS.between(start, end).coerceAtLeast(1).toFloat()
    fun pick(x: Float, w: Float, left: Float) {
        if (pts.isEmpty()) return
        picked = pts.indices.minByOrNull { kotlin.math.abs(left + ChronoUnit.DAYS.between(start, pts[it].date) / days * (w - left - 8f) - x) }
    }
    Canvas(
        Modifier.fillMaxWidth().height(220.dp)
            .pointerInput(pts) { detectTapGestures { pick(it.x, size.width.toFloat(), 34.dp.toPx()) } }
            .pointerInput(pts) { detectDragGestures(onDragEnd = {}) { c, _ -> pick(c.position.x, size.width.toFloat(), 34.dp.toPx()) } },
    ) {
        val L = 34.dp.toPx(); val R = 8f; val T = 10.dp.toPx(); val B = 22.dp.toPx()
        val w = size.width; val h = size.height
        fun x(d: LocalDate) = L + ChronoUnit.DAYS.between(start, d) / days * (w - L - R)
        fun y(v: Double) = T + ((5 - v.coerceIn(2.0, 5.0)) / 3 * (h - T - B)).toFloat()
        val tick = TextStyle(fontSize = 10.sp, color = muted)
        for (v in 2..5) { drawLine(grid, Offset(L, y(v.toDouble())), Offset(w - R, y(v.toDouble())), 1f); label(tm, "$v", L - 8.dp.toPx(), y(v.toDouble()), tick, right = true) }
        for ((v, t) in listOf(4.5 to "на 5", 3.5 to "на 4")) {
            drawLine(muted.copy(alpha = .6f), Offset(L, y(v)), Offset(w - R, y(v)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f)))
            label(tm, t, w - R, y(v) - 7.dp.toPx(), tick.copy(fontSize = 9.sp), right = true)
        }
        var m = start.withDayOfMonth(1)
        while (m <= end) { val d = if (m < start) start else m; label(tm, MONTH_SHORT[m.monthValue - 1], x(d) + if (d == start) 10.dp.toPx() else 0f, h - B / 2, tick, center = true); m = m.plusMonths(1) }
        if (pts.isEmpty()) return@Canvas
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.date), y(p.average)) else path.lineTo(x(p.date), y(p.average)) }
        drawPath(path, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        pts.forEach { p -> drawCircle(surface, 5.dp.toPx(), Offset(x(p.date), y(p.average))); drawCircle(accent, 3.5.dp.toPx(), Offset(x(p.date), y(p.average))) }
        picked?.let { i ->
            val p = pts[i]
            val px = x(p.date); val py = y(p.average)
            drawLine(muted, Offset(px, T), Offset(px, h - B), 1f)
            drawCircle(accent, 6.dp.toPx(), Offset(px, py), style = Stroke(2.dp.toPx()))
            val text = "${shortDate(p.mark.date)} · ${p.mark.subjectName}\nОценка ${p.mark.value} · средний ${formatAverage(p.average)}"
            val r = tm.measure(text, TextStyle(fontSize = 11.sp, color = ink))
            val bx = (if (px + 10 + r.size.width + 12 > w) px - r.size.width - 22 else px + 10).coerceAtLeast(0f)
            val by = (py - r.size.height / 2f - 6).coerceIn(0f, h - r.size.height - 12)
            drawRoundRect(surface, Offset(bx, by), Size(r.size.width + 12f, r.size.height + 12f), CornerRadius(12f))
            drawText(r, topLeft = Offset(bx + 6, by + 6))
        }
    }
}

@Composable
private fun AbsenceChart(months: List<MonthAbsences>) {
    val tm = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surfaceVariant
    var picked by remember(months) { mutableStateOf<Int?>(null) }
    if (months.isEmpty()) return
    val top = (maxOf(4, months.maxOf { it.count }) + 3) / 4 * 4
    Canvas(Modifier.fillMaxWidth().height(170.dp).pointerInput(months) {
        detectTapGestures { o -> val L = 30.dp.toPx(); val i = ((o.x - L) / ((size.width - L) / months.size)).toInt(); picked = i.takeIf { it in months.indices && it != picked } }
    }) {
        val L = 30.dp.toPx(); val T = 10.dp.toPx(); val B = 22.dp.toPx()
        val w = size.width; val h = size.height
        val bw = (w - L) / months.size
        fun y(v: Int) = T + (1 - v / top.toFloat()) * (h - T - B)
        val tick = TextStyle(fontSize = 10.sp, color = muted)
        for (v in 0..top step top / 4) { drawLine(grid, Offset(L, y(v)), Offset(w, y(v)), 1f); label(tm, "$v", L - 8.dp.toPx(), y(v), tick, right = true) }
        months.forEachIndexed { i, mo ->
            val cx = L + i * bw + bw / 2
            val bwid = minOf(28.dp.toPx(), bw - 8)
            if (mo.count > 0) {
                val yy = y(mo.count)
                drawRoundRect(accent, Offset(cx - bwid / 2, yy), Size(bwid, h - B - yy), CornerRadius(4.dp.toPx()))
                drawRect(accent, Offset(cx - bwid / 2, yy + 4.dp.toPx()), Size(bwid, (h - B - yy - 4.dp.toPx()).coerceAtLeast(0f))) // square at the baseline
                label(tm, "${mo.count}", cx, yy - 8.dp.toPx(), TextStyle(fontSize = 10.sp, color = ink, fontWeight = FontWeight.SemiBold), center = true)
            }
            label(tm, MONTH_SHORT[mo.month - 1], cx, h - B / 2, tick, center = true)
        }
        picked?.let { i ->
            val mo = months[i]
            val text = "${MONTH_NOM[mo.month - 1]} ${mo.year}: ${mo.count}" + mo.subjects.entries.sortedByDescending { it.value }.joinToString("") { "\n${it.key}: ${it.value}" }
            val r = tm.measure(text, TextStyle(fontSize = 11.sp, color = ink))
            val cx = L + i * bw + bw / 2
            val bx = (cx - r.size.width / 2f - 6).coerceIn(0f, w - r.size.width - 12)
            drawRoundRect(surface, Offset(bx, T), Size(r.size.width + 12f, r.size.height + 12f), CornerRadius(12f))
            drawText(r, topLeft = Offset(bx + 6, T + 6))
        }
    }
}
