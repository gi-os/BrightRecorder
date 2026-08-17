package com.gios.brightrecorder.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.gios.brightrecorder.place.Places
import com.gios.brightrecorder.report.Trouble
import com.gios.brightrecorder.tape.Clip
import com.gios.brightrecorder.tape.Library
import com.gios.brightrecorder.tape.Naming
import com.gios.brightrecorder.tape.Recorder
import com.gios.brightrecorder.tape.SAMPLES_PER_SECOND
import com.gios.brightrecorder.tape.TapeEngine
import com.gios.brightrecorder.tape.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Everything the UI draws, in one snapshot. */
data class TapeState(
    val clips: List<Clip> = emptyList(),
    val transport: Transport = Transport.Stopped,
    /** Head position in samples from the start of the whole tape. */
    val position: Long = 0L,
    val total: Long = 0L,
    /** Signed tape speed, so the UI can show which way and how fast. */
    val rate: Float = 0f,
    val shuttling: Boolean = false,
    /** Samples captured so far by a recording in progress. */
    val recorded: Long = 0L,
    val level: Float = 0f,
    /** Where the current recording will be filed, as far as the fix has got. */
    val place: String = Naming.NOWHERE,
) {
    val clip: Clip? get() = clipAt(position)

    private fun clipAt(global: Long): Clip? {
        var at = 0L
        for (c in clips) {
            if (global < at + c.samples) return c
            at += c.samples
        }
        return clips.lastOrNull()
    }

    /** Seconds into the clip under the head, for the counter. */
    val intoClip: Float
        get() {
            var at = 0L
            for (c in clips) {
                if (position < at + c.samples) return (position - at) / SAMPLES_PER_SECOND
                at += c.samples
            }
            return 0f
        }

    val isRecording: Boolean get() = transport == Transport.Recording
    val isEmpty: Boolean get() = clips.isEmpty()
}

/**
 * Single owner of the tape. The UI and the foreground service both talk to this rather than to
 * each other, which keeps the Compose layer free of any binder plumbing.
 *
 * The engine publishes nothing on its own — it runs on the audio thread and must not be made to
 * emit — so this polls it while something is moving and stops polling when nothing is. That is
 * the [ticker]: at [TICK_MS] it is smooth enough for a counter and a needle, and it exists only
 * while the reels are turning, so an idle app on a phone in a pocket costs nothing.
 */
