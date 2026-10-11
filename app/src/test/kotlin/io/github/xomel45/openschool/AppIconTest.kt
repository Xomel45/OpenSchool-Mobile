package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppIconTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val res = File("src/main/res")

    @Test fun aDeveloperOnlyIconFallsBackWhileTheModeIsOffAndTheChoiceIsKept() {
        assertEquals(AppIcon.DEFAULT, AppIcon.effective(AppIcon.Spark, devEnabled = false))
        assertEquals(AppIcon.Spark, AppIcon.effective(AppIcon.Spark, devEnabled = true))
        assertEquals(AppIcon.Dusk, AppIcon.effective(AppIcon.Dusk, devEnabled = false))
        assertEquals(setOf(AppIcon.Disc, AppIcon.Spark), AppIcon.entries.filter { it.devOnly }.toSet())
    }

    @Test fun idsAreTheOnesOfTheSharedFormat() {
        assertEquals(listOf("graphite", "ink", "night", "paper", "dusk", "indigo", "dawn", "ring", "disc", "spark"), AppIcon.entries.map { it.id })
        assertNull(AppIcon.fromId("evil")); assertNull(AppIcon.fromId(null))
        assertEquals("io.github.xomel45.openschool.IconDusk", AppIcon.Dusk.alias)
    }

    /** Every icon needs its alias in the manifest (only the default one enabled) and its pictures; a missing piece crashes the launcher entry. */
    @Test fun everyIconHasItsAliasAndPictures() {
        val aliases = Regex("""<activity-alias(.*?)</activity-alias>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(AppIcon.entries.size, aliases.size)
        for (icon in AppIcon.entries) {
            val a = aliases.single { it.contains("android:name=\".Icon${icon.id.replaceFirstChar { c -> c.uppercase() }}\"") }
            assertTrue(icon.id, a.contains("android:icon=\"@mipmap/ic_${icon.id}\"") && a.contains("android:roundIcon=\"@mipmap/ic_${icon.id}\""))
            assertEquals(icon.id, icon == AppIcon.DEFAULT, a.contains("android:enabled=\"true\""))
            assertTrue(a.contains("android.intent.category.LAUNCHER") && a.contains("android:targetActivity=\".MainActivity\""))
            for (f in listOf("mipmap-anydpi-v26/ic_${icon.id}.xml", "drawable-nodpi/ic_${icon.id}_bg.png", "drawable-nodpi/ic_${icon.id}_fg.png", "drawable-nodpi/ic_prev_${icon.id}.png"))
                assertTrue("$f is missing", File(res, f).isFile)
        }
    }

    @Test fun theActivityItselfIsNoLauncherEntry() {
        val activity = Regex("""<activity android:name="\.MainActivity"[^>]*/>""").find(manifest)
        assertTrue("MainActivity must have no intent filter of its own", activity != null)
        assertFalse(manifest.substringBefore("<activity-alias").contains("LAUNCHER"))
    }
}
