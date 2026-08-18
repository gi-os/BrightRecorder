package com.gios.brightrecorder.tape

import java.io.File
import java.io.RandomAccessFile

/** Where the samples are in a WAV file, and how many there are. */
data class WavInfo(val dataOffset: Long, val samples: Long)

/** A clip's measured loudness, as stored in its own file. See [Wav.readLevel]. */
data class WavLevel(val lufs: Float?, val peak: Float)

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

    /**
     * Our own chunk, holding what [Loudness] measured.
     *
     * Written *after* the data chunk rather than before it, which is not a free choice: the sample
     * offset has to stay arithmetic (see the class comment), and anything inserted ahead of the
     * data moves it. A reader that does not know this chunk skips it, which is all of them.
     *
     * Twelve bytes: the loudness in hundredths of a LU, the peak in ten-thousandths of full scale,
     * and a flag saying whether the loudness is a real measurement or the gate found only silence.
     * Fixed point rather than floats so the bytes are the same on every machine that writes them.
     */
    private const val LEVEL_ID = "brlv"

    private const val LEVEL_BYTES = 12L

    /** What a clip whose gating blocks were all silence stores in place of a loudness. */
    private const val NO_LOUDNESS = Int.MIN_VALUE

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

    /**
     * Read back what was measured, or null if nothing has been.
     *
     * Our chunk sits immediately after the samples, and this looks there and nowhere else. That is
     * deliberate rather than lazy: an unfinished recording declares a data length of zero, so a
     * walker that carried on past the data chunk would be reading raw audio as chunk headers — and
     * a walker that did that on the *write* path could find a coincidental "brlv" inside a
     * recording and truncate the file at it.
     */
    fun readLevel(file: File): WavLevel? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val at = levelOffset(raf) ?: return@use null
            if (at + 8 + LEVEL_BYTES > raf.length()) return@use null
            raf.seek(at)
            val id = ByteArray(4).also { raf.readFully(it) }.let { String(it) }
            if (id != LEVEL_ID) return@use null
            val size = readLe32(raf)
            if (size < LEVEL_BYTES) return@use null
            val hundredths = readLe32(raf).toInt()
            val tenThousandths = readLe32(raf)
            val real = readLe32(raf) != 0L
            WavLevel(
                lufs = if (real) hundredths / 100f else null,
                peak = (tenThousandths / 10_000f).coerceIn(0f, 1f),
            )
        }
    }.getOrNull()

    /**
     * Store a measurement in the clip's own file, replacing any already there.
     *
     * Replacing rather than appending, because the target loudness can change and the clips get
     * remeasured — a file that accrued one chunk per release would be a file whose readers disagree
     * about which one is current. Since the chunk is always the last thing in the file, replacing it
     * is a truncate to the end of the samples and a write.
     *
     * Refuses a file whose samples do not run to a plausible end, which is the one case where
     * truncating would take audio with it.
     */
    fun writeLevel(file: File, lufs: Float?, peak: Float): Boolean = runCatching {
        RandomAccessFile(file, "rw").use { raf ->
            val at = levelOffset(raf) ?: return@use false
            // The samples must actually end here, or the only thing past them must be a
            // measurement of ours that this is about to replace. Without this an *unfinished*
            // recording — whose data chunk declares a length of zero, so whose "end" is byte 44 —
            // would be truncated to its header and the whole recording lost.
            if (at != raf.length() && readLevel(file) == null) return@use false
            raf.setLength(at)
            raf.seek(at)
            raf.write(LEVEL_ID.map { it.code.toByte() }.toByteArray())
            writeLe32(raf, LEVEL_BYTES)
            writeLe32(raf, (lufs?.let { (it * 100f).toInt() } ?: 0).toLong() and 0xFFFFFFFFL)
            writeLe32(raf, (peak.coerceIn(0f, 1f) * 10_000f).toInt().toLong())
            writeLe32(raf, if (lufs != null) 1L else 0L)
            // RIFF's own length field covers everything after it, our chunk included, so a reader
            // walking the file stops in the right place.
            raf.seek(4)
            writeLe32(raf, raf.length() - 8)
            true
        }
    }.getOrDefault(false)

    /**
     * The offset where our chunk lives, or would live: straight after the samples.
     *
     * Null unless this is a file this app wrote — a canonical 44-byte header with a data chunk that
     * declares a length reaching no further than the file does. Anything else came from somewhere
     * else, and is not ours to append to or truncate.
     */
    private fun levelOffset(raf: RandomAccessFile): Long? {
        if (raf.length() < HEADER_BYTES) return null
        raf.seek(0)
        val riff = ByteArray(4).also { raf.readFully(it) }
        if (String(riff) != "RIFF") return null
        raf.skipBytes(4)
        val wave = ByteArray(4).also { raf.readFully(it) }
        if (String(wave) != "WAVE") return null
        raf.seek(36)
        val id = ByteArray(4).also { raf.readFully(it) }.let { String(it) }
        if (id != "data") return null
        val declared = readLe32(raf)
        val end = HEADER_BYTES + declared + (declared and 1L)
        // A declared length past the end of the file is a recording that was cut short, and one
        // that stops short of it with no chunk of ours after it is one that was never patched.
        // Neither has a settled end to write to; [repair] deals with them first.
        if (end > raf.length()) return null
        return end
    }

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
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(36)
            val id = ByteArray(4).also { raf.readFully(it) }.let { String(it) }
            // Only the shape this app writes, with the samples straight after a canonical header.
            // A clip that came back from a desktop editor has a `LIST` chunk of credits in front of
            // its data, and inferring its length from its file size would count that metadata as
            // audio — which the old version of this did.
            if (id != "data") return false
            val declared = readLe32(raf)
            // Anything already sitting after the samples means the file was patched when it was
            // written and there is nothing here to mend. Our own measurement chunk is the case:
            // without this check every measured clip looked twelve bytes short and got "repaired"
            // into playing its own metadata as a click at the end.
            if (readLevel(file) != null) return false
            val actual = length - HEADER_BYTES
            if (declared == actual) return false
            patch(raf, actual)
            true
        }
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
