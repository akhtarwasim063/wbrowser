package dev.wbrowser.nativeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/** Everything the notification, the lock screen and the mini-player need to know about the page that is playing. */
data class MediaInfo(
    val title: String, val artist: String, val album: String, val artwork: Bitmap?,
    val playing: Boolean, val positionMs: Long, val durationMs: Long, val rate: Float, val features: Long,
)

/**
 * Connects the notification / lock-screen / mini-player buttons back to the tab that is playing
 * (MainActivity fills these in from BrowserTab). The service itself has no reference to a GeckoSession.
 */
object MediaBridge {
    /** What to show; null = nothing is playing and the notification goes away. */
    var info: MediaInfo? = null
    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null
    var onSeekBy: ((Long) -> Unit)? = null
    fun toggle() { if (info?.playing == true) onPause?.invoke() else onPlay?.invoke() }
}

/**
 * Foreground "media playback" service. It owns an Android MediaSession, so the phone shows proper media controls
 * (play/pause, seek bar, previous/next, artwork) in the notification shade and on the lock screen, and keeps the app alive
 * while a page plays audio/video after the user leaves the app or locks the screen.
 *
 * It also: holds audio focus (pauses for calls / other players and resumes after short interruptions), pauses when headphones
 * are unplugged, keeps the CPU and Wi-Fi awake only while playing, and stops itself after a long pause.
 */
