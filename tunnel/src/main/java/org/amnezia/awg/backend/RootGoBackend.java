/*
 * Copyright © 2024 AmneziaWG. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.backend;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import org.amnezia.awg.backend.BackendException.Reason;
import org.amnezia.awg.backend.Tunnel.State;
import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.InetEndpoint;
import org.amnezia.awg.config.InetNetwork;
import org.amnezia.awg.config.Peer;
import org.amnezia.awg.crypto.Key;
import org.amnezia.awg.crypto.KeyFormatException;
import org.amnezia.awg.util.NonNullForAll;
import org.amnezia.awg.util.RootShell;
import org.amnezia.awg.util.SharedLibraryLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import static org.amnezia.awg.GoBackend.*;
import static org.amnezia.awg.backend.RootNetworkManager.TUN_INTERFACE;

/**
 * {@link Backend} implementation that uses root access to create a TUN interface
 * and configure routing via iptables/ip route, bypassing Android VPN API.
 */
@NonNullForAll
public final class RootGoBackend implements Backend {
    private static final int DNS_RESOLUTION_RETRIES = 10;
    private static final String TAG = "AmneziaWG/RootGoBackend";
    private static final long TURN_OFF_TIMEOUT_MS = 5000;
    static final String NOTIFICATION_CHANNEL_ID = "amneziawg_root_tunnel";
    static final int NOTIFICATION_ID = 51820;
    static final String EXTRA_TUNNEL_NAME = "tunnel_name";
    static final String EXTRA_CONNECTED = "connected";

    private final Context context;
    private final RootShell rootShell;
    private final RootNetworkManager networkManager;
    @Nullable private volatile Config currentConfig;
    @Nullable private volatile Tunnel currentTunnel;
    private volatile int currentTunnelHandle = -1;
    private volatile int tunFd = -1;
    @Nullable private volatile Thread statusThread;
    @Nullable private volatile StatusCallback statusCallback;
    // Track zombie awgTurnOff thread on timeout
    @Nullable private volatile Thread zombieTurnOffThread;
    // Network change monitor for endpoint route updates
    @Nullable private ConnectivityManager.NetworkCallback networkCallback;

    public RootGoBackend(final Context context, final RootShell rootShell) {
        SharedLibraryLoader.loadSharedLibrary(context, "wg-go");
        this.context = context;
        this.rootShell = rootShell;
        this.networkManager = new RootNetworkManager(context, rootShell);
        cleanupStaleResources();
    }

    /**
     * Cleanup routing/iptables rules that may remain after a crash or OOM kill.
     * Only runs if the TUN interface exists without an active tunnel handle,
     * indicating a previous unclean shutdown.
     */
    private void cleanupStaleResources() {
        try {
            final List<String> output = new ArrayList<>();
            rootShell.run(output, "ip link show " + TUN_INTERFACE + " 2>/dev/null");
            if (!output.isEmpty()) {
                Log.w(TAG, "Stale TUN interface found — cleaning up after previous crash");
                networkManager.cleanup(tunFd);
                tunFd = -1;
                stopTunnelService();
            }
        } catch (final Exception e) {
            // Root shell not available or interface doesn't exist — nothing to clean up
            Log.v(TAG, "Stale resource check skipped: " + e.getMessage());
        }
    }

    @Override
    public Set<String> getRunningTunnelNames() {
        if (currentTunnel != null) {
            final Set<String> runningTunnels = new ArraySet<>();
            runningTunnels.add(currentTunnel.getName());
            return runningTunnels;
        }
        return Collections.emptySet();
    }

    @Override
    public State getState(final Tunnel tunnel) {
        return currentTunnel == tunnel ? State.UP : State.DOWN;
    }

    @Override
    public Statistics getStatistics(final Tunnel tunnel) {
        final Statistics stats = new Statistics();
        // Snapshot into locals to guard against race condition (check-then-act)
        final Tunnel activeTunnel = currentTunnel;
        final int handle = currentTunnelHandle;
        if (tunnel != activeTunnel || handle == -1)
            return stats;
        final String config = awgGetConfig(handle);
        if (config == null)
            return stats;
        Key key = null;
        long rx = 0;
        long tx = 0;
        long latestHandshakeMSec = 0;
        for (final String line : config.split("\\n")) {
            if (line.startsWith("public_key=")) {
                if (key != null)
                    stats.add(key, rx, tx, latestHandshakeMSec);
                rx = 0;
                tx = 0;
                latestHandshakeMSec = 0;
                try {
                    key = Key.fromHex(line.substring(11));
                } catch (final KeyFormatException ignored) {
                    key = null;
                }
            } else if (line.startsWith("rx_bytes=")) {
                if (key == null) continue;
                try { rx = Long.parseLong(line.substring(9)); } catch (final NumberFormatException ignored) { rx = 0; }
            } else if (line.startsWith("tx_bytes=")) {
                if (key == null) continue;
                try { tx = Long.parseLong(line.substring(9)); } catch (final NumberFormatException ignored) { tx = 0; }
            } else if (line.startsWith("last_handshake_time_sec=")) {
                if (key == null) continue;
                try { latestHandshakeMSec += Long.parseLong(line.substring(24)) * 1000; } catch (final NumberFormatException ignored) { latestHandshakeMSec = 0; }
            } else if (line.startsWith("last_handshake_time_nsec=")) {
                if (key == null) continue;
                try { latestHandshakeMSec += Long.parseLong(line.substring(25)) / 1000000; } catch (final NumberFormatException ignored) { latestHandshakeMSec = 0; }
            }
        }
        if (key != null)
            stats.add(key, rx, tx, latestHandshakeMSec);
        return stats;
    }

