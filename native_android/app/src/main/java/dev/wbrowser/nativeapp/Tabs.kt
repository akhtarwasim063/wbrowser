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
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

interface TabHost {
    fun context(): Context
    fun onTabChanged(tab: BrowserTab)
    fun onFullScreen(on: Boolean)
    fun newTabForWindow(opener: BrowserTab): BrowserTab
    fun onPopupBlocked(tab: BrowserTab, uri: String)
    fun onContextMenu(tab: BrowserTab, element: GeckoSession.ContentDelegate.ContextElement)
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
            override fun onFullScreen(s: GeckoSession, fullScreen: Boolean) = host.onFullScreen(fullScreen)
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

    fun close() { Tracker.onTabClosed(this); if (session.isOpen) session.close() }

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
