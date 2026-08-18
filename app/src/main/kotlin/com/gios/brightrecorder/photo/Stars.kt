package com.gios.brightrecorder.photo

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The photographs you starred in Roll.
 *
 * A star is the one fact about a photograph that only Roll knows. Everything else — when it was
 * taken, where it is on disk — is on the filesystem, which is why [Gallery] needs no bridge at all.
 * `IS_FAVORITE` exists in MediaStore but is writable in practice only by the system gallery, so
 * Roll keeps its own list and offers it through a read-only provider.
 *
 * **Names, not ids.** A MediaStore id is a row number: rescan the volume, move a file, restore a
 * backup, and the same photograph has a different one. Roll stores stars by filename for exactly
 * that reason, and that is what comes back here — which is also why matching them up with
 * [Gallery.Photo] is a name comparison and not a path one.
 *
 * Roll not being installed is not a failure. The query returns null, the picker hides the filter,
 * and nothing else changes.
 */
object Stars {

    private const val AUTHORITY = "com.gios.lightcamera.stars"
    private const val COLUMN_NAME = "display_name"

    /**
     * The starred filenames, or null if Roll is not installed or has nothing to say.
     *
     * Null rather than an empty set on purpose, because the two mean different things to the
     * picker: nothing to offer means hide the filter, while an empty set means "you have not
     * starred anything yet" and is worth saying out loud.
     */
    suspend fun names(context: Context): Set<String>? = withContext(Dispatchers.IO) {
        runCatching {
            val cursor = context.contentResolver
                .query(Uri.parse("content://$AUTHORITY/stars"), null, null, null, null)
                ?: return@runCatching null
            cursor.use {
                val column = it.getColumnIndex(COLUMN_NAME)
                if (column < 0) return@runCatching null
                val found = mutableSetOf<String>()
                while (it.moveToNext()) {
                    val name = it.getString(column)
                    if (!name.isNullOrBlank()) found.add(name)
                }
                found
            }
        }.getOrNull()
    }
}
