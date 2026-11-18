package dev.wbrowser.nativeapp

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.security.SecureRandom

/**
 * The user's identity + consent state for activity sharing.
 *
 * Shared with the admin (Firestore): the generated user ID, the name the user typed, consent flags, device model /
 * OS / app version, and optionally the Google name/email (via official Google sign-in; we never see a password).
 * Never passwords, cookies or auth tokens. Authentication is a Firebase Auth user (see Cloud).
 */
object Account {
    private const val PREF = "wb_account"
    private val sp get() = App.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** User has seen the first-launch dialog and made a choice. */
    val onboarded: Boolean get() = sp.getBoolean("onboarded", false)
    val name: String get() = sp.getString("name", "") ?: ""
    /** Human-readable unique ID (WB-XXXXXXXXXXXXXXXX), generated on this device. */
    val userId: String? get() = sp.getString("user_id", null)
    val registered: Boolean get() = sp.getBoolean("registered", false)
    /** Master switch: browsing activity is collected and uploaded only while true. */
    val sharing: Boolean get() = sp.getBoolean("sharing", false) && userId != null && !revoked
    val searchConsent: Boolean get() = sharing && sp.getBoolean("search_consent", false)
    /** What the Tracker actually does: the user's switches AND the admin's remote kill switches. */
    val collecting: Boolean get() = sharing && Remote.trackingEnabled
    val searchActive: Boolean get() = collecting && searchConsent && Remote.searchAllowed
    /** typed | device-name | google | guest */
    val nameSource: String get() = sp.getString("name_source", "typed") ?: "typed"
    val googleEmail: String? get() = sp.getString("google_email", null)
    val revoked: Boolean get() = sp.getBoolean("revoked", false)
    val erasePending: Boolean get() = sp.getBoolean("erase_pending", false)
    val consentDirty: Boolean get() = sp.getBoolean("consent_dirty", false)
    var lastSyncAt: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) { sp.edit().putLong("last_sync", v).apply() }
    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) { sp.edit().putString("last_error", v).apply() }

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun changed() = android.os.Handler(android.os.Looper.getMainLooper()).post { listeners.toList().forEach { it() } }

    private fun randomId(): String {
        val r = SecureRandom()
        return "WB-" + (1..16).map { CROCKFORD[r.nextInt(32)] }.joinToString("")
    }

    /** First-launch choice. Generates the unique user ID on this device. */
    fun enroll(name: String, shareActivity: Boolean, shareSearch: Boolean, source: String = "typed") {
        val e = sp.edit().putBoolean("onboarded", true)
        if (shareActivity) {
            val id = randomId()
            val typed = name.trim().take(80)
            e.putString("user_id", id).putString("name", typed.ifEmpty { "Guest " + id.takeLast(4) })
                .putString("name_source", if (typed.isEmpty()) "guest" else source).putBoolean("sharing", true)
                .putBoolean("search_consent", shareSearch).putBoolean("registered", false)
                .putBoolean("revoked", false).putBoolean("erase_pending", false).putBoolean("consent_dirty", false)
        } else e.putBoolean("sharing", false)
        e.apply()
        SyncScheduler.kickNow()
        changed()
    }

    fun setSharing(on: Boolean) {
        val e = sp.edit().putBoolean("sharing", on).putBoolean("consent_dirty", true)
        if (!on) e.putBoolean("search_consent", false)
        e.apply()
        if (!on) { Tracker.stop(); ActivityDb.clear() }
        SyncScheduler.kickNow(); changed()
    }

    fun setSearchConsent(on: Boolean) {
        sp.edit().putBoolean("search_consent", on).putBoolean("consent_dirty", true).apply()
        if (!on) ActivityDb.purgeSearches()   // queued-but-unsent queries are dropped on withdrawal
        SyncScheduler.kickNow(); changed()
    }

    fun setName(n: String) {
        sp.edit().putString("name", n.trim().take(80)).putString("name_source", "typed").putBoolean("consent_dirty", true).apply()
        SyncScheduler.kickNow(); changed()
    }

    fun setGoogle(email: String?) { sp.edit().putString("google_email", email).apply(); changed() }

    /** After Google sign-in: use the Google name unless the user typed their own. */
    fun adoptGoogleName(n: String?) {
        if (n.isNullOrBlank() || nameSource == "typed") return
        sp.edit().putString("name", n.trim().take(80)).putString("name_source", "google").putBoolean("consent_dirty", true).apply()
        SyncScheduler.kickNow(); changed()
    }

    /** Right to erasure: stop collecting now; the worker deletes the cloud copy as soon as there is a connection. */
    fun requestErasure() {
        Tracker.stop(); ActivityDb.clear()
        sp.edit().putBoolean("sharing", false).putBoolean("erase_pending", true).apply()
        SyncScheduler.kickNow(); changed()
    }

    fun markRegistered() { sp.edit().putBoolean("registered", true).apply() }
    fun clearConsentDirty() { sp.edit().putBoolean("consent_dirty", false).apply() }

    /** The cloud no longer knows this user (removed by an administrator): stop sharing, keep nothing queued. */
    fun markRevoked() {
        Tracker.stop(); ActivityDb.clear()
        sp.edit().putBoolean("revoked", true).putBoolean("sharing", false).apply(); changed()
    }

    /**
     * An admin erased this user's data server-side. Rather than leaving the device stuck until someone opens
     * Settings and taps "reset", pick a brand-new user ID (and drop the Firebase sign-in, handled by the caller)
     * so the next sync quietly re-registers as a genuinely fresh user: no old history comes back, and nothing
     * ties the new record to the erased one. The name and sharing choices the person already made are kept, so
     * there is no onboarding dialog to answer again.
     */
    fun reEnrollAfterErase() {
        Tracker.stop(); ActivityDb.clear()
        sp.edit().putString("user_id", randomId())
            .putBoolean("registered", false).putBoolean("revoked", false).putBoolean("erase_pending", false)
            .putBoolean("consent_dirty", false).remove("google_email")   // old link belonged to the erased record
            .apply()
        SyncScheduler.kickNow(); changed()
    }

    /** After the cloud confirmed erasure: the local identity is destroyed too. */
    fun finishErasure() {
        sp.edit().remove("user_id").remove("name").remove("google_email").putBoolean("registered", false)
            .putBoolean("sharing", false).putBoolean("search_consent", false).putBoolean("erase_pending", false)
            .putBoolean("revoked", false).putBoolean("consent_dirty", false).apply()
        changed()
    }

    fun deviceJson(): JSONObject = JSONObject()
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
        .put("os", Build.VERSION.RELEASE ?: "")
        .put("appVersion", BuildConfig.VERSION_NAME)
}
