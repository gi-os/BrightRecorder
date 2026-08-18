package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The wheel, and specifically the thing it was reported doing: flashing.
 *
 * Turning slowly used to flip the transport into rewind and back out again on every notch,
 * because the wheel drove a *mode* with a timeout rather than a rate. The rewind key blinked and
 * playback stopped and restarted under it. So the properties pinned here are: the rate is
 * continuous while turning, it follows how fast you turn, and it never reaches zero mid-turn.
 */
class ScrubTest {

    /** Turn the wheel at [gapMs] between notches for [ms], sampling the rate as the engine does. */
    private fun turn(
        scrub: Scrub,
        gapMs: Long,
        ms: Long,
        direction: Int = 1,
        startAt: Long = 1_000L,
        onSample: (Long, Float) -> Unit = { _, _ -> },
    ): Long {
        var now = startAt
        var nextNotch = startAt
        val end = startAt + ms
        while (now < end) {
            if (now >= nextNotch) {
                scrub.notch(direction, now)
                nextNotch = now + gapMs
            }
            onSample(now, scrub.rate(now))
            now += BLOCK_MS
        }
        return now
    }

    /** One audio block, which is how often the engine asks for a rate. */
    private val BLOCK_MS = 23L

    @Test
    fun `a still wheel contributes nothing`() {
        val s = Scrub()
        assertEquals(0f, s.rate(1_000L), 0f)
        assertFalse(s.isActive)
    }

    @Test
    fun `a slow steady turn never drops back to zero`() {
        // The reported bug, exactly: notches further apart than the old 350 ms timeout. Every
        // sample after the wheel has got going must be moving the tape.
        val s = Scrub()
        var zeroes = 0
        var samples = 0
        turn(s, gapMs = 400L, ms = 4_000L) { _, rate ->
            samples++
            // Skip the first turn's worth while the ramp is still coming up from rest.
            if (samples > 20 && rate == 0f) zeroes++
        }
        assertTrue("the tape stalled $zeroes times mid-turn", zeroes == 0)
    }

    @Test
    fun `turning faster moves the tape faster`() {
        fun steady(gap: Long): Float {
            val s = Scrub()
            var last = 0f
            turn(s, gapMs = gap, ms = 3_000L) { _, r -> last = r }
            return last
        }
        val slow = steady(400L)
        val medium = steady(150L)
        val fast = steady(40L)
        assertTrue("slow=$slow medium=$medium", medium > slow)
        assertTrue("medium=$medium fast=$fast", fast > medium)
        // And a slow turn really is slow — the point of the complaint was that it should crawl.
        assertTrue("a slow turn ran at ${slow}x", slow < 1.5f)
        assertTrue("a fast turn only reached ${fast}x", fast > 3f)
    }

    @Test
    fun `a turn eases in rather than lurching`() {
        // The first notch must not arrive at whatever speed the last turn ended on.
        val s = Scrub()
        s.notch(1, 1_000L)
        val first = s.rate(1_000L)
        assertTrue("first notch jumped straight to ${first}x", abs(first) < 1.5f)
    }

    @Test
    fun `the rate never exceeds the ceiling however hard it is spun`() {
        val s = Scrub()
        var worst = 0f
        turn(s, gapMs = 10L, ms = 3_000L) { _, r -> worst = maxOf(worst, abs(r)) }
        assertTrue("ran away to ${worst}x", worst <= 8.001f)
    }

    @Test
    fun `letting go returns to zero without a jump`() {
        val s = Scrub()
        var now = turn(s, gapMs = 60L, ms = 1_500L)
        val moving = s.rate(now)
        assertTrue(moving > 1f)

        // No more notches. Sample forward and watch it come down.
        var previous = moving
        var biggestStep = 0f
        repeat(80) {
            now += BLOCK_MS
            val r = s.rate(now)
            biggestStep = maxOf(biggestStep, abs(previous - r))
            previous = r
        }
        assertEquals("did not come to rest", 0f, previous, 0f)
        // Continuous: the position must not lurch, so no single block may drop the rate far.
        assertTrue("dropped ${biggestStep}x in one block", biggestStep < moving * 0.5f)
        assertFalse(s.isActive)
    }

    @Test
    fun `turning back drives the tape backwards`() {
        val s = Scrub()
        var last = 0f
        turn(s, gapMs = 60L, ms = 1_500L, direction = -1) { _, r -> last = r }
        assertTrue("expected reverse, got $last", last < -1f)
    }

    @Test
    fun `reversing direction mid-turn starts the new direction gently`() {
        // Turning back the other way is a new turn, not a continuation at the old speed.
        val s = Scrub()
        var now = turn(s, gapMs = 40L, ms = 1_500L, direction = 1)
        assertTrue(s.rate(now) > 2f)

        s.notch(-1, now)
        val afterFlip = s.rate(now)
        assertTrue("flipped straight to $afterFlip", afterFlip > -1.5f)
    }

    @Test
    fun `still drops everything at once`() {
        val s = Scrub()
        val now = turn(s, gapMs = 40L, ms = 1_000L)
        assertTrue(s.isActive)
        s.still()
        assertEquals(0f, s.rate(now), 0f)
        assertFalse(s.isActive)
    }
}
