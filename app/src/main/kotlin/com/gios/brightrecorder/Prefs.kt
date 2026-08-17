package com.gios.brightrecorder

import android.content.Context

/**
 * The little that has to survive being closed.
 *
 * Only which tape is on the machine. Everything else the app knows is on disk already, in the
 * names of folders and files, which is the whole filing system — there is no state worth storing
 * about a clip that its own name does not already carry.
 *
 * The tape is stored as its **folder name** rather than an index, because a shelf reorders when a
 * tape is added and "the second one" would then be a different tape. A folder name that no longer
 * exists — the tape was deleted, or the store was replaced — reads back as null and the app falls
 * back to the first tape on the shelf.
 */
object Prefs {

    private const val FILE = "brightrecorder"
    private const val KEY_TAPE = "tape"

    fun currentTape(context: Context): String? =
        sp(context).getString(KEY_TAPE, null)?.takeIf { it.isNotBlank() }

    fun setCurrentTape(context: Context, dirName: String?) {
        sp(context).edit().apply {
            if (dirName.isNullOrBlank()) remove(KEY_TAPE) else putString(KEY_TAPE, dirName)
        }.apply()
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
