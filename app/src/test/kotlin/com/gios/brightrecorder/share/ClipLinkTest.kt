package com.gios.brightrecorder.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing a URI needs the framework, so what is tested here is the part that decides whether a
 * name arriving from another process may be looked up at all.
 */
class ClipLinkTest {

    @Test
    fun `the names the recorder actually writes are accepted`() {
        assertTrue(ClipLink.isBareName("2026-08-17 143205 Bastille, Paris.wav"))
        assertTrue(ClipLink.isBareName("2026-08-17 143205 Trip to Rome"))
        // A place whose own name ends in a period, doubled by the extension.
        assertTrue(ClipLink.isBareName("2026-08-17 143205 Washington, D.C..wav"))
    }

    @Test
    fun `a name that could leave its directory is refused`() {
        assertFalse(ClipLink.isBareName(".."))
        assertFalse(ClipLink.isBareName(" .. "))
        assertFalse(ClipLink.isBareName("."))
        assertFalse(ClipLink.isBareName("../../databases/notes.db"))
        assertFalse(ClipLink.isBareName("tapes/clip.wav"))
        assertFalse(ClipLink.isBareName("tapes" + Char(92) + "clip.wav"))
        assertFalse(ClipLink.isBareName("clip" + Char(0) + ".wav"))
    }

    @Test
    fun `nothing is not a name`() {
        assertFalse(ClipLink.isBareName(""))
        assertFalse(ClipLink.isBareName("   "))
    }
}
