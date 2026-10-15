package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesTest {
    private fun snap(vararg days: Pair<String, List<LessonSnap>>): Snapshot = days.toMap()
    private fun l(id: String, s: String, t: String, r: String = "") = LessonSnap(id, s, t, r)
    private fun details(e: List<NewsEvent>) = e.map { "${it.subject}: ${it.detail}" }

    @Test fun substitutionCancelNewMoveAndRoomAreToldLikeOnTheDesktop() {
        val old = snap("2026-10-15" to listOf(l("1", "Алгебра", "08:30", "21"), l("2", "Физика", "09:25", "5"), l("3", "Химия", "10:20", "7"), l("4", "Труд", "11:15", "1")))
        val new = snap("2026-10-15" to listOf(l("1", "Алгебра", "08:30", "34"), l("9", "История", "09:25", "5"), l("4", "Труд", "12:10", "1"), l("8", "ИЗО", "13:05", "2")))
        assertEquals(
            listOf(
                "История: Замена: Физика → История, 15 октября, 9:25",
                "Химия: Урок отменён: 15 октября, 10:20",
                "ИЗО: Новый урок: 15 октября, 13:05",
                "Алгебра: Кабинет: каб. 21 → каб. 34, 15 октября, 8:30",
                "Труд: Перенос: 11:15 → 12:10, 15 октября",
            ),
            details(scheduleChanges(old, new, "2026-10-15")),
        )
    }

    @Test fun newWeeksVanishedDaysAndThePastSayNothing() {
        val old = snap("2026-10-14" to listOf(l("1", "A", "08:30")), "2026-10-15" to listOf(l("2", "B", "08:30")))
        val new = snap("2026-10-14" to listOf(l("5", "C", "08:30")), "2026-10-20" to listOf(l("3", "D", "08:30")))
        assertTrue(scheduleChanges(old, new, "2026-10-15").isEmpty())
        assertTrue(scheduleChanges(old, old, "2026-10-14").isEmpty())
    }

    @Test fun theSnapshotSurvivesJson() {
        val s = snap("2026-10-15" to listOf(l("1", "Алгебра", "08:30", "21")), "2026-10-16" to listOf(l("2", "Б", "09:25")))
        assertEquals(s, snapshotFromJson(snapshotToJson(s)))
        assertTrue(snapshotFromJson("{broken").isEmpty())
    }
}
