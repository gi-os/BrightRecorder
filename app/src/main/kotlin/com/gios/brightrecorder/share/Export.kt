package com.gios.brightrecorder.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
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
 */
object Export {

    /** BrightChat's package, which is also the one this app knows how to aim at. See the manifest. */
    private const val BRIGHTCHAT = "com.gios.lightchat"

    /**
     * The intent that shares [clip], or null if the file cannot be handed out.
     *
     * [address] is the recipient when one is known, following the AOSP messaging convention that
     * BrightChat already reads — passed through so a clip can eventually go to a person rather than
     * to whichever thread is open. Left empty it waits for a conversation to be opened, which is
     * BrightChat's own behaviour for a share with no address.
     */
    fun intentFor(context: Context, dir: File, clip: Clip, address: String = ""): Intent? {
        val uri = uriFor(context, dir, clip) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            // The mime the other end branches on. `audio/x-wav` and not `audio/wav`: it is what
            // Messages and the BlueBubbles server label a WAV, and what BrightChat's own table
            // expects to be handed. See its MediaKind.
            type = "audio/x-wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The title as it reads on screen — "Bastille, Paris at 17 Aug 2026, 14:32" — so a clip
            // arriving somewhere with no idea what a tape is still says where and when it was.
            putExtra(Intent.EXTRA_SUBJECT, clip.title)
            putExtra(Intent.EXTRA_TITLE, clip.title)
            if (address.isNotBlank()) putExtra("address", address)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * True if BrightChat is installed.
     *
     * Asked of the package manager rather than assumed, and it needs the `<queries>` entry in the
     * manifest to answer at all on API 30 and up.
     */
    fun brightChatInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(BRIGHTCHAT, 0) != null
    }.getOrDefault(false)

    /**
     * Send [clip] to BrightChat if it is installed, and offer a chooser if it is not.
     *
     * Returns false when there is nothing to send it with, so the caller can say so rather than
     * leaving a key that appears to do nothing.
     */
    fun send(context: Context, dir: File, clip: Clip, address: String = ""): Boolean {
        val intent = intentFor(context, dir, clip, address) ?: return false
        return runCatching {
            if (brightChatInstalled(context)) {
                context.startActivity(Intent(intent).setPackage(BRIGHTCHAT))
            } else {
                context.startActivity(Intent.createChooser(intent, "Send this moment"))
            }
            true
        }.getOrDefault(false)
    }

    private fun uriFor(context: Context, dir: File, clip: Clip): Uri? = runCatching {
        val file = File(dir, clip.fileName)
        if (!file.isFile) return null
        FileProvider.getUriForFile(context, "${context.packageName}.clips", file)
    }.getOrNull()
}
