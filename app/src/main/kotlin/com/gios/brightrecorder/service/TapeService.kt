package com.gios.brightrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.gios.brightrecorder.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Holds the process up while the reels are turning, and puts a stop control in the shade.
 *
 * The tape itself lives in [TapeController]; this class only keeps the process alive, owns the
 * notification, and hands audio focus back when it goes away. A recording that stops because the
 * screen went dark is a lost moment, and this app exists to catch moments — so the wake lock is
 * not an optimisation here, it is the feature.
 */
class TapeService : Service() {

    companion object {
        const val ACTION_STOP = "com.gios.brightrecorder.STOP"
        private const val CHANNEL = "tape"
        private const val NOTIFICATION_ID = 1
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var refreshJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TapeController.attach(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stop means stop whichever thing is running. Pressing it during a recording has to
            // file the clip rather than discard it — the samples are already on disk either way,
            // but only this path gives them their name.
            if (TapeController.state.value.isRecording) {
                TapeController.finishRecording()
            } else {
                TapeController.stop()
            }
            return START_NOT_STICKY
        }

        promote()
        requestFocus()
        acquireWakeLock()

        // Two things on this notification move: which clip is under the head, and whether we are
        // recording. Both are worth a redraw and nothing else is, so the collector compares
        // exactly those. Rewriting it on every position tick would repost it thirty times a
        // second and wake the process out of Doze each time with the wake lock already held.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            TapeController.state
                .map { it.isRecording to TapeController.nowLabel() }
                .distinctUntilChanged()
                .collect { promote() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        abandonFocus()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not end a recording in progress.
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Go foreground, or change which kind of foreground service this is.
     *
     * The type has to match what the service is doing at that moment. Declaring `microphone`
     * while merely playing back would be claiming a capability the app is not using, and from
     * API 34 the platform checks: a microphone-typed foreground service started without the
     * recording permission granted is a `SecurityException` rather than a warning. So the type
     * follows the transport, and calling `startForeground` again is how it is changed.
     */
    private fun promote() {
        val recording = TapeController.state.value.isRecording
        val type = if (recording) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(), type) }
            .onFailure {
                // Better a service with no notification than a crash mid-recording.
                runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            }
    }

    // ---------------------------------------------------------------- notification

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL,
            "Tape",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val recording = TapeController.state.value.isRecording
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TapeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(TapeController.nowLabel())
            .setContentText(if (recording) "Recording" else "Playing")
            .setSmallIcon(
                if (recording) {
                    android.R.drawable.presence_audio_online
                } else {
                    android.R.drawable.ic_media_play
                },
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (recording) "Stop recording" else "Stop",
                    stop,
                ).build(),
            )
            .build()
    }

    // ------------------------------------------------------------------ audio focus

    private fun requestFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                // Losing focus outright stops playback, but never a recording: whatever took the
                // focus wants the speaker, and a recording is not using it.
                if (change == AudioManager.AUDIOFOCUS_LOSS &&
                    !TapeController.state.value.isRecording
                ) {
                    TapeController.stop()
                }
            }
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BrightRecorder:tape").apply {
            setReferenceCounted(false)
            // A long recording is plausible; a six-hour one is not, and a stuck lock past that
            // would quietly flatten the phone.
            acquire(6 * 60 * 60 * 1000L)
        }
    }
}
