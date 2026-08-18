package com.gios.brightrecorder.tape

import kotlin.math.log10
import kotlin.math.pow

/**
 * How loud a clip is, and how much to turn it up so every clip is as loud as the next.
 *
 * A tape of moments is recorded in whatever the room was doing at the time: a kitchen at breakfast,
 * a street, a train, a room with one person in it. Left alone those come back twenty decibels
 * apart, so listening to a tape end to end means riding the volume — which is the one thing this
 * app is for not having to do.
 *
 * So every clip is measured once ([Loudness]) and played back through a gain that brings it to the
 * same place. The measurement is stored in the file it belongs to, so it survives the app being
 * reinstalled and the clip being copied off the phone and back.
 *
 * ### Why this happens on playback and not on the way to disk
 *
 * The record path already has a fixed makeup gain, and a fixed gain cannot do this: it does not
 * know how loud the room is until the recording is over. Measuring afterwards and *rewriting* the
 * file would work, but it would be irreversible on a recording that cannot be made again, it would
 * compound if the target ever changed, and it could not be undone if the measurement were wrong.
 * A number stored beside the samples costs one multiply per sample and can be recomputed for ever.
 *
 * It also means the levelling applies to everything already recorded, with nothing rewritten —
 * which is what "and retroactively" asked for.
 */
object Levels {

    /**
     * How loud a clip should end up, in LUFS. **This is the one number to turn.**
     *
     * Streaming services normalise to −14 LUFS, and that figure is quoted for *stereo* programme
     * material. A mono channel measured on its own reads 3.01 LU lower than the same waveform
     * measured as a stereo pair, so the mono equivalent of −14 stereo is about −17. This sits a
     * decibel louder than that, deliberately: the ask was for it to be a little louder than music
     * rather than exactly level with it, so that the phone's own volume never has to move.
     *
     * Turning this *up* does not make everything louder indefinitely — past a point every clip is
     * against [MAX_LIMITING_DB] and the only thing that grows is the limiting.
     */
    const val TARGET_LUFS = -16f

    /**
     * The most a clip is ever turned up, in decibels.
     *
     * A recording made in a genuinely silent room measures very low, and normalising it to the
     * target would mean lifting the noise floor of the microphone to conversational level: a clip
     * that used to be quiet becomes a clip that is loudly nothing. Better to leave the quietest
     * material quiet than to make a wall of hiss out of it.
     */
    const val MAX_BOOST_DB = 20f

    /**
     * How hard the playback limiter is ever asked to work, in decibels.
     *
     * This is the real constraint on loudness, and it is worth understanding before turning
     * anything. Loudness is an average and peaks are not: a quiet room with one door slam in it
     * can be 25 dB below the target on average while its loudest sample is already at the ceiling.
     * Normalising by the average alone would ask the limiter to swallow all 25 dB, and a limiter
     * pulling down that far is audible as the whole recording ducking around every transient.
     *
     * So the gain is allowed to push peaks this far past [CEILING] and no further. Clips with a
     * lot of headroom reach the target exactly; spiky ones land short of it and stay clean, which
     * is the right way round.
     */
    const val MAX_LIMITING_DB = 12f

    /** What the limiter holds peaks at. Below 1.0 so that resampling overshoot has somewhere to go. */
    const val CEILING = 0.95f

    /**
     * The playback gain for a clip measured at [lufs] with a peak sample of [peak].
     *
     * A null [lufs] means the clip is silence as far as the gate is concerned, and silence has no
     * loudness to correct — so it is left exactly as it is rather than boosted by the maximum.
     */
    fun gainFor(lufs: Float?, peak: Float): Float {
        if (lufs == null || !lufs.isFinite()) return 1f
        val wanted = TARGET_LUFS - lufs
        // How far the loudest sample can rise before it touches the ceiling. Negative if it is
        // already past it, which is possible in a file that came from somewhere else.
        val headroom = if (peak > 0f) 20f * log10(CEILING / peak) else MAX_BOOST_DB
        val allowed = headroom + MAX_LIMITING_DB
        val db = minOf(wanted, MAX_BOOST_DB, allowed)
        return 10f.pow(db / 20f)
    }

    /** [gainFor] straight from a clip's stored measurement. 1.0 for one that has not been measured. */
    fun gainFor(clip: Clip): Float = gainFor(clip.lufs, clip.peak)
}
