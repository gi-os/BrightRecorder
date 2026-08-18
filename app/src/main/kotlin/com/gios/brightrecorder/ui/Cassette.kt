package com.gios.brightrecorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.gios.brightrecorder.label.Label
import com.gios.brightrecorder.label.LabelTitle
import com.gios.brightrecorder.tape.Pattern
import com.gios.brightrecorder.ui.theme.Faint
import com.gios.brightrecorder.ui.theme.RuleGrey

/**
 * A cassette, drawn.
 *
 * The shelf is a row of these and they have to be told apart at a glance, from the shape of the
 * thing rather than by reading it — which is why the label carries a [Pattern] and why the
 * patterns are coarse. Everything is drawn rather than bitmapped so it stays sharp at whatever
 * size the pager gives it, and because a black-and-white line drawing is what this panel renders
 * best: no anti-aliased greys to smear, no assets to ship.
 *
 * The reels fill the way the real ones do — the left one empties as the right one fills — so a
 * tape that has a lot on it looks different from a fresh one before you have read a word of it.
 *
 * [art] is what has been written on the label: a photograph, something drawn on with a finger, or
 * both. When there is any, it replaces the [Pattern] — the pattern exists to tell tapes apart when
 * nothing else does, and a photograph does that job far better. It is drawn *fitted* inside the
 * label rather than cropped to it, so nothing anybody wrote goes off the edge; the letterboxing is
 * invisible on a black panel.
 */
@Composable
fun Cassette(
    pattern: Pattern,
    fill: Float,
    selected: Boolean,
    modifier: Modifier = Modifier,
    art: Label.Art = Label.Art(),
    title: String = "",
) {
    val edge = if (selected) Color.White else Faint
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = h * 0.06f

        // The shell.
        drawRoundRect(
            color = edge,
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = Stroke(width = if (selected) 3f else 2f),
        )

        // The label, inset, holding the pattern. Its top two-thirds, so the windows below it have
        // room — the same proportions a real inlay card has.
        val label = Rect(
            left = w * 0.08f,
            top = h * 0.10f,
            right = w * 0.92f,
            bottom = h * 0.52f,
        )
        drawRect(
            color = RuleGrey,
            topLeft = Offset(label.left, label.top),
            size = Size(label.width, label.height),
            style = Stroke(width = 1.5f),
        )
        clipRect(label.left, label.top, label.right, label.bottom) {
            if (art.isEmpty) {
                paint(pattern, label, if (selected) Color.White else Faint)
            } else {
                art.photo?.let { fitImage(it, label) }
                art.drawing?.let { fitImage(it, label) }
                if (art.title.shown && title.isNotBlank()) {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.translate(label.left, label.top)
                        LabelTitle.draw(
                            canvas.nativeCanvas,
                            title,
                            art.title.font,
                            label.width.toInt(),
                            label.height.toInt(),
                        )
                        canvas.nativeCanvas.restore()
                    }
                }
            }
        }

        // The two windows, with the tape packs behind them.
        val cy = h * 0.72f
        val rad = h * 0.15f
        val gap = w * 0.19f
        val hub = rad * 0.36f
        val packLeft = hub + (rad - hub) * (1f - fill.coerceIn(0f, 1f))
        val packRight = hub + (rad - hub) * fill.coerceIn(0f, 1f)

        window(Offset(w / 2f - gap, cy), rad, hub, packLeft, edge)
        window(Offset(w / 2f + gap, cy), rad, hub, packRight, edge)

        // The exposed tape along the bottom edge, which is the bit that says "cassette".
        drawLine(
            color = edge,
            start = Offset(w * 0.22f, h * 0.93f),
            end = Offset(w * 0.78f, h * 0.93f),
            strokeWidth = 2f,
        )
    }
}

/** One window: the ring, the pack of tape inside it, and the hub. */
private fun DrawScope.window(
    centre: Offset,
    radius: Float,
    hub: Float,
    pack: Float,
    edge: Color,
) {
    drawCircle(color = edge, radius = radius, center = centre, style = Stroke(width = 1.5f))
    if (pack > hub + 0.5f) {
        drawCircle(color = RuleGrey, radius = pack, center = centre)
    }
    drawCircle(color = edge, radius = hub, center = centre, style = Stroke(width = 1.5f))
}

