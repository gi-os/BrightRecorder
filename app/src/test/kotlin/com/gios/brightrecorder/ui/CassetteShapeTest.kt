package com.gios.brightrecorder.ui

import com.gios.brightrecorder.label.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label window on the cassette and the label stored on disk must be the same shape.
 *
 * They were not, and it was the reported fault: the editor composed a 2.5:1 canvas while the
 * cassette drew a window of whatever shape fell out of the size it was given, so a photograph
 * filled the editor and then sat letterboxed in the middle of the label on the shelf.
 */
class CassetteShapeTest {

    @Test
    fun `the label window has the same shape as a stored label`() {
        val windowWidth = CassetteShape.LABEL_RIGHT - CassetteShape.LABEL_LEFT
        // Widths are fractions of the cassette's width and heights of its height, so comparing the
        // two goes through the cassette's own aspect.
        val windowAspect = windowWidth * CassetteShape.ASPECT / CassetteShape.LABEL_HEIGHT
        val storedAspect = Label.WIDTH.toFloat() / Label.HEIGHT
        assertEquals(storedAspect.toDouble(), windowAspect.toDouble(), 0.001)
    }

    @Test
    fun `the label window fits inside the cassette`() {
        assertTrue(CassetteShape.LABEL_LEFT > 0f)
        assertTrue(CassetteShape.LABEL_RIGHT < 1f)
        assertTrue(CassetteShape.LABEL_TOP > 0f)
        assertTrue(
            "the label runs into the reels",
            CassetteShape.LABEL_TOP + CassetteShape.LABEL_HEIGHT < 0.58f,
        )
    }

    /** A cassette is wider than it is tall, and roughly 100 by 64 millimetres. */
    @Test
    fun `the cassette is cassette-shaped`() {
        assertEquals(1.57, CassetteShape.ASPECT.toDouble(), 0.05)
    }
}
