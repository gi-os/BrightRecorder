package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the tape does when you let go of a wind.
 *
 * This is the behaviour people mean by "like a tape recorder": hold rewind, let go, and it
 * carries on playing. Every case below is one where getting it wrong leaves the machine either
 * stuck winding or silently stopped after a wind, and neither is visible in code review — they
 * only show up as "it stopped playing" with the phone in your hand.
 */
class WindLatchTest {

    @Test
    fun `winding from play and letting go carries on playing`() {
        val w = WindLatch()
        assertEquals(Transport.Rewinding, w.begin(Transport.Playing, Transport.Rewinding))
        assertTrue(w.isWinding)
        assertEquals(Transport.Playing, w.end())
        assertFalse(w.isWinding)
    }

    @Test
    fun `winding from stopped and letting go stays stopped`() {
        val w = WindLatch()
        w.begin(Transport.Stopped, Transport.FastForwarding)
        assertEquals(Transport.Stopped, w.end())
    }

    @Test
    fun `only the first begin of a run captures what to resume`() {
        // The wheel calls begin on every notch. If the second one recorded "rewinding" as the
        // thing to go back to, letting go would resume into a wind and the tape would never come
        // out of it — which is the bug this re-entrancy exists to prevent.
        val w = WindLatch()
        w.begin(Transport.Playing, Transport.Rewinding)
        repeat(20) { w.begin(Transport.Rewinding, Transport.Rewinding) }
        assertEquals(Transport.Playing, w.end())
    }

    @Test
    fun `changing direction mid-wind keeps the original resume`() {
        // Turning the wheel back and then forward without pausing is one gesture, and it should
        // still hand the tape back to what it was doing before any of it started.
        val w = WindLatch()
        w.begin(Transport.Playing, Transport.Rewinding)
        assertEquals(Transport.FastForwarding, w.begin(Transport.Rewinding, Transport.FastForwarding))
        assertEquals(Transport.Playing, w.end())
    }

    @Test
    fun `a wind never resumes into another wind`() {
        val w = WindLatch()
        // Should not be reachable through the controller, but if a wind were ever begun from a
        // wind with no latch already held, resuming into it would leave the tape running away.
        w.begin(Transport.FastForwarding, Transport.Rewinding)
        assertEquals(Transport.Stopped, w.end())
    }

    @Test
    fun `a wind never resumes into recording`() {
        // A recording is filed the moment it stops; there is nothing to resume into.
        val w = WindLatch()
        w.begin(Transport.Recording, Transport.Rewinding)
        assertEquals(Transport.Stopped, w.end())
    }

    @Test
    fun `cancel forgets the wind without resuming`() {
        // Pressing record mid-wind. Without this the tape starts playing when the recording ends.
        val w = WindLatch()
        w.begin(Transport.Playing, Transport.Rewinding)
        w.cancel()
        assertFalse(w.isWinding)
        assertEquals(Transport.Stopped, w.resumeTo)
    }

    @Test
    fun `ending twice does not resurrect the wind`() {
        val w = WindLatch()
        w.begin(Transport.Playing, Transport.Rewinding)
        assertEquals(Transport.Playing, w.end())
        assertFalse(w.isWinding)
        assertEquals(Transport.Playing, w.end())
    }

    @Test
    fun `rates are signed so winding back moves the head backwards`() {
        assertTrue(Transport.Rewinding.baseRate < 0f)
        assertTrue(Transport.FastForwarding.baseRate > 0f)
        assertEquals(0f, Transport.Stopped.baseRate, 0f)
        assertEquals(1f, Transport.Playing.baseRate, 0f)
        // Nothing may speed up or reverse a recording.
        assertEquals(1f, Transport.Recording.baseRate, 0f)
    }

    @Test
    fun `winding is winding and playing is not`() {
        assertTrue(Transport.Rewinding.isWinding)
        assertTrue(Transport.FastForwarding.isWinding)
        assertFalse(Transport.Playing.isWinding)
        assertFalse(Transport.Stopped.isWinding)
        assertFalse(Transport.Recording.isWinding)
    }

    /**
     * **The shape of the fault that came back four times, as a unit test.**
     *
     * Nothing here is wrong: a stop that arrives mid-wind *should* throw the resume away. A phone
     * call takes the audio, the tape stops, and letting go of a key a second later must not start
     * it playing into the call. That is what [WindLatch.cancel] is for and it should stay.
     *
     * What this pins is the consequence, so the next person to read it knows what a cancelled
     * latch costs: after one, the release resumes **nothing**, and on screen the counter's
     * `-> PLAY` disappears while the key is still held. If that is happening without a phone call,
     * the bug is upstream -- something is stopping the tape that should not be. In 1.16 it was the
     * service re-requesting audio focus it already held on every transport change, which made the
     * system hand our own listener an AUDIOFOCUS_LOSS and stop us mid-wind.
     */
    @Test
    fun `a stop mid-wind is a wind with nothing to go back to`() {
        val latch = WindLatch()
        latch.begin(from = Transport.Playing, wind = Transport.FastForwarding)
        assertEquals(Transport.Playing, latch.resumeTo)

        // Something outside the transport takes the machine over.
        latch.cancel()

        // The readout goes blank while the key is still down, which is the visible symptom.
        assertFalse(latch.isWinding)
        // And the release has nothing to resume, which is the audible one.
        assertEquals(Transport.Stopped, latch.end())
    }
}
