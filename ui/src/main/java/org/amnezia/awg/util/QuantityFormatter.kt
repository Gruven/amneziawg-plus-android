/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.util

import org.amnezia.awg.Application
import org.amnezia.awg.R

object QuantityFormatter {
    fun formatBytes(bytes: Long): String {
        val context = Application.get().applicationContext
        return when {
            bytes < 1024 -> context.getString(R.string.transfer_bytes, bytes)
            bytes < 1024 * 1024 -> context.getString(R.string.transfer_kibibytes, bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> context.getString(R.string.transfer_mibibytes, bytes / (1024.0 * 1024.0))
            bytes < 1024 * 1024 * 1024 * 1024L -> context.getString(R.string.transfer_gibibytes, bytes / (1024.0 * 1024.0 * 1024.0))
            else -> context.getString(R.string.transfer_tibibytes, bytes / (1024.0 * 1024.0 * 1024.0) / 1024.0)
        }
    }

    fun formatEpochAgo(epochMillis: Long): String {
        var span = (System.currentTimeMillis() - epochMillis) / 1000

        if (span <= 0L) {
            return Application.get().applicationContext.getString(R.string.latest_handshake_now)
        }

        val context = Application.get().applicationContext
        val parts = ArrayList<String>(4)
        if (span >= 24 * 60 * 60L) {
            val v = (span / (24 * 60 * 60L)).toInt()
            parts.add(context.getString(R.string.duration_days, v))
            span -= v * (24 * 60 * 60L)
        }
        if (span >= 60 * 60L) {
            val v = (span / (60 * 60L)).toInt()
            parts.add(context.getString(R.string.duration_hours, v))
            span -= v * (60 * 60L)
        }
        if (span >= 60L) {
            val v = (span / 60L).toInt()
            parts.add(context.getString(R.string.duration_minutes, v))
            span -= v * 60L
        }
        if (span > 0L)
            parts.add(context.getString(R.string.duration_seconds, span.toInt()))

        return Application.get().applicationContext.getString(R.string.latest_handshake_ago, parts.joinToString(", "))
    }
}