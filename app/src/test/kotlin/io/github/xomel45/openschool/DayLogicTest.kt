package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Mark
import uniffi.openschool_bridge.Teacher
import java.time.LocalDate

class DayLogicTest {
    private val day = LocalDate.of(2026, 10, 8)
    private fun lesson(id: String, subject: String, start: String, name: String = subject) =
        Lesson(id, subject, name, Teacher("", "", ""), "2026-10-08 $start", "2026-10-08 09:15", "", null, null)
    private fun hw(id: String, subject: String?, lessonId: String?, due: String = "2026-10-08") =
        Homework(id, subject, "Предмет", "текст", "2026-10-01", due, lessonId, emptyList())
    private fun mark(id: String, subject: String, value: String, lessonId: String? = null, date: String = "2026-10-08", name: String = "Предмет") =
        Mark(id, subject, name, value, null, date, "t", "Работа", null, null, lessonId)

    @Test fun homeworkGoesToItsLessonThenToTheSameSubjectThenToOrphans() {
        val lessons = listOf(lesson("l2", "phys", "09:00"), lesson("l1", "math", "08:00"))
        val d = buildDayRows(day, lessons, listOf(hw("a", "x", "l2"), hw("b", "math", null), hw("c", "art", null), hw("late", "math", "l1", due = "2026-10-09")), emptyList())
        assertEquals(listOf("l1", "l2"), d.rows.map { it.lesson.id }) // sorted by time, numbered from 1
        assertEquals(listOf(1, 2), d.rows.map { it.number })
        assertEquals(listOf("b"), d.rows[0].homework.map { it.id })
        assertEquals(listOf("a"), d.rows[1].homework.map { it.id })
        assertEquals(listOf("c"), d.orphanHomework.map { it.id }) // no lesson of that subject; another day's homework is not shown
    }

    @Test fun aMarkWithoutLessonFallsBackToSubjectAndDay() {
        val lessons = listOf(lesson("l1", "math", "08:00"))
        val d = buildDayRows(day, lessons, emptyList(), listOf(mark("m1", "math", "5", lessonId = "l1"), mark("m2", "math", "4"), mark("m3", "math", "3", date = "2026-10-07"), mark("m4", "art", "2")))
        assertEquals(listOf("m1", "m2"), d.rows[0].marks.map { it.id })
    }

    @Test fun gradesAverageOnlyNumbersAndKeepSubjectsWithoutMarks() {
        val lessons = listOf(lesson("l1", "math", "08:00", "Алгебра"), lesson("l2", "art", "09:00", "Рисование"))
        val rows = gradeRowsOf(day.minusDays(30), day, lessons, listOf(mark("1", "math", "5", name = "Алгебра"), mark("2", "math", "4", name = "Алгебра", date = "2026-10-05"), mark("3", "math", "н", name = "Алгебра"), mark("4", "math", "2", name = "Алгебра", date = "2026-01-01")))
        assertEquals(listOf("Алгебра", "Рисование"), rows.map { it.name })
        assertEquals(4.5, rows[0].average!!, 0.0001) // "н" is shown but not counted; the January mark is outside the period
        assertEquals(3, rows[0].marks.size)
        assertNull(rows[1].average)
    }

    @Test fun averagesAreFormattedAndColoured() {
        assertEquals("4,5", formatAverage(4.5)); assertEquals("4", formatAverage(4.0)); assertEquals("3,67", formatAverage(11.0 / 3))
        assertEquals(4.5, toNumber("4,5")!!, 0.0); assertNull(toNumber("н"))
    }
}
