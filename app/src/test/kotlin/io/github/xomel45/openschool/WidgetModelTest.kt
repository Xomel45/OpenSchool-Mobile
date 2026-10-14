package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.openschool_bridge.Absence
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Material
import uniffi.openschool_bridge.Teacher
import uniffi.openschool_bridge.Week
import java.time.LocalDate
import java.time.LocalDateTime

class WidgetModelTest {
    private val t = Teacher("", "", "")
    private fun lesson(id: String, day: String, from: String, to: String, name: String, room: String = "12", absence: Absence? = null) =
        Lesson(id, "s-$name", name, t, "$day $from:00", "$day $to:00", room, null, absence)
    private fun hw(id: String, subject: String, due: String, text: String, files: Boolean = false) =
        Homework(id, "s-$subject", subject, text, "2026-10-01", due, null, if (files) listOf(Material("a.pdf", "x")) else emptyList())

    // Mon 12 Oct 2026
    private val week = Week(
        2026u, 42u,
        listOf(
            lesson("a", "2026-10-12", "08:30", "09:15", "Алгебра"), lesson("b", "2026-10-12", "09:25", "10:05", "Физика", absence = Absence("Н", "")),
            lesson("c", "2026-10-13", "08:30", "09:15", "Химия"), lesson("d", "2026-10-15", "08:30", "09:15", "Труд"),
        ),
        listOf(hw("h1", "Физика", "2026-10-12", "§5   ответить\nна вопросы", files = true), hw("h2", "Алгебра", "2026-10-13", "№40"), hw("h3", "Химия", "2026-10-09", "старое")),
        emptyList(),
    )
    private fun at(h: Int, m: Int, day: Int = 12) = LocalDateTime.of(2026, 10, day, h, m)

    @Test fun theDayShowsEachLessonAsPastNowOrComing() {
        val m = WidgetModels.schedule(listOf(week), at(9, 30))
        assertEquals("Сегодня, 12 окт", m.title)
        assertEquals(listOf(LessonPhase.Past, LessonPhase.Now), m.lessons.map { it.phase })
        assertEquals(listOf("8:30–9:15", "9:25–10:05"), m.lessons.map { it.time })
        assertEquals(listOf(false, true), m.lessons.map { it.homework }) // the physics homework is due today
        assertEquals(listOf(false, true), m.lessons.map { it.absent })
        assertEquals(listOf(LessonPhase.Upcoming, LessonPhase.Upcoming), WidgetModels.schedule(listOf(week), at(7, 0)).lessons.map { it.phase })
    }

    @Test fun afterTheLastLessonTheNextSchoolDayIsShown() {
        val m = WidgetModels.schedule(listOf(week), at(10, 5))
        assertEquals("Завтра, 13 окт", m.title)
        assertEquals(listOf("Химия"), m.lessons.map { it.subject })
        assertTrue(m.lessons.all { it.phase == LessonPhase.Upcoming })
        // two days later (a gap in the week): the weekday is named
        assertEquals("Чт, 15 окт", WidgetModels.schedule(listOf(week), at(12, 0, day = 13)).title)
    }

    @Test fun nothingToShowGivesAHintNotAnEmptyBox() {
        assertTrue(WidgetModels.schedule(emptyList(), at(9, 0)).note!!.contains("Откройте приложение"))
        val none = WidgetModels.schedule(listOf(week), at(12, 0, day = 16))
        assertTrue(none.lessons.isEmpty() && none.note == "Ближайшие дни без уроков")
    }

    @Test fun homeworkIsTheOpenOnesFromTodayOnNearestFirst() {
        val m = WidgetModels.homework(listOf(week), LocalDate.of(2026, 10, 12), emptySet())
        assertEquals(listOf("Физика", "Алгебра"), m.lines.map { it.subject })
        assertEquals(listOf("Сегодня", "Завтра"), m.lines.map { it.due })
        assertEquals("§5 ответить на вопросы", m.lines[0].text)
        assertEquals(listOf(true, false), m.lines.map { it.files })
        assertEquals(listOf("Алгебра"), WidgetModels.homework(listOf(week), LocalDate.of(2026, 10, 12), setOf("h1")).lines.map { it.subject })
        assertEquals("Всё сделано", WidgetModels.homework(listOf(week), LocalDate.of(2026, 10, 12), setOf("h1", "h2")).note)
        assertEquals("Ничего не задано", WidgetModels.homework(listOf(week), LocalDate.of(2026, 10, 20), emptySet()).note)
    }

    @Test fun oldDataIsSaidAndFreshDataIsNot() {
        val now = at(15, 0)
        fun ms(h: Int, m: Int, day: Int = 12) = at(h, m, day).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertNull(WidgetModels.staleNote(ms(14, 0), now)); assertNull(WidgetModels.staleNote(null, now))
        assertEquals("данные от 9:05", WidgetModels.staleNote(ms(9, 5), now))
        assertEquals("данные от вчера 19:40", WidgetModels.staleNote(ms(19, 40, 11), now))
        assertEquals("данные от 9 окт", WidgetModels.staleNote(ms(10, 0, 9), now))
    }

    @Test fun noMarkIsEverPartOfAWidgetModel() {
        // the models have no field for a mark: the widget cannot leak one
        val fields = (ScheduleModel::class.java.declaredFields + WidgetLesson::class.java.declaredFields + HomeworkModel::class.java.declaredFields + HomeworkLine::class.java.declaredFields).map { it.name.lowercase() }
        assertTrue(fields.toString(), fields.none { "mark" in it || "grade" in it })
    }
}
