package com.gios.brightrecorder.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

/**
 * The container, and specifically what happens to it when a recording is interrupted.
 *
 * A recording that survives the process dying is the requirement this whole file format choice
 * was made for, so the crash case is tested first-class rather than as an edge case.
 */
class WavTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A file with [samples] samples and a correctly patched header. */
    private fun wav(name: String, samples: Int): java.io.File {
        val file = folder.newFile(name)
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(Wav.header(samples.toLong() * BYTES_PER_SAMPLE))
            val bytes = ByteArray(samples * BYTES_PER_SAMPLE)
            for (i in 0 until samples) {
                // A rising ramp, so a wrong data offset reads visibly wrong values.
                val v = i and 0x7FFF
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
            }
            raf.write(bytes)
        }
        return file
    }

    @Test
    fun `the header is the canonical 44 bytes`() {
        assertEquals(44, Wav.header(0).size)
        assertEquals(44L, Wav.HEADER_BYTES)
    }

    @Test
    fun `a written file reads back with the right length and offset`() {
        val info = Wav.info(wav("a.wav", 1000))
        assertNotNull(info)
        assertEquals(44L, info!!.dataOffset)
        assertEquals(1000L, info.samples)
    }

    @Test
    fun `an unpatched header is repaired from the file size`() {
        // Exactly what a recording killed mid-flight leaves behind: all the samples on disk and a
        // header still claiming zero. Before repair it reads as an empty clip and the library
        // drops it, which is a lost recording.
        val file = folder.newFile("orphan.wav")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(Wav.header(0))
            raf.write(ByteArray(500 * BYTES_PER_SAMPLE))
        }
        assertEquals(0L, Wav.info(file)!!.samples)

        assertTrue(Wav.repair(file))
        assertEquals(500L, Wav.info(file)!!.samples)
    }

    @Test
    fun `repairing an already correct file changes nothing`() {
        val file = wav("fine.wav", 300)
        assertTrue(!Wav.repair(file))
        assertEquals(300L, Wav.info(file)!!.samples)
    }

    @Test
    fun `a header claiming more than the file holds is not trusted`() {
        // A file cut short by a full disk still declares its intended length. Reading that many
        // samples would run off the end of the file on every pass.
        val file = wav("short.wav", 100)
        RandomAccessFile(file, "rw").use { raf -> Wav.patch(raf, 10_000L * BYTES_PER_SAMPLE) }
        assertEquals(100L, Wav.info(file)!!.samples)
    }

    @Test
    fun `data after an extra chunk is still found`() {
        // Anything that has been through an audio editor comes back with a LIST chunk of software
        // credits in front of the data. Assuming byte 44 would play those credits as noise.
        val file = folder.newFile("tagged.wav")
        val extra = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        RandomAccessFile(file, "rw").use { raf ->
            val header = Wav.header(200L * BYTES_PER_SAMPLE)
            // fmt and data are the last two chunks in our header; splice LIST in before data.
            raf.write(header, 0, 36)
            raf.write(extra)
            raf.write("data".toByteArray())
            raf.write(
                byteArrayOf(
                    (200 * BYTES_PER_SAMPLE and 0xFF).toByte(),
                    (200 * BYTES_PER_SAMPLE shr 8 and 0xFF).toByte(),
                    0, 0,
                ),
            )
            raf.write(ByteArray(200 * BYTES_PER_SAMPLE))
            // RIFF size has to cover what was actually written.
            raf.seek(4)
            raf.write(
                byteArrayOf(
                    ((raf.length() - 8) and 0xFF).toByte(),
                    ((raf.length() - 8) shr 8 and 0xFF).toByte(),
                    0, 0,
                ),
            )
        }
        val info = Wav.info(file)
        assertNotNull("data chunk was not found after a LIST chunk", info)
        assertEquals(200L, info!!.samples)
        assertEquals(44L + extra.size, info.dataOffset)
    }

    @Test
    fun `a file that is not a wav is refused`() {
        val junk = folder.newFile("junk.wav")
        junk.writeBytes(ByteArray(200) { 0x41 })
        assertNull(Wav.info(junk))
    }

    @Test
    fun `an empty file is refused rather than crashing`() {
        assertNull(Wav.info(folder.newFile("empty.wav")))
    }
}
