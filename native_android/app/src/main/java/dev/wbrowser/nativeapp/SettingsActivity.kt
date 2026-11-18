package dev.wbrowser.nativeapp

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.StorageController

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = MaterialToolbar(this).apply {
            title = "Settings"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        val container = FrameLayout(this).apply { id = R.id.settingsContainer }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(toolbar, LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
            addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, PrefsFragment()).commit()
        }
    }

    class PrefsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val statusListener: () -> Unit = { findPreference<Preference>("ubo_status")?.summary = Engine.uboStatus }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("ubo_status")?.apply {
                summary = Engine.uboStatus
                setOnPreferenceClickListener { Engine.ensureUbo(force = true); true }
                title = "uBlock Origin (tap to reinstall / update)"
            }
            findPreference<Preference>("ubo_dashboard")?.setOnPreferenceClickListener {
                val base = Engine.uboBaseUrl
                if (base == null) {
                    android.widget.Toast.makeText(requireContext(), "uBlock Origin: ${Engine.uboStatus}", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(requireContext(), MainActivity::class.java).setData(Uri.parse(base + "dashboard.html")))
                    requireActivity().finish()
                }
                true
            }
            findPreference<Preference>("clear_data")?.setOnPreferenceClickListener { showClearDialog(); true }
            findPreference<Preference>("account")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AccountActivity::class.java)); true
            }
            findPreference<Preference>("about")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext()).setTitle("WBrowser 1.0")
                    .setMessage("A private, ad-free browser.\n\nEngine: Gecko (Mozilla)\nAd blocking: uBlock Origin + built-in shields")
                    .setPositiveButton("OK", null).show()
                true
            }
        }

        private fun showClearDialog() {
            val labels = arrayOf("Browsing history", "Cookies and site data", "Cached images and files")
            val checked = booleanArrayOf(true, true, true)
            MaterialAlertDialogBuilder(requireContext()).setTitle("Clear browsing data")
                .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("Clear") { _, _ ->
                    if (checked[0]) HistoryDb.clearAll()
                    var flags = 0L
                    if (checked[1]) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or
                        StorageController.ClearFlags.AUTH_SESSIONS
                    if (checked[2]) flags = flags or StorageController.ClearFlags.ALL_CACHES
                    if (flags != 0L) Engine.runtime.storageController.clearData(flags)
                    android.widget.Toast.makeText(requireContext(), "Cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null).show()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
            Engine.applyAll()
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            Engine.addStatusListener(statusListener)
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            Engine.removeStatusListener(statusListener)
        }
    }
}
