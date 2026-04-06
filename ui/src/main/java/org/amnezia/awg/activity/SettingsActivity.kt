/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
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
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.preference.PreferencesPreferenceDataStore
import org.amnezia.awg.util.AdminKnobs
import org.amnezia.awg.util.UserKnobs
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.core.net.toUri
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
        private var batteryOptPref: CheckBoxPreference? = null

        private val batteryOptLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            updateBatteryOptimizationState()
        }

        private fun updateBatteryOptimizationState() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                batteryOptPref?.parent?.removePreference(batteryOptPref!!)
                return
            }
            val pm = requireContext().getSystemService(PowerManager::class.java)
            val ignored = pm.isIgnoringBatteryOptimizations(requireContext().packageName)
            if (ignored) {
                batteryOptPref?.parent?.removePreference(batteryOptPref!!)
            } else {
                batteryOptPref?.isChecked = false
            }
        }


        private suspend fun shutdownTunnelsAndRestart() {
            try {
                val manager = Application.getTunnelManager()
                for (tunnel in manager.getTunnels()) {
                    if (tunnel.state == Tunnel.State.UP) {
                        try {
                            manager.setTunnelState(tunnel, Tunnel.State.DOWN)
                        } catch (e: Throwable) {
                            Log.w("AmneziaWG/Settings", "Failed to stop tunnel ${tunnel.name}", e)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w("AmneziaWG/Settings", "Failed to shut down tunnels before restart", e)
            }
            restartApp()
        }

        private fun restartApp() {
            try {
                Toast.makeText(requireContext(), R.string.success_application_will_restart, Toast.LENGTH_LONG).show()
                val intent = Intent(requireContext(), SettingsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                Application.get().startActivity(intent)
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

            // Battery optimization toggle (no root needed)
            batteryOptPref = preferenceManager.findPreference("disable_battery_optimization")
            updateBatteryOptimizationState()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                batteryOptPref?.setOnPreferenceChangeListener { _, _ ->
                    @Suppress("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${requireContext().packageName}".toUri()
                    }
                    batteryOptLauncher.launch(intent)
                    false
                }
            }

            // Process protection toggle (root only — hidden when root mode is off)
            val processProtPref = preferenceManager.findPreference<CheckBoxPreference>("enable_process_protection")
            lifecycleScope.launch {
                val rootEnabled = UserKnobs.enableRootMode.first()
                if (!rootEnabled) {
                    processProtPref?.parent?.removePreference(processProtPref)
                } else {
                    processProtPref?.isChecked = UserKnobs.enableProcessProtection.first()
                }
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
                        shutdownTunnelsAndRestart()
                    }
                    false
                } else {
                    lifecycleScope.launch {
                        if (UserKnobs.enableProcessProtection.first()) {
                            try {
                                val rootShell = Application.getRootShell()
                                val myPid = android.os.Process.myPid()
                                withContext(Dispatchers.IO) {
                                    rootShell.run(null, "echo 0 > /proc/$myPid/oom_score_adj")
                                }
                            } catch (_: Throwable) { }
                        }
                        UserKnobs.setEnableProcessProtection(false)
                        UserKnobs.setEnableRootMode(false)
                        shutdownTunnelsAndRestart()
                    }
                    false
                }
            }
            processProtPref?.setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as Boolean
                lifecycleScope.launch {
                    val myPid = android.os.Process.myPid()
                    try {
                        val rootShell = Application.getRootShell()
                        withContext(Dispatchers.IO) { rootShell.start() }
                        if (enable) {
                            val oomRet = withContext(Dispatchers.IO) {
                                rootShell.run(null, "echo -1000 > /proc/$myPid/oom_score_adj")
                            }
                            if (oomRet != 0) throw Exception("oom_score_adj failed: $oomRet")
                        } else {
                            val oomRet = withContext(Dispatchers.IO) {
                                rootShell.run(null, "echo 0 > /proc/$myPid/oom_score_adj")
                            }
                            if (oomRet != 0) throw Exception("oom_score_adj cleanup failed: $oomRet")
                        }
                        UserKnobs.setEnableProcessProtection(enable)
                        processProtPref.isChecked = enable
                    } catch (e: Throwable) {
                        Log.e("AmneziaWG/Settings", "Process protection failed", e)
                        processProtPref.isChecked = !enable
                        Toast.makeText(requireContext(), R.string.process_protection_error, Toast.LENGTH_SHORT).show()
                    }
                }
                false
            }

            val remoteIntentsPref = preferenceManager.findPreference<CheckBoxPreference>("allow_remote_control_intents")
            val tokenPref = preferenceManager.findPreference<Preference>("remote_control_token")

            fun updateTokenVisibility(enabled: Boolean) {
                tokenPref?.isVisible = enabled
            }

            lifecycleScope.launch {
                val enabled = UserKnobs.allowRemoteControlIntents.first()
                updateTokenVisibility(enabled)
                if (enabled) {
                    val token = UserKnobs.remoteControlToken.first()
                    tokenPref?.summary = token ?: "—"
                }
            }

            remoteIntentsPref?.setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as Boolean
                lifecycleScope.launch {
                    if (enable) {
                        val existing = UserKnobs.remoteControlToken.first()
                        if (existing.isNullOrBlank()) {
                            val token = UserKnobs.generateToken()
                            UserKnobs.setRemoteControlToken(token)
                            tokenPref?.summary = token
                        }
                    }
                    updateTokenVisibility(enable)
                }
                true
            }

            tokenPref?.setOnPreferenceClickListener {
                lifecycleScope.launch {
                    val token = UserKnobs.remoteControlToken.first() ?: ""
                    val pad = (24 * resources.displayMetrics.density).toInt()

                    val editText = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
                        setText(token)
                        setSingleLine()
                    }
                    val inputLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(editText)
                    }
                    val regenButton = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "\uD83C\uDFB2"
                        minWidth = 0
                        minimumWidth = 0
                        setPadding(pad / 2, 0, pad / 2, 0)
                        contentDescription = getString(R.string.remote_control_token_regenerate)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            tooltipText = getString(R.string.remote_control_token_regenerate)
                        setOnClickListener {
                            editText.setText(UserKnobs.generateToken())
                        }
                    }
                    val row = android.widget.LinearLayout(requireContext()).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(pad, pad / 2, pad, 0)
                        addView(inputLayout)
                        addView(regenButton)
                    }

                    val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.remote_control_token_title)
                        .setView(row)
                        .setPositiveButton(R.string.save, null)
                        .setNeutralButton(R.string.remote_control_token_copy, null)
                        .create()
                    dialog.show()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newToken = editText.text.toString().trim()
                        if (newToken.isEmpty()) {
                            inputLayout.error = getString(R.string.remote_control_token_empty)
                            return@setOnClickListener
                        }
                        inputLayout.error = null
                        lifecycleScope.launch {
                            UserKnobs.setRemoteControlToken(newToken)
                            tokenPref.summary = newToken
                        }
                        dialog.dismiss()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val clipboard = requireContext().getSystemService<ClipboardManager>()
                        clipboard?.setPrimaryClip(ClipData.newPlainText("token", editText.text.toString()))
                        Toast.makeText(requireContext(), R.string.remote_control_token_copied, Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
        }
    }
}
