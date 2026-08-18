package com.gios.brightrecorder.tape

import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsTest {

    private fun db(gain: Float) = 20.0 * log10(gain.toDouble())

    /** A clip with room to spare lands exactly on the target, which is the whole idea. */
    @Test
    fun `a quiet clip with headroom is brought to the target`() {
        // 10 LU below target, and peaking at a tenth of full scale — 19.5 dB of headroom, so
        // nothing is in the limiter's way.
        val gain = Levels.gainFor(lufs = Levels.TARGET_LUFS - 10f, peak = 0.1f)
        assertEquals(10.0, db(gain), 0.01)
    }

    @Test
    fun `a clip already at the target is left alone`() {
        assertEquals(1f, Levels.gainFor(lufs = Levels.TARGET_LUFS, peak = 0.1f), 1e-4f)
    }

    /** Levelling means both directions: a loud clip comes down as well. */
    @Test
    fun `a clip louder than the target is turned down`() {
        val gain = Levels.gainFor(lufs = Levels.TARGET_LUFS + 6f, peak = 0.99f)
        assertEquals(-6.0, db(gain), 0.01)
    }

    /** Otherwise a recording of a silent room becomes a recording of a microphone's noise floor. */
    @Test
    fun `a nearly silent clip is not boosted without limit`() {
        val gain = Levels.gainFor(lufs = -70f, peak = 0.001f)
        assertEquals(Levels.MAX_BOOST_DB.toDouble(), db(gain), 0.01)
    }

    /**
     * The constraint that actually decides how loud most clips get. A quiet room with one door slam
     * in it is far below the target on average and already at full scale on that one sample, and
     * asking the limiter to swallow the difference would duck the whole recording around it.
     */
    @Test
    fun `a spiky clip is only boosted as far as the limiter can take it`() {
        val gain = Levels.gainFor(lufs = Levels.TARGET_LUFS - 18f, peak = Levels.CEILING)
        assertEquals(Levels.MAX_LIMITING_DB.toDouble(), db(gain), 0.01)
    }

    @Test
    fun `a clip with some headroom gets that headroom plus the allowance`() {
        // Peaking 6 dB under the ceiling, and wanting far more than it can have.
        val peak = Levels.CEILING / 2f
        val gain = Levels.gainFor(lufs = Levels.TARGET_LUFS - 30f, peak = peak)
        assertEquals(6.02 + Levels.MAX_LIMITING_DB, db(gain), 0.02)
    }

    /** A file from elsewhere can arrive already over the ceiling; it still gets turned down. */
    @Test
    fun `a clip already past the ceiling is not boosted`() {
        val gain = Levels.gainFor(lufs = Levels.TARGET_LUFS - 30f, peak = 1f)
        assertTrue("expected under ${Levels.MAX_LIMITING_DB} dB, got ${db(gain)}", db(gain) < Levels.MAX_LIMITING_DB)
    }

    @Test
    fun `an unmeasured clip plays exactly as it was recorded`() {
        val clip = Clip("2026-08-17 143205 Paris.wav", "Paris", 0L, samples = 1000)
        assertEquals(1f, clip.gain, 0f)
    }

    /** Silence has no loudness to correct, and boosting it would only raise the noise. */
    @Test
    fun `a clip measured as silence is left alone`() {
        val clip = Clip("2026-08-17 143205 Paris.wav", "Paris", 0L, 1000, lufs = null, peak = 0.001f, measured = true)
        assertEquals(1f, clip.gain, 0f)
    }

    /** The point of all of it: two clips recorded 20 dB apart come out level. */
    @Test
    fun `two clips recorded far apart end up at the same loudness`() {
        val quiet = Clip("a.wav", "A", 0L, 1000, lufs = Levels.TARGET_LUFS - 12f, peak = 0.1f, measured = true)
        val loud = Clip("b.wav", "B", 0L, 1000, lufs = Levels.TARGET_LUFS + 4f, peak = 0.9f, measured = true)
        val quietAfter = quiet.lufs!! + db(quiet.gain).toFloat()
        val loudAfter = loud.lufs!! + db(loud.gain).toFloat()
        assertEquals(quietAfter.toDouble(), loudAfter.toDouble(), 0.05)
        assertEquals(Levels.TARGET_LUFS.toDouble(), quietAfter.toDouble(), 0.05)
    }

    /**
     * The target is quoted against music, and the figure everyone quotes for music is a stereo one.
     * A lone mono channel measures 3.01 LU lower for the same waveform, so this is where that
     * conversion is written down — getting it backwards would be 6 dB out.
     */
    @Test
    fun `the target is the mono equivalent of a streaming service's stereo target`() {
        val streamingStereo = -14f
        val monoEquivalent = streamingStereo - 3.01f
        assertTrue(
            "target ${Levels.TARGET_LUFS} should sit within a couple of LU of $monoEquivalent",
            Levels.TARGET_LUFS > monoEquivalent && Levels.TARGET_LUFS < monoEquivalent + 2f,
        )
    }
}
