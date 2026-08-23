package com.gios.brightrecorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gios.brightrecorder.Prefs
import com.gios.brightrecorder.hw.WheelScroll
import com.gios.brightrecorder.send.ContactsRepo
import com.gios.brightrecorder.send.Recipient
import com.gios.brightrecorder.send.Recipients
import com.gios.brightrecorder.share.Export
import com.gios.brightrecorder.tape.Clip
import com.gios.brightrecorder.ui.theme.Dim
import com.gios.brightrecorder.ui.theme.Faint
import java.io.File

/**
 * **Who**, not which app.
 *
 * Sending used to fire an intent at BrightChat and stop there, which meant a moment landed in
 * whichever conversation happened to be open — a recording going to somebody nobody chose. The
 * system chooser is no better: it answers "which application?", and on a phone with three of them
 * that is an obvious answer wrapped in a grid, asked instead of the question you actually have.
 *
 * So this owns the address book and hands BrightChat a share that is already addressed, the same
 * way Roll does for photographs. The `address` extra is the AOSP messaging convention; BrightChat
 * reads it, and a share that omits it still works and just waits for a thread.
 *
 * Full screen rather than a sheet, like every other panel here — a Material sheet on this display
 * is a grey box on black with a hairline nobody can see.
 *
 * **A tap chooses; SEND sends.** Two steps for one irreversible act. Tapping a name used to be
 * enough in Roll and a misplaced thumb sent a photograph to the wrong person; there is no unsend,
 * and a recording of somebody's kitchen is not a thing to hand to a stranger by accident.
 */
@Composable
fun SendScreen(clip: Clip, dir: File?, onClose: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    WheelScroll(listState)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    /**
     * **Refused twice means the dialog is gone for good**, and the button that asks for it becomes
     * permanently inert with nothing to explain why. Android reports that only indirectly: after a
     * denial `shouldShowRequestPermissionRationale` goes *false*, which is the system saying it
     * will not ask again. So the button becomes one that opens the app's own settings page, which
     * is the only route left.
     */
    val activity = context as? android.app.Activity
    var blocked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok && activity != null) {
            blocked = !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
        }
    }
    // Granting happens outside this app, so the answer is re-read on the way back rather than
    // waiting for another tap.
    LifecycleResumeEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) blocked = false
        onPauseOrDispose { }
    }

    var all by remember { mutableStateOf<List<Recipient>?>(null) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        all = ContactsRepo(context).load()
    }

    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Recipient?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Back steps out of the choice before it steps out of the picker — one level at a time.
    BackHandler(enabled = true) { if (chosen != null) chosen = null else onClose() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenTitle("SEND — ${clip.place.uppercase()}")

        when {
            !granted -> Column(Modifier.weight(1f)) {
                EmptyState(
                    if (blocked) {
                        "Sending to a person needs the address book, and the prompt has been " +
                            "turned down twice, so Android will not ask again. It can be " +
                            "switched on in this app's settings."
                    } else {
                        "Who is this moment for?\n\nThe address book is read on this phone and " +
                            "never leaves it — it is only how a name becomes a number for " +
                            "BrightChat to send to."
                    },
                )
            }

            all == null -> Column(Modifier.weight(1f)) { EmptyState("Reading the address book…") }

            else -> {
                val ordered = remember(all, query) {
                    val matching = all!!.filter { Recipients.matches(it, query) }
                    Recipients.ordered(matching, Prefs.recentRecipients(context))
                }
                SearchField(query) { query = it }
                Rule()
                LazyColumn(Modifier.weight(1f), state = listState) {
                    if (ordered.recent.isNotEmpty()) {
                        item { SectionHeading("RECENT") }
                        items(ordered.recent, key = { "r" + it.id }) { person ->
                            PersonRow(person, chosen) { chosen = person }
                        }
                    }
                    if (ordered.rest.isNotEmpty()) {
                        if (ordered.recent.isNotEmpty()) item { SectionHeading("EVERYONE") }
                        items(ordered.rest, key = { it.id }) { person ->
                            PersonRow(person, chosen) { chosen = person }
                        }
                    }
                    if (ordered.recent.isEmpty() && ordered.rest.isEmpty()) {
                        item {
                            Text(
                                "Nobody by that name.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Dim,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }

        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Faint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Rule()
        Row(Modifier.fillMaxWidth()) {
            TransportKey(glyph = "BACK", modifier = Modifier.weight(1f), onClick = onClose)
            when {
                blocked -> TransportKey(glyph = "SETTINGS", modifier = Modifier.weight(1f)) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }

                !granted -> TransportKey(glyph = "CONTACTS", modifier = Modifier.weight(1f)) {
                    ask.launch(Manifest.permission.READ_CONTACTS)
                }

                else -> TransportKey(
                    glyph = "SEND",
                    held = true,
                    // A moment can only go to somebody who has a way of receiving it. A person
                    // with only an email address is still offered — BrightChat will not take it,
                    // and the chooser fallback is the honest answer for that.
                    enabled = chosen?.forSound != null && dir != null,
                    modifier = Modifier.weight(1f),
                ) {
                    val person = chosen ?: return@TransportKey
                    val to = person.forSound ?: return@TransportKey
                    val folder = dir ?: return@TransportKey
                    when (val outcome = Export.send(context, folder, clip, to)) {
                        Export.Outcome.Sent -> {
                            Prefs.rememberRecipient(context, to.key)
                            onClose()
                        }
                        // Remembered anyway: the person was chosen, and whether the platform
                        // found an app that reads the address extra is not something the user
                        // did. Not closed, because the recipient is lost in a chooser and they
                        // have to address it again inside whatever they pick.
                        Export.Outcome.Chooser -> {
                            Prefs.rememberRecipient(context, to.key)
                            notice = "BrightChat can't take a sound, so this went to the " +
                                "chooser — you'll have to pick the person again there."
                        }
                        is Export.Outcome.Failed -> notice = outcome.why
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(person: Recipient, chosen: Recipient?, onPick: () -> Unit) {
    ClipRow(
        label = person.name,
        sub = person.subtitle.ifBlank { null },
        selected = person.id == chosen?.id,
        onClick = onPick,
    )
    Rule()
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Faint,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

/**
 * One box for two searches.
 *
 * Letters match the name and digits match the number, because on a phone this size the same box
 * gets both and asking which kind of search you meant is a question with no good answer. See
 * [Recipients.matches] — that is where the rule lives and where it is tested.
 */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(40)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        decorationBox = { field ->
            if (value.isEmpty()) {
                Text("Name or number", style = MaterialTheme.typography.bodyLarge, color = Faint)
            }
            field()
        },
    )
    Spacer(Modifier.height(2.dp))
}
