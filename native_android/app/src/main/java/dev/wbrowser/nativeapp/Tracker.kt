package dev.wbrowser.nativeapp

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID

/**
 * Records page visits, time spent, sessions and (only with explicit consent) search queries into the offline outbox.
 * Main-thread only. Never records private tabs, non-web pages or the new-tab page, and does nothing unless the
 * user has opted in (Account.collecting).
 *
 * Time spent = time the page was the selected tab while the app was in the foreground.
 */
object Tracker {
    private class Visit(val id: String, val url: String, val startedAt: Long, var title: String) {
        var accMs = 0L
        var since = 0L   // 0 = not currently on screen
    }

    private val main = Handler(Looper.getMainLooper())
    private val open = HashMap<BrowserTab, Visit>()
    private var focused: BrowserTab? = null
    private var appActive = false
    private var sessionId: String? = null
    private var sessionStart = 0L
    private var lastBackground = 0L
    private var lastSearch = "" to 0L

    private const val SESSION_GRACE_MS = 5 * 60_000L
    private val CHECKPOINT_MS get() = Remote.intervalSec * 1000L   // admin-controlled

    private val checkpointTask = object : Runnable {
        override fun run() {
            if (appActive) { checkpointAll(); main.postDelayed(this, CHECKPOINT_MS) }
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun id() = UUID.randomUUID().toString().replace("-", "").take(24)

    // ---------- lifecycle from MainActivity ----------
    fun onAppForeground(current: BrowserTab?) {
        if (appActive) return
        appActive = true
        if (Account.collecting) {
            val t = now()
            if (sessionId == null || t - lastBackground > SESSION_GRACE_MS) {
                sessionId = id(); sessionStart = t
            }
            focused = current
            current?.let { c -> open[c]?.let { if (it.since == 0L) it.since = t } }
            main.removeCallbacks(checkpointTask); main.postDelayed(checkpointTask, CHECKPOINT_MS)
        }
    }

    fun onAppBackground() {
        if (!appActive) return
        appActive = false
        main.removeCallbacks(checkpointTask)
        lastBackground = now()
        pauseAll()
        checkpointAll()
        SyncScheduler.kickNow()
    }

    fun onFocus(tab: BrowserTab?) {
        val t = now()
        focused?.let { open[it]?.pause(t) }
        focused = tab
        if (appActive && tab != null) open[tab]?.let { if (it.since == 0L) it.since = t }
    }

    // ---------- page events from BrowserTab ----------
    fun onLocation(tab: BrowserTab, rawUrl: String) {
        val clean = if (Account.collecting && !tab.isPrivate && !Prefs.isNewTab(rawUrl)) Sanitizer.clean(rawUrl) else null
        val cur = open[tab]
        if (cur != null && clean != null && cur.url == clean.url) return   // hash change / same page
        cur?.let { finish(tab, it) }
        if (clean == null) return
        val t = now()
        val v = Visit(id(), clean.url, t, "")   // title arrives via onTitle (tab.title may still be the previous page's)
        if (appActive && focused === tab) v.since = t
        open[tab] = v
        if (Account.searchActive) recordSearch(rawUrl, t)
    }

    fun onTitle(tab: BrowserTab, title: String) { open[tab]?.title = title }

    /** Page failed to load: do not report it as a visit. */
    fun onLoadFailed(tab: BrowserTab) { open.remove(tab) }

    fun onTabClosed(tab: BrowserTab) {
        open[tab]?.let { finish(tab, it) }
        if (focused === tab) focused = null
    }

    /** Sharing switched off / account erased: drop everything in flight without queuing it. */
    fun stop() {
        open.clear(); sessionId = null
    }

    // ---------- internals ----------
    private fun Visit.pause(t: Long) { if (since != 0L) { accMs += t - since; since = 0L } }

    private fun pauseAll() { val t = now(); open.values.forEach { it.pause(t) } }

    private fun finish(tab: BrowserTab, v: Visit) {
        v.pause(now())
        open.remove(tab)
        enqueueVisit(v)
    }

    private fun enqueueVisit(v: Visit, liveMs: Long = 0) {
        val sid = sessionId ?: return
        ActivityDb.enqueue(JSONObject().put("type", "visit").put("id", v.id).put("sessionId", sid)
            .put("url", v.url).put("title", v.title.take(300)).put("startedAt", v.startedAt)
            .put("durationMs", v.accMs + liveMs))
    }

    /** Persist current state of every open visit + session so a process kill or offline spell loses nothing. */
    private fun checkpointAll() {
        if (!Account.collecting) return
        val t = now()
        open.values.forEach { v -> enqueueVisit(v, if (v.since != 0L) t - v.since else 0) }
        sessionId?.let {
            ActivityDb.enqueue(JSONObject().put("type", "session").put("id", it)
                .put("startedAt", sessionStart).put("endedAt", if (appActive) t else lastBackground))
        }
    }

    private fun recordSearch(rawUrl: String, t: Long) {
        val s = Sanitizer.searchQuery(rawUrl) ?: return
        if (s.query == lastSearch.first && t - lastSearch.second < 30_000) return
        lastSearch = s.query to t
        ActivityDb.enqueue(JSONObject().put("type", "search").put("id", id()).put("query", s.query)
            .put("engine", s.engine).put("at", t))
    }
}