class MediaPlaybackService : Service() {
    companion object {
        private const val CHANNEL_ID = "wb_media_playback"
        private const val NOTIF_ID = 4201
        private const val ACTION_UPDATE = "dev.wbrowser.nativeapp.media.UPDATE"
        private const val ACTION_PLAY = "dev.wbrowser.nativeapp.media.PLAY"
        private const val ACTION_PAUSE = "dev.wbrowser.nativeapp.media.PAUSE"
        private const val ACTION_TOGGLE = "dev.wbrowser.nativeapp.media.TOGGLE"
        private const val ACTION_STOP = "dev.wbrowser.nativeapp.media.STOP"
        private const val ACTION_NEXT = "dev.wbrowser.nativeapp.media.NEXT"
        private const val ACTION_PREV = "dev.wbrowser.nativeapp.media.PREV"
        private const val ACTION_REW = "dev.wbrowser.nativeapp.media.REW"
        private const val ACTION_FWD = "dev.wbrowser.nativeapp.media.FWD"
        const val SEEK_STEP_MS = 10_000L
        private const val IDLE_STOP_MS = 15 * 60_000L

        /** Show or refresh the notification for [MediaBridge.info]. */
        fun refresh(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, MediaPlaybackService::class.java).setAction(ACTION_UPDATE)) }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, MediaPlaybackService::class.java)) } }
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnGain = false
    private var lastPlaying = false
    private val idleStop = Runnable { MediaBridge.onStop?.invoke(); stopSelf() }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { MediaBridge.onPause?.invoke() }   // headphones unplugged
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; MediaBridge.onPause?.invoke() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnGain = MediaBridge.info?.playing == true; MediaBridge.onPause?.invoke() }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) { resumeOnGain = false; MediaBridge.onPlay?.invoke() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> MediaBridge.onPlay?.invoke()
            ACTION_PAUSE -> MediaBridge.onPause?.invoke()
            ACTION_TOGGLE -> MediaBridge.toggle()
            ACTION_NEXT -> MediaBridge.onNext?.invoke()
            ACTION_PREV -> MediaBridge.onPrevious?.invoke()
            ACTION_REW -> MediaBridge.onSeekBy?.invoke(-SEEK_STEP_MS)
            ACTION_FWD -> MediaBridge.onSeekBy?.invoke(SEEK_STEP_MS)
            ACTION_STOP -> { MediaBridge.onStop?.invoke(); MediaBridge.info = null; finish(); return START_NOT_STICKY }
        }
        val info = MediaBridge.info
        if (info == null) { finish(); return START_NOT_STICKY }
        render(info)
        return START_NOT_STICKY
    }

    private fun finish() {
        // a service started with startForegroundService must still have called startForeground() once
        runCatching { startForeground(NOTIF_ID, placeholder()) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(idleStop)
        runCatching { unregisterReceiver(noisy) }
        releaseLocks()
        abandonAudioFocus()
        session?.release(); session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ rendering
    private fun ensureSession(): MediaSession {
        session?.let { return it }
        val s = MediaSession(this, "WBrowserMedia")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { MediaBridge.onPlay?.invoke() }
            override fun onPause() { MediaBridge.onPause?.invoke() }
            override fun onStop() { MediaBridge.onStop?.invoke(); MediaBridge.info = null; finish() }
            override fun onSkipToNext() { MediaBridge.onNext?.invoke() }
            override fun onSkipToPrevious() { MediaBridge.onPrevious?.invoke() }
            override fun onSeekTo(pos: Long) { MediaBridge.onSeekTo?.invoke(pos) }
            override fun onFastForward() { MediaBridge.onSeekBy?.invoke(SEEK_STEP_MS) }
            override fun onRewind() { MediaBridge.onSeekBy?.invoke(-SEEK_STEP_MS) }
        }, main)
        s.isActive = true
        session = s
        return s
    }

    private fun render(info: MediaInfo) {
        ensureChannel()
        val s = ensureSession()
        val seekable = info.durationMs > 0
        val hasNext = info.features and org.mozilla.geckoview.MediaSession.Feature.NEXT_TRACK != 0L
        val hasPrev = info.features and org.mozilla.geckoview.MediaSession.Feature.PREVIOUS_TRACK != 0L

        // --- what the lock screen / notification shade / Bluetooth and headset controls see
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, info.album)
        if (seekable) md.putLong(MediaMetadata.METADATA_KEY_DURATION, info.durationMs)
        info.artwork?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it); md.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        s.setMetadata(md.build())

        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (seekable) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        if (hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (hasPrev) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        s.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(if (info.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, info.positionMs, if (info.playing) info.rate else 0f, SystemClock.elapsedRealtime())
                .build()
        )

        // --- the notification (also what the lock screen shows on Android versions without media-controls UI)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun pi(req: Int, act: String) = PendingIntent.getService(this, req, Intent(this, MediaPlaybackService::class.java).setAction(act), flags)
        fun act(icon: Int, label: String, req: Int, a: String) = Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi(req, a)).build()

        val list = mutableListOf<Notification.Action>()
        val compact = mutableListOf<Int>()
        if (hasPrev) list += act(R.drawable.ic_media_prev, "Previous", 1, ACTION_PREV)
        if (seekable) { compact += list.size; list += act(R.drawable.ic_media_rew, "Back 10 seconds", 2, ACTION_REW) }
        compact += list.size
        list += if (info.playing) act(R.drawable.ic_media_pause, "Pause", 3, ACTION_PAUSE) else act(R.drawable.ic_media_play, "Play", 3, ACTION_PLAY)
        if (seekable) { compact += list.size; list += act(R.drawable.ic_media_ff, "Forward 10 seconds", 4, ACTION_FWD) }
        if (hasNext) list += act(R.drawable.ic_media_next, "Next", 5, ACTION_NEXT)
        if (list.size < 5) list += act(R.drawable.ic_close, "Stop", 6, ACTION_STOP)

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), flags)
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(info.title)
            .setContentText(info.artist.ifBlank { "WBrowser" })
            .setContentIntent(open)
            .setDeleteIntent(pi(7, ACTION_STOP))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(info.playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(*compact.take(3).toIntArray()))
        info.artwork?.let { b.setLargeIcon(it) }
        list.forEach { b.addAction(it) }
        val notif = b.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, notif)

        if (info.playing) {
            main.removeCallbacks(idleStop)
            acquireLocks(); requestAudioFocus()
        } else {
            // paused: keep the notification (so it can be resumed or swiped away) but stop being a foreground service
            stopForeground(STOP_FOREGROUND_DETACH)
            releaseLocks()
            if (lastPlaying) { main.removeCallbacks(idleStop); main.postDelayed(idleStop, IDLE_STOP_MS) }
        }
        lastPlaying = info.playing
    }

    private fun placeholder(): Notification {
        ensureChannel()
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_shield).setContentTitle("WBrowser").build()
    }

    // ------------------------------------------------------------------ locks and focus
    /** CPU and Wi-Fi stay awake only while media plays: without this, Doze and OEM battery managers (ColorOS, MIUI, ...)
     *  can stall the decoder or the network shortly after the screen locks even with the foreground service running. */
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wbrowser:mediaPlayback")?.apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = runCatching { wm?.createWifiLock(mode, "wbrowser:mediaPlayback")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }; wifiLock = null
    }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, main).build()
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
    }

    private fun abandonAudioFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocusRequest(req)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Media playback", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Controls for audio and video that keeps playing in the background"; setShowBadge(false) }
            )
        }
    }
}
