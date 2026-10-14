package io.github.xomel45.openschool

import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Week
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

// Pure logic shared by the screens and the background work (JVM-tested): tests in lessons, the evening summary, «сейчас идёт»
// and the .ics export. The same rules and word list as the desktop app (src-tauri/src/planner.rs, ui/js/planner.js).

/** Word stem, the label shown, whole word only (`впр` is in «вправо»). */
val TEST_WORDS: List<Triple<String, String, Boolean>> = listOf(
    Triple("контрольн", "Контрольная", false),
    Triple("к/р", "Контрольная", false),
    Triple("самостоятельн", "Самостоятельная", false),
    Triple("с/р", "Самостоятельная", false),
    Triple("проверочн", "Проверочная", false),
    Triple("диктант", "Диктант", false),
    Triple("зачёт", "Зачёт", false),
    Triple("зачет", "Зачёт", false),
    Triple("впр", "ВПР", true),
    Triple("тест", "Тест", false),
    Triple("срез", "Срез", true),
    Triple("диагностическ", "Диагностическая", false),
    Triple("экзамен", "Экзамен", false),
)

fun testLabel(text: String?): String? {
    val t = (text ?: return null).lowercase()
    for ((stem, label, whole) in TEST_WORDS) {
        var i = t.indexOf(stem)
        while (i >= 0) {
            if (!whole) return label
            val before = t.getOrNull(i - 1)
            val after = t.getOrNull(i + stem.length)
            if (before?.isLetterOrDigit() != true && after?.isLetterOrDigit() != true) return label
            i = t.indexOf(stem, i + 1)
        }
    }
    return null
}

/** Is this lesson a test? From its topic first, then from a homework due on it. */
fun lessonTest(lesson: Lesson, homework: List<Homework>): String? =
    testLabel(lesson.theme) ?: homework.firstNotNullOfOrNull { testLabel(it.description) }

fun lessonTest(row: LessonRow): String? = lessonTest(row.lesson, row.homework)

// ---------- the evening summary ----------

data class Note(val id: String, val lesson: String, val text: String, val done: Boolean)

data class DayPlan(val date: LocalDate, val lessons: Int, val first: String, val open: List<String>, val tests: List<Pair<String, String>>, val notes: List<String>)

private fun dayOf(dt: String): LocalDate? = runCatching { LocalDate.parse(dt.take(10)) }.getOrNull()
private fun hmOf(dt: String): String = dt.drop(11).take(5).removePrefix("0")

fun plan(weeks: List<Week>, date: LocalDate, done: Set<String>, notes: List<Note>): DayPlan {
    val lessons = weeks.flatMap { it.lessons }.filter { dayOf(it.start) == date }.distinctBy { it.id }.sortedBy { it.start }
    val hws = weeks.flatMap { it.homeworks }.filter { dayOf(it.dueDate) == date }.distinctBy { it.id }
    val tests = mutableListOf<Pair<String, String>>()
    for (l in lessons) {
        val label = lessonTest(l, hws.filter { it.dueLessonId == l.id }) ?: continue
        if (tests.none { it.first == l.subjectName }) tests += l.subjectName to label
    }
    val ids = lessons.map { it.id }.toSet()
    return DayPlan(
        date, lessons.size, lessons.firstOrNull()?.let { hmOf(it.start) } ?: "",
        hws.filter { it.id !in done }.map { it.subjectName }, tests,
        notes.filter { !it.done && it.lesson in ids }.map { it.text.take(80) },
    )
}

fun nextSchoolDay(weeks: List<Week>, today: LocalDate, within: Int = 3): LocalDate? =
    (1..within).map { today.plusDays(it.toLong()) }.firstOrNull { d -> weeks.any { w -> w.lessons.any { dayOf(it.start) == d } } }

private val WEEKDAYS_IN = listOf("В понедельник", "Во вторник", "В среду", "В четверг", "В пятницу", "В субботу", "В воскресенье")

fun lessonsWord(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "урок"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "урока"
    else -> "уроков"
}

