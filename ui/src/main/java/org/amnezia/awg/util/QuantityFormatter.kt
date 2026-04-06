/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.util

import android.os.Build
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
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val rdf = android.icu.text.RelativeDateTimeFormatter.getInstance()
                rdf.format(
                    android.icu.text.RelativeDateTimeFormatter.Direction.PLAIN,
                    android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.NOW
                )
            } else {
                Application.get().applicationContext.getString(R.string.latest_handshake_now)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return formatEpochAgoIcu(span)
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

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
    private fun formatEpochAgoIcu(spanSeconds: Long): String {
        var span = spanSeconds
        val measureFormat = android.icu.text.MeasureFormat.getInstance(
            java.util.Locale.getDefault(), android.icu.text.MeasureFormat.FormatWidth.WIDE
        )
        val parts = ArrayList<CharSequence>(4)
        if (span >= 24 * 60 * 60L) {
            val v = span / (24 * 60 * 60L)
            parts.add(measureFormat.format(android.icu.util.Measure(v, android.icu.util.MeasureUnit.DAY)))
            span -= v * (24 * 60 * 60L)
        }
        if (span >= 60 * 60L) {
            val v = span / (60 * 60L)
            parts.add(measureFormat.format(android.icu.util.Measure(v, android.icu.util.MeasureUnit.HOUR)))
            span -= v * (60 * 60L)
        }
        if (span >= 60L) {
            val v = span / 60L
            parts.add(measureFormat.format(android.icu.util.Measure(v, android.icu.util.MeasureUnit.MINUTE)))
            span -= v * 60L
        }
        if (span > 0L)
            parts.add(measureFormat.format(android.icu.util.Measure(span, android.icu.util.MeasureUnit.SECOND)))

        val joined = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            parts.joinToString()
        else
            android.icu.text.ListFormatter.getInstance(
                java.util.Locale.getDefault(),
                android.icu.text.ListFormatter.Type.UNITS,
                android.icu.text.ListFormatter.Width.SHORT
            ).format(parts)

        return Application.get().applicationContext.getString(R.string.latest_handshake_ago, joined)
    }
}