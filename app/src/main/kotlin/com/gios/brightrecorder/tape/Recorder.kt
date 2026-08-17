package com.gios.brightrecorder.tape

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max

/**
 * Recording, straight to a WAV file on a thread of its own.
 *
 * ### The recording is named after it happens, not before
 *
 * Pressing record has to start the microphone immediately, and working out where you are does
 * not finish immediately — a location fix takes seconds and a reverse geocode needs the network.
 * So a recording begins as [IN_PROGRESS] with its start time in the filename, and is renamed to
 * its real title when it stops, by which point the place has usually arrived. Nothing waits on
 * the geocoder, and a fix that never comes costs the clip its place name and nothing else.
 *
 * ### Why the temporary name carries the timestamp
 *
 * Because of what happens when the app dies mid-recording, which is the failure this design is
 * built around. The samples are already on disk — they were written as they arrived — so all
 * that is missing is a patched header and a name. [recover] does both on the next launch,
 * reading the start time out of the temporary filename. Using the file's modification time
 * instead would have said when recording *stopped*, and a clip labelled with the moment the
 * battery died is a clip filed under the wrong hour.
 */
class Recorder(private val dir: File) {

    /**
     * A recording in progress. The leading dot is not what hides it from the library — that is
     * [Naming.parse] refusing to read it as a clip — but it keeps it out of the way of anything
     * else that lists the directory.
     */
    private companion object {
        const val IN_PROGRESS = ".recording-"
        const val SUFFIX = ".wav"

        /** Samples per read. About 90 ms, which is a comfortable amount of work per wakeup. */
        const val CHUNK = 2048

        /** How fast the meter falls. A peak meter that only rises is unreadable. */
        const val METER_DECAY = 0.86f
    }

    @Volatile
    var isRecording: Boolean = false
        private set

    /** Samples committed to disk. Drives the counter, so it is updated per chunk. */
    @Volatile
    var samples: Long = 0L
        private set

    /** 0..1 peak level for the meter. */
    @Volatile
    var level: Float = 0f
        private set

    /**
     * Set by the thread when the microphone fails, for the UI to report.
     *
     * A recording that silently does not happen is the worst outcome this app has, so the
     * failure is surfaced rather than logged — [com.gios.brightrecorder.report.Trouble] puts it
     * in front of the user with a button that files it.
     */
    @Volatile
    var failure: String? = null
        private set

    /**
     * Called if the capture thread stops on its own, which means it stopped early and wrongly.
     *
     * Without this the app sits there showing RECORDING with a counter frozen at zero, which is
     * the single worst state this app can be in: it looks exactly like a successful recording of a
     * quiet room, so nobody presses stop, and the moment is gone. The controller uses it to end
     * the recording properly and file whatever was captured before it died.
     */
    var onDied: (() -> Unit)? = null

    private var thread: Thread? = null
    private var startedAt = 0L
    private var target: File? = null

    /** Start recording. False if the microphone could not be opened at all. */
    fun start(now: Long): Boolean {
        if (isRecording) return true
        failure = null
        samples = 0L
        level = 0f
        startedAt = now
        val file = File(dir, "$IN_PROGRESS$now$SUFFIX")
        target = file

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            failure = "the microphone reported no usable buffer size"
            return false
        }

