package dev.wbrowser.nativeapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

interface TabHost {
    fun context(): Context
    fun onTabChanged(tab: BrowserTab)
    fun onFullScreen(on: Boolean)
    fun newTabForWindow(opener: BrowserTab): BrowserTab
    fun onPopupBlocked(tab: BrowserTab, uri: String)
    fun onContextMenu(tab: BrowserTab, element: GeckoSession.ContentDelegate.ContextElement)
    /** Media (audio/video) on this tab started, stopped, or paused/resumed - drives the mini-player
     *  button and the background-playback notification. */
    fun onMediaChanged(tab: BrowserTab)
    /** The page scrolled. deltaY > 0 means the page moved down (user scrolled down the content,
     *  which is the usual "hide the toolbar" gesture); scrollY is the absolute position. */
    fun onContentScroll(tab: BrowserTab, deltaY: Int, scrollY: Int)
    val prompts: GeckoSession.PromptDelegate
    val permissions: GeckoSession.PermissionDelegate
}

/** One browser tab = one GeckoSession (private tabs use a private-mode session). */
class BrowserTab(private val host: TabHost, val isPrivate: Boolean) {
    val session: GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .userAgentMode(
                if (Prefs.bool("desktop_default", false)) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .viewportMode(
                if (Prefs.bool("desktop_default", false)) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
            .build()
    )

    var url = ""
    var title = ""
    var progress = 0
    var canGoBack = false
    var canGoForward = false
    var secure: Boolean? = null
    var thumbnail: Bitmap? = null
    var popupsBlocked = 0
    var desktop = Prefs.bool("desktop_default", false)
        private set

    /** True while this tab's page has audio/video actively playing (drives the mini-player button
     *  and the background-playback notification). */
    var isPlayingMedia = false
        private set
    var mediaTitle = ""
        private set
    private var activeMediaSession: MediaSession? = null

    // what the page tells us about its media (title/artist/artwork/position/features): drives the notification and lock-screen controls
    var mediaArtist = ""
        private set
    var mediaAlbum = ""
        private set
    var mediaArtwork: Bitmap? = null
        private set
    var mediaDurationMs = 0L
        private set
    private var mediaPositionMs = 0L
    private var mediaPositionStamp = 0L
    var mediaRate = 1.0
        private set
    var mediaFeatures = 0L
        private set
    /** Real size of the playing video (when the page reports it), used for the mini-player aspect ratio. */
    var videoWidth = 0
        private set
    var videoHeight = 0
        private set
    val hasMediaSession get() = activeMediaSession != null

    /** Best guess of the current playback position now (the page only reports it when it changes). */
    fun mediaPositionNow(): Long {
        if (!isPlayingMedia || mediaPositionStamp == 0L) return mediaPositionMs
        val v = mediaPositionMs + ((android.os.SystemClock.elapsedRealtime() - mediaPositionStamp) * mediaRate).toLong()
        return if (mediaDurationMs > 0) v.coerceIn(0, mediaDurationMs) else v.coerceAtLeast(0)
    }

    fun mediaInfo() = MediaInfo(
        title = mediaTitle.ifBlank { displayTitle() }, artist = mediaArtist.ifBlank { Uri.parse(url).host.orEmpty() }, album = mediaAlbum,
        artwork = mediaArtwork, playing = isPlayingMedia, positionMs = mediaPositionNow(), durationMs = mediaDurationMs,
        rate = mediaRate.toFloat(), features = mediaFeatures,
    )

    private fun resetMediaInfo() {
        mediaTitle = ""; mediaArtist = ""; mediaAlbum = ""; mediaArtwork = null
        mediaDurationMs = 0; mediaPositionMs = 0; mediaPositionStamp = 0; mediaRate = 1.0; mediaFeatures = 0
        videoWidth = 0; videoHeight = 0
    }

    /** True while the page has a video in HTML5 fullscreen (the signal nearly every streaming
     *  site - YouTube, Netflix, Prime, Hotstar... - uses for its player). More reliable than the
     *  media-session play/pause events on sites with unusual players, so it is the primary trigger
     *  for the mini-player (PiP) button and auto-pop on leaving the app. */
    var isFullscreenVideo = false
        private set
    private var lastScrollY = 0

    /** Pause/resume the page's current media, e.g. from the background-playback notification. */
    fun toggleMediaPlayback() {
        activeMediaSession?.let { if (isPlayingMedia) it.pause() else it.play() }
    }

    fun pauseMediaPlayback() { activeMediaSession?.pause() }
    fun stopMediaPlayback() { activeMediaSession?.stop() }
    fun nextTrack() { activeMediaSession?.nextTrack() }
    fun previousTrack() { activeMediaSession?.previousTrack() }
    /** Seek to an absolute position (milliseconds). */
    fun seekToMs(ms: Long) {
        val t = (if (mediaDurationMs > 0) ms.coerceIn(0, mediaDurationMs) else ms.coerceAtLeast(0)) / 1000.0
        activeMediaSession?.seekTo(t, false)
        mediaPositionMs = (t * 1000).toLong(); mediaPositionStamp = android.os.SystemClock.elapsedRealtime()
        host.onMediaChanged(this)
    }
    /** Skip forward/back (negative) by [deltaMs]. */
    fun seekByMs(deltaMs: Long) = seekToMs(mediaPositionNow() + deltaMs)
    /** The system took audio focus away (call, another player) or gave it back. */
    fun audioFocusChanged(state: Int) { activeMediaSession?.notifySystemAudioFocusChange(state) }

    /** Play only (never toggles), used to undo a pause the page/engine did on its own when the
     *  app left the foreground. Returns false if there is no media session to resume. */
    fun resumeMediaPlayback(): Boolean {
        val s = activeMediaSession ?: return false
        s.play()
        return true
    }

    /**
     * YouTube's MOBILE site pauses its player the moment the app leaves the screen (it is built to push "YouTube Premium" for
     * background play). Its DESKTOP site does not, which is why "request desktop site" is the well-known way to get background
     * play in Firefox on Android. So YouTube pages are loaded with the desktop user agent (still with the phone viewport, so the
     * layout stays responsive) unless the user turned that off in Settings. Leaving YouTube restores the normal mobile agent.
     */
    private var ytDesktop = false
    private fun isYoutubeFamily(host: String) = host == "youtu.be" || listOf("youtube.com", "google.com", "googleusercontent.com", "gstatic.com", "ytimg.com", "googlevideo.com")
        .any { host == it || host.endsWith(".$it") }
    private fun isYoutube(host: String) = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    private fun setYoutubeAgent(on: Boolean) {
        ytDesktop = on
        session.settings.userAgentMode = if (on) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
    }

    /** A YouTube watch page: the pages that get the app-like behaviour (rotate = full screen, docked player). */
    val isYoutubeWatch: Boolean
        get() {
            val u = Uri.parse(url)
            val h = u.host?.lowercase().orEmpty()
            return h == "youtu.be" || ((h == "youtube.com" || h.endsWith(".youtube.com")) && u.path?.startsWith("/watch") == true)
        }

    val isNewTab get() = Prefs.isNewTab(url)
    val loading get() = progress in 1..99

    fun displayTitle(): String = when {
        isNewTab || (url.isEmpty() && title.isEmpty()) -> "New tab"
        title.isNotEmpty() -> title
        else -> url
    }

    init {
        session.promptDelegate = host.prompts
        session.permissionDelegate = host.permissions

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, t: String?) {
                title = t ?: ""
                Tracker.onTitle(this@BrowserTab, title)
                host.onTabChanged(this@BrowserTab)
            }
            override fun onFullScreen(s: GeckoSession, fullScreen: Boolean) {
                isFullscreenVideo = fullScreen
                host.onFullScreen(fullScreen)
                host.onMediaChanged(this@BrowserTab)
            }
            override fun onContextMenu(
                s: GeckoSession, screenX: Int, screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) = host.onContextMenu(this@BrowserTab, element)
            override fun onExternalResponse(s: GeckoSession, response: WebResponse) = download(host.context(), response)
            override fun onCrash(s: GeckoSession) {
                // content process died: restart it and reload so the user does not see a dead tab
                s.close(); s.open(Engine.runtime); if (url.isNotEmpty()) s.loadUri(url)
            }
            override fun onKill(s: GeckoSession) = onCrash(s)
        }

