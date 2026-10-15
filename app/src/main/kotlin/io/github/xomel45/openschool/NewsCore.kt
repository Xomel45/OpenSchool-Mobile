package io.github.xomel45.openschool

import uniffi.openschool_bridge.Week

enum class Kind { Mark, Homework, Absence, Change }

/** Something new found in a week. */
data class NewsEvent(
    /** Stable across weeks and runs: `m:<mark id>`, `h:<homework id>`, `a:<lesson id>`. */
    val key: String,
    val kind: Kind,
    val subject: String,
    val value: String?,
    /** One line of context: the kind of work and the date, the assignment and its due date, the lesson time. */
    val detail: String,
    val date: String,
)

/** `2026-10-08` -> `8 октября`; anything else is returned unchanged. */
fun formatDate(iso: String): String {
    val y = iso.take(10).split('-')
    val m = y.getOrNull(1)?.toIntOrNull()
    val d = y.getOrNull(2)?.toIntOrNull()
    return if (m != null && d != null && m in 1..12) "$d ${MONTHS[m - 1]}" else iso
}

fun truncateText(s: String, max: Int): String {
    val one = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    return if (one.length <= max) one else one.take(max).trimEnd() + "…"
}

fun eventsOf(week: Week): List<NewsEvent> {
    val out = mutableListOf<NewsEvent>()
    for (m in week.marks) {
        val work = m.workName?.takeIf { it.isNotBlank() } ?: m.workType
        val detail = if (work.isBlank()) formatDate(m.date) else "$work · ${formatDate(m.date)}"
        out += NewsEvent("m:${m.id}", Kind.Mark, m.subjectName, m.value, detail, m.date)
    }
    for (h in week.homeworks) {
        val text = if (h.description.isBlank()) "без текста" else truncateText(h.description, 90)
        out += NewsEvent("h:${h.id}", Kind.Homework, h.subjectName, null, "$text · на ${formatDate(h.dueDate)}", h.dueDate)
    }
    for (l in week.lessons) {
        if (l.absence == null) continue
        val date = l.start.take(10)
        val time = l.start.drop(11).take(5)
        out += NewsEvent("a:${l.id}", Kind.Absence, l.subjectName, null, if (time.isNotEmpty()) "${formatDate(date)}, $time" else formatDate(date), date)
    }
    return out
}

/** Russian plural: 1 / 2-4 / 5+ (and 11-14). */
fun plural(n: Int, one: String, few: String, many: String): String = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

/** Title and body of the system notification for one or several new events. */
fun notificationText(items: List<NewsEvent>, detail: Boolean): Pair<String, String> {
    val hidden = "Откройте OpenSchool"
    if (items.size == 1) {
        val e = items[0]
        val (title, name) = when (e.kind) {
            Kind.Mark -> "Новая оценка" to e.value?.let { "${e.subject} — $it" }
            Kind.Homework -> "Новое задание" to e.subject
            Kind.Absence -> "Отметка о пропуске" to e.subject
            Kind.Change -> "Изменение в расписании" to e.subject
        }
        return if (detail && name != null) "$title: $name" to e.detail else title to hidden
    }
    val n = items.size
    val same = items.all { it.kind == items[0].kind }
    val title = "$n " + if (!same) plural(n, "новое событие", "новых события", "новых событий") else when (items[0].kind) {
        Kind.Mark -> plural(n, "новая оценка", "новые оценки", "новых оценок")
        Kind.Homework -> plural(n, "новое задание", "новых задания", "новых заданий")
        Kind.Absence -> plural(n, "отметка о пропуске", "отметки о пропусках", "отметок о пропусках")
        Kind.Change -> plural(n, "изменение в расписании", "изменения в расписании", "изменений в расписании")
    }
    if (!detail) return title to hidden
    val short = items.take(3).map { e -> e.value?.let { "${e.subject} $it" } ?: e.subject }
    val more = if (n > 3) " · и ещё ${n - 3}" else ""
    return title to short.joinToString(" · ") + more
}
