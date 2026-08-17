package com.gios.brightrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightrecorder.hw.LightKey
import com.gios.brightrecorder.hw.LightKeys
import com.gios.brightrecorder.hw.LocalWheelBus
import com.gios.brightrecorder.hw.WheelBus
import com.gios.brightrecorder.report.CrashLog
import com.gios.brightrecorder.report.ReportContext
import com.gios.brightrecorder.report.ReportOverlay
import com.gios.brightrecorder.service.TapeController
import com.gios.brightrecorder.ui.ClipsScreen
import com.gios.brightrecorder.ui.NowStrip
import com.gios.brightrecorder.ui.TabBar
import com.gios.brightrecorder.ui.TapeScreen
import com.gios.brightrecorder.ui.theme.BrightRecorderTheme

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /**
     * Every hardware key arrives here first — `DecorView` hands the event to the window callback
     * before it walks the view hierarchy — so a notch reaches the screen that is showing whatever
     * happens to hold focus.
     *
     * Only the turns. The wheel click and the camera button belong to LightControl, which owns
     * them phone-wide and passes bare turns through on purpose.
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
            // Press the wheel in to start and stop the tape. On the way down, so it answers
            // under the thumb, and only on the first event of a press: a held button
            // auto-repeats, and without the guard that would toggle play a dozen times.
            LightKey.WheelClick -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    TapeController.toggle()
                }
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
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
    val labels = remember { listOf("TAPE", "MOMENTS") }

    // Whichever screen is up is the screen a crash report should name.
    ReportContext.screen = if (tab == 0) "tape" else "moments"

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
                else -> ClipsScreen(state)
            }
        }
        // The transport strip only belongs on the list: the tape screen already is one.
        if (tab != 0) NowStrip(state)
        TabBar(selected = tab, labels = labels) { tab = it }
    }
}
