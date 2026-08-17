package com.gios.brightrecorder.tape

import kotlin.math.abs
import kotlin.math.exp

/** What the transport is doing. The base speed of the tape comes from this. */
enum class Transport {
    /** Reels stopped. The wheel can still shuttle the tape by hand. */
    Stopped,

    Playing,

    /** Winding back with the audio audible, the way a tape does. */
    Rewinding,

    FastForwarding,

    Recording,
    ;

    /**
     * Tape speed for this mode, signed, before the wheel adds anything.
     *
     * [Rewinding] and [FastForwarding] are 4x rather than the 15x or so a real transport
     * managed, because a real transport lifted the tape off the head and gave you a mechanical
     * whirr with no programme audio at all. Here the point of winding is to hear where you are,
     * and past roughly 5x speech becomes chatter you cannot navigate by. 4x is fast enough to
     * cross a ten-minute clip in a couple of minutes and slow enough that you still recognise
     * a room, a voice, or a street when you pass it.
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
}

/**
 * The flywheel behind the jog wheel.
 *
 * The wheel is not a position control. It reports one notch at a time, roughly every 35 ms
 * while it is moving, and says nothing about how fast it is being turned — so speed has to be
 * inferred from how thickly the notches arrive. That is what this does: each notch shoves the
 * tape a little harder in its direction, and the shove bleeds away continuously. Spin quickly
 * and the shoves arrive faster than they decay, so the tape winds fast; ease off and it coasts
 * down; stop and it settles back to whatever the transport was doing.
 *
 * The result is the thing that makes a jog wheel feel mechanical rather than like a scroll bar:
 * the tape has mass. It keeps moving for a moment after your thumb stops, and it cannot jump.
 *
 * ### Why not just map notches to a position
 *
 * The obvious implementation — one notch moves the tape *n* milliseconds — was the first one
 * tried and it is unusable. Every notch becomes an instant jump, so a fast spin is a stutter of
 * discontinuities rather than a sweep, and every one of them is a click in the audio, because a
 * jump in a waveform is a step and a step is a click. Driving *speed* instead means the read
 * position always moves continuously, at whatever rate, and continuous motion through a
 * waveform is exactly what a tape head does.
 *
 * All the state is a single float, and every method is arithmetic on it with no Android in
 * sight, so the feel of the wheel is testable on the JVM rather than only in the hand.
 */
class Shuttle {

    /**
     * Speed the wheel is currently contributing, signed, in multiples of normal tape speed.
     *
     * Volatile and not Compose state: it is written from the input thread and read from the
     * audio thread every block, and nothing in composition looks at it.
     */
    @Volatile
    var spin: Float = 0f
        private set

    /** One notch of the wheel. [direction] is +1 forward down the tape, -1 back. */
    fun notch(direction: Int) {
        val next = spin + direction.coerceIn(-1, 1) * IMPULSE
        // Clamped on the way in as well as on the way out, so holding the wheel down for a
        // minute cannot bank up a speed that then takes a second to bleed off.
        spin = next.coerceIn(-MAX_SPIN, MAX_SPIN)
    }

    /**
     * Let [seconds] of wall clock pass, bleeding off the shove.
     *
     * Exponential rather than linear: friction on a spinning mass is proportional to speed, so
     * a fast wind slows quickly at first and then creeps, which is what the ear expects from
     * something with inertia. A linear ramp-down sounds like a fade being pulled.
     */
    fun advance(seconds: Float) {
        if (spin == 0f) return
        val next = spin * exp(-DECAY * seconds)
        // Below this the contribution is inaudible and only costs the engine a resample; snap
        // to zero so a stopped transport actually reaches silence instead of asymptoting at it.
        spin = if (abs(next) < DEAD_ZONE) 0f else next
    }

    /** Everything stops. Used when the transport is taken over, e.g. by starting a recording. */
    fun still() {
        spin = 0f
    }

    /**
     * Tape speed to play at, given what the transport is doing.
     *
     * The wheel adds to the transport rather than overriding it, so nudging the wheel while
     * playing shoves the tape along and then settles back to 1x on its own — the behaviour of
     * a thumb on the reel of a machine that is already running.
     */
    fun rate(transport: Transport): Float =
        (transport.baseRate + spin).coerceIn(-MAX_RATE, MAX_RATE)

    /** True when the wheel is contributing anything, for the scrub indicator in the UI. */
    val isShuttling: Boolean get() = spin != 0f

    private companion object {
        /**
         * Speed added per notch.
         *
         * With [DECAY] at 5, a continuous spin lands at `IMPULSE x notchRate / DECAY`. The
         * sensor tops out around 28 notches a second, so 0.9 puts a hard spin near 5x — fast
         * enough to cross a long clip, and short of the point where the interpolator's own
         * artefacts start to dominate what you hear. An unhurried spin of eight notches a
         * second gives about 1.4x, which is the speed you want for finding a word.
         */
        const val IMPULSE = 0.9f

        /** Friction, per second. 5 gives a 200 ms time constant: a coast, not a brake. */
        const val DECAY = 5f

        /** Ceiling on the wheel's own contribution. */
        const val MAX_SPIN = 6f

        /** Ceiling once the transport's own speed is added. */
        const val MAX_RATE = 10f

        /** Below this the wheel is treated as still. About 1/50th of normal speed. */
        const val DEAD_ZONE = 0.02f
    }
}
