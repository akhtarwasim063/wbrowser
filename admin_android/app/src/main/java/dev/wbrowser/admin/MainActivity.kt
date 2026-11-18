package dev.wbrowser.admin

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * WBrowser Admin: a locked-down shell around the Admin web app.
 * - only the configured admin server is shown in-app; every other link opens in the phone's browser
 * - JS alert/confirm/prompt (used by the admin UI for delete confirmations) are shown as native dialogs
 * - release builds block screenshots and refuse plain http
 * It holds no credentials: you sign in on the admin page, the session lives in an HttpOnly cookie.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private val prefs by lazy { getSharedPreferences("admin", Context.MODE_PRIVATE) }

    private fun adminUrl(): String = prefs.getString("url", null) ?: BuildConfig.ADMIN_URL
    private fun origin(u: String): String? = Uri.parse(u).let { if (it.scheme == null || it.host == null) null else "${it.scheme}://${it.host}:${if (it.port == -1) (if (it.scheme == "https") 443 else 80) else it.port}" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        web = WebView(this)
        errorText = TextView(this).apply { textSize = 16f; setPadding(48, 48, 48, 24) }
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(32, 120, 32, 32)
            addView(errorText)
            addView(Button(context).apply { text = "Retry"; setOnClickListener { load() } })
            addView(Button(context).apply { text = "Change server address"; setOnClickListener { askUrl() } })
        }
        val root = android.widget.FrameLayout(this).apply { addView(web); addView(errorBox) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (origin(u) == origin(adminUrl())) return false
                // external site (a visited page / search from the admin tables): use the phone's browser
                if (r.url.scheme == "http" || r.url.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, r.url)) }
                return true
            }
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) { errorBox.visibility = View.GONE; web.visibility = View.VISIBLE }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame) return
                web.visibility = View.GONE; errorBox.visibility = View.VISIBLE
                errorText.text = "Cannot reach the admin server.\n\n${adminUrl()}\n\n${e.description}\n\nIs the backend running? Over USB run: adb reverse tcp:4001 tcp:4001"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(v: WebView, url: String, msg: String, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(msg).setPositiveButton("OK") { _, _ -> r.confirm() }.setOnCancelListener { r.cancel() }.show(); return true
            }
            override fun onJsConfirm(v: WebView, url: String, msg: String, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(msg).setPositiveButton("OK") { _, _ -> r.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> r.cancel() }.setOnCancelListener { r.cancel() }.show(); return true
            }
            override fun onJsPrompt(v: WebView, url: String, msg: String, def: String?, r: JsPromptResult): Boolean {
                val input = EditText(this@MainActivity).apply { setText(def ?: ""); setSingleLine() }
                AlertDialog.Builder(this@MainActivity).setMessage(msg).setView(input)
                    .setPositiveButton("OK") { _, _ -> r.confirm(input.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> r.cancel() }.setOnCancelListener { r.cancel() }.show(); return true
            }
        }
        web.setDownloadListener { url, ua, cd, mime, _ ->   // CSV export
            runCatching {
                val name = URLUtil.guessFileName(url, cd, mime)
                val req = DownloadManager.Request(Uri.parse(url)).setMimeType(mime).addRequestHeader("User-Agent", ua)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .setTitle(name).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else load()
    }

    private fun load() {
        val u = adminUrl()
        if (!BuildConfig.DEBUG && !u.startsWith("https://")) {
            errorBox.visibility = View.VISIBLE; web.visibility = View.GONE
            errorText.text = "Release builds only connect over https.\n\n$u"; return
        }
        web.loadUrl(u)
    }

    private fun askUrl() {
        val input = EditText(this).apply { setText(adminUrl()); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Admin server address").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (origin(v) != null) { prefs.edit().putString("url", v).apply(); load() }
                else Toast.makeText(this, "Enter a full address like https://host:4001/admin/", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }
}
