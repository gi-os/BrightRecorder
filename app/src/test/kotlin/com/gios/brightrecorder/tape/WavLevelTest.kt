package com.gios.brightrecorder.tape

import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement lives in the clip's own file, and these are the ways that could go wrong.
 *
 * The one to read first is [repair does not swallow a measurement into the audio] — that failure
 * mode would have put twelve bytes of metadata on the end of every measured clip, as a click.
 */
class WavLevelTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "brlevel-${System.nanoTime()}")
        .apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A finished recording: header patched, samples running to the end of the file. */
    private fun clipFile(samples: Int = 4000, name: String = "clip.wav"): File {
        val file = File(dir, name)
        val bytes = samples * BYTES_PER_SAMPLE
        file.writeBytes(Wav.header(bytes.toLong()) + ByteArray(bytes) { (it % 251).toByte() })
        return file
    }

    /** A recording the process died in the middle of: length never patched, samples on disk. */
    private fun unfinishedFile(samples: Int = 4000, name: String = "dead.wav"): File {
        val file = File(dir, name)
        val bytes = samples * BYTES_PER_SAMPLE
        file.writeBytes(Wav.header(0L) + ByteArray(bytes) { (it % 251).toByte() })
        return file
    }

    // ------------------------------------------------------------------ round trip

    @Test
    fun `a measurement written is a measurement read back`() {
        val file = clipFile()
        assertTrue(Wav.writeLevel(file, lufs = -23.45f, peak = 0.6789f))
        val level = Wav.readLevel(file)!!
        assertEquals(-23.45, level.lufs!!.toDouble(), 0.01)
        assertEquals(0.6789, level.peak.toDouble(), 0.0001)
    }

    @Test
    fun `an unmeasured clip reads back as nothing measured`() {
        assertNull(Wav.readLevel(clipFile()))
    }

    /** Silence has no loudness, and that has to survive the round trip as a distinct answer. */
    @Test
    fun `a clip measured as silence stores that it has no loudness`() {
        val file = clipFile()
        assertTrue(Wav.writeLevel(file, lufs = null, peak = 0f))
        val level = Wav.readLevel(file)
        assertNotNull("the clip should read as measured", level)
        assertNull("with no loudness", level!!.lufs)
    }

    /** Otherwise a file would grow a chunk per release and its readers would disagree. */
    @Test
    fun `remeasuring replaces the measurement rather than appending another`() {
        val file = clipFile()
        Wav.writeLevel(file, lufs = -30f, peak = 0.5f)
        val afterFirst = file.length()
        Wav.writeLevel(file, lufs = -18f, peak = 0.8f)
        assertEquals(afterFirst, file.length())
        assertEquals(-18.0, Wav.readLevel(file)!!.lufs!!.toDouble(), 0.01)
    }

    // ------------------------------------------------- not disturbing the recording

    @Test
    fun `the samples are still exactly where they were`() {
        val file = clipFile(samples = 4000)
        val before = Wav.info(file)!!
        Wav.writeLevel(file, lufs = -20f, peak = 0.5f)
        val after = Wav.info(file)!!
        assertEquals(before.dataOffset, after.dataOffset)
        assertEquals(before.samples, after.samples)
        assertEquals(4000L, after.samples)
    }

    /**
     * The dangerous one.
     *
     * [Wav.repair] used to infer the data length from the file size, so once a measurement was
     * appended every measured clip looked twelve bytes short and got "repaired" into declaring the
     * metadata as audio — a click at the end of every clip on the tape, growing by one release.
     */
    @Test
    fun `repair does not swallow a measurement into the audio`() {
        val file = clipFile(samples = 4000)
        Wav.writeLevel(file, lufs = -20f, peak = 0.5f)
        assertFalse("nothing to repair", Wav.repair(file))
        assertEquals(4000L, Wav.info(file)!!.samples)
        assertNotNull("and the measurement survives", Wav.readLevel(file))
    }

    /** The case repair exists for still has to work. */
    @Test
    fun `repair still mends a recording the process died in the middle of`() {
        val file = unfinishedFile(samples = 4000)
        assertEquals(0L, Wav.info(file)!!.samples)
        assertTrue(Wav.repair(file))
        assertEquals(4000L, Wav.info(file)!!.samples)
    }

    /**
     * And must not truncate one. An unfinished file declares a data length of zero, so the place a
     * measurement would go is byte 44 — writing there would take the whole recording with it.
     */
    @Test
    fun `an unfinished recording cannot be measured over`() {
        val file = unfinishedFile(samples = 4000)
        val before = file.length()
        assertFalse(Wav.writeLevel(file, lufs = -20f, peak = 0.5f))
        assertEquals(before, file.length())
        assertTrue("and it is still repairable", Wav.repair(file))
        assertEquals(4000L, Wav.info(file)!!.samples)
    }

    /**
     * A clip that has been through a desktop editor comes back with a `LIST` chunk of software
     * credits in front of its data. Its length cannot be inferred from its file size, and inferring
     * it anyway would have counted the credits as half a second of noise at the head of the clip.
     */
    @Test
    fun `repair leaves a file that has been through an editor alone`() {
        val file = File(dir, "edited.wav")
        val samples = 2000
        val data = ByteArray(samples * BYTES_PER_SAMPLE) { (it % 251).toByte() }
        val list = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        file.writeBytes(
            "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WAVE".toByteArray() +
                Wav.header(data.size.toLong()).copyOfRange(12, 36) +
                list +
                "data".toByteArray() +
                byteArrayOf(
                    (data.size and 0xFF).toByte(),
                    ((data.size shr 8) and 0xFF).toByte(),
                    ((data.size shr 16) and 0xFF).toByte(),
                    ((data.size shr 24) and 0xFF).toByte(),
                ) +
                data,
        )
        assertEquals(samples.toLong(), Wav.info(file)!!.samples)
        assertFalse(Wav.repair(file))
        assertEquals(samples.toLong(), Wav.info(file)!!.samples)
    }

    @Test
    fun `a file that is not ours is not written to`() {
        val file = File(dir, "notours.txt").apply { writeText("this is not a wav file at all") }
        val before = file.readText()
        assertFalse(Wav.writeLevel(file, lufs = -20f, peak = 0.5f))
        assertNull(Wav.readLevel(file))
        assertEquals(before, file.readText())
    }

    /** RIFF declares its own length, and a file we grew has to declare the new one. */
    @Test
    fun `the RIFF length covers the measurement`() {
        val file = clipFile()
        Wav.writeLevel(file, lufs = -20f, peak = 0.5f)
        val declared = RandomAccessFile(file, "r").use { raf ->
            raf.seek(4)
            val b = ByteArray(4).also { raf.readFully(it) }
            (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        }
        assertEquals(file.length() - 8, declared)
    }
}
