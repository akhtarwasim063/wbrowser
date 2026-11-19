package dev.wbrowser.nativeapp

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Account: just identity. Your name and (optionally) Google sign-in. Everything about activity
 * sharing, search consent, what is collected and deleting your data lives in Settings > Privacy
 * (see PrivacyActivity) so the two are not mixed into one screen.
 */
class AccountActivity : AppCompatActivity() {
    private lateinit var box: LinearLayout
    private val listener: () -> Unit = { build() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String, size: Float = 15f) = TextView(this).apply {
        text = t; textSize = size; setPadding(0, dp(6), 0, dp(6))
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
    }
    private fun outlined(t: String, click: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = t; setOnClickListener { click() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tb = MaterialToolbar(this).apply {
            title = "Account"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationIconTint(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setNavigationOnClickListener { finish() }
        }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(tb, LinearLayout.LayoutParams(-1, dp(56)))
            addView(ScrollView(this@AccountActivity).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        Account.addListener(listener)
        build()
    }

    private fun build() {
        box.removeAllViews()
        when {
            Account.erasePending -> box.addView(label("Deleting your data...\nYour data will be removed from the server as soon as the phone is online."))
            Account.revoked -> {
                box.addView(label("This account was removed by the administrator."))
                box.addView(outlined("Reset and start over") { Account.finishErasure() })
            }
            Account.userId == null -> {
                box.addView(label("No account yet."))
                box.addView(MaterialButton(this).apply {
                    text = "Set up with your name"
                    setOnClickListener { NameDialog.show(this@AccountActivity) }
                })
            }
            else -> buildAccount()
        }
    }

    private fun buildAccount() {
        box.addView(label(Account.name.ifEmpty { "Unnamed" }, 20f))
        box.addView(label(when (Account.nameSource) {
            "device-name" -> "Name taken from your phone's device name. You can change it below."
            "google" -> "Name taken from your Google account. You can change it below."
            "guest" -> "No name was set, so a guest name was used. You can change it below."
            else -> ""
        }, 12f))
        box.addView(label("User ID: ${Account.userId}", 13f))
        box.addView(outlined("Change name") {
            val input = EditText(this).apply { setText(Account.name); setSingleLine() }
            MaterialAlertDialogBuilder(this).setTitle("Your name").setView(input)
                .setPositiveButton("Save") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { Account.setName(it) } }
                .setNegativeButton("Cancel", null).show()
        })

        if (GoogleLink.available) {
            box.addView(label(" ", 8f))
            val email = Account.googleEmail
            box.addView(label(if (email != null) "Google account: $email" else "Google account: not linked", 14f))
            if (email != null) box.addView(outlined("Unlink Google") {
                GoogleLink.unlink { r -> r.onFailure { Toast.makeText(this, "Could not unlink: ${it.message}", Toast.LENGTH_LONG).show() } }
            }) else box.addView(outlined("Sign in with Google (optional)") {
                GoogleLink.signIn(this) { r ->
                    r.onSuccess { id -> GoogleLink.link(id) { l -> l.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() } } }
                        .onFailure { Toast.makeText(this, "Google sign-in cancelled", Toast.LENGTH_SHORT).show() }
                }
            })
        }
    }

    override fun onResume() { super.onResume(); build() }
    override fun onDestroy() { Account.removeListener(listener); super.onDestroy() }
}
