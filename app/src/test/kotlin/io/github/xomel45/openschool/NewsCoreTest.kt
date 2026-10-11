package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsCoreTest {
    private fun ev(kind: Kind, subject: String, value: String? = null, detail: String = "d") = NewsEvent("k", kind, subject, value, detail, "2026-10-08")

    @Test fun pluralFollowsRussianRules() {
        val f = { n: Int -> plural(n, "оценка", "оценки", "оценок") }
        assertEquals(listOf("оценка", "оценки", "оценок", "оценок", "оценок", "оценка"), listOf(1, 2, 5, 11, 14, 21).map(f))
    }

    @Test fun oneMarkShowsSubjectAndValueOrHidesThem() {
        val e = ev(Kind.Mark, "Физика", "4", "Контрольная · 8 октября")
        assertEquals("Новая оценка: Физика — 4" to "Контрольная · 8 октября", notificationText(listOf(e), true))
        assertEquals("Новая оценка" to "Откройте OpenSchool", notificationText(listOf(e), false))
    }

    @Test fun severalEventsAreSummarised() {
        val items = listOf(ev(Kind.Mark, "Физика", "4"), ev(Kind.Mark, "Химия", "5"), ev(Kind.Mark, "Алгебра", "3"), ev(Kind.Mark, "Музыка", "5"))
        assertEquals("4 новые оценки" to "Физика 4 · Химия 5 · Алгебра 3 · и ещё 1", notificationText(items, true))
        assertEquals("2 новых события", notificationText(listOf(ev(Kind.Mark, "a", "5"), ev(Kind.Homework, "b")), true).first)
    }

    @Test fun datesAndLongTextsAreShortened() {
        assertEquals("8 октября", formatDate("2026-10-08"))
        assertEquals("мусор", formatDate("мусор"))
        assertEquals("a b…", truncateText("a   b\n c d", 3))
    }
}
