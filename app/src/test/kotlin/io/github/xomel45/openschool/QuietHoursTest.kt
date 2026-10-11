package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test fun theNightRunsOverMidnightAndIncludesOnlyItsStart() {
        val night = NewsSettings(quiet = true, quietFrom = 22 * 60, quietTo = 7 * 60)
        for ((m, quiet) in listOf(22 * 60 to true, 22 * 60 - 1 to false, 23 * 60 + 59 to true, 0 to true, 6 * 60 + 59 to true, 7 * 60 to false, 12 * 60 to false)) assertEquals("$m", quiet, night.isQuiet(m))
        val day = NewsSettings(quiet = true, quietFrom = 13 * 60, quietTo = 15 * 60)
        assertTrue(day.isQuiet(13 * 60) && day.isQuiet(14 * 60 + 59)); assertFalse(day.isQuiet(15 * 60)); assertFalse(day.isQuiet(3 * 60))
    }

    @Test fun offAndEqualTimesMeanNoQuietHours() {
        assertFalse(NewsSettings(quiet = false).isQuiet(0))
        assertFalse(NewsSettings(quiet = true, quietFrom = 480, quietTo = 480).isQuiet(480))
    }

    @Test fun theTimeTextRoundTripsAndOddTextIsRefused() {
        assertEquals("06:45", minutesToHm(6 * 60 + 45)); assertEquals(6 * 60 + 45, parseHm("06:45")); assertEquals(0, parseHm("00:00")); assertEquals(23 * 60 + 59, parseHm("23:59"))
        for (bad in listOf("24:00", "5:00", "12:60", "", "ab:cd", "07:00:00", " 07:00")) assertNull(bad, parseHm(bad))
        assertNull(parseHm(null))
    }
}
