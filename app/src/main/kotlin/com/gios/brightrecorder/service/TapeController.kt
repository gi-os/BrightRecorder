package com.gios.brightrecorder.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.gios.brightrecorder.Prefs
import com.gios.brightrecorder.place.Places
import com.gios.brightrecorder.report.Trouble
import com.gios.brightrecorder.tape.Clip
import com.gios.brightrecorder.tape.Library
import com.gios.brightrecorder.tape.Naming
import com.gios.brightrecorder.tape.Recorder
import com.gios.brightrecorder.tape.Tape
import com.gios.brightrecorder.tape.Tapes
import com.gios.brightrecorder.tape.SAMPLES_PER_SECOND
import com.gios.brightrecorder.tape.TapeEngine
import com.gios.brightrecorder.tape.Transport
import com.gios.brightrecorder.tape.WindLatch
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
    /** The shelf, oldest tape first. */
    val tapes: List<Tape> = emptyList(),
    /** The tape on the machine. Null only before the first shelf read finishes. */
    val tape: Tape? = null,
    /** Clips on the tape that is loaded. */
    val clips: List<Clip> = emptyList(),
    val transport: Transport = Transport.Stopped,
    /** Head position in samples from the start of the whole tape. */
    val position: Long = 0L,
    val total: Long = 0L,
    /** Signed tape speed, so the UI can show which way and how fast. */
    val rate: Float = 0f,
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
    private var root: File? = null
    private var dir: File? = null
    private var current: Tape? = null
    private var shelved: List<Tape> = emptyList()
    private var engine: TapeEngine? = null
    private var recorder: Recorder? = null
    private var places: Places? = null

    private var ticker: Job? = null
    private var locating: Job? = null

    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val shelf = Tapes.root(app.filesDir)
        root = shelf
        places = Places(app)
        // Pointed at the shelf until a tape is loaded, which happens below before anything can
        // play; the folder it actually reads from is set by [TapeEngine.loadTape].
        engine = TapeEngine(shelf).also { e ->
            e.onEnd = { atStart -> onRanOff(atStart) }
        }

        scope.launch {
            val now = System.currentTimeMillis()
            // Everything recorded before tapes existed lived in one flat folder. Move it onto the
            // shelf before anything reads the shelf, so those recordings are never briefly absent.
            Tapes.migrateLegacy(app.filesDir, now)

            var onShelf = Tapes.list(shelf)
            if (onShelf.isEmpty()) {
                // A first launch, or a store that has been cleared. The machine always has a tape
                // on it, so there is always one to record onto without deciding anything first.
                Tapes.create(shelf, Tapes.DEFAULT_NAME, now)
                onShelf = Tapes.list(shelf)
            }

            // Anything a killed recording left behind is filed before the first scan, and on
            // *every* tape rather than only the current one — an orphan on a tape you have not
            // opened since would otherwise sit there unfiled indefinitely.
            for (t in onShelf) Recorder(Tapes.dirOf(shelf, t)).recover()

            val wanted = Prefs.currentTape(app)
            val tape = onShelf.firstOrNull { it.dirName == wanted } ?: onShelf.first()
            openTape(tape)
        }
    }

    // ---------------------------------------------------------------------- the shelf

    /**
     * Put a tape on the machine.
     *
     * Everything that reads or writes clips is rebuilt around the new folder: the recorder writes
     * there, the engine reads there, and the head starts at the beginning of it. Refused while
     * recording, because the recording in progress belongs to the tape it was started on and
     * moving the machine out from under it would file it somewhere else.
     */
    fun openTape(tape: Tape) {
        val shelf = root ?: return
        val app = appContext ?: return
        if (_state.value.isRecording) return

        stop()
        val tapeDir = Tapes.dirOf(shelf, tape)
        dir = tapeDir
        current = tape
        recorder = Recorder(tapeDir).also { r ->
            // A capture thread that stops on its own has failed. Finish the recording properly so
            // whatever it managed to capture is filed and the UI stops claiming to be recording.
            r.onDied = { scope.launch { finishRecording() } }
        }
        engine?.loadTape(tapeDir, Library.scan(tapeDir))
        Prefs.setCurrentTape(app, tape.dirName)
        refreshShelf()
    }

    /** A new tape, put on the machine straight away — you made it in order to record onto it. */
    fun newTape(name: String) {
        val shelf = root ?: return
        if (_state.value.isRecording) return
        scope.launch {
            val made = Tapes.create(shelf, name, System.currentTimeMillis()) ?: return@launch
            openTape(made)
        }
    }

    fun renameTape(tape: Tape, name: String) {
        val shelf = root ?: return
        scope.launch {
            val renamed = Tapes.rename(shelf, tape, name) ?: return@launch
            // The folder moved, so anything holding the old one is stale — including this tape if
            // it is the one on the machine.
            if (tape.dirName == current?.dirName) openTape(renamed) else refreshShelf()
        }
    }

    fun cyclePattern(tape: Tape) {
        val shelf = root ?: return
        scope.launch {
            Tapes.setPattern(shelf, tape, tape.pattern.next())
            refreshShelf()
        }
    }

    /**
     * Take an empty tape off the shelf.
     *
     * [Tapes.delete] refuses one with clips on it, so this cannot destroy recordings however it is
     * called. The last tape cannot go either — the machine always has something on it, and an
     * empty shelf would be a state with no way back except recording into nothing.
     */
    fun deleteTape(tape: Tape) {
        val shelf = root ?: return
        scope.launch {
            if (_state.value.tapes.size <= 1) return@launch
            if (!Tapes.delete(shelf, tape)) return@launch
            if (tape.dirName == current?.dirName) {
                Tapes.list(shelf).firstOrNull()?.let { openTape(it) }
            } else {
                refreshShelf()
            }
        }
    }

    /** Re-read the shelf and publish. Cheap: a listing and a header read per clip. */
    private fun refreshShelf() {
        val shelf = root ?: return
        shelved = Tapes.list(shelf)
        current = shelved.firstOrNull { it.dirName == current?.dirName } ?: current
        publish()
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

    fun stop() {
        wind.cancel()
        set(Transport.Stopped)
    }

    fun toggle() = if (_state.value.transport == Transport.Playing) stop() else play()

    private fun set(transport: Transport) {
        val e = engine ?: return
        if (transport != Transport.Stopped && e.tape.isEmpty) return
        e.setTransport(transport)
        if (transport != Transport.Stopped) {
            e.start()
            startService()
        }
        startTicking()
        publish()
    }

    // --------------------------------------------------------------------- winding

    /**
     * Winding, from the wind keys and from nothing else.
     *
     * The wheel used to share this latch, and that was the bug behind "rewinding while playing
     * stops playing when you let go": the wheel armed the latch on a notch and its idle timer
     * disarmed it a third of a second later, which could happen while a finger was still holding
     * the rewind key. The wind ended early under the finger, and the release then found the latch
     * already spent and did nothing. The wheel is out of it now — see [scrubFromWheel].
     */
    private val wind = WindLatch()

    /**
     * Press and hold a wind key. Release with [endWind].
     *
     * Momentary rather than latching, which is the whole difference between this and a media
     * player: on a tape machine you hold rewind, and the instant you let go the tape carries on
     * doing what it was doing before — playing if it was playing. You never press play again.
     */
    fun beginWind(back: Boolean) {
        val e = engine ?: return
        if (_state.value.isRecording || e.tape.isEmpty) return
        val to = if (back) Transport.Rewinding else Transport.FastForwarding
        set(wind.begin(from = e.transport, wind = to))
    }

    /** Let go of a wind key: back to whatever it interrupted. */
    fun endWind() {
        if (!wind.isWinding) return
        val resume = wind.end()
        // Resuming into play at the very end of the tape would start and immediately stop, so
        // treat that as having played to the end — which is what it is.
        val e = engine
        if (resume == Transport.Playing && e != null && e.position >= e.tape.samples.toDouble()) {
            set(Transport.Stopped)
        } else {
            set(resume)
        }
    }

    /**
     * One notch of the wheel.
     *
     * The wheel does **not** change the transport. It used to: a notch switched the machine into
     * rewind and a timer switched it back out a third of a second after the last one, so any turn
     * slower than that timer flipped the transport in and out on every notch — the rewind key
     * blinking on and off, and playback stopping and restarting under it. Turning the wheel is not
     * a mode change, it is a hand on the reel.
     *
     * So a notch only feeds [Scrub], which the engine reads as a rate override for as long as the
     * wheel keeps moving. Scroll while playing and it stays playing; the head just moves faster,
     * and slides back to 1x when the turning stops, with nothing to resume because nothing was
     * interrupted. How fast comes from how quickly the notches arrive.
     */
    fun scrubFromWheel(direction: Int) {
        val e = engine ?: return
        if (_state.value.isRecording || e.tape.isEmpty) return
        e.scrub.notch(direction, System.nanoTime() / 1_000_000L)
        e.start()
        startService()
        startTicking()
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
        // Whatever the wind was going to resume into is meaningless now: this recording will be
        // filed when it stops, and an armed resume would start the tape playing on top of it.
        wind.cancel()
        e.scrub.still()
        e.setTransport(Transport.Recording)
        scope.launch { e.stop() }

        places?.forget()
        startLocating()

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

    /**
     * Start, or restart, the hunt for a place name for the recording in progress.
     *
     * Separate from [record] because it has to be callable again from outside: the location
     * permission is asked for *while* the first recording is already running, so the first
     * attempt happens before the grant exists and gives up immediately. Without a second
     * attempt when the answer arrives, the first clip anyone ever records is filed under
     * "Somewhere" no matter what they tap, and so is every clip until the app is restarted.
     */
    fun startLocating() {
        locating?.cancel()
        locating = scope.launch {
            places?.locate()
            publish()
        }
    }

    /**
     * The location permission has just been answered. Try again if it was a yes.
     *
     * Only while a recording is in progress: a grant that arrives after the clip has been filed
     * has missed its chance, and the next recording will start its own lookup anyway.
     */
    fun onLocationPermissionResult() {
        if (places?.granted() != true) return
        if (_state.value.isRecording) startLocating()
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
        // Through the shelf rather than straight to publish: the shelf shows how long each tape
        // is, so recording or deleting a clip changes what the tape looks like there too.
        refreshShelf()
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
        // The tape ran out from under the wind, so there is nothing to let go of and nothing to
        // resume into. Without this, releasing the key afterwards would start playing from an end.
        wind.cancel()
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
                val moving = s.isRecording || s.transport != Transport.Stopped ||
                    engine?.scrub?.isActive == true
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
            tapes = shelved,
            tape = current,
            clips = tape?.clips ?: emptyList(),
            transport = if (r?.isRecording == true) Transport.Recording else e?.transport ?: Transport.Stopped,
            position = e?.position?.toLong() ?: 0L,
            total = tape?.samples ?: 0L,
            rate = e?.lastRate ?: 0f,
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

    /** Roughly 30 fps while moving. The reels and the counter both read from this. */
    private const val TICK_MS = 33L

}
