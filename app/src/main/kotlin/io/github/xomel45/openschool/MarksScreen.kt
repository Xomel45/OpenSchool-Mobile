package io.github.xomel45.openschool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private const val YEAR = 0 // a period id: 1..4 are quarters
private const val CHARTS = 5 // «Графики»: the year as charts

private sealed interface Load {
    data class Loading(val done: Int, val total: Int) : Load
    data object Ready : Load
    data class Failed(val error: LoadFailure) : Load
}

/** The quarter that is running now; between quarters, the last one that has started. */
private fun defaultPeriod(data: SchoolData): Int {
    val t = data.today
    val q = data.quarters.firstOrNull { t >= it.second && t <= it.third } ?: data.quarters.lastOrNull { t >= it.second }
    return q?.first ?: 1
}

private fun range(data: SchoolData, period: Int): Pair<LocalDate, LocalDate>? {
    if (data.quarters.isEmpty()) return null
    if (period == YEAR || period == CHARTS) return data.quarters.first().second to data.quarters.last().third
    return data.quarters.firstOrNull { it.first == period }?.let { it.second to it.third }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarksScreen(data: SchoolData, onExpired: () -> Unit) {
    var period by rememberSaveable { mutableIntStateOf(defaultPeriod(data)) }
    var load by remember { mutableStateOf<Load>(Load.Ready) }
    var retry by remember { mutableIntStateOf(0) }
    val span = range(data, period)

    LaunchedEffect(period, retry) {
        val r = range(data, period) ?: return@LaunchedEffect
        if (r.first > data.today) { load = Load.Ready; return@LaunchedEffect }
        load = Load.Loading(0, 1)
        load = try {
            data.loadRange(r.first, r.second) { done, total -> load = Load.Loading(done, total) }
            Load.Ready
        } catch (e: LoadFailure) {
            if (e.expired) onExpired()
            Load.Failed(e)
        }
    }

    @Suppress("UNUSED_EXPRESSION") data.revision // redraw when weeks arrive

    val periods = listOf(1, 2, 3, 4, YEAR, CHARTS)
    LazyColumn(Modifier.fillMaxWidth().swipePages(periods.indexOf(period) > 0, periods.indexOf(period) < periods.lastIndex, onPrev = { period = periods[periods.indexOf(period) - 1] }, onNext = { period = periods[periods.indexOf(period) + 1] }), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Оценки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(periods) { p ->
                    FilterChip(selected = period == p, onClick = { period = p }, label = { Text(when (p) { YEAR -> "Итог за год"; CHARTS -> "Графики"; else -> "$p четверть" }) })
                }
            }
        }
        when {
            span == null -> item { Note("Нет данных о четвертях этого года.") }
            span.first > data.today -> item { Note("Эта четверть ещё не началась.") }
            else -> {
                (load as? Load.Failed)?.let { f -> item { Column { Text(f.error.text); Button(onClick = { retry++ }, Modifier.padding(top = 8.dp)) { Text("Повторить") } } } }
                (load as? Load.Loading)?.let { l -> item { Note("Загрузка оценок… ${l.done} из ${l.total} нед.") } }
                when (period) { CHARTS -> item { ChartsSection(data, span.first, span.second) }; YEAR -> yearTable(data); else -> quarterTable(data, span) }
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.quarterTable(data: SchoolData, span: Pair<LocalDate, LocalDate>) {
    val rows = data.gradeRows(span.first, span.second)
    val marks = data.marksIn(span.first, span.second)
    if (marks.isNotEmpty()) item { Summary(marks) }
    if (rows.isEmpty()) { item { Note("В этой четверти пока нет уроков.") }; return }
    items(rows, key = { it.name }) { r ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    AverageChip(r.average)
                }
                if (r.marks.isEmpty()) Text("Оценок нет", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.marks.forEach { MarkChip(it.value, 32) }
                }
            }
        }
    }
}

/** Year view: a card per subject with the average of every quarter that has begun and the year. */
private fun androidx.compose.foundation.lazy.LazyListScope.yearTable(data: SchoolData) {
    val qs = data.quarters
    if (qs.isEmpty()) return
    val started = qs.map { it.second <= data.today }
    val perQuarter = qs.mapIndexed { i, q -> if (started[i]) data.gradeRows(q.second, q.third) else emptyList() }
    val yearRows = data.gradeRows(qs.first().second, qs.last().third)
    val all = data.marksIn(qs.first().second, qs.last().third)
    if (all.isNotEmpty()) item { Summary(all) }
    items(yearRows, key = { it.name }) { yr ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(yr.name, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    qs.forEachIndexed { i, q ->
                        Labelled("${q.first} чтв", dim = !started[i]) { AverageChip(if (started[i]) perQuarter[i].firstOrNull { it.name == yr.name }?.average else null) }
                    }
                    Labelled("Год") { AverageChip(yr.average) }
                }
            }
        }
    }
}

@Composable
private fun Labelled(label: String, dim: Boolean = false, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dim) 0.5f else 1f), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun AverageChip(x: Double?) {
    if (x == null) {
        Box(Modifier.size(width = 44.dp, height = 30.dp), contentAlignment = Alignment.Center) { Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        Box(Modifier.size(width = 44.dp, height = 30.dp).clip(RoundedCornerShape(8.dp)).background(averageColor(x)), contentAlignment = Alignment.Center) {
            Text(formatAverage(x), color = MarkColors.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(marks: List<uniffi.openschool_bridge.Mark>) {
    val nums = marks.mapNotNull { toNumber(it.value) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column { Text("Средний балл", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(mean(nums)?.let(::formatAverage) ?: "–", fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                Column { Text("Оценок", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${marks.size}", fontWeight = FontWeight.Bold, fontSize = 20.sp) }
            }
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(5, 4, 3, 2).forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) { MarkChip("$g", 26); Text(" ×${nums.count { Math.round(it).toInt() == g }}", fontSize = 14.sp) }
                }
            }
        }
    }
}
