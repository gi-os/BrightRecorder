package com.gios.brightrecorder.photo

import android.graphics.Bitmap
import com.gios.brightrecorder.label.LabelFilter
import com.gios.brightrecorder.label.LabelSpec
import kotlin.math.max

/**
 * A photograph reduced to the two colours this phone has.
 *
 * The LPIII panel is monochrome, so a photograph put on a tape label has to become black and white
 * one way or another — and letting the panel do it produces a smeared grey mush. An ordered Bayer
 * dither instead gives a halftone: a deliberate, printed look that reads as a photograph on a
 * cassette label rather than as a picture that has gone wrong.
 *
 * Ported from BrightChat, where the matrix and the cell arithmetic were already earned.
 */
object Dither {

    /**
     * [src] as pure black and white, in halftone cells [cell] pixels across.
     *
     * The dither is computed at one-cell scale and blown back up with no filtering, so a cell is a
     * solid block rather than a fine pattern the panel would average away to grey. That is the
     * whole trick: the halftone has to be coarser than the display, not finer.
     *
     * [filter] is applied to the grey value *before* the threshold, which is the only place it can
     * usefully go — once a pixel is one of two colours there is nothing left to brighten.
     */
    fun halftone(src: Bitmap, cell: Int = CELL, filter: LabelFilter = LabelFilter.Normal): Bitmap {
        val c = cell.coerceIn(1, 32)
        val w = max(1, src.width / c)
        val h = max(1, src.height / c)
        val small = if (c == 1) {
            src.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createScaledBitmap(src, w, h, true)
        }
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val g = filter.apply(gray(pixels[row + x]))
                val threshold = (BAYER_8[y and 7][x and 7] * 255 + 32) / 64
                pixels[row + x] = if (g > threshold) WHITE else BLACK
            }
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h)
        return if (c == 1) small else Bitmap.createScaledBitmap(small, src.width, src.height, false)
    }

    /**
     * [src] placed on a label [width] by [height], as [spec] says, then filtered and halftoned.
     *
     * Cover, not fit: a label with letterboxing on it looks like a mistake. Where within that cover
     * the picture sits is [LabelSpec.photoX] and [LabelSpec.photoY], and how far into it you are is
     * [LabelSpec.photoScale] — so this is the whole of "move the photograph around".
     *
     * The nudge is clamped to what the picture actually has to give: pushed to its limit an edge
     * lands exactly on the edge of the label and no further, so it is never possible to shove a
     * photograph off its own label and end up with a band of black.
     */
    fun place(src: Bitmap, width: Int, height: Int, spec: LabelSpec): Bitmap {
        val scale = max(width.toFloat() / src.width, height.toFloat() / src.height) * spec.photoScale
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        // What is left over in each direction is how far there is to move.
        val slackX = (w - width).coerceAtLeast(0)
        val slackY = (h - height).coerceAtLeast(0)
        val left = (slackX / 2f + spec.photoX * slackX / 2f).toInt().coerceIn(0, slackX)
        val top = (slackY / 2f + spec.photoY * slackY / 2f).toInt().coerceIn(0, slackY)
        val cropped = Bitmap.createBitmap(
            scaled,
            left,
            top,
            width.coerceAtMost(w),
            height.coerceAtMost(h),
        )
        return halftone(cropped, filter = spec.filter)
    }

    /**
     * A tile of the halftone at exactly half grey, for filling with.
     *
     * There is no grey on this panel, so a grey line has to be a pattern of black and white — and it
     * has to be *the same* pattern the photographs use, or a grey stroke over a halftoned picture
     * reads as two different materials rather than as ink on a photograph. So this is the Bayer
     * matrix at the one threshold a mid-grey pixel would take, blown up by [cell] with no filtering,
     * which is the same treatment [halftone] gives a picture.
     *
     * Cached: a stroke asks for this on every frame it is drawn, and it is the same eight-by-eight
     * decision every time.
     */
    fun greyTile(cell: Int = CELL): Bitmap {
        val c = cell.coerceIn(1, 16)
        tiles[c]?.let { return it }
        val small = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(64)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val threshold = (BAYER_8[y][x] * 255 + 32) / 64
                // Mid grey against this cell's threshold, which is what makes the texture match.
                pixels[y * 8 + x] = if (128 > threshold) WHITE else BLACK
            }
        }
        small.setPixels(pixels, 0, 8, 0, 0, 8, 8)
        val tile = if (c == 1) small else Bitmap.createScaledBitmap(small, 8 * c, 8 * c, false)
        tiles[c] = tile
        return tile
    }

    private val tiles = HashMap<Int, Bitmap>()

    /** Luminance, at the weights the eye actually uses. */
    private fun gray(pixel: Int): Int =
        ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /**
     * Halftone cell size in pixels.
     *
     * Three is the smallest that still reads as a halftone on this panel rather than as noise, and
     * a label is small enough that anything coarser loses the subject.
     */
    private const val CELL = 3

    /** The standard 8×8 Bayer matrix, values 0..63. */
    private val BAYER_8 = arrayOf(
        intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
        intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
        intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
        intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
        intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
        intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
        intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
        intArrayOf(63, 31, 55, 23, 61, 29, 53, 21),
    )
}
