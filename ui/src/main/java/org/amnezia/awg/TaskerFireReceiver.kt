/*
 * Copyright © 2024 AmneziaWG. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.amnezia.awg.activity.TaskerEditActivity
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.util.UserKnobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for Tasker plugin FIRE_SETTING action.
 * Receives the configured tunnel name and action from the Bundle
 * and executes the tunnel state change.
 */
class TaskerFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_FIRE_SETTING) return

        val bundle = intent.getBundleExtra(TaskerEditActivity.EXTRA_BUNDLE) ?: return
        val tunnelName = bundle.getString(TaskerEditActivity.KEY_TUNNEL) ?: return
        val action = bundle.getString(TaskerEditActivity.KEY_ACTION) ?: return

        val state = when (action) {
            "up" -> Tunnel.State.UP
            "down" -> Tunnel.State.DOWN
            "toggle" -> Tunnel.State.TOGGLE
            else -> return
        }

        Log.i(TAG, "Tasker: $action tunnel $tunnelName")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                if (!UserKnobs.allowTaskerPlugin.first()) {
                    Log.w(TAG, "Tasker: plugin not allowed")
                    return@launch
                }
                val tunnels = Application.getTunnelManager().getTunnels()
                val tunnel = tunnels[tunnelName]
                if (tunnel != null) {
                    tunnel.setStateAsync(state)
                    Log.i(TAG, "Tasker: tunnel $tunnelName set to $state")
                } else {
                    Log.e(TAG, "Tasker: tunnel $tunnelName not found")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Tasker: error setting tunnel state", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AmneziaWG/Tasker"
        private const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    }
}
