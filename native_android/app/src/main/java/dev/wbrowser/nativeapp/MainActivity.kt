package dev.wbrowser.nativeapp

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController

class MainActivity : AppCompatActivity(), TabHost {
    private val tabs = mutableListOf<BrowserTab>()
    private var current: BrowserTab? = null
    private var showingPrivate = false
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var gecko: GeckoView
    private lateinit var urlBar: EditText
    private lateinit var btnShield: ImageView
    private lateinit var ivLock: ImageView
    private lateinit var btnReload: ImageView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tabCount: TextView
    private lateinit var btnBack: ImageView
    private lateinit var btnForward: ImageView
    private lateinit var suggestions: ListView
    private lateinit var tabSwitcher: View
    private lateinit var tabGrid: RecyclerView
    private lateinit var chipPrivate: Chip
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private var settingText = false
    private val tabAdapter = TabAdapter()

    override fun context(): Context = this

    // ======================= lifecycle =======================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupToolbar()
        setupUrlBar()
        setupTabSwitcher()
        setupFind()
        applySystemBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        val start = intent?.data?.toString()
        if (start != null) newTab(start)
        else if (!restoreTabs()) newTab()

        // First install: just ask for the name.
        if (!Account.onboarded) NameDialog.show(this)
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        gecko = findViewById(R.id.gecko)
        urlBar = findViewById(R.id.urlBar)
        btnShield = findViewById(R.id.btnShield)
        ivLock = findViewById(R.id.ivLock)
        btnReload = findViewById(R.id.btnReload)
        progress = findViewById(R.id.progress)
        tabCount = findViewById(R.id.tabCount)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        suggestions = findViewById(R.id.suggestions)
        tabSwitcher = findViewById(R.id.tabSwitcher)
        tabGrid = findViewById(R.id.tabGrid)
        chipPrivate = findViewById(R.id.chipPrivate)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
    }

    private fun applySystemBars() {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { newTab(it) }
    }

    override fun onResume() {
        super.onResume()
        syncUi()
        Tracker.onAppForeground(current)
    }

    override fun onPause() {
        super.onPause()
        Tracker.onAppBackground()
        saveTabs()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && Prefs.bool("clear_on_exit", false)) {
            Engine.runtime.storageController.clearData(StorageController.ClearFlags.ALL)
            HistoryDb.clearAll()
        }
        tabs.forEach { it.close() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            Engine.lastTouch = System.currentTimeMillis()
            // tapping the page (not the suggestion list) leaves the address bar
            if (urlBar.hasFocus() && !hit(suggestions, ev) && !hit(topBar, ev)) {
                urlBar.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hit(v: View, ev: MotionEvent): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
    }

    private fun onBack() {
        val t = current
        when {
            tabSwitcher.visibility == View.VISIBLE -> hideSwitcher()
            findBar.visibility == View.VISIBLE -> closeFind()
            urlBar.hasFocus() -> urlBar.clearFocus()
            t != null && t.canGoBack -> t.session.goBack()
            t != null && tabs.size > 1 && !t.isNewTab -> closeTab(t)
            t != null && tabs.size > 1 -> closeTab(t)
            else -> finish()
        }
    }

    // ======================= toolbar =======================
    private fun setupToolbar() {
        btnBack.setOnClickListener { current?.session?.goBack() }
        btnForward.setOnClickListener { current?.session?.goForward() }
        findViewById<View>(R.id.btnHome).setOnClickListener { current?.session?.loadUri(Prefs.newTabUrl()) }
        findViewById<View>(R.id.btnTabs).setOnClickListener {
            if (tabSwitcher.visibility == View.VISIBLE) hideSwitcher() else showSwitcher()
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }
        btnShield.setOnClickListener { showShieldSheet() }
        btnReload.setOnClickListener {
            val t = current ?: return@setOnClickListener
            if (t.loading) t.session.stop() else t.session.reload()
        }
    }

    // ======================= address bar =======================
    private fun setupUrlBar() {
        urlBar.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_GO || ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                loadFromBar(urlBar.text.toString()); true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, focus ->
            val t = current
            if (focus) {
                setBarText(if (t == null || t.isNewTab) "" else t.url)
                urlBar.post { urlBar.selectAll() }
                updateSuggestions(urlBar.text.toString())
            } else {
                suggestions.visibility = View.GONE
                syncUi()
            }
        }
        urlBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { if (!settingText && urlBar.hasFocus()) updateSuggestions(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        suggestions.setOnItemClickListener { _, _, pos, _ ->
            val item = suggestions.adapter.getItem(pos) as Suggestion
            loadFromBar(item.target)
        }
    }

    private data class Suggestion(val label: String, val sub: String, val target: String) {
        override fun toString() = label
    }

    private fun loadFromBar(text: String) {
        urlBar.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
        current?.load(text)
    }

    private fun setBarText(s: String) {
        settingText = true
        urlBar.setText(s)
        settingText = false
    }

    private fun updateSuggestions(q: String) {
        val query = q.trim()
        if (query.isEmpty()) { suggestions.visibility = View.GONE; return }
        HistoryDb.list(query) { hist ->
            if (!urlBar.hasFocus() || urlBar.text.toString().trim() != query) return@list
            val items = ArrayList<Suggestion>()
            items += Suggestion("Search \"$query\"", "", query)
            hist.distinctBy { it.url }.take(6).forEach {
                items += Suggestion(it.title.ifEmpty { it.url }, it.url, it.url)
            }
            suggestions.adapter = object : ArrayAdapter<Suggestion>(this, R.layout.item_menu_row, R.id.rowText, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    val s = getItem(position)!!
                    v.findViewById<TextView>(R.id.rowText).text = if (s.sub.isEmpty()) s.label else s.label + "\n" + s.sub
                    v.findViewById<ImageView>(R.id.rowIcon).setImageResource(if (position == 0) R.drawable.ic_search else R.drawable.ic_history)
                    v.findViewById<MaterialSwitch>(R.id.rowSwitch).visibility = View.GONE
                    return v
                }
            }
            suggestions.visibility = View.VISIBLE
        }
    }

    private fun friendly(url: String): String {
        val u = Uri.parse(url)
        val host = u.host?.removePrefix("www.")
        return if ((u.scheme == "http" || u.scheme == "https") && host != null) host else url
    }

    // ======================= UI sync =======================
    private fun syncUi() {
        val t = current ?: return
        if (!urlBar.hasFocus()) setBarText(if (t.isNewTab) "" else friendly(t.url))
        progress.setProgressCompat(t.progress, true)
        progress.visibility = if (t.loading) View.VISIBLE else View.INVISIBLE
        btnReload.setImageResource(if (t.loading) R.drawable.ic_close else R.drawable.ic_refresh)
        tabCount.text = if (tabs.size > 99) ":)" else tabs.size.toString()
        btnBack.alpha = if (t.canGoBack) 1f else 0.35f
        btnForward.alpha = if (t.canGoForward) 1f else 0.35f

        when {
            t.isNewTab || t.url.isEmpty() -> ivLock.visibility = View.GONE
            t.secure == true -> {
                ivLock.visibility = View.VISIBLE
                ivLock.setImageResource(R.drawable.ic_lock)
                ivLock.setColorFilter(getColor(R.color.wb_green))
            }
            t.secure == false -> {
                ivLock.visibility = View.VISIBLE
                ivLock.setImageResource(R.drawable.ic_warning)
                ivLock.setColorFilter(getColor(R.color.wb_danger))
            }
            else -> ivLock.visibility = View.GONE
        }
        val shieldOn = Engine.shieldsOn
        btnShield.setColorFilter(
            when {
                t.isPrivate -> getColor(R.color.wb_private)
                shieldOn -> getColor(R.color.wb_green)
                else -> getColor(R.color.wb_outline)
            }
        )
        topBar.setBackgroundColor(if (t.isPrivate) ColorUtils.setAlphaComponent(getColor(R.color.wb_private), 40) else 0)
    }

    override fun onTabChanged(tab: BrowserTab) {
        if (tab === current) syncUi() else tabCount.text = tabs.size.toString()
        if (tabSwitcher.visibility == View.VISIBLE) tabAdapter.refresh()
    }

    // ======================= tabs =======================
    private fun newTab(url: String = Prefs.newTabUrl(), private: Boolean = false): BrowserTab {
        val t = BrowserTab(this, private)
        tabs += t
        t.session.open(Engine.runtime)
        select(t)
        t.session.loadUri(url)
        hideSwitcher()
        return t
    }

    /** A page asked for a window (popup or target=_blank). Gecko opens the session itself. */
    override fun newTabForWindow(opener: BrowserTab): BrowserTab {
        val t = BrowserTab(this, opener.isPrivate)
        tabs += t
        fun trySelect(n: Int) {
            if (!tabs.contains(t)) return
            if (t.session.isOpen) { select(t); hideSwitcher() }
            else if (n > 0) ui.postDelayed({ trySelect(n - 1) }, 100)
        }
        ui.postDelayed({ trySelect(15) }, 100)
        return t
    }

    private fun select(t: BrowserTab) {
        current?.takeIf { it !== t }?.session?.setActive(false)
        current = t
        if (!t.session.isOpen) t.session.open(Engine.runtime)
        if (gecko.session !== t.session) gecko.setSession(t.session)
        t.session.setActive(true)
        Tracker.onFocus(t)
        closeFind()
        syncUi()
    }

    private fun closeTab(t: BrowserTab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        val sameMode = tabs.filter { it.isPrivate == t.isPrivate }
        tabs.remove(t)
        if (current === t) {
            gecko.releaseSession()
            val next = tabs.getOrNull((idx - 1).coerceAtLeast(0)) ?: run { newTab(); return@run null }
            if (next != null) select(next)
        }
        t.close()
        syncUi()
        if (tabSwitcher.visibility == View.VISIBLE) {
            if (sameMode.size <= 1) { newTab(private = t.isPrivate); } else tabAdapter.refresh()
        }
    }

    private fun saveTabs() {
        val arr = JSONArray()
        tabs.filter { !it.isPrivate }.forEach { arr.put(JSONObject().put("url", it.url.ifEmpty { Prefs.newTabUrl() })) }
        Prefs.sp.edit().putString("open_tabs", arr.toString())
            .putInt("open_index", tabs.filter { !it.isPrivate }.indexOf(current).coerceAtLeast(0)).apply()
    }

    private fun restoreTabs(): Boolean {
        if (!Prefs.bool("restore_tabs", true)) return false
        val arr = runCatching { JSONArray(Prefs.str("open_tabs", "[]")) }.getOrNull() ?: return false
        if (arr.length() == 0) return false
        val idx = Prefs.sp.getInt("open_index", 0)
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i).getString("url")
            val t = BrowserTab(this, false)
            tabs += t
            t.session.open(Engine.runtime)
            t.session.loadUri(u)
        }
        select(tabs[idx.coerceIn(0, tabs.size - 1)])
        return true
    }

    // ======================= tab switcher =======================
    private fun setupTabSwitcher() {
        tabGrid.layoutManager = GridLayoutManager(this, 2)
        tabGrid.adapter = tabAdapter
        chipPrivate.setOnCheckedChangeListener { _, checked ->
            showingPrivate = checked
            tabAdapter.refresh()
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fabNewTab).setOnClickListener { newTab(private = showingPrivate) }

        // swipe a tab card left or right to close it, like most browsers' tab switchers
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                tabAdapter.closeAt(vh.bindingAdapterPosition)
            }
        }).attachToRecyclerView(tabGrid)
    }

    private fun showSwitcher() {
        val t = current
        showingPrivate = t?.isPrivate ?: false
        chipPrivate.isChecked = showingPrivate
        urlBar.clearFocus()
        tabAdapter.refresh()
        tabSwitcher.visibility = View.VISIBLE
        // refresh the current tab's thumbnail in the background
        if (t != null) gecko.capturePixels().accept({ bmp ->
            if (bmp != null) {
                val w = 480
                t.thumbnail = Bitmap.createScaledBitmap(bmp, w, (bmp.height * (w.toFloat() / bmp.width)).toInt().coerceAtLeast(1), true)
                tabAdapter.refresh()
            }
        }, { })
    }

    private fun hideSwitcher() { tabSwitcher.visibility = View.GONE }

    private inner class TabAdapter : RecyclerView.Adapter<TabAdapter.VH>() {
        private var items: List<BrowserTab> = emptyList()
        fun refresh() { items = tabs.filter { it.isPrivate == showingPrivate }; notifyDataSetChanged() }
        fun closeAt(i: Int) { items.getOrNull(i)?.let { closeTab(it) } }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tabTitle)
            val close: ImageView = v.findViewById(R.id.tabClose)
            val thumb: ImageView = v.findViewById(R.id.tabThumb)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_tab, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val t = items[i]
            h.title.text = t.displayTitle()
            h.title.setTextColor(if (t === current) getColor(R.color.wb_primary) else h.title.currentTextColor)
            if (t.thumbnail != null) { h.thumb.setImageBitmap(t.thumbnail); h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP }
            else { h.thumb.setImageResource(if (t.isPrivate) R.drawable.ic_private else R.drawable.ic_home); h.thumb.scaleType = ImageView.ScaleType.CENTER; h.thumb.setColorFilter(getColor(R.color.wb_outline)) }
            if (t.thumbnail != null) h.thumb.clearColorFilter()
            h.itemView.setOnClickListener { select(t); hideSwitcher() }
            h.close.setOnClickListener { closeTab(t) }
        }
    }

    // ======================= menu & sheets =======================
    private fun row(parent: ViewGroup, icon: Int, text: String, switch: Boolean? = null, onClick: () -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_menu_row, parent, false)
        v.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.rowText).text = text
        if (switch != null) v.findViewById<MaterialSwitch>(R.id.rowSwitch).apply { visibility = View.VISIBLE; isChecked = switch }
        v.setOnClickListener { onClick() }
        parent.addView(v)
        return v
    }

    private fun showMenu() {
        val t = current ?: return
        val dlg = BottomSheetDialog(this)
        val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(0, 12, 0, 24) }
        val scroll = android.widget.ScrollView(this).apply { addView(col) }
        fun item(icon: Int, text: String, switch: Boolean? = null, act: () -> Unit) = row(col, icon, text, switch) { dlg.dismiss(); act() }

        item(R.drawable.ic_add, "New tab") { newTab() }
        item(R.drawable.ic_private, "New private tab") { newTab(private = true) }
        val bm = item(R.drawable.ic_bookmark_border, "Add bookmark") {
            if (!t.isNewTab && t.url.isNotEmpty()) HistoryDb.toggleBookmark(t.url, t.title) { added ->
                Snackbar.make(root, if (added) "Bookmark added" else "Bookmark removed", Snackbar.LENGTH_SHORT).show()
            }
        }
        if (!t.isNewTab) HistoryDb.isBookmarked(t.url) { yes ->
            if (yes) {
                bm.findViewById<ImageView>(R.id.rowIcon).setImageResource(R.drawable.ic_bookmark)
                bm.findViewById<TextView>(R.id.rowText).text = "Remove bookmark"
            }
        }
        item(R.drawable.ic_bookmark, "Bookmarks") { startActivityForResult(Intent(this, HistoryActivity::class.java).putExtra("mode", "bookmarks"), REQ_LIST) }
        item(R.drawable.ic_history, "History") { startActivityForResult(Intent(this, HistoryActivity::class.java).putExtra("mode", "history"), REQ_LIST) }
        item(R.drawable.ic_download, "Downloads") { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        item(R.drawable.ic_share, "Share") {
            if (!t.isNewTab) startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url).putExtra(Intent.EXTRA_SUBJECT, t.title), "Share link"))
        }
        item(R.drawable.ic_search, "Find in page") { openFind() }
        item(R.drawable.ic_desktop, "Desktop site", t.desktop) { t.setDesktop(!t.desktop) }
        item(R.drawable.ic_settings, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        dlg.setContentView(scroll)
        dlg.show()
    }

    private fun showShieldSheet() {
        val t = current ?: return
        val dlg = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_shield, null)
        val sw = v.findViewById<MaterialSwitch>(R.id.shieldSwitch)
        val title = v.findViewById<TextView>(R.id.shieldTitle)
        val info = v.findViewById<TextView>(R.id.shieldInfo)
        v.findViewById<TextView>(R.id.shieldSite).text = if (t.isNewTab) "WBrowser" else friendly(t.url)
        fun refresh() {
            val on = Engine.shieldsOn
            title.text = if (on) "Shields are ON" else "Shields are OFF"
            v.findViewById<ImageView>(R.id.shieldIcon).setColorFilter(getColor(if (on) R.color.wb_green else R.color.wb_outline))
            info.text = if (on) buildString {
                append("• Ads and trackers: blocked (uBlock Origin)\n")
                append("• Tracking protection: ${if (Prefs.str("tracking_level", "strict") == "strict") "Strict" else "Standard"}\n")
                append("• Pop-ups and promo tabs blocked on this page: ${t.popupsBlocked}\n")
                append("• Connection: ${when (t.secure) { true -> "Secure (HTTPS)"; false -> "Not secure"; else -> "n/a" }}")
            } else "Ads, trackers and pop-ups are not blocked. Turn Shields on for a safer, cleaner page."
        }
        sw.isChecked = Engine.shieldsOn
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.sp.edit().putBoolean("shields_on", on).apply()
            Engine.applyAll()
            refresh(); syncUi()
            t.session.reload()
        }
        v.findViewById<View>(R.id.btnUbo).setOnClickListener {
            dlg.dismiss()
            val base = Engine.uboBaseUrl
            if (base != null) newTab(base + "dashboard.html")
            else Snackbar.make(root, "uBlock Origin is still installing: ${Engine.uboStatus}", Snackbar.LENGTH_LONG).show()
        }
        refresh()
        dlg.setContentView(v)
        dlg.show()
    }

    // ======================= find in page =======================
    private fun setupFind() {
        findInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = doFind(s.toString(), 0)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<View>(R.id.findNext).setOnClickListener { doFind(null, 0) }
        findViewById<View>(R.id.findPrev).setOnClickListener { doFind(null, GeckoSession.FINDER_FIND_BACKWARDS) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
    }

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, 0)
    }

    private fun doFind(text: String?, flags: Int) {
        val s = current?.session ?: return
        if (text != null && text.isEmpty()) { s.finder.clear(); findCount.text = ""; return }
        s.finder.find(text, flags).accept({ r ->
            findCount.text = if (r == null || r.total == 0) "0/0" else "${r.current}/${r.total}"
        }, { })
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE
        current?.session?.finder?.clear()
        findInput.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findInput.windowToken, 0)
    }

    // ======================= TabHost callbacks =======================
    override fun onFullScreen(on: Boolean) {
        val v = if (on) View.GONE else View.VISIBLE
        topBar.visibility = v; bottomBar.visibility = v
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPopupBlocked(tab: BrowserTab, uri: String) {
        if (tab !== current) return
        Snackbar.make(root, "Pop-up blocked", Snackbar.LENGTH_LONG)
            .setAction("Allow") { newTab(uri, tab.isPrivate) }.show()
    }

    override fun onContextMenu(tab: BrowserTab, element: GeckoSession.ContentDelegate.ContextElement) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        val link = element.linkUri
        val src = element.srcUri
        if (link != null) {
            labels += "Open in new tab"; acts += { newTab(link, tab.isPrivate) }
            labels += "Open in private tab"; acts += { newTab(link, true) }
            labels += "Copy link"; acts += { copy(link) }
            labels += "Share link"; acts += {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share link"))
            }
        }
        if (src != null && element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) {
            labels += "Open image in new tab"; acts += { newTab(src, tab.isPrivate) }
            labels += "Download image"; acts += { BrowserTab.downloadUrl(this, src) }
            labels += "Copy image address"; acts += { copy(src) }
        }
        if (labels.isEmpty()) return
        MaterialAlertDialogBuilder(this).setTitle(link ?: src)
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
        Snackbar.make(root, "Copied", Snackbar.LENGTH_SHORT).show()
    }

    private fun hostOf(uri: String?): String = runCatching { Uri.parse(uri).host ?: uri }.getOrNull() ?: "This page"

    // ======================= prompts (alert / confirm / select / file / popup) =======================
    private var pendingFile: Pair<GeckoSession.PromptDelegate.FilePrompt, GeckoResult<PromptResponse>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val (prompt, result) = pendingFile ?: return@registerForActivityResult
        pendingFile = null
        val data = res.data
        if (res.resultCode != RESULT_OK || data == null) { result.complete(prompt.dismiss()); return@registerForActivityResult }
        val uris = ArrayList<Uri>()
        data.clipData?.let { for (i in 0 until it.itemCount) uris += it.getItemAt(i).uri }
        data.data?.let { if (uris.isEmpty()) uris += it }
        result.complete(
            if (uris.isEmpty()) prompt.dismiss()
            else if (uris.size == 1) prompt.confirm(this, uris[0]) else prompt.confirm(this, uris.toTypedArray())
        )
    }

    override val prompts: GeckoSession.PromptDelegate = object : GeckoSession.PromptDelegate {
        override fun onAlertPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(p.title ?: hostOf(null)).setMessage(p.message)
                .setPositiveButton("OK") { _, _ -> r.complete(p.dismiss()) }
                .setOnCancelListener { r.complete(p.dismiss()) }.show()
            return r
        }

        override fun onButtonPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(p.title ?: "Confirm").setMessage(p.message)
                .setPositiveButton("OK") { _, _ -> r.complete(p.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton("Cancel") { _, _ -> r.complete(p.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                .setOnCancelListener { r.complete(p.dismiss()) }.show()
            return r
        }

        override fun onTextPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val input = EditText(this@MainActivity).apply { setText(p.defaultValue ?: ""); setSingleLine() }
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(p.title ?: "").setMessage(p.message).setView(input)
                .setPositiveButton("OK") { _, _ -> r.complete(p.confirm(input.text.toString())) }
                .setNegativeButton("Cancel") { _, _ -> r.complete(p.dismiss()) }
                .setOnCancelListener { r.complete(p.dismiss()) }.show()
            return r
        }

        override fun onChoicePrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val flat = ArrayList<GeckoSession.PromptDelegate.ChoicePrompt.Choice>()
            fun walk(arr: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>?) {
                arr?.forEach { c -> if (c.items != null && c.items!!.isNotEmpty()) walk(c.items) else if (!c.separator) flat += c }
            }
            walk(p.choices)
            val labels = flat.map { it.label ?: "" }.toTypedArray()
            val b = MaterialAlertDialogBuilder(this@MainActivity).setTitle(p.message ?: p.title ?: "")
            if (p.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                val checked = BooleanArray(flat.size) { flat[it].selected }
                b.setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton("OK") { _, _ ->
                        r.complete(p.confirm(flat.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
                    }.setNegativeButton("Cancel") { _, _ -> r.complete(p.dismiss()) }
            } else {
                b.setItems(labels) { _, i -> r.complete(p.confirm(flat[i])) }
            }
            b.setOnCancelListener { r.complete(p.dismiss()) }.show()
            return r
        }

        override fun onFilePrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            if (p.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) { r.complete(p.dismiss()); return r }
            val mimes = p.mimeTypes?.filter { it.contains("/") }.orEmpty()
            val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (mimes.size == 1) mimes[0] else "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
            if (mimes.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            pendingFile = p to r
            filePicker.launch(Intent.createChooser(intent, "Choose file"))
            return r
        }

        override fun onPopupPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> {
            val tab = tabs.firstOrNull { it.session === s }
            if (Prefs.bool("block_popups", true) && Engine.shieldsOn) {
                if (tab != null) { tab.popupsBlocked++; onPopupBlocked(tab, p.targetUri ?: "") }
                return GeckoResult.fromValue(p.confirm(AllowOrDeny.DENY))
            }
            return GeckoResult.fromValue(p.confirm(AllowOrDeny.ALLOW))
        }
    }

    // ======================= permissions =======================
    private var pendingAndroidPerm: GeckoSession.PermissionDelegate.Callback? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        val cb = pendingAndroidPerm ?: return@registerForActivityResult
        pendingAndroidPerm = null
        if (map.values.all { it }) cb.grant() else cb.reject()
    }

    override val permissions: GeckoSession.PermissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            s: GeckoSession, perms: Array<String>?, cb: GeckoSession.PermissionDelegate.Callback,
        ) {
            val need = perms?.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.orEmpty()
            if (need.isEmpty()) { cb.grant(); return }
            pendingAndroidPerm = cb
            permLauncher.launch(need.toTypedArray())
        }

        override fun onContentPermissionRequest(
            s: GeckoSession, p: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? {
            val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            return when (p.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                    if (Prefs.bool("block_notifications", false)) GeckoResult.fromValue(deny) else ask(p, "show notifications")
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> ask(p, "know your location")
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> GeckoResult.fromValue(deny)
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
                GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> GeckoResult.fromValue(allow)
                else -> ask(p, "use this feature")
            }
        }

        private fun ask(p: GeckoSession.PermissionDelegate.ContentPermission, what: String): GeckoResult<Int> {
            val r = GeckoResult<Int>()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(hostOf(p.uri)).setMessage("Allow this site to $what?")
                .setPositiveButton("Allow") { _, _ -> r.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) }
                .setNegativeButton("Block") { _, _ -> r.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }
                .setOnCancelListener { r.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }.show()
            return r
        }

        override fun onMediaPermissionRequest(
            s: GeckoSession, uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            cb: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            val what = listOfNotNull(if (!video.isNullOrEmpty()) "camera" else null, if (!audio.isNullOrEmpty()) "microphone" else null)
                .joinToString(" and ")
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(hostOf(uri)).setMessage("Allow this site to use your $what?")
                .setPositiveButton("Allow") { _, _ -> cb.grant(video?.firstOrNull(), audio?.firstOrNull()) }
                .setNegativeButton("Block") { _, _ -> cb.reject() }
                .setOnCancelListener { cb.reject() }.show()
        }
    }

    companion object { private const val REQ_LIST = 11 }

    @Deprecated("List screen returns the URL to open")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LIST) data?.getStringExtra("open")?.let { current?.session?.loadUri(it) }
    }
}
