package com.gios.brightrecorder

import android.content.Context
import com.gios.brightrecorder.send.Recipients

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
    private const val KEY_PLACE = "lastPlace"
    private const val KEY_RECENTS = "recentRecipients"

    fun currentTape(context: Context): String? =
        sp(context).getString(KEY_TAPE, null)?.takeIf { it.isNotBlank() }

    /**
     * The last real place name this phone found. See `Places.remembered`.
     *
     * A fact about the phone rather than about any tape, which is why it is here and not beside a
     * recording.
     */
    fun lastPlace(context: Context): String? =
        sp(context).getString(KEY_PLACE, null)?.takeIf { it.isNotBlank() }

    fun setLastPlace(context: Context, place: String) {
        sp(context).edit().putString(KEY_PLACE, place).apply()
    }

    fun setCurrentTape(context: Context, dirName: String?) {
        sp(context).edit().apply {
            if (dirName.isNullOrBlank()) remove(KEY_TAPE) else putString(KEY_TAPE, dirName)
        }.apply()
    }

    /**
     * The last few people a moment was sent to, most recent first.
     *
     * Held as address keys rather than as contact ids: a contact id is local to one address-book
     * database and does not survive a restore, so the list would empty itself after a phone swap.
     * The same reasoning as storing a tape by its folder name rather than its position.
     *
     * Newline-joined rather than a `StringSet`, because a set has no order and the order is the
     * entire content of this list.
     */
    fun recentRecipients(context: Context): List<String> =
        sp(context).getString(KEY_RECENTS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun rememberRecipient(context: Context, key: String) {
        if (key.isBlank()) return
        val next = Recipients.remember(recentRecipients(context), key)
        sp(context).edit().putString(KEY_RECENTS, next.joinToString("\n")).apply()
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
