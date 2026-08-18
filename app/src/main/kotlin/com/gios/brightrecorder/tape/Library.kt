package com.gios.brightrecorder.tape

import java.io.File
import java.io.RandomAccessFile

/**
 * One directory of clips, read as a tape.
 *
 * There is no database and no index file. A tape is whatever is in its directory, sorted by the
 * timestamps in the filenames, and that is a deliberate choice rather than a shortcut: an index is
 * a second copy of the truth that can disagree with the first one, and when it does, recordings
 * that still exist stop being playable. Reading the directory every time cannot disagree with
 * itself. It costs a listing and a header read per clip, which for a few hundred clips is a few
 * milliseconds — paid once on launch and once after each recording.
 *
 * Which directory is [Tapes]' business; this reads whichever one it is handed.
 */
object Library {

    /**
     * Every clip in [dir], in recording order.
     *
     * Anything that is not one of ours is skipped: a file whose name has no timestamp, or whose
     * header will not parse as our format. Skipped rather than repaired into place, because the
     * timeline is addressed by sample count and a clip of an unknown length or sample rate would
     * make every position after it wrong.
     */
    fun scan(dir: File): List<Clip> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                val named = Naming.parse(file.name) ?: return@mapNotNull null
                // A clip whose header was never patched is one an interrupted recording left,
                // and it reads as empty until this fixes it.
                Wav.repair(file)
                val info = Wav.info(file) ?: return@mapNotNull null
                if (info.samples == 0L) return@mapNotNull null
                // The loudness comes out of the same file, if anything has measured it yet. A seek
                // and twenty bytes per clip, so this stays a header read rather than becoming a
                // pass over the audio — the measuring itself is [measure], done once and in the
                // background.
                val level = Wav.readLevel(file)
                named.copy(
                    samples = info.samples,
                    lufs = level?.lufs,
                    peak = level?.peak ?: 1f,
                    measured = level != null,
                )
            }
            // Recording order, which the filename prefix gives. The name is the tiebreak so that
            // two clips from the same second always sort the same way — an unstable order would
            // move every position on the tape after them between launches.
            .sortedWith(compareBy({ it.startedAt }, { it.fileName }))
            .toList()
    }

    fun delete(dir: File, clip: Clip): Boolean = File(dir, clip.fileName).delete()

    /**
     * Measure how loud [clip] is and store the answer in its own file.
     *
     * The expensive one: it reads every sample. That is why it is separate from [scan], which runs
     * on launch and must not, and why the answer is written into the file rather than recomputed —
     * a clip is measured once in its life, and after that it costs the twenty bytes [scan] already
     * reads.
     *
     * Returns the clip with its measurement, or null if it could not be read or written. Failing is
     * survivable: an unmeasured clip plays at unity gain, which is exactly what it did before any of
     * this existed.
     */
    fun measure(dir: File, clip: Clip): Clip? {
        val file = File(dir, clip.fileName)
        val info = Wav.info(file) ?: return null
        val loudness = Loudness()
        var peak = 0f
        val buf = ShortArray(CHUNK)
        val bytes = ByteArray(CHUNK * BYTES_PER_SAMPLE)
        val ok = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(info.dataOffset)
                var left = info.samples
                while (left > 0) {
                    val want = minOf(left, CHUNK.toLong()).toInt()
                    val read = raf.read(bytes, 0, want * BYTES_PER_SAMPLE)
                    if (read <= 0) break
                    val got = read / BYTES_PER_SAMPLE
                    for (i in 0 until got) {
                        val lo = bytes[i * 2].toInt() and 0xFF
                        val hi = bytes[i * 2 + 1].toInt()
                        val sample = ((hi shl 8) or lo).toShort()
                        buf[i] = sample
                        val mag = if (sample < 0) -sample.toFloat() else sample.toFloat()
                        if (mag > peak) peak = mag
                    }
                    loudness.add(buf, got)
                    left -= got
                }
            }
            true
        }.getOrDefault(false)
        if (!ok) return null

        val lufs = loudness.lufs()
        val peakScaled = (peak / 32768f).coerceIn(0f, 1f)
        if (!Wav.writeLevel(file, lufs, peakScaled)) return null
        return clip.copy(lufs = lufs, peak = peakScaled, measured = true)
    }

    /** Samples read per pass while measuring. 16 KB, the same size the tape head uses. */
    private const val CHUNK = 8192

    /**
     * File an existing clip under a different place, keeping the moment it was recorded.
     *
     * This is how a clip gets its real name after the fact. A moment is four seconds long and a
     * location lookup is not, so a clip is very often filed under whatever was known when you
     * pressed stop — and when the answer arrives a minute later, the clip is still the only place
     * to put it. There is no database to update, so the rename *is* the update, which is the whole
     * point of filing by filename.
     *
     * The timestamp is taken from the clip rather than from the clock, so the tape does not
     * reorder itself under the head when this happens.
     *
     * Returns the clip under its new name, or null if the rename did not happen — the name was
     * already right, something else is sitting on the new one, or the file has since gone.
     */
    fun rename(dir: File, clip: Clip, place: String): Clip? {
        val name = Naming.fileName(place, clip.startedAt)
        if (name == clip.fileName) return null
        val from = File(dir, clip.fileName)
        val to = File(dir, name)
        if (!from.isFile || to.exists()) return null
        if (!from.renameTo(to)) return null
        return clip.copy(fileName = name, place = Naming.clean(place))
    }

    /** Bytes the tape occupies, for the settings readout. */
    fun bytes(dir: File): Long =
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}
