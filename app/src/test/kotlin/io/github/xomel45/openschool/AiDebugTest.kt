package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.openschool_bridge.Absence
import uniffi.openschool_bridge.BridgeException
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Mark
import uniffi.openschool_bridge.Material
import uniffi.openschool_bridge.Teacher
import uniffi.openschool_bridge.Week
import java.time.LocalDate

class AiDebugTest {
    @Test fun valuesAddressesAndTokensDoNotSurvive() {
        val t = AiScrub.scrub("invalid type: string \"Иван Петров\", expected u32 at https://www.gosuslugi.ru/api/x?id=1 mail a.b@c.ru phone 79001234567 cookie 3f2a9c8e7d6b5a4c3b2a1f0e9d8c7b6a", 400)
        for (secret in listOf("Иван", "Петров", "gosuslugi", "a.b@c.ru", "79001234567", "3f2a9c8e")) assertFalse("$secret left in: $t", t.contains(secret))
        assertTrue("the reason stays: $t", t.contains("invalid type") && t.contains("expected u32"))
    }

    @Test fun shortNumbersAndFieldNamesStayAndTheTextIsCut() {
        assertEquals("missing field `due_date` at line 1 column 214", AiScrub.scrub("missing field `due_date` at line 1 column 214"))
        assertEquals("abcd", AiScrub.scrub("abcdefghij", 4))
    }

    @Test fun failuresAreClassifiedWithoutTheirDetails() {
        assertEquals("auth (401/403)", AiLog.classify(BridgeException.Auth("cookie=abc")))
        assertEquals("http_503", AiLog.classify(BridgeException.Network("HTTP 503 Service Unavailable: <html>secret</html>")))
        assertEquals("network: unreachable", AiLog.classify(BridgeException.Network("error sending request for url (https://x.ru/a?b=1)")))
        val p = AiLog.classify(BridgeException.Parse("invalid type: string \"Мария\", expected a sequence"))
        assertTrue(p, p.startsWith("parse: ") && !p.contains("Мария"))
    }

    @Test fun nothingIsRecordedWhileTheDiagnosticsAreOff() {
        AiLog.enabled = false
        val before = AiLog.snapshot().size
        AiLog.note("week", "ok")
        assertEquals(before, AiLog.snapshot().size)
        AiLog.enabled = true
        AiLog.note("week", "ok", 5)
        assertEquals(before + 1, AiLog.snapshot().size)
        AiLog.enabled = false
    }

    private val teacher = Teacher("Анна", "Белова", "Ивановна")
    private val week = Week(
        2026u, 41u,
        listOf(
            Lesson("lesson-id-0123456789abcdef", "subj-1", "Физика", teacher, "2026-10-06 08:30", "2026-10-06 09:15", "12", "Закон Ома", Absence("Н", "По болезни")),
            Lesson("l2", "subj-2", "Химия", Teacher("", "", ""), "2026-10-06 09:30", "2026-10-06 10:15", "", null, null),
        ),
        listOf(Homework("h1", "subj-1", "Физика", "§5 ответить на вопросы Ивану Петрову", "2026-10-01", "2026-10-06", "lesson-id-0123456789abcdef", listOf(Material("секретный.docx", "https://example.test/f")))),
        listOf(Mark("m1", "subj-1", "Физика", "5", "4", "2026-10-06", "T", "Контрольная", "Работа №3", "Молодец, Мария", "lesson-id-0123456789abcdef"), Mark("m2", "subj-2", "Химия", "н", null, "2026-10-06", "T", "Пропуск", null, null, null)),
    )

    @Test fun theReportHasTheShapeOfTheDataAndNoneOfItsContents() {
        val shape = AiReport.shape(listOf(week), LocalDate.of(2026, 10, 7))
        val facts = AiFacts(listOf("- app"), listOf("- net"), listOf("- login"), listOf("- settings"), listOf(3, 70), shape,
            listOf(AiLog.Event(System.currentTimeMillis() - 5000, "week", AiScrub.scrub("parse: invalid type: string \"Мария Сидорова\"", 160), 120)), "NullPointerException at X.y:1")
        val text = AiScrub.report(AiReport.build(facts))
        for (secret in listOf("Физика", "Химия", "Анна", "Белова", "Ивановна", "Закон Ома", "Ивану", "Петрову", "Мария", "Сидорова", "секретный", "Молодец", "lesson-id-0123456789abcdef", "subj-1", "example.test", "Работа")) {
            assertFalse("$secret leaked:\n$text", text.contains(secret))
        }
        assertTrue(text, text.contains("week +0") && text.contains("lessons 2") && text.contains("homework 1") && text.contains("marks 2"))
        assertTrue(text, text.contains("value shapes 9×1, a×1") || text.contains("value shapes a×1, 9×1"))
        assertTrue(text, text.contains("empty theme 1") && text.contains("with absence 1") && text.contains("double 1"))
        assertTrue(text, text.contains("## Last crash") && text.contains("## Requests"))
    }
}
