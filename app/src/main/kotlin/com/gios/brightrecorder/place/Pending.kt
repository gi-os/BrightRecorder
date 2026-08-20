package com.gios.brightrecorder.place

import java.io.File

/**
 * Clips that know where they were recorded but not what it is called.
 *
 * A recording needs no network. Turning a position into a name does — Android's `Geocoder` is a
 * network service — so a moment caught on a phone with no signal gets a position and no name, is
 * filed under whatever coarse answer was available, and there the matter used to end. Which is how
 * a tape of clips all called the same country happens.
 *
 * This is the list of those, per tape: a filename and the position it was recorded at. When the
 * phone next has signal the list is worked through, each position looked up, and each clip renamed
 * to the answer.
 *
 * ### It is a work queue, not an index
 *
 * Which matters, because the rest of this app is built on there being no index — a tape is a
 * directory and the filenames are the whole truth. This does not break that rule: nothing here is a
 * second copy of anything. It is a to-do list, and the cost of losing it is that some clips keep the
 * coarse name they already have. A line is removed the moment its clip is renamed, so the file is
 * empty almost all of the time and gone as soon as it is.
 *
 * Tab-separated, one clip per line, because the parser has to survive place names and a filename
 * can contain a comma but never a tab.
 */
object Pending {

    private const val FILE = "pending-places.tsv"

    data class Waiting(val fileName: String, val latitude: Double, val longitude: Double)

    fun list(tapeDir: File): List<Waiting> = runCatching {
        val file = File(tapeDir, FILE)
        if (!file.isFile) return emptyList()
        file.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            if (parts[0].isBlank()) null else Waiting(parts[0], lat, lon)
        }
    }.getOrDefault(emptyList())

    /**
     * Remember that [fileName] is waiting for a name.
     *
     * Replaces any line already there for the same clip rather than adding a second, so a clip
     * recorded, renamed and re-queued cannot accumulate entries.
     */
    fun add(tapeDir: File, fileName: String, latitude: Double, longitude: Double): Boolean =
        write(tapeDir, list(tapeDir).filterNot { it.fileName == fileName } + Waiting(fileName, latitude, longitude))

    fun remove(tapeDir: File, fileName: String): Boolean =
        write(tapeDir, list(tapeDir).filterNot { it.fileName == fileName })

    /** Drop anything whose clip is no longer on the tape — deleted, or renamed by hand. */
    fun prune(tapeDir: File, present: Set<String>): Boolean {
        val kept = list(tapeDir).filter { it.fileName in present }
        return if (kept.size == list(tapeDir).size) false else write(tapeDir, kept)
    }

    private fun write(tapeDir: File, waiting: List<Waiting>): Boolean = runCatching {
        val file = File(tapeDir, FILE)
        if (waiting.isEmpty()) {
            file.delete()
            return true
        }
        file.writeText(waiting.joinToString("\n") { "${it.fileName}\t${it.latitude}\t${it.longitude}" })
        true
    }.getOrDefault(false)
}
