package io.github.xomel45.openschool

import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.openschool_bridge.BridgeException

class AttachmentsTest {
    private fun text(e: Throwable) = Attachments.errorText(e)

    @Test fun everyKindOfFailureHasATextTheUserCanRead() {
        assertTrue(text(BridgeException.Auth("HTTP 401")).contains("войдите"))
        assertTrue(text(BridgeException.Network("HTTP 403 Forbidden")).contains("HTTP 403"))
        assertTrue(text(BridgeException.Network("error sending request")).contains("Нет связи"))
        assertTrue(text(BridgeException.Network("too many redirects")).contains("по кругу"))
        assertTrue(text(BridgeException.Parse("file too big")).contains("50 МБ"))
        assertTrue(text(BridgeException.Parse("not a file (a web page came back)")).contains("веб-страницу"))
        assertTrue(text(BridgeException.Parse("link is not https")).contains("небезопасной"))
        assertTrue(text(RuntimeException("boom")).contains("Не удалось"))
    }

    @Test fun theTextsNeverRepeatWhatTheServerSaid() {
        val t = text(BridgeException.Network("HTTP 500 Internal Server Error: <html>секрет Иван Петров</html>"))
        assertTrue(t, !t.contains("Иван") && !t.contains("секрет"))
    }
}
