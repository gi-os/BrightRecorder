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

    if (state.isEmpty) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("TAPE")
            EmptyState("Nothing on the tape yet.\nPress record on the front panel.")
        }
        return
    }

    val here = state.clip

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("TAPE — ${state.clips.size} ${if (state.clips.size == 1) "MOMENT" else "MOMENTS"}")
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(state.clips, key = { it.fileName }) { clip ->
                val selected = clip.fileName == here?.fileName
                Box(
                    Modifier.pointerInput(clip.fileName) {
                        detectTapGestures(
                            onTap = { TapeController.seekToClip(clip) },
                            // Long press rather than a swipe or an edit mode: these recordings
                            // cannot be made again, so deleting one should take a deliberate hold
                            // and then a second confirmation.
                            onLongPress = { pendingDelete = clip },
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
