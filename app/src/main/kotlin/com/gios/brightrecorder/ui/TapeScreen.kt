package com.gios.brightrecorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.brightrecorder.hw.WheelNotches
import com.gios.brightrecorder.service.TapeController
import com.gios.brightrecorder.service.TapeState
import com.gios.brightrecorder.tape.SAMPLES_PER_SECOND
import com.gios.brightrecorder.tape.Transport
import com.gios.brightrecorder.ui.theme.Dim
import com.gios.brightrecorder.ui.theme.Faint
import com.gios.brightrecorder.ui.theme.RuleGrey
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The front of the machine: what is under the head, the reels, and the keys.
 *
 * Laid out in the order you use it. The title of whatever the head is sitting on is at the top
 * because that is the question you are asking when you pick the phone up; the counter is next
 * because it is the one thing you watch while recording; the reels are in the middle where the
 * eye rests; and the keys are at the bottom under a thumb.
 */
@Composable
fun TapeScreen(state: TapeState, onNeedMicrophone: () -> Unit) {
    // Turning the wheel moves the tape for as long as you keep turning, at a speed that follows
    // how fast you turn. It does not change the transport — scroll while playing and it is still
    // playing — so the wind keys do not blink and playback does not stop and restart under it.
    WheelNotches(active = !state.isRecording) { notches ->
        TapeController.scrubFromWheel(if (notches < 0) -1 else 1)
    }

    Column(Modifier.fillMaxSize()) {
        Header(state)
        Rule()

        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Counter(state)
                Spacer(Modifier.height(18.dp))
                Reels(state)
                Spacer(Modifier.height(18.dp))
                if (state.isRecording) LevelMeter(state.level) else TapePosition(state)
            }
        }

        Rule()
        Keys(state, onNeedMicrophone)
    }
}

/** Which tape is on, what the head is sitting on, and where the next recording will be filed. */
@Composable
private fun Header(state: TapeState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.isRecording -> "RECORDING"
                    state.isEmpty -> "BLANK TAPE"
                    // While a wind key is held, say what letting go will do. Four releases of this
                    // not working were four releases of having to guess, and the machine knows.
                    state.resumeTo.isNotEmpty() ->
                        "${speedLabel(state.rate)}  \u2192 ${state.resumeTo}"
                    else -> speedLabel(state.rate)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.isRecording) Color.White else Dim,
            )
            Spacer(Modifier.weight(1f))
            // Which tape this is going onto. Small and to the right, because you only look at it
            // when you are about to record — but you must be able to look, or a recording lands on
            // whichever tape happened to be loaded and you find out weeks later.
            Text(
                state.tape?.name.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                state.isRecording -> state.place
                // The blank tape is the one moment someone needs telling how to record, and the
                // wheel gesture is the one that cannot be discovered by looking at the screen.
                state.isEmpty -> "Press record, or hold the wheel"
                else -> state.clip?.title ?: ""
            },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The big counter.
 *
 * Shows time into the current clip rather than into the whole tape. The tape is a continuous
 * thing to *listen* to, but it is a pile of separate moments to think about, and "four minutes
 * into the market in Palermo" is a position you can hold in your head where "one hour fifty into
 * the tape" is not.
 */
@Composable
private fun Counter(state: TapeState) {
    val seconds = if (state.isRecording) state.recorded / SAMPLES_PER_SECOND else state.intoClip
    Text(
        counter(seconds),
        style = MaterialTheme.typography.displaySmall,
        color = Color.White,
    )
}

/**
 * Two reels that turn at the speed of the tape, in the direction of travel.
 *
 * This is the only ornament in the app and it is here because it is the readout that needs no
 * reading. Rewinding looks like rewinding. A hand-shuttle looks like a hand on a reel. Speed is
 * legible as speed without a number being parsed, which matters on a panel you are glancing at
 * while something is happening in front of you.
 *
 * The pack radii move with position — the left reel empties as the right one fills, the way a
 * reel-to-reel does — so how far through the tape you are is visible from across a room.
 */
@Composable
private fun Reels(state: TapeState) {
    var angle by remember { mutableFloatStateOf(0f) }
    // Recording moves the tape forward at exactly 1x, and the reels have to say so. They did not:
    // the engine publishes a rate of zero while the microphone owns the audio path — correctly,
    // because nothing is being *played* — and the reels were reading that as a stopped machine. So
    // the one moment you most want to see the tape moving was the one moment it sat still.
    val turning = if (state.isRecording) 1f else state.rate
    val rate by rememberUpdatedState(turning)
    val moving = abs(turning) > 0.02f

    // Driven by frames rather than by an animation: the angle is the integral of a rate that
    // changes continuously, which no keyframe animation expresses. The effect only exists while
    // something is turning, so a stopped machine is not asking for frames it does not need.
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            // 120 degrees a second at 1x: a third of a turn, fast enough to read as motion and
            // slow enough that the spokes do not strobe against the frame rate at 4x.
            angle = (angle + rate * 120f * dt) % 360f
        }
    }

    // While recording, the right reel fills with what is being captured rather than with where the
    // head is — the head does not move during a recording, and a reel that does not fill while you
    // are filling it is the wrong picture. Against ten minutes, which is longer than a moment and
    // short enough that a few seconds visibly moves it.
    val fill = when {
        state.isRecording ->
            (state.recorded / SAMPLES_PER_SECOND / RECORD_REEL_SECONDS).coerceIn(0f, 1f)
        state.total > 0 -> state.position.toFloat() / state.total
        else -> 0f
    }

    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val cy = size.height / 2f
        val flange = minOf(size.height / 2f - 4f, size.width / 5f)
        val gap = size.width / 4.4f
        val left = Offset(size.width / 2f - gap, cy)
        val right = Offset(size.width / 2f + gap, cy)

        val hub = flange * 0.28f
        // Supply reel empties, take-up reel fills. Never quite to the hub, because a reel wound
        // right down to the spindle looks like a rendering fault rather than an empty reel.
        val packLeft = hub + (flange - hub) * (1f - fill) * 0.92f
        val packRight = hub + (flange - hub) * fill * 0.92f

        reel(left, flange, hub, packLeft, angle)
        reel(right, flange, hub, packRight, angle)

        // The tape path between them, passing the head.
        drawLine(
            color = RuleGrey,
            start = Offset(left.x, cy + flange),
            end = Offset(right.x, cy + flange),
            strokeWidth = 2f,
        )
    }
}

