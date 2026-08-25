package com.gios.brightrecorder.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.gios.brightrecorder.tape.Library
import com.gios.brightrecorder.tape.Tapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What was recorded on a given day, offered to the rest of the collection.
 *
 * BrightNotebook draws a day out of what the other apps know about it — where you were, what you
 * played, who you talked to — and a recording is the one kind of evidence a notebook would most
 * obviously want and could not see. Tapes live in this app's `filesDir`, which nothing outside
 * this process can read, so the only way across the boundary is a provider.
 *
 * ### Shape
 *
 * `content://com.gios.brightrecorder.clips/clips/2026-08-25` → a row per clip that started on that
 * **calendar** date. Deliberately calendar days rather than the notebook's four-in-the-morning
 * journal day: this app has no opinion about where a day begins, the caller does, and it asks for
 * both dates and filters. Sorting the rows here would be the same mistake — the caller places them
 * on an axis of its own.
 *
 * `content://com.gios.brightrecorder.clips/clip/<tape dir>/<file name>` opens the audio read-only,
 * so a caller that wants to play a clip can, without a copy of it or a storage permission. Both
 * segments are checked against the real directory listing rather than trusted: a name is a string
 * that arrived from another process, and `../` is a string too.
 *
 * ### No permission
 *
 * The same reasoning as Roll's `StarsProvider` and BrightChat's `ChatsProvider`, and it holds a
 * little better here: what this reveals is that a recording happened, when, how long it ran, and
 * whatever place name you typed on it. One phone, one user, a hand-picked set of applications. The
 * alternative is a signature check maintained against a keystore per app, which is more moving
 * parts protecting less. Nothing here is writable — every mutating method answers zero.
 */
class ClipsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context ?: return cursor
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != PATH_CLIPS) return cursor
        val date = runCatching { LocalDate.parse(segments[1]) }.getOrNull() ?: return cursor
        val zone = ZoneId.systemDefault()

        // Read off disk, with none of this app running. A tape is its directory and a clip is its
        // filename — there is no index to load and nothing to keep in sync, which is the property
        // that makes serving this from a cold process cost a listing and a header read per clip.
        runCatching {
            val root = Tapes.root(context.filesDir)
            root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val tape = Tapes.read(dir)?.name ?: dir.name
                Library.scan(dir)
                    .filter { clipDate(it.startedAt, zone) == date }
                    .forEach { clip ->
                        cursor.addRow(
                            arrayOf(
                                clip.startedAt,
                                clip.seconds,
                                clip.place,
                                tape,
                                clip.title,
                                dir.name,
                                clip.fileName,
                            ),
                        )
                    }
            }
        }
        return cursor
    }

    /**
     * Open one clip, read-only.
     *
     * Resolved against the directory listing rather than by building a path out of what arrived:
     * `File(root, "../../databases/notes.db")` is a perfectly ordinary-looking pair of segments.
     * Matching names against what is actually there means a request can only ever name a clip.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.size < 3 || segments[0] != PATH_CLIP) return null
        if (mode.contains('w')) return null
        val root = Tapes.root(context.filesDir)
        val dir = root.listFiles()?.firstOrNull { it.isDirectory && it.name == segments[1] }
            ?: return null
        val file = dir.listFiles()?.firstOrNull { it.isFile && it.name == segments[2] }
            ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        if (uri.pathSegments.firstOrNull() == PATH_CLIP) "audio/wav" else "vnd.android.cursor.dir/vnd.$AUTHORITY.clip"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun clipDate(startedAt: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()

    companion object {
        const val AUTHORITY = "com.gios.brightrecorder.clips"
        const val PATH_CLIPS = "clips"
        const val PATH_CLIP = "clip"

        const val COLUMN_STARTED_MS = "started_ms"
        const val COLUMN_SECONDS = "seconds"
        const val COLUMN_PLACE = "place"
        const val COLUMN_TAPE = "tape"
        const val COLUMN_TITLE = "title"
        const val COLUMN_TAPE_DIR = "tape_dir"
        const val COLUMN_FILE = "file"

        val COLUMNS = arrayOf(
            COLUMN_STARTED_MS,
            COLUMN_SECONDS,
            COLUMN_PLACE,
            COLUMN_TAPE,
            COLUMN_TITLE,
            COLUMN_TAPE_DIR,
            COLUMN_FILE,
        )

        /** The URI a caller reads a day of recordings from. */
        fun clipsFor(date: LocalDate): Uri =
            Uri.parse("content://$AUTHORITY/$PATH_CLIPS/$date")

        /** The URI a caller plays one clip from. */
        fun clipFile(tapeDir: String, fileName: String): Uri =
            Uri.parse("content://$AUTHORITY/$PATH_CLIP/$tapeDir/$fileName")
    }
}
