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
 * Two things sit on top of that. Which transport is running, and what the wind keys return to
 * when released, is [Deck] — every rule about that lives there, away from any thread and any
 * phone, because getting it wrong is not visible until you are holding one. What the *wheel*
 * contributes is [Scrub], which overrides the rate for as long as the wheel is turning without
 * touching the transport at all — so scrolling while playing leaves it playing.
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
class TapeEngine(dir: File, private val deck: Deck) {

    /**
     * The folder the head reads from.
     *
     * Mutable because the machine takes different tapes now, and a tape is a directory. Changed
     * only by [swapTape], together with the timeline it belongs to — the two must move as one, or
     * the head spends a block reading one tape's clip list out of another tape's folder.
     */
    @Volatile
    private var dir: File = dir

    /**
     * What the machine is doing — read from [Deck], never copied.
     *
     * This used to be a field here that the controller wrote to from five different places, one of
     * them the audio thread. That was the bug behind four releases of "rewinding while playing does
     * not carry on playing when you let go", and it could not be caught by testing the rules,
     * because the rules were right: letting go at the front of the tape has the audio thread
     * reporting the end and the finger coming up at the same instant, and whichever of the two
     * wrote its copy last won. The copy the audio thread was holding said stopped.
     *
     * A property with no state cannot go stale, so the whole class of race is gone rather than
     * narrowed.
     */
    val transport: Transport get() = deck.transport

    /**
     * The wheel's contribution, which *overrides* the transport while it is turning.
     *
     * An override rather than a mode, because the transport must survive being scrubbed over:
     * scroll while playing and it is still playing, so when the wheel stops the tape carries on at
     * 1x with nothing to resume. See [Scrub].
     */
    val scrub = Scrub()

    /**
     * The rate the last block actually ran at, for the readout.
     *
     * Published rather than recomputed by the UI because the wheel's rate lives in [scrub] and is
     * smoothed per block — the only honest source for "how fast is the tape going" is the loop.
     */
    @Volatile
    var lastRate: Float = 0f
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

    /**
     * Put a different tape on the machine: new folder, new clips, head back to the start.
     *
     * The position resets rather than being clamped, which is the difference between this and
     * [swapTape]. Swapping is the same tape re-read after a recording or a delete and the head
     * should stay where it was; this is a *different* tape, and starting it in the middle because
     * that is where you happened to be on the last one would make no sense.
     */
    fun loadTape(newDir: File, clips: List<Clip>) {
        synchronized(lock) {
            dir = newDir
            timeline = Timeline(clips)
            position = 0.0
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

    private fun silentFor(mode: Transport, t: Timeline): Boolean =
        mode == Transport.Recording || t.isEmpty

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
        // Levelling in [TapeHead] hands up samples that can be well past full scale — that is the
        // point of it, and this is what keeps them from squaring off. The same limiter the record
        // path uses, for the same reason: a quiet clip lifted 20 dB has one door slam in it that
        // must not become a click. It costs about two milliseconds of latency at the head of a
        // clip, which is the length of its look-ahead and is inaudible.
        val limiter = Limiter(threshold = Levels.CEILING)
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
                // The wheel wins while it is turning; otherwise the transport's own speed.
                val scrubRate = scrub.rate(System.nanoTime() / 1_000_000L)
                // A wind's speed is a gear the user picks, so it comes from the deck rather than
                // from the transport: the transport only says which way.
                val transportRate =
                    if (mode.isWinding) mode.baseRate * deck.windSpeed else mode.baseRate
                val rate = if (scrubRate != 0f) scrubRate else transportRate
                lastRate = if (silentFor(mode, t)) 0f else rate
                // Recording owns the audio device, so the loop idles rather than fighting it for
                // the speaker — and playing the tape into the microphone would be a feedback loop.
                val silent = silentFor(mode, t)
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
                    out[i] = softClip(limiter.process(Resample.at(p, rate) { reader.sample(it) })) * gain
                    p += rate
                }

                // Silent means the tape is not ours to move: recording owns the transport, and an
                // empty tape has no positions. A *stopped* tape is not silent in this sense — its
                // rate is zero, so the position simply does not change, and starting a wind moves
                // it again without any special case here.
                if (!silent) {
                    val end = t.samples.toDouble()
                    // Only a *moving* tape can run off an end. A stopped one parked against one
                    // sits there with p == position for ever, and reporting that every block —
                    // which this used to — meant the end of the tape was announced forty times a
                    // second to a transport that had already stopped because of it.
                    val moving = rate != 0f
                    when {
                        p >= end -> {
                            position = end
                            if (moving) onEnd?.invoke(false)
                        }
                        p < 0.0 -> {
                            position = 0.0
                            if (moving) onEnd?.invoke(true)
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