        // Background playback / mini-player: tracks whether this page currently has audio or
        // video playing, so the host can show the pop-out (PiP) button and keep a foreground
        // notification alive while the app is backgrounded (YouTube and other streaming sites).
        session.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onActivated(s: GeckoSession, mediaSession: MediaSession) {
                android.util.Log.d("WBMedia", "activated")
                activeMediaSession = mediaSession
            }
            override fun onDeactivated(s: GeckoSession, mediaSession: MediaSession) {
                android.util.Log.d("WBMedia", "deactivated")
                if (activeMediaSession === mediaSession) { activeMediaSession = null; resetMediaInfo() }
                isPlayingMedia = false
                host.onMediaChanged(this@BrowserTab)
            }
            override fun onMetadata(s: GeckoSession, mediaSession: MediaSession, meta: MediaSession.Metadata) {
                mediaTitle = meta.title ?: ""
                mediaArtist = meta.artist ?: ""
                mediaAlbum = meta.album ?: ""
                host.onMediaChanged(this@BrowserTab)
                // artwork arrives asynchronously; the notification and lock screen refresh when it is ready
                runCatching {
                    meta.artwork?.getBitmap(512)?.accept({ bmp -> if (bmp != null) { mediaArtwork = bmp; host.onMediaChanged(this@BrowserTab) } }, { })
                }
            }
            override fun onFeatures(s: GeckoSession, mediaSession: MediaSession, features: Long) {
                mediaFeatures = features
                host.onMediaChanged(this@BrowserTab)
            }
            override fun onPositionState(s: GeckoSession, mediaSession: MediaSession, state: MediaSession.PositionState) {
                mediaDurationMs = if (state.duration.isFinite() && state.duration > 0) (state.duration * 1000).toLong() else 0L
                mediaPositionMs = (state.position * 1000).toLong().coerceAtLeast(0)
                mediaRate = if (state.playbackRate > 0) state.playbackRate else 1.0
                mediaPositionStamp = android.os.SystemClock.elapsedRealtime()
                host.onMediaChanged(this@BrowserTab)
            }
            override fun onFullscreen(s: GeckoSession, mediaSession: MediaSession, enabled: Boolean, meta: MediaSession.ElementMetadata?) {
                if (meta != null && meta.width > 0 && meta.height > 0) { videoWidth = meta.width.toInt(); videoHeight = meta.height.toInt() }
            }
            override fun onPlay(s: GeckoSession, mediaSession: MediaSession) {
                android.util.Log.d("WBMedia", "onPlay")
                mediaPositionStamp = android.os.SystemClock.elapsedRealtime()
                isPlayingMedia = true
                host.onMediaChanged(this@BrowserTab)
            }
            override fun onPause(s: GeckoSession, mediaSession: MediaSession) {
                android.util.Log.d("WBMedia", "onPause")
                mediaPositionMs = mediaPositionNow(); mediaPositionStamp = android.os.SystemClock.elapsedRealtime()
                isPlayingMedia = false
                host.onMediaChanged(this@BrowserTab)
            }
            override fun onStop(s: GeckoSession, mediaSession: MediaSession) {
                android.util.Log.d("WBMedia", "onStop")
                isPlayingMedia = false
                host.onMediaChanged(this@BrowserTab)
            }
        }

        // Toolbar auto-hide on scroll (every page, like Chrome/Brave): report scroll deltas to the
        // host, which shows/hides the address bar and bottom toolbar accordingly.
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(s: GeckoSession, scrollX: Int, scrollY: Int) {
                val dy = scrollY - lastScrollY
                lastScrollY = scrollY
                host.onContentScroll(this@BrowserTab, dy, scrollY)
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, u: String) {
                progress = 5
                host.onTabChanged(this@BrowserTab)
            }
            override fun onProgressChange(s: GeckoSession, p: Int) {
                progress = p
                host.onTabChanged(this@BrowserTab)
            }
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                progress = 100
                if (success && !isPrivate && !isNewTab) HistoryDb.addVisit(url, title)
                if (!success) Tracker.onLoadFailed(this@BrowserTab)
                host.onTabChanged(this@BrowserTab)
            }
            override fun onSecurityChange(s: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) {
                secure = info.isSecure
                host.onTabChanged(this@BrowserTab)
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                s: GeckoSession, u: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                url = u ?: ""
                if (isNewTab) { title = ""; secure = null }
                lastScrollY = 0
                Tracker.onLocation(this@BrowserTab, url)
                host.onTabChanged(this@BrowserTab)
            }
            override fun onCanGoBack(s: GeckoSession, v: Boolean) { canGoBack = v; host.onTabChanged(this@BrowserTab) }
            override fun onCanGoForward(s: GeckoSession, v: Boolean) { canGoForward = v; host.onTabChanged(this@BrowserTab) }

            override fun onLoadRequest(s: GeckoSession, r: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                // hand non-web schemes (tel:, mailto:, intent:, market:) to Android
                val scheme = Uri.parse(r.uri).scheme ?: ""
                if (scheme !in WEB_SCHEMES) {
                    runCatching {
                        host.context().startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(r.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                // YouTube desktop site for background play (see above). The agent only changes for a fresh load, so deny and load again.
                if (!desktop && Prefs.bool("yt_desktop", true)) {
                    val host = Uri.parse(r.uri).host?.lowercase().orEmpty()
                    if (isYoutube(host) && !ytDesktop) { setYoutubeAgent(true); s.loadUri(r.uri); return GeckoResult.fromValue(AllowOrDeny.DENY) }
                    if (ytDesktop && host.isNotEmpty() && !isYoutubeFamily(host)) { setYoutubeAgent(false); s.loadUri(r.uri); return GeckoResult.fromValue(AllowOrDeny.DENY) }
                }
                return null
            }

            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                // Popup / promo-tab blocker: a page may only open a window right after a real touch.
                if (Prefs.bool("block_popups", true) && Engine.shieldsOn &&
                    System.currentTimeMillis() - Engine.lastTouch > 1500
                ) {
                    popupsBlocked++
                    host.onPopupBlocked(this@BrowserTab, uri)
                    return GeckoResult.fromValue(null)
                }
                return GeckoResult.fromValue(host.newTabForWindow(this@BrowserTab).session)
            }

            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                return GeckoResult.fromValue("data:text/html;charset=utf-8," + Uri.encode(errorPage(uri, error)))
            }
        }
    }

    fun load(input: String) = session.loadUri(Prefs.resolveInput(input))

    fun setDesktop(on: Boolean) {
        desktop = on
        session.settings.userAgentMode =
            if (on) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode =
            if (on) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        session.reload()
    }

    fun close() {
        Tracker.onTabClosed(this)
        if (isPlayingMedia) { isPlayingMedia = false; host.onMediaChanged(this) }
        if (session.isOpen) session.close()
    }

    companion object {
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "file", "resource", "moz-extension", "view-source")

        fun download(ctx: Context, r: WebResponse) {
            runCatching {
                val headers = r.headers.entries.associate { it.key.lowercase() to it.value }
                val name = URLUtil.guessFileName(r.uri, headers["content-disposition"], headers["content-type"])
                val req = DownloadManager.Request(Uri.parse(r.uri))
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(ctx, "Downloading $name", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show() }
        }

        fun downloadUrl(ctx: Context, url: String) {
            runCatching {
                val name = URLUtil.guessFileName(url, null, null)
                val req = DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(ctx, "Downloading $name", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show() }
        }

        private fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun errorPage(uri: String?, e: WebRequestError): String {
            val security = e.category == WebRequestError.ERROR_CATEGORY_SECURITY
            val title = if (security) "Connection not private" else "Can't reach this page"
            val msg = if (security)
                "WBrowser stopped this page because the site's security certificate could not be trusted. " +
                    "Your information may be at risk."
            else "Check your connection or the address, then try again."
            return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>:root{color-scheme:light dark}body{font-family:system-ui,sans-serif;margin:0;padding:12vh 24px;max-width:560px;margin:auto}
h1{font-size:22px;margin:16px 0 8px}p{color:#667;line-height:1.5}code{word-break:break-all;font-size:12px}
.i{width:56px;height:56px;border-radius:16px;background:linear-gradient(135deg,#4f46e5,#06b6d4);color:#fff;display:grid;place-items:center;font-size:28px;font-weight:700}
button{margin-top:18px;padding:12px 22px;border:0;border-radius:24px;background:#4f46e5;color:#fff;font-size:16px}</style></head>
<body><div class="i">${if (security) "!" else "W"}</div><h1>$title</h1><p>$msg</p><p><code>${esc(uri)}</code></p>
<button onclick="history.back()">Go back</button></body></html>"""
        }
    }
}
