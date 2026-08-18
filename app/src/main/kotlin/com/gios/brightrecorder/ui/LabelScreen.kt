package com.gios.brightrecorder.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gios.brightrecorder.label.Label
import com.gios.brightrecorder.label.LabelSpec
import com.gios.brightrecorder.label.LabelTitle
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
 * the problem this screen exists to solve. A name in a list does some of that; a scrawl in your own
 * handwriting and a photograph of where you were does the rest, at a glance from across the room.
 *
 * ### Three tools, because one row of keys cannot hold them all
 *
 * The label is one thing but there are three ways to change it, and each wants different keys and a
 * different meaning for a finger dragged across the label. So there is a [Tool] — DRAW, PHOTO,
 * TEXT — and the keys and the gesture both follow it. Without that the bottom of the screen would
 * be eleven keys wide and a drag would have to guess what you meant.
 *
 * - **DRAW** — a finger draws. INK swaps black for white, RUB rubs out, UNDO takes back a stroke.
 * - **PHOTO** — a finger moves the picture, two fingers zoom it. FILTER cycles how it is graded.
 * - **TEXT** — a finger moves the title, two fingers size and turn it. FACE cycles the face.
 *
 * ### What is a stroke and what is a placement
 *
 * Strokes are kept as strokes, so **undo is dropping the last one** rather than a stack of bitmaps —
 * which at label resolution would be a megabyte a step. They are flattened into the drawing only on
 * save.
 *
 * Everything else — where the picture sits, how far into it you are, where the title is, how big,
 * how turned, which face, which filter — is a number in [LabelSpec], not pixels. That is what makes
 * all of it reversible: nudging a photograph re-renders it from the picture you picked rather than
 * from the halftoned copy, and changing your mind about a face rubs nothing out.
 */
