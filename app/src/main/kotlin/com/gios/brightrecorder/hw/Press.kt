package com.gios.brightrecorder.hw

/**
 * What a press of the wheel means.
 *
 * The wheel is the only control you can reach without looking at the screen, so it carries the two
 * things you do without looking: a tap plays and stops, and a **hold records**. Pressing it again
 * stops the recording — you do not have to hold it a second time, because by then you are holding
 * a phone at a moment you are trying not to interrupt, and a second timed gesture is a thing to
 * get wrong.
 *
 * Kept as a decision rather than as a handler so the whole of it is testable: the caller supplies
 * the events and the timer, this supplies the meaning. It has no clock of its own on purpose —
 * [held] is called *by* the timer, so a test can fire it exactly when it likes.
 */
class Press {

    enum class Act {
        /** Nothing yet. Either the press is still being read, or it has already been answered. */
        None,

        /** A tap, and nothing was recording: play if stopped, stop if playing. */
        Toggle,

        /** Held past [HOLD_MS] with nothing recording. */
        StartRecording,

        /** Pressed while recording, however briefly. */
        StopRecording,
    }

    private var down = false

    /**
     * True once this press has been answered, so its release means nothing.
     *
     * Without it, holding to record would record *and then* toggle play on the way up, and the
     * tape would start playing over the recording that had just begun.
     */
    private var answered = false

    /**
     * The wheel went in.
     *
     * A press while recording ends the recording here, on the way down, rather than waiting for
     * the release: stopping a recording is the one thing you want to happen the instant you ask
     * for it, and a hold would otherwise have to be distinguished from a tap first.
     */
    fun down(recording: Boolean): Act {
        down = true
        answered = recording
        return if (recording) Act.StopRecording else Act.None
    }

    /** [HOLD_MS] has passed with the wheel still in. */
    fun held(): Act {
        if (!down || answered) return Act.None
        answered = true
        return Act.StartRecording
    }

    /** The wheel came back out. Only an unanswered press is a tap. */
    fun up(): Act {
        val tap = down && !answered
        down = false
        answered = false
        return if (tap) Act.Toggle else Act.None
    }

    /** The press was lost rather than released — the app went away under it, say. */
    fun cancel() {
        down = false
        answered = false
    }

    companion object {
        /**
         * How long the wheel has to be in before it means record.
         *
         * Long enough that pressing play never records by accident, short enough that it answers
         * while your thumb is still deciding. Android's own long-press is 500 ms and feels slow
         * for something you are doing *because* a moment is happening in front of you.
         */
        const val HOLD_MS = 400L
    }
}