object TapeController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(TapeState())
    val state: StateFlow<TapeState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var dir: File? = null
    private var engine: TapeEngine? = null
    private var recorder: Recorder? = null
    private var places: Places? = null

    private var ticker: Job? = null
    private var locating: Job? = null

    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val tapeDir = Library.dir(app.filesDir)
        dir = tapeDir
        recorder = Recorder(tapeDir).also { r ->
            // A capture thread that stops on its own has failed. Finish the recording properly so
            // whatever it managed to capture is filed and the UI stops claiming to be recording.
            r.onDied = { scope.launch { finishRecording() } }
        }
        places = Places(app)
        engine = TapeEngine(tapeDir).also { e ->
            e.onEnd = { atStart -> onRanOff(atStart) }
        }

        scope.launch {
            // Anything a killed recording left behind is filed before the first scan, so it
            // appears on the tape immediately rather than after the next launch.
            recorder?.recover()
            reload()
        }
    }

    // ------------------------------------------------------------------ transport

    fun play() {
        if (_state.value.isRecording) return
        val e = engine ?: return
        if (e.tape.isEmpty) return
        // Playing from the very end is the one case where play has to move the head: otherwise
        // it starts, instantly runs off the end, and stops again, which reads as a dead button.
        if (e.position >= e.tape.samples.toDouble()) e.seek(0)
        set(Transport.Playing)
    }

    fun stop() = set(Transport.Stopped)

    fun rewind() = set(Transport.Rewinding)

    fun fastForward() = set(Transport.FastForwarding)

    fun toggle() = if (_state.value.transport == Transport.Playing) stop() else play()

    private fun set(transport: Transport) {
        val e = engine ?: return
        if (transport != Transport.Stopped && e.tape.isEmpty) return
        e.setTransport(transport)
        if (transport == Transport.Stopped) {
            // The engine thread is left running while the wheel still has spin to bleed off:
            // stopping it mid-coast would cut the sound instead of letting the tape settle.
            startTicking()
        } else {
            e.start()
            startService()
            startTicking()
        }
        publish()
    }

    fun skipClip(count: Int) {
        engine?.skipClip(count)
        publish()
    }

    fun seekTo(global: Long) {
        engine?.seek(global)
        publish()
    }

    fun seekToClip(clip: Clip) {
        val e = engine ?: return
        val index = e.tape.clips.indexOfFirst { it.fileName == clip.fileName }
        if (index < 0) return
        e.seek(e.tape.startOf(index))
        publish()
    }

    /**
     * One notch of the jog wheel.
     *
     * Passed straight through to the engine with no filtering — the guard against a stray brush
     * of the wheel is in the UI layer, where it can be told whether a screen wants the wheel at
     * all. Spinning the wheel also starts the audio thread, because scrubbing a stopped tape is
     * how you find a moment and it has to be audible.
     */
    fun notch(direction: Int) {
        val e = engine ?: return
        if (_state.value.isRecording || e.tape.isEmpty) return
        e.notch(direction)
        e.start()
        startService()
        startTicking()
    }

    // ------------------------------------------------------------------ recording

    /**
     * Start recording. The location lookup runs alongside it; see [Places].
     *
     * Returns false when the microphone could not be opened, which the UI reports rather than
     * silently showing a running counter that is capturing nothing.
     */
    fun record(): Boolean {
        val r = recorder ?: return false
        val e = engine ?: return false
        if (r.isRecording) return true

        // The tape stops dead first, before the microphone opens. Setting the transport mutes the
        // output in the same block, which is what stops the tape playing into the microphone; the
        // render thread is then torn down off this thread, because joining it here would block the
        // record button for as long as a block takes to drain.
        e.setTransport(Transport.Recording)
        scope.launch { e.stop() }

        places?.forget()
        locating?.cancel()
        locating = scope.launch {
            places?.locate()
            publish()
        }

        if (!r.start(System.currentTimeMillis())) {
            e.setTransport(Transport.Stopped)
            locating?.cancel()
            Trouble.record("start recording", r.failure)
            publish()
            return false
        }
        // No e.start() here: the render thread has nothing to render while the microphone owns the
        // audio path, and an AudioTrack held open against an AudioRecord on the same device is a
        // way to find out that some phones only let one of them work.
        startService()
        startTicking()
        publish()
        return true
    }

    /** Stop recording and file the clip. Returns it, or null if nothing was captured. */
    fun finishRecording(): Clip? {
        val r = recorder ?: return null
        if (!r.isRecording) return null
        val place = places?.current ?: Naming.NOWHERE
        val clip = r.stop(place)
        locating?.cancel()
        engine?.setTransport(Transport.Stopped)
        r.failure?.let { Trouble.record("finish the recording", it) }

        scope.launch {
            reload()
            // Park the head at the start of what was just recorded, so pressing play plays back
            // the thing you have just made. Anything else means hunting for it.
            if (clip != null) {
                val e = engine
                val index = e?.tape?.clips?.indexOfFirst { it.fileName == clip.fileName } ?: -1
                if (e != null && index >= 0) e.seek(e.tape.startOf(index))
            }
            publish()
        }
        return clip
    }

    fun toggleRecord() {
        if (_state.value.isRecording) finishRecording() else record()
    }

    // ------------------------------------------------------------------- library

    fun delete(clip: Clip) {
        val d = dir ?: return
        Library.delete(d, clip)
        scope.launch { reload() }
    }

    /** Re-read the directory and put the result on the machine. */
    private suspend fun reload() {
        val d = dir ?: return
        val clips = Library.scan(d)
        engine?.swapTape(clips)
        publish()
    }

    // ---------------------------------------------------------------------- ticks

    /**
     * What the end of the tape means, decided per direction.
     *
     * Running off the end stops the transport — the tape has finished. Running off the front does
     * not: a rewind that reaches the beginning should sit there with the reels stopped and the
     * head at zero, ready to play, which is what the machine does.
     *
     * Called from the audio thread, so it does the least possible: flips the transport and lets
     * the ticker publish.
     */
    private fun onRanOff(atStart: Boolean) {
        engine?.setTransport(Transport.Stopped)
        if (!atStart) engine?.seek(engine?.tape?.samples ?: 0L)
    }

    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                publish()
                val s = _state.value
                // Stop polling once everything has come to rest, including the wheel's coast.
                val moving = s.isRecording || s.transport != Transport.Stopped || s.shuttling
                if (!moving) break
                delay(TICK_MS)
            }
            // Nothing is moving, so nothing needs the audio device or the service.
            engine?.stop()
            stopService()
            publish()
        }
    }

    private fun publish() {
        val e = engine
        val r = recorder
        val tape = e?.tape
        _state.value = TapeState(
            clips = tape?.clips ?: emptyList(),
            transport = if (r?.isRecording == true) Transport.Recording else e?.transport ?: Transport.Stopped,
            position = e?.position?.toLong() ?: 0L,
            total = tape?.samples ?: 0L,
            rate = e?.let { it.shuttle.rate(it.transport) } ?: 0f,
            shuttling = e?.shuttle?.isShuttling == true,
            recorded = r?.samples ?: 0L,
            level = r?.level ?: 0f,
            place = places?.current ?: Naming.NOWHERE,
        )
    }

    // ------------------------------------------------------------------- service

    private fun startService() {
        val ctx = appContext ?: return
        val intent = Intent(ctx, TapeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun stopService() {
        val ctx = appContext ?: return
        ctx.stopService(Intent(ctx, TapeService::class.java))
    }

    /** Title for the notification and the transport strip. */
    fun nowLabel(): String {
        val s = _state.value
        return when {
            s.isRecording -> "Recording — ${s.place}"
            s.isEmpty -> "No tape"
            else -> s.clip?.title ?: "Tape"
        }
    }

    /** Roughly 30 fps while moving. The needle and the counter both read from this. */
    private const val TICK_MS = 33L
}
