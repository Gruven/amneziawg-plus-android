/*
 * Copyright © 2024 AmneziaWG. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.backend;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import org.amnezia.awg.util.LogListener;
import org.amnezia.awg.util.NonNullForAll;
import org.amnezia.awg.util.RootShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.Nullable;

import static org.amnezia.awg.backend.RootGoBackend.EXTRA_CONNECTED;
import static org.amnezia.awg.backend.RootGoBackend.EXTRA_TUNNEL_NAME;
import static org.amnezia.awg.backend.RootGoBackend.NOTIFICATION_ID;
import static org.amnezia.awg.backend.RootNetworkManager.ENDPOINT_IPS_FILE;
import static org.amnezia.awg.backend.RootNetworkManager.SYSCTL_FILE;

/**
 * Foreground service that keeps the app process alive while root tunnel is active.
 * Shows a persistent notification with the tunnel name.
 * On restart after crash (null intent / START_STICKY), cleans up stale
 * networking resources and stops itself.
 */
@NonNullForAll
public class RootTunnelService extends Service {
    private static final String TAG = "AmneziaWG/RootGoBackend";

    @Override
    public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
        final String tunnelName = intent != null ? intent.getStringExtra(EXTRA_TUNNEL_NAME) : null;

        // Restart after crash: intent is null, tunnel is dead — clean up and exit
        if (tunnelName == null) {
            LogListener.w(TAG, "RootTunnelService restarted after crash — running cleanup");
            final String cleanupText = getLocalizedString("root_tunnel_notification_cleanup", "Cleaning up\u2026");
            showNotification(cleanupText, "");
            new Thread(() -> {
                try {
                    cleanupAfterCrash();
                } finally {
                    stopSelf();
                }
            }, "RootTunnelService-Cleanup").start();
            return START_NOT_STICKY;
        }

        final boolean connected = intent.getBooleanExtra(EXTRA_CONNECTED, false);
        final String status = connected
                ? getLocalizedString("root_tunnel_notification_connected", "Connected")
                : getLocalizedString("root_tunnel_notification_connecting", "Connecting\u2026");
        showNotification(tunnelName, status);
        return START_STICKY;
    }

    @SuppressLint({"DiscouragedApi", "ForegroundServiceType", "UnspecifiedImmutableFlag"})
    @SuppressWarnings("deprecation")
    private void showNotification(final String title, final String text) {
        final Notification.Builder builder = new Notification.Builder(this);
        builder.setPriority(Notification.PRIORITY_LOW);

        int iconRes = getResources().getIdentifier("ic_notification", "drawable", getPackageName());
        if (iconRes == 0)
            iconRes = getApplicationInfo().icon;

        final Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launchIntent, 0));
        }

        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(iconRes)
                .setOngoing(true);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    /**
     * Cleanup networking resources using a temporary root shell.
     * Delegates to {@link RootNetworkManager#performNetworkCleanup}
     * without depending on a RootGoBackend instance.
     */
    private void cleanupAfterCrash() {
        try {
            final RootShell shell = new RootShell(getApplicationContext());

            // Load saved endpoint IPs from file
            final List<String> endpointIps = new ArrayList<>();
            final File file = new File(getApplicationContext().getCacheDir(), ENDPOINT_IPS_FILE);
            if (file.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) endpointIps.add(line);
                    }
                } catch (final Exception e) {
                    LogListener.w(TAG, "Failed to load endpoint IPs: " + e.getMessage());
                }
                file.delete();
            }

            // Load saved sysctl values from file
            String ipv4Forward = "0";
            String ipv6Forward = "0";
            String rpFilterAll = "1";
            String beLiberal = "0";
            final File fwdFile = new File(getApplicationContext().getCacheDir(), SYSCTL_FILE);
            if (fwdFile.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(fwdFile))) {
                    final String line4 = br.readLine();
                    final String line6 = br.readLine();
                    final String lineRp = br.readLine();
                    final String lineBl = br.readLine();
                    if (line4 != null) ipv4Forward = RootNetworkManager.sanitizeForwardValue(line4.trim());
                    if (line6 != null) ipv6Forward = RootNetworkManager.sanitizeForwardValue(line6.trim());
                    if (lineRp != null) rpFilterAll = RootNetworkManager.sanitizeSysctlValue(lineRp.trim());
                    if (lineBl != null) beLiberal = RootNetworkManager.sanitizeSysctlValue(lineBl.trim());
                } catch (final Exception e) {
                    LogListener.w(TAG, "Failed to load sysctl values: " + e.getMessage());
                }
                fwdFile.delete();
            }

            RootNetworkManager.performNetworkCleanup(shell, android.os.Process.myUid(),
                    null, false, endpointIps, ipv4Forward, ipv6Forward,
                    rpFilterAll, beLiberal);

            shell.stop();
            LogListener.i(TAG, "Post-crash cleanup completed");
        } catch (final Exception e) {
            LogListener.w(TAG, "Post-crash cleanup failed: " + e.getMessage());
        }
    }

    @SuppressLint("DiscouragedApi")
    private String getLocalizedString(final String name, final String fallback) {
        final int id = getApplicationContext().getResources()
                .getIdentifier(name, "string", getPackageName());
        if (id != 0) {
            try {
                return getApplicationContext().getString(id);
            } catch (final Exception e) {
                LogListener.w(TAG, "Failed to get string " + name + ": " + e.getMessage());
            }
        }
        return fallback;
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable final Intent intent) {
        return null;
    }
}
