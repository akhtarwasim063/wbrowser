package dev.wbrowser.nativeapp

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
import androidx.core.app.NotificationCompat

/**
 * Bridges the background-playback notification's buttons back to whichever tab is currently
 * playing media (MainActivity fills these in from BrowserTab.toggleMediaPlayback/pauseMediaPlayback).
 * The service itself has no reference to a GeckoSession.
 */
object MediaBridge {
    var onToggle: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
}

/**
 * Foreground "media playback" service: keeps the app process (and its GeckoView content) alive and
 * shows a notification with Play/Pause and Stop while a page's audio/video keeps playing after the
 * user backgrounds the app or locks the screen - the same idea as Brave's "Play in background".
 * Started/updated from BrowserTab's media session callbacks (see Tabs.kt / MainActivity.onMediaChanged)
 * and stopped as soon as nothing is playing or the user taps Stop.
 */
class MediaPlaybackService : Service() {
    companion object {
        private const val CHANNEL_ID = "wb_media_playback"
        private const val NOTIF_ID = 4201
        private const val ACTION_UPDATE = "dev.wbrowser.nativeapp.media.UPDATE"
        private const val ACTION_TOGGLE = "dev.wbrowser.nativeapp.media.TOGGLE"
        private const val ACTION_STOP = "dev.wbrowser.nativeapp.media.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"

        /** Start or refresh the notification for the page currently playing. */
        fun update(ctx: Context, title: String, playing: Boolean) {
            val i = Intent(ctx, MediaPlaybackService::class.java).setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PLAYING, playing)
            runCatching { ctx.startForegroundService(i) }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, MediaPlaybackService::class.java)) } }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> MediaBridge.onToggle?.invoke()
            ACTION_STOP -> { MediaBridge.onStop?.invoke(); stopSelf(); return START_NOT_STICKY }
        }
        ensureChannel()
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "WBrowser"
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        val notif = buildNotification(title, playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        if (playing) { acquireWakeLock(); requestAudioFocus() } else releaseWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        abandonAudioFocus()
        super.onDestroy()
    }

    /** Keeps the CPU from sleeping while media plays with the screen off - without this, Doze /
     *  OEM battery managers (common on ColorOS, MIUI, etc.) can stall GeckoView's decode thread
     *  shortly after the screen locks even with the foreground service running. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wbrowser:mediaPlayback").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)   // 6h safety cap; always released on stop/pause
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Holding audio focus signals to Android (and some OEM battery managers) that this is a real,
     *  current media session, which helps it survive backgrounding the same way a music app would. */
    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setWillPauseWhenDucked(false).build()
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
    }

    private fun abandonAudioFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocusRequest(req)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background playback", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while a page keeps playing audio or video in the background" }
            )
        }
    }

    private fun buildNotification(title: String, playing: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            flags,
        )
        fun action(req: Int, act: String) = PendingIntent.getService(this, req, Intent(this, MediaPlaybackService::class.java).setAction(act), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(if (playing) "Playing in background" else "Paused")
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(if (playing) R.drawable.ic_down else R.drawable.ic_up, if (playing) "Pause" else "Play", action(1, ACTION_TOGGLE))
            .addAction(R.drawable.ic_close, "Stop", action(2, ACTION_STOP))
            .build()
    }
}
