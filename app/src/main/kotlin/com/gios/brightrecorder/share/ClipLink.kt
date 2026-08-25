package com.gios.brightrecorder.share

import android.content.Intent
import android.net.Uri

/**
 * A link to one clip: `brightrecorder://clip?tape=<dir>&file=<name>`.
 *
 * The other side of the bridge [ClipsProvider] already opens. The provider tells BrightNotebook
 * that you recorded something at half past two; this is how the row it draws gets you back to the
 * recording itself — on the tape it is on, with the head parked at its first sample.
 *
 * **A tape directory and a file name, not a content URI.** The provider hands out those same two
 * strings for exactly this reason: they name a clip without granting anything, they survive the app
 * being killed, and they are what the library already looks clips up by. A `content://` URI would
 * be a read grant on a WAV — enough to play the audio somewhere else, and not enough to open the
 * machine at it, which is the thing being asked for.
 *
 * Names here carry spaces, commas and periods, because that is what the recorder writes:
 * `2026-08-17 143205 Bastille, Paris.wav`. So the check on the way in is not "is this tidy" but
 * "is this one path segment" — a name with a separator in it is refused rather than resolved, the
 * same rule and the same reason as [ClipsProvider]'s path checks. What a name resolves to is then
 * decided by the real directory listing, never by the string.
 */
object ClipLink {

    const val SCHEME = "brightrecorder"
    const val HOST = "clip"

    private const val TAPE = "tape"
    private const val FILE = "file"

    /** Written as a code point so this file carries no escape of its own. */
    private val BACKSLASH = Char(92)

    /** One clip, named the way the shelf names it. */
    data class Target(val tapeDir: String, val fileName: String)

    /** The link another app should send. Public so the notebook's copy has something to match. */
    fun uriFor(tapeDir: String, fileName: String): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter(TAPE, tapeDir)
            .appendQueryParameter(FILE, fileName)
            .build()
            .toString()

    /** Null for anything that is not one of our links, or that names something it should not. */
    fun parse(intent: Intent?): Target? = parse(intent?.data)

    fun parse(uri: Uri?): Target? {
        if (uri == null) return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val tape = uri.getQueryParameter(TAPE).orEmpty()
        val file = uri.getQueryParameter(FILE).orEmpty()
        if (!isBareName(tape) || !isBareName(file)) return null
        return Target(tapeDir = tape, fileName = file)
    }

    /**
     * One path segment and nothing else. `.`, `..` and anything holding a separator are refused:
     * the string is about to be compared against a directory listing, and a name that could leave
     * the directory it is looked up in is not a name.
     */
    fun isBareName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() &&
            trimmed != "." &&
            trimmed != ".." &&
            name.none { it == '/' || it == BACKSLASH || it == Char(0) }
    }
}
