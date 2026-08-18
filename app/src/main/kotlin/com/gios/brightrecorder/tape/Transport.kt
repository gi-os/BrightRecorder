package com.gios.brightrecorder.tape

/** What the transport is doing. The speed of the tape comes from this and nothing else. */
enum class Transport {
    Stopped,

    Playing,

    /** Winding back with the audio audible, the way a tape does. */
    Rewinding,

    FastForwarding,

    Recording,
    ;

    /**
     * Tape speed for this mode, signed.
     *
     * [Rewinding] and [FastForwarding] are 4x rather than the 15x or so a real transport managed,
     * because a real transport lifted the tape off the head and gave you a mechanical whirr with
     * no programme audio at all. Here the point of winding is to hear where you are, and past
     * roughly 5x speech becomes chatter you cannot navigate by. 4x is fast enough to cross a
     * ten-minute clip in a couple of minutes and slow enough that you still recognise a room, a
     * voice, or a street when you pass it.
     */
    val baseRate: Float
        get() = when (this) {
            Stopped -> 0f
            Playing -> 1f
            Rewinding -> -4f
            FastForwarding -> 4f
            // The tape moves forward at exactly 1x while recording, and nothing may change that.
            Recording -> 1f
        }

    val isWinding: Boolean get() = this == Rewinding || this == FastForwarding
}

/**
 * Winding is momentary, and it remembers what it interrupted.
 *
 * This is the whole behaviour of a tape transport's wind keys, and it is the part that makes the
 * machine feel like a machine rather than like a media player: you hold rewind, the tape winds
 * back while you hold it, and the moment you let go it carries on doing whatever it was doing
 * before — playing if it was playing, sitting still if it was stopped. You never have to press
 * play again to get back to where you were.
 *
 * The wheel does *not* come through here, and used to. Turning it looks like the same gesture —
 * wind while you turn, carry on when you stop — but it is not one, because a turn has no release
 * to wait for. The only thing that could end a wheel wind was a timer, and a timer that fires
 * between two slow notches ends a wind the user is still making. Sharing this class with the keys
 * meant it could also end a wind a *key* was still holding. The wheel contributes a rate instead,
 * which interrupts nothing and so has nothing to resume; see [Scrub].
 *
 * Kept apart from the engine and free of Android so that "what does it return to" is a question
 * with a unit test rather than a question about a phone.
 */
class WindLatch {

    /** The wind in progress, or null when nothing is winding. */
    var winding: Transport? = null
        private set

    /** What to go back to when it ends. */
    var resumeTo: Transport = Transport.Stopped
        private set

    val isWinding: Boolean get() = winding != null

    /**
     * Start winding [wind], interrupting [from]. Returns the transport to run.
     *
     * Re-entrant on purpose: the wheel calls this on every notch, and only the first of a run
     * captures what to return to. Without that, the second notch would record "rewinding" as the
     * thing to resume and the tape would never come out of the wind.
     */
    fun begin(from: Transport, wind: Transport): Transport {
        if (winding == null) {
            // Never resume into a wind or a recording. A wind is what we are leaving, and a
            // recording cannot be resumed at all — it was filed the moment it stopped.
            resumeTo = if (from.isWinding || from == Transport.Recording) {
                Transport.Stopped
            } else {
                from
            }
        }
        winding = wind
        return wind
    }

    /** Let go. Returns the transport to run — what was interrupted. */
    fun end(): Transport {
        winding = null
        return resumeTo
    }

    /**
     * Forget the wind without resuming, for when something else takes the transport over.
     *
     * Pressing record mid-wind is the case: the resume state is about to be meaningless, and
     * leaving it armed would have the tape start playing when the recording ends.
     */
    fun cancel() {
        winding = null
        resumeTo = Transport.Stopped
    }
}