fun eveningText(p: DayPlan, today: LocalDate, summary: Boolean, tests: Boolean): Pair<String, String>? {
    val whenText = if (p.date == today.plusDays(1)) "Завтра" else WEEKDAYS_IN[p.date.dayOfWeek.value - 1]
    val testLine = p.tests.joinToString(", ") { (s, l) -> "$s (${l.lowercase()})" }
    if (summary) {
        val body = mutableListOf<String>()
        if (tests && p.tests.isNotEmpty()) body += "Контрольные: $testLine."
        body += if (p.open.isEmpty()) "Все задания отмечены сделанными." else "Не сделано заданий: ${p.open.size} (${p.open.distinct().joinToString(", ")})."
        if (p.notes.isNotEmpty()) body += "Не забыть: ${p.notes.joinToString("; ")}."
        return "$whenText: ${p.lessons} ${lessonsWord(p.lessons)} с ${p.first}" to body.joinToString(" ")
    }
    if (tests && p.tests.isNotEmpty()) {
        val title = if (p.tests.size == 1) "$whenText ${p.tests[0].second.lowercase()}" else "$whenText контрольные"
        return title to testLine
    }
    return null
}

// ---------- «сейчас идёт урок» ----------

private val DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private fun dt(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s.take(19), DT) }.getOrNull()

fun minutesWord(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "минута"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "минуты"
    else -> "минут"
}

/** The state of the day at [now]: the text, and when it changes next (null after the last lesson). */
data class NowState(val text: String, val title: String, val until: LocalDateTime?)

fun nowState(lessons: List<Lesson>, now: LocalDateTime): NowState? {
    val sorted = lessons.sortedBy { it.start }
    val room = { l: Lesson -> if (l.room.isNotBlank()) ", каб. ${l.room}" else "" }
    sorted.forEachIndexed { i, l ->
        val a = dt(l.start) ?: return@forEachIndexed
        val b = dt(l.end) ?: return@forEachIndexed
        if (now < a) {
            val left = ceil(java.time.Duration.between(now, a).seconds / 60.0).toInt()
            return if (i == 0) NowState("Первый урок: ${l.subjectName} в ${hmOf(l.start)}${room(l)}", "Скоро первый урок", a)
            else NowState("Перемена · дальше ${l.subjectName}${room(l)} через $left ${if (minutesWord(left) == "минута") "минуту" else minutesWord(left)}", "Перемена", a)
        }
        if (now < b) {
            val left = ceil(java.time.Duration.between(now, b).seconds / 60.0).toInt()
            return NowState("${l.subjectName}${room(l)} · до конца $left ${minutesWord(left)}", "Сейчас: ${l.subjectName}", b)
        }
    }
    return null
}

// ---------- .ics ----------

data class IcsEvent(val uid: String, val start: String, val end: String, val summary: String, val location: String, val description: String)

private fun icsText(s: String) = buildString {
    for (c in s) when (c) {
        '\\' -> append("\\\\"); ';' -> append("\\;"); ',' -> append("\\,"); '\n' -> append("\\n")
        else -> if (!c.isISOControl()) append(c)
    }
}

private fun icsStamp(s: String): String? {
    if (s.length < 16 || s[4] != '-' || s[7] != '-' || s[13] != ':') return null
    val d = s.filter { it.isDigit() }
    return if (d.length >= 12) "${d.take(8)}T${d.substring(8, 12)}${d.drop(12).take(2).padEnd(2, '0')}" else null
}

/** Lines folded at 75 octets, never inside a character (RFC 5545). */
private fun StringBuilder.fold(line: String) {
    var len = 0
    for (ch in line) {
        val w = ch.toString().toByteArray(Charsets.UTF_8).size
        if (len + w > 75) { append("\r\n "); len = 1 }
        append(ch); len += w
    }
    append("\r\n")
}

fun buildIcs(events: List<IcsEvent>, nowUtc: String): String = buildString {
    listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//OpenSchool//Schedule//RU", "CALSCALE:GREGORIAN", "X-WR-CALNAME:Уроки (OpenSchool)").forEach { fold(it) }
    for (e in events) {
        val a = icsStamp(e.start) ?: continue
        val b = icsStamp(e.end) ?: continue
        fold("BEGIN:VEVENT")
        fold("UID:${e.uid.filter { it.isLetterOrDigit() && it.code < 128 || it in "-_." }}@openschool")
        fold("DTSTAMP:$nowUtc"); fold("DTSTART:$a"); fold("DTEND:$b")
        fold("SUMMARY:${icsText(e.summary)}")
        if (e.location.isNotEmpty()) fold("LOCATION:${icsText(e.location)}")
        if (e.description.isNotEmpty()) fold("DESCRIPTION:${icsText(e.description)}")
        fold("END:VEVENT")
    }
    fold("END:VCALENDAR")
}