/** One reel: flange, tape pack, hub, and three spokes that show it turning. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.reel(
    centre: Offset,
    flange: Float,
    hub: Float,
    pack: Float,
    angle: Float,
) {
    drawCircle(color = RuleGrey, radius = flange, center = centre, style = Stroke(width = 2f))
    if (pack > hub + 1f) {
        drawCircle(color = Color(0xFF3A3A3A), radius = pack, center = centre)
    }
    drawCircle(color = Color.White, radius = hub, center = centre, style = Stroke(width = 3f))

    // Three spokes rather than one mark: a single mark on a fast reel is a strobe that appears to
    // change direction, three make the direction unambiguous at every speed this app reaches.
    for (i in 0 until 3) {
        val a = Math.toRadians((angle + i * 120f).toDouble())
        val inner = hub * 0.4f
        drawLine(
            color = Color.White,
            start = Offset(
                centre.x + (cos(a) * inner).toFloat(),
                centre.y + (sin(a) * inner).toFloat(),
            ),
            end = Offset(
                centre.x + (cos(a) * hub * 2.1f).toFloat(),
                centre.y + (sin(a) * hub * 2.1f).toFloat(),
            ),
            strokeWidth = 3f,
        )
    }
}

/**
 * Where the head is on the whole tape, with a tick for every clip boundary.
 *
 * The ticks are the point. Without them this is a progress bar for a single long file, and the
 * thing you actually want to know while winding is how many moments away the one you are looking
 * for is.
 */
@Composable
private fun TapePosition(state: TapeState) {
    if (state.isEmpty) {
        Text(
            "Nothing recorded yet",
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
        )
        return
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(18.dp),
    ) {
        val y = size.height / 2f
        drawLine(RuleGrey, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)

        val total = state.total.coerceAtLeast(1L)
        var at = 0L
        for (clip in state.clips) {
            at += clip.samples
            val x = size.width * (at.toFloat() / total)
            drawLine(Faint, Offset(x, y - 4f), Offset(x, y + 4f), strokeWidth = 2f)
        }

        val hx = size.width * (state.position.toFloat() / total)
        drawLine(Color.White, Offset(hx, 0f), Offset(hx, size.height), strokeWidth = 3f)
    }
}

/**
 * Recording level.
 *
 * Blocks rather than a needle, for the same reason the other Light apps use them: a thin line on
 * a matte greyscale panel is invisible at arm's length, and while recording this is being read
 * from a metre away with the phone propped against something.
 */
@Composable
private fun LevelMeter(level: Float) {
    val segments = 20
    val lit = (level * segments).toInt().coerceIn(0, segments)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(segments) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(if (i < lit) Color.White else Color(0xFF303030)),
            )
        }
    }
}

/**
 * The keys.
 *
 * Rewind and fast-forward are momentary: they wind while held and hand the tape straight back to
 * whatever it was doing when released, so winding out of the middle of a clip and letting go
 * carries on playing without a second press. Play doubles as stop, because a separate stop key
 * would sit unused nine times out of ten and these targets are big.
 */
@Composable
private fun Keys(state: TapeState, onNeedMicrophone: () -> Unit) {
    val hasTape = !state.isEmpty
    Row(Modifier.fillMaxWidth()) {
        HoldKey(
            glyph = "<<",
            held = state.transport == Transport.Rewinding,
            enabled = hasTape && !state.isRecording,
            modifier = Modifier.weight(1f),
            onPress = { TapeController.beginWind(back = true) },
            onRelease = { TapeController.endWind() },
        )
        TransportKey(
            glyph = if (state.transport == Transport.Playing) "||" else ">",
            held = state.transport == Transport.Playing,
            enabled = hasTape && !state.isRecording,
            modifier = Modifier.weight(1f),
        ) {
            TapeController.toggle()
        }
        HoldKey(
            glyph = ">>",
            held = state.transport == Transport.FastForwarding,
            enabled = hasTape && !state.isRecording,
            modifier = Modifier.weight(1f),
            onPress = { TapeController.beginWind(back = false) },
            onRelease = { TapeController.endWind() },
        )
        TransportKey(
            // A filled circle for record, the one symbol on a tape machine nobody has to learn.
            glyph = if (state.isRecording) "■" else "●",
            held = state.isRecording,
            modifier = Modifier.weight(1f),
        ) {
            if (state.isRecording) TapeController.finishRecording() else onNeedMicrophone()
        }
    }
}

/** How much recording fills the reel on screen. Longer than a moment, short enough to see move. */
private const val RECORD_REEL_SECONDS = 600f
