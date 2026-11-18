package dev.wbrowser.nativeapp

import android.content.Context

/**
 * Settings the superadmin controls from the admin console (Firestore doc config/app), cached on the phone.
 * Defaults mean "everything on" so the app keeps working before the first fetch or when offline.
 * The security rules enforce trackingEnabled / searchAllowed server-side too; this only makes the app stop sooner.
 */
object Remote {
    private val sp get() = App.instance.getSharedPreferences("wb_remote", Context.MODE_PRIVATE)

    val trackingEnabled: Boolean get() = sp.getBoolean("tracking", true)
    val searchAllowed: Boolean get() = sp.getBoolean("search", true)
    /** How often activity is checkpointed and pushed, in seconds (admin range 5..600). */
    val intervalSec: Int get() = sp.getInt("interval", 15).coerceIn(5, 600)
    val announcement: String get() = sp.getString("announcement", "") ?: ""

    fun apply(tracking: Boolean, search: Boolean, interval: Int, announcement: String) {
        val before = trackingEnabled
        sp.edit().putBoolean("tracking", tracking).putBoolean("search", search).putInt("interval", interval).putString("announcement", announcement).apply()
        if (before && !tracking) { Tracker.stop(); ActivityDb.clear() }   // kill switch: drop anything pending immediately
        Account.changed()
    }
}
