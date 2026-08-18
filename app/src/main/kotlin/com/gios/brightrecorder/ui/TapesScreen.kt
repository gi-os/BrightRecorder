package com.gios.brightrecorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.brightrecorder.hw.WheelNotches
import com.gios.brightrecorder.label.Label
import com.gios.brightrecorder.service.TapeController
import com.gios.brightrecorder.service.TapeState
import com.gios.brightrecorder.tape.Naming
import com.gios.brightrecorder.tape.Tape
import com.gios.brightrecorder.ui.theme.Dim
import com.gios.brightrecorder.ui.theme.Faint

/**
 * The shelf: one cassette at a time, swiped through.
 *
 * A pager rather than a list, because the thing being chosen is a physical object and picking one
 * should feel like sliding the next one into view — and because at this screen size a cassette
 * drawn big enough to recognise by its pattern is most of the panel anyway.
 *
 * Swiping only *looks* at a tape. Loading one onto the machine is a deliberate press, so that
 * browsing the shelf while something is playing does not keep stopping the tape.
 *
 * Each cassette carries whatever is on its label — a photograph, something written on it with a
 * finger, or the [com.gios.brightrecorder.tape.Pattern] when neither. That is the point of the
 * screen: a shelf you pick from by recognising a tape, not by reading a list of names.
 */
@Composable
fun TapesScreen(state: TapeState) {
    val tapes = state.tapes
    if (tapes.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("SHELF")
            EmptyState("No tapes yet.")
        }
        return
    }

    val loadedIndex = tapes.indexOfFirst { it.dirName == state.tape?.dirName }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = loadedIndex) { tapes.size }
    var renaming by remember { mutableStateOf<Tape?>(null) }
    var naming by remember { mutableStateOf(false) }
    var labelling by remember { mutableStateOf<Tape?>(null) }

    // Follow the machine when the tape changes underneath us — loading, creating or deleting one
    // all move which tape is on, and the shelf should be looking at it.
    LaunchedEffect(state.tape?.dirName, tapes.size) {
        val i = tapes.indexOfFirst { it.dirName == state.tape?.dirName }
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
    }

    // The wheel moves along the shelf. One tape per notch — there is nothing continuous here to
    // wind, so this is the one screen where a notch is a step rather than a speed.
    //
    // The notch cannot scroll the pager itself: the handler is not a coroutine and animating is a
    // suspend call. So it records where to go and the effect below does the moving.
    var wheelTarget by remember { mutableStateOf<Int?>(null) }
    WheelNotches(active = renaming == null && !naming && labelling == null) { notches ->
        wheelTarget = (pager.currentPage + if (notches < 0) -1 else 1).coerceIn(0, tapes.size - 1)
    }
    LaunchedEffect(wheelTarget) {
        val target = wheelTarget ?: return@LaunchedEffect
        if (target != pager.currentPage) pager.animateScrollToPage(target)
        wheelTarget = null
    }

    val shown = tapes.getOrNull(pager.currentPage) ?: tapes.first()
    val isLoaded = shown.dirName == state.tape?.dirName

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("SHELF — ${tapes.size} ${if (tapes.size == 1) "TAPE" else "TAPES"}")

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 44.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val tape = tapes[page]
            val dir = remember(tape.dirName) { TapeController.dirOf(tape) }
            // Keyed on the label revision as well as the folder, so a label edited on the screen
            // below is redrawn here rather than served from the decode cache.
            val art by produceState(Label.Art(), dir, state.labelRevision) {
                value = dir?.let { Label.art(it) } ?: Label.Art()
            }
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Cassette(
                    art = art,
                    title = tape.name,
                    pattern = tape.pattern,
                    // How full it looks is how much is on it, against the longest tape on the
                    // shelf — a relative reading, because there is no such thing as a full tape.
                    fill = tape.samples.toFloat() /
                        (tapes.maxOf { it.samples }.coerceAtLeast(1L)).toFloat(),
                    selected = tape.dirName == state.tape?.dirName,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                shown.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(if (shown.isEmpty) "empty" else "${shown.clips} ")
                    if (!shown.isEmpty) {
                        append(if (shown.clips == 1) "moment · " else "moments · ")
                        append(counter(shown.seconds))
                    }
                    append(" · ")
                    append(shown.pattern.label.lowercase())
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (isLoaded) "ON THE MACHINE" else "STARTED ${Naming.whenOnly(shown.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (isLoaded) Color.White else Faint,
            )
            Spacer(Modifier.height(12.dp))
        }

        Rule()
        Row(Modifier.fillMaxWidth()) {
            TransportKey(
                glyph = if (isLoaded) "ON" else "LOAD",
                held = isLoaded,
                enabled = !isLoaded && !state.isRecording,
                modifier = Modifier.weight(1f),
            ) { TapeController.openTape(shown) }
            TransportKey(glyph = "NAME", modifier = Modifier.weight(1f)) { renaming = shown }
            TransportKey(glyph = "LABEL", modifier = Modifier.weight(1f)) { labelling = shown }
            TransportKey(
                glyph = "NEW",
                enabled = !state.isRecording,
                modifier = Modifier.weight(1f),
            ) { naming = true }
        }
    }

    labelling?.let { tape ->
        LabelScreen(tape = tape, onClose = { labelling = null })
        return
    }

    renaming?.let { tape ->
        NameSheet(
            title = "NAME THIS TAPE",
            initial = tape.name,
            canDelete = tape.isEmpty && tapes.size > 1,
            onDelete = {
                TapeController.deleteTape(tape)
                renaming = null
            },
            onCancel = { renaming = null },
            onCyclePattern = { TapeController.cyclePattern(tape) },
            onDone = { name ->
                TapeController.renameTape(tape, name)
                renaming = null
            },
        )
    }

    if (naming) {
        NameSheet(
            title = "NEW TAPE",
            initial = "",
            canDelete = false,
            onDelete = {},
            onCancel = { naming = false },
            onCyclePattern = null,
            onDone = { name ->
                TapeController.newTape(name)
                naming = false
            },
        )
    }
}

/**
 * The one place this app takes typing.
 *
 * Full screen rather than a dialog, for the same reason the delete confirmation is: a Material
 * dialog on this panel is a grey box on black with a hairline nobody can see. Delete lives here
 * too — it is the one place a tape is looked at closely enough to decide, and it is only offered
 * for an empty tape, because [com.gios.brightrecorder.tape.Tapes.delete] will not take one with
 * recordings on it.
 */
@Composable
private fun NameSheet(
    title: String,
    initial: String,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onCyclePattern: (() -> Unit)? = null,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Dim)
        Spacer(Modifier.height(10.dp))
        TextField(
            value = text,
            onValueChange = { text = it.take(40) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Black,
                unfocusedContainerColor = Color.Black,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "A tape for one thing — a trip, a room, a year.",
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth()) {
            TransportKey(glyph = "BACK", modifier = Modifier.weight(1f), onClick = onCancel)
            // The pattern moved off the shelf when the label took its key. It belongs here anyway:
            // a mark is the fallback for a tape nobody has labelled, and this is where you are when
            // you are deciding what a tape is.
            if (onCyclePattern != null) {
                TransportKey(glyph = "MARK", modifier = Modifier.weight(1f), onClick = onCyclePattern)
            }
            if (canDelete) {
                TransportKey(glyph = "DELETE", modifier = Modifier.weight(1f), onClick = onDelete)
            }
            TransportKey(
                glyph = "SAVE",
                held = true,
                enabled = text.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { onDone(text) }
        }
    }
}