@Composable
fun LabelScreen(tape: Tape, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dir = remember(tape.dirName) { TapeController.dirOf(tape) }

    var spec by remember { mutableStateOf(LabelSpec()) }
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var existing by remember { mutableStateOf<ImageBitmap?>(null) }
    var hasSource by remember { mutableStateOf(false) }

    var tool by remember { mutableStateOf(Tool.Draw) }
    var picking by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var blackInk by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val strokes = remember { mutableStateListOf<Stroke2D>() }

    LaunchedEffect(dir) {
        val d = dir ?: return@LaunchedEffect
        spec = LabelSpec.read(d)
        existing = Label.readDrawing(d)
        hasSource = Label.sourceFile(d).isFile
        photo = if (hasSource) Label.renderPhoto(d, spec) else null
    }

    // Re-render whenever the placement or the grade changes. Cheap enough to do live: it is one
    // scale and one threshold pass over 800x256, not a decode.
    LaunchedEffect(spec.photoScale, spec.photoX, spec.photoY, spec.filter, hasSource) {
        val d = dir ?: return@LaunchedEffect
        if (hasSource) photo = Label.renderPhoto(d, spec)
    }

    BackHandler { if (picking) picking = false else onClose() }

    if (picking) {
        PhotoPickerScreen(
            onPick = { file ->
                picking = false
                scope.launch {
                    val d = dir ?: return@launch
                    if (keepSource(d, file)) {
                        hasSource = true
                        // A newly chosen picture starts square on, whatever the last one was left at.
                        spec = spec.copy(photoScale = 1f, photoX = 0f, photoY = 0f)
                        photo = Label.renderPhoto(d, spec)
                    }
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
                LabelCanvas(
                    tape = tape,
                    spec = spec,
                    photo = photo,
                    existing = existing,
                    strokes = strokes,
                    tool = tool,
                    erasing = erasing,
                    blackInk = blackInk,
                    onSpec = { spec = it },
                    onSize = { canvasSize = it },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    hint(tool, erasing, blackInk, spec, hasSource),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Rule()
        // Which tool is in hand. Always on screen, because the same drag means three things and
        // there has to be somewhere that says which.
        Row(Modifier.fillMaxWidth()) {
            Tool.entries.forEach { each ->
                TransportKey(
                    glyph = each.label,
                    held = tool == each,
                    modifier = Modifier.weight(1f),
                ) {
                    tool = each
                    erasing = false
                }
            }
        }
        Rule()
        Row(Modifier.fillMaxWidth()) {
            TransportKey(glyph = "BACK", modifier = Modifier.weight(1f), onClick = onClose)
            when (tool) {
                Tool.Draw -> {
                    TransportKey(
                        glyph = if (blackInk) "BLACK" else "WHITE",
                        held = blackInk,
                        enabled = !erasing,
                        modifier = Modifier.weight(1f),
                    ) { blackInk = !blackInk }
                    TransportKey(
                        glyph = "RUB",
                        held = erasing,
                        modifier = Modifier.weight(1f),
                    ) { erasing = !erasing }
                    TransportKey(
                        glyph = "UNDO",
                        enabled = strokes.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { strokes.removeLastOrNull() }
                }

                Tool.Photo -> {
                    TransportKey(glyph = "PICK", modifier = Modifier.weight(1f)) { picking = true }
                    TransportKey(
                        glyph = spec.filter.label,
                        enabled = hasSource,
                        modifier = Modifier.weight(1f),
                    ) { spec = spec.copy(filter = spec.filter.next()) }
                    TransportKey(
                        glyph = "CLEAR",
                        enabled = hasSource,
                        modifier = Modifier.weight(1f),
                    ) {
                        scope.launch {
                            dir?.let { Label.clearPhoto(it) }
                            hasSource = false
                            photo = null
                            spec = spec.copy(photoScale = 1f, photoX = 0f, photoY = 0f)
                        }
                    }
                }

                Tool.Text -> {
                    TransportKey(
                        glyph = if (spec.titleShown) "OFF" else "ON",
                        held = spec.titleShown,
                        enabled = tape.name.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { spec = spec.copy(titleShown = !spec.titleShown) }
                    TransportKey(
                        glyph = spec.font.label,
                        enabled = spec.titleShown,
                        modifier = Modifier.weight(1f),
                    ) { spec = spec.copy(font = spec.font.next()) }
                    TransportKey(
                        glyph = "STRAIGHT",
                        enabled = spec.titleShown,
                        modifier = Modifier.weight(1f),
                    ) { spec = spec.copy(titleAngle = 0f, titleX = 0.5f, titleY = 0.82f) }
                }
            }
            TransportKey(
                glyph = "SAVE",
                held = true,
                enabled = !saving && canvasSize.width > 0,
                modifier = Modifier.weight(1f),
            ) {
                saving = true
                scope.launch {
                    val d = dir
                    if (d != null) {
                        if (strokes.isNotEmpty()) {
                            flatten(existing, strokes, canvasSize)?.let { Label.writeDrawing(d, it) }
                        }
                        LabelSpec.write(d, spec)
                        if (hasSource) Label.renderPhoto(d, spec)
                        Label.forget(d)
                    }
                    TapeController.labelChanged()
                    saving = false
                    onClose()
                }
            }
        }
    }
}

/** Which of the three things a finger on the label is doing. */
private enum class Tool(val label: String) {
    Draw("DRAW"),
    Photo("PHOTO"),
    Text("TEXT"),
}

private fun hint(
    tool: Tool,
    erasing: Boolean,
    blackInk: Boolean,
    spec: LabelSpec,
    hasSource: Boolean,
): String = when {
    tool == Tool.Draw && erasing -> "Rub out what you drew."
    tool == Tool.Draw -> "Draw with a finger, in ${if (blackInk) "black" else "white"}."
    tool == Tool.Photo && !hasSource -> "PICK a photograph to put behind the label."
    tool == Tool.Photo -> "Drag to move it, pinch to zoom. Graded ${spec.filter.label.lowercase()}."
    spec.titleShown -> "Drag to move the title, pinch to size and turn it. ${spec.font.label}."
    else -> "Turn the title ON to put this tape's name on its label."
}

/**
 * The label itself, at exactly the shape it will be on the shelf.
 *
 * The aspect comes from [Label], and the cassette's window is derived from the same two numbers, so
 * what is composed here is what ends up on the tape — which it was not before: the editor drew a
 * 2.5:1 canvas and the cassette had a 4:1 window, so a photograph filled this and then sat
 * letterboxed on the shelf.
 */
@Composable
private fun LabelCanvas(
    tape: Tape,
    spec: LabelSpec,
    photo: ImageBitmap?,
    existing: ImageBitmap?,
    strokes: MutableList<Stroke2D>,
    tool: Tool,
    erasing: Boolean,
    blackInk: Boolean,
    onSpec: (LabelSpec) -> Unit,
    onSize: (IntSize) -> Unit,
) {
    val specNow by rememberUpdatedState(spec)
    val onSpecNow by rememberUpdatedState(onSpec)
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(Label.WIDTH.toFloat() / Label.HEIGHT)
            .background(Color.Black)
            .border(1.dp, Faint)
            .clipToBounds()
            .onSizeChanged {
                size = it
                onSize(it)
            }
            // Keyed on the tool so that switching tools rebuilds the gesture with the right
            // meaning — a drag is a stroke, a pan, or moving the title, and never two of those.
            .pointerInput(tool, erasing, blackInk) {
                when (tool) {
                    Tool.Draw -> awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val stroke = Stroke2D(erasing, blackInk, mutableStateListOf(down.position))
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

                    Tool.Photo -> transformGesture { pan, zoom, _ ->
                        val current = specNow
                        val w = size.width.coerceAtLeast(1)
                        val h = size.height.coerceAtLeast(1)
                        onSpecNow(
                            current
                                .withPhotoScale(current.photoScale * zoom)
                                // Dragging right should move the picture right, which means moving
                                // the window the other way — hence the minus.
                                .withPhotoAt(
                                    current.photoX - pan.x / w * 2f,
                                    current.photoY - pan.y / h * 2f,
                                ),
                        )
                    }

                    Tool.Text -> transformGesture { pan, zoom, turn ->
                        val current = specNow
                        val w = size.width.coerceAtLeast(1)
                        val h = size.height.coerceAtLeast(1)
                        onSpecNow(
                            current
                                .withTitleAt(current.titleX + pan.x / w, current.titleY + pan.y / h)
                                .withTitleSize(current.titleSize * zoom)
                                .copy(titleAngle = current.titleAngle + turn),
                        )
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
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        StrokeLayer(strokes)
        if (spec.titleShown && tape.name.isNotBlank()) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    LabelTitle.draw(
                        canvas.nativeCanvas,
                        tape.name,
                        spec,
                        this.size.width.toInt(),
                        this.size.height.toInt(),
                    )
                }
            }
        }
    }
}

/**
 * Pan, zoom and rotate, reported as they happen.
 *
 * Compose has `detectTransformGestures`, and this is it with one difference that matters here: it
 * reports a one-finger drag as pan from the first movement, with no slop to cross. On a label this
 * small a gesture that has to travel before it starts feels like the thing is stuck.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.transformGesture(
    onChange: (pan: Offset, zoom: Float, rotation: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val cancelled = event.changes.any { it.isConsumed }
            if (!cancelled) {
                val zoom = event.calculateZoom()
                val rotation = event.calculateRotation()
                val pan = event.calculatePan()
                if (zoom != 1f || rotation != 0f || pan != Offset.Zero) {
                    onChange(pan, zoom, rotation)
                }
                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
            }
        } while (!cancelled && event.changes.any { it.pressed })
    }
}

/** One continuous mark. */
private class Stroke2D(
    val erase: Boolean,
    val black: Boolean,
    val points: MutableList<Offset>,
)

/**
 * The strokes drawn live, in screen coordinates.
 *
 * A rub-out is drawn in black here and *removes* pixels when it is flattened. On screen that is the
 * same thing, because what is under it in the preview is the label's own black.
 */
@Composable
private fun StrokeLayer(strokes: List<Stroke2D>) {
    Canvas(Modifier.fillMaxSize()) {
        strokes.forEach { stroke ->
            val path = androidx.compose.ui.graphics.Path().apply {
                stroke.points.firstOrNull()?.let { moveTo(it.x, it.y) }
                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                path = path,
                color = if (stroke.erase || stroke.black) Color.Black else Color.White,
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
 * whatever the panel is. Done off the main thread: it allocates a bitmap the size of the label and
 * rasterises every stroke into it.
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
        // A rub-out has to actually remove pixels rather than paint black ones: the drawing sits
        // over the photograph, so black ink would blot the photograph out instead of revealing it.
        // Black *ink*, on the other hand, really is black, and is how you draw on a light picture.
        paint.xfermode = if (stroke.erase) {
            android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        } else {
            null
        }
        paint.color = when {
            stroke.erase -> android.graphics.Color.TRANSPARENT
            stroke.black -> android.graphics.Color.BLACK
            else -> android.graphics.Color.WHITE
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

/** Decode a picked file and keep it as this tape's source photograph. */
private suspend fun keepSource(dir: File, file: File): Boolean = withContext(Dispatchers.Default) {
    val decoded = Gallery.decode(file, 1600) ?: return@withContext false
    Label.putSource(dir, decoded)
}
