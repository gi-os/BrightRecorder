package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The record gain, which has one job that must not go wrong: make quiet recordings louder
 * without ruining loud ones.
 *
 * Both halves matter and they pull against each other, so both are pinned. A gain that fails to
 * lift a quiet room leaves the app doing the thing it was reported for; a gain that clips a loud
 * one bakes distortion into a recording that cannot be made again.
 */
class GainTest {

    /** A sine at [amp] of full scale, as the shorts the microphone hands over. */
    private fun tone(amp: Float, n: Int = 8192, period: Double = 120.0): ShortArray =
        ShortArray(n) { i -> (sin(2 * PI * i / period) * amp * 32767).toInt().toShort() }

    private fun peakOf(buf: ShortArray): Float =
        buf.maxOf { abs(it.toInt()) } / 32768f

    private fun rmsOf(buf: ShortArray): Double {
        var acc = 0.0
        for (s in buf) acc += (s / 32768.0) * (s / 32768.0)
        return kotlin.math.sqrt(acc / buf.size)
    }

    @Test
    fun `a quiet room comes up substantially`() {
        // The complaint this exists for: a room recorded at a few percent of full scale is
        // technically correct and useless on a phone speaker.
        val quiet = tone(0.05f)
        val before = rmsOf(quiet)
        RecordGain().apply(quiet, quiet.size)
        val after = rmsOf(quiet)
        val lift = after / before
        assertTrue("expected roughly the full makeup on quiet material, got ${lift}x", lift > 3.5)
    }

    @Test
    fun `a loud source is held below full scale instead of clipping`() {
        // Full-scale in, times four, would be four times over. Nothing may reach the rails.
        val loud = tone(0.95f)
        RecordGain().apply(loud, loud.size)
        val peak = peakOf(loud)
        assertTrue("peak reached $peak — that is clipping", peak < 0.999f)
        // And it must still be loud; a limiter that solves clipping by turning everything down
        // to a whisper has just moved the original complaint somewhere else.
        assertTrue("loud material came out at only $peak", peak > 0.6f)
    }

    @Test
    fun `nothing clips at any input level`() {
        for (amp in listOf(0.01f, 0.05f, 0.2f, 0.5f, 0.8f, 1.0f)) {
            val buf = tone(amp)
            RecordGain().apply(buf, buf.size)
            val peak = peakOf(buf)
            assertTrue("amp $amp produced peak $peak", peak <= 1.0f)
            // A run of samples pinned to the rail is what clipping looks like in a file.
            val railed = buf.count { abs(it.toInt()) >= 32760 }
            assertTrue("amp $amp pinned $railed samples to the rail", railed == 0)
        }
    }

    @Test
    fun `silence stays silent`() {
        // Gain on an empty room must not invent a noise floor of its own.
        val silence = ShortArray(4096)
        RecordGain().apply(silence, silence.size)
        assertEquals(0, silence.count { it != 0.toShort() })
    }

    @Test
    fun `a quiet passage after a loud one recovers its level`() {
        // The release. A door slamming at the start of a clip must not leave the next minute
        // sounding ducked -- that is the failure mode people describe as "it went quiet".
        val gain = RecordGain()
        val bang = tone(0.95f, n = 2048)
        gain.apply(bang, bang.size)

        // Two seconds of quiet after it, then measure only the tail.
        val quiet = tone(0.05f, n = SAMPLE_RATE * 2)
        gain.apply(quiet, quiet.size)
        val tail = quiet.copyOfRange(quiet.size - 8192, quiet.size)

        val fresh = tone(0.05f, n = 8192).also { RecordGain().apply(it, it.size) }
        val ratio = rmsOf(tail) / rmsOf(fresh)
        assertTrue("still ducked ${ratio}x after two seconds", ratio > 0.9)
    }

    @Test
    fun `the limiter does not pump on dense transients`() {
        // Heavy rain is the material that exposed this in BrightNoise: a fast release chases
        // every drop and the noise floor audibly breathes. Measured as the spread of block RMS.
        val gain = RecordGain()
        val rain = ShortArray(SAMPLE_RATE) { i ->
            val spike = if (i % 37 == 0) 0.9f else 0.06f
            (spike * 32767 * (if (i % 2 == 0) 1 else -1)).toInt().toShort()
        }
        gain.apply(rain, rain.size)
        val blocks = rain.toList().chunked(2048).map { c ->
            kotlin.math.sqrt(c.sumOf { (it / 32768.0) * (it / 32768.0) } / c.size)
        }.drop(1) // the first block includes the limiter settling from unity
        val spread = blocks.max() / blocks.min()
        assertTrue("block level varied ${spread}x — that is pumping", spread < 1.35)
    }

    @Test
    fun `soft clip is a no-op in the normal range and bounded outside it`() {
        assertEquals(0f, softClip(0f), 1e-6f)
        assertTrue(abs(softClip(0.3f) - 0.3f) < 0.01f)
        for (x in listOf(-100f, -2f, -1.6f, 1.6f, 2f, 100f)) {
            assertTrue("softClip($x) = ${softClip(x)}", abs(softClip(x)) <= 1f)
        }
    }
}
