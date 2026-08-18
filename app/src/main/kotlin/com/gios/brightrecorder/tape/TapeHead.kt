package com.gios.brightrecorder.tape

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads any sample on the tape, by number, from whichever file it happens to live in.
 *
 * The engine asks for sample 4,183,220 of the tape; this works out that it is 12,004 samples
 * into the fourth clip, and returns it. That indirection is what lets the engine be a plain
 * resampling loop with no idea that the tape is forty files in a directory.
 *
 * ### One window, and why one is enough
 *
 * Every read is served from a single buffered window of [WINDOW] samples. A seek to a sample
 * outside it loads the window that contains it and throws the old one away — no LRU, no second
 * buffer. That works because of how this is actually used: the head only ever *drifts*, at some
 * speed between -10x and 10x, so consecutive requests are almost always in the same window and
 * the next one needed is the one immediately before or after. A cache with more entries would
 * hold clips the head has already left behind.
 *
 * The window never spans a clip boundary — it is filled from one file — so the boundary logic
 * lives here, in a seek that happens once every few thousand samples, rather than in the audio
 * loop that runs per sample.
 *
 * Not thread safe, deliberately: it is owned by the audio thread and touched by nothing else.
 */
class TapeHead(private val dir: File, val timeline: Timeline) : Closeable {

    private var openIndex = -1
    private var file: RandomAccessFile? = null
    private var dataOffset = 0L

    /**
     * The levelling gain of the clip the window came from. See [Levels].
     *
     * Applied here rather than in the engine because *here* is the only place that knows which clip
     * a sample came from, and the whole point of levelling is that the answer is per clip. It falls
     * out of the window logic for free: a window never spans a boundary, so one gain covers it, and
     * the engine goes on being a loop that knows nothing about files.
     */
    private var openGain = 1f

    private val window = ShortArray(WINDOW)
    private val bytes = ByteArray(WINDOW * BYTES_PER_SAMPLE)

    /** Global index of `window[0]`, or -1 when nothing is loaded. */
    private var windowStart = -1L
    private var windowLength = 0

    /**
     * Sample [global] as a float, levelled, or silence if it is not on the tape.
     *
     * Levelled means the result can exceed 1.0 — a quiet clip is turned up by as much as 12 dB more
     * than its peak has room for, and holding that back is the engine's limiter, not this. See
     * [Levels.MAX_LIMITING_DB].
     *
     * Silence rather than an exception for anything off the ends or unreadable: this is called from
     * the audio thread tens of thousands of times a second, and the failure a missing file should
     * produce is a quiet gap, not a stalled transport.
     */
    fun sample(global: Long): Float {
        if (global < windowStart || global >= windowStart + windowLength) {
            if (!load(global)) return 0f
        }
        return window[(global - windowStart).toInt()] / 32768f * openGain
    }

    /** Pull in the window containing [global]. False if there is nothing there to read. */
    private fun load(global: Long): Boolean {
        val spot = timeline.locate(global) ?: return false
        val clip = timeline.clips[spot.index]
        if (spot.index != openIndex && !open(spot.index, clip)) return false

        // Windows are aligned to multiples of WINDOW within the clip, so drifting back and forth
        // across one boundary reloads two fixed windows rather than re-centering on every sample.
        val alignedLocal = (spot.offset / WINDOW) * WINDOW
        val wanted = minOf(WINDOW.toLong(), clip.samples - alignedLocal).toInt()
        if (wanted <= 0) return false

        val raf = file ?: return false
        val read = runCatching {
            raf.seek(dataOffset + alignedLocal * BYTES_PER_SAMPLE)
            raf.read(bytes, 0, wanted * BYTES_PER_SAMPLE)
        }.getOrDefault(-1)
        if (read <= 0) return false

        val samples = read / BYTES_PER_SAMPLE
        for (i in 0 until samples) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            window[i] = ((hi shl 8) or lo).toShort()
        }
        windowStart = timeline.startOf(spot.index) + alignedLocal
        windowLength = samples
        return true
    }

    private fun open(index: Int, clip: Clip): Boolean {
        close()
        val info = Wav.info(File(dir, clip.fileName)) ?: return false
        return runCatching {
            file = RandomAccessFile(File(dir, clip.fileName), "r")
            dataOffset = info.dataOffset
            openGain = clip.gain
            openIndex = index
            true
        }.getOrDefault(false)
    }

    override fun close() {
        runCatching { file?.close() }
        file = null
        openGain = 1f
        openIndex = -1
        windowStart = -1L
        windowLength = 0
    }

    private companion object {
        /**
         * Samples buffered per read: 8192, which is 16 KB and about a third of a second.
         *
         * Sized against the worst case rather than the normal one. At 10x the head consumes
         * 220,500 samples a second, so this is 27 reads a second — enough that a full-tilt spin
         * is not doing disk I/O in the audio callback's critical path, small enough that a
         * change of direction throws away only a third of a second of work.
         */
        const val WINDOW = 8192
    }
}
