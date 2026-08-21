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
 * ### Letting go has exactly one rule, and it has no exceptions
 *
 * **The tape goes back to whatever it was doing before you pressed the key.** Playing if it was
 * playing, stopped if it was stopped. That is the whole of it, and it took four releases to get
 * right because each attempt kept an exception to it that looked reasonable on its own:
 *
 *  - Reaching the front of the tape used to cancel the wind *and its resume together*, so letting
 *    go left the tape stopped at zero. On clips a few seconds long — which is what this app
 *    records — a wind reaches an end almost every time you use it, so the exception fired more
 *    often than the rule did.
 *  - Then reaching the *back* still cancelled it, on the reasoning that there is nothing left to
 *    play. There is nothing left to play, and the tape stops on its own when it gets there — which
 *    it does through [ranOff] a moment later, without this having to pre-empt it.
 *  - And letting go at the very end refused to resume into play at all, to avoid a start that
 *    instantly stopped again. The refusal was wrong, and so was the shrug that replaced it: a
 *    start that instantly stops again *reads as letting go doing nothing*, and it was reported as
 *    exactly that. The resume stays unconditional here — letting go says play — and the
 *    controller answers the parked head the same way its play key always has, by moving it back
 *    to the start. Where the head sits is not this rule's business.
 *
 * So an end of the tape parks the reels and touches nothing else. The key may still be down;
 * letting go is the only thing allowed to decide where the tape goes, and it decides it the same
 * way everywhere.
 */
class Deck {

    private val lock = Any()

    private companion object {
        /**
         * The one speed a wind runs at, as a multiple of playing speed.
         *
         * One speed, not a set of gears. A second tap of a wind key used to step this to 16x and
         * then 32x; that gesture skips a moment now, which is worth more — a wind at 8x crosses a
         * moment in a second or two, and past roughly 8x speech stops being something you can
         * navigate by, which is the entire point of hearing the tape while it winds.
         */
        const val WIND_SPEED = 8f
    }

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

    /**
     * How fast a wind runs, as a multiple of playing speed. See [WIND_SPEED].
     *
     * A property rather than a constant on [Transport] because the engine multiplies by it every
     * block, and direction and speed are decided in different places — the transport says which
     * way, this says how fast.
     */
    val windSpeed: Float get() = WIND_SPEED

    /** Press and hold a wind key. Release with [endWind]; a second tap is the controller's to spot. */
    fun beginWind(back: Boolean) = synchronized(lock) {
        val to = if (back) Transport.Rewinding else Transport.FastForwarding
        transport = wind.begin(from = transport, wind = to)
    }

    /**
     * Let go of a wind key: back to whatever it interrupted, with no exceptions. See the class
     * comment for the three that used to live here and what each of them cost.
     */
    fun endWind() = synchronized(lock) {
        if (!wind.isWinding) return@synchronized
        transport = wind.end()
    }

    /**
     * A wind key tapped twice: end the wind without waiting for the key to come up.
     *
     * The *skip itself* belongs to the engine, because only it knows where the clips are. This is
     * the transport half, and the reason it is not simply [endWind] is that the first press of a
     * double tap has already started a wind. Winding for a tenth of a second and then jumping is
     * fine; being left in a wind that nobody is holding is not.
     *
     * What the tape was doing before that first press is what it goes back to — the same rule
     * letting go obeys — so double-tapping while playing lands on the next moment and plays on.
     */
    fun cancelWind() = synchronized(lock) {
        if (!wind.isWinding) return@synchronized
        transport = wind.end()
    }

    /**
     * What letting go would do right now: "PLAY", "STOP", or nothing when no key is held.
     *
     * Shown on screen while winding, because after four releases of this not working the machine
     * should say what it is about to do rather than leaving it to be found out. It is also the one
     * thing that tells the two possible faults apart: if it says PLAY and the tape then does not
     * play, the fault is downstream of every rule in this class.
     */
    val resumeLabel: String
        get() = synchronized(lock) {
            if (!wind.isWinding) "" else if (wind.resumeTo == Transport.Playing) "PLAY" else "STOP"
        }

    /**
     * The head reached an end of the tape. See the class comment for why the two ends differ.
     *
     * Called from the audio thread, so it does nothing but decide. Safe to call repeatedly with
     * the same answer, because the audio thread has no way of knowing it has already reported it.
     */
    fun ranOff(atStart: Boolean) = synchronized(lock) {
        // A wind that runs out of tape parks the reels and touches nothing else — either end, the
        // same answer. The latch is deliberately untouched: the key may still be down, and letting
        // go is the only thing allowed to decide where the tape goes next.
        if (wind.isWinding) {
            transport = Transport.Stopped
            return@synchronized
        }
        // Not winding, so this is the tape itself arriving at an end: playing to the finish, or the
        // wheel scrubbed into the wall. Playing to the finish stops, because it is finished. The
        // front is not an ending — the wheel never touched the transport, so playing carries on
        // from zero and a stopped tape stays stopped.
        if (!atStart) transport = Transport.Stopped
    }
}
