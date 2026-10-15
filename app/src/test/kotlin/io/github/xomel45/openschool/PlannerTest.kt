package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Teacher
import uniffi.openschool_bridge.Week
import java.time.LocalDate
import java.time.LocalDateTime

class PlannerTest {
    private fun lesson(id: String, subject: String, start: String, end: String = start, theme: String? = null, room: String = "") =
        Lesson(id, subject, subject, Teacher("", "", ""), start, end, room, theme, null)
    private fun hw(id: String, subject: String, due: String, lesson: String, text: String) =
        Homework(id, subject, subject, text, due, due, lesson, emptyList())
    private fun week(lessons: List<Lesson>, hws: List<Homework> = emptyList()) = Week(2026u, 42u, lessons, hws, emptyList())
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun testWordsAreFoundAndShortOnesOnlyAsWords() {
        mapOf("Контрольная работа №2" to "Контрольная", "к/р по теме" to "Контрольная", "ВПР по математике" to "ВПР", "итоговый тест" to "Тест", "Словарный диктант" to "Диктант", "зачет" to "Зачёт")
            .forEach { (t, l) -> assertEquals(t, l, testLabel(t)) }
        listOf("Повернуть вправо", "§5, ответить на вопросы", "прочитать текст", "срезать углы", "").forEach { assertNull(it, testLabel(it)) }
    }

    @Test fun theWordListIsTheDesktopOne() {
        // the same 13 entries in the same order as src-tauri/src/planner.rs of the desktop app
        assertEquals(listOf("контрольн", "к/р", "самостоятельн", "с/р", "проверочн", "диктант", "зачёт", "зачет", "впр", "тест", "срез", "диагностическ", "экзамен"), TEST_WORDS.map { it.first })
        assertEquals(listOf("впр", "срез"), TEST_WORDS.filter { it.third }.map { it.first })
    }

    @Test fun thePlanCountsLessonsOpenHomeworkTestsAndNotesOnce() {
        val a = week(
            listOf(lesson("L2", "Физика", "2026-10-15 09:25:00"), lesson("L1", "Алгебра", "2026-10-15 08:30:00", theme = "Контрольная работа"), lesson("L3", "Химия", "2026-10-16 08:30:00")),
            listOf(hw("H1", "Алгебра", "2026-10-15", "L1", "повторить §3"), hw("H2", "Физика", "2026-10-15", "L2", "к тесту: §4-5"), hw("H3", "Химия", "2026-10-16", "L3", "x")),
        )
        val b = week(listOf(lesson("L1", "Алгебра", "2026-10-15 08:30:00", theme = "Контрольная работа")), listOf(hw("H1", "Алгебра", "2026-10-15", "L1", "повторить §3")))
        val notes = listOf(Note("n1", "L2", "взять тетрадь", false), Note("n2", "L3", "другой день", false), Note("n3", "L1", "сделано", true))
        val p = plan(listOf(a, b), d("2026-10-15"), setOf("H2"), notes)
        assertEquals(2, p.lessons)
        assertEquals("8:30", p.first)
        assertEquals(listOf("Алгебра"), p.open)
        assertEquals(listOf("Алгебра" to "Контрольная", "Физика" to "Тест"), p.tests)
        assertEquals(listOf("взять тетрадь"), p.notes)
    }

    @Test fun theNextSchoolDaySkipsTheWeekend() {
        val w = week(listOf(lesson("L", "X", "2026-10-19 08:30:00")))
        assertEquals(d("2026-10-19"), nextSchoolDay(listOf(w), d("2026-10-16")))
        assertNull(nextSchoolDay(listOf(w), d("2026-10-12")))
    }

    @Test fun theEveningTextSaysWhatIsSwitchedOn() {
        val p = DayPlan(d("2026-10-15"), 6, "8:30", listOf("Алгебра", "Физика", "Алгебра"), listOf("Алгебра" to "Контрольная"), emptyList())
        val today = d("2026-10-14")
        assertEquals("Завтра: 6 уроков с 8:30" to "Контрольные: Алгебра (контрольная). Не сделано заданий: 3 (Алгебра, Физика).", eveningText(p, today, true, true))
        assertTrue(!eveningText(p, today, true, false)!!.second.contains("Контрольн"))
        assertEquals("Завтра контрольная" to "Алгебра (контрольная)", eveningText(p, today, false, true))
        assertTrue(eveningText(p.copy(notes = listOf("взять форму")), today, true, true)!!.second.endsWith(" Не забыть: взять форму."))
        val calm = p.copy(tests = emptyList(), open = emptyList())
        assertNull(eveningText(calm, today, false, true))
        assertEquals("Все задания отмечены сделанными.", eveningText(calm, today, true, true)!!.second)
        assertTrue(eveningText(calm.copy(date = d("2026-10-19")), d("2026-10-16"), true, true)!!.first.startsWith("В понедельник: "))
        assertEquals(listOf("урок", "урока", "уроков", "урока"), listOf(1, 3, 11, 22).map(::lessonsWord))
    }

