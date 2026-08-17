package com.gios.brightrecorder.tape

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.File
import kotlin.math.abs

/**
 * The transport. One thread, one AudioTrack, one fractional position on the tape.
 *
 * The loop is the entire behaviour of the app in about thirty lines: read the rate, read a
 * sample at the current position, move the position by the rate, repeat. Everything the user can
 * do is a change to [Transport] — playing is 1, rewinding is -4, fast-forward is 4, stopped is 0.
 * There is no separate rewind routine, because at this level winding and playing are the same
 * operation at different speeds and signs.
 *
 * Which transport is running, and what winding returns to when it ends, is not decided here —
 * that is [WindLatch], driven by the keys and the wheel from the controller.
 *
 * That is also why crossing from one clip into the next needs no code here. The position is a
 * number on a tape that [Timeline] has already made continuous, so the end of a clip is a
 * position like any other and playback runs into the following recording without a gap. Winding
 * backwards leaves the front of a clip and lands in the one before it by the same arithmetic.
 *
 * ### The rules the audio thread plays by
 *
 * Nothing here locks. Everything the UI can change is a `@Volatile` scalar that the loop reads
 * once per block, because a lock held for even a moment on the audio thread is a click in the
 * output, and a click on a phone speaker is louder than the recording.
 *
 * The one exception is [swapTape], which replaces the timeline after a recording. That does take
 * the lock — but it is called from the service between transports, never while the reels are
 * turning.
 */
class TapeEngine(private val dir: File) {

    @Volatile
    var transport: Transport = Transport.Stopped
        private set

    /**
     * Where the head is, in samples from the start of the tape.
     *
     * Fractional because the rate is: at 0.7x the head sits between samples, and rounding it to
     * a whole number every block would make the position drift against the audio it is
     * producing — slowly, and then obviously.
     */
    @Volatile
    var position: Double = 0.0
        private set

    /** Called on the audio thread when the tape runs off either end. */
    var onEnd: ((atStart: Boolean) -> Unit)? = null

    private var timeline = Timeline(emptyList())
    private val lock = Any()

    private var thread: Thread? = null

    @Volatile
    private var running = false

    val tape: Timeline get() = synchronized(lock) { timeline }

    /**
     * Put a different set of clips on the machine, keeping the head where it was in time.
     *
     * Called after a recording is filed and after a delete. The position is clamped rather than
     * reset: deleting a clip you are nowhere near should not send the head back to the start of
     * the tape.
     */
    fun swapTape(clips: List<Clip>) {
        synchronized(lock) {
            timeline = Timeline(clips)
            position = position.coerceIn(0.0, timeline.samples.toDouble())
        }
    }

    fun seek(global: Long) {
        val t = tape
        position = global.coerceIn(0L, t.samples).toDouble()
    }

    /** Jump [count] clips, in the sense a back button means it. See [Timeline.seekByClip]. */
    fun skipClip(count: Int) {
        val t = tape
        seek(t.seekByClip(position.toLong(), count))
    }

    fun setTransport(next: Transport) {
        transport = next
    }

    // ------------------------------------------------------------------ thread

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "BrightRecorder-tape").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(BLOCK * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            // The transport is driven by physical controls — the wind keys and the wheel — and
            // latency between a press and the tape moving is felt directly, so this asks for the
            // low-latency path rather than the power-saving one the other apps use.
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val out = FloatArray(BLOCK)
        // Rebuilt whenever the tape is swapped: the head holds an open file descriptor and a
        // buffer belonging to a particular clip list, so it cannot outlive one.
        var head: TapeHead? = null
        var headFor: Timeline? = null
        var gain = 0f

        track.play()
        try {
            while (running) {
                val t = tape
                if (headFor !== t) {
                    head?.close()
                    head = TapeHead(dir, t)
                    headFor = t
                }
                val reader = head!!

                val mode = transport
                val rate = mode.baseRate
                // Recording owns the audio device, so the loop idles rather than fighting it for
                // the speaker — and playing the tape into the microphone would be a feedback loop.
                val silent = mode == Transport.Recording || t.isEmpty
                // Ramped, not switched: cutting gain from 1 to 0 mid-waveform is a step, and a
                // step is a click. Ten milliseconds is inaudible as a fade and enough to remove it.
                val target = if (silent || abs(rate) < STILL) 0f else 1f

                var p = position
                for (i in 0 until BLOCK) {
                    gain += (target - gain).coerceIn(-GAIN_STEP, GAIN_STEP)
                    if (gain <= 0f && target == 0f) {
                        out[i] = 0f
                        continue
                    }
                    out[i] = Resample.at(p, rate) { reader.sample(it) } * gain
                    p += rate
                }

                // Silent means the tape is not ours to move: recording owns the transport, and an
                // empty tape has no positions. A *stopped* tape is not silent in this sense — its
                // rate is zero, so the position simply does not change, and starting a wind moves
                // it again without any special case here.
                if (!silent) {
                    val end = t.samples.toDouble()
                    when {
                        p >= end -> {
                            position = end
                            onEnd?.invoke(false)
                        }
                        p < 0.0 -> {
                            position = 0.0
                            onEnd?.invoke(true)
                        }
                        else -> position = p
                    }
                }

                var written = 0
                while (written < BLOCK && running) {
                    val n = track.write(out, written, BLOCK - written, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    written += n
                }
            }
        } finally {
            head?.close()
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private companion object {
        /**
         * Output samples per block: 512, about 23 ms.
         *
         * Shorter than the other Light apps use, because this one has a control in your hand.
         * The rate is only re-read once a block, so the block length is the delay between
         * turning the wheel and hearing the tape respond, and past about 30 ms that stops
         * feeling like a mechanism and starts feeling like a lag.
         */
        const val BLOCK = 512

        /** Below this rate the tape is treated as stopped and the output muted. */
        const val STILL = 0.02f

        /** Gain change per sample, giving a ~10 ms fade at this sample rate. */
        const val GAIN_STEP = 1f / (0.010f * SAMPLE_RATE)
    }
}
