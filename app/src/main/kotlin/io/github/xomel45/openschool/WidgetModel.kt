package io.github.xomel45.openschool

import uniffi.openschool_bridge.Week
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * What the home-screen widgets show, worked out from the weeks kept on the phone (the offline copy); no network is used when a
 * widget is drawn. Marks are deliberately not shown: a widget sits on a screen anyone can see. The drawing is in Widgets.kt.
 */

enum class LessonPhase { Past, Now, Upcoming }

data class WidgetLesson(val number: Int, val subject: String, val time: String, val room: String, val phase: LessonPhase, val homework: Boolean, val absent: Boolean)

/** [note] replaces the list when there is nothing to list (or is added under it, as a hint about old data). */
data class ScheduleModel(val title: String, val lessons: List<WidgetLesson>, val note: String? = null)

data class HomeworkLine(val subject: String, val text: String, val due: String, val files: Boolean)

data class HomeworkModel(val lines: List<HomeworkLine>, val note: String? = null)

private val DOW = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private val MONTHS_SHORT = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/** "Сегодня", "Завтра" or "Пн, 13 окт" (a day that is not close gets the weekday). */
fun dayLabel(date: LocalDate, today: LocalDate): String {
    val short = "${date.dayOfMonth} ${MONTHS_SHORT[date.monthValue - 1]}"
    return when (java.time.temporal.ChronoUnit.DAYS.between(today, date)) {
        0L -> "Сегодня, $short"
        1L -> "Завтра, $short"
        else -> "${DOW[date.dayOfWeek.value - 1]}, $short"
    }
}

object WidgetModels {
    private const val NO_DATA = "Откройте приложение, чтобы загрузить данные"

    private fun time(s: String): LocalDateTime = LocalDateTime.parse(s.replace(' ', 'T'))

    /**
     * Today's lessons; once the last one is over (or on a day off) the next day that has lessons within a week. [staleNote] is
     * shown under the list when the data on the phone is old.
     */
    fun schedule(weeks: List<Week>, now: LocalDateTime, staleNote: String? = null): ScheduleModel {
        if (weeks.isEmpty()) return ScheduleModel("Расписание", emptyList(), NO_DATA)
        val today = now.toLocalDate()
        val byDay = weeks.flatMap { it.lessons }.groupBy { it.start.take(10) }
        val homeworks = weeks.flatMap { it.homeworks }
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            val lessons = byDay[date.toString()] ?: continue
            if (offset == 0L && lessons.all { runCatching { time(it.end) <= now }.getOrDefault(false) }) continue // the day is over
            val rows = buildDayRows(date, lessons, homeworks, emptyList()).rows
            val lines = rows.map { r ->
                val l = r.lesson
                val phase = if (offset > 0) LessonPhase.Upcoming else runCatching {
                    when { time(l.end) <= now -> LessonPhase.Past; time(l.start) <= now -> LessonPhase.Now; else -> LessonPhase.Upcoming }
                }.getOrDefault(LessonPhase.Upcoming)
                WidgetLesson(r.number, l.subjectName.ifBlank { "Урок" }, "${hm(l.start)}–${hm(l.end)}", l.room, phase, r.homework.isNotEmpty(), l.absence != null)
            }
            return ScheduleModel(dayLabel(date, today), lines, staleNote)
        }
        return ScheduleModel("Расписание", emptyList(), "Ближайшие дни без уроков")
    }

    /** What is still to do: homework due today or later that is not ticked off, nearest first. */
    fun homework(weeks: List<Week>, today: LocalDate, done: Set<String>, staleNote: String? = null): HomeworkModel {
        if (weeks.isEmpty()) return HomeworkModel(emptyList(), NO_DATA)
        val all = weeks.flatMap { it.homeworks }.filter { it.dueDate >= today.toString() }
        val open = all.filter { it.id !in done }.sortedWith(compareBy({ it.dueDate }, { it.subjectName }, { it.id }))
        if (open.isEmpty()) return HomeworkModel(emptyList(), if (all.isEmpty()) "Ничего не задано" else "Всё сделано")
        val lines = open.map { h ->
            HomeworkLine(
                subject = h.subjectName.ifBlank { "Без предмета" },
                text = h.description.replace(Regex("\\s+"), " ").trim().ifBlank { "Без текста" },
                due = runCatching { dayLabel(LocalDate.parse(h.dueDate), today).substringBefore(',') }.getOrDefault(h.dueDate),
                files = h.materials.isNotEmpty(),
            )
        }
        return HomeworkModel(lines, staleNote)
    }

    /** "данные от 14:20" (today), "данные от вчера 19:40" or "данные от 9 окт", only when the data is older than [afterMinutes]. */
    fun staleNote(savedAtMs: Long?, now: LocalDateTime, afterMinutes: Long = 180): String? {
        if (savedAtMs == null) return null
        val saved = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(savedAtMs), java.time.ZoneId.systemDefault())
        if (java.time.Duration.between(saved, now).toMinutes() < afterMinutes) return null
        val clock = "%d:%02d".format(saved.hour, saved.minute)
        val days = java.time.temporal.ChronoUnit.DAYS.between(saved.toLocalDate(), now.toLocalDate())
        return when {
            days <= 0 -> "данные от $clock"
            days == 1L -> "данные от вчера $clock"
            else -> "данные от ${saved.dayOfMonth} ${MONTHS_SHORT[saved.monthValue - 1]}"
        }
    }
}
