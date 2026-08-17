package com.gios.brightrecorder.tape

/**
 * The one audio format this app records, stores and plays.
 *
 * 22050 Hz, 16-bit, mono, uncompressed. Every part of that is chosen for scrubbing rather
 * than for fidelity, because scrubbing is the whole app:
 *
 *  - **Uncompressed.** A tape you can spin has to be addressable by sample. In AAC or Opus
 *    the audio is packed into frames that only decode in order and only from a keyframe, so
 *    playing backwards means decoding forwards repeatedly and throwing most of it away.
 *    With raw PCM, sample *n* is at a known byte offset and reverse is a negative step.
 *  - **Mono.** One microphone, and halving the data halves every seek.
 *  - **22050 Hz.** Half of CD rate, ~11 kHz of bandwidth, and the reason is honest about
 *    what this is: a tape recorder for moments, not a field recorder for masters. It also
 *    keeps a clip at 2.6 MB a minute, so an hour of tape is 155 MB on a phone that does not
 *    have much to spare — at 44100 the same hour would be 310 MB.
 *  - **16-bit.** The format AudioRecord delivers natively. Anything narrower would need
 *    dithering to avoid hiss in quiet rooms, which is exactly where this app gets used.
 */
const val SAMPLE_RATE = 22050

/** Bytes per stored sample. 16-bit mono, so this is also bytes per frame. */
const val BYTES_PER_SAMPLE = 2

/** Samples in a second, as a float, for the division that turns a position into a clock. */
const val SAMPLES_PER_SECOND = SAMPLE_RATE.toFloat()
