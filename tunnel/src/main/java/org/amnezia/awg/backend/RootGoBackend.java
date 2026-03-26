/*
 * Copyright © 2024 AmneziaWG. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.backend;

import android.content.Context;
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
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import static org.amnezia.awg.GoBackend.*;

/**
 * {@link Backend} implementation that uses root access to create a TUN interface
 * and configure routing via iptables/ip route, bypassing Android VPN API.
 */
@NonNullForAll
public final class RootGoBackend implements Backend {
    private static final int DNS_RESOLUTION_RETRIES = 10;
    private static final String TAG = "AmneziaWG/RootGoBackend";
    private static final String TUN_INTERFACE = "awg0";
    private static final int FWMARK = 51820;
    private static final int ROUTING_TABLE = 51820;

    private final Context context;
    private final RootShell rootShell;
    @Nullable private Config currentConfig;
    @Nullable private Tunnel currentTunnel;
    private int currentTunnelHandle = -1;
    private int tunFd = -1;
    @Nullable private Thread statusThread;
    @Nullable private StatusCallback statusCallback;
    // Endpoint IPs saved for targeted rule removal during cleanup
    private final List<String> activeEndpointIps = new ArrayList<>();
    // DNS IP saved for targeted rule removal during cleanup
    @Nullable private String activeDnsIp;
    private boolean activeDnsIsV6;

    public RootGoBackend(final Context context, final RootShell rootShell) {
        SharedLibraryLoader.loadSharedLibrary(context, "wg-go");
        this.context = context;
        this.rootShell = rootShell;
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
        if (tunnel != currentTunnel || currentTunnelHandle == -1)
            return stats;
        final String config = awgGetConfig(currentTunnelHandle);
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
        if (tunnel != currentTunnel || currentTunnelHandle == -1)
            return -3;
        final String config = awgGetConfig(currentTunnelHandle);
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

    private void launchStatusJob() {
        stopStatusJob();
        statusThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                final long lastHandshake = getLastHandshake(currentTunnel);
                if (lastHandshake == -3L) break;
                if (lastHandshake == 0L) {
                    try { Thread.sleep(1000); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                if (lastHandshake > 0L) {
                    if (statusCallback != null) statusCallback.onStatusChanged(true);
                    break;
                }
                try { Thread.sleep(1000); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            statusThread = null;
        }, "RootStatusJob");
        statusThread.start();
    }

    private void stopStatusJob() {
        if (statusThread != null) {
            statusThread.interrupt();
            statusThread = null;
        }
    }

    @Override
    public String getVersion() {
        return awgVersion();
    }

    @Override
    public State setState(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
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

    private void runRootCommand(final String command) throws Exception {
        final int ret = rootShell.run(null, command);
        if (ret != 0)
            Log.w(TAG, "Root command returned " + ret + ": " + command);
    }

    private void runRootCommandStrict(final String command) throws Exception {
        final int ret = rootShell.run(null, command);
        if (ret != 0)
            throw new BackendException(Reason.ROOT_SHELL_ERROR, ret);
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

            // Clean up leftovers from a previous run
            cleanupRootResources();

            // Resolve DNS for endpoints
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

            // Run root command to create TUN (in background, it will connect to the socket)
            // tun-creator: open(/dev/tun) + ioctl(TUNSETIFF) as root, then passes fd via SCM_RIGHTS
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
                final String goConfig = config.toAwgUserspaceString();
                Log.d(TAG, "Go backend " + awgVersion());
                currentTunnelHandle = awgTurnOn(tunnel.getName(), tunFd, goConfig);

                if (currentTunnelHandle < 0) {
                    // Go did NOT take ownership — fd is still ours, cleanup will close it
                    throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, currentTunnelHandle);
                }
                // Go successfully took ownership of fd — do not close manually
                tunFd = -1;

                // Configure routing and iptables
                setupRouting(config);
                setupIptables(config);
            } catch (final Exception e) {
                // On any error: stop Go (if running) and clean up resources
                if (currentTunnelHandle >= 0) {
                    awgTurnOff(currentTunnelHandle);
                    tunFd = -1; // Go closed fd on turnOff
                }
                currentTunnelHandle = -1;
                cleanupRootResources();
                throw e;
            }

            currentTunnel = tunnel;
            currentConfig = config;

            launchStatusJob();
        } else {
            if (currentTunnelHandle == -1) {
                Log.w(TAG, "Tunnel already down");
                return;
            }
            stopStatusJob();

            final int handleToClose = currentTunnelHandle;
            currentTunnel = null;
            currentTunnelHandle = -1;
            currentConfig = null;

            awgTurnOff(handleToClose);
            cleanupRootResources();
        }

        tunnel.onStateChange(state);
    }

