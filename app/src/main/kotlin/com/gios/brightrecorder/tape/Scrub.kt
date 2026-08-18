package com.gios.brightrecorder.tape

import kotlin.math.abs

/**
 * The wheel, turned into a speed.
 *
 * Scrubbing is not a transport mode. That is the whole idea here, and it is the correction to the
 * version before it: turning the wheel used to *switch the machine into rewind* and switch it back
 * out again a third of a second after the last notch — so a slow turn, where the notches arrive
 * further apart than that timeout, flipped the transport in and out on every notch. The rewind key
 * lit up, went out, lit up, went out. Worse, coming out of the wind meant "resume what you were
 * doing", so a scroll while playing was a stutter of stop-start.
 *
 * Here the wheel only ever contributes a *rate*, and the transport underneath it is never touched.
 * Scroll while playing and it is still playing — the head just moves faster for as long as you
 * keep turning, and slides back to 1x when you stop, with nothing to resume because nothing was
 * interrupted. The keys do not blink because their state never changed.
 *
 * ### Speed comes from how fast you turn
 *
 * The sensor gives one notch at a time and no speed, so speed is the gap between notches. Each
 * notch is worth [TAPE_PER_NOTCH] of tape, so turning twice as fast covers twice as much ground
 * per second — which is what "linear" means here, and what a jog wheel does. Delivered as a
 * continuous rate rather than as a jump per notch, because a jump in a waveform is a step and a
 * step is a click.
 *
 * ### Threading
 *
 * [notch] is called from the input thread and [rate] from the audio thread, once per block.
 * Everything crossing between them is `@Volatile`. [rate] also advances the smoother, so it must
 * be called from exactly one thread — the audio loop — and nowhere else.
 */
class Scrub {

    @Volatile
    private var lastNotchMs = 0L

    /** Smoothed gap between notches, in milliseconds. */
    @Volatile
    private var gapMs = 0f

    /** -1, 0 or 1. Zero means the wheel is not turning. */
    @Volatile
    private var direction = 0

    /** The rate actually being handed to the engine, smoothed toward the target. */
    @Volatile
    private var current = 0f

    /** True while the wheel is driving the tape, so the UI and the ticker know to stay awake. */
    val isActive: Boolean get() = direction != 0 || current != 0f

    /** One notch of the wheel. */
    fun notch(direction: Int, nowMs: Long) {
        val dir = if (direction < 0) -1 else 1
        val gap = (nowMs - lastNotchMs).toFloat()

        gapMs = when {
            // First notch of a turn, or the first after a pause: no gap to measure yet, so start
            // from a deliberately unhurried one. A turn should begin gently rather than lurch —
            // if it began at the last turn's speed, every touch of the wheel would jump.
            this.direction != dir || gap > RESET_MS -> START_GAP_MS
            // Smoothed, because notch timing is jittery enough on its own that the raw interval
            // would make the speed shimmer.
            else -> gapMs + (gap.coerceIn(MIN_GAP_MS, RESET_MS) - gapMs) * SMOOTHING
        }

        this.direction = dir
        lastNotchMs = nowMs
    }

    /**
     * The signed rate the tape should run at, or 0 when the wheel is not turning.
     *
     * Called every block; advances the ramp toward the target, so nothing here jumps.
     */
    fun rate(nowMs: Long): Float {
        val since = nowMs - lastNotchMs
        val target = when {
            direction == 0 -> 0f
            // The notches have stopped. Let go, and let the ramp take the rate down rather than
            // cutting it — the position must stay continuous or the drop is a click.
            since > IDLE_MS -> {
                if (current == 0f) direction = 0
                0f
            }
            else -> {
                val wanted = direction * TAPE_PER_NOTCH * 1000f / gapMs
                // Pinned at the ceiling rather than smoothed towards it. Spun hard, the measured
                // gap jitters around the sensor's floor, so the raw figure hunts a few tenths below
                // 8x and the readout never settles — which reads as the wheel not holding its top
                // speed. Anything asking for the ceiling or beyond gets exactly the ceiling.
                if (wanted >= MAX_RATE) MAX_RATE
                else if (wanted <= -MAX_RATE) -MAX_RATE
                else wanted
            }
        }

        val step = if (abs(target) < abs(current)) FALL else RISE
        current += (target - current) * step
        if (abs(current) < DEAD) current = 0f
        // And once the ramp is within a whisker of the ceiling, sit on it. Without this the rate
        // approaches 8x asymptotically and the readout shows 7.9x for ever while the wheel is
        // being spun as fast as it can go.
        if (current >= MAX_RATE - PIN) current = MAX_RATE
        if (current <= -MAX_RATE + PIN) current = -MAX_RATE
        return current
    }

    /** Drop everything, for when the transport is taken over — by recording, or a new tape. */
    fun still() {
        direction = 0
        current = 0f
        gapMs = START_GAP_MS
    }

    private companion object {
        /**
         * How much tape one notch is worth, in seconds.
         *
         * The whole speed curve comes from this. At the sensor's fastest — a notch every 35 ms —
         * it works out at about 8x, and an unhurried turn of one notch every 250 ms gives about
         * 1.2x, which is the speed you want for finding a word. Raise it and the wheel gets
         * coarser; lower it and you have to spin further to cross a clip.
         */
        const val TAPE_PER_NOTCH = 0.30f

        /** Ceiling. Past this the interpolator's own artefacts dominate what you hear. */
        const val MAX_RATE = 8f

        /** The gap a turn starts from: about 1.2x, so the wheel eases in rather than lurching. */
        const val START_GAP_MS = 250f

        /** Faster than the sensor can physically report; anything under this is a bounce. */
        const val MIN_GAP_MS = 30f

        /** A gap longer than this is a new turn, not a slow one. Also the ceiling on a measured gap. */
        const val RESET_MS = 700f

        /**
         * Silence from the wheel that means the turn is over.
         *
         * Comfortably longer than the slowest deliberate turn's gap, so a slow scroll is one
         * continuous movement rather than a series of starts — which is precisely what went wrong
         * when this timeout was the thing switching the transport.
         */
        const val IDLE_MS = 420L

        /** Smoothing on the measured gap. */
        const val SMOOTHING = 0.35f

        /** Ramp toward a faster rate, and toward a slower one. Falling quicker feels like letting go. */
        const val RISE = 0.25f
        const val FALL = 0.35f

        /** Below this the wheel is contributing nothing. */
        const val DEAD = 0.02f

        /** How close to [MAX_RATE] counts as being at it. See [rate]. */
        const val PIN = 0.15f
    }
}
