package com.gios.brightrecorder.label

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gios.brightrecorder.photo.Dither
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What is written on a tape's label, kept in the tape's own folder.
 *
 * Four files, and no index, beside the recordings they belong to:
 *
 *  - `label-source.jpg` — the photograph as picked, in grey, big enough to be moved around in.
 *  - `label-photo.png` — that photograph placed, filtered and halftoned. What the shelf draws.
 *  - `label-drawing.png` — what was drawn on with a finger.
 *  - `label.txt` — where everything sits, which face the title is in, which filter is on.
 *
 * Same rule as the rest of the app: a tape is a directory and everything about it is in there, so
 * copying the folder off the phone takes the label with it and there is no second copy of the truth
 * to fall out of step.
 *
 * ### Why the source is kept as well as the rendered copy
 *
 * Because a photograph can be moved after it has been chosen. Rendering the halftone at pick time
 * and throwing the picture away — which is what the first version did — makes every later decision
 * destructive: nudging it left would halftone an already-halftoned image, and changing the filter
 * would have nothing to change it *from*. Keeping the source costs a couple of hundred kilobytes a
 * tape and makes the label editable for as long as the tape exists.
 *
 * The rendered copy is kept as well rather than composed on demand, because the shelf draws five
 * cassettes and must not do image processing per frame.
 *
 * ### Nothing is ever partly written
 *
 * Each file is saved to a temporary name and renamed into place, so a process killed mid-save
 * leaves the label that was already there rather than half of a new one.
 */
object Label {

    /**
     * Label pixel size.
     *
     * A cassette drawn on this panel is about 300dp across at roughly 2.5x, and the label is most
     * of it — so 800 wide is a shade over one panel pixel per stored pixel, which is enough to
     * carry a drawing without storing a megabyte per tape.
     *
     * The 25:8 ratio is not a taste: it *is* the shape of the label window on the cassette, and
     * [com.gios.brightrecorder.ui.CassetteShape] derives the window from these two numbers rather
     * than the other way round. They used to disagree, and the symptom was a photograph that filled
     * the editor and then sat letterboxed in the middle of the label.
     */
    const val WIDTH = 800
    const val HEIGHT = 256

    /**
     * How big the kept source is, on its long side.
     *
     * Enough to zoom into a corner of it at [LabelSpec.MAX_SCALE] and still have a pixel per label
     * pixel, and no bigger — this is stored per tape.
     */
    private const val SOURCE_DIM = 1600

    private const val SOURCE = "label-source.jpg"
    private const val PHOTO = "label-photo.png"
    private const val DRAWING = "label-drawing.png"

    fun sourceFile(tapeDir: File): File = File(tapeDir, SOURCE)

    fun photoFile(tapeDir: File): File = File(tapeDir, PHOTO)

    fun drawingFile(tapeDir: File): File = File(tapeDir, DRAWING)

    /** True if this tape has anything on its label at all. Cheap: a few `stat` calls. */
    fun has(tapeDir: File): Boolean =
        photoFile(tapeDir).isFile || drawingFile(tapeDir).isFile || File(tapeDir, "label.txt").isFile

    // ------------------------------------------------------------------------ reading

    /**
     * The photograph, the writing and the placement, decoded and memoised.
     *
     * [Art.stamp] is the files' modification times added together, and it is what makes the cache
     * safe: a label edited on one screen has to look different on the next, and comparing a few
     * `lastModified` calls is far cheaper than decoding two PNGs on every swipe of the shelf.
     */
    suspend fun art(tapeDir: File): Art = withContext(Dispatchers.IO) {
        val key = tapeDir.path
        val stamp = photoFile(tapeDir).lastModified() +
            drawingFile(tapeDir).lastModified() +
            File(tapeDir, "label.txt").lastModified()
        cache[key]?.let { if (it.stamp == stamp) return@withContext it }
        val art = Art(
            photo = read(photoFile(tapeDir)),
            drawing = read(drawingFile(tapeDir)),
            spec = LabelSpec.read(tapeDir),
            stamp = stamp,
        )
        cache.put(key, art)
        art
    }

