package com.gios.brightrecorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.brightrecorder.ui.theme.Dim
import com.gios.brightrecorder.ui.theme.Faint
import com.gios.brightrecorder.ui.theme.RuleGrey
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun Rule(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier = modifier, color = RuleGrey, thickness = 1.dp)

@Composable
fun ScreenTitle(text: String) {
    Column {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Dim,
            modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp),
        )
        Rule()
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Full-width tappable row. Selection inverts the whole row rather than tinting it —
 * on a greyscale matte panel an inversion is the only state change that reads at
 * arm's length in a dark room.
 */
@Composable
fun ClipRow(
    label: String,
    sub: String? = null,
    selected: Boolean = false,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val fg = if (selected) Color.Black else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Color.White else Color.Black)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) Color(0xFF444444) else Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}

/** Bottom tab bar in the LightOS action-bar idiom: the active tab is bracketed. */
@Composable
fun TabBar(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Column {
        Rule()
        Row(
            Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (active) "[ $label ]" else label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) Color.White else Faint,
                    )
                }
            }
        }
    }
}

/**
 * A wind key: it winds for exactly as long as it is held down.
 *
 * Momentary rather than latching, which is the whole difference between this and a media player.
 * On the machine this imitates you hold rewind, and the instant you let go the tape carries on
 * doing what it was doing before. A latching button cannot do that — with a latch you have to
 * watch for the tape arriving and then press play yourself.
 *
 * `tryAwaitRelease` returning false means the gesture was cancelled rather than lifted, which is
 * a finger sliding off the key. Both end the wind; anything else leaves the tape winding with
 * nothing holding it.
 */
@Composable
fun HoldKey(
    glyph: String,
    held: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val fg = when {
        !enabled -> Faint
        held -> Color.Black
        else -> Color.White
    }
    Box(
        modifier
            .height(56.dp)
            .background(if (held && enabled) Color.White else Color.Black)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    onPress()
                    tryAwaitRelease()
                    onRelease()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

/**
 * A transport key.
 *
 * Deliberately large and deliberately square. These are the buttons on the front of the machine
 * and they get pressed without looking — while the phone is in one hand and the moment is
 * happening in front of you — so each one is a 56dp target with a symbol big enough to identify
 * by shape alone. `held` inverts, which is how a mechanical key that has latched down reads.
 */
@Composable
fun TransportKey(
    glyph: String,
    held: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fg = when {
        !enabled -> Faint
        held -> Color.Black
        else -> Color.White
    }
    Box(
        modifier
            .height(56.dp)
            .background(if (held && enabled) Color.White else Color.Black)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

/**
 * Time as a tape counter: `1:04:12`, or `04:12` under an hour.
 *
 * Hours are dropped when there are none rather than padded with `0:`, because the counter is
 * read at a glance and a leading zero hour is a digit that never changes taking up the space
 * where the minutes should be.
 */
fun counter(seconds: Float): String {
    val total = abs(seconds).roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }
}

/**
 * How the current speed reads on the panel: `PLAY`, `REW 4.0x`, `>> 1.4x`.
 *
 * The number is shown whenever the tape is not at exactly 1x, because when you are shuttling by
 * hand the only feedback that tells you what the wheel is doing is this line.
 */
fun speedLabel(rate: Float): String {
    val magnitude = abs(rate)
    return when {
        magnitude < 0.02f -> "STOP"
        rate < 0 -> "<< " + String.format("%.1fx", magnitude)
        magnitude in 0.98f..1.02f -> "PLAY"
        else -> ">> " + String.format("%.1fx", magnitude)
    }
}
