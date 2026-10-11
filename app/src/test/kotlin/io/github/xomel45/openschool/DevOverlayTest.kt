package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.openschool_bridge.ClassInfo
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Mark
import uniffi.openschool_bridge.Student
import uniffi.openschool_bridge.Teacher
import uniffi.openschool_bridge.Week

class DevOverlayTest {
    private val teacher = Teacher("Анна", "Белова", "Ивановна")
    private fun lesson(id: String, subject: String, date: String = "2026-10-06") =
        Lesson(id, "s-$subject", subject, teacher, "$date 08:30", "$date 09:15", "12", "Тема", null)
    private val mark = Mark("m1", "s-Физика", "Физика", "5", null, "2026-10-06", "T", "Контрольная", null, null, "l1")
    private val hw = Homework("h1", "s-Физика", "Физика", "§5", "2026-10-01", "2026-10-06", "l1", emptyList())
    // 2026-10-06 is in ISO week 41 of 2026
    private val week = Week(2026u, 41u, listOf(lesson("l1", "Физика"), lesson("l2", "Химия")), listOf(hw), listOf(mark))

    @Test fun anEmptyOverlayChangesNothing() {
        assertEquals(week, Overlay().applyWeek(week))
    }

    @Test fun subjectsAndTeachersAreRenamedEverywhere() {
        val o = Overlay(subjects = mapOf("Физика" to "Магия"), teachers = mapOf(teacherKey(teacher) to "Крылов Пётр Сергеевич"))
        val w = o.applyWeek(week)
        assertEquals(listOf("Магия", "Химия"), w.lessons.map { it.subjectName })
        assertEquals("Магия", w.marks[0].subjectName); assertEquals("Магия", w.homeworks[0].subjectName)
        assertEquals(Teacher("Пётр", "Крылов", "Сергеевич"), w.lessons[0].teacher)
        assertEquals("marks are never changed", "5", w.marks[0].value)
    }

    @Test fun oneLessonCanBeEditedOrRemoved() {
        val o = Overlay(lessons = mapOf("l1" to LessonEdit(subject = "Особый", start = "10:00", room = "99", theme = "Новая тема"), "l2" to LessonEdit(deleted = true)))
        val w = o.applyWeek(week)
        assertEquals(1, w.lessons.size)
        val l = w.lessons[0]
        assertEquals("Особый", l.subjectName); assertEquals("2026-10-06 10:00", l.start); assertEquals("2026-10-06 09:15", l.end)
        assertEquals("99", l.room); assertEquals("Новая тема", l.theme)
    }

    @Test fun homeworkIsEditedRemovedAndAddedIntoTheRightWeek() {
        val added = AddedHomework("dev-h1", "2026-10-07", "Физика", "Новое", null)
        val elsewhere = AddedHomework("dev-h2", "2026-11-20", "Физика", "Далеко", null)
        val w = Overlay(homework = mapOf("h1" to HwEdit(text = "§6")), addedHomework = listOf(added, elsewhere)).applyWeek(week)
        assertEquals(listOf("§6", "Новое"), w.homeworks.map { it.description }) // the far one belongs to another week
        assertTrue(Overlay(homework = mapOf("h1" to HwEdit(deleted = true))).applyWeek(week).homeworks.isEmpty())
    }

    @Test fun addedLessonsLandInTheirWeekAndSurviveTheJson() {
        val o = Overlay(
            profile = mapOf("firstName" to "Иван"), subjects = mapOf("A" to "B"),
            addedLessons = listOf(AddedLesson("dev-l1", "2026-10-07", "13:00", "13:45", "Музыка", "5", "Белова Анна", "")),
            lessons = mapOf("l1" to LessonEdit(room = "7")), homework = mapOf("h1" to HwEdit(deleted = true)),
        )
        assertEquals(o, Overlay.fromJson(o.toJson().toString()))
        val w = o.applyWeek(week)
        assertEquals("Музыка", w.lessons.last().subjectName); assertEquals("2026-10-07 13:00", w.lessons.last().start)
        assertEquals(Teacher("Анна", "Белова", ""), w.lessons.last().teacher)
        assertEquals(Overlay(), Overlay.fromJson("{ junk")); assertEquals(Overlay(), Overlay.fromJson(null))
    }

    @Test fun theProfileIsReplacedPieceByPiece() {
        val o = Overlay(profile = mapOf("firstName" to "Иван", "schoolName" to "Школа №0", "classLetter" to "Я"))
        val s = o.applyStudent(Student("1", "Пётр", "Сидоров", "Олегович", "41"))
        assertEquals(listOf("Иван", "Сидоров", "Олегович"), listOf(s.firstName, s.lastName, s.middleName))
        val c = o.applyClass(ClassInfo("Школа №1", "8", "Б", 2026u, emptyList()))!!
        assertEquals(Triple("Школа №0", "8", "Я"), Triple(c.schoolName, c.classNumber, c.classLetter))
    }

    @Test fun anEditIsKeptWhenTheSameValuesAreSavedAgain() {
        // what the dialog does: compare with the server's lesson (plus the renaming rules), not with what is shown
        val o = Overlay(subjects = mapOf("Физика" to "Магия"), lessons = mapOf("l1" to LessonEdit(room = "99")))
        val shown = o.applyWeek(week).lessons.first { it.id == "l1" }
        val orig = week.lessons.first { it.id == "l1" }
        fun diff(new: String, base: String) = new.takeIf { it != base }
        val again = LessonEdit(
            subject = diff(shown.subjectName, o.subjects[orig.subjectName] ?: orig.subjectName), room = diff(shown.room, orig.room),
            teacher = diff(teacherText(shown.teacher), teacherText(orig.teacher)), theme = diff(shown.theme.orEmpty(), orig.theme.orEmpty()),
        )
        assertEquals(LessonEdit(room = "99"), again)
    }
}
