package com.gios.brightrecorder.label

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelTitleTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "brlabel-${System.nanoTime()}")
        .apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a tape with nothing chosen has no title on its label`() {
        val title = LabelTitle.read(dir)
        assertFalse(title.shown)
        assertEquals(LabelFont.Plain, title.font)
    }

    @Test
    fun `a title survives being written and read back`() {
        LabelTitle.write(dir, LabelTitle.Title(shown = true, font = LabelFont.Typewriter))
        val read = LabelTitle.read(dir)
        assertTrue(read.shown)
        assertEquals(LabelFont.Typewriter, read.font)
    }

    /** A folder of recordings should not accrue a file that says "nothing chosen". */
    @Test
    fun `choosing nothing writes no file at all`() {
        LabelTitle.write(dir, LabelTitle.Title())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `turning a title back off removes the file`() {
        LabelTitle.write(dir, LabelTitle.Title(shown = true, font = LabelFont.Serif))
        assertTrue((dir.listFiles()?.size ?: 0) > 0)
        LabelTitle.write(dir, LabelTitle.Title())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    /**
     * A label written by a later version naming a face this one does not have must not lose the
     * title — the tape folder is meant to survive being carried between installs.
     */
    @Test
    fun `an unknown face falls back to plain rather than losing the title`() {
        File(dir, "label-title.txt").writeText("1\nCopperplate\n")
        val read = LabelTitle.read(dir)
        assertTrue(read.shown)
        assertEquals(LabelFont.Plain, read.font)
    }

    @Test
    fun `a corrupt file reads as no title rather than throwing`() {
        File(dir, "label-title.txt").writeText("")
        assertFalse(LabelTitle.read(dir).shown)
    }

    @Test
    fun `clear removes the title`() {
        LabelTitle.write(dir, LabelTitle.Title(shown = true, font = LabelFont.Heavy))
        LabelTitle.clear(dir)
        assertFalse(LabelTitle.read(dir).shown)
    }

    // --------------------------------------------------------------------------- faces

    /** The TYPE key walks the faces and comes back round, so the cycle has to close. */
    @Test
    fun `the faces cycle back to the first`() {
        var font = LabelFont.entries.first()
        repeat(LabelFont.entries.size) { font = font.next() }
        assertEquals(LabelFont.entries.first(), font)
    }

    @Test
    fun `every face has a label short enough for a key`() {
        LabelFont.entries.forEach {
            assertTrue("${it.name} label is too long", it.label.length <= 7)
            assertTrue("${it.name} has no label", it.label.isNotBlank())
        }
    }

    /** Spacing out a title is a look, and the capitals are part of it. */
    @Test
    fun `only the spaced face changes the words`() {
        LabelFont.entries.forEach { font ->
            val rendered = font.render("Trip to Rome")
            if (font == LabelFont.Spaced) {
                assertEquals("TRIP TO ROME", rendered)
            } else {
                assertEquals("Trip to Rome", rendered)
            }
        }
    }
}
