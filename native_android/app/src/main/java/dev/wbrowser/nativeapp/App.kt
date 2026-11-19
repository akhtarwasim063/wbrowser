package dev.wbrowser.nativeapp

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Gecko child processes (tab, gpu, crashhelper) also run Application.onCreate.
        // Creating a GeckoRuntime there kills the app, so only the main process may init.
        if (getProcessName() != packageName) return
        applyNightMode()
        HistoryDb.init(this)
        ActivityDb.init(this)
        Cloud.init(this)
        SyncScheduler.ensurePeriodic(this)
        Engine.init(this)
    }

    companion object {
        lateinit var instance: App
            private set

        fun applyNightMode() {
            AppCompatDelegate.setDefaultNightMode(
                when (Prefs.str("theme", "system")) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}

/** Process-wide Gecko runtime, shields (uBlock Origin + tracking protection) and settings. */
object Engine {
    const val UBO_ID = "uBlock0@raymondhill.net"
    const val WB_ID = "wbshield@wbrowser.dev"
    const val WP_ID = "wbplayback@wbrowser.dev"   // keeps pages "visible" so video keeps playing in the background
    private const val UBO_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private const val WB_URI = "resource://android/assets/extensions/wbshield/"
    private const val WP_URI = "resource://android/assets/extensions/wbplayback/"

    lateinit var runtime: GeckoRuntime
        private set

    val shieldsOn: Boolean get() = Prefs.bool("shields_on", true)

    var uboStatus = "Not installed"
        private set
    var uboBaseUrl: String? = null
        private set

    /** Time of the last user touch; used by the popup blocker. */
    @Volatile var lastTouch = 0L

    private val main = Handler(Looper.getMainLooper())
    private val statusListeners = mutableListOf<() -> Unit>()
    fun addStatusListener(l: () -> Unit) { statusListeners += l }
    fun removeStatusListener(l: () -> Unit) { statusListeners -= l }
    private fun setStatus(s: String) {
        uboStatus = s
        main.post { statusListeners.toList().forEach { it() } }
    }

    fun init(app: App) {
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(false)
            .crashHandler(CrashService::class.java)
            .build()
        runtime = GeckoRuntime.create(app, settings)

        // Auto-accept the permission prompt: we only ever install uBlock Origin from AMO.
        runtime.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ) = GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, true))
        }
        applyAll()
        ensureUbo()
        ensureBuiltIn()
    }

    /** Pushes every setting from Prefs into the live runtime. Safe to call any time. */
    fun applyAll() {
        App.applyNightMode()
        val s = runtime.settings
        s.setPreferredColorScheme(
            when (Prefs.str("theme", "system")) {
                "light" -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                "dark" -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
                else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
            }
        )
        s.setJavaScriptEnabled(Prefs.bool("javascript", true))
        s.setGlobalPrivacyControl(Prefs.bool("gpc", true))
        s.setAllowInsecureConnections(
            if (Prefs.bool("https_only", false)) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL
        )
        s.setAutomaticFontSizeAdjustment(false)
        s.setFontSizeFactor(Prefs.str("text_size", "100").toFloat() / 100f)

        val on = shieldsOn
        val strict = Prefs.str("tracking_level", "strict") == "strict"
        val cb = s.contentBlocking
        cb.setAntiTracking(
            when {
                !on -> ContentBlocking.AntiTracking.NONE
                strict -> ContentBlocking.AntiTracking.STRICT
                else -> ContentBlocking.AntiTracking.DEFAULT
            }
        )
        cb.setEnhancedTrackingProtectionLevel(
            when {
                !on -> ContentBlocking.EtpLevel.NONE
                strict -> ContentBlocking.EtpLevel.STRICT
                else -> ContentBlocking.EtpLevel.DEFAULT
            }
        )
        cb.setCookieBehavior(
            if (Prefs.bool("block_3p_cookies", true)) ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
            else ContentBlocking.CookieBehavior.ACCEPT_ALL
        )
        cb.setSafeBrowsing(
            if (Prefs.bool("safe_browsing", true)) ContentBlocking.SafeBrowsing.DEFAULT
            else ContentBlocking.SafeBrowsing.NONE
        )
        cb.setCookiePurging(true)
        cb.setQueryParameterStrippingEnabled(on)

        syncExtensions()
    }

    /** Turns the ad-block extensions on or off to match the settings. */
    private fun syncExtensions() {
        val c = runtime.webExtensionController
        c.list().accept({ list ->
            list?.forEach { ext ->
                val want = when (ext.id) {
                    UBO_ID -> shieldsOn
                    WB_ID -> shieldsOn && Prefs.bool("youtube_shield", true)
                    WP_ID -> Prefs.bool("bg_playback", true)
                    else -> return@forEach
                }
                if (ext.id == UBO_ID) uboBaseUrl = ext.metaData?.baseUrl
                if (want != ext.metaData?.enabled) {
                    if (want) c.enable(ext, WebExtensionController.EnableSource.USER)
                    else c.disable(ext, WebExtensionController.EnableSource.USER)
                }
                if (ext.id == UBO_ID) setStatus(if (want) "uBlock Origin active" else "uBlock Origin installed (shields off)")
            }
        }, { })
    }

    /** Installs uBlock Origin from addons.mozilla.org the first time (then it persists). */
    fun ensureUbo(force: Boolean = false) {
        setStatus("Checking...")
        val c = runtime.webExtensionController
        c.list().accept({ list ->
            val existing = list?.firstOrNull { it.id == UBO_ID }
            if (existing != null && !force) {
                uboBaseUrl = existing.metaData?.baseUrl
                syncExtensions()
            } else {
                setStatus("Installing uBlock Origin...")
                c.install(UBO_URL).accept({ ext ->
                    uboBaseUrl = ext?.metaData?.baseUrl
                    syncExtensions()
                }, { e -> setStatus("Install failed: ${e?.message ?: "unknown"} (check internet)") })
            }
        }, { e -> setStatus("Error: ${e?.message}") })
    }

    /** Our own small extension (YouTube backup shield), bundled in the APK. */
    private fun ensureBuiltIn() {
        runtime.webExtensionController.ensureBuiltIn(WB_URI, WB_ID).accept({ syncExtensions() }, { })
        runtime.webExtensionController.ensureBuiltIn(WP_URI, WP_ID).accept({ syncExtensions() }, { })
    }
}
