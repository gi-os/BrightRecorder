package com.gios.brightrecorder.tape

/**
 * One recording: where, when, and how long.
 *
 * Where and when come from the filename, so the tape survives being copied off the phone and back
 * with no database to restore. The length and the loudness come from the clip's own file — the
 * length from the WAV header, the loudness from a chunk written after it was measured — which keeps
 * the same property: there is no index anywhere that could disagree with the recording.
 *
 * A clip whose header cannot be read is dropped by the library rather than carried with a length of
 * zero. An unmeasured one is not dropped; it plays at unity until the measuring pass reaches it.
 */
data class Clip(
    val fileName: String,
    val place: String,
    val startedAt: Long,
    val samples: Long = 0L,
    /**
     * How loud this clip sounds, in LUFS, or null if there is no honest answer.
     *
     * Read from the clip's own file rather than from any index — see [Wav.readLevel]. Null both on
     * a clip nobody has measured yet and on one whose every gating block was silence, which is why
     * [measured] is a field of its own rather than a test on this.
     */
    val lufs: Float? = null,
    /** Loudest sample in the clip, 0..1. Bounds how far [Levels.gainFor] may turn it up. */
    val peak: Float = 1f,
    /**
     * True once this clip has been measured, whatever the answer was.
     *
     * Separate from [lufs] being null because "silence" and "not looked at yet" are different
     * states that need different handling: the first is finished, the second is work to do.
     */
    val measured: Boolean = false,
) {
    /** "Bastille, Paris at 17 Aug 2026, 14:32". The only name shown anywhere. */
    val title: String get() = Naming.title(place, startedAt)

    val seconds: Float get() = samples / SAMPLES_PER_SECOND

    /** What to multiply this clip's samples by so it is as loud as every other clip. */
    val gain: Float get() = Levels.gainFor(this)
}
