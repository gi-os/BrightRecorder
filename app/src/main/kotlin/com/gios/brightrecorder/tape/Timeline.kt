package com.gios.brightrecorder.tape

/**
 * Every clip laid end to end as one continuous length of tape.
 *
 * This is the idea the whole app rests on. There is no "next track" and no gap between
 * recordings: the clips are butted together in recording order and addressed by a single
 * sample number that runs from 0 to [samples]. Playing past the end of one clip is not an
 * event that has to be handled — it is just a position that happens to fall inside the next
 * one. Rewinding past the start of a clip lands in the previous one the same way.
 *
 * That is what makes a spool of separate recordings behave like a tape you can wind through,
 * and it is why this class exists at all rather than the engine holding "current clip" and a
 * "next" pointer. A pointer would need a rule for every boundary in both directions, and each
 * of those rules is a place for the audio to click, stall, or skip a clip.
 *
 * Positions are `Long` sample counts, never seconds and never bytes. Seconds are floating
 * point and would drift; bytes would have to be divided by the sample size at every use, and
 * the one time that division is forgotten it produces audio at the wrong speed.
 */
class Timeline(val clips: List<Clip>) {

    /**
     * Where each clip starts on the tape, plus the total at the end.
     *
     * `starts[i]` is the first sample of clip *i* and `starts[size]` is the length of the whole
     * tape, which makes every lookup below a plain binary search with no special case for the
     * last clip. Built once: this is read on the audio thread, several times a block.
     */
    private val starts: LongArray = LongArray(clips.size + 1).also { acc ->
        var at = 0L
        clips.forEachIndexed { i, clip ->
            acc[i] = at
            at += clip.samples
        }
        acc[clips.size] = at
    }

    /** Length of the whole tape in samples. */
    val samples: Long get() = starts[clips.size]

    val isEmpty: Boolean get() = samples == 0L

    val seconds: Float get() = samples / SAMPLES_PER_SECOND

    /** First sample of clip [index]. */
    fun startOf(index: Int): Long = starts[index.coerceIn(0, clips.size)]

    /**
     * Which clip sample [global] falls in, and how far into it.
     *
     * Null past either end — the caller decides what running off the tape means, because the
     * answer differs: playback stops at the end, while a rewind that runs off the front should
     * park at zero and keep the transport running.
     */
    fun locate(global: Long): Spot? {
        if (global < 0 || global >= samples) return null
        // Binary search for the last start that is <= global. Clips can be zero-length in
        // principle (a recording stopped instantly), and those must be skipped rather than
        // landed on, which is why this searches the boundaries instead of walking the list.
        var lo = 0
        var hi = clips.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= global) lo = mid else hi = mid - 1
        }
        // A run of zero-length clips shares a start, so step forward off any of them.
        var i = lo
        while (i < clips.size && clips[i].samples == 0L) i++
        if (i >= clips.size) return null
        return Spot(i, global - starts[i])
    }

    /** The clip playing at [global], or null off either end. */
    fun clipAt(global: Long): Clip? = locate(global)?.let { clips[it.index] }

    /**
     * The start of the clip [count] clips away from the one at [global].
     *
     * Used by the skip buttons. Skipping back from partway through a clip goes to the start of
     * *that* clip rather than the one before it, which is how every transport that has ever had
     * a back button behaves and what a thumb expects.
     */
    fun seekByClip(global: Long, count: Int): Long {
        if (isEmpty) return 0L
        val here = locate(global) ?: return if (count < 0) startOf(clips.size - 1) else samples
        val target = if (count < 0 && here.offset > 0) here.index + count + 1 else here.index + count
        // Forward off the last clip is the end of the tape, not the last clip again: skip at the
        // final clip should stop the transport the way playing to the end does.
        if (target >= clips.size) return samples
        return startOf(target.coerceAtLeast(0))
    }
}

/** A position on the tape, resolved to a clip and an offset inside it. */
data class Spot(val index: Int, val offset: Long)
