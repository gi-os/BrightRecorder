package com.gios.brightrecorder.tape

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measuring a real clip on disk, and the tape reading the answer back on the next scan. */
class LibraryMeasureTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "brmeasure-${System.nanoTime()}")
        .apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun record(place: String, amplitude: Float, seconds: Float = 3f, hz: Float = 997f): Clip {
        val n = (seconds * SAMPLE_RATE).toInt()
        val bytes = ByteArray(n * BYTES_PER_SAMPLE)
        for (i in 0 until n) {
            val v = (amplitude * sin(2.0 * PI * hz * i / SAMPLE_RATE) * 32767).toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val name = Naming.fileName(place, 1_770_000_000_000L + place.hashCode())
        File(dir, name).writeBytes(Wav.header(bytes.size.toLong()) + bytes)
        return Naming.parse(name)!!
    }

    @Test
    fun `a clip is measured and comes back with its loudness`() {
        val clip = record("Paris", 0.1f)
        val measured = Library.measure(dir, clip)!!
        assertEquals(-23.0, measured.lufs!!.toDouble(), 0.2)
        assertEquals(0.1, measured.peak.toDouble(), 0.01)
        assertTrue(measured.measured)
    }

    /** Which is what makes it a one-off cost: the next launch reads it rather than redoing it. */
    @Test
    fun `the measurement is on the tape the next time it is scanned`() {
        val clip = record("Paris", 0.1f)
        assertFalse("not measured yet", Library.scan(dir).single().measured)
        Library.measure(dir, clip)
        val rescanned = Library.scan(dir).single()
        assertTrue(rescanned.measured)
        assertEquals(-23.0, rescanned.lufs!!.toDouble(), 0.2)
    }

    @Test
    fun `measuring does not change how long the clip is`() {
        val clip = record("Paris", 0.1f, seconds = 2f)
        val before = Library.scan(dir).single().samples
        Library.measure(dir, clip)
        assertEquals(before, Library.scan(dir).single().samples)
    }

    /**
     * The whole point, end to end: two clips recorded ten decibels apart, measured off disk, come
     * out of the gain at the same loudness.
     */
    @Test
    fun `two clips recorded ten decibels apart end up level`() {
        val quiet = Library.measure(dir, record("Quiet", 0.03f))!!
        val loud = Library.measure(dir, record("Loud", 0.1f))!!
        assertTrue("they really were far apart", abs(quiet.lufs!! - loud.lufs!!) > 9f)

        val after = { c: Clip -> c.lufs!! + 20f * log10(c.gain) }
        assertEquals(Levels.TARGET_LUFS.toDouble(), after(quiet).toDouble(), 0.05)
        assertEquals(Levels.TARGET_LUFS.toDouble(), after(loud).toDouble(), 0.05)
    }

    /**
     * And the deliberate exception to it. A recording made in a genuinely silent room is more than
     * [Levels.MAX_BOOST_DB] below the target, and bringing it all the way up would mean lifting the
     * microphone's own noise floor to conversational level — a clip that used to be quiet becoming
     * a clip that is loudly nothing. It stays quieter than the rest, on purpose.
     */
    @Test
    fun `a clip far below the target stays quieter rather than becoming a wall of noise`() {
        val nearSilent = Library.measure(dir, record("Faint", 0.004f))!!
        val after = nearSilent.lufs!! + 20f * log10(nearSilent.gain)
        assertEquals(Levels.MAX_BOOST_DB.toDouble(), 20.0 * log10(nearSilent.gain.toDouble()), 0.01)
        assertTrue("still short of the target at $after", after < Levels.TARGET_LUFS - 3f)
    }

    /** A sine has no headroom to speak of, so both land where the limiter allowance puts them. */
    @Test
    fun `a clip with no headroom is boosted only as far as the limiter allows`() {
        val clip = Library.measure(dir, record("Loud", 0.95f))!!
        assertTrue(20f * log10(clip.gain) <= Levels.MAX_LIMITING_DB + 0.01f)
    }

    @Test
    fun `a silent clip is measured, and left alone`() {
        val clip = record("Silence", 0f)
        val measured = Library.measure(dir, clip)!!
        assertTrue("it has been looked at", measured.measured)
        assertNull("and has no loudness", measured.lufs)
        assertEquals(1f, measured.gain, 0f)
    }

    @Test
    fun `measuring a clip that is not there fails rather than throwing`() {
        val clip = record("Paris", 0.1f)
        File(dir, clip.fileName).delete()
        assertNull(Library.measure(dir, clip))
    }

    /** An interrupted recording is repaired by the scan first, and only then is it measurable. */
    @Test
    fun `an interrupted recording survives being scanned and measured`() {
        val n = (2f * SAMPLE_RATE).toInt()
        val bytes = ByteArray(n * BYTES_PER_SAMPLE)
        for (i in 0 until n) {
            val v = (0.1 * sin(2.0 * PI * 997 * i / SAMPLE_RATE) * 32767).toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val name = Naming.fileName("Interrupted", 1_770_000_000_000L)
        File(dir, name).writeBytes(Wav.header(0L) + bytes)

        val scanned = Library.scan(dir).single()
        assertEquals(n.toLong(), scanned.samples)
        val measured = Library.measure(dir, scanned)
        assertNotNull(measured)
        assertEquals(n.toLong(), Library.scan(dir).single().samples)
    }
}
