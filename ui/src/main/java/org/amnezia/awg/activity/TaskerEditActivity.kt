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
    private var displayNames: List<String> = emptyList()
    private var tunnelValues: List<String> = emptyList()
    private var selectedTunnelIndex = 0
    private var selectedActionIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val actions = arrayOf(
            getString(R.string.tasker_action_up),
            getString(R.string.tasker_action_down),
            getString(R.string.tasker_action_toggle)
        )
        val actionDropdown = binding.actionDropdown
        actionDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, actions))
        actionDropdown.setText(actions[0], false)
        actionDropdown.setOnItemClickListener { _, _, position, _ -> selectedActionIndex = position }

        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            val realNames = tunnels.map { it.name }
            if (realNames.isEmpty()) {
                Toast.makeText(this@TaskerEditActivity, R.string.tasker_no_tunnels, Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
                return@launch
            }

            val lastUsedLabel = getString(R.string.tasker_last_used_tunnel)
            displayNames = listOf(lastUsedLabel) + realNames
            tunnelValues = listOf(LAST_USED) + realNames

            val tunnelDropdown = binding.tunnelDropdown
            tunnelDropdown.setAdapter(ArrayAdapter(this@TaskerEditActivity, android.R.layout.simple_dropdown_item_1line, displayNames))
            tunnelDropdown.setText(displayNames[0], false)
            tunnelDropdown.setOnItemClickListener { _, _, position, _ -> selectedTunnelIndex = position }

            // Restore previous selection if editing
            val prevBundle = intent.getBundleExtra(EXTRA_BUNDLE)
            if (prevBundle != null) {
                val prevTunnel = prevBundle.getString(KEY_TUNNEL)
                val prevAction = prevBundle.getString(KEY_ACTION)
                val tunnelIndex = tunnelValues.indexOf(prevTunnel)
                if (tunnelIndex >= 0) {
                    selectedTunnelIndex = tunnelIndex
                    tunnelDropdown.setText(displayNames[tunnelIndex], false)
                }
                val actionIndex = ACTIONS.indexOf(prevAction)
                if (actionIndex >= 0) {
                    selectedActionIndex = actionIndex
                    actionDropdown.setText(actions[actionIndex], false)
                }
            }
        }

        binding.saveButton.setOnClickListener {
            if (tunnelValues.isEmpty()) {
                setResult(Activity.RESULT_CANCELED)
                finish()
                return@setOnClickListener
            }
            val tunnelValue = tunnelValues[selectedTunnelIndex]
            val action = ACTIONS[selectedActionIndex]

            val bundle = Bundle().apply {
                putString(KEY_TUNNEL, tunnelValue)
                putString(KEY_ACTION, action)
            }

            val blurb = "${displayNames[selectedTunnelIndex]}: ${actions[selectedActionIndex]}"

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
        const val LAST_USED = "__last_used__"
        val ACTIONS = arrayOf("up", "down", "toggle")
    }
}
