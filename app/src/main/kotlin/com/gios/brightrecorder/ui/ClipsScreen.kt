package com.gios.brightrecorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.brightrecorder.hw.WheelScroll
import com.gios.brightrecorder.service.TapeController
import com.gios.brightrecorder.service.TapeState
import com.gios.brightrecorder.tape.Clip
import com.gios.brightrecorder.tape.Naming
import com.gios.brightrecorder.tape.SAMPLES_PER_SECOND
import com.gios.brightrecorder.ui.theme.Dim
import com.gios.brightrecorder.ui.theme.Faint

/**
 * Everything on the tape, in the order it was recorded.
 *
 * Recording order and not newest-first, which is the unusual choice here. A list of files wants
 * the newest at the top, but this is not a list of files — it is the tape, and the tape runs one
 * way. Playing the fourth row and letting it run takes you into the fifth, so the fifth has to be
 * underneath the fourth or the list is lying about what the machine will do.
 *
 * Tapping a row moves the head to the start of that clip. It does not start playing: on a
 * greyscale panel with no headphones plugged in, a tap that suddenly makes noise is a tap people
 * learn not to make.
 */
@Composable
fun ClipsScreen(state: TapeState) {
    val listState = rememberLazyListState()
    WheelScroll(listState)

    var pendingDelete by remember { mutableStateOf<Clip?>(null) }
    var renaming by remember { mutableStateOf<Clip?>(null) }

    if (state.isEmpty) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("TAPE")
            EmptyState(
                "Nothing on ${state.tape?.name ?: "this tape"} yet.\n" +
                    "Press record on the front panel.",
            )
        }
        return
    }

    val here = state.clip

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            "${state.tape?.name?.uppercase() ?: "TAPE"} — " +
                "${state.clips.size} ${if (state.clips.size == 1) "MOMENT" else "MOMENTS"}",
        )
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(state.clips, key = { it.fileName }) { clip ->
                val selected = clip.fileName == here?.fileName
                Box(
                    Modifier.pointerInput(clip.fileName) {
                        detectTapGestures(
                            onTap = { TapeController.seekToClip(clip) },
                            // A hold opens the moment rather than deleting it. Deleting used to be
                            // the hold, which put the one irreversible action in the app behind the
                            // easiest gesture to make by accident — and left renaming with nowhere
                            // to live. It is a key on the sheet now, still behind a confirmation.
                            onLongPress = { renaming = clip },
                        )
                    },
                ) {
                    ClipRow(
                        label = clip.place,
                        sub = Naming.whenOnly(clip.startedAt),
                        selected = selected,
                        trailing = counter(clip.seconds),
                        onClick = { TapeController.seekToClip(clip) },
                    )
                }
                Rule()
            }
        }
    }

    renaming?.let { clip ->
        MomentSheet(
            clip = clip,
            onCancel = { renaming = null },
            onDelete = {
                renaming = null
                pendingDelete = clip
            },
            onDone = { name ->
                TapeController.renameClip(clip, name)
                renaming = null
            },
        )
        return
    }

    pendingDelete?.let { clip ->
        ConfirmDelete(
            clip = clip,
            onCancel = { pendingDelete = null },
            onConfirm = {
                TapeController.delete(clip)
                pendingDelete = null
            },
        )
    }
}

/**
 * The one destructive confirmation in the app.
 *
 * A full-screen panel rather than a Material dialog: a dialog on this panel is a grey box on a
 * black background with a hairline border nobody can see, and the two buttons end up small enough
 * to hit the wrong one. This fills the screen, names the clip being destroyed, and puts cancel
 * first because that is the answer most of the time.
 */
@Composable
private fun ConfirmDelete(clip: Clip, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("DELETE", style = MaterialTheme.typography.labelSmall, color = Dim)
        Text(
            clip.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(top = 10.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${counter(clip.seconds)} of tape. This cannot be undone.",
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
            modifier = Modifier.padding(top = 10.dp, bottom = 26.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            TransportKey(glyph = "KEEP", modifier = Modifier.weight(1f), onClick = onCancel)
            TransportKey(
                glyph = "DELETE",
                held = true,
                modifier = Modifier.weight(1f),
                onClick = onConfirm,
            )
        }
    }
}

/** The strip above the tab bar: what the head is on, wherever you are in the app. */
@Composable
fun NowStrip(state: TapeState) {
    Column {
        Rule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.isRecording) "REC" else speedLabel(state.rate),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.isRecording) Color.White else Faint,
            )
            Text(
                if (state.isEmpty) "" else "  ${state.clip?.place ?: ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                counter(
                    if (state.isRecording) state.recorded / SAMPLES_PER_SECOND else state.intoClip,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/**
 * What a moment is called.
 *
 * A clip is named for where and when it was recorded, and most of the time that is the right name
 * and nobody should have to think about it. But the place is a *guess* about where you were, and
 * sometimes the useful name is not a street at all — "Ada's first word" is a better label for a
 * moment than "Rue de Lappe, Paris" is, and only you know which.
 *
 * So the place is editable and the time is not. The timestamp is what puts the tape in order, and a
 * tape that reordered itself because you renamed something would be a different kind of object.
 *
 * Full screen for the same reason every other panel in this app is: a Material dialog on this
 * display is a grey box on black with a hairline nobody can see.
 */
@Composable
private fun MomentSheet(
    clip: Clip,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(clip.place) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("NAME THIS MOMENT", style = MaterialTheme.typography.labelSmall, color = Dim)
        Spacer(Modifier.height(10.dp))
        TextField(
            value = text,
            onValueChange = { text = it.take(60) },
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
            "Recorded ${Naming.whenOnly(clip.startedAt)}. The time stays as it is — it is what keeps " +
                "the tape in order.",
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth()) {
            TransportKey(glyph = "BACK", modifier = Modifier.weight(1f), onClick = onCancel)
            TransportKey(glyph = "DELETE", modifier = Modifier.weight(1f), onClick = onDelete)
            TransportKey(
                glyph = "SAVE",
                held = true,
                enabled = text.isNotBlank() && text != clip.place,
                modifier = Modifier.weight(1f),
            ) { onDone(text) }
        }
    }
}
