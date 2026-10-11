package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorTest {
    @Test fun hexAndHsvRoundTripWithoutDrift() {
        for (hex in listOf("#2563EB", "#FF0000", "#00FF00", "#0000FF", "#FFFFFF", "#000000", "#808080", "#E8890C", "#123456", "#FEDCBA")) {
            val (h, s, v) = hexToHsv(hex)!!
            assertEquals(hex, hsvToHex(h, s, v))
        }
    }

    @Test fun primariesHaveTheRightHue() {
        assertEquals(0f, hexToHsv("#FF0000")!!.first, 0.01f)
        assertEquals(120f, hexToHsv("#00FF00")!!.first, 0.01f)
        assertEquals(240f, hexToHsv("#0000FF")!!.first, 0.01f)
        assertEquals("#FFFF00", hsvToHex(60f, 1f, 1f))
        assertNull(hexToHsv("blue")); assertNull(hexToHsv("#12345"))
    }
}
