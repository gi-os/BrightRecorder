package com.gios.brightrecorder.tape

import java.io.File

/** One tape on the shelf: a folder of clips with a name and a pattern. */
data class Tape(
    /** The folder, which is also the identity. See [Naming.folderName]. */
    val dirName: String,
    val name: String,
    val createdAt: Long,
    val pattern: Pattern,
    val clips: Int = 0,
    val samples: Long = 0L,
) {
    val seconds: Float get() = samples / SAMPLES_PER_SECOND
    val isEmpty: Boolean get() = clips == 0
}

/**
 * The shelf of tapes.
 *
 * A tape is a directory and nothing else: `tapes/2026-08-17 143205 Trip to Rome/` holding the
 * clips recorded onto it. Which means the whole idea costs no new machinery — a tape is the thing
 * [Library] already knew how to read, and switching tapes is pointing the engine at a different
 * folder.
 *
 * ### Where the name and the pattern live
 *
 * The **name is the folder**, filed exactly like a clip: timestamp first so the shelf sorts by
 * when each tape was started, human name after. Renaming is renaming the directory, which is
 * atomic and moves nothing. Copy the store onto a desktop and it reads as itself, with no index
 * to export.
 *
 * The **pattern is a one-line file** inside it, and that is the one place this design keeps
 * something outside a filename. It has to be somewhere — it cannot be derived from the name
 * without a rename silently repainting the tape — and putting it in the folder name would mean
 * every pattern change rewrote the identity of the tape. A missing file is not an error: the
 * pattern falls back to [Pattern.forName], which is stable, so a folder copied in from anywhere
 * still looks like something.
 *
 * ### One rule about deleting
 *
 * Nothing here deletes a directory tree. [delete] refuses a tape that still has clips in it, and
 * the caller has to empty it first, because a recursive delete on a store of recordings that
 * cannot be made again is the one bug in this app that would be unrecoverable.
 */
object Tapes {

    /** The shelf. */
    fun root(filesDir: File): File = File(filesDir, "tapes").apply { mkdirs() }

    /** Where a tape's clips live. */
    fun dirOf(root: File, tape: Tape): File = File(root, tape.dirName)

    /**
     * Every tape, oldest first, each with its clip count and length.
     *
     * Reads the clips of every tape, which is the same directory walk [Library.scan] does and is
     * why the shelf can show how long each tape is without keeping a tally anywhere. For a few
     * dozen tapes of a few hundred clips that is a handful of milliseconds, paid on launch and
     * after each recording.
     */
    fun list(root: File): List<Tape> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs
            .mapNotNull { dir -> read(dir) }
            .sortedWith(compareBy({ it.createdAt }, { it.dirName }))
    }

    /** One tape from its directory, or null if the folder is not one of ours. */
    fun read(dir: File): Tape? {
        val (name, createdAt) = Naming.parseFolder(dir.name) ?: return null
        val clips = Library.scan(dir)
        return Tape(
            dirName = dir.name,
            name = name,
            createdAt = createdAt,
            pattern = readPattern(dir) ?: Pattern.forName(name),
            clips = clips.size,
            samples = clips.sumOf { it.samples },
        )
    }

    /**
     * Put a new tape on the shelf.
     *
     * Returns the existing one if that name and second are already taken, rather than failing:
     * two taps on NEW should not leave two empty tapes called the same thing.
     */
    fun create(root: File, name: String, now: Long, pattern: Pattern? = null): Tape? {
        val clean = Naming.clean(name).ifBlank { DEFAULT_NAME }
        val dir = File(root, Naming.folderName(clean, now))
        if (!dir.exists() && !dir.mkdirs()) return null
        val chosen = pattern ?: Pattern.forName(clean)
        writePattern(dir, chosen)
        return read(dir)
    }

    /** Rename a tape, keeping its creation stamp so the shelf does not reshuffle. */
    fun rename(root: File, tape: Tape, newName: String): Tape? {
        val clean = Naming.clean(newName).ifBlank { return null }
        if (clean == tape.name) return tape
        val from = dirOf(root, tape)
        val to = File(root, Naming.folderName(clean, tape.createdAt))
        if (to.exists()) return null
        if (!from.renameTo(to)) return null
        return read(to)
    }

    fun setPattern(root: File, tape: Tape, pattern: Pattern): Tape? {
        val dir = dirOf(root, tape)
        if (!dir.isDirectory) return null
        writePattern(dir, pattern)
        return read(dir)
    }

    /**
     * Take an *empty* tape off the shelf.
     *
     * Refuses one with clips still on it. Deleting a folder of recordings is the single
     * unrecoverable mistake this app can make, so it is not something one call can do by
     * accident — the caller deletes the clips first, one at a time, through the same confirmation
     * every other delete goes through.
     */
    fun delete(root: File, tape: Tape): Boolean {
        val dir = dirOf(root, tape)
        if (!dir.isDirectory) return false
        if (Library.scan(dir).isNotEmpty()) return false
        // Whatever else is in there is ours and small: the pattern file, and any half-written
        // recording that was already abandoned.
        dir.listFiles()?.forEach { it.delete() }
        return dir.delete()
    }

    /**
     * Move a pre-tapes store onto the shelf.
     *
     * Before this feature every clip lived in one flat `tape/` folder. Those recordings cannot be
     * made again, so this moves them rather than copying, file by file with `renameTo` inside the
     * same filesystem — no stream, no temporary copy, nothing to half-finish. A file that will not
     * move is left exactly where it is and the old folder is kept, so a partial migration loses
     * nothing and can be finished on the next launch.
     *
     * The new tape is stamped with the *earliest clip* rather than with now, so it sorts as the
     * oldest tape on the shelf, which is what it is.
     */
    fun migrateLegacy(filesDir: File, now: Long): Tape? {
        val legacy = File(filesDir, "tape")
        if (!legacy.isDirectory) return null
        val files = legacy.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            legacy.delete()
            return null
        }

        val clips = Library.scan(legacy)
        val stamp = clips.minOfOrNull { it.startedAt } ?: now
        val tape = create(root(filesDir), DEFAULT_NAME, stamp) ?: return null
        val target = dirOf(root(filesDir), tape)

        var moved = 0
        for (f in files) {
            if (f.name == PATTERN_FILE) continue
            if (f.renameTo(File(target, f.name))) moved++
        }
        // Only when everything is out; otherwise the folder stays and so does whatever is in it.
        if (legacy.listFiles()?.none { it.isFile } == true) legacy.delete()
        return if (moved > 0) read(target) else tape
    }

    private fun readPattern(dir: File): Pattern? =
        runCatching { Pattern.parse(File(dir, PATTERN_FILE).readText().trim()) }.getOrNull()

    private fun writePattern(dir: File, pattern: Pattern) {
        runCatching { File(dir, PATTERN_FILE).writeText(pattern.name) }
    }

    /** What the first tape is called, and what a nameless one falls back to. */
    const val DEFAULT_NAME = "Tape"

    private const val PATTERN_FILE = ".pattern"
}
