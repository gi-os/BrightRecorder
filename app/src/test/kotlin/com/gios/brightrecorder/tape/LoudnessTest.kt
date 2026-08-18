package com.gios.brightrecorder.tape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessTest {

    private fun sine(hz: Float, amplitude: Float, seconds: Float): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        return FloatArray(n) { i -> amplitude * sin(2.0 * PI * hz * i / SAMPLE_RATE).toFloat() }
    }

    private fun measure(vararg parts: FloatArray): Float? {
        val l = Loudness()
        parts.forEach { part -> part.forEach { l.add(it) } }
        return l.lufs()
    }

    // ------------------------------------------------------------------ the filters

    /**
     * The spec publishes K-weighting coefficients for 48 kHz only, and this app runs at 22050, so
     * they are recomputed from the analog prototype. This is what says the recomputation is right:
     * asked for 48 kHz it must reproduce the published numbers, which it can only do by having the
     * derivation — and the spec's slightly odd shelf exponent — exactly right.
     */
    @Test
    fun `the shelf derivation reproduces the spec's published 48 kHz coefficients`() {
        val ours = Biquad.highShelf(1681.974450955533f, 3.999843853973347f, 0.7071752369554196f, 48000)
        val spec = reference(
            b0 = 1.53512485958697, b1 = -2.69169618940638, b2 = 1.19839281085285,
            a1 = -1.69065929318241, a2 = 0.73248077421585,
        )
        assertImpulseResponsesMatch(ours, spec)
    }

    @Test
    fun `the high-pass derivation reproduces the spec's published 48 kHz coefficients`() {
        val ours = Biquad.highPass(38.13547087602444f, 0.5003270373238773f, 48000)
        val spec = reference(
            b0 = 1.0, b1 = -2.0, b2 = 1.0,
            a1 = -1.99004745483398, a2 = 0.99007225036621,
        )
        assertImpulseResponsesMatch(ours, spec)
    }

    // -------------------------------------------------------------------- the scale

    /**
     * The spec's own calibration: a 997 Hz sine at −20 dBFS reads −23 LUFS.
     *
     * Quoted for one channel of a stereo pair; a lone mono channel is the same figure, which is why
     * this is the tolerance-free anchor for the whole measurement. The 0.1 allows for the K-weight
     * curve landing fractionally differently at 22050 Hz than at the 48 kHz the figure was quoted
     * for — it comes out at −22.98.
     */
    @Test
    fun `a minus twenty dBFS sine reads minus twenty three LUFS`() {
        val lufs = measure(sine(997f, 0.1f, 3f))!!
        assertEquals(-23.0, lufs.toDouble(), 0.1)
    }

    @Test
    fun `doubling the amplitude adds six decibels`() {
        val quiet = measure(sine(997f, 0.05f, 3f))!!
        val loud = measure(sine(997f, 0.1f, 3f))!!
        assertEquals(6.02, (loud - quiet).toDouble(), 0.02)
    }

    @Test
    fun `silence has no loudness rather than a very small one`() {
        assertNull(measure(FloatArray((3f * SAMPLE_RATE).toInt())))
    }

    @Test
    fun `a clip shorter than one gating block has no loudness`() {
        assertNull(measure(sine(997f, 0.5f, 0.2f)))
    }

    @Test
    fun `a clip exactly one gating block long is measurable`() {
        assertNotNull(measure(sine(997f, 0.5f, 0.45f)))
    }

    // --------------------------------------------------------------------- the gates

    /**
     * The reason gating is implemented rather than approximated. A moment is mostly the room
     * between the things in it, and averaging that in would report the clip as far quieter than it
     * sounds — which is then corrected by turning the noise floor up into a wall.
     */
    @Test
    fun `silence between things does not count towards how loud a clip is`() {
        val tone = sine(997f, 0.1f, 2f)
        val quiet = FloatArray((2f * SAMPLE_RATE).toInt())
        val toneOnly = measure(tone)!!
        val withSilence = measure(tone, quiet)!!
        // Ungated, half silence would cost a full 3 dB. The blocks straddling the join are real
        // and do count, which is what the remaining fraction of a decibel is.
        assertTrue(
            "gated $withSilence should be near $toneOnly, not 3 dB below it",
            abs(withSilence - toneOnly) < 1.0f,
        )
    }

    /** The relative gate: the quiet half of a recording is not what "how loud is it" means. */
    @Test
    fun `a quiet passage twenty decibels down is gated out`() {
        val loud = sine(997f, 0.1f, 2f)
        val faint = sine(997f, 0.01f, 2f)
        val loudOnly = measure(loud)!!
        assertEquals(loudOnly.toDouble(), measure(loud, faint)!!.toDouble(), 1.0)
    }

    /** And a passage only 5 LU down is part of the programme, so it does count. */
    @Test
    fun `a passage just below the loudest part still counts`() {
        val loud = sine(997f, 0.1f, 2f)
        val nearly = sine(997f, 0.056f, 2f)
        assertTrue(measure(loud, nearly)!! < measure(loud)!! - 1f)
    }

    // ---------------------------------------------------------------- K-weighting

    /** Rumble you can barely hear should not be counted as loudness. That is the high-pass. */
    @Test
    fun `low rumble counts for less than a mid tone of the same amplitude`() {
        val rumble = measure(sine(25f, 0.1f, 3f))!!
        val mid = measure(sine(997f, 0.1f, 3f))!!
        assertTrue("rumble $rumble should be well below mid $mid", rumble < mid - 10f)
    }

    /** And the shelf: the ear is most sensitive up here, so it counts for more. */
    @Test
    fun `treble counts for more than a mid tone of the same amplitude`() {
        val treble = measure(sine(6000f, 0.1f, 3f))!!
        val mid = measure(sine(997f, 0.1f, 3f))!!
        assertTrue("treble $treble should be above mid $mid", treble > mid + 2f)
    }

    // ------------------------------------------------------------------- plumbing

    @Test
    fun `feeding shorts matches feeding floats`() {
        val floats = sine(997f, 0.5f, 2f)
        val shorts = ShortArray(floats.size) { (floats[it] * 32767f).toInt().toShort() }
        val a = measure(floats)!!
        val b = Loudness().apply { add(shorts, shorts.size) }.lufs()!!
        assertEquals(a.toDouble(), b.toDouble(), 0.01)
    }

    // ------------------------------------------------------------------- helpers

    private fun interface Filter {
        fun process(x: Float): Float
    }

    /** The difference equation, straight from a set of coefficients, as something to compare to. */
    private fun reference(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): Filter {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return Filter { x ->
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
            y.toFloat()
        }
    }

    private fun assertImpulseResponsesMatch(ours: Biquad, spec: Filter) {
        for (i in 0 until 2000) {
            val x = if (i == 0) 1f else 0f
            assertEquals("sample $i", spec.process(x).toDouble(), ours.process(x).toDouble(), 1e-6)
        }
    }
}
