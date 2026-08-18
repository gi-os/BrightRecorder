package com.gios.brightrecorder.label

import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

/**
 * The faces a title can be set in.
 *
 * Deliberately few, and deliberately the ones the phone already has. A font file is half a megabyte
 * an app does not need to ship, and on a panel this size the difference between two similar
 * grotesques is invisible anyway — what reads at arm's length is the difference between a plain
 * face, a serif, a typewriter and something spaced out like a label maker. Those are four decisions
 * you can actually see, which is the point of offering a choice at all.
 */
enum class LabelFont(val label: String) {
    /** The app's own face, and what a title gets if nobody chooses. */
    Plain("PLAIN"),

    /** For a tape of something old, or something being taken seriously. */
    Serif("SERIF"),

    /** A typewriter, which is what a field recording wants to be labelled in. */
    Typewriter("TYPED"),

    /** Spaced-out capitals: a label maker, or the spine of a box file. */
    Spaced("SPACED"),

    /** Loud, for a tape you want to find without looking. */
    Heavy("HEAVY"),
    ;

    fun next(): LabelFont = entries[(ordinal + 1) % entries.size]

    /** Apply this face to [paint]. Everything about a font that is not the glyphs lives here. */
    fun applyTo(paint: Paint, sizePx: Float) {
        paint.typeface = when (this) {
            Plain -> Typeface.SANS_SERIF
            Serif -> Typeface.SERIF
            Typewriter -> Typeface.MONOSPACE
            Spaced -> Typeface.SANS_SERIF
            Heavy -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        paint.textSize = sizePx
        // Letter spacing is in ems, so it does not have to be rescaled when the size is fitted.
        paint.letterSpacing = if (this == Spaced) 0.22f else 0f
    }

    /** What the title reads as in this face. Capitals are part of the look, not a formatting rule. */
    fun render(title: String): String = if (this == Spaced) title.uppercase() else title
}

/**
 * A title written on a label: the words, and the face they are in.
 *
 * Stored as one short text file in the tape's own folder, beside the photograph and the drawing —
 * the same rule as everything else here, so a tape folder copied off the phone carries its whole
 * label with it and there is no index anywhere to fall out of step.
 *
 * Kept as text rather than burned into the drawing on purpose. A title is the one part of a label
 * you change your mind about: rename the tape and the label should follow, try a different face and
 * the previous one should not have to be rubbed out. Burning it in would make both of those
 * destructive.
 */
object LabelTitle {

    private const val FILE = "label-title.txt"

    /** [shown] false means the tape has a name but you have chosen not to put it on the label. */
    data class Title(
        val shown: Boolean = false,
        val font: LabelFont = LabelFont.Plain,
    )

    fun read(tapeDir: File): Title = runCatching {
        val file = File(tapeDir, FILE)
        if (!file.isFile) return Title()
        val lines = file.readLines()
        Title(
            shown = lines.getOrNull(0)?.trim() == "1",
            font = lines.getOrNull(1)?.trim()
                ?.let { name -> LabelFont.entries.firstOrNull { it.name == name } }
                ?: LabelFont.Plain,
        )
    }.getOrDefault(Title())

    fun write(tapeDir: File, title: Title): Boolean = runCatching {
        val file = File(tapeDir, FILE)
        if (!title.shown && title.font == LabelFont.Plain) {
            // Nothing worth storing. Deleting rather than writing a file that says "no" keeps a
            // folder of recordings from accruing empty metadata.
            file.delete()
            return true
        }
        file.writeText("${if (title.shown) 1 else 0}\n${title.font.name}\n")
        true
    }.getOrDefault(false)

    fun clear(tapeDir: File) {
        runCatching { File(tapeDir, FILE).delete() }
    }

    /**
     * Draw [text] across the bottom of a label [width] by [height], shrinking it to fit.
     *
     * Along the bottom because that is where a title goes on an inlay card, and because the top of
     * a label is where a photograph's subject usually is. Shrunk rather than wrapped: a tape name
     * is a few words, and two lines of small type on a label this size is unreadable where one line
     * of large type is not.
     *
     * White with a black shadow under it, so it stays legible over the light parts of a halftoned
     * photograph. That shadow is the only reason this is not simply a `drawText`.
     */
    fun draw(
        canvas: android.graphics.Canvas,
        text: String,
        font: LabelFont,
        width: Int,
        height: Int,
    ) {
        val shown = font.render(text).trim()
        if (shown.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var size = height * 0.26f
        font.applyTo(paint, size)
        val room = width * 0.88f
        // Shrink until it fits, with a floor: below this it is not worth reading and the label is
        // better off showing a name that has been cut short.
        while (paint.measureText(shown) > room && size > height * 0.10f) {
            size -= height * 0.01f
            font.applyTo(paint, size)
        }
        val x = width * 0.06f
        val y = height * 0.94f
        paint.color = android.graphics.Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.18f
        canvas.drawText(shown, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        canvas.drawText(shown, x, y, paint)
    }
}
