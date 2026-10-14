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

class SchoolData(private val source: WeekSource, private val realStudent: Student, private val realClass: ClassInfo?, context: Context, val dev: DevStore = DevStore(context), val files: FileSource? = null) {
    /** What the screens show: the server's student and class, with the developer mode's changes when it is on. */
    val student: Student get() = dev.active.applyStudent(realStudent)
    val classInfo: ClassInfo? get() = dev.active.applyClass(realClass)

    val today: LocalDate = LocalDate.now()
    val min: LocalDate = realClass?.quarters?.firstOrNull()?.startDate?.let { LocalDate.parse(it.take(10)) }
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
        realClass?.quarters?.map { Triple(it.number.toInt(), LocalDate.parse(it.startDate.take(10)), LocalDate.parse(it.endDate.take(10))) } ?: emptyList()

    // Bridge calls never run on the main thread (the TLS check on Android may use the network).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val weeks = HashMap<String, Deferred<Week>>()
    // What the screens read: snapshots that are replaced as a whole, so a screen never sees a half-filled map.
    @Volatile private var lessonsByDate: Map<String, Map<String, Lesson>> = emptyMap()
    @Volatile private var marks: Map<String, Mark> = emptyMap()
    @Volatile private var homework: Map<String, Homework> = emptyMap()
    /** The weeks exactly as the server sent them; the screens' maps are built from these (plus the developer mode's changes). */
    private val raw = HashMap<String, Week>()

    init {
        dev.rawLesson = { id -> raw.values.firstNotNullOfOrNull { w -> w.lessons.firstOrNull { it.id == id } } }
        dev.rawHomework = { id -> raw.values.firstNotNullOfOrNull { w -> w.homeworks.firstOrNull { it.id == id } } }
    }

    private val cache = WeekCache(context)
    private val appContext = context.applicationContext

    /** Set while some week shown comes from the phone's memory because Gosuslugi could not be reached: when that data was fetched. */
    var offlineSince by mutableStateOf<Long?>(null)
        private set
    private val staleKeys = HashSet<String>()

    private val prefs = context.getSharedPreferences("homework-done", Context.MODE_PRIVATE)
    private var done by mutableStateOf(prefs.all.keys.toSet())

    fun isDone(id: String) = id in done

    /** Forget every "done" tick (they live only on this phone). */
    fun clearDone() { done = emptySet(); prefs.edit().clear().apply() }

    fun setDone(id: String, value: Boolean) {
        done = if (value) done + id else done - id
        prefs.edit().apply { if (value) putBoolean(id, true) else remove(id) }.apply()
        WidgetUpdater.refreshAsync(appContext)
    }

    /** Loads the week the date belongs to (once; a failed load is forgotten so the next call retries). */
    suspend fun loadWeek(date: LocalDate) {
        val year = date.get(IsoFields.WEEK_BASED_YEAR)
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val key = "$year-$week"
        val request = lock.withLock {
            // The request belongs to this object, not to the caller: a screen that is left must not cancel a load others wait for.
            weeks.getOrPut(key) {
                scope.async {
                    try {
                        source.week(student.id, year.toUInt(), week.toUInt()).also {
                            cache.put(realStudent.id, it); merge(it); WidgetUpdater.refreshAsync(appContext)
                            val none = lock.withLock { staleKeys -= key; staleKeys.isEmpty() }
                            if (none) withContext(Dispatchers.Main) { offlineSince = null } // the connection is back
                        }
                    } catch (e: BridgeException.Network) {
                        // No connection (or the server is down): the last copy of the week is better than an error.
                        val (cached, at) = cache.get(realStudent.id, year.toUInt(), week.toUInt()) ?: throw e
                        lock.withLock { staleKeys += key }
                        withContext(Dispatchers.Main) { offlineSince = minOf(offlineSince ?: at, at) }
                        merge(cached)
                        cached
                    }
                }
            }
        }
        try {
            request.await()
            // A week that came from the cache is asked for again next time, so a returning connection is noticed.
            lock.withLock { if (key in staleKeys) weeks.remove(key) }
        } catch (e: BridgeException) {
            lock.withLock { weeks.remove(key) }
            throw failure(e)
        }
    }

    private suspend fun merge(w: Week) {
        lock.withLock { raw["${w.year}-${w.isoWeek}"] = w; rebuildLocked() }
        withContext(Dispatchers.Main) { revision++ }
    }

    /** Ask the server again for the weeks that were shown from the cache; when all of them arrive the "offline" mark goes away. */
    suspend fun refreshStale() {
        for (k in lock.withLock { staleKeys.toList() }) {
            val (y, w) = k.split("-").map { it.toInt() }
            val date = LocalDate.of(y, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, w.toLong())
            try { loadWeek(date) } catch (e: LoadFailure) { return } // still no connection: the banner stays
        }
    }

    /** Forget the cached weeks (logout). */
    fun clearCache() = cache.clear()

    /** Build the maps again, after the developer mode's changes have changed. */
    suspend fun rebuild() {
        lock.withLock { rebuildLocked() }
        withContext(Dispatchers.Main) { revision++ }
    }

    private fun rebuildLocked() {
        val overlay = dev.active
        val byDate = HashMap<String, MutableMap<String, Lesson>>()
        val m = HashMap<String, Mark>()
        val h = HashMap<String, Homework>()
        for (week in raw.values) {
            val w = overlay.applyWeek(week)
            w.lessons.forEach { byDate.getOrPut(it.start.take(10)) { HashMap() }[it.id] = it }
            w.marks.forEach { m[it.id] = it }
            w.homeworks.forEach { h[it.id] = it }
        }
        lessonsByDate = byDate; marks = m; homework = h
    }

    /** The server's profile values by the keys the developer mode uses. */
    fun realProfile(): Map<String, String> = mapOf(
        "firstName" to realStudent.firstName, "lastName" to realStudent.lastName, "middleName" to realStudent.middleName, "region" to realStudent.region,
        "schoolName" to (realClass?.schoolName ?: ""), "classNumber" to (realClass?.classNumber ?: ""), "classLetter" to (realClass?.classLetter ?: ""),
    )

    /** Subjects and teachers as the server names them, for the developer mode's renaming lists. */
    /** For the diagnostics: the weeks as the server sent them, and which of them came from the phone's memory. */
    suspend fun rawSnapshot(): List<Week> = lock.withLock { raw.values.toList() }
    suspend fun staleKeysSnapshot(): Set<String> = lock.withLock { staleKeys.toSet() }

    fun knownSubjects(): List<String> = raw.values.flatMap { w -> w.lessons.map { it.subjectName } + w.marks.map { it.subjectName } + w.homeworks.map { it.subjectName } }.filter { it.isNotBlank() }.distinct().sorted()
    fun knownTeachers(): List<uniffi.openschool_bridge.Teacher> = raw.values.flatMap { w -> w.lessons.map { it.teacher } }.filter { it.lastName.isNotBlank() }.distinctBy { teacherKey(it) }.sortedBy { it.lastName }

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
