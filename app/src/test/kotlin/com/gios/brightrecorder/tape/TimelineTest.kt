package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tape has to be continuous, and these are the boundaries where it would stop being so.
 *
 * Every one of these cases is a place where playback either clicks, stalls, or silently skips a
 * recording, and none of them is visible by reading the engine — they only show up as "it played
 * the wrong thing" on a phone, minutes into a tape.
 */
class TimelineTest {

    private fun clip(name: String, samples: Long, at: Long = 0L) =
        Clip(fileName = name, place = "Somewhere", startedAt = at, samples = samples)

    private val three = Timeline(
        listOf(
            clip("a.wav", 100),
            clip("b.wav", 50),
            clip("c.wav", 200),
        ),
    )

    @Test
    fun `total is the sum of every clip`() {
        assertEquals(350L, three.samples)
    }

    @Test
    fun `an empty tape has no length and no position`() {
        val empty = Timeline(emptyList())
        assertEquals(0L, empty.samples)
        assertNull(empty.locate(0))
    }

    @Test
    fun `the first sample of a clip belongs to that clip and not the one before`() {
        // The off-by-one that matters: sample 100 is the first sample of b, not the last of a.
        assertEquals(Spot(0, 99), three.locate(99))
        assertEquals(Spot(1, 0), three.locate(100))
        assertEquals(Spot(1, 49), three.locate(149))
        assertEquals(Spot(2, 0), three.locate(150))
    }

    @Test
    fun `the last sample is on the tape and the one after it is not`() {
        assertEquals(Spot(2, 199), three.locate(349))
        assertNull(three.locate(350))
        assertNull(three.locate(-1))
    }

    @Test
    fun `a zero length clip is stepped over rather than landed on`() {
        // A record button pressed and released instantly used to leave one of these, and the head
        // would sit on it forever: its start and end are the same sample, so every read returned
        // nothing and the position never advanced past it.
        val withEmpty = Timeline(
            listOf(clip("a.wav", 10), clip("empty.wav", 0), clip("c.wav", 10)),
        )
        assertEquals(20L, withEmpty.samples)
        assertEquals(Spot(2, 0), withEmpty.locate(10))
    }

    @Test
    fun `skipping back from mid clip goes to the start of that clip`() {
        // What every transport with a back button does, and what a thumb expects.
        assertEquals(100L, three.seekByClip(120, -1))
    }

    @Test
    fun `skipping back from the first sample of a clip goes to the previous clip`() {
        assertEquals(0L, three.seekByClip(100, -1))
    }

    @Test
    fun `skipping back at the very start stays at the start`() {
        assertEquals(0L, three.seekByClip(0, -1))
    }

    @Test
    fun `skipping forward moves to the next clip`() {
        assertEquals(150L, three.seekByClip(120, 1))
    }

    @Test
    fun `skipping forward off the last clip lands at the end of the tape`() {
        // Not the start of the last clip again, which would replay it instead of stopping.
        assertEquals(350L, three.seekByClip(200, 1))
    }
}
