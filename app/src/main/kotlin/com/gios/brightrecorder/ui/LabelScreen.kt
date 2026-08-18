package com.gios.brightrecorder.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gios.brightrecorder.label.Label
import com.gios.brightrecorder.label.LabelFont
import com.gios.brightrecorder.label.LabelTitle
import com.gios.brightrecorder.photo.Dither
import com.gios.brightrecorder.photo.Gallery
import com.gios.brightrecorder.service.TapeController
import com.gios.brightrecorder.tape.Tape
import com.gios.brightrecorder.ui.theme.Dim
import com.gios.brightrecorder.ui.theme.Faint
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writing on the label.
 *
 * A cassette without something written on it is a cassette you have to play to identify, which is
 * the problem this whole screen exists to solve. A name in a list does some of that; a scrawl in
 * your own handwriting and a photograph of where you were does the rest, and does it at a glance
 * from across the room.
 *
 * ### Drawing, and why the strokes are kept
 *
 * A stroke is a list of points, and the strokes are kept as a list, so **undo is dropping the last
 * one** rather than a stack of bitmaps. That matters on a phone with this much memory: a bitmap
 * undo history at label resolution is a megabyte a step.
 *
 * The strokes are drawn live by Compose in screen coordinates and only flattened to a bitmap when
 * you save. Flattening on every stroke would mean an allocation and a rasterise per finger-lift.
 *
 * ### The photograph is dithered on the way in
 *
 * Once, at pick time, by [Dither] — not at every draw. A photograph on this panel has to become
 * black and white one way or another, and doing it deliberately as a halftone is the difference
 * between a printed-looking label and a grey smear. See [Dither.labelFrom].
 *
 * ### The title is a layer, not ink
 *
 * The tape's name can be set on the label in one of a few faces, and it stays *live*: it is stored
 * as a choice rather than burned into the drawing, so renaming the tape moves the label with it and
 * changing your mind about the face does not mean rubbing the old one out. See [LabelTitle].
 */
@Composable
fun LabelScreen(tape: Tape, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember(tape.dirName) { TapeController.dirOf(tape) }

    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var picking by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(LabelTitle.Title()) }
    var saving by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Every stroke drawn this session, plus whatever was already on the label underneath.
    val strokes = remember { mutableStateListOf<Stroke2D>() }
    var existing by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(dir) {
        if (dir == null) return@LaunchedEffect
        photo = Label.readPhoto(dir)
        existing = Label.readDrawing(dir)
        title = LabelTitle.read(dir)
    }

    BackHandler { if (picking) picking = false else onClose() }

    if (picking) {
        PhotoPickerScreen(
            onPick = { file ->
                picking = false
                scope.launch {
                    photo = usePhoto(dir, file)
                }
            },
            onClose = { picking = false },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenTitle("LABEL — ${tape.name.uppercase()}")

        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), Alignment.Center) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(Label.WIDTH.toFloat() / Label.HEIGHT)
                        .background(Color.Black)
                        .border(1.dp, Faint)
                        .clipToBounds()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(erasing) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                val stroke = Stroke2D(erasing, mutableStateListOf(down.position))
                                strokes.add(stroke)
                                var stillDown = true
                                while (stillDown) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { change ->
                                        if (change.pressed) {
                                            stroke.points.add(change.position)
                                            change.consume()
                                        }
                                    }
                                    stillDown = event.changes.any { it.pressed }
                                }
                            }
                        },
                ) {
                    photo?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    existing?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    StrokeLayer(strokes)
                    if (title.shown && tape.name.isNotBlank()) {
                        TitleLayer(tape.name, title.font)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        erasing -> "Rub out what you drew."
                        title.shown ->
                            "Title set in ${title.font.label.lowercase()} — TYPE again for the next face."
                        else -> "Write on it with a finger."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                )
            }
        }

        Rule()
        Row(Modifier.fillMaxWidth()) {
            TransportKey(glyph = "BACK", modifier = Modifier.weight(1f), onClick = onClose)
            TransportKey(glyph = "PHOTO", modifier = Modifier.weight(1f)) { picking = true }
            // One key for the title: the first press puts the tape's name on it, and every press
            // after walks through the faces until it comes round to off again. An on/off key and a
            // separate font key would be two keys for one decision, and there is not room for two.
            TransportKey(
                glyph = "TYPE",
                held = title.shown,
                enabled = tape.name.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                title = when {
                    !title.shown -> LabelTitle.Title(shown = true, font = LabelFont.Plain)
                    title.font == LabelFont.entries.last() -> LabelTitle.Title(shown = false)
                    else -> title.copy(font = title.font.next())
                }
            }
            TransportKey(
                glyph = if (erasing) "PEN" else "RUB",
                held = erasing,
                modifier = Modifier.weight(1f),
            ) { erasing = !erasing }
            TransportKey(
                glyph = "UNDO",
                enabled = strokes.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { strokes.removeLastOrNull() }
            TransportKey(
                glyph = "SAVE",
                held = true,
                enabled = !saving && canvasSize.width > 0,
                modifier = Modifier.weight(1f),
            ) {
                saving = true
                scope.launch {
                    if (dir != null) {
                        if (strokes.isNotEmpty()) {
                            val flattened = flatten(existing, strokes, canvasSize)
                            if (flattened != null) Label.writeDrawing(dir, flattened)
                        }
                        LabelTitle.write(dir, title)
                        Label.forget(dir)
                    }
                    TapeController.labelChanged()
                    saving = false
                    onClose()
                }
            }
        }
    }
}

