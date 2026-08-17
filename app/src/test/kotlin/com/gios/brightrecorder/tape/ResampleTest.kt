package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The resampler, which is the one part of this app whose bugs are inaudible until they are
 * unbearable.
 *
 * A drift of one sample per block is silent for a minute and a rising whine after ten. Aliasing
 * only appears when winding fast through bright material. Neither is visible in code review and
 * neither reproduces reliably by hand, so they are pinned here instead.
 */
class ResampleTest {

    /** A ramp, so that any position error shows up as a value error of the same size. */
    private val ramp: (Long) -> Float = { it.toFloat() }

    @Test
    fun `at normal speed a whole position reads the sample itself`() {
        assertEquals(7f, Resample.at(7.0, 1f, ramp), 0f)
    }

    @Test
    fun `a fractional position interpolates between neighbours`() {
        assertEquals(7.5f, Resample.at(7.5, 1f, ramp), 1e-4f)
        assertEquals(7.25f, Resample.at(7.25, 0.5f, ramp), 1e-4f)
    }

    @Test
    fun `slow playback does not produce a staircase`() {
        // At 0.25x four consecutive outputs come from between the same two stored samples. If they
        // all came back identical the waveform would be stepped, which is audible as a buzz.
        val values = (0 until 4).map { Resample.at(10.0 + it * 0.25, 0.25f, ramp) }
        assertEquals(values.toString(), 4, values.distinct().size)
        assertTrue(values.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `reading backwards mirrors reading forwards`() {
        // Reverse is not a special case in the engine — it is a negative rate — so the only thing
        // that has to hold is that a negative rate reads the same material.
        val forward = Resample.at(100.0, 1f, ramp)
        val backward = Resample.at(100.0, -1f, ramp)
        assertEquals(forward, backward, 1e-4f)
    }

    @Test
    fun `winding fast averages instead of picking one sample`() {
        // A constant signal must stay constant whatever the speed: if the averaging window ran off
        // in the wrong direction or divided by the wrong count, this is where it shows.
        val flat: (Long) -> Float = { 0.5f }
        for (rate in listOf(1f, 2f, 4f, 8f, -4f)) {
            assertEquals("rate $rate", 0.5f, Resample.at(50.0, rate, flat), 1e-4f)
        }
    }

    @Test
    fun `winding fast suppresses the aliasing that skipping would cause`() {
        // The Nyquist worst case: a signal alternating every sample. Sampled at 4x by picking one
        // in four, this comes back as a full-amplitude tone that is not in the recording. Averaged
        // across the span, it very nearly cancels — which is the whole reason the averaging exists.
        val nyquist: (Long) -> Float = { if (it % 2 == 0L) 1f else -1f }
        var worst = 0f
        var p = 200.0
        repeat(64) {
            worst = maxOf(worst, abs(Resample.at(p, 4f, nyquist)))
            p += 4f
        }
        assertTrue("aliased content survived at $worst", worst < 0.35f)
    }

    @Test
    fun `a tone keeps its shape when played at speed`() {
        // A slow sine is well below Nyquist, so speeding it up must not flatten it — an
        // over-aggressive average would remove the programme along with the aliasing.
        val period = 400.0
        val tone: (Long) -> Float = { sin(2 * PI * it / period).toFloat() }
        var peak = 0f
        var p = 0.0
        while (p < period * 4) {
            peak = maxOf(peak, abs(Resample.at(p, 4f, tone)))
            p += 4.0
        }
        assertTrue("tone was flattened to $peak", peak > 0.9f)
    }

    @Test
    fun `an extreme rate does not turn into thousands of reads`() {
        // The tap ceiling. A rate arriving from somewhere unexpected must not stall the audio
        // thread, and the cheapest proof is counting the reads.
        var reads = 0
        val counted: (Long) -> Float = { reads++; 0f }
        Resample.at(0.0, 5_000f, counted)
        assertTrue("made $reads reads", reads <= 64)
    }
}
