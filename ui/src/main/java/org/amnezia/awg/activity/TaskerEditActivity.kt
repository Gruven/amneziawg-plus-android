/*
 * Copyright © 2024 AmneziaWG. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivityTaskerBinding
import kotlinx.coroutines.launch

/**
 * Tasker plugin edit activity. Allows the user to select a tunnel and action
 * (up/down/toggle) when configuring a Tasker task.
 */
class TaskerEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskerBinding
    private var tunnelNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val actions = arrayOf(
            getString(R.string.tasker_action_up),
            getString(R.string.tasker_action_down),
            getString(R.string.tasker_action_toggle)
        )
        binding.actionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actions)

        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            tunnelNames = tunnels.map { it.name }
            if (tunnelNames.isEmpty()) {
                Toast.makeText(this@TaskerEditActivity, R.string.tasker_no_tunnels, Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
                return@launch
            }
            binding.tunnelSpinner.adapter = ArrayAdapter(this@TaskerEditActivity, android.R.layout.simple_spinner_dropdown_item, tunnelNames)

            // Restore previous selection if editing
            val prevBundle = intent.getBundleExtra(EXTRA_BUNDLE)
            if (prevBundle != null) {
                val prevTunnel = prevBundle.getString(KEY_TUNNEL)
                val prevAction = prevBundle.getString(KEY_ACTION)
                val tunnelIndex = tunnelNames.indexOf(prevTunnel)
                if (tunnelIndex >= 0) binding.tunnelSpinner.setSelection(tunnelIndex)
                val actionIndex = ACTIONS.indexOf(prevAction)
                if (actionIndex >= 0) binding.actionSpinner.setSelection(actionIndex)
            }
        }

        binding.saveButton.setOnClickListener {
            if (tunnelNames.isEmpty()) {
                setResult(Activity.RESULT_CANCELED)
                finish()
                return@setOnClickListener
            }
            val tunnelName = tunnelNames[binding.tunnelSpinner.selectedItemPosition]
            val action = ACTIONS[binding.actionSpinner.selectedItemPosition]

            val bundle = Bundle().apply {
                putString(KEY_TUNNEL, tunnelName)
                putString(KEY_ACTION, action)
            }

            val blurb = "$tunnelName: ${actions[binding.actionSpinner.selectedItemPosition]}"

            val resultIntent = Intent().apply {
                putExtra(EXTRA_BUNDLE, bundle)
                putExtra(EXTRA_STRING_BLURB, blurb)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    companion object {
        const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
        const val EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
        const val KEY_TUNNEL = "tunnel"
        const val KEY_ACTION = "action"
        val ACTIONS = arrayOf("up", "down", "toggle")
    }
}
