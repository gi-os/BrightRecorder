package com.gios.brightrecorder.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.gios.brightrecorder.Prefs
import com.gios.brightrecorder.label.Label
import com.gios.brightrecorder.place.Fix
import com.gios.brightrecorder.place.Pending
import com.gios.brightrecorder.place.Places
import com.gios.brightrecorder.report.Trouble
import com.gios.brightrecorder.tape.Clip
import com.gios.brightrecorder.tape.Deck
import com.gios.brightrecorder.tape.Library
import com.gios.brightrecorder.tape.Naming
import com.gios.brightrecorder.tape.Recorder
import com.gios.brightrecorder.tape.Tape
import com.gios.brightrecorder.tape.Tapes
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
    /**
     * What letting go of the wind key would do — "PLAY", "STOP", or empty when none is held.
     *
     * On screen while winding. See [com.gios.brightrecorder.tape.Deck.resumeLabel].
     */
    val resumeTo: String = "",
    /**
     * Bumped whenever a label is written on.
     *
     * A label is a pair of files, not a field, so nothing about [tapes] changes when one is edited
     * and the shelf would go on drawing the picture it had already decoded. This is what tells it
     * to look again — a counter rather than the art itself, because the shelf shows one tape and
     * carrying every tape's bitmaps through the state would hold the lot in memory.
     */
    val labelRevision: Int = 0,
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

    /**
     * Every rule about what the transport does next, and the only place it is stored. See [Deck].
     *
     * Declared up here because [TapeEngine] takes it: the engine reads the transport from this
     * rather than keeping a copy, which is what stopped the two of them disagreeing.
     */
    private val deck = Deck()

    private var ticker: Job? = null
    private var locating: Job? = null
    private var measuring: Job? = null
    private var naming: Job? = null

    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val shelf = Tapes.root(app.filesDir)
        root = shelf
        places = Places(app)
        // Pointed at the shelf until a tape is loaded, which happens below before anything can
        // play; the folder it actually reads from is set by [TapeEngine.loadTape].
        engine = TapeEngine(shelf, deck).also { e ->
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
        // Everything on this tape that predates levelling gets measured now, in the background.
        measuring?.cancel()
        measureUnmeasured()
        naming?.cancel()
        namePending()
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
            val gone = dirOf(tape)
            if (!Tapes.delete(shelf, tape)) return@launch
            // The folder is gone; the decoded label would otherwise sit in memory until something
            // else evicted it, and a new tape landing on the same path would inherit it.
            gone?.let { Label.forget(it) }
            if (tape.dirName == current?.dirName) {
                Tapes.list(shelf).firstOrNull()?.let { openTape(it) }
            } else {
                refreshShelf()
            }
        }
    }

    /**
     * The folder a tape lives in, for anything that keeps its own files beside the recordings.
     *
     * The label is the only such thing so far. Exposed rather than having the label store find its
     * own way there, because where a tape lives is [Tapes]' business and this is the one object
     * that knows which shelf is in use.
     */
    fun dirOf(tape: Tape): File? = root?.let { Tapes.dirOf(it, tape) }

    /** The folder the tape on the machine lives in, for anything that needs to reach a clip file. */
    fun currentDir(): File? = dir

    /** A label was written on. Nothing about the tape changed, but what it looks like did. */
    fun labelChanged() {
        labelRevision++
        refreshShelf()
    }

    /** See [TapeState.labelRevision]. */
    private var labelRevision = 0

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
        deck.play()
        follow()
    }

    fun stop() {
        deck.stop()
        follow()
    }

    fun toggle() = if (_state.value.transport == Transport.Playing) stop() else play()

    /**
     * Make the machine do whatever [deck] now says, and publish it.
     *
     * The only place the engine, the service and the ticker are started, so there is one answer to
     * "what has to be running for this transport" rather than one per caller.
     */
    private fun follow() {
        val e = engine ?: return
        val transport = deck.transport
        if (transport != Transport.Stopped && e.tape.isEmpty) return
        if (transport != Transport.Stopped && transport != Transport.Recording) {
            e.start()
            startService()
        }
        startTicking()
        publish()
    }

    // --------------------------------------------------------------------- winding

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
        // A press that lands soon after the last release of the *same* key is a second tap, and a
        // second tap skips a moment instead of winding again — forwards on fast-forward, back on
        // rewind. Timed here rather than in [Deck] so the deck stays free of a clock, and per key
        // so tapping rewind does not read fast-forward's last release as its own first tap.
        val now = System.nanoTime() / 1_000_000L
        val skip = back == lastWindBack && now - lastWindReleasedAt < DOUBLE_TAP_MS
        lastWindBack = back
        if (skip) {
            // The first tap's wind ended at its own release and the transport already went back
            // to whatever it was doing — the same rule letting go obeys — so only the head moves,
            // and a double tap while playing lands on the next moment still playing. The cancel
            // is a guard rather than a step: nothing should be winding here, and if something is,
            // being left in a wind nobody is holding is the one outcome that must not happen.
            deck.cancelWind()
            e.skipClip(if (back) -1 else 1)
            follow()
            return
        }
        deck.beginWind(back)
        follow()
    }

    /** Let go of a wind key: back to whatever it interrupted. No exceptions; see [Deck]. */
    fun endWind() {
        // Recorded before the winding check, not after it: the release that follows a double
        // tap's skip finds nothing winding, but it still marks time — a third tap within the
        // window is another skip, so tap-tap-tap hops a moment per tap.
        lastWindReleasedAt = System.nanoTime() / 1_000_000L
        if (!deck.isWinding) return
        deck.endWind()
        // Letting go into play with the head parked at the very end is the dead button [play]
        // already guards against: the tape starts, runs off the end inside the first audio block,
        // and stops — which reads as "letting go did nothing". And it is the common case, not the
        // corner: a wind runs at 8x and a moment is a few seconds long, so fast-forwarding while
        // playing reaches the end of the tape almost every time. Same case, same answer as the
        // play key: the head goes back to the start, so the tape audibly does what the readout
        // promised. The resume rule itself stays exception-free in [Deck] — where the head sits
        // is not the rule's business, it is this controller's.
        val e = engine
        if (deck.transport == Transport.Playing && e != null &&
            e.position >= e.tape.samples.toDouble()
        ) {
            e.seek(0)
        }
        follow()
    }

    /** When a wind key was last let go, and which one, for spotting a double tap. */
    private var lastWindReleasedAt = 0L
    private var lastWindBack = false

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
        deck.record()
        e.scrub.still()
        scope.launch { e.stop() }

        // Not `forget()` any more: the phone has not moved since the last recording, and the place
        // already found is the one answer certain to be ready in time. Looking again is what keeps
        // it honest.
        startLocating()

        if (!r.start(System.currentTimeMillis())) {
            deck.finishedRecording()
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
     * Find out where we are before anything asks, whenever the app comes to the front.
     *
     * This is the single biggest reason clips used to be called "Somewhere". The lookup only ran
     * when you pressed record, and a moment is four seconds long — so the clip was filed long
     * before the first fix arrived, every time. Started here it has the whole time you spend
     * looking at the screen before you press anything, which is usually enough.
     *
     * Cheap to call repeatedly: [Places.locate] returns immediately while its answer is fresh, so
     * this costs a field read on all but the first resume in five minutes.
     */
    fun warmPlace() {
        if (_state.value.isRecording) return
        if (places?.granted() != true) return
        startLocating()
        // Coming to the front is also the moment to try again for anything filed without a name,
        // because it is the moment the phone is most likely to have signal.
        namePending()
    }

    /**
     * The location permission has just been answered. Try again if it was a yes.
     *
     * A grant that arrives mid-recording still counts — the clip has not been filed yet — and one
     * that arrives afterwards warms the fix for the next recording, which is worth having.
     */
    fun onLocationPermissionResult() {
        if (places?.granted() != true) return
        startLocating()
    }

    /** Stop recording and file the clip. Returns it, or null if nothing was captured. */
    fun finishRecording(): Clip? {
        val r = recorder ?: return null
        if (!r.isRecording) return null
        val best = places?.best
        val clip = r.stop(best?.name ?: Naming.NOWHERE)
        deck.finishedRecording()
        r.failure?.let { Trouble.record("finish the recording", it) }

        // The lookup is deliberately *not* cancelled when it has only a guess to show for itself.
        // A four-second recording almost always ends before a fix arrives, and cancelling here is
        // what used to leave the clip called "Somewhere" for ever. See [nameWhenKnown].
        val provisional = clip != null && best?.fix != Fix.Named
        if (!provisional) locating?.cancel()

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
            // Measured after the reload rather than before it, so the clip is on the tape and
            // playable the instant recording stops. The measurement only changes how loud it is.
            measureUnmeasured()
            if (clip != null && provisional) {
                // Put its position by before waiting, so a clip filed with no signal is still
                // nameable in an hour's time when there is some. See [Pending].
                val d = dir
                val at = places?.lastFix
                if (d != null && at != null) Pending.add(d, clip.fileName, at.first, at.second)
                nameWhenKnown(clip)
            }
        }
        return clip
    }

    /**
     * Work out how loud every clip on this tape is, for the ones nobody has measured yet.
     *
     * This is what makes levelling apply to everything already recorded rather than only to new
     * recordings. It reads each unmeasured clip once, in the background, and writes the answer into
     * the clip's own file — so it happens once in a tape's life however many times the app is
     * opened, and a clip copied off the phone and back brings its answer with it.
     *
     * Deliberately not on the launch path. A tape of a few hundred clips is tens of megabytes to
     * read, and the app has to be usable while that happens: the tape plays throughout, at unity
     * gain for whatever has not been reached yet, and each clip starts playing at its proper level
     * as soon as its own measurement lands.
     *
     * One clip at a time with a pause between, for the same reason — the point is that the app
     * stays responsive, and a background pass that saturated the flash would not leave it that way.
     * The tape is republished in batches as it goes, but only while nothing is playing: putting a
     * new timeline on the machine makes the head reopen its file, and doing that under playback is
     * a disk read in the audio loop.
     */
    private fun measureUnmeasured() {
        if (measuring?.isActive == true) return
        val d = dir ?: return
        measuring = scope.launch {
            var since = 0
            var done = 0
            // A snapshot, not a re-read per clip. Re-reading meant a directory scan and a header
            // read per clip *per clip*, which on a tape of a few hundred is minutes of pointless
            // I/O; anything recorded or deleted during the pass is picked up by the next one.
            for (clip in engine?.tape?.clips.orEmpty()) {
                if (d != dir) return@launch
                if (clip.measured) continue
                if (Library.measure(d, clip) == null) {
                    // Unreadable or unwritable, and there is no way to mark it done without the
                    // file — so skip it rather than spin, and let the next launch try again.
                    Trouble.record("measure ${clip.fileName}")
                    continue
                }
                done++
                since++
                // Republished in batches so the tape levels out as the pass goes, without paying
                // for a rescan per clip — and never under playback, where swapping the timeline
                // would make the head reopen its file from inside the audio loop.
                if (since >= RELOAD_EVERY && _state.value.transport == Transport.Stopped) {
                    since = 0
                    reload()
                }
                // The pass is not urgent and the app has to stay usable while it runs.
                delay(BREATH_MS)
            }
            // The last one is not optional: without it the clips measured since the most recent
            // batch would go on playing at unity until something else reloaded the tape.
            if (done > 0) reload()
        }
    }

    /**
     * Wait for the place to be known, then file [clip] under it.
     *
     * The clip already has a name — the region from the time zone, usually — and this replaces it
     * with the real one if the lookup lands in time. It is a plain rename, because the tape *is*
     * the directory: no index to update, nothing to disagree with. The head is safe over it, since
     * a file already open goes on being readable under its old path and the rescan below hands the
     * engine a fresh timeline anyway.
     *
     * Bounded by [NAMING_GRACE_MS]. Past that the phone has had every chance and the guess is what
     * the clip keeps — which is still a place you have been, rather than "Somewhere".
     */
    private suspend fun nameWhenKnown(clip: Clip) {
        val p = places ?: return
        val d = dir ?: return
        val until = System.currentTimeMillis() + NAMING_GRACE_MS
        while (System.currentTimeMillis() < until) {
            if (p.located) break
            delay(NAMING_POLL_MS)
        }
        if (!p.located) return
        // The tape may have moved on: a delete, another recording, a different tape on the
        // machine. Only rename what is still there, and only on the tape it was recorded onto.
        if (d != dir) return
        Library.rename(d, clip, p.found.name) ?: return
        Pending.remove(d, clip.fileName)
        reload()
    }

    /**
     * Give their real names to clips that were filed before the geocoder could answer.
     *
     * The other half of [Pending], and the answer to a tape of clips all called the same country: a
     * recording needs no network and a geocode does, so a moment caught with no signal keeps its
     * position and gets looked up the next time there is some. Run when a tape is put on and when
     * the app comes to the front, which between them covers "the next time you open it with signal".
     *
     * One clip at a time with a pause between, and every rename removes its own line, so a lookup
     * that fails is simply tried again next time rather than losing the position.
     */
    private fun namePending() {
        if (naming?.isActive == true) return
        val d = dir ?: return
        val p = places ?: return
        naming = scope.launch {
            if (Pending.list(d).isEmpty()) return@launch
            // Anything whose clip has since gone — deleted, or renamed by hand — is not worth
            // looking up. Pruned first so a stale line cannot keep the list alive for ever.
            Pending.prune(d, engine?.tape?.clips.orEmpty().map { it.fileName }.toSet())
            var renamed = false
            for (each in Pending.list(d)) {
                if (d != dir) return@launch
                val clip = engine?.tape?.clips?.firstOrNull { it.fileName == each.fileName }
                if (clip == null) {
                    Pending.remove(d, each.fileName)
                    continue
                }
                // Still no network, or the geocoder has nothing for that spot: leave the line where
                // it is and stop, because the rest will be no luckier right now.
                val name = p.nameOf(each.latitude, each.longitude) ?: break
                if (Library.rename(d, clip, name) != null) renamed = true
                Pending.remove(d, each.fileName)
                delay(BREATH_MS)
            }
            if (renamed) reload()
        }
    }

    /**
     * File [clip] under a name you typed instead of the one it was given.
     *
     * The place in a filename is not sacred — it is a guess about where you were, and sometimes the
     * useful name for a moment is "Ada's first word" rather than a street. Renaming is the same
     * operation the geocoder's late answer uses, so there is nothing new to go wrong: the timestamp
     * is kept, the tape does not reorder, and the file is the only thing that changes.
     *
     * Any position waiting to be looked up for this clip is dropped. A name you chose is not a guess
     * for the geocoder to overwrite later.
     */
    fun renameClip(clip: Clip, place: String) {
        val d = dir ?: return
        if (place.isBlank()) return
        scope.launch {
            Pending.remove(d, clip.fileName)
            if (Library.rename(d, clip, place) == null) return@launch
            reload()
        }
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
     * The head reached an end of the tape. What that means is [Deck]'s to decide.
     *
     * Called from the audio thread, so it does the least possible: asks the deck, copies the
     * answer into the engine, and lets the ticker publish.
     */
    private fun onRanOff(atStart: Boolean) {
        deck.ranOff(atStart)
        if (!atStart) engine?.seek(engine?.tape?.samples ?: 0L)
    }

    /**
     * Whether anything still needs polling.
     *
     * The wheel's coast counts. So does a wind key still being *held* after the head has stopped
     * against the front of the tape: letting go of it is about to start the tape again, and
     * tearing the render thread down in the half second before that would put a gap where there
     * should be none.
     */
    private fun moving(): Boolean {
        val s = _state.value
        return s.isRecording || s.transport != Transport.Stopped ||
            deck.isWinding || engine?.scrub?.isActive == true
    }

    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                publish()
                if (!moving()) break
                delay(TICK_MS)
            }
            // Let go of the ticker slot *before* tearing anything down. Shutting down means
            // joining the audio thread, and while this job still held the slot a key pressed
            // during that join found `ticker.isActive` true and did not start one of its own —
            // leaving the machine with a live transport, no ticker, and a render thread that had
            // just been stopped underneath it. A tape that looks like it is playing and is silent.
            ticker = null
            engine?.stop()
            stopService()
            publish()
            // And if that is what just happened, put it back on its feet.
            if (moving()) follow()
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
            transport = if (r?.isRecording == true) Transport.Recording else deck.transport,
            position = e?.position?.toLong() ?: 0L,
            total = tape?.samples ?: 0L,
            rate = e?.lastRate ?: 0f,
            recorded = r?.samples ?: 0L,
            level = r?.level ?: 0f,
            place = places?.best?.name ?: Naming.NOWHERE,
            resumeTo = deck.resumeLabel,
            labelRevision = labelRevision,
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

    /**
     * How long a clip filed under a guess keeps waiting for its real name.
     *
     * Long enough to cover a cold GPS fix outdoors, which is the case this exists for. Past it the
     * phone has had every chance — it is indoors, or offline, or refusing — and the region name it
     * already has is what the clip keeps.
     */
    private const val NAMING_GRACE_MS = 90_000L

    /** How often the wait above looks. Cheap: it reads one volatile field. */
    private const val NAMING_POLL_MS = 500L

    /**
     * How soon after letting go a second tap still counts as a double tap.
     *
     * Longer than Android's own 300 ms, because these are big keys pressed without looking and the
     * gesture here is tap-and-hold rather than tap-tap — the second press is the start of a wind you
     * mean to keep holding, so being generous costs nothing.
     */
    private const val DOUBLE_TAP_MS = 450L

    /** Clips measured between one republish of the tape and the next. See [measureUnmeasured]. */
    private const val RELOAD_EVERY = 8

    /** A pause between clips while measuring, so the pass never competes with playing the tape. */
    private const val BREATH_MS = 25L
}
