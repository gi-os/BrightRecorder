package com.gios.brightrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.gios.brightrecorder.hw.LightKey
import com.gios.brightrecorder.hw.LightKeys
import com.gios.brightrecorder.hw.LocalWheelBus
import com.gios.brightrecorder.hw.Press
import com.gios.brightrecorder.hw.WheelBus
import com.gios.brightrecorder.report.CrashLog
import com.gios.brightrecorder.report.ReportContext
import com.gios.brightrecorder.report.ReportOverlay
import com.gios.brightrecorder.service.TapeController
import com.gios.brightrecorder.ui.ClipsScreen
import com.gios.brightrecorder.ui.NowStrip
import com.gios.brightrecorder.ui.TabBar
import com.gios.brightrecorder.ui.TapeScreen
import com.gios.brightrecorder.ui.TapesScreen
import com.gios.brightrecorder.ui.theme.BrightRecorderTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /** What a press of the wheel means: a tap plays, a hold records. See [Press]. */
    private val press = Press()

    /** The hold timer. Cancelled by the release, so a tap never reaches [Press.held]. */
    private var holding: Job? = null

    /**
     * Every hardware key arrives here first — `DecorView` hands the event to the window callback
     * before it walks the view hierarchy — so a notch reaches the screen that is showing whatever
     * happens to hold focus.
     *
     * The turns and the press. The camera button belongs to LightControl, which owns it
     * phone-wide; from 2.15 it knows this package by name and stands off the whole wheel, which is
     * what lets the press arrive here at all.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            // The wheel is the only control you can work without looking at the screen, so it
            // carries both of the things you do without looking: tap to play or stop, hold to
            // record. What each means is [Press]; this only supplies it with events and a timer.
            //
            // `repeatCount == 0` because a held key auto-repeats, and every repeat is another
            // ACTION_DOWN. The repeats are also why the hold is timed here rather than taken from
            // the first repeat: the repeat delay belongs to the system and is not ours to set.
            LightKey.WheelClick -> {
                when {
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                        act(press.down(recording = TapeController.state.value.isRecording))
                        holding?.cancel()
                        holding = lifecycleScope.launch {
                            delay(Press.HOLD_MS)
                            act(press.held())
                        }
                    }
                    event.action == KeyEvent.ACTION_UP -> {
                        holding?.cancel()
                        act(press.up())
                    }
                }
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    /** Carry out what the press meant. */
    private fun act(what: Press.Act) = when (what) {
        Press.Act.None -> Unit
        Press.Act.Toggle -> TapeController.toggle()
        // Through the same door as the record button, because recording needs the microphone and
        // this is very often the first time anything has asked for it.
        Press.Act.StartRecording -> startRecording()
        Press.Act.StopRecording -> {
            TapeController.finishRecording()
            Unit
        }
    }

    /**
     * The app went away with the wheel still held, so the press will never be released.
     *
     * Without this the hold timer would fire behind whatever came over the top and start a
     * recording nobody asked for, and the stale press would swallow the next release as well.
     */
    override fun onPause() {
        super.onPause()
        holding?.cancel()
        press.cancel()
    }

    /** Start looking for a place name now rather than when record is pressed. See [TapeController.warmPlace]. */
    override fun onResume() {
        super.onResume()
        TapeController.warmPlace()
    }

    /**
     * Recording is the whole app, so the microphone is asked for when record is pressed rather
     * than on first launch. A permission prompt on a cold start is a prompt answered without
     * reading; one that appears the moment you press a record button explains itself.
     */
    private val requestMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) TapeController.record()
        }

    /** Only the shade notification needs this, and recording works fine without it. */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Location is asked for while the first recording is already running.
     *
     * Deliberately not a blocker: a clip with no place is still a clip, so a refusal costs the
     * title its first half and nothing else.
     *
     * The result is acted on, which it originally was not, and that was the bug that made the
     * feature look broken. The lookup starts at the same moment as this prompt, so on the very
     * first recording it runs before any grant exists, finds no permission and gives up in a
     * microsecond. Nothing retried it, so that clip was filed under "Somewhere" — and so was
     * every clip after it, because the grant only got picked up by a later launch. Telling the
     * controller the answer arrived is the whole fix.
     */
    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            TapeController.onLocationPermissionResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, before anything else can throw: the handler chains onto whatever is
        // already installed and only writes a file, so it is safe this early.
        CrashLog.install(this)
        TapeController.attach(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !has(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            // The screen stays awake for as long as the reels are turning.
            //
            // The service already holds a partial wake lock, which keeps the *CPU* going so a
            // recording survives the panel going dark. This is the other half: while you are
            // recording you are holding the phone at something that is happening, watching the
            // level meter and the counter, and having the screen go out under your thumb means
            // waking it to find out whether the recording is still running.
            //
            // A flag on the window rather than a wake lock, because it needs no permission and the
            // system takes it away by itself when the activity goes away — there is no path where
            // this is left on with nothing recording.
            val recording by TapeController.state.collectAsStateWithLifecycle()
            DisposableEffect(recording.isRecording) {
                if (recording.isRecording) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            BrightRecorderTheme {
                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    Root(onRecord = ::startRecording)
                }
                // Shake to report, the crash offer on next launch, and the app's own noticed
                // failures. A sibling, not a wrapper — the sheet is its own window, so it covers
                // the app whether or not it contains it.
                ReportOverlay()
            }
        }
    }

    /**
     * Press record: get the microphone if we do not have it, then start.
     *
     * The location request is fired afterwards and its result ignored, because the recording is
     * already running by then and [com.gios.brightrecorder.place.Places] will pick up the grant
     * whenever it arrives.
     */
    private fun startRecording() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        TapeController.record()
        if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            requestLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun Root(onRecord: () -> Unit) {
    val state by TapeController.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = remember { listOf("TAPE", "MOMENTS", "SHELF") }

    // Whichever screen is up is the screen a crash report should name.
    ReportContext.screen = when (tab) {
        0 -> "tape"
        1 -> "moments"
        else -> "shelf"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxSize()) {
            when (tab) {
                0 -> TapeScreen(state, onNeedMicrophone = onRecord)
                1 -> ClipsScreen(state)
                else -> TapesScreen(state)
            }
        }
        // The transport strip only belongs where the transport is not already on screen.
        if (tab == 1) NowStrip(state)
        TabBar(selected = tab, labels = labels) { tab = it }
    }
}
