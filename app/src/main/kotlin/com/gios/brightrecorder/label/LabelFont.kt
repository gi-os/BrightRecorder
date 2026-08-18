package com.gios.brightrecorder.label

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max

/**
 * The faces a title can be set in.
 *
 * All but one are families Android already has, asked for by name. That is deliberate: a font file
 * is most of a megabyte an app does not need to ship, and `Typeface.create` falls back to the
 * default rather than failing when a phone does not have the family — so a missing face costs the
 * look of the title and nothing else.
 *
 * Comic Sans is not on Android and never has been. `casual` is Coming Soon, which is the same idea
 * done better: an upright, round, informal hand. `cursive` is Dancing Script, which is properly
 * joined up.
 *
 * [Pixel] has no typeface at all, because Android ships no bitmap font. It is drawn small and blown
 * back up with no filtering, which is exactly how a bitmap font looks and exactly what this panel
 * renders best — see [drawPixel].
 */
enum class LabelFont(val label: String) {
    Plain("PLAIN"),
    Serif("SERIF"),
    Typewriter("TYPED"),
    Spaced("SPACED"),
    Heavy("HEAVY"),

    /** Dancing Script, or whatever this phone answers `cursive` with. */
    Cursive("CURSIVE"),

    /** Coming Soon: the round informal hand people mean when they say Comic Sans. */
    Comic("COMIC"),

    /** The same hand, slanted, which reads as somebody writing faster. */
    Hand("HAND"),

    /** Chunky bitmap lettering, drawn rather than set. See [drawPixel]. */
    Pixel("PIXEL"),
    ;

    fun next(): LabelFont = entries[(ordinal + 1) % entries.size]

    /** True when this face is drawn by hand rather than set in a typeface. */
    val isDrawn: Boolean get() = this == Pixel

    fun applyTo(paint: Paint, sizePx: Float) {
        paint.typeface = when (this) {
            Plain -> Typeface.SANS_SERIF
            Serif -> Typeface.SERIF
            Typewriter -> Typeface.MONOSPACE
            Spaced -> Typeface.SANS_SERIF
            Heavy -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            Cursive -> Typeface.create("cursive", Typeface.NORMAL)
            Comic -> Typeface.create("casual", Typeface.NORMAL)
            Hand -> Typeface.create("casual", Typeface.ITALIC)
            // Set in a plain bold face and then destroyed on purpose; see [drawPixel].
            Pixel -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        paint.textSize = sizePx
        paint.textSkewX = if (this == Hand) -0.12f else 0f
        // Letter spacing is in ems, so it survives the size being fitted.
        paint.letterSpacing = when (this) {
            Spaced -> 0.22f
            Pixel -> 0.10f
            else -> 0f
        }
    }

    /** What the title reads as in this face. Capitals are part of a look, not a formatting rule. */
    fun render(title: String): String = when (this) {
        Spaced, Pixel -> title.uppercase()
        else -> title
    }

    /**
     * Draw [text] as bitmap lettering: set small, scaled back up with no filtering.
     *
     * Every glyph becomes blocks the size of the scale factor, which is what a bitmap font is. It
     * has to be done through an offscreen bitmap rather than by scaling the canvas, because the
     * blockiness comes from the *sampling* — a scaled canvas would just draw smooth glyphs bigger.
     */
    private fun drawPixel(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val block = max(2f, paint.textSize / 9f)
        val small = Paint(paint).apply { textSize = paint.textSize / block; letterSpacing = 0.10f }
        val w = max(1, (small.measureText(text) + 2).toInt())
        val metrics = small.fontMetrics
        val h = max(1, (metrics.descent - metrics.ascent + 2).toInt())
        val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawText(text, 1f, -metrics.ascent, small)
        val big = Bitmap.createScaledBitmap(tile, (w * block).toInt(), (h * block).toInt(), false)
        canvas.drawBitmap(big, x, y + metrics.ascent * block, null)
        tile.recycle()
    }

    /** [text] drawn at [x], [y] in this face, whether it is set or drawn. */
    fun draw(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        if (isDrawn) drawPixel(canvas, text, x, y, paint) else canvas.drawText(text, x, y, paint)
    }

    /** How wide [text] is in this face at the size already set on [paint]. */
    fun measure(text: String, paint: Paint): Float = paint.measureText(text)
}
