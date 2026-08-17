package com.gios.brightrecorder.tape

/**
 * One recording: where, when, and how long.
 *
 * Everything except [samples] is recovered from the filename, so the tape survives being
 * copied off the phone and back with no database to restore. [samples] comes from the WAV
 * header and is what the timeline needs, which is why a clip whose header cannot be read is
 * dropped by the library rather than carried with a length of zero.
 */
data class Clip(
    val fileName: String,
    val place: String,
    val startedAt: Long,
    val samples: Long = 0L,
) {
    /** "Bastille, Paris at 17 Aug 2026, 14:32". The only name shown anywhere. */
    val title: String get() = Naming.title(place, startedAt)

    val seconds: Float get() = samples / SAMPLES_PER_SECOND
}
