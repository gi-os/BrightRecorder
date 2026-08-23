package com.gios.brightrecorder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gios.brightrecorder.tape.Clip
import com.gios.brightrecorder.tape.Naming
import com.gios.brightrecorder.ui.theme.Dim
import com.gios.brightrecorder.ui.theme.Faint

/**
 * "What was that?", asked once, straight after recording.
 *
 * The clip is **already filed** under its automatic name by the time this appears, and that is
 * the whole shape of it: this is an offer, never a step. Skipping leaves exactly what would have
 * been there without the prompt, so nothing can be lost by dismissing it, ignoring it, or the
 * phone being taken out of your hand.
 *
 * Asked here rather than left for later because the name is worth most at the moment you stop.
 * "Rue de Lappe, Paris" is where you were and the app can work that out; "Ada's first word" is
 * what it *was*, and thirty seconds later you are the only one who still knows. Finding the clip
 * in the list and holding it does the same job and always did — hardly anyone ever does.
 *
 * The field starts empty rather than pre-filled with the guess. Pre-filling makes the automatic
 * name look like something you have to delete before you can type, which is friction on the one
 * path that should be free; leaving it blank makes typing the only thing to do and SKIP the
 * obvious way out. What the guess was is shown underneath instead, so skipping is an informed
 * choice rather than a shrug.
 */
@Composable
fun NameMomentSheet(clip: Clip, onSkip: () -> Unit, onName: (String) -> Unit) {
    var text by remember(clip.fileName) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(clip.fileName) { runCatching { focus.requestFocus() } }

    // Back is a skip, not a trap. The recording is safe either way.
    BackHandler(enabled = true) { onSkip() }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("WHAT WAS THAT?", style = MaterialTheme.typography.labelSmall, color = Dim)
        Spacer(Modifier.height(10.dp))
        TextField(
            value = text,
            onValueChange = { text = it.take(60) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (text.isNotBlank()) onName(text) else onSkip() },
            ),
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
            "Filed as \"${clip.place}\", ${Naming.whenOnly(clip.startedAt)}. Skip and it stays " +
                "that way — you can rename it any time by holding it in the list.",
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth()) {
            TransportKey(glyph = "SKIP", modifier = Modifier.weight(1f), onClick = onSkip)
            TransportKey(
                glyph = "NAME",
                held = true,
                enabled = text.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                onName(text)
            }
        }
    }
}
