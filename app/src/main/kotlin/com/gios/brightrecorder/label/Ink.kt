package com.gios.brightrecorder.label

import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.gios.brightrecorder.photo.Dither

/**
 * What the pen is loaded with.
 *
 * Three, because the label has three kinds of ground to draw on. A halftoned photograph has as much
 * white in it as black, so a white line disappears into its light half and a black one into its
 * dark half — and grey reads on both, which is why it is the useful one over a picture rather than
 * a third colour for the sake of it.
 *
 * There is no grey on this panel. [Grey] is the halftone at half, in the same pattern and at the
 * same cell size the photographs use, so a grey line over a photograph reads as ink on a photograph
 * rather than as a different material laid over one. See [Dither.greyTile].
 */
enum class Ink(val label: String) {
    White("WHITE"),
    Grey("GREY"),
    Black("BLACK"),
    ;

    fun next(): Ink = entries[(ordinal + 1) % entries.size]
}

/**
 * The one place a stroke's paint is made, so that the preview and the saved label cannot differ.
 *
 * They could before: the preview drew through Compose and the save drew through `android.graphics`,
 * which agreed for solid colours by luck and would not agree at all about a pattern.
 */
object Strokes {

    /**
     * A paint for [ink] at [widthPx], or for a rub-out.
     *
     * A rub-out really removes what it passes over rather than painting black. It has to: the
     * drawing sits above the photograph, so black ink would blot the photograph out instead of
     * revealing it. That is the whole reason erasing is a mode rather than a fourth colour.
     *
     * [tilePx] is how big a halftone cell should be in *this* canvas's pixels. The editor draws the
     * label smaller than it is stored, so passing the label's own cell size would make grey look
     * coarser in the preview than on the tape.
     */
    fun paint(ink: Ink, erase: Boolean, widthPx: Float, tilePx: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = widthPx
            when {
                erase -> {
                    color = android.graphics.Color.TRANSPARENT
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }

                ink == Ink.Grey -> {
                    // A pattern rather than a colour, so the line is made of the same black and
                    // white everything else on this label is.
                    shader = BitmapShader(
                        Dither.greyTile(tilePx.coerceAtLeast(1)),
                        Shader.TileMode.REPEAT,
                        Shader.TileMode.REPEAT,
                    )
                    // Antialiasing a patterned stroke smears its edge into greys the panel cannot
                    // show, which is what makes a dithered line look muddy rather than drawn.
                    isAntiAlias = false
                }

                ink == Ink.Black -> color = android.graphics.Color.BLACK
                else -> color = android.graphics.Color.WHITE
            }
        }
}
