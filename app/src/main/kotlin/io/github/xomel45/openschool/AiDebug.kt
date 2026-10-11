package io.github.xomel45.openschool

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.openschool_bridge.BridgeException
import uniffi.openschool_bridge.Week
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/*
 * "Diagnostics for an AI": a report about the app that is safe to paste to an AI (the same idea as `--ai-debug` on the desktop).
 * It is built by whitelist: facts only (versions, settings, counts, shapes of the loaded data, kinds of outcomes, timings), never
 * copies of data: no names, ids, subjects, teachers, texts, addresses, cookies, marks. Texts from outside go through [AiScrub].
 * It is offered in the developer mode (7 taps on the version), or in a debug build started with `--ez ai true`.
 */

/** Hides what a text may carry: quoted values, addresses, mail, long numbers, token-like words. */
object AiScrub {
    private val TOKEN_CHARS = Regex("^[A-Za-z0-9_+/=.-]+$")
    private val LONG_NUMBER = Regex("\\d{5,}")
    private val QUOTED = Regex("\"[^\"]*\"")

    fun scrub(text: String, max: Int = 200): String =
        text.replace(QUOTED, "\"…\"").split(' ').joinToString(" ") { word(it) }.take(max)

    internal fun word(w: String): String {
        val core = w.trim { !it.isLetterOrDigit() }
        return when {
            core.contains("://") -> "<url>"
            core.contains('@') && core.contains('.') -> "<email>"
            isToken(core) -> "<token>"
            else -> w.replace(LONG_NUMBER, "<n>")
        }
    }

    /** Long and mixed letters and digits (hex ids, uuids, cookies, base64), or very long without spaces. */
    private fun isToken(w: String): Boolean =
        w.length >= 20 && TOKEN_CHARS.matches(w) && ((w.any { it.isDigit() } && w.any { it in 'A'..'Z' || it in 'a'..'z' }) || w.length >= 32)

    /** The last pass over the finished report: every word goes through the rules (the report's own markup stays). */
    fun report(text: String): String = text.lines().joinToString("\n") { l -> l.split(' ').joinToString(" ") { word(it) } }
}

/** What happened to the requests (only the kind of outcome and the time), kept in memory while the diagnostics are on. */
object AiLog {
    /** The debug flag from `adb ... --ez ai true` (debug builds only). */
    @Volatile var flag = false
    /** Events are recorded only while this is on (the developer mode or the flag): otherwise nothing is kept. */
    @Volatile var enabled = false

    class Event(val atMs: Long, val what: String, val outcome: String, val ms: Long)

    private val events = ArrayDeque<Event>()
    private const val KEEP = 200

    fun note(what: String, outcome: String, ms: Long = 0) {
        if (!enabled) return
        synchronized(events) {
            if (events.size >= KEEP) events.removeFirst()
            events.addLast(Event(System.currentTimeMillis(), what, AiScrub.scrub(outcome, 160), ms))
        }
    }

    /** Run a request and remember how it ended and how long it took (nothing else about it). */
    suspend fun <T> timed(what: String, block: suspend () -> T): T {
        val started = System.currentTimeMillis()
        try {
            return block().also { note(what, "ok", System.currentTimeMillis() - started) }
        } catch (e: Throwable) {
            note(what, classify(e), System.currentTimeMillis() - started)
            throw e
        }
    }

    fun snapshot(): List<Event> = synchronized(events) { events.toList() }

    /** What kind of failure it was, without the details a message may carry. */
    fun classify(e: Throwable): String = when (e) {
        is BridgeException.Auth -> "auth (401/403)"
        is BridgeException.Network -> if (e.v1.startsWith("HTTP ")) "http_" + e.v1.drop(5).take(3).filter { it.isDigit() } else "network: unreachable"
        // a decoding message names the field that was wrong, which helps; its values do not
        is BridgeException.Parse -> "parse: " + AiScrub.scrub(e.v1, 160)
        else -> e.javaClass.simpleName + ": " + AiScrub.scrub(e.message ?: "", 120)
    }
}

