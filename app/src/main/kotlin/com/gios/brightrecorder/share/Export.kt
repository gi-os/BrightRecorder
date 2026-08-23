package com.gios.brightrecorder.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.gios.brightrecorder.send.Address
import com.gios.brightrecorder.tape.Clip
import java.io.File

/**
 * Sending a moment somewhere else.
 *
 * A recording lives in this app's private storage, so a share cannot pass a path — the receiving app
 * has no permission to read it. A [FileProvider] issues a `content://` URI and the read grant rides
 * on the intent, one clip at a time, revoked when the receiver is finished with it.
 *
 * ### Straight to BrightChat when it is there
 *
 * BrightChat declares itself a share target for any audio type, so an explicit intent aimed at its
 * package opens it on a conversation with the clip attached — no chooser, no picking the app out of
 * a list every time. A chooser is what you get when it is not installed, which is the right answer
 * for anything else that can take a sound.
 *
 * Both go through the same intent otherwise, which matters: `EXTRA_STREAM` plus a read grant is the
 * whole contract, and BrightChat gets no special treatment beyond being named.
 *
 * ### Who, not which app
 *
 * A send with no [Address] lands in whichever conversation BrightChat happens to open, which is a
 * moment going somewhere nobody chose. The picker in `ui/SendScreen.kt` is what fills that in, and
 * the `address` extra it produces is the same AOSP messaging convention Roll uses for photographs.
 */
object Export {

    /** BrightChat's package, which is also the one this app knows how to aim at. See the manifest. */
    private const val BRIGHTCHAT = "com.gios.lightchat"

    /**
     * The mime the other end branches on.
     *
     * `audio/x-wav` and not `audio/wav`: it is what Messages and the BlueBubbles server label a
     * WAV, and what BrightChat's own table expects to be handed. See its MediaKind.
     */
    const val MIME = "audio/x-wav"

    /** The SMS convention. Not `Intent.EXTRA_EMAIL`, which is where an email address goes. */
    private const val EXTRA_ADDRESS = "address"

    /**
     * The intent that shares [clip], or null if the file cannot be handed out.
     *
     * Two things here are load-bearing and neither is obvious.
     *
     * **`ClipData` as well as the extra.** `FLAG_GRANT_READ_URI_PERMISSION` grants the URI in the
     * intent's `data` and every URI in its `ClipData` — it does *not* walk `EXTRA_STREAM`. A photo
     * send can survive that oversight, because the receiver holds `READ_MEDIA_IMAGES` and can read
     * the MediaStore row under its own permission. A recording cannot: it is an app-private file
     * with no MediaStore row, so the grant is the only way in and omitting it means every send
     * arrives unreadable.
     *
     * **`FLAG_ACTIVITY_NEW_TASK`.** This is started from a sheet, and without it the receiving
     * activity lands on top of this app's task — back from BrightChat then returns here rather
     * than to wherever the user came from.
     *
     * [to] is the recipient when one is known, and goes into the extra that matches its kind:
     * `address` is the SMS convention and means nothing to a mail client, so putting an email
     * address in it hands a messaging app something it will try to text. Left null the clip waits
     * for a conversation to be opened, which is BrightChat's own behavior for an unaddressed share.
     */
    fun intentFor(context: Context, dir: File, clip: Clip, to: Address? = null): Intent? {
        val uri = uriFor(context, dir, clip) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            // The title as it reads on screen — "Bastille, Paris at 17 Aug 2026, 14:32" — so a clip
            // arriving somewhere with no idea what a tape is still says where and when it was.
            putExtra(Intent.EXTRA_SUBJECT, clip.title)
            putExtra(Intent.EXTRA_TITLE, clip.title)
            when (to?.kind) {
                Address.Kind.Phone -> putExtra(EXTRA_ADDRESS, to.raw)
                Address.Kind.Email -> putExtra(Intent.EXTRA_EMAIL, arrayOf(to.raw))
                null -> Unit
            }
            // A real resolver, not null: `newUri` asks it for the URI's mime type and throws on a
            // content:// URI without one.
            clipData = ClipData.newUri(context.contentResolver, clip.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Whether BrightChat is installed **and** will currently accept a sound.
     *
     * Asked of the resolver with the real action and type, not of `getPackageInfo`. "Installed"
     * and "registered for `audio/x-wav`" are different questions, and answering the first while
     * meaning the second is how you get an `ActivityNotFoundException` on a phone that plainly has
     * BrightChat on it — with the chooser fallback never offered, because the code already decided
     * it was going to BrightChat.
     *
     * Needs the `<queries>` entry in the manifest to answer at all on API 30 and up.
     */
    fun brightChatCanReceive(context: Context): Boolean {
        val probe = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            setPackage(BRIGHTCHAT)
        }
        return runCatching {
            context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .isNotEmpty()
        }.getOrDefault(false)
    }

    /** What happened, in enough detail for the sheet to say something true. */
    sealed interface Outcome {
        /** Opened BrightChat with the clip attached, addressed if there was an address. */
        data object Sent : Outcome

        /**
         * BrightChat could not take it, so the system chooser was opened. The recipient is lost
         * here — no other app reads the `address` extra reliably — and the user has to address it
         * again inside whatever they pick.
         */
        data object Chooser : Outcome

        /** Nothing happened. [why] is worth showing. */
        data class Failed(val why: String) : Outcome
    }

    /**
     * Send [clip] to [to] through BrightChat, falling back to a chooser.
     *
     * The failure that used to be invisible is the first branch: a clip whose file is missing, or
     * whose URI the provider will not issue, returns null from [intentFor] and is reported rather
     * than swallowed.
     */
    fun send(context: Context, dir: File, clip: Clip, to: Address? = null): Outcome {
        val intent = intentFor(context, dir, clip, to)
            ?: return Outcome.Failed("That recording isn't on the phone any more.")
        if (brightChatCanReceive(context)) {
            val explicit = Intent(intent).setPackage(BRIGHTCHAT)
            if (runCatching { context.startActivity(explicit) }.isSuccess) return Outcome.Sent
        }
        val chooser = Intent.createChooser(intent, "Send this moment")
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching { context.startActivity(chooser) }
            .fold(
                onSuccess = { Outcome.Chooser },
                onFailure = { Outcome.Failed("Nothing on this phone can take a sound.") },
            )
    }

    private fun uriFor(context: Context, dir: File, clip: Clip): Uri? = runCatching {
        val file = File(dir, clip.fileName)
        if (!file.isFile) return null
        FileProvider.getUriForFile(context, "${context.packageName}.clips", file)
    }.getOrNull()
}