/**
 * The pattern itself, painted into [area].
 *
 * Every one of these is drawn from the same handful of primitives at a deliberately coarse
 * pitch. Fine hatching and fine dots are indistinguishable from each other at the size a shelf
 * shows, so each pattern differs in *rhythm* — the spacing and the direction — rather than in
 * texture.
 */
private fun DrawScope.paint(pattern: Pattern, area: Rect, ink: Color) {
    val pitch = area.height / 5f
    when (pattern) {
        Pattern.Plain -> Unit

        Pattern.Stripes -> {
            var y = area.top + pitch / 2f
            while (y < area.bottom) {
                drawLine(ink, Offset(area.left, y), Offset(area.right, y), strokeWidth = pitch / 3f)
                y += pitch
            }
        }

        Pattern.Checks -> {
            val n = (area.width / pitch).toInt().coerceAtLeast(1)
            var row = 0
            var y = area.top
            while (y < area.bottom) {
                for (i in 0 until n) {
                    if ((i + row) % 2 == 0) {
                        drawRect(
                            color = ink,
                            topLeft = Offset(area.left + i * pitch, y),
                            size = Size(pitch, minOf(pitch, area.bottom - y)),
                        )
                    }
                }
                y += pitch
                row++
            }
        }

        Pattern.Dots -> {
            var y = area.top + pitch / 2f
            var row = 0
            while (y < area.bottom) {
                var x = area.left + pitch / 2f + if (row % 2 == 0) 0f else pitch / 2f
                while (x < area.right) {
                    drawCircle(ink, radius = pitch / 5f, center = Offset(x, y))
                    x += pitch
                }
                y += pitch
                row++
            }
        }

        Pattern.Grid -> {
            var y = area.top + pitch / 2f
            while (y < area.bottom) {
                drawLine(ink, Offset(area.left, y), Offset(area.right, y), strokeWidth = 1.5f)
                y += pitch / 1.4f
            }
            var x = area.left + pitch / 2f
            while (x < area.right) {
                drawLine(ink, Offset(x, area.top), Offset(x, area.bottom), strokeWidth = 1.5f)
                x += pitch / 1.4f
            }
        }

        Pattern.Lean -> {
            // Diagonals, drawn past both edges so the clip cuts them square at the label border.
            var x = area.left - area.height
            while (x < area.right) {
                drawLine(
                    ink,
                    Offset(x, area.bottom),
                    Offset(x + area.height, area.top),
                    strokeWidth = pitch / 3.5f,
                )
                x += pitch
            }
        }

        Pattern.Waves -> {
            var y = area.top + pitch / 2f
            while (y < area.bottom) {
                val path = Path().apply {
                    moveTo(area.left, y)
                    var x = area.left
                    var up = true
                    while (x < area.right) {
                        val next = (x + pitch / 2f).coerceAtMost(area.right)
                        quadraticTo(
                            (x + next) / 2f,
                            if (up) y - pitch / 3f else y + pitch / 3f,
                            next,
                            y,
                        )
                        up = !up
                        x = next
                    }
                }
                drawPath(path, ink, style = Stroke(width = 2f))
                y += pitch
            }
        }

        Pattern.Chevron -> {
            var y = area.top
            while (y < area.bottom + pitch) {
                val path = Path().apply {
                    var x = area.left
                    moveTo(x, y)
                    var up = true
                    while (x < area.right) {
                        val next = (x + pitch).coerceAtMost(area.right)
                        lineTo(next, if (up) y - pitch / 2f else y + pitch / 2f)
                        up = !up
                        x = next
                    }
                }
                drawPath(path, ink, style = Stroke(width = 2f))
                y += pitch
            }
        }
    }
}

/**
 * Draw [image] as large as it will go inside [into] without cropping or distorting it.
 *
 * Fitted rather than cropped because the label is small and somebody's handwriting runs to its
 * edges — losing the last word of it to a crop would be worse than a band of black above and below,
 * which on this panel cannot be seen at all.
 */
private fun DrawScope.fitImage(image: ImageBitmap, into: Rect) {
    if (image.width <= 0 || image.height <= 0) return
    val scale = minOf(into.width / image.width, into.height / image.height)
    val w = image.width * scale
    val h = image.height * scale
    drawImage(
        image = image,
        dstOffset = IntOffset(
            (into.left + (into.width - w) / 2f).toInt(),
            (into.top + (into.height - h) / 2f).toInt(),
        ),
        dstSize = IntSize(w.toInt(), h.toInt()),
    )
}