/** The last crash of the app, kept for the report: the kind of error and where it happened (no data). Installed once at start. */
object CrashNote {
    fun install(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val frames = e.stackTrace.take(8).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                prefs.edit().putString("text", "${e.javaClass.simpleName}: ${AiScrub.scrub(e.message ?: "", 160)} at $frames").putLong("at", System.currentTimeMillis()).commit()
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun last(context: Context): String? {
        val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val text = prefs.getString("text", null) ?: return null
        return "${ago((System.currentTimeMillis() - prefs.getLong("at", 0)) / 1000)} ago: $text"
    }
}

fun ago(seconds: Long): String = when {
    seconds < 90 -> "$seconds s"
    seconds < 5400 -> "${Math.round(seconds / 60.0)} min"
    else -> "${Math.round(seconds / 3600.0)} h"
}

/** Everything the report says, already reduced to facts. */
class AiFacts(
    val app: List<String>,
    val network: List<String>,
    val login: List<String>,
    val settings: List<String>,
    val cacheMinutes: List<Long>,
    val data: List<String>,
    val events: List<AiLog.Event>,
    val crash: String?,
    val now: Long = System.currentTimeMillis(),
)

object AiReport {
    fun build(f: AiFacts): String {
        val l = ArrayList<String>()
        l += "# OpenSchool for Android: diagnostic report (anonymised)"
        l += ""
        l += "OpenSchool is an unofficial client (Rust library through UniFFI + Kotlin/Compose) for the Russian school diary «Моя школа» on Gosuslugi: schedule, homework, marks, background notifications. This report was made by the app itself in its diagnostics mode. It contains only facts about the app and the shape of the loaded data; names, ids, subjects, teachers, texts, addresses, cookies and mark values are NOT included. Dates are relative to today. Please use it to find the cause of the problem described below the report."
        fun section(title: String, lines: List<String>) { l += ""; l += "## $title"; l += lines }
        section("App and phone", f.app)
        section("Network", f.network)
        section("Login", f.login)
        section("Settings", f.settings)
        section("Offline copy", listOf("- ${f.cacheMinutes.size} weeks stored; age in minutes: ${f.cacheMinutes.take(12).joinToString(", ").ifEmpty { "—" }}${if (f.cacheMinutes.size > 12) ", …" else ""}"))
        section("Loaded data (shape only)", f.data)
        section("Requests (newest last; kind, outcome, milliseconds)",
            f.events.takeLast(60).map { "- ${ago((f.now - it.atMs) / 1000)} ago: ${it.what} → ${it.outcome}${if (it.ms > 0) " (${it.ms} ms)" else ""}" }.ifEmpty { listOf("- none yet") })
        section("Last crash", listOf(f.crash?.let { "- $it" } ?: "- none recorded"))
        section("Problem", listOf("(describe it here)"))
        return l.joinToString("\n")
    }

    private fun stats(nums: List<Long>): String =
        if (nums.isEmpty()) "—" else nums.sorted().let { "min ${it.first()}, median ${it[it.size / 2]}, max ${it.last()}" }

    private fun count(items: List<String>): String =
        items.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" }.ifEmpty { "—" }

    /** `9` for digits, `a` for runs of letters: the form of a value without the value. */
    private fun form(v: String): String = v.replace(Regex("[0-9]"), "9").replace(Regex("[\\p{L}]+"), "a").take(8)

    /** What the loaded weeks look like: counts, empty fields, odd links. Dates are days from today; subjects are not named. */
    fun shape(weeks: List<Week>, today: LocalDate, stale: Set<String> = emptySet()): List<String> {
        val out = ArrayList<String>()
        val sorted = weeks.sortedWith(compareBy({ it.year }, { it.isoWeek }))
        val lessonIds = sorted.flatMap { w -> w.lessons.map { it.id } }
        val labels = HashMap<String, String>() // S1, S2…: "the same subject" stays visible without its name
        fun label(id: String) = labels.getOrPut(id) { "S${labels.size + 1}" }
        fun monday(w: Week) = LocalDate.of(w.year.toInt(), 1, 4).with(DayOfWeek.MONDAY).plusWeeks(w.isoWeek.toLong() - 1)
        val curMonday = today.with(DayOfWeek.MONDAY)
        fun day(s: String): Long? = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(s.take(10))) }.getOrNull()
        for (w in sorted) {
            val key = "${w.year}-${w.isoWeek}"
            val off = ChronoUnit.WEEKS.between(curMonday, monday(w))
            val perDay = IntArray(7)
            for (x in w.lessons) runCatching { perDay[LocalDate.parse(x.start.take(10)).dayOfWeek.value - 1]++ }
            out += "- week ${if (off >= 0) "+" else ""}$off (${if (key in stale) "from the offline copy" else "fresh"}): lessons ${w.lessons.size} [Mon..Sun ${perDay.joinToString("/")}], homework ${w.homeworks.size}, marks ${w.marks.size}"
            val lengths = w.lessons.mapNotNull { x -> runCatching { ChronoUnit.MINUTES.between(LocalDateTime.parse(x.start.replace(' ', 'T')), LocalDateTime.parse(x.end.replace(' ', 'T'))) }.getOrNull() }
            out += "  - lessons: subjects ${w.lessons.map { it.subjectId }.toSet().size}, teachers ${w.lessons.map { "${it.teacher.lastName}|${it.teacher.firstName}|${it.teacher.patronymic}" }.toSet().size}, with absence ${w.lessons.count { it.absence != null }}, empty theme ${w.lessons.count { it.theme.isNullOrBlank() }}, empty room ${w.lessons.count { it.room.isBlank() }}, end<=start ${w.lessons.count { it.end <= it.start }}, lengths (min) ${stats(lengths)}"
            out += "  - homework: with materials ${w.homeworks.count { it.materials.isNotEmpty() }}, no due lesson id ${w.homeworks.count { it.dueLessonId == null }}, due lesson not in loaded weeks ${w.homeworks.count { it.dueLessonId != null && it.dueLessonId !in lessonIds }}, due before issue ${w.homeworks.count { it.dueDate < it.issueDate }}, text length ${stats(w.homeworks.map { it.description.length.toLong() })}, due in days ${stats(w.homeworks.mapNotNull { day(it.dueDate) })}"
            out += "  - marks: value shapes ${count(w.marks.map { form(it.value) })}, double ${w.marks.count { it.value2 != null }}, type codes ${count(w.marks.map { it.workTypeCode })}, no lesson id ${w.marks.count { it.lessonId == null }}, lesson not loaded ${w.marks.count { it.lessonId != null && it.lessonId !in lessonIds }}, with comment ${w.marks.count { !it.comment.isNullOrBlank() }}, subjects with marks ${w.marks.map { label(it.subjectId) }.toSet().sorted().joinToString(" ").ifEmpty { "—" }}"
        }
        out += "- all weeks: ${sorted.size} loaded, duplicate lesson ids ${lessonIds.size - lessonIds.toSet().size}"
        return out
    }
}