    private void setupRouting(final Config config) throws Exception {
        // Collect endpoint IPs to exclude from tunnel routing
        activeEndpointIps.clear();
        for (final Peer peer : config.getPeers()) {
            final InetEndpoint ep = peer.getEndpoint().orElse(null);
            if (ep == null) continue;
            final InetEndpoint resolved = ep.getResolved().orElse(null);
            if (resolved != null)
                activeEndpointIps.add(resolved.getHost());
        }

        // Enable IP forwarding
        runRootCommand("echo 1 > /proc/sys/net/ipv4/ip_forward");
        runRootCommand("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding");

        // Save routes to endpoints BEFORE setting up tunnel routing.
        // On Android the default route is in per-network tables, not in main.
        // Add explicit host routes for each endpoint so they remain reachable.
        for (final String ip : activeEndpointIps) {
            final List<String> routeOutput = new ArrayList<>();
            if (ip.contains(":"))
                rootShell.run(routeOutput, "ip -6 route get " + ip + " | sed 's/ uid .*//'");
            else
                rootShell.run(routeOutput, "ip route get " + ip + " | sed 's/ uid .*//'");
            if (!routeOutput.isEmpty()) {
                final String route = routeOutput.get(0).trim();
                Log.d(TAG, "Saving endpoint route: " + route);
                runRootCommand("ip route add " + route + " table main 2>/dev/null");
            }
        }

        // Rule: fwmark-ed packets (from our app) use main table
        // (bypass tunnel for WireGuard UDP traffic to endpoint)
        runRootCommand("ip rule add fwmark " + FWMARK + " table main priority 10");

        // Mark our app's UDP packets with fwmark via iptables mangle
        final int myUid = android.os.Process.myUid();
        runRootCommand("iptables -t mangle -A OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK);
        runRootCommand("ip6tables -t mangle -A OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK);

        // Add routes for AllowedIPs
        for (final Peer peer : config.getPeers()) {
            for (final InetNetwork addr : peer.getAllowedIps()) {
                final String route = addr.getAddress().getHostAddress() + "/" + addr.getMask();
                if (addr.getAddress() instanceof java.net.Inet6Address)
                    runRootCommand("ip -6 route add " + route + " dev " + TUN_INTERFACE + " table " + ROUTING_TABLE);
                else
                    runRootCommand("ip route add " + route + " dev " + TUN_INTERFACE + " table " + ROUTING_TABLE);
            }
        }

        // Routing rules: all traffic without fwmark goes through tunnel table
        runRootCommand("ip rule add not fwmark " + FWMARK + " table " + ROUTING_TABLE + " priority 100");
        runRootCommand("ip rule add not fwmark " + FWMARK + " table main suppress_prefixlength 0 priority 90");

        // Exclude server endpoints from tunnel routing (additional protection)
        for (final String ip : activeEndpointIps) {
            if (ip.contains(":"))
                runRootCommand("ip -6 rule add to " + ip + " table main priority 80");
            else
                runRootCommand("ip rule add to " + ip + " table main priority 80");
        }
    }

    private void setupIptables(final Config config) throws Exception {
        // NAT for traffic through tunnel
        runRootCommand("iptables -t nat -A POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE");
        runRootCommand("ip6tables -t nat -A POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE");

        // DNS redirect to first DNS server from config
        activeDnsIp = null;
        for (final InetAddress dns : config.getInterface().getDnsServers()) {
            activeDnsIp = dns.getHostAddress();
            activeDnsIsV6 = dns instanceof java.net.Inet6Address;
            if (activeDnsIsV6) {
                runRootCommand("ip6tables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination [" + activeDnsIp + "]:53");
                runRootCommand("ip6tables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination [" + activeDnsIp + "]:53");
            } else {
                runRootCommand("iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination " + activeDnsIp + ":53");
                runRootCommand("iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination " + activeDnsIp + ":53");
            }
            break;
        }
    }

    private void cleanupRootResources() {
        try {
            final int myUid = android.os.Process.myUid();

            // Remove routing rules by priority (loop for reliability)
            rootShell.run(null, "while ip rule del priority 10 2>/dev/null; do :; done; " +
                    "while ip rule del priority 80 2>/dev/null; do :; done; " +
                    "while ip rule del priority 90 2>/dev/null; do :; done; " +
                    "while ip rule del priority 100 2>/dev/null; do :; done");

            // Flush routing table
            rootShell.run(null, "ip route flush table " + ROUTING_TABLE + " 2>/dev/null; " +
                    "ip -6 route flush table " + ROUTING_TABLE + " 2>/dev/null");

            // Remove NAT POSTROUTING rules
            rootShell.run(null, "iptables -t nat -D POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE 2>/dev/null; " +
                    "ip6tables -t nat -D POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE 2>/dev/null");

            // Remove DNS redirect rules
            if (activeDnsIp != null) {
                if (activeDnsIsV6) {
                    rootShell.run(null, "ip6tables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination [" + activeDnsIp + "]:53 2>/dev/null; " +
                            "ip6tables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination [" + activeDnsIp + "]:53 2>/dev/null");
                } else {
                    rootShell.run(null, "iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination " + activeDnsIp + ":53 2>/dev/null; " +
                            "iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination " + activeDnsIp + ":53 2>/dev/null");
                }
                activeDnsIp = null;
            }

            // Remove mangle rules
            rootShell.run(null, "iptables -t mangle -D OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK + " 2>/dev/null; " +
                    "ip6tables -t mangle -D OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK + " 2>/dev/null");

            // Remove TUN interface
            rootShell.run(null, "ip link delete " + TUN_INTERFACE + " 2>/dev/null");

            // Remove endpoint host routes from main table
            for (final String ip : activeEndpointIps) {
                if (ip.contains(":"))
                    rootShell.run(null, "ip -6 route del " + ip + " table main 2>/dev/null");
                else
                    rootShell.run(null, "ip route del " + ip + " table main 2>/dev/null");
            }

            // Restore /dev/net/tun and /dev/tun permissions
            rootShell.run(null, "chmod 660 /dev/net/tun 2>/dev/null; chmod 660 /dev/tun 2>/dev/null");

            activeEndpointIps.clear();

            // Close fd only if Go did not take ownership (awgTurnOn error)
            if (tunFd >= 0) {
                closeTun(tunFd);
                tunFd = -1;
            }
        } catch (final Exception e) {
            Log.w(TAG, "Error during cleanup: " + e.getMessage());
        }
    }
}
