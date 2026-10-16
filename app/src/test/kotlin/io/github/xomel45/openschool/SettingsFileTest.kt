package io.github.xomel45.openschool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsFileTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }
    private fun cfg(extra: String = "") = """{"format":"openschool-config","version":1$extra}""".toByteArray()

    @Test fun desktopFileIsReadAndValuesAreChecked() {
        val s = SettingsFile.read(zip("config.json" to cfg(""","style":"sunrise","scheme":"dark","accent":"teal","zoom":1.3,"bg":{"mode":"solid","color":"#112233","dim":500}""")))
        assertEquals(StylePref.Sunrise, s.style); assertEquals(SchemePref.Dark, s.scheme); assertEquals(Accent.Teal, s.accent); assertEquals(1.3f, s.zoom)
        assertEquals("solid", s.bg!!.mode); assertEquals("#112233", s.bg.color); assertEquals(80, s.bg.dim) // clamped
    }

    @Test fun junkFallsBackAndACustomAccentIsKept() {
        val s = SettingsFile.read(zip("config.json" to cfg(""","style":"nope","scheme":7,"accent":"#ff00ff","zoom":9,"bg":{"mode":"x","color":"red"}""")))
        assertNull(s.style); assertNull(s.scheme); assertNull(s.zoom); assertEquals(true, s.accentGiven); assertEquals("#FF00FF", s.accentHex)
        assertEquals("default", s.bg!!.mode); assertEquals("#E2E8F0", s.bg.color)
    }

    @Test fun foreignEntriesAreIgnoredAndBadArchivesRefused() {
        val ok = SettingsFile.read(zip("config.json" to cfg(), "../evil.sh" to "x".toByteArray(), "background.exe" to "x".toByteArray(), "sub/config.json" to """{"no":1}""".toByteArray(), "background.png" to byteArrayOf(1, 2, 3)))
        assertTrue(ok.picture!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertThrows(IllegalArgumentException::class.java) { SettingsFile.read(zip("background.png" to byteArrayOf(1))) }
        assertThrows(IllegalArgumentException::class.java) { SettingsFile.read(zip("config.json" to ByteArray(70_000) { ' '.code.toByte() })) }
        assertThrows(IllegalArgumentException::class.java) { SettingsFile.read(zip("config.json" to """{"format":"other"}""".toByteArray())) }
        assertThrows(IllegalArgumentException::class.java) { SettingsFile.read(zip("config.json" to cfg(), "background.png" to byteArrayOf(1), "background.jpg" to byteArrayOf(2))) }
        assertThrows(IllegalArgumentException::class.java) { SettingsFile.read(zip("config.json" to byteArrayOf(0xff.toByte(), 0xfe.toByte()))) }
    }
}
