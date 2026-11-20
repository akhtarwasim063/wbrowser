package dev.wbrowser.nativeapp

import android.net.Uri

/**
 * Privacy minimisation applied ON DEVICE before anything is queued for upload
 * (the server applies the same rules again).
 */
object Sanitizer {
    data class Clean(val url: String, val domain: String)
    data class Search(val engine: String, val query: String)

    private val SENSITIVE = Regex(
        "(token|code|secret|passw|pwd|auth|session|sess|sid|key|signature|sig|otp|pin|jwt|bearer|credential|ticket|csrf|nonce|state)",
        RegexOption.IGNORE_CASE
    )
    private val SEARCH_HOSTS = listOf(
        Regex("(^|\\.)google\\.[a-z.]+$"), Regex("(^|\\.)bing\\.com$"), Regex("(^|\\.)duckduckgo\\.com$"),
        Regex("(^|\\.)startpage\\.com$"), Regex("(^|\\.)ecosia\\.org$"), Regex("(^|\\.)search\\.yahoo\\.com$"),
        Regex("(^|\\.)search\\.brave\\.com$"), Regex("(^|\\.)yandex\\.[a-z.]+$"),
    )

    private fun isSearchHost(h: String) = SEARCH_HOSTS.any { it.containsMatchIn(h) }

    /** Null = this page must never be recorded (non-web scheme, internal page, malformed). */
    fun clean(raw: String?): Clean? {
        if (raw.isNullOrEmpty() || raw.length > 8192) return null
        val u = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (u.port != -1) ":${u.port}" else ""
        val path = u.encodedPath ?: ""
        val query = if (isSearchHost(host)) "" else {
            // queries on search engines travel only via the consent-gated search event
            (u.encodedQuery ?: "").split('&')
                .filter { it.isNotEmpty() && !SENSITIVE.containsMatchIn(Uri.decode(it.substringBefore('='))) }
                .joinToString("&")
        }
        val url = "$scheme://$host$port$path" + if (query.isNotEmpty()) "?$query" else ""
        return Clean(url.take(2048), host.removePrefix("www."))
    }

    /** Extracts a web-search query from a search-engine URL; only call when the user consented. */
    fun searchQuery(raw: String?): Search? {
        val u = runCatching { Uri.parse(raw ?: return null) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (!isSearchHost(host)) return null
        val p = if (host.contains("yahoo")) "p" else if (host.contains("yandex")) "text" else "q"
        val q = u.getQueryParameter(p)?.trim()?.take(500)?.takeIf { it.isNotEmpty() } ?: return null
        val engine = host.removePrefix("www.").split('.').let { if (it.size >= 2) it[it.size - 2].let { n -> if (n == "co" || n == "com") it[it.size - 3] else n } else it[0] }
        return Search(engine, q)
    }
}
