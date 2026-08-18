package com.gios.brightrecorder.label

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * A title, drawn where the label says it goes.
 *
 * The one routine both the editor and the shelf call, so that the face and the position you choose
 * are the ones that end up on the cassette rather than an approximation of them.
 *
 * White with a black outline under it, always. A title sits over a halftoned photograph, and a
 * halftone has as much white in it as black — plain white text vanishes into the light half of a
 * picture and plain black into the dark half. The outline is the only reason a title stays readable
 * wherever it is moved to.
 */
object LabelTitle {

    fun draw(
        canvas: Canvas,
        text: String,
        spec: LabelSpec,
        width: Int,
        height: Int,
    ) {
        val shown = spec.font.render(text).trim()
        if (shown.isEmpty() || width <= 0 || height <= 0) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var size = height * spec.titleSize
        spec.font.applyTo(paint, size)
        // Shrink to fit rather than wrap: a tape name is a few words, and two lines of small type
        // on a label this size is unreadable where one line of large type is not.
        val room = width * 0.94f
        while (spec.font.measure(shown, paint) > room && size > height * 0.06f) {
            size -= height * 0.01f
            spec.font.applyTo(paint, size)
        }

        val textWidth = spec.font.measure(shown, paint)
        val x = width * spec.titleX - textWidth / 2f
        val y = height * spec.titleY

        canvas.save()
        canvas.rotate(spec.titleAngle, width * spec.titleX, y)
        if (spec.font.isDrawn) {
            // The bitmap face carries no outline — it is drawn from a bitmap, not stroked — so it
            // gets a slab of black behind it instead, which suits the look anyway.
            val metrics = paint.fontMetrics
            paint.color = Color.BLACK
            canvas.drawRect(
                x - size * 0.14f,
                y + metrics.ascent - size * 0.10f,
                x + textWidth + size * 0.14f,
                y + metrics.descent * 0.6f + size * 0.10f,
                paint,
            )
            paint.color = Color.WHITE
            spec.font.draw(canvas, shown, x, y, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.18f
            paint.color = Color.BLACK
            canvas.drawText(shown, x, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawText(shown, x, y, paint)
        }
        canvas.restore()
    }
}
