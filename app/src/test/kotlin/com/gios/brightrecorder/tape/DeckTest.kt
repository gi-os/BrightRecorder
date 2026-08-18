package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTest {

    // ------------------------------------------------------------ the reported fault

    /**
     * The bug, three releases running.
     *
     * A moment is a few seconds long, and rewind runs at 4x, so rewinding while playing reaches
     * the front of the tape almost every time you do it. Reaching the front used to cancel the
     * wind *and* its resume, so letting go left the tape stopped at zero — the fault as reported:
     * "the rewind feature when playing does not continue to play like a tape does".
     */
    @Test
    fun `rewinding to the front of the tape and letting go carries on playing`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        assertEquals(Transport.Rewinding, deck.transport)

        deck.ranOff(atStart = true)
        // The reels have stopped against the wall — but the key is still down, so the tape does
        // not start moving again under the finger holding it.
        assertEquals(Transport.Stopped, deck.transport)

        deck.endWind(atEnd = false)
        assertEquals(Transport.Playing, deck.transport)
    }

    @Test
    fun `rewinding to the front from stopped stays stopped`() {
        val deck = Deck()
        deck.beginWind(back = true)
        deck.ranOff(atStart = true)
        assertEquals(Transport.Stopped, deck.transport)
        deck.endWind(atEnd = false)
        assertEquals(Transport.Stopped, deck.transport)
    }

    /** The audio thread cannot know it has already reported the wall, so it may report it twice. */
    @Test
    fun `reaching the front twice still leaves the resume armed`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.ranOff(atStart = true)
        deck.ranOff(atStart = true)
        assertTrue(deck.isWinding)
        deck.endWind(atEnd = false)
        assertEquals(Transport.Playing, deck.transport)
    }

    /** Scrubbing back into the wall: the wheel never touched the transport, so nothing changes. */
    @Test
    fun `scrubbing to the front while playing keeps playing`() {
        val deck = Deck()
        deck.play()
        deck.ranOff(atStart = true)
        assertEquals(Transport.Playing, deck.transport)
    }

    // ------------------------------------------------------------------ the other end

    @Test
    fun `playing to the end of the tape stops`() {
        val deck = Deck()
        deck.play()
        deck.ranOff(atStart = false)
        assertEquals(Transport.Stopped, deck.transport)
    }

    @Test
    fun `winding forward off the end stops and forgets the resume`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = false)
        deck.ranOff(atStart = false)
        assertEquals(Transport.Stopped, deck.transport)
        deck.endWind(atEnd = true)
        assertEquals(Transport.Stopped, deck.transport)
    }

    /** Letting go at the very end would start and instantly stop again, which reads as a dead key. */
    @Test
    fun `letting go of a wind at the very end does not resume into play`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = false)
        deck.endWind(atEnd = true)
        assertEquals(Transport.Stopped, deck.transport)
    }

    // ---------------------------------------------------------------- ordinary winding

    @Test
    fun `winding from play in the middle of the tape and letting go carries on playing`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.endWind(atEnd = false)
        assertEquals(Transport.Playing, deck.transport)
    }

    @Test
    fun `winding from stopped and letting go stays stopped`() {
        val deck = Deck()
        deck.beginWind(back = false)
        assertEquals(Transport.FastForwarding, deck.transport)
        deck.endWind(atEnd = false)
        assertEquals(Transport.Stopped, deck.transport)
    }

    @Test
    fun `changing direction mid-wind keeps the original resume`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.beginWind(back = false)
        assertEquals(Transport.FastForwarding, deck.transport)
        deck.endWind(atEnd = false)
        assertEquals(Transport.Playing, deck.transport)
    }

    @Test
    fun `letting go twice does not resurrect the wind`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.endWind(atEnd = false)
        deck.stop()
        deck.endWind(atEnd = false)
        assertEquals(Transport.Stopped, deck.transport)
    }

    // -------------------------------------------------------------------- recording

    @Test
    fun `recording mid-wind is not resumed out of`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.record()
        assertEquals(Transport.Recording, deck.transport)
        assertFalse(deck.isWinding)
        deck.finishedRecording()
        assertEquals(Transport.Stopped, deck.transport)
        deck.endWind(atEnd = false)
        assertEquals(Transport.Stopped, deck.transport)
    }

    @Test
    fun `a wind never resumes into a recording`() {
        val deck = Deck()
        deck.record()
        deck.beginWind(back = true)
        deck.endWind(atEnd = false)
        assertEquals(Transport.Stopped, deck.transport)
    }

    @Test
    fun `winding reports itself as winding and playing does not`() {
        val deck = Deck()
        deck.play()
        assertFalse(deck.isWinding)
        deck.beginWind(back = true)
        assertTrue(deck.isWinding)
        deck.endWind(atEnd = false)
        assertFalse(deck.isWinding)
    }
}