    @Override
    public long getLastHandshake(final Tunnel tunnel) {
        // Snapshot into locals to guard against race condition
        final Tunnel activeTunnel = currentTunnel;
        final int handle = currentTunnelHandle;
        if (tunnel != activeTunnel || handle == -1)
            return -3;
        final String config = awgGetConfig(handle);
        if (config == null)
            return -2;
        for (final String line : config.split("\\n")) {
            if (line.startsWith("last_handshake_time_sec=")) {
                try {
                    return Long.parseLong(line.substring(24));
                } catch (final NumberFormatException ignored) {
                    return -2;
                }
            }
        }
        return -1;
    }

    @Override
    public void setStatusCallback(@Nullable final StatusCallback callback) {
        this.statusCallback = callback;
    }

    @SuppressWarnings("MissingPermission") // ACCESS_NETWORK_STATE declared in AndroidManifest
    private void registerNetworkMonitor() {
        final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        // Defensive copy for thread-safe access from callback —
        // endpoint IPs don't change during tunnel lifetime
        final List<String> endpointIps = new ArrayList<>(networkManager.getActiveEndpointIps());

        final ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(final Network network) {
                new Thread(() -> refreshEndpointRoutes(endpointIps), "EndpointRouteRefresh").start();
            }

            @Override
            public void onLost(final Network network) {
                new Thread(() -> refreshEndpointRoutes(endpointIps), "EndpointRouteRefresh").start();
            }
        };