    data class Art(
        val photo: ImageBitmap? = null,
        val drawing: ImageBitmap? = null,
        val spec: LabelSpec = LabelSpec(),
        val stamp: Long = 0L,
    ) {
        /** True when there is nothing on the label and the pattern should stand in for it. */
        val isEmpty: Boolean get() = photo == null && drawing == null && !spec.titleShown
    }

    suspend fun readDrawing(tapeDir: File): ImageBitmap? = read(drawingFile(tapeDir))

    /** The picked photograph as it was picked, for the editor to place and filter. */
    suspend fun readSource(tapeDir: File): Bitmap? = withContext(Dispatchers.IO) {
        val file = sourceFile(tapeDir)
        if (!file.isFile) return@withContext null
        runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    private suspend fun read(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
    }

    // ------------------------------------------------------------------------ writing

    /** Keep [picked] as this tape's source photograph, downscaled to something workable. */
    suspend fun putSource(tapeDir: File, picked: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val scale = SOURCE_DIM.toFloat() / maxOf(picked.width, picked.height)
        val stored = if (scale >= 1f) {
            picked
        } else {
            Bitmap.createScaledBitmap(
                picked,
                (picked.width * scale).toInt().coerceAtLeast(1),
                (picked.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        // JPEG rather than PNG: this is a photograph, it is never drawn directly, and a PNG of one
        // is four times the size for a difference nothing here can see.
        writeFile(sourceFile(tapeDir)) { out -> stored.compress(Bitmap.CompressFormat.JPEG, 88, out) }
    }

    /**
     * Redraw `label-photo.png` from the source, at the placement and filter [spec] describes.
     *
     * Everything the shelf sees about a photograph happens here, once, when something changes —
     * never per frame.
     */
    suspend fun renderPhoto(tapeDir: File, spec: LabelSpec): ImageBitmap? =
        withContext(Dispatchers.Default) {
            val source = readSource(tapeDir) ?: return@withContext null
            val placed = Dither.place(source, WIDTH, HEIGHT, spec)
            val ok = withContext(Dispatchers.IO) {
                writeFile(photoFile(tapeDir)) { out ->
                    placed.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            cache.remove(tapeDir.path)
            if (ok) placed.asImageBitmap() else null
        }

    suspend fun writeDrawing(tapeDir: File, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        writeFile(drawingFile(tapeDir)) { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            .also { cache.remove(tapeDir.path) }
    }

    suspend fun clearPhoto(tapeDir: File): Boolean = withContext(Dispatchers.IO) {
        val gone = runCatching {
            photoFile(tapeDir).delete() or sourceFile(tapeDir).delete()
        }.getOrDefault(false)
        cache.remove(tapeDir.path)
        gone
    }

    suspend fun clearDrawing(tapeDir: File): Boolean = withContext(Dispatchers.IO) {
        val gone = runCatching { drawingFile(tapeDir).delete() }.getOrDefault(false)
        cache.remove(tapeDir.path)
        gone
    }

    /** Everything the label is made of, for when the tape itself is thrown away. */
    fun clearAll(tapeDir: File) {
        runCatching { sourceFile(tapeDir).delete() }
        runCatching { photoFile(tapeDir).delete() }
        runCatching { drawingFile(tapeDir).delete() }
        LabelSpec.clear(tapeDir)
        cache.remove(tapeDir.path)
    }

    /** Forget a decoded label, for when something other than a write here changed it. */
    fun forget(tapeDir: File) {
        cache.remove(tapeDir.path)
    }

    private fun writeFile(file: File, body: (java.io.OutputStream) -> Boolean): Boolean =
        runCatching {
            val temp = File(file.parentFile, "${file.name}.part")
            val wrote = temp.outputStream().use(body)
            if (!wrote || !temp.renameTo(file)) {
                temp.delete()
                return@runCatching false
            }
            true
        }.getOrDefault(false)

    /**
     * Decoded labels, by tape folder.
     *
     * Small on purpose: the shelf shows one cassette at a time and pre-composes its neighbours, so
     * a handful is the working set, and a label is a third of a megabyte decoded.
     */
    private val cache = LruCache<String, Art>(12)
}
