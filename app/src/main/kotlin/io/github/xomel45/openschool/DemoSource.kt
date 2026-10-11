package io.github.xomel45.openschool

import uniffi.openschool_bridge.ClassInfo
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Absence
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Mark
import uniffi.openschool_bridge.Period
import uniffi.openschool_bridge.Student
import uniffi.openschool_bridge.Teacher
import uniffi.openschool_bridge.Material
import uniffi.openschool_bridge.Week
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields

/** Invented data for the debug demo mode: lessons Monday to Friday, some marks and homework, one absence. Nobody's real data. */
class DemoSource : WeekSource {
    companion object {
        val student = Student("demo", "Иван", "Петров", "", "")
        val classInfo = ClassInfo(
            "Демонстрационная школа", "8", "А", 2026u,
            listOf(
                Period(1u, "2026-09-01", "2026-10-27"), Period(2u, "2026-11-05", "2026-12-28"),
                Period(3u, "2027-01-11", "2027-03-22"), Period(4u, "2027-04-01", "2027-05-31"),
            ),
        )
        private val subjects = listOf("Алгебра", "Русский язык", "Физика", "История", "Литература", "Биология", "Английский язык", "Физкультура", "Информатика")
        private val teacher = Teacher("Ирина", "Соколова", "Петровна")
    }

    override suspend fun week(studentId: String, year: UInt, isoWeek: UInt): Week {
        val monday = LocalDate.of(year.toInt(), 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, isoWeek.toLong()).with(DayOfWeek.MONDAY)
        val today = LocalDate.now()
        val lessons = mutableListOf<Lesson>()
        val marks = mutableListOf<Mark>()
        val homeworks = mutableListOf<Homework>()
        // Weeks far ahead are "not published": nothing there.
        if (monday.isAfter(today.plusDays(14))) return Week(year, isoWeek, emptyList(), emptyList(), emptyList())
        for (d in 0..4) {
            val date = monday.plusDays(d.toLong())
            val perDay = 5 + (d % 2)
            for (i in 0 until perDay) {
                val seed = (date.toEpochDay() * 7 + i).toInt()
                val subject = subjects[Math.floorMod(seed, subjects.size)]
                val id = "l-$date-$i"
                val sh = 8 + i; val start = "$date %02d:%02d".format(sh, 30); val end = "$date %02d:%02d".format(sh + 1, 15)
                val absence = if (date == today.minusDays(3) && i == 0) Absence("Н", "По болезни") else null
                lessons += Lesson(id, "s-$subject", subject, teacher, start, end, "${100 + i * 3}", if (date <= today) "Тема урока: $subject" else null, absence)
                if (date <= today && Math.floorMod(seed, 5) < 2) {
                    val v = listOf("5", "4", "5", "3", "4", "2", "н")[Math.floorMod(seed / 3, 7)]
                    marks += Mark("m-$date-$i", "s-$subject", subject, v, null, date.toString(), "T", "Контрольная работа", null, null, id)
                }
                if (Math.floorMod(seed, 3) == 0) {
                    val due = date.plusDays(if (d == 4) 3 else 1)
                    homeworks += Homework("h-$date-$i", "s-$subject", subject, "Параграф ${Math.floorMod(seed, 30) + 1}, вопросы 1–5", date.toString(), due.toString(), null, if (i == 1) listOf(Material("Конспект.pdf", "")) else emptyList())
                }
            }
        }
        return Week(year, isoWeek, lessons, homeworks, marks)
    }
}
