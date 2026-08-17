package com.gios.brightrecorder.tape

import java.io.File
import java.io.RandomAccessFile

/** Where the samples are in a WAV file, and how many there are. */
data class WavInfo(val dataOffset: Long, val samples: Long)

/**
 * The WAV container, written by hand.
 *
 * A 44-byte canonical header in front of raw little-endian PCM, and nothing else. No encoder,
 * no `MediaMuxer`, no library. Two reasons:
 *
 *  - **The offset of every sample has to be arithmetic.** Scrubbing means seeking to sample
 *    *n* thousands of times a minute, and with a fixed-size header that is
 *    `dataOffset + n * 2` — no index, no parsing, no decode. A container that stores audio in
 *    variable-length frames cannot answer that question without walking the file.
 *  - **A recording in progress has to be a valid file.** RIFF stores its own length in the
 *    header, which is not known until recording stops, so the length is written as a
 *    placeholder and patched afterwards by [patch]. If the app dies mid-recording the
 *    placeholder is wrong but the samples are all on disk, and [repair] fixes the header from
 *    the file size on the next launch. Nothing is lost — which was the whole requirement,
 *    since the recordings this app makes cannot be made again.
 */
object Wav {

    /** Canonical PCM header size. Fixed, which is what makes seeking arithmetic. */
    const val HEADER_BYTES = 44L

    /** The header for a file whose data section is [dataBytes] long. */
    fun header(dataBytes: Long): ByteArray {
        val out = ByteArray(HEADER_BYTES.toInt())
        var i = 0
        fun ascii(s: String) { s.forEach { out[i++] = it.code.toByte() } }
        fun le32(v: Long) {
            out[i++] = (v and 0xFF).toByte()
            out[i++] = (v shr 8 and 0xFF).toByte()
            out[i++] = (v shr 16 and 0xFF).toByte()
            out[i++] = (v shr 24 and 0xFF).toByte()
        }
        fun le16(v: Int) {
            out[i++] = (v and 0xFF).toByte()
            out[i++] = (v shr 8 and 0xFF).toByte()
        }

        val byteRate = SAMPLE_RATE.toLong() * BYTES_PER_SAMPLE

        ascii("RIFF")
        // Everything after this field: 36 bytes of headers plus the data.
        le32(36L + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        le32(16L)          // PCM fmt chunk length
        le16(1)            // format 1 = uncompressed PCM
        le16(1)            // mono
        le32(SAMPLE_RATE.toLong())
        le32(byteRate)
        le16(BYTES_PER_SAMPLE) // block align
        le16(8 * BYTES_PER_SAMPLE)
        ascii("data")
        le32(dataBytes)
        return out
    }

    /**
     * Read where the samples start and how many there are.
     *
     * The chunk walk is real rather than assuming byte 44, because these files get copied off
     * the phone and edited, and anything that has been through an editor comes back with a
     * `LIST` chunk of software credits in front of the data. Assuming the offset would play
     * that metadata as a half-second of noise at the head of the clip.
     *
     * The declared data length is *cross-checked against the file size and the smaller wins*.
     * A file cut short by a full disk or a battery pull still declares its intended length, and
     * trusting that would read past the end of the file on every pass.
     */
    fun info(file: File): WavInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < HEADER_BYTES) return null
            val riff = ByteArray(4).also { raf.readFully(it) }
            if (String(riff) != "RIFF") return null
            raf.skipBytes(4) // overall size, not trusted; see above
            val wave = ByteArray(4).also { raf.readFully(it) }
            if (String(wave) != "WAVE") return null

            var mono16 = false
            while (raf.filePointer + 8 <= raf.length()) {
                val id = ByteArray(4).also { raf.readFully(it) }.let { String(it) }
                val size = readLe32(raf)
                val body = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val format = readLe16(raf)
                        val channels = readLe16(raf)
                        val rate = readLe32(raf)
                        raf.skipBytes(6) // byte rate and block align, both implied by the rest
                        val bits = readLe16(raf)
                        mono16 = format == 1 && channels == 1 && bits == 16 && rate == SAMPLE_RATE.toLong()
                    }
                    "data" -> {
                        if (!mono16) return null
                        val available = raf.length() - body
                        val bytes = minOf(size, available).coerceAtLeast(0L)
                        return WavInfo(body, bytes / BYTES_PER_SAMPLE)
                    }
                }
                // Chunks are word aligned: an odd length is followed by a pad byte.
                raf.seek(body + size + (size and 1L))
            }
            null
        }
    }.getOrNull()

    /** Rewrite both length fields once the final [dataBytes] is known. */
    fun patch(raf: RandomAccessFile, dataBytes: Long) {
        raf.seek(4)
        writeLe32(raf, 36L + dataBytes)
        raf.seek(40)
        writeLe32(raf, dataBytes)
    }

    /**
     * Fix a header whose lengths were never patched, using the file's real size.
     *
     * Called on every clip the library finds, because the cost is a stat and two writes and the
     * alternative is a clip that was recorded but reads as empty. A recording interrupted by the
     * process dying is the case this exists for, and it is not a rare one — the app records with
     * the screen off for minutes at a time.
     */
    fun repair(file: File): Boolean = runCatching {
        val length = file.length()
        if (length <= HEADER_BYTES) return false
        val declared = RandomAccessFile(file, "r").use { raf ->
            raf.seek(40)
            readLe32(raf)
        }
        val actual = length - HEADER_BYTES
        if (declared == actual) return false
        RandomAccessFile(file, "rw").use { raf -> patch(raf, actual) }
        true
    }.getOrDefault(false)

    private fun readLe16(raf: RandomAccessFile): Int {
        val a = raf.read()
        val b = raf.read()
        return (a and 0xFF) or ((b and 0xFF) shl 8)
    }

    private fun readLe32(raf: RandomAccessFile): Long {
        val a = raf.read().toLong() and 0xFF
        val b = raf.read().toLong() and 0xFF
        val c = raf.read().toLong() and 0xFF
        val d = raf.read().toLong() and 0xFF
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }

    private fun writeLe32(raf: RandomAccessFile, v: Long) {
        raf.write(byteArrayOf(
            (v and 0xFF).toByte(),
            (v shr 8 and 0xFF).toByte(),
            (v shr 16 and 0xFF).toByte(),
            (v shr 24 and 0xFF).toByte(),
        ))
    }
}
