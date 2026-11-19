package dev.wbrowser.nativeapp

import android.os.Bundle
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Privacy: activity sharing, search consent, what is collected, and the right to erase your data.
 * Kept separate from Account (name / Google sign-in) on purpose - this screen is the one place all
 * the consent and disclosure controls live, so they stay visible and are never bundled away.
 * Activity uploads happen automatically in the background (see SyncWorker); this screen only shows
 * status and lets the user control consent - there is deliberately no "Sync now" button.
 */
class PrivacyActivity : AppCompatActivity() {
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
            title = "Privacy"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationIconTint(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setNavigationOnClickListener { finish() }
        }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(tb, LinearLayout.LayoutParams(-1, dp(56)))
            addView(ScrollView(this@PrivacyActivity).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        Account.addListener(listener)
        build()
    }

    private fun build() {
        box.removeAllViews()
        when {
            Account.erasePending -> box.addView(label("Deleting your data...\nYour data will be removed from the server as soon as the phone is online. Sharing is already stopped."))
            Account.revoked -> box.addView(label("This account was removed by the administrator. Sharing is off. Go to Settings > Account to reset and start over."))
            Account.userId == null -> box.addView(label("No account yet. Set one up in Settings > Account first."))
            else -> buildPrivacy()
        }
    }

    private fun buildPrivacy() {
        Remote.announcement.takeIf { it.isNotBlank() }?.let { box.addView(label("📢 $it", 14f).apply { setPadding(0, dp(4), 0, dp(12)) }) }
        if (!Remote.trackingEnabled) box.addView(label("Collection is paused by the administrator.", 13f))

        box.addView(MaterialSwitch(this).apply {
            text = "Share browsing activity"; isChecked = Account.sharing
            setPadding(0, dp(12), 0, dp(4))
            setOnCheckedChangeListener { _, c -> if (c != Account.sharing) Account.setSharing(c) }
        })
        box.addView(label("Websites, URLs, visit times and time spent are uploaded automatically in the background when online. Private tabs are never shared.", 12f))

        box.addView(MaterialSwitch(this).apply {
            text = "Share my search queries"; isChecked = Account.searchConsent; isEnabled = Account.sharing
            setPadding(0, dp(12), 0, dp(4))
            setOnCheckedChangeListener { _, c -> if (c != Account.searchConsent) Account.setSearchConsent(c) }
        })
        box.addView(label("On by default. Turning it off also deletes search queries already stored on the server.", 12f))

        val pending = ActivityDb.count()
        val last = Account.lastSyncAt.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "not yet"
        box.addView(label("Background sync: ${if (!Cloud.ready) "cloud not configured" else if (Account.registered) "connected" else "waiting for connection"}\n" +
            "Waiting to upload: $pending item(s) · last upload: $last" + (Account.lastError?.let { "\n$it" } ?: ""), 13f).apply { setPadding(0, dp(16), 0, dp(8)) })

        box.addView(outlined("What is collected?") {
            MaterialAlertDialogBuilder(this).setTitle("Privacy notice").setMessage(PrivacyNotice.TEXT).setPositiveButton("OK", null).show()
        })
        // full policy / terms are public pages on our Firebase Hosting site
        Cloud.siteUrl()?.let { site ->
            fun page(path: String) = runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(site + path))) }
                .onFailure { Toast.makeText(this, "Could not open the page", Toast.LENGTH_SHORT).show() }
            box.addView(outlined("Privacy policy") { page("/privacy") })
            box.addView(outlined("Terms of service") { page("/terms") })
        }
        box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Delete my data and leave"
            setTextColor(getColor(R.color.wb_danger))
            setOnClickListener {
                MaterialAlertDialogBuilder(this@PrivacyActivity).setTitle("Delete everything?")
                    .setMessage("This stops sharing and permanently deletes your account and all activity stored on the server.")
                    .setPositiveButton("Delete") { _, _ -> Account.requestErasure() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
    }

    override fun onResume() { super.onResume(); build() }
    override fun onDestroy() { Account.removeListener(listener); super.onDestroy() }
}
