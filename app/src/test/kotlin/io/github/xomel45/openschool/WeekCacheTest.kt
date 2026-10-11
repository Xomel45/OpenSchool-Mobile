package io.github.xomel45.openschool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.openschool_bridge.Absence
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Mark
import uniffi.openschool_bridge.Material
import uniffi.openschool_bridge.Teacher
import uniffi.openschool_bridge.Week

class WeekCacheTest {
    private val full = Week(
        2026u, 41u,
        listOf(
            Lesson("l1", "s1", "Физика", Teacher("Анна", "Белова", "Ивановна"), "2026-10-06 08:30", "2026-10-06 09:15", "12", "Тема", Absence("Н", "По болезни")),
            Lesson("l2", "s2", "Химия", Teacher("", "", ""), "2026-10-06 09:30", "2026-10-06 10:15", "", null, null),
        ),
        listOf(Homework("h1", "s1", "Физика", "§5 \"в кавычках\"\nвторая строка", "2026-10-01", "2026-10-06", "l1", listOf(Material("файл.docx", "https://example.test/f"))), Homework("h2", null, "Химия", "", "2026-10-02", "2026-10-07", null, emptyList())),
        listOf(Mark("m1", "s1", "Физика", "5", "4", "2026-10-06", "T", "Контрольная", "Работа №3", "Молодец", "l1"), Mark("m2", "s2", "Химия", "н", null, "2026-10-06", "T", "Пропуск", null, null, null)),
    )

    @Test fun aWeekSurvivesTheRoundTripThroughJsonIncludingNullsAndOddText() {
        assertEquals(full, weekFromJson(JSONObject(weekToJson(full).toString())))
        val empty = Week(2026u, 1u, emptyList(), emptyList(), emptyList())
        assertEquals(empty, weekFromJson(JSONObject(weekToJson(empty).toString())))
    }

    @Test(expected = Exception::class) fun aDamagedFileIsAnErrorNotAHalfWeek() {
        val o = JSONObject(weekToJson(full).toString())
        o.getJSONArray("marks").getJSONObject(0).remove("value")
        weekFromJson(o)
    }

    @Test fun nullStaysNullAndEmptyStaysEmpty() {
        val back = weekFromJson(JSONObject(weekToJson(full).toString()))
        assertNull(back.lessons[1].theme); assertNull(back.lessons[1].absence); assertNull(back.homeworks[1].subjectId)
        assertEquals("", back.homeworks[1].description)
    }
}
