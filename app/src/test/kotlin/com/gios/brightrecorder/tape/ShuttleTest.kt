package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The feel of the jog wheel, checked as arithmetic.
 *
 * How the wheel feels is not otherwise testable without a phone in a hand, but every property
 * that makes it feel mechanical rather than digital is a fact about these numbers: it builds with
 * continued spinning, it coasts down instead of stopping dead, it settles back to whatever the
 * transport was doing, and it cannot run away.
 */
class ShuttleTest {

    /** One notch every 35 ms, which is what the sensor does when spun hard. */
    private fun Shuttle.spinFor(seconds: Float, direction: Int) {
        val step = 0.035f
        var t = 0f
        while (t < seconds) {
            notch(direction)
            advance(step)
            t += step
        }
    }

    @Test
    fun `a still wheel contributes nothing`() {
        val s = Shuttle()
        assertEquals(0f, s.spin, 0f)
        assertFalse(s.isShuttling)
        assertEquals(0f, s.rate(Transport.Stopped), 0f)
        assertEquals(1f, s.rate(Transport.Playing), 0f)
    }

    @Test
    fun `a sustained spin builds to a useful winding speed`() {
        val s = Shuttle()
        s.spinFor(0.5f, +1)
        // Fast enough to cross a long clip by hand, and short of where the resampler's own
        // artefacts take over. If this drifts far from 5x the wheel has changed character.
        assertTrue("expected a hard spin to reach ~5x, got ${s.spin}", s.spin > 3.5f)
        assertTrue("expected a hard spin to stay under the ceiling, got ${s.spin}", s.spin < 6.1f)
    }

    @Test
    fun `a gentle spin stays slow enough to hear detail`() {
        val s = Shuttle()
        // A notch every 125 ms — an unhurried thumb looking for a word.
        var t = 0f
        while (t < 1f) {
            s.notch(+1)
            s.advance(0.125f)
            t += 0.125f
        }
        assertTrue("expected a slow spin to stay near 1x, got ${s.spin}", s.spin < 2f)
        assertTrue(s.spin > 0.2f)
    }

    @Test
    fun `spinning back drives the tape backwards`() {
        val s = Shuttle()
        s.spinFor(0.3f, -1)
        assertTrue("expected reverse, got ${s.spin}", s.spin < -1f)
        // Backwards past a stopped transport is exactly how scrubbing back through a clip works.
        assertTrue(s.rate(Transport.Stopped) < 0f)
    }

    @Test
    fun `the wheel coasts down rather than stopping dead`() {
        val s = Shuttle()
        s.spinFor(0.5f, +1)
        val hard = s.spin

        // A single frame later it is still moving: that coast is what gives the tape mass.
        s.advance(0.016f)
        assertTrue(s.spin > hard * 0.85f)
        assertTrue(s.spin < hard)

        // Half a second later it is down to a crawl but has not stopped — the tail of the coast is
        // the part you feel, so it is deliberately still moving here rather than snapped to zero.
        s.advance(0.5f)
        assertTrue(s.spin > 0f)
        assertTrue("still at ${s.spin} after half a second", s.spin < hard * 0.15f)

        // A second after that the dead zone takes it, and the tape is properly still.
        s.advance(1f)
        assertEquals(0f, s.spin, 0f)
        assertFalse(s.isShuttling)
    }

    @Test
    fun `the wheel adds to the transport and then gives it back`() {
        val s = Shuttle()
        s.spinFor(0.2f, +1)
        // Nudging the wheel while playing shoves the tape along…
        assertTrue(s.rate(Transport.Playing) > 1.5f)
        s.advance(1.5f)
        // …and it returns to 1x on its own, with no state to reset.
        assertEquals(1f, s.rate(Transport.Playing), 0.001f)
    }

    @Test
    fun `holding the wheel cannot bank up speed it would take seconds to shed`() {
        val s = Shuttle()
        // Ten seconds of hard spinning. Without the clamp on the way in, the spin would keep
        // integrating and the tape would then take a second to come back under control.
        s.spinFor(10f, +1)
        assertTrue("spin ran away to ${s.spin}", abs(s.spin) <= 6f)
        // Ten seconds of winding still comes to rest in the same second and a half a short spin
        // does, which is the property that matters: the coast is bounded, not proportional.
        s.advance(1.5f)
        assertEquals(0f, s.spin, 0f)
    }

    @Test
    fun `the total rate is capped even when winding and spinning together`() {
        val s = Shuttle()
        s.spinFor(2f, +1)
        assertTrue(s.rate(Transport.FastForwarding) <= 10f)
        s.still()
        s.spinFor(2f, -1)
        assertTrue(s.rate(Transport.Rewinding) >= -10f)
    }

    @Test
    fun `recording always runs the tape forward at exactly one`() {
        // Nothing may speed up or reverse a recording, whatever the wheel is doing.
        assertEquals(1f, Transport.Recording.baseRate, 0f)
    }

    @Test
    fun `still stops the wheel outright`() {
        val s = Shuttle()
        s.spinFor(0.4f, +1)
        assertTrue(s.isShuttling)
        s.still()
        assertFalse(s.isShuttling)
        assertEquals(0f, s.spin, 0f)
    }
}
