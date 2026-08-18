package com.gios.brightrecorder.tape

/**
 * What the machine is doing, and every rule about how that changes.
 *
 * This exists because the same reported fault came back three times. The transport used to be a
 * field on the engine that four different threads wrote to — the keys from composition, the wheel
 * from the input thread, the end of the tape from the audio thread, the recorder from a
 * coroutine — with the resume latch beside it and nothing holding the two together. Every fix was
 * a guess about an interleaving, because nothing could be tested without a phone.
 *
 * So the rules live here instead: no Android, no engine, no coroutines, one lock, and a test for
 * each rule. The controller's job is reduced to pushing [transport] at the engine and starting or
 * stopping the things that make noise.
 *
 * ### The one lock, and why the audio thread may take it
 *
 * [TapeEngine] holds that nothing on the audio thread may block, and [ranOff] is called from it.
 * The exception is deliberate and narrow. Every critical section here is three or four field
 * writes — no allocation, no I/O, nothing that can wait — and the other callers are keys under a
 * thumb, so two of them arriving in the same handful of nanoseconds is not a thing that happens.
 * The alternative is what was here before: four threads writing a transport and a latch with no
 * agreement between them, which is the state this class exists to end.
 *
 * ### The end of the tape is two different rules
 *
 * The back of the tape is a stop: playing to the end means the tape has finished, and there is
 * nothing left to resume into.
 *
 * The front is not. It is a wall the head rests against: the reels stop, because there is no more
 * tape to wind, and that is *all* that happens. The key is still down and what it interrupted is
 * still waiting behind it, so letting go plays on from the beginning.
 *
 * This is the bug that kept coming back. Reaching zero used to cancel the wind and its resume
 * together, so letting go left the tape stopped at the start. On clips a few seconds long — which
 * is what this app records — a 4x rewind reaches zero almost every time you use it, so "rewind
 * while playing, then let go" ended in silence very nearly always.
 */
class Deck {

    private val lock = Any()

    /**
     * The transport the engine should be running.
     *
     * Volatile as well as guarded, because the audio thread reads it every block and must never
     * wait for a lock to do it. The lock is only for the *writers*, which is where the races were.
     */
    @Volatile
    var transport: Transport = Transport.Stopped
        private set

    private val wind = WindLatch()

    val isWinding: Boolean get() = wind.isWinding

    /** What the wind keys will hand the tape back to. Exposed for the tests and the readout. */
    val resumeTo: Transport get() = wind.resumeTo

    fun play() = synchronized(lock) {
        wind.cancel()
        transport = Transport.Playing
    }

    fun stop() = synchronized(lock) {
        wind.cancel()
        transport = Transport.Stopped
    }

    /**
     * Recording takes the machine over.
     *
     * Cancels the resume as well as the wind: this recording is filed when it stops, and an armed
     * resume would start the tape playing on top of it.
     */
    fun record() = synchronized(lock) {
        wind.cancel()
        transport = Transport.Recording
    }

    fun finishedRecording() = synchronized(lock) {
        transport = Transport.Stopped
    }

    /** Press and hold a wind key. */
    fun beginWind(back: Boolean) = synchronized(lock) {
        val to = if (back) Transport.Rewinding else Transport.FastForwarding
        transport = wind.begin(from = transport, wind = to)
    }

    /**
     * Let go of a wind key: back to whatever it interrupted.
     *
     * [atEnd] because resuming into play at the very end of the tape would start and immediately
     * stop again, which reads as a dead key. That is the one place a resume is dropped.
     */
    fun endWind(atEnd: Boolean) = synchronized(lock) {
        if (!wind.isWinding) return@synchronized
        val resume = wind.end()
        transport = if (resume == Transport.Playing && atEnd) Transport.Stopped else resume
    }

    /**
     * The head reached an end of the tape. See the class comment for why the two ends differ.
     *
     * Called from the audio thread, so it does nothing but decide. Safe to call repeatedly with
     * the same answer, because the audio thread has no way of knowing it has already reported it.
     */
    fun ranOff(atStart: Boolean) = synchronized(lock) {
        if (atStart) {
            // The reels stop against the wall. The latch is deliberately left alone: the key is
            // still down, and letting go is what decides where the tape goes next.
            if (wind.isWinding) transport = Transport.Stopped
            // Not winding means the wheel scrubbed back into the wall, and the wheel never touched
            // the transport in the first place. Nothing to do: playing keeps playing from zero.
        } else {
            // The other end really is the end. Nothing is left to play, so there is nothing to
            // resume into either, and an armed resume would start the tape from an end it has
            // already reached.
            wind.cancel()
            transport = Transport.Stopped
        }
    }
}
