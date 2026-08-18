package com.gios.brightrecorder.photo

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's own photographs, read straight off the filesystem.
 *
 * Ported from BrightChat, which learned the hard part: the system photo picker reads MediaStore,
 * and nothing on LightOS keeps MediaStore current — there is no media provider doing the scanning a
 * normal Android build does — so a photo taken minutes ago simply is not offered. Walking DCIM and
 * Pictures cannot go stale, because the directory listing *is* the source of truth.
 *
 * Stills only here. BrightChat's copy also offers video, which a tape label has no use for, and
 * asking for `READ_MEDIA_VIDEO` to choose a picture would be asking for someone's video library to
 * put a photograph on a cassette.
 */
object Gallery {

    /** The one grant this needs. Stills only — see the class comment. */
    val permission: String = Manifest.permission.READ_MEDIA_IMAGES

    /** One photograph on disk. [takenAt] is the file's mtime, which for a camera roll is the same
     *  thing as when it was taken and does not mean opening every file to sort the grid. */
    data class Photo(val file: File, val takenAt: Long) {
        val key: String get() = file.path
        val name: String get() = file.name
    }

    /**
     * Every image under DCIM and Pictures, newest first.
     *
     * Off-main: this touches the filesystem. Returns empty rather than throwing when the permission
     * is missing, because the caller is showing a prompt in that case anyway.
     */
    suspend fun scan(): List<Photo> = withContext(Dispatchers.IO) {
        roots()
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    // Deep enough for DCIM/Camera and Pictures/Screenshots, not so deep that a
                    // stray folder of assets turns into a long walk.
                    .maxDepth(3)
                    // Dot directories hold the launcher's own cached crops, which are junk here.
                    .onEnter { !it.name.startsWith(".") }
                    .mapNotNull { file ->
                        if (!file.isFile || file.length() <= 0L) return@mapNotNull null
                        // `.trashed-` and `.pending-` are MediaProvider's own bookkeeping and pass
                        // the extension filter otherwise.
                        if (file.name.startsWith(".")) return@mapNotNull null
                        if (file.extension.lowercase() !in EXTENSIONS) return@mapNotNull null
                        Photo(file, file.lastModified())
                    }
                    .toList()
            }
            .sortedByDescending { it.takenAt }
            .take(MAX_ITEMS)
    }

    /** A downsampled thumbnail, cached, or null if the file will not decode. */
    suspend fun thumbnail(photo: Photo): ImageBitmap? {
        thumbnails.get(photo.key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val image = decode(photo.file, THUMB_DIM)?.asImageBitmap() ?: return@withContext null
            thumbnails.put(photo.key, image)
            image
        }
    }

    /**
     * Decode [file] no larger than [maxDim] on its long side.
     *
     * Two passes: bounds first, then a power-of-two `inSampleSize`, which is the only downscale the
     * decoder can do without allocating the full-size bitmap first. A camera JPEG on this phone is
     * twelve megapixels — 48 MB decoded — and a grid of those is an OutOfMemoryError rather than a
     * slow scroll.
     */
    fun decode(file: File, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    /**
     * DCIM and Pictures.
     *
     * `getExternalStoragePublicDirectory` is deprecated in favour of MediaStore, which is precisely
     * the thing that does not work on this phone, so the deprecation is noted and ignored.
     */
    @Suppress("DEPRECATION")
    private fun roots(): List<File> = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
    )

    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")

    /** A ceiling on the grid. The LPIII's roll is not a phone library. */
    private const val MAX_ITEMS = 600

    /** Grid cells are about a third of the panel; 256 is a touch under that and decodes faster. */
    private const val THUMB_DIM = 256

    /** 8 MB of thumbnails, sized in bytes rather than entries — a count-based cache of these
     *  quietly retains tens of megabytes for the life of the process. */
    private val thumbnails = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
}