/** Gathers the facts from the phone and the app's state, builds the report and cleans it once more. */
object AiCollect {
    suspend fun report(context: Context, ui: UiPrefs, background: BackgroundPrefs, news: NewsStore, net: NetPolicy, data: SchoolData, students: Int): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        val dm = app.resources.displayMetrics
        val night = (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val webview = runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        val power = app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val work = runCatching {
            androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(NewsWorker.UNIQUE).get().joinToString(", ") { it.state.name }.ifEmpty { "not scheduled" }
        }.getOrDefault("unknown")
        val interval = news.settings
        val facts = AiFacts(
            app = listOf(
                "- OpenSchool $version (${if (debuggable) "debug" else "release"} build), package ${app.packageName}",
                "- Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), maker ${android.os.Build.MANUFACTURER}; language ${java.util.Locale.getDefault().language}; screen ${(dm.widthPixels / dm.density).toInt()}×${(dm.heightPixels / dm.density).toInt()} dp, density ${dm.density}; system dark mode ${night}",
                "- web view ${webview ?: "unknown"}",
                "- notifications allowed ${androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()}; battery optimisation ${if (power?.isIgnoringBatteryOptimizations(app.packageName) == true) "off for the app" else "on"}; background check: $work",
            ),
            network = listOf("- mode ${net.mode.name.lowercase()}, direct ${net.direct}, exit country ${net.country ?: "unknown"}, web view proxy override supported ${net.overrideSupported}"),
            login = listOf("- saved session ${if (SessionStore(app).load() != null) "yes" else "no"}; students on the account $students; background check paused (session expired) ${news.expired}"),
            settings = listOf(
                "- style ${ui.style.name.lowercase()}, scheme ${ui.scheme.name.lowercase()}, accent ${if (ui.accentHex != null) "custom colour" else ui.accent?.name?.lowercase() ?: "default"}, zoom ${ui.zoom}, icon ${ui.icon.id} (in use ${AppIcon.effective(ui.icon, data.dev.enabled).id})",
                "- background: mode ${background.bg.mode}${if (background.bg.mode == "media") ", picture ${if (background.image != null) "present" else "missing"}" else ""}, dim ${background.bg.dim}, blur ${background.bg.blur}",
                "- notifications: marks ${interval.marks}, homework ${interval.homework}, absences ${interval.absences}, every ${interval.intervalMin} min, detailed ${interval.detail}",
                "- developer mode ${if (data.dev.enabled) "on (edits: ${if (data.dev.overlay.isEmpty) "none" else "some"})" else "off"}",
                "- offline bar: ${if (data.offlineSince != null) "shown" else "hidden"}",
            ),
            cacheMinutes = WeekCache(app).agesMinutes(),
            data = AiReport.shape(data.rawSnapshot(), data.today, data.staleKeysSnapshot()),
            events = AiLog.snapshot(),
            crash = CrashNote.last(app),
        )
        AiScrub.report(AiReport.build(facts))
    }
}