        val record = runCatching {
            AudioRecord(
                // UNPROCESSED asks the platform for the microphone without voice processing.
                // The defaults are tuned for speech — noise suppression, AGC, a high-pass around
                // 100 Hz — and every one of those is wrong here. This app records a room, a
                // street, rain on a window; a noise suppressor hears all of that as noise and
                // removes precisely what was being recorded.
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf * 2, CHUNK * BYTES_PER_SAMPLE * 4),
            )
        }.getOrNull()

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            // Fall back to the ordinary microphone: UNPROCESSED is optional and some devices
            // refuse it. A processed recording is worth far more than no recording.
            return startWith(MediaRecorder.AudioSource.MIC, file, minBuf)
        }
        return launch(record, file)
    }

    private fun startWith(source: Int, file: File, minBuf: Int): Boolean {
        val record = runCatching {
            AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf * 2, CHUNK * BYTES_PER_SAMPLE * 4),
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            failure = "open the microphone"
            return false
        }
        return launch(record, file)
    }

    private fun launch(record: AudioRecord, file: File): Boolean {
        isRecording = true
        thread = Thread({ write(record, file) }, "BrightRecorder-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /**
     * Stop, patch the header, and file the clip under [place].
     *
     * Returns the finished clip, or null if nothing was captured — a record button pressed and
     * released instantly should leave no trace rather than a zero-length reel on the tape.
     */
    fun stop(place: String): Clip? {
        if (!isRecording) return null
        isRecording = false
        thread?.join(1_000)
        thread = null
        val file = target ?: return null
        target = null

        Wav.repair(file)
        val info = Wav.info(file)
        if (info == null || info.samples == 0L) {
            file.delete()
            return null
        }

        val name = Naming.fileName(place, startedAt)
        val dest = unique(File(dir, name))
        return if (file.renameTo(dest)) {
            Clip(dest.name, Naming.clean(place), startedAt, info.samples)
        } else {
            // The samples are safe under the temporary name and [recover] will file them on the
            // next launch, so this is a naming failure and not a lost recording.
            failure = "rename the finished recording"
            null
        }
    }

    private fun write(record: AudioRecord, file: File) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(CHUNK)
        val bytes = ByteArray(CHUNK * BYTES_PER_SAMPLE)
        var written = 0L
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            // A placeholder length, patched by [stop] or by [recover] if this never gets there.
            raf.write(Wav.header(0))
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                failure = "start the microphone"
                return
            }
            var meter = 0f
            while (isRecording) {
                val n = record.read(buf, 0, CHUNK)
                if (n <= 0) {
                    // ERROR_INVALID_OPERATION and friends do not recover by being read again.
                    if (n < 0) failure = "read from the microphone (code $n)"
                    break
                }
                var peak = 0f
                for (i in 0 until n) {
                    val s = buf[i]
                    val v = abs(s.toInt()) / 32768f
                    if (v > peak) peak = v
                    val u = s.toInt()
                    bytes[i * 2] = (u and 0xFF).toByte()
                    bytes[i * 2 + 1] = (u shr 8 and 0xFF).toByte()
                }
                raf.write(bytes, 0, n * BYTES_PER_SAMPLE)
                written += n
                samples = written
                meter = max(peak, meter * METER_DECAY)
                level = meter.coerceIn(0f, 1f)
            }
            // Patch here as well as in [stop]: this is the thread that knows the true count, and
            // doing it now means the file on disk is valid the instant recording ends.
            Wav.patch(raf, written * BYTES_PER_SAMPLE)
        } catch (e: Throwable) {
            failure = failure ?: "write the recording (${e.javaClass.simpleName})"
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { raf?.close() }
            level = 0f
            // Still flagged as recording means nobody asked this thread to stop — it fell over. See
            // [onDied]. Deliberately last, so the file is closed and complete before the controller
            // is told, because what it does next is read the header.
            if (isRecording) onDied?.invoke()
        }
    }

    /**
     * File anything left behind by a recording that never stopped cleanly.
     *
     * Runs on every launch. Recordings recovered this way have no place name — whatever the
     * geocoder had found died with the process — so they are filed as [Naming.NOWHERE], which is
     * an honest label rather than a guess about where the phone was.
     */
    fun recover(): List<Clip> {
        val orphans = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(IN_PROGRESS) && it.name.endsWith(SUFFIX) }
            ?: return emptyList()

        return orphans.mapNotNull { file ->
            val stamp = file.name
                .removePrefix(IN_PROGRESS)
                .removeSuffix(SUFFIX)
                .toLongOrNull()
                ?: run { file.delete(); return@mapNotNull null }

            Wav.repair(file)
            val info = Wav.info(file)
            if (info == null || info.samples == 0L) {
                file.delete()
                return@mapNotNull null
            }
            val dest = unique(File(dir, Naming.fileName(Naming.NOWHERE, stamp)))
            if (file.renameTo(dest)) {
                Clip(dest.name, Naming.NOWHERE, stamp, info.samples)
            } else {
                null
            }
        }
    }

    /**
     * A free filename near [wanted].
     *
     * Two recordings can share a name — the same place in the same second — and a rename onto an
     * existing file would destroy a recording that cannot be made again. Recording is the one
     * place in this app where a collision has to be survivable rather than merely unlikely.
     */
    private fun unique(wanted: File): File {
        if (!wanted.exists()) return wanted
        val base = wanted.name.removeSuffix(SUFFIX)
        for (n in 2..99) {
            val candidate = File(wanted.parentFile, "$base ($n)$SUFFIX")
            if (!candidate.exists()) return candidate
        }
        return File(wanted.parentFile, "$base (${System.nanoTime()})$SUFFIX")
    }
}
