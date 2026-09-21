package com.gios.brightrecorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gios.brightrecorder.service.TapeController
import com.gios.brightrecorder.tape.Clip
import com.gios.brightrecorder.ui.theme.Dim
import com.gios.brightrecorder.ui.theme.Faint

/**
 * "What was that?", asked in a strip rather than on a screen.
 *
 * **The first version filled the panel, and that was the wrong shape for the place this happens.**
 * You are at the thing you just recorded. The next moment worth recording is thirty seconds away
 * and you cannot see the record key, because a full-screen sheet is between you and it — so the
 * prompt that exists to make a recording more findable was making the next one impossible. A bar
 * over the bottom of the transport keeps every key reachable: start recording again and this
 * simply gets out of the way, with the clip still filed under its automatic name.
 *
 * The clip is **already saved** by the time this appears, which is what lets it be this casual.
 * Ignore it, walk away from it, press record through it — nothing is lost, because skipping is
 * the state the app was in before anyone thought to ask.
 *
 * No back handler, and no dimming behind it. Both would make this modal, and it is deliberately
 * not: a modal costs you the moment while you dismiss it.
 *
 * The field is empty rather than pre-filled with the guess. Pre-filling makes the automatic name
 * look like something to delete before you can type, which is friction on the one path that
 * should be free; the guess is named in the placeholder instead, so skipping is a choice.
 *
 * ### The one thing it is pre-filled with
 *
 * The last name you typed. Moments come in runs — nine clips from one rehearsal — and this
 * keyboard makes typing "Rehearsal" nine times the most expensive thing in the app. So once a
 * moment has been named, the strip opens with that name already in it, and the key reads NAME:
 * one press files the clip under it. It stays until you delete it, and deleting it is **one**
 * Backspace, not one per letter: the first deletion on a pre-filled name empties the field and
 * forgets the preset, and the strip is back to the empty field with the guess behind it. Typing
 * onto the end of the preset keeps it, which is how "Rehearsal" becomes "Rehearsal Act 2".
 */
@Composable
fun NameMomentStrip(clip: Clip, onSkip: () -> Unit, onName: (String) -> Unit) {
    val preset = remember(clip.fileName) { TapeController.presetName() }
    var text by remember(clip.fileName) { mutableStateOf(preset ?: "") }
    // True until the first edit. The first edit is what decides between "clear it all" and
    // "keep typing".
    var untouched by remember(clip.fileName) { mutableStateOf(preset != null) }

    // Deliberately no focusRequester. Opening the keyboard would cover the transport again and
    // undo the whole point of the strip — you tap the field if you want to type, and until you
    // do, the machine is exactly as usable as it was a second ago.
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Rule()
        Spacer(Modifier.height(8.dp))
        Text("WHAT WAS THAT?", style = MaterialTheme.typography.labelSmall, color = Dim)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { typed ->
                    if (untouched && typed.length < text.length) {
                        // One Backspace on a preset takes the whole thing, and the preset with it.
                        text = ""
                        TapeController.clearPresetName()
                    } else {
                        text = typed.take(60)
                    }
                    untouched = false
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (text.isNotBlank()) onName(text) else onSkip() },
                ),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                decorationBox = { field ->
                    if (text.isEmpty()) {
                        Text(
                            clip.place,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Faint,
                        )
                    }
                    field()
                },
            )
            TransportKey(
                glyph = if (text.isBlank()) "KEEP" else "NAME",
                held = text.isNotBlank(),
                modifier = Modifier.width(96.dp),
            ) {
                if (text.isBlank()) onSkip() else onName(text)
            }
        }
    }
}