        try {
            cm.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(), cb);
            networkCallback = cb;
        } catch (final Exception e) {
            Log.w(TAG, "Failed to register network monitor: " + e.getMessage());
        }
    }

    private void unregisterNetworkMonitor() {
        final ConnectivityManager.NetworkCallback cb = networkCallback;
        networkCallback = null;
        if (cb == null) return;
        try {
            final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(cb);
        } catch (final Exception e) {
            Log.w(TAG, "Failed to unregister network monitor: " + e.getMessage());
        }
    }

    /**
     * Refreshes WireGuard endpoint routes on network change.
     * Finds the current default route (excluding TUN) and re-routes endpoint traffic through it.
     */
    private void refreshEndpointRoutes(final List<String> endpointIps) {
        if (currentTunnelHandle < 0) return;

        try {
            // IPv4 default route, excluding tunnel interface
            final List<String> routeLines = new ArrayList<>();
            rootShell.run(routeLines, "ip route show table all default 2>/dev/null");

            String v4Via = null, v4Dev = null;
            for (final String line : routeLines) {
                if (line.contains(TUN_INTERFACE)) continue;
                final String[] parts = line.trim().split("\\s+");
                for (int i = 0; i < parts.length - 1; i++) {
                    if ("via".equals(parts[i])) v4Via = parts[i + 1];
                    if ("dev".equals(parts[i])) v4Dev = parts[i + 1];
                }
                if (v4Dev != null) break;
            }

            // IPv6 default route
            routeLines.clear();
            rootShell.run(routeLines, "ip -6 route show table all default 2>/dev/null");

            String v6Via = null, v6Dev = null;
            for (final String line : routeLines) {
                if (line.contains(TUN_INTERFACE)) continue;
                final String[] parts = line.trim().split("\\s+");
                for (int i = 0; i < parts.length - 1; i++) {
                    if ("via".equals(parts[i])) v6Via = parts[i + 1];
                    if ("dev".equals(parts[i])) v6Dev = parts[i + 1];
                }
                if (v6Dev != null) break;
            }

            for (final String ip : endpointIps) {
                if (ip.contains(":")) {
                    if (v6Dev == null) continue;
                    final String cmd = v6Via != null
                            ? "ip -6 route replace " + ip + " via " + v6Via + " dev " + v6Dev + " table main 2>/dev/null"
                            : "ip -6 route replace " + ip + " dev " + v6Dev + " table main 2>/dev/null";
                    rootShell.run(null, cmd);
                } else {
                    if (v4Dev == null) continue;
                    final String cmd = v4Via != null
                            ? "ip route replace " + ip + " via " + v4Via + " dev " + v4Dev + " table main 2>/dev/null"
                            : "ip route replace " + ip + " dev " + v4Dev + " table main 2>/dev/null";
                    rootShell.run(null, cmd);
                }
            }

            Log.d(TAG, "Endpoint routes refreshed: v4=" + v4Via + "/" + v4Dev + " v6=" + v6Via + "/" + v6Dev);
        } catch (final Exception e) {
            Log.w(TAG, "Failed to refresh endpoint routes: " + e.getMessage());
        }
    }

    private void launchStatusJob() {
        stopStatusJob();
        final Tunnel tunnel = currentTunnel;
        final Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                final long lastHandshake = getLastHandshake(tunnel);
                if (lastHandshake == -3L) break;
                if (lastHandshake == 0L) {
                    try { Thread.sleep(1000); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                if (lastHandshake > 0L) {
                    updateTunnelServiceStatus(true);
                    try {
                        final StatusCallback cb = statusCallback;
                        if (cb != null) cb.onStatusChanged(true);
                    } catch (final Exception e) {
                        Log.w(TAG, "statusCallback.onStatusChanged failed: " + e.getMessage());
                    }
                    break;
                }
                try { Thread.sleep(1000); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            // Don't null out statusThread from the lambda — lifecycle managed by stopStatusJob() only
        }, "RootStatusJob");
        statusThread = thread;
        thread.start();
    }

    private void stopStatusJob() {
        final Thread thread = statusThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(3000);
            } catch (final InterruptedException ignored) {
            }
            statusThread = null;
        }
    }

    @Override
    public String getVersion() {
        return awgVersion();
    }

    @Override
    public synchronized State setState(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
        final State originalState = getState(tunnel);
        if (state == State.TOGGLE)
            state = originalState == State.UP ? State.DOWN : State.UP;
        if (state == originalState && tunnel == currentTunnel && config == currentConfig)
            return originalState;
        if (state == State.UP) {
            final Config originalConfig = currentConfig;
            final Tunnel originalTunnel = currentTunnel;
            if (currentTunnel != null)
                setStateInternal(currentTunnel, null, State.DOWN);
            try {
                setStateInternal(tunnel, config, state);
            } catch (final Exception e) {
                if (originalTunnel != null)
                    setStateInternal(originalTunnel, originalConfig, State.UP);
                throw e;
            }
        } else if (state == State.DOWN && tunnel == currentTunnel) {
            setStateInternal(tunnel, null, State.DOWN);
        }
        return getState(tunnel);
    }

    private void runRootCommandStrict(final String command) throws Exception {
        final int ret = rootShell.run(null, command);
        if (ret != 0)
            throw new BackendException(Reason.ROOT_SHELL_ERROR, ret);
    }

    private void awgTurnOffWithTimeout(final int handle, final long timeoutMs) {
        // Wait for previous zombie thread if present
        final Thread zombie = zombieTurnOffThread;
        if (zombie != null) {
            try { zombie.join(timeoutMs); } catch (final InterruptedException ignored) { }
            zombieTurnOffThread = null;
        }

        final Thread turnOffThread = new Thread(() -> awgTurnOff(handle), "awgTurnOff");
        turnOffThread.start();
        try {
            turnOffThread.join(timeoutMs);
            if (turnOffThread.isAlive()) {
                Log.e(TAG, "awgTurnOff did not finish within " + timeoutMs + " ms, proceeding with cleanup");
                zombieTurnOffThread = turnOffThread;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interrupted while waiting for awgTurnOff");
        }
    }

    private void setStateInternal(final Tunnel tunnel, @Nullable final Config config, final State state)
            throws Exception {
        Log.i(TAG, "Bringing tunnel " + tunnel.getName() + ' ' + state);

        if (state == State.UP) {
            if (config == null)
                throw new BackendException(Reason.TUNNEL_MISSING_CONFIG);

            if (currentTunnelHandle != -1) {
                Log.w(TAG, "Tunnel already up");
                return;
            }

            // Clean up leftovers from a previous run (including after a crash)
            networkManager.cleanup(tunFd);
            tunFd = -1;

            // DNS resolution for endpoints
            dnsRetry: for (int i = 0; i < DNS_RESOLUTION_RETRIES; ++i) {
                for (final Peer peer : config.getPeers()) {
                    final InetEndpoint ep = peer.getEndpoint().orElse(null);
                    if (ep == null) continue;
                    if (ep.getResolved().orElse(null) == null) {
                        if (i < DNS_RESOLUTION_RETRIES - 1) {
                            Log.w(TAG, "DNS host \"" + ep.getHost() + "\" failed to resolve; trying again");
                            Thread.sleep(1000);
                            continue dnsRetry;
                        } else
                            throw new BackendException(Reason.DNS_RESOLUTION_FAILURE, ep.getHost());
                    }
                }
                break;
            }

            // Path to helper binary and Unix socket for fd passing
            final File nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
            final String tunCreator = new File(nativeLibDir, "libawg-tun-creator.so").getAbsolutePath();
            final String socketPath = new File(context.getCacheDir(), "tun_fd.sock").getAbsolutePath();
            new File(socketPath).delete();

            // tun-creator: open(/dev/tun) + ioctl(TUNSETIFF) as root, passes fd via SCM_RIGHTS
            rootShell.run(null, tunCreator + " " + TUN_INTERFACE + " " + socketPath + " &");

            // Receive fd via Unix domain socket
            tunFd = receiveTunFd(socketPath);
            new File(socketPath).delete();
            if (tunFd < 0) {
                tunFd = -1;
                throw new BackendException(Reason.TUN_CREATION_ERROR);
            }

            Log.d(TAG, "TUN fd=" + tunFd + " received for " + TUN_INTERFACE);

            try {
                // Configure interface via root
                final int mtu = config.getInterface().getMtu().orElse(1280);
                for (final InetNetwork addr : config.getInterface().getAddresses()) {
                    runRootCommandStrict("ip addr add " + addr.getAddress().getHostAddress() + "/" + addr.getMask() + " dev " + TUN_INTERFACE);
                }
                runRootCommandStrict("ip link set " + TUN_INTERFACE + " mtu " + mtu);
                runRootCommandStrict("ip link set " + TUN_INTERFACE + " up");

                // Start amneziawg-go
                // Go takes fd ownership on awgTurnOn and closes it on error,
                // so we reset tunFd BEFORE the call to avoid double close
                final int fd = tunFd;
                tunFd = -1;
                final String goConfig = config.toAwgUserspaceString();
                Log.d(TAG, "Go backend " + awgVersion());
                currentTunnelHandle = awgTurnOn(tunnel.getName(), fd, goConfig);

                if (currentTunnelHandle < 0) {
                    throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, currentTunnelHandle);
                }

                // Set up routing and iptables
                networkManager.setupRouting(config);
                networkManager.setupIptables(config);
            } catch (final Exception e) {
                // Stop Go (if it started successfully) and clean up resources
                if (currentTunnelHandle >= 0) {
                    awgTurnOff(currentTunnelHandle);
                }
                currentTunnelHandle = -1;
                networkManager.cleanup(tunFd);
                tunFd = -1;
                throw e;
            }

            currentTunnel = tunnel;
            currentConfig = config;

            registerNetworkMonitor();
            launchStatusJob();
            startTunnelService(tunnel.getName());

            tunnel.onStateChange(State.UP);
        } else {
            if (currentTunnelHandle == -1) {
                Log.w(TAG, "Tunnel already down");
                return;
            }
            stopTunnelService();
            unregisterNetworkMonitor();
            stopStatusJob();

            final int handleToClose = currentTunnelHandle;

            // Reset state BEFORE stopping Go so that concurrent readers
            // (getStatistics/getLastHandshake) immediately see -1 and don't
            // access the Go backend during shutdown
            currentTunnelHandle = -1;
            currentTunnel = null;
            currentConfig = null;

            try {
                awgTurnOffWithTimeout(handleToClose, TURN_OFF_TIMEOUT_MS);
            } finally {

                // Clear interrupt flag so rootShell.run() in cleanup won't be interrupted;
                // restore it after cleanup
                final boolean wasInterrupted = Thread.interrupted();
                networkManager.cleanup(tunFd);
                tunFd = -1;
                if (wasInterrupted) Thread.currentThread().interrupt();

                try {
                    final StatusCallback cb = statusCallback;
                    if (cb != null) cb.onStatusChanged(false);
                } catch (final Exception e) {
                    Log.w(TAG, "statusCallback.onStatusChanged failed: " + e.getMessage());
                }

                tunnel.onStateChange(State.DOWN);
            }
        }
    }

    private void startTunnelService(final String tunnelName) {
        final Intent intent = new Intent(context, RootTunnelService.class);
        intent.putExtra(EXTRA_TUNNEL_NAME, tunnelName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private void updateTunnelServiceStatus(final boolean connected) {
        final Tunnel tunnel = currentTunnel;
        if (tunnel == null) return;
        final Intent intent = new Intent(context, RootTunnelService.class);
        intent.putExtra(EXTRA_TUNNEL_NAME, tunnel.getName());
        intent.putExtra(EXTRA_CONNECTED, connected);
        context.startService(intent);
    }

    private void stopTunnelService() {
        context.stopService(new Intent(context, RootTunnelService.class));
    }
}
