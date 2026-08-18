package com.gios.brightrecorder.label

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What is written on a tape's label, kept in the tape's own folder.
 *
 * Two files, and no index: `label-photo.png` and `label-drawing.png`, beside the recordings they
 * belong to. That is the same rule the rest of the app is built on — a tape is a directory and
 * everything about it is in there, so copying the folder off the phone takes the label with it and
 * there is no second copy of the truth to fall out of step.
 *
 * They are separate files rather than one flattened image so that each can be changed without
 * destroying the other: re-photograph a label and the writing stays, wipe the writing and the
 * photograph stays. Which is how a real cassette label works, more or less — the drawing is on top
 * of whatever is underneath it.
 *
 * ### Fixed size, black and white
 *
 * Both are stored at [WIDTH] by [HEIGHT], already reduced to the two colours the panel has. Doing
 * that once at save time rather than at every draw is what keeps the shelf cheap: a screen showing
 * five cassettes is five bitmap loads and no per-frame image processing.
 *
 * Neither file is ever partly written. Each is saved to a temporary name and renamed into place, so
 * a process killed mid-save leaves the previous label rather than a half-decoded one.
 */
object Label {

    /**
     * Label pixel size.
     *
     * A cassette drawn on this panel is about 340dp across at roughly 2.5x, and the label is most
     * of it — so 640 wide is a shade over one panel pixel per stored pixel, which is enough to
     * carry a drawing without storing a megabyte per tape. The 5:2 ratio is the label window on the
     * cassette drawn in [com.gios.brightrecorder.ui.Cassette].
     */
    const val WIDTH = 640
    const val HEIGHT = 256

    private const val PHOTO = "label-photo.png"
    private const val DRAWING = "label-drawing.png"

    fun photoFile(tapeDir: File): File = File(tapeDir, PHOTO)

    fun drawingFile(tapeDir: File): File = File(tapeDir, DRAWING)

    /** True if this tape has anything on its label at all. Cheap: two `stat` calls. */
    fun has(tapeDir: File): Boolean = photoFile(tapeDir).isFile || drawingFile(tapeDir).isFile

    /** The photograph and the writing, decoded and memoised. See [Art]. */
    suspend fun art(tapeDir: File): Art = withContext(Dispatchers.IO) {
        val key = tapeDir.path
        val stamp = photoFile(tapeDir).lastModified() + drawingFile(tapeDir).lastModified() +
            File(tapeDir, "label-title.txt").lastModified()
        cache[key]?.let { if (it.stamp == stamp) return@withContext it }
        val art = Art(
            photo = read(photoFile(tapeDir)),
            drawing = read(drawingFile(tapeDir)),
            title = LabelTitle.read(tapeDir),
            stamp = stamp,
        )
        cache.put(key, art)
        art
    }

    /**
     * What a label looks like: a photograph behind, writing in front, either or both absent.
     *
     * [stamp] is the two files' modification times added together, and it is what makes the cache
     * safe — a label edited on one screen has to look different on the next one, and comparing a
     * pair of `lastModified` calls is cheaper than decoding two PNGs on every swipe of the shelf.
     */
    data class Art(
        val photo: ImageBitmap? = null,
        val drawing: ImageBitmap? = null,
        val title: LabelTitle.Title = LabelTitle.Title(),
        val stamp: Long = 0L,
    ) {
        /** True when there is nothing on the label at all and the pattern should stand in. */
        val isEmpty: Boolean get() = photo == null && drawing == null && !title.shown
    }

    /**
     * Decoded labels, by tape folder.
     *
     * Small on purpose: the shelf shows one cassette at a time and pre-composes its neighbours, so
     * a handful is the working set, and a label is a third of a megabyte decoded.
     */
    private val cache = LruCache<String, Art>(12)

    suspend fun readPhoto(tapeDir: File): ImageBitmap? = read(photoFile(tapeDir))

    suspend fun readDrawing(tapeDir: File): ImageBitmap? = read(drawingFile(tapeDir))

    private suspend fun read(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
    }

    suspend fun writePhoto(tapeDir: File, bitmap: Bitmap): Boolean =
        write(photoFile(tapeDir), bitmap)

    suspend fun writeDrawing(tapeDir: File, bitmap: Bitmap): Boolean =
        write(drawingFile(tapeDir), bitmap)

    suspend fun clearPhoto(tapeDir: File): Boolean = delete(photoFile(tapeDir))

    suspend fun clearDrawing(tapeDir: File): Boolean = delete(drawingFile(tapeDir))

    /** Everything the label is made of, for when the tape itself is thrown away. */
    fun clearAll(tapeDir: File) {
        runCatching { photoFile(tapeDir).delete() }
        runCatching { drawingFile(tapeDir).delete() }
        LabelTitle.clear(tapeDir)
        cache.remove(tapeDir.path)
    }

    /** Forget a decoded label, for when something other than [write] changed it. */
    fun forget(tapeDir: File) {
        cache.remove(tapeDir.path)
    }

    private suspend fun write(file: File, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Written beside the target and renamed in, so a process killed mid-save leaves the
            // label that was already there rather than half of a new one.
            val temp = File(file.parentFile, "${file.name}.part")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(file)) {
                temp.delete()
                return@runCatching false
            }
            cache.remove(file.parentFile?.path ?: "")
            true
        }.getOrDefault(false)
    }

    private suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching { !file.exists() || file.delete() }.getOrDefault(false)
    }
}
