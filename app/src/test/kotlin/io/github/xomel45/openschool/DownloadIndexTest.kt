package io.github.xomel45.openschool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadIndexTest {
    private fun dir(): File = Files.createTempDirectory("dl").toFile()

    @Test fun savedFilesAreListedNewestFirstAndSurviveANewIndex() {
        val root = dir()
        val ix = DownloadIndex(root)
        val a = ix.add("https://x/a", "a.pdf", byteArrayOf(1, 2, 3), "Алгебра", "2026-10-15", "H1", 1000)
        val b = ix.add("https://x/a", "a.pdf", byteArrayOf(9), "Алгебра", "2026-10-15", "H1", 2000)
        assertTrue("same name twice: two files", ix.fileOf(a) != ix.fileOf(b) && ix.fileOf(a)!!.readBytes().size == 3)
        assertEquals(listOf(b.id, a.id), DownloadIndex(root).list().map { it.id })
        assertEquals(3L, a.size)
        assertEquals("a.pdf", a.name)
    }

    @Test fun filesRemovedBehindItsBackAreDroppedAndDeleteOnlyByKnownId() {
        val ix = DownloadIndex(dir())
        val a = ix.add("l1", "a.txt", byteArrayOf(1), "", "", "", 1)
        val b = ix.add("l2", "b.txt", byteArrayOf(1), "", "", "", 2)
        ix.fileOf(a)!!.delete()
        assertEquals(listOf(b.id), ix.list().map { it.id })
        assertFalse(ix.delete("nope"))
        assertTrue(ix.fileOf(b)!!.isFile)
        assertTrue(ix.delete(b.id))
        assertFalse(ix.fileOf(b)!!.exists())
        assertTrue(ix.list().isEmpty())
    }

    @Test fun aPathFromABrokenIndexNeverLeavesTheFolder() {
        val root = dir()
        val ix = DownloadIndex(root)
        assertNull(ix.fileOf(Saved("x", "l", "../../etc/passwd", 1, 1, "", "", "")))
        File(root, "index.json").writeText("{broken")
        assertTrue("a broken index is an empty list", ix.list().isEmpty())
    }

    @Test fun kindsAndSizesReadWell() {
        assertEquals("PDF", fileKind("Схема.PDF").first)
        assertEquals("DOC", fileKind("a.docx").first)
        assertEquals("ZIP", fileKind("ответы.zip").first)
        assertEquals("ФАЙЛ", fileKind("noext").first)
        assertEquals("EXE", fileKind("x.exe").first)
        assertEquals("512 Б", fmtSize(512))
        assertEquals("2 КБ", fmtSize(2048))
        assertEquals("1,5 МБ", fmtSize(1536L * 1024))
    }
}
