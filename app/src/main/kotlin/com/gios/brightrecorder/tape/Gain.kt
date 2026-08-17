package com.gios.brightrecorder.tape

import kotlin.math.exp

/**
 * Makeup gain on the way to disk, with a limiter under it.
 *
 * The microphone is opened as `UNPROCESSED`, which is the right source for recording a room —
 * it turns off the noise suppression, the high-pass and, crucially, the automatic gain control
 * that would otherwise be fighting the recording. The cost is that nothing is making the
 * recording loud any more, so a quiet room comes back honest and far too quiet to listen to on
 * a phone speaker. That is what this fixes, and it fixes it on the record path rather than the
 * playback path so a clip copied off the phone is loud too.
 *
 * ### Why a limiter and not just multiplication
 *
 * A flat multiply loud enough to lift a quiet room is far too much for a passing motorbike, and
 * once samples clip they are clipped in the file for good — there is no undo on a recording that
 * cannot be made again. So the makeup is generous and a look-ahead limiter sits after it,
 * holding peaks at [Limiter.threshold] instead of letting them square off. Quiet material gets
 * the whole [MAKEUP]; loud material gets whatever fits, smoothly.
 *
 * This is BrightNoise's limiter, unchanged apart from the comment. It was already debugged there
 * against exactly the material that breaks a naive one — dense transient streams like heavy
 * rain, where a fast release pumps audibly.
 */
class RecordGain(private val makeup: Float = MAKEUP) {

    private val limiter = Limiter()

    /**
     * Amplify [count] samples of [buf] in place, and return the peak of the result in 0..1.
     *
     * The peak is measured *after* limiting, so the level meter shows what is being written
     * rather than what the microphone sent — which is what you want when you are holding the
     * phone and deciding whether to move closer.
     */
    fun apply(buf: ShortArray, count: Int): Float {
        var peak = 0f
        for (i in 0 until count) {
            val raw = buf[i] / 32768f
            val out = softClip(limiter.process(raw * makeup))
            val mag = if (out < 0f) -out else out
            if (mag > peak) peak = mag
            // 32767 rather than 32768: -1.0 has a representation as a short, +1.0 does not.
            buf[i] = (out * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return peak
    }

    companion object {
        /**
         * How much louder, before limiting. 4x is +12 dB.
         *
         * This is the one number to turn if recordings still come back too quiet — everything
         * else here is protection against turning it too far. It is deliberately applied ahead
         * of the limiter, so raising it makes quiet recordings louder and does progressively
         * less to loud ones rather than distorting them.
         */
        const val MAKEUP = 4f
    }
}

/**
 * Cubic soft clip. Rounds off whatever the limiter's attack let through instead of squaring it.
 *
 * The limiter should mean this almost never does anything; it is here because "almost never" on
 * a recording that cannot be repeated is not good enough.
 */
fun softClip(x: Float): Float = when {
    x > 1.6f -> 1f
    x < -1.6f -> -1f
    else -> x - x * x * x * 0.13f
}.coerceIn(-1f, 1f)

/**
 * Look-ahead peak limiter.
 *
 * Ported from BrightNoise, where the comments below were earned. Note the [delay] line means the
 * first few dozen samples out are silence — about two milliseconds at this sample rate, at the
 * very head of a clip, which is inaudible and is the price of the gain having settled before the
 * peak that caused it arrives.
 */
class Limiter(
    private val threshold: Float = 0.90f,
    lookaheadSec: Float = 0.0022f,
    releaseSec: Float = 0.18f,
) {
    private val delay = FloatArray((lookaheadSec * SAMPLE_RATE).toInt().coerceAtLeast(8))
    private var writeIndex = 0

    // Reaches ~99 % of target within the look-ahead window, so the gain has settled by
    // the time the peak that caused it reaches the output.
    private val attack = 1f - exp(-5.0 / delay.size).toFloat()
    private val release = 1f - exp(-1.0 / (releaseSec * SAMPLE_RATE)).toFloat()

    // Peak follower on the input, held for the length of the delay line. The hold is
    // load-bearing: without it the follower starts decaying the instant the peak
    // passes, the gain target rises again, and the peak arrives at the output against a
    // gain that has already begun recovering. Measured 1.076 against a 0.9 ceiling.
    private val envRelease = 1f - exp(-1.0 / (0.015f * SAMPLE_RATE)).toFloat()
    private var env = 0f
    private var hold = 0
    private var gain = 1f

    /** Current gain reduction, for tests and diagnostics. */
    val currentGain: Float get() = gain

    fun process(x: Float): Float {
        val mag = if (x < 0f) -x else x
        if (mag >= env) {
            env = mag
            hold = delay.size
        } else if (hold > 0) {
            hold--
        } else {
            env += (mag - env) * envRelease
        }

        val target = if (env > threshold) threshold / env else 1f
        // Clamp down quickly, recover slowly — otherwise the release pumps audibly
        // against a dense transient stream like heavy rain.
        gain += (target - gain) * if (target < gain) attack else release

        val delayed = delay[writeIndex]
        delay[writeIndex] = x
        writeIndex = (writeIndex + 1) % delay.size
        return delayed * gain
    }
}
