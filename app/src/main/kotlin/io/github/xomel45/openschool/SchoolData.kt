package io.github.xomel45.openschool

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.openschool_bridge.BridgeException
import uniffi.openschool_bridge.Client
import uniffi.openschool_bridge.ClassInfo
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Mark
import uniffi.openschool_bridge.Student
import uniffi.openschool_bridge.Week
import java.time.LocalDate
import java.time.temporal.IsoFields

/** One lesson of a day with its homework and marks. */
data class LessonRow(val number: Int, val lesson: Lesson, val homework: List<Homework>, val marks: List<Mark>)

data class DayData(val rows: List<LessonRow>, val orphanHomework: List<Homework>)

/** Why a week could not be loaded; `text` is what the user reads. */
class LoadFailure(val text: String, val expired: Boolean = false) : Exception(text)

/** Weeks loaded so far (by ISO week), merged the way the desktop app does it. Compose reads [revision] to know when to redraw. */
/** Where weeks come from: the Bridge in the app, invented data in the demo mode. */
fun interface WeekSource {
    suspend fun week(studentId: String, year: UInt, isoWeek: UInt): Week
}

class SchoolData(private val source: WeekSource, val student: Student, val classInfo: ClassInfo?, context: Context) {
    val today: LocalDate = LocalDate.now()
    val min: LocalDate = classInfo?.quarters?.firstOrNull()?.startDate?.let { LocalDate.parse(it.take(10)) }
        ?: LocalDate.of(if (today.monthValue >= 8) today.year else today.year - 1, 9, 1)
    val max: LocalDate = today.plusDays(21) // how far ahead the server publishes lessons is unknown

    var revision by mutableIntStateOf(0)
        private set

    /** The day shown on the home page; kept here so it survives switching to another tab and back. */
    var homeDay by mutableStateOf(today)

    /** The day shown on the schedule page (kept here so it survives switching tabs). */
    var scheduleDay by mutableStateOf(today)

    private val settings = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    /** "Only not done" on the tasks page. */
    var tasksOpenOnly by mutableStateOf(settings.getBoolean("tasksOpenOnly", false))
        private set

    fun updateTasksOpenOnly(v: Boolean) { tasksOpenOnly = v; settings.edit().putBoolean("tasksOpenOnly", v).apply() }

    /** Quarters as (number, first day, last day), in order. */
    val quarters: List<Triple<Int, LocalDate, LocalDate>> =
        classInfo?.quarters?.map { Triple(it.number.toInt(), LocalDate.parse(it.startDate.take(10)), LocalDate.parse(it.endDate.take(10))) } ?: emptyList()

    // Bridge calls never run on the main thread (the TLS check on Android may use the network).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val weeks = HashMap<String, Deferred<Week>>()
    private val lessonsByDate = HashMap<String, MutableMap<String, Lesson>>()
    private val marks = HashMap<String, Mark>()
    private val homework = HashMap<String, Homework>()

    private val prefs = context.getSharedPreferences("homework-done", Context.MODE_PRIVATE)
    private var done by mutableStateOf(prefs.all.keys.toSet())

    fun isDone(id: String) = id in done

    /** Forget every "done" tick (they live only on this phone). */
    fun clearDone() { done = emptySet(); prefs.edit().clear().apply() }

    fun setDone(id: String, value: Boolean) {
        done = if (value) done + id else done - id
        prefs.edit().apply { if (value) putBoolean(id, true) else remove(id) }.apply()
    }

    /** Loads the week the date belongs to (once; a failed load is forgotten so the next call retries). */
    suspend fun loadWeek(date: LocalDate) {
        val year = date.get(IsoFields.WEEK_BASED_YEAR)
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val key = "$year-$week"
        val request = lock.withLock {
            // The request belongs to this object, not to the caller: a screen that is left must not cancel a load others wait for.
            weeks.getOrPut(key) { scope.async { source.week(student.id, year.toUInt(), week.toUInt()).also { merge(it) } } }
        }
        try {
            request.await()
        } catch (e: BridgeException) {
            lock.withLock { weeks.remove(key) }
            throw failure(e)
        }
    }

    private suspend fun merge(w: Week) = lock.withLock {
        w.lessons.forEach { lessonsByDate.getOrPut(it.start.take(10)) { HashMap() }[it.id] = it }
        w.marks.forEach { marks[it.id] = it }
        w.homeworks.forEach { homework[it.id] = it }
        withContext(Dispatchers.Main) { revision++ }
    }

    /** Lessons of one day with their homework and marks (see [buildDayRows]). */
    fun buildDay(date: LocalDate): DayData =
        buildDayRows(date, lessonsByDate[date.toString()]?.values ?: emptyList(), homework.values, marks.values)

    /** Loads every week of the period that has already begun, four at a time (be gentle with the server). */
    suspend fun loadRange(start: LocalDate, end: LocalDate, progress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val last = if (end < today) end else today
        if (start > last) return
        val weeks = generateSequence(start.with(java.time.DayOfWeek.MONDAY)) { it.plusWeeks(1) }.takeWhile { it <= last }.toList()
        var done = 0
        weeks.chunked(4).forEach { batch ->
            coroutineScope { batch.map { async { loadWeek(it); progress(++done, weeks.size) } }.forEach { it.await() } }
        }
    }

    /** One entry per subject seen in the period (see [gradeRowsOf]). */
    fun gradeRows(start: LocalDate, end: LocalDate): List<GradeRow> =
        gradeRowsOf(start, end, lessonsByDate.values.flatMap { it.values }, marks.values)

    /** All marks of the period (for the summary). */
    fun marksIn(start: LocalDate, end: LocalDate): List<Mark> = marks.values.filter { it.date in start.toString()..end.toString() }

    /** The newest marks up to today. */
    fun recentMarks(limit: Int = 16): List<Mark> {
        val todayKey = today.toString()
        return marks.values.filter { it.date <= todayKey }.sortedWith(compareByDescending<Mark> { it.date }.thenByDescending { it.id }).take(limit)
    }

    private fun failure(e: BridgeException): LoadFailure = when (e) {
        is BridgeException.Auth -> LoadFailure("Сессия истекла. Войдите снова.", expired = true)
        is BridgeException.Network -> LoadFailure(
            if (e.v1.startsWith("HTTP ")) "Госуслуги ответили ошибкой: ${e.v1}" else "Не удалось связаться с Госуслугами. Проверьте интернет."
        )
        else -> LoadFailure("Неожиданный ответ Госуслуг: ${e.message}")
    }
}
