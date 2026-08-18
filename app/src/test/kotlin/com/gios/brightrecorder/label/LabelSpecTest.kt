package com.gios.brightrecorder.label

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelSpecTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "brlabel-${System.nanoTime()}")
        .apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // -------------------------------------------------------------------- storage

    @Test
    fun `a tape nobody has labelled reads as the defaults`() {
        assertEquals(LabelSpec(), LabelSpec.read(dir))
    }

    @Test
    fun `everything about a label survives being written and read back`() {
        val spec = LabelSpec(
            titleShown = true,
            font = LabelFont.Cursive,
            titleX = 0.3f,
            titleY = 0.2f,
            titleSize = 0.4f,
            titleAngle = -12f,
            filter = LabelFilter.Punch,
            photoScale = 2.5f,
            photoX = -0.4f,
            photoY = 0.6f,
        )
        LabelSpec.write(dir, spec)
        assertEquals(spec, LabelSpec.read(dir))
    }

    /** A folder of recordings should not accrue a file that says "everything as it would be". */
    @Test
    fun `a label with nothing chosen writes no file at all`() {
        LabelSpec.write(dir, LabelSpec())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `going back to the defaults removes the file`() {
        LabelSpec.write(dir, LabelSpec(titleShown = true, font = LabelFont.Pixel))
        assertTrue((dir.listFiles()?.size ?: 0) > 0)
        LabelSpec.write(dir, LabelSpec())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    /**
     * A tape folder is meant to survive being carried between installs, so a file written by a
     * later version must not lose what this version does understand.
     */
    @Test
    fun `unknown keys and faces are ignored rather than losing the label`() {
        File(dir, "label.txt").writeText(
            "title=1\nfont=Copperplate\ntitleX=0.25\nsomethingNew=7\nfilter=Sepia\n",
        )
        val read = LabelSpec.read(dir)
        assertTrue(read.titleShown)
        assertEquals(LabelFont.Plain, read.font)
        assertEquals(LabelFilter.Normal, read.filter)
        assertEquals(0.25f, read.titleX, 1e-4f)
    }

    @Test
    fun `a corrupt file reads as the defaults rather than throwing`() {
        File(dir, "label.txt").writeText("  not a label at all")
        assertEquals(LabelSpec(), LabelSpec.read(dir))
    }

    /** v1.8 wrote a two-line file. A label made then must still open. */
    @Test
    fun `a label written by the previous version is still read`() {
        File(dir, "label-title.txt").writeText("1\nTypewriter\n")
        val read = LabelSpec.read(dir)
        assertTrue(read.titleShown)
        assertEquals(LabelFont.Typewriter, read.font)
    }

    @Test
    fun `saving replaces the previous version's file rather than leaving both`() {
        File(dir, "label-title.txt").writeText("1\nSerif\n")
        LabelSpec.write(dir, LabelSpec(titleShown = true, font = LabelFont.Serif))
        assertFalse(File(dir, "label-title.txt").exists())
        assertTrue(File(dir, "label.txt").isFile)
    }

    // -------------------------------------------------------------------- limits

    @Test
    fun `a photograph cannot be zoomed past its limits`() {
        assertEquals(LabelSpec.MAX_SCALE, LabelSpec().withPhotoScale(99f).photoScale, 1e-4f)
        assertEquals(LabelSpec.MIN_SCALE, LabelSpec().withPhotoScale(0.01f).photoScale, 1e-4f)
    }

    /** Otherwise a photograph could be shoved off its own label, leaving a band of black. */
    @Test
    fun `a photograph cannot be nudged off the label`() {
        val far = LabelSpec().withPhotoAt(9f, -9f)
        assertEquals(1f, far.photoX, 1e-4f)
        assertEquals(-1f, far.photoY, 1e-4f)
    }

    @Test
    fun `a title cannot be dragged off the label`() {
        val far = LabelSpec().withTitleAt(4f, -4f)
        assertEquals(1f, far.titleX, 1e-4f)
        assertEquals(0f, far.titleY, 1e-4f)
    }

    @Test
    fun `a title cannot be pinched to nothing or to fill the panel`() {
        assertEquals(LabelSpec.MAX_TITLE, LabelSpec().withTitleSize(9f).titleSize, 1e-4f)
        assertEquals(LabelSpec.MIN_TITLE, LabelSpec().withTitleSize(0f).titleSize, 1e-4f)
    }

    // --------------------------------------------------------------------- faces

    @Test
    fun `the faces cycle back to the first`() {
        var font = LabelFont.entries.first()
        repeat(LabelFont.entries.size) { font = font.next() }
        assertEquals(LabelFont.entries.first(), font)
    }

    @Test
    fun `the filters cycle back to the first`() {
        var filter = LabelFilter.entries.first()
        repeat(LabelFilter.entries.size) { filter = filter.next() }
        assertEquals(LabelFilter.entries.first(), filter)
    }

    @Test
    fun `every face and filter has a label short enough for a key`() {
        LabelFont.entries.forEach {
            assertTrue("${it.name} label is too long", it.label.length <= 8)
            assertTrue("${it.name} has no label", it.label.isNotBlank())
        }
        LabelFilter.entries.forEach {
            assertTrue("${it.name} label is too long", it.label.length <= 8)
        }
    }

    @Test
    fun `only the spaced and pixel faces change the words`() {
        LabelFont.entries.forEach { font ->
            val rendered = font.render("Trip to Rome")
            if (font == LabelFont.Spaced || font == LabelFont.Pixel) {
                assertEquals("TRIP TO ROME", rendered)
            } else {
                assertEquals("Trip to Rome", rendered)
            }
        }
    }

    // ----------------------------------------------------------------------- ink

    @Test
    fun `the inks cycle back to the first`() {
        var ink = Ink.entries.first()
        repeat(Ink.entries.size) { ink = ink.next() }
        assertEquals(Ink.entries.first(), ink)
    }

    /**
     * White for the dark half of a halftone, black for the light half, grey for either — which is
     * why grey is the one that is useful over a photograph rather than a third colour for its own
     * sake.
     */
    @Test
    fun `there is an ink for each kind of ground`() {
        assertEquals(listOf(Ink.White, Ink.Grey, Ink.Black), Ink.entries.toList())
    }

    @Test
    fun `every ink has a label short enough for a key`() {
        Ink.entries.forEach {
            assertTrue("${it.name} label is too long", it.label.length <= 8)
            assertTrue("${it.name} has no label", it.label.isNotBlank())
        }
    }

    // ------------------------------------------------------------------- filters

    @Test
    fun `brightening lifts and darkening lowers`() {
        assertTrue(LabelFilter.Bright.apply(120) > 120)
        assertTrue(LabelFilter.Dark.apply(120) < 120)
        assertEquals(120, LabelFilter.Normal.apply(120))
    }

    @Test
    fun `punch pushes apart and soft pulls together`() {
        assertTrue(LabelFilter.Punch.apply(200) > 200)
        assertTrue(LabelFilter.Punch.apply(50) < 50)
        assertTrue(LabelFilter.Soft.apply(200) < 200)
        assertTrue(LabelFilter.Soft.apply(50) > 50)
    }

    @Test
    fun `invert turns it over`() {
        assertEquals(255, LabelFilter.Invert.apply(0))
        assertEquals(0, LabelFilter.Invert.apply(255))
    }

    /** Every filter has to stay inside a byte, or the halftone threshold compares nonsense. */
    @Test
    fun `no filter can produce a value outside a byte`() {
        LabelFilter.entries.forEach { filter ->
            for (g in 0..255) {
                val out = filter.apply(g)
                assertTrue("${filter.name} produced $out from $g", out in 0..255)
            }
        }
    }
}
