package dev.wbrowser.nativeapp

import android.content.Context
import android.provider.Settings

/**
 * Works out a display name when the user did not type one. Order:
 *   1. the name typed by the user (handled by the caller)
 *   2. the device name from phone Settings, used as-is ("Amir's phone" -> "Amir's phone")
 *   3. the Google account name, if the user chooses "Use Google" (official sign-in, never a password)
 *   4. a neutral guest name built from the generated user ID
 * Nothing is read silently beyond the device name, which any app may read and which the user set themselves.
 */
object NameResolver {
    /** The device's own name from phone Settings, trimmed, or null when there isn't one. */
    fun fromDevice(ctx: Context): String? {
        val raw = (Settings.Global.getString(ctx.contentResolver, "device_name")
            ?: Settings.Secure.getString(ctx.contentResolver, "bluetooth_name"))?.trim().orEmpty()
        return raw.take(80).ifEmpty { null }
    }
}
