package dev.wbrowser.nativeapp

import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager

/** Typed access to the settings stored by the Settings screen (keys match res/xml/preferences.xml). */
object Prefs {
    const val NEWTAB_BASE = "resource://android/assets/newtab.html"

    val sp: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(App.instance)

    fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    fun str(key: String, def: String) = sp.getString(key, def) ?: def

    fun searchTemplate(): String = when (str("search_engine", "google")) {
        "duckduckgo" -> "https://duckduckgo.com/?q=%s"
        "bing" -> "https://www.bing.com/search?q=%s"
        "startpage" -> "https://www.startpage.com/do/search?q=%s"
        "ecosia" -> "https://www.ecosia.org/search?q=%s"
        else -> "https://www.google.com/search?q=%s"
    }

    /** Our own new-tab page; the search template is handed over in the URL hash. */
    fun newTabUrl() = NEWTAB_BASE + "#" + Uri.encode(searchTemplate())
    fun isNewTab(url: String?) = url != null && url.startsWith(NEWTAB_BASE)

    /** Turns whatever the user typed into a URL or a search. */
    fun resolveInput(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return newTabUrl()
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) return t
        if (t.startsWith("about:") || t.startsWith("data:") || t.startsWith("file:")) return t
        val looksLikeHost = !t.contains(' ') &&
            (t.contains('.') || t.startsWith("localhost") || Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?").containsMatchIn(t))
        if (looksLikeHost) return "https://$t"
        return searchTemplate().replace("%s", Uri.encode(t))
    }
}
