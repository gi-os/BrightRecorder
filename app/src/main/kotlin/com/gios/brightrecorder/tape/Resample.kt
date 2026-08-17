package com.gios.brightrecorder.tape

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Reading the tape at a speed that is not 1x.
 *
 * The engine holds a fractional position and moves it by `rate` samples per output sample.
 * Almost none of those positions land on a stored sample, so each one has to be reconstructed.
 * Two different problems, depending on which way the rate goes:
 *
 * ### Slower than 1x — interpolate
 *
 * At 0.3x the head crawls and consecutive output samples come from between the same two stored
 * ones. Taking the nearest would hold each stored sample for three outputs and turn a smooth
 * waveform into a staircase, which is audible as a buzz. Linear interpolation between the two
 * neighbours is enough here: the error it makes is a gentle high-frequency roll-off, and this
 * is a 22 kHz mono tape recording, not a mastering chain.
 *
 * ### Faster than 1x — average
 *
 * At 4x the head skips three of every four samples, and skipping is aliasing: content above a
 * quarter of the sample rate folds back down and arrives as a metallic whistle that has nothing
 * to do with the recording. Anything above 5x turns speech into a sound like a modem.
 *
 * The fix is to *average across the samples being skipped* rather than picking one — a moving
 * average is a low-pass filter, and it is placed exactly where the aliasing is created. It also
 * happens to be what the physical object did: a tape head reads a finite length of tape at once,
 * so winding at speed averages over the gap and comes out dull rather than shrill. So the honest
 * emulation and the correct signal processing are the same operation, which is a rare thing.
 *
 * Not the sharpest filter available — a windowed sinc would be — but it costs one multiply per
 * tap, runs inside a per-sample loop on a phone, and removes the artefact that matters.
 */
object Resample {

    /**
     * Ceiling on averaging taps.
     *
     * At the 10x maximum the span is ten samples, so this is never reached in normal use; it is
     * here so that a rate arriving from somewhere unexpected cannot turn the audio loop into
     * thousands of reads per output sample and stall the thread.
     */
    private const val MAX_TAPS = 32

    /** One output sample read at [position], travelling at [rate] samples per output sample. */
    fun at(position: Double, rate: Float, sample: (Long) -> Float): Float {
        val span = abs(rate)
        if (span <= 1f) return lerp(position, sample)

        val taps = ceil(span).toInt().coerceIn(2, MAX_TAPS)
        // Step across the span in the direction of travel, so the average covers the samples
        // actually being crossed rather than a window centred on where the head already was.
        val step = (rate / taps).toDouble()
        var acc = 0f
        var p = position
        repeat(taps) {
            acc += lerp(p, sample)
            p += step
        }
        return acc / taps
    }

    /** Linear interpolation between the two stored samples either side of [position]. */
    private fun lerp(position: Double, sample: (Long) -> Float): Float {
        val floor = floor(position)
        val i = floor.toLong()
        val f = (position - floor).toFloat()
        // Skip the second read when the position is effectively on a sample: at 1x exactly,
        // which is the common case, this halves the reads.
        if (f <= 0f) return sample(i)
        return sample(i) * (1f - f) + sample(i + 1) * f
    }
}
