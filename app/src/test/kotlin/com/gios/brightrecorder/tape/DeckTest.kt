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

        deck.endWind()
        assertEquals(Transport.Playing, deck.transport)
    }

    @Test
    fun `rewinding to the front from stopped stays stopped`() {
        val deck = Deck()
        deck.beginWind(back = true)
        deck.ranOff(atStart = true)
        assertEquals(Transport.Stopped, deck.transport)
        deck.endWind()
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
        deck.endWind()
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

    /**
     * Fast-forward, named separately because it is what was reported and because the two keys took
     * different paths through the old code.
     */
    @Test
    fun `fast-forwarding while playing and letting go carries on playing`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = false)
        assertEquals(Transport.FastForwarding, deck.transport)
        deck.endWind()
        assertEquals(Transport.Playing, deck.transport)
    }

    /**
     * The race the engine's copy of the transport used to lose.
     *
     * Letting go at the front of the tape has two things happening at once: the audio thread
     * reporting that the head has run out of tape, and the finger coming up. Whichever order they
     * arrive in, the answer has to be the same — and it has to be "playing", because that is what
     * the rewind interrupted.
     */
    @Test
    fun `the end being reported after the release does not undo the resume`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.endWind()
        assertEquals(Transport.Playing, deck.transport)
        // The audio thread catches up a moment later.
        deck.ranOff(atStart = true)
        assertEquals(Transport.Playing, deck.transport)
    }

    @Test
    fun `the end being reported before the release does not undo the resume either`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.ranOff(atStart = true)
        deck.endWind()
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

    /**
     * The reported fault, at the other end. Winding forward off the end parks the reels — and lets
     * go into playing, because that is what the tape was doing when the key went down. It used to
     * cancel the resume here, and a wind at 8x reaches an end almost every time it is used.
     */
    @Test
    fun `winding forward off the end parks the reels and still goes back to playing`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = false)
        deck.ranOff(atStart = false)
        assertEquals(Transport.Stopped, deck.transport)
        assertTrue("the key is still down, so the resume must survive", deck.isWinding)
        deck.endWind()
        assertEquals(Transport.Playing, deck.transport)
    }

    @Test
    fun `winding off an end from stopped still ends up stopped`() {
        val deck = Deck()
        deck.beginWind(back = false)
        deck.ranOff(atStart = false)
        deck.endWind()
        assertEquals(Transport.Stopped, deck.transport)
    }

    /**
     * Even at the very end. The tape has run out, so playing from there stops again immediately —
     * which is what a tape that has run out does, and is one fewer exception to be wrong about.
     */
    @Test
    fun `letting go of a wind at the very end still goes back to playing`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = false)
        deck.endWind()
        assertEquals(Transport.Playing, deck.transport)
    }

    // ---------------------------------------------------------------- ordinary winding

    @Test
    fun `winding from play in the middle of the tape and letting go carries on playing`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.endWind()
        assertEquals(Transport.Playing, deck.transport)
    }

    @Test
    fun `winding from stopped and letting go stays stopped`() {
        val deck = Deck()
        deck.beginWind(back = false)
        assertEquals(Transport.FastForwarding, deck.transport)
        deck.endWind()
        assertEquals(Transport.Stopped, deck.transport)
    }

    @Test
    fun `changing direction mid-wind keeps the original resume`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.beginWind(back = false)
        assertEquals(Transport.FastForwarding, deck.transport)
        deck.endWind()
        assertEquals(Transport.Playing, deck.transport)
    }

    @Test
    fun `letting go twice does not resurrect the wind`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        deck.endWind()
        deck.stop()
        deck.endWind()
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
        deck.endWind()
        assertEquals(Transport.Stopped, deck.transport)
    }

    @Test
    fun `a wind never resumes into a recording`() {
        val deck = Deck()
        deck.record()
        deck.beginWind(back = true)
        deck.endWind()
        assertEquals(Transport.Stopped, deck.transport)
    }

    @Test
    fun `winding reports itself as winding and playing does not`() {
        val deck = Deck()
        deck.play()
        assertFalse(deck.isWinding)
        deck.beginWind(back = true)
        assertTrue(deck.isWinding)
        deck.endWind()
        assertFalse(deck.isWinding)
    }
}

/** The gears a wind runs in, and what the machine says it is about to do. */
class DeckSpeedTest {

    @Test
    fun `a wind starts at eight times`() {
        val deck = Deck()
        deck.beginWind(back = true)
        assertEquals(8f, deck.windSpeed, 0.01f)
    }

    /** Tap the same key again and it steps up: 8, 16, 32. */
    @Test
    fun `tapping again steps up a gear`() {
        val deck = Deck()
        deck.beginWind(back = true)
        deck.endWind()
        deck.beginWind(back = true, step = true)
        assertEquals(16f, deck.windSpeed, 0.01f)
        deck.endWind()
        deck.beginWind(back = true, step = true)
        assertEquals(32f, deck.windSpeed, 0.01f)
    }

    @Test
    fun `the top gear is the top`() {
        val deck = Deck()
        repeat(6) {
            deck.beginWind(back = true, step = it > 0)
            deck.endWind()
        }
        deck.beginWind(back = true, step = true)
        assertEquals(32f, deck.windSpeed, 0.01f)
    }

    /** A press that is not a second tap starts again at the bottom. */
    @Test
    fun `a fresh press drops back to eight times`() {
        val deck = Deck()
        deck.beginWind(back = true)
        deck.endWind()
        deck.beginWind(back = true, step = true)
        assertEquals(16f, deck.windSpeed, 0.01f)
        deck.endWind()
        deck.beginWind(back = true, step = false)
        assertEquals(8f, deck.windSpeed, 0.01f)
    }

    /**
     * Letting go does *not* put the gear back, and must not: stepping up is tap, tap, tap, so the
     * gear has to survive the gap between two taps — and letting go is that gap. Resetting here is
     * what stopped 32x from ever being reachable.
     */
    @Test
    fun `letting go keeps the gear for the next tap`() {
        val deck = Deck()
        deck.beginWind(back = false)
        deck.endWind()
        deck.beginWind(back = false, step = true)
        assertEquals(16f, deck.windSpeed, 0.01f)
        deck.endWind()
        assertEquals(16f, deck.windSpeed, 0.01f)
    }

    // ------------------------------------------------------ what it says it will do

    @Test
    fun `nothing is said when no key is held`() {
        assertEquals("", Deck().resumeLabel)
    }

    @Test
    fun `winding from play says it will play`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = true)
        assertEquals("PLAY", deck.resumeLabel)
    }

    @Test
    fun `winding from stopped says it will stop`() {
        val deck = Deck()
        deck.beginWind(back = true)
        assertEquals("STOP", deck.resumeLabel)
    }

    /** Still PLAY after the reels have parked against an end, because letting go still plays. */
    @Test
    fun `it still says play after the tape has run out under the key`() {
        val deck = Deck()
        deck.play()
        deck.beginWind(back = false)
        deck.ranOff(atStart = false)
        assertEquals("PLAY", deck.resumeLabel)
    }
}