    @Test fun nowStateFollowsTheDay() {
        val l = listOf(lesson("a", "Алгебра", "2026-10-14 08:30:00", "2026-10-14 09:10:00", room = "21"), lesson("b", "Физика", "2026-10-14 09:25:00", "2026-10-14 10:05:00"))
        fun at(h: Int, m: Int) = nowState(l, LocalDateTime.of(2026, 10, 14, h, m))
        assertEquals("Первый урок: Алгебра в 8:30, каб. 21", at(7, 0)!!.text)
        assertEquals("Алгебра, каб. 21 · до конца 40 минут", at(8, 30)!!.text)
        assertEquals(LocalDateTime.of(2026, 10, 14, 9, 10), at(8, 59)!!.until)
        assertEquals("Перемена · дальше Физика через 13 минут", at(9, 12)!!.text)
        assertEquals("Перемена · дальше Физика через 1 минуту", at(9, 24)!!.text)
        assertNull(at(10, 5))
    }

    @Test fun icsIsValidAndFolded() {
        val e = IcsEvent("L-1/../x", "2026-10-14 08:30:00", "2026-10-14 09:10:00", "Ж".repeat(100), "Каб. 21", "Д/з: §5, №1; №2\nУчитель")
        val s = buildIcs(listOf(e, e.copy(start = "завтра")), "20261014T120000Z")
        assertTrue(s.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n") && s.endsWith("END:VCALENDAR\r\n"))
        assertEquals("bad times skipped", 1, Regex("BEGIN:VEVENT").findAll(s).count())
        assertTrue(s.contains("\r\nUID:L-1..x@openschool\r\n"))
        assertTrue(s.contains("\r\nDTSTART:20261014T083000\r\nDTEND:20261014T091000\r\n"))
        s.split("\r\n").forEach { assertTrue("${it.toByteArray().size} bytes", it.toByteArray().size <= 75) }
        val unfolded = s.replace("\r\n ", "")
        assertTrue(unfolded.contains("SUMMARY:${"Ж".repeat(100)}\r\n"))
        assertTrue(unfolded.contains("DESCRIPTION:Д/з: §5\\, №1\\; №2\\nУчитель\r\n"))
    }

    @Test fun theEveningRunIsPlannedForTodayOrTomorrow() {
        val now = LocalDateTime.of(2026, 10, 14, 18, 0)
        assertEquals(java.time.Duration.ofHours(1), EveningWorker.delayUntil(now, 19 * 60))
        assertEquals("past today's time: tomorrow", java.time.Duration.ofHours(23), EveningWorker.delayUntil(now.plusHours(1), 18 * 60))
        assertEquals("exactly now: tomorrow", java.time.Duration.ofHours(24), EveningWorker.delayUntil(LocalDateTime.of(2026, 10, 14, 19, 0), 19 * 60))
    }

    @Test fun searchFindsAllWordsInAnyOrderWithYoAsYe() {
        val items = listOf(Found("Задание", "2026-10-10", "L1", "Алгебра", "§5, ответить на вопросы"), Found("Тема урока", "2026-10-12", "L2", "Химия", "Ёмкость раствора"), Found("Оценка", "2026-10-11", null, "Алгебра", "Контрольная работа", "5"))
        assertEquals(listOf("L1"), searchIn(items, "вопросы алгебра").map { it.lessonId })
        assertEquals(1, searchIn(items, "емкость").size)
        assertEquals(listOf("2026-10-11", "2026-10-10"), searchIn(items, "алгебра").map { it.date })
        assertTrue(searchIn(items, "   ").isEmpty())
    }

    @Test fun theRunningAverageSkipsNonNumbersAndKeepsDateOrder() {
        fun mark(id: String, v: String, date: String) = uniffi.openschool_bridge.Mark(id, "S", "Алгебра", v, null, date, "", "", null, null, null)
        val pts = runningAverage(listOf(mark("b", "3", "2026-10-02"), mark("a", "5", "2026-10-01"), mark("c", "н", "2026-10-03"), mark("d", "4", "2026-10-03")))
        assertEquals(listOf(5.0, 4.0, 4.0), pts.map { it.average })
        assertEquals(listOf("a", "b", "d"), pts.map { it.mark.id })
    }

    @Test fun absencesAreCountedPerMonthWithEmptyMonths() {
        val a = uniffi.openschool_bridge.Absence("Н", "")
        val ls = listOf(lesson("1", "Химия", "2026-09-10 08:30:00").copy(absence = a), lesson("2", "Химия", "2026-11-02 08:30:00").copy(absence = a), lesson("3", "Физика", "2026-11-03 08:30:00").copy(absence = a), lesson("4", "Физика", "2026-11-04 08:30:00"))
        val m = absencesByMonth(ls, d("2026-09-01"), d("2026-11-30"))
        assertEquals(listOf(1, 0, 2), m.map { it.count })
        assertEquals(mapOf("Химия" to 1, "Физика" to 1), m[2].subjects)
    }

    @Test fun theWebViewVersionIsReadAsItsMajorNumber() {
        assertEquals(74, webViewMajor("74.0.3729.185"))
        assertEquals(141, webViewMajor("141.0.7390.122"))
        assertNull(webViewMajor(null))
        assertNull(webViewMajor("unknown"))
        assertTrue(webViewMajor("74.0.3729.185")!! < MIN_WEBVIEW_MAJOR)
    }

    @Test fun theDaysHomeworkIsSharedAsOneNumberedText() {
        val l1 = lesson("L1", "Алгебра", "2026-10-15 08:30:00")
        val h1 = hw("H1", "Алгебра", "2026-10-15", "L1", "§5,\n  №1–3").copy(materials = listOf(uniffi.openschool_bridge.Material("схема.pdf", "https://x")))
        val orphan = hw("H2", "", "2026-10-15", "", "принести форму")
        val day = DayData(listOf(LessonRow(1, l1, listOf(h1), emptyList())), listOf(orphan))
        assertEquals("Задания на четверг, 15 октября:\n1. Алгебра: §5, №1–3 (файлы: схема.pdf)\n2. Без предмета: принести форму", homeworkShareText(d("2026-10-15"), day))
        assertEquals("", homeworkShareText(d("2026-10-15"), DayData(emptyList(), emptyList())))
    }

    @Test fun missedLessonsCarryTheirTopicAndTheHomeworkOfThatDay() {
        val a = uniffi.openschool_bridge.Absence("Н", "")
        val ls = listOf(lesson("1", "Химия", "2026-10-10 08:30:00", theme = "Оксиды").copy(absence = a), lesson("2", "Химия", "2026-10-12 08:30:00").copy(absence = a), lesson("3", "Физика", "2026-10-12 09:25:00"))
        val hws = listOf(hw("H1", "Химия", "2026-10-14", "x", "§12").copy(issueDate = "2026-10-10"), hw("H2", "Физика", "2026-10-14", "x", "№5").copy(issueDate = "2026-10-10"))
        val m = missedLessons(ls, hws, d("2026-09-01"), d("2026-10-31"))
        assertEquals(listOf("2", "1"), m.map { it.lesson.id })
        assertEquals(listOf("H1"), m[1].homework.map { it.id })
        assertTrue(m[0].homework.isEmpty())
    }

    @Test fun theNextBandNeedsTheFewestFives() {
        assertEquals(5 to 4, nextBand(listOf(4.0, 4.0, 4.0, 4.0)))
        assertEquals(5 to 3, nextBand(listOf(3.0, 4.0, 5.0)))
        assertEquals(3 to 1, nextBand(listOf(2.0, 2.0)))
        assertNull(nextBand(listOf(4.0, 5.0)))
        assertNull(nextBand(emptyList()))
        for (a in listOf(listOf(4.0, 4.0, 4.0, 4.0), listOf(2.0, 3.0, 3.0, 4.0, 4.0, 4.0, 5.0), listOf(2.0, 2.0))) {
            val (g, k) = nextBand(a)!!
            val t = mapOf(3 to 2.6, 4 to 3.5, 5 to 4.5)[g]!!
            fun mean(k: Int) = (a.sum() + 5 * k) / (a.size + k)
            assertTrue(mean(k) >= t && (k == 1 || mean(k - 1) < t))
        }
        assertEquals(listOf("пятёрка", "пятёрки", "пятёрок", "пятёрок"), listOf(1, 3, 5, 11).map(::fivesWord))
    }
}