/** One continuous mark. [erase] marks it as a rub-out rather than a line of ink. */
private class Stroke2D(val erase: Boolean, val points: MutableList<Offset>)

/**
 * The strokes drawn live, in screen coordinates.
 *
 * White ink, because the label is black. A rub-out is drawn in black over the top rather than by
 * removing anything, which is both what an eraser does on paper and the only thing that can rub out
 * a mark that is part of the bitmap loaded from disk.
 */
@Composable
private fun StrokeLayer(strokes: List<Stroke2D>) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        strokes.forEach { stroke ->
            val path = androidx.compose.ui.graphics.Path().apply {
                stroke.points.firstOrNull()?.let { moveTo(it.x, it.y) }
                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                path = path,
                color = if (stroke.erase) Color.Black else Color.White,
                style = Stroke(
                    width = if (stroke.erase) ERASER_PX else PEN_PX,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

private const val PEN_PX = 6f
private const val ERASER_PX = 28f

/**
 * The strokes burned into a label-sized bitmap, on top of whatever was already there.
 *
 * Screen coordinates are scaled to label coordinates, so the same drawing comes out the same size
 * whatever the panel is. Done on a background thread: it allocates a bitmap the size of the label
 * and rasterises every stroke into it.
 */
private suspend fun flatten(
    existing: ImageBitmap?,
    strokes: List<Stroke2D>,
    canvas: IntSize,
): Bitmap? = withContext(Dispatchers.Default) {
    if (canvas.width <= 0 || canvas.height <= 0) return@withContext null
    val out = Bitmap.createBitmap(Label.WIDTH, Label.HEIGHT, Bitmap.Config.ARGB_8888)
    val target = AndroidCanvas(out)
    existing?.let {
        val old = it.asAndroidBitmap()
        target.drawBitmap(
            old,
            android.graphics.Rect(0, 0, old.width, old.height),
            android.graphics.Rect(0, 0, Label.WIDTH, Label.HEIGHT),
            null,
        )
    }
    val scaleX = Label.WIDTH.toFloat() / canvas.width
    val scaleY = Label.HEIGHT.toFloat() / canvas.height
    val paint = AndroidPaint().apply {
        isAntiAlias = true
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
    }
    strokes.forEach { stroke ->
        if (stroke.points.isEmpty()) return@forEach
        // A rub-out has to actually remove pixels, not paint black ones: the label is drawn over
        // the photograph, so black ink would blot the photograph out instead of revealing it.
        paint.color = if (stroke.erase) android.graphics.Color.TRANSPARENT else
            android.graphics.Color.WHITE
        paint.xfermode = if (stroke.erase) {
            android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        } else {
            null
        }
        paint.strokeWidth = (if (stroke.erase) ERASER_PX else PEN_PX) * scaleX
        val path = AndroidPath().apply {
            val first = stroke.points.first()
            moveTo(first.x * scaleX, first.y * scaleY)
            stroke.points.drop(1).forEach { lineTo(it.x * scaleX, it.y * scaleY) }
        }
        target.drawPath(path, paint)
    }
    out
}

/** Decode, crop, halftone and store a picked photograph. Returns it for the preview. */
private suspend fun usePhoto(dir: File?, file: File): ImageBitmap? {
    if (dir == null) return null
    return withContext(Dispatchers.Default) {
        val decoded = Gallery.decode(file, maxOf(Label.WIDTH, Label.HEIGHT) * 2)
            ?: return@withContext null
        val label = Dither.labelFrom(decoded, Label.WIDTH, Label.HEIGHT)
        Label.writePhoto(dir, label)
        label.asImageBitmap()
    }
}

/**
 * The title as it will appear, drawn over the label at the size the editor is showing it.
 *
 * The same routine the cassette uses, so the face you are choosing between is the one that ends up
 * on the shelf rather than an approximation of it.
 */
@Composable
private fun TitleLayer(text: String, font: LabelFont) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            LabelTitle.draw(
                canvas.nativeCanvas,
                text,
                font,
                size.width.toInt(),
                size.height.toInt(),
            )
        }
    }
}
