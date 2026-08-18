package com.gios.brightrecorder.tape

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * How loud a clip *sounds*, to ITU-R BS.1770.
 *
 * Not a peak and not an RMS. Both of those get the answer wrong in the way that matters here: two
 * recordings of a moment can have identical peaks and be twenty decibels apart to listen to, and
 * plain RMS counts a lorry passing at 40 Hz as loudly as a voice. So this is the measurement the
 * whole industry settled on — frequency-weighted, mean-square, and *gated* so that the silence
 * between things does not drag the answer down.
 *
 * ### The three parts, and why each is there
 *
 * **K-weighting** is two biquads: a high shelf that lifts everything above about 1.7 kHz by 4 dB,
 * because that is where the ear is most sensitive, and a high-pass at about 38 Hz, because rumble
 * you cannot hear should not count as loudness. The spec publishes coefficients for 48 kHz only,
 * so they are recomputed here for whatever [SAMPLE_RATE] is — the same derivation `libebur128`
 * uses, from the analog prototype rather than by resampling the published numbers.
 *
 * **Overlapping blocks.** Mean square over 400 ms windows, a new one every 100 ms. The overlap is
 * what keeps a loud moment straddling a window boundary from being averaged into insignificance.
 *
 * **Two gates**, which are the reason this is worth implementing properly rather than
 * approximating. The absolute gate throws away blocks below −70 LUFS: digital silence, and the
 * room between two sentences. The relative gate then throws away everything more than 10 LU below
 * the average of what is left, which removes the *quiet* parts of a recording from the measure of
 * how loud it is. Without them a four-second clip with three seconds of near-silence in it reads
 * as almost silent and gets normalised up until the noise floor is a wall — which is precisely the
 * material this app records.
 *
 * ### Reading the result
 *
 * The unit is LUFS: decibels, full scale, so every value is negative and −16 is louder than −30.
 * This is a single mono channel at weight 1.0, which reads **3.01 LU lower than the same waveform
 * measured as stereo** — worth knowing before comparing a number here against a figure quoted for
 * music. See [Levels.TARGET_LUFS].
 *
 * Fed in blocks rather than all at once, because a clip is a file and reading a whole one into
 * memory to measure it would be the largest allocation in the app.
 */
class Loudness {

    private val shelf = Biquad.highShelf(SHELF_HZ, SHELF_DB, SHELF_Q, SAMPLE_RATE)
    private val highPass = Biquad.highPass(PASS_HZ, PASS_Q, SAMPLE_RATE)

    /** Samples per gating block, and the step between two of them. */
    private val blockSize = (BLOCK_SEC * SAMPLE_RATE).toInt()
    private val stepSize = (STEP_SEC * SAMPLE_RATE).toInt()

    /** Ring of squared, weighted samples, one gating block long. */
    private val squares = FloatArray(blockSize)
    private var written = 0L

    /** Running sum of [squares], so a block's mean square is a division rather than a loop. */
    private var sum = 0.0

    /** Mean square of each completed block. The gates need them all, so they are kept. */
    private val blocks = ArrayList<Double>()

    /** Feed [count] samples of [buf], as 16-bit PCM. */
    fun add(buf: ShortArray, count: Int) {
        for (i in 0 until count) add(buf[i] / 32768f)
    }

    fun add(sample: Float) {
        val weighted = highPass.process(shelf.process(sample))
        val slot = (written % blockSize).toInt()
        sum += weighted * weighted - squares[slot]
        squares[slot] = weighted * weighted
        written++
        // A block is complete every stepSize samples, once there are enough for a whole one.
        if (written >= blockSize && (written - blockSize) % stepSize == 0L) {
            blocks.add((sum / blockSize).coerceAtLeast(0.0))
        }
    }

    /**
     * The integrated loudness in LUFS, or null if there is nothing to measure.
     *
     * Null rather than a very negative number for a clip that is entirely silence: there is no
     * honest loudness for it, and a caller normalising by it would apply the maximum boost to a
     * file with nothing in it but the noise floor.
     */
    fun lufs(): Float? {
        if (blocks.isEmpty()) return null
        // Above the absolute gate: anything quieter than this is silence by definition.
        val audible = blocks.filter { loudnessOf(it) > ABSOLUTE_GATE }
        if (audible.isEmpty()) return null
        // The relative gate is set from the average of what survived the absolute one, so it
        // adapts to the recording rather than to a fixed idea of loud.
        val relativeGate = loudnessOf(audible.average()) - RELATIVE_GATE
        val kept = audible.filter { loudnessOf(it) > relativeGate }
        if (kept.isEmpty()) return null
        return loudnessOf(kept.average()).toFloat()
    }

    /** A mean square as a loudness. The offset is the spec's, and calibrates the scale to dBFS. */
    private fun loudnessOf(meanSquare: Double): Double =
        if (meanSquare <= 0.0) Double.NEGATIVE_INFINITY else OFFSET + 10.0 * log10(meanSquare)

    private companion object {
        const val BLOCK_SEC = 0.400f
        const val STEP_SEC = 0.100f

        /** Blocks quieter than this are silence and never count. LUFS. */
        const val ABSOLUTE_GATE = -70.0

        /** And then: blocks this far below the average of the rest do not count either. LU. */
        const val RELATIVE_GATE = 10.0

        /**
         * The spec's calibration constant.
         *
         * −0.691 dB is what makes a signal measured through the K-weighting filters come out on
         * the same scale as dBFS, so that a number here can be compared with a peak.
         */
        const val OFFSET = -0.691

        // The analog prototype the spec's 48 kHz coefficients come from. Recomputing from these
        // is what makes the measurement correct at 22050 Hz rather than approximately correct.
        const val SHELF_HZ = 1681.974450955533f
        const val SHELF_DB = 3.999843853973347f
        const val SHELF_Q = 0.7071752369554196f
        const val PASS_HZ = 38.13547087602444f
        const val PASS_Q = 0.5003270373238773f
    }
}

/**
 * One biquad, direct form I, as a mutable filter over a stream of samples.
 *
 * Double state rather than float: these run over a whole clip, and the high-pass at 38 Hz has poles
 * close enough to the unit circle that single-precision state accumulates audible error over a few
 * million samples. The measurement is a sum over the same samples, so that error does not cancel.
 */
class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = xd
        y2 = y1
        y1 = y
        return y.toFloat()
    }

    companion object {
        /** The K-weighting shelf: [gainDb] of lift above [hz]. */
        fun highShelf(hz: Float, gainDb: Float, q: Float, rate: Int): Biquad {
            val k = tan(PI * hz / rate)
            val vh = 10.0.pow(gainDb / 20.0)
            // The exponent is the spec's, and it is not 0.5: it is what places the shelf's
            // mid-point where the published 48 kHz coefficients put it.
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return Biquad(
                b0 = (vh + vb * k / q + k * k) / a0,
                b1 = 2.0 * (k * k - vh) / a0,
                b2 = (vh - vb * k / q + k * k) / a0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / q + k * k) / a0,
            )
        }

        fun highPass(hz: Float, q: Float, rate: Int): Biquad {
            val k = tan(PI * hz / rate)
            val a0 = 1.0 + k / q + k * k
            return Biquad(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / q + k * k) / a0,
            )
        }
    }
}
