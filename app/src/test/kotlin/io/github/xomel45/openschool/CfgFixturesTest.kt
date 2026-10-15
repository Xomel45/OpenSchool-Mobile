package io.github.xomel45.openschool

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/** The shared test files of the settings format (OpenSchool-Bridge/docs/cfg): every app must read them the same way (the desktop app has the same expectations). */
class CfgFixturesTest {
    private fun fixture(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("cfg/$name")!!.use { it.readBytes() }

    @Test fun theMinimalFileChangesNothing() {
        val s = SettingsFile.read(fixture("minimal.cfg"))
        assertNull(s.style); assertNull(s.scheme); assertNull(s.zoom); assertNull(s.icon); assertNull(s.bg); assertNull(s.notifications); assertNull(s.network); assertNull(s.picture)
        assertEquals(false, s.accentGiven)
    }

    @Test fun theFullFileIsReadFieldByField() {
        val s = SettingsFile.read(fixture("full.cfg"))
        assertEquals(StylePref.Sunrise, s.style); assertEquals(SchemePref.Dark, s.scheme); assertEquals("#B04199", s.accentHex); assertEquals(1.15f, s.zoom)
        assertEquals(AppIcon.Dusk, s.icon)
        assertEquals(Bg(mode = "gradient", color = "#E2E8F0", a = "#FF9966", b = "#5B86E5", angle = 120, dim = 40, blur = 3), s.bg)
        // the file says 5 minutes; this phone runs 15, 30 or 60 and takes the nearest
        assertEquals(NotificationsPatch(marks = true, homework = false, absences = true, detail = false, intervalMin = 15, quiet = true, quietFrom = 21 * 60 + 30, quietTo = 6 * 60 + 45, changes = false, evening = false, eveningAt = 20 * 60 + 15, testReminders = false), s.notifications)
        assertEquals(NetMode.Direct, s.network)
    }

    @Test fun aPictureComesAlongAndAVideoIsLeftOut() {
        val img = SettingsFile.read(fixture("with-image.cfg"))
        assertEquals("media", img.bg!!.mode)
        assertNotNull(img.picture); assertEquals(0x89.toByte(), img.picture!![0]); assertEquals('P'.code.toByte(), img.picture!![1])
        val vid = SettingsFile.read(fixture("with-video.cfg"))
        assertNull(vid.picture) // a phone does not show video: the file is skipped, the rest still applies
        assertEquals(StylePref.Sunrise, vid.style)
    }

    @Test fun hostileValuesAreSkippedAndWhatIsValidStillApplies() {
        val s = SettingsFile.read(fixture("hostile.cfg"))
        assertNull(s.style); assertNull(s.scheme); assertNull(s.zoom); assertNull(s.network); assertNull(s.icon)
        assertEquals(Accent.Red, s.accent)
        assertEquals(Bg(mode = "default", color = "#E2E8F0", a = "#FF9966", b = "#FF5E62", angle = 160, dim = 80, blur = 0), s.bg)
        assertEquals(NotificationsPatch(marks = null, homework = null, absences = null, detail = false, intervalMin = null), s.notifications)
        assertNull(s.picture)
    }

    @Test fun whatThePhoneWritesIsWhatTheSpecSaysAndItReadsBack() {
        val cfg = SettingsFile.config(StylePref.Terminal, SchemePref.Light, Accent.Teal, null, 1.3f, Bg(mode = "solid", color = "#112233"), NewsSettings(marks = false, intervalMin = 30, detail = false), NetMode.System, AppIcon.Spark)
        // only the keys of the spec, nothing private
        assertEquals(setOf("format", "version", "style", "scheme", "accent", "zoom", "icon", "bg", "notifications", "network"), cfg.keys().asSequence().toSet())
        assertEquals(setOf("marks", "homework", "absences", "interval_min", "detail", "quiet", "changes", "evening"), cfg.getJSONObject("notifications").keys().asSequence().toSet())
        assertEquals(setOf("on", "at", "tests"), cfg.getJSONObject("notifications").getJSONObject("evening").keys().asSequence().toSet())
        assertEquals(setOf("on", "from", "to"), cfg.getJSONObject("notifications").getJSONObject("quiet").keys().asSequence().toSet())
        val back = SettingsFile.read(SettingsFile.archive(cfg, null))
        assertEquals(StylePref.Terminal, back.style); assertEquals(Accent.Teal, back.accent); assertEquals(1.3f, back.zoom)
        assertEquals("solid", back.bg!!.mode); assertEquals(NetMode.System, back.network); assertEquals(AppIcon.Spark, back.icon)
        assertEquals(NotificationsPatch(false, true, false, false, 30, false, 22 * 60, 7 * 60, true, true, 19 * 60, true), back.notifications)
        // the archive is a zip with exactly config.json
        val names = ZipInputStream(SettingsFile.archive(cfg, null).inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertEquals(listOf("config.json"), names)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2)
        val withPic = SettingsFile.read(SettingsFile.archive(cfg, png)).picture
        assertArrayEquals(png, withPic)
        assertTrue(clampInterval(5) == 15 && clampInterval(15) == 15 && clampInterval(45) == 60 && clampInterval(60) == 60)
    }

    @Test fun aFileThatIsNotASettingsFileIsRefused() {
        val bad = SettingsFile.archive(JSONObject().put("format", "something-else"), null)
        try { SettingsFile.read(bad); throw AssertionError("should have been refused") } catch (e: IllegalArgumentException) { /* expected */ }
    }

    private val androidConfig get() = SettingsFile.config(
        StylePref.Classic, SchemePref.Light, Accent.Amber, null, 1.3f, Bg(mode = "solid", color = "#112233", dim = 20, blur = 0),
        NewsSettings(marks = false, homework = true, absences = false, intervalMin = 30, detail = true, quiet = true, quietFrom = 23 * 60, quietTo = 6 * 60 + 30, changes = false, evening = true, eveningAt = 18 * 60 + 30, testReminders = false), NetMode.System, AppIcon.Night,
    )

    /** `OPENSCHOOL_WRITE_FIXTURE=<path> gradlew testDebugUnitTest` writes what this app writes, as the shared file `from-android.cfg`. */
    @Test fun writeTheAndroidMadeFileWhenAsked() {
        System.getenv("OPENSCHOOL_WRITE_FIXTURE")?.let { java.io.File(it).writeBytes(SettingsFile.archive(androidConfig, null)) }
    }

    @Test fun theFileTheAndroidAppWroteIsReadBackAndMatchesWhatItWritesNow() {
        val s = SettingsFile.read(fixture("from-android.cfg"))
        assertEquals(StylePref.Classic, s.style); assertEquals(SchemePref.Light, s.scheme); assertEquals(Accent.Amber, s.accent); assertEquals(1.3f, s.zoom)
        assertEquals(AppIcon.Night, s.icon)
        assertEquals("solid", s.bg!!.mode); assertEquals("#112233", s.bg!!.color); assertEquals(20, s.bg!!.dim)
        assertEquals(NotificationsPatch(false, true, false, true, 30, true, 23 * 60, 6 * 60 + 30, false, true, 18 * 60 + 30, false), s.notifications); assertEquals(NetMode.System, s.network)
        // the file in the repository is what this code produces today (a change of the format must change the shared file on purpose)
        assertEquals(androidConfig.toString(), JSONObject(java.util.zip.ZipInputStream(fixture("from-android.cfg").inputStream()).use { z -> z.nextEntry; z.readBytes().toString(Charsets.UTF_8) }).toString())
    }

    @Test fun theFileTheDesktopAppWroteIsReadToo() {
        val s = SettingsFile.read(fixture("from-desktop.cfg"))
        assertEquals(StylePref.Sunrise, s.style); assertEquals(SchemePref.Dark, s.scheme); assertEquals("#B04199", s.accentHex); assertEquals(1.15f, s.zoom)
        assertEquals("gradient", s.bg!!.mode); assertEquals(NetMode.Direct, s.network); assertEquals(15, s.notifications!!.intervalMin)
    }
}
