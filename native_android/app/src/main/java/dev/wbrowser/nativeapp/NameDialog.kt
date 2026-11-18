package dev.wbrowser.nativeapp

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * First install: ask for a name. It is optional. If left empty the app uses, in order:
 * the phone's own device name (when it looks like a person's name), then a guest name.
 * "Use Google" (shown when Google sign-in is set up) takes the name and email from the user's Google account.
 */
object NameDialog {
    fun show(activity: Activity, onDone: () -> Unit = {}) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            hint = "Your name (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(80))
        }
        val holder = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("Welcome to WBrowser")
            .setMessage("What is your name? Leave it empty to use your phone's name.")
            .setView(holder)
            .setCancelable(false)
            .setPositiveButton("Continue", null)
        if (GoogleLink.available) builder.setNeutralButton("Use Google", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = input.text.toString().trim()
                if (typed.isNotEmpty()) Account.enroll(typed, shareActivity = true, shareSearch = false, source = "typed")
                else {
                    val device = NameResolver.fromDevice(activity)
                    Account.enroll(device.orEmpty(), shareActivity = true, shareSearch = false, source = "device-name")  // empty => guest
                }
                dialog.dismiss(); onDone()
            }
            if (GoogleLink.available) dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                GoogleLink.signIn(activity) { r ->
                    r.onSuccess { id ->
                        Account.enroll(id.name.orEmpty(), shareActivity = true, shareSearch = false, source = "google")
                        GoogleLink.link(id) { l -> l.onFailure { Toast.makeText(activity, "Could not link Google now: ${it.message}", Toast.LENGTH_LONG).show() } }
                        dialog.dismiss(); onDone()
                    }.onFailure { Toast.makeText(activity, "Google sign-in cancelled", Toast.LENGTH_SHORT).show() }
                }
            }
        }
        dialog.show()
    }
}

/** Shown on demand in Settings > Account and privacy ("What is collected?"). */
object PrivacyNotice {
    const val TEXT = """What is shared with the administrator of this browser

- the websites and page addresses (URLs) you visit, and when
- how long you spend on each page and how long each browsing session lasts
- your name (typed, taken from your phone's device name, or from Google if you choose), your generated user ID
- your phone make, model, Android version, language and time zone, and the app version
- optionally, your Google name and email, if you sign in with Google
- optionally, your search queries, only if you switch that on in Account and privacy

Never collected: passwords, cookies, login tokens, page contents, form data, your phone number, contacts, or anything from private tabs. Sensitive parts of addresses (tokens, codes, keys) are removed on your phone before anything is sent.

You can pause sharing, turn search sharing on or off, unlink Google, or delete everything the server holds about you at any time in Settings > Account and privacy."""
}
