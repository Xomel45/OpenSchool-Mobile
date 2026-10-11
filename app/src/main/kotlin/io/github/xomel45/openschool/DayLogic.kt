package io.github.xomel45.openschool

import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Mark
import java.time.LocalDate

/** Lessons of one day with their homework (by the lesson it is due on) and marks. Same rules as the desktop app's `buildDay`. */
fun buildDayRows(date: LocalDate, lessonsOfDay: Collection<Lesson>, homeworks: Collection<Homework>, marks: Collection<Mark>): DayData {
    val key = date.toString()
    val lessons = lessonsOfDay.sortedBy { it.start }
    val homeworkOf = HashMap<String, MutableList<Homework>>()
    val orphans = mutableListOf<Homework>()
    for (h in homeworks.filter { it.dueDate == key }) {
        val target = lessons.firstOrNull { it.id == h.dueLessonId } ?: lessons.firstOrNull { h.subjectId != null && it.subjectId == h.subjectId }
        if (target != null) homeworkOf.getOrPut(target.id) { mutableListOf() } += h else orphans += h
    }
    val rows = lessons.mapIndexed { i, l ->
        // A mark belongs to its lesson; one without a lesson goes to the lesson of the same subject on its day.
        val own = marks.filter { m -> m.lessonId == l.id || (m.lessonId == null && m.date == key && m.subjectId == l.subjectId) }
        LessonRow(i + 1, l, homeworkOf[l.id] ?: emptyList(), own)
    }
    return DayData(rows, orphans)
}

/** One entry per subject seen in the period (lessons or marks): its marks by date and the average of the numeric ones. */
fun gradeRowsOf(start: LocalDate, end: LocalDate, lessons: Collection<Lesson>, marks: Collection<Mark>): List<GradeRow> {
    val from = start.toString()
    val to = end.toString()
    val subjects = LinkedHashMap<String, Pair<String, MutableList<Mark>>>()
    fun entry(id: String?, name: String) = subjects.getOrPut(id?.takeIf { it.isNotEmpty() } ?: "n:$name") { name to mutableListOf() }
    lessons.forEach { l -> if (l.subjectName.isNotBlank() && l.start.take(10) in from..to) entry(l.subjectId, l.subjectName) }
    marks.forEach { m -> if (m.subjectName.isNotBlank() && m.date in from..to) entry(m.subjectId, m.subjectName).second += m }
    return subjects.values
        .map { (name, list) ->
            val sorted = list.sortedWith(compareBy<Mark> { it.date }.thenBy { it.id })
            GradeRow(name, sorted, mean(sorted.mapNotNull { toNumber(it.value) }))
        }
        .sortedBy { it.name.lowercase() }
}
