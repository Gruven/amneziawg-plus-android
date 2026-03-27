/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.amnezia.awg.Application
import org.amnezia.awg.QuickTileService
import org.amnezia.awg.R
import org.amnezia.awg.backend.AwgQuickBackend
import org.amnezia.awg.backend.RootGoBackend
import org.amnezia.awg.preference.PreferencesPreferenceDataStore
import org.amnezia.awg.util.AdminKnobs
import org.amnezia.awg.util.UserKnobs
import android.util.Log
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interface for changing application-global persistent settings.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (supportFragmentManager.findFragmentById(R.id.settings_container) == null) {
            supportFragmentManager.commit {
                add(R.id.settings_container, SettingsFragment())
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private fun restartApp() {
            try {
                Toast.makeText(requireContext(), R.string.success_application_will_restart, Toast.LENGTH_LONG).show()
                val intent = requireContext().packageManager.getLaunchIntentForPackage(requireContext().packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                requireActivity().finishAffinity()
                if (intent != null) startActivity(intent)
            } catch (e: Throwable) {
                Log.w("AmneziaWG/Settings", "Restart failed", e)
            }
            Runtime.getRuntime().exit(0)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, key: String?) {
            preferenceManager.preferenceDataStore = PreferencesPreferenceDataStore(lifecycleScope, Application.getPreferencesDataStore())
            addPreferencesFromResource(R.xml.preferences)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || QuickTileService.isAdded) {
                val quickTile = preferenceManager.findPreference<Preference>("quick_tile")
                quickTile?.parent?.removePreference(quickTile)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val darkTheme = preferenceManager.findPreference<Preference>("dark_theme")
                darkTheme?.parent?.removePreference(darkTheme)
            }
            if (AdminKnobs.disableConfigExport) {
                val zipExporter = preferenceManager.findPreference<Preference>("zip_exporter")
                zipExporter?.parent?.removePreference(zipExporter)
            }
            val awgQuickOnlyPrefs = arrayOf(
                preferenceManager.findPreference("tools_installer"),
                preferenceManager.findPreference("restore_on_boot"),
                preferenceManager.findPreference<Preference>("multiple_tunnels")
            ).filterNotNull()
            awgQuickOnlyPrefs.forEach { it.isVisible = false }
            lifecycleScope.launch {
                if (Application.getBackend() is AwgQuickBackend) {
                    awgQuickOnlyPrefs.forEach { it.isVisible = true }
                } else {
                    awgQuickOnlyPrefs.forEach { it.parent?.removePreference(it) }
                }
            }
            preferenceManager.findPreference<Preference>("log_viewer")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LogViewerActivity::class.java))
                true
            }
            val kernelModuleEnabler = preferenceManager.findPreference<Preference>("kernel_module_enabler")
            if (AwgQuickBackend.hasKernelSupport()) {
                lifecycleScope.launch {
                    if (Application.getBackend() !is AwgQuickBackend) {
                        try {
                            withContext(Dispatchers.IO) { Application.getRootShell().start() }
                        } catch (_: Throwable) {
                            kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
                        }
                    }
                }
            } else {
                kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
            }

            val rootModePref = preferenceManager.findPreference<CheckBoxPreference>("enable_root_mode")
            lifecycleScope.launch {
                rootModePref?.isChecked = UserKnobs.enableRootMode.first()
            }
            rootModePref?.setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as Boolean
                if (enable) {
                    lifecycleScope.launch {
                        // Step 1: verify root access separately
                        val rootAvailable = try {
                            withContext(Dispatchers.IO) { Application.getRootShell().start() }
                            true
                        } catch (e: Throwable) {
                            Log.e("AmneziaWG/Settings", "Root check failed", e)
                            false
                        }
                        if (!rootAvailable) {
                            rootModePref.isChecked = false
                            Toast.makeText(requireContext(), R.string.root_mode_error, Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        // Step 2: save and restart
                        UserKnobs.setEnableRootMode(true)
                        restartApp()
                    }
                    false
                } else {
                    lifecycleScope.launch {
                        UserKnobs.setEnableRootMode(false)
                        restartApp()
                    }
                    false
                }
            }
        }
    }
}
