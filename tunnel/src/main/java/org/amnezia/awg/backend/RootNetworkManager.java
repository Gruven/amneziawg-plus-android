/*
 * Copyright © 2024 AmneziaWG. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.backend;

import android.content.Context;
import android.util.Log;

import org.amnezia.awg.backend.BackendException.Reason;
import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.InetEndpoint;
import org.amnezia.awg.config.InetNetwork;
import org.amnezia.awg.config.Peer;
import org.amnezia.awg.util.NonNullForAll;
import org.amnezia.awg.util.RootShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import static org.amnezia.awg.GoBackend.*;

/**
 * Manages root networking operations: routing, iptables rules, and cleanup.
 * Encapsulates mutable network state (endpoint IPs, DNS IP, ip_forward values)
 * that lives for the duration of a tunnel session.
 */
@NonNullForAll
final class RootNetworkManager {
    private static final String TAG = "AmneziaWG/RootGoBackend";
    static final String TUN_INTERFACE = "awg0";
    static final int FWMARK = 51820;
    static final int ROUTING_TABLE = 51820;
    static final String ENDPOINT_IPS_FILE = "root_endpoint_ips.txt";

    private final Context context;
    private final RootShell rootShell;

    // Endpoint IPs for targeted route removal during cleanup
    private final List<String> activeEndpointIps = new ArrayList<>();
    // DNS IP for targeted rule removal during cleanup
    @Nullable private String activeDnsIp;
    private boolean activeDnsIsV6;
    // Saved ip_forward values to restore on cleanup
    private String savedIpv4Forward = "1";
    private String savedIpv6Forward = "0";

    RootNetworkManager(final Context context, final RootShell rootShell) {
        this.context = context;
        this.rootShell = rootShell;
    }

    List<String> getActiveEndpointIps() {
        return activeEndpointIps;
    }

    void setupRouting(final Config config) throws Exception {
        // Collect endpoint IPs to exclude from tunnel routing
        activeEndpointIps.clear();
        for (final Peer peer : config.getPeers()) {
            final InetEndpoint ep = peer.getEndpoint().orElse(null);
            if (ep == null) continue;
            final InetEndpoint resolved = ep.getResolved().orElse(null);
            if (resolved != null)
                activeEndpointIps.add(resolved.getHost());
        }

        // Persist endpoint IPs to disk for crash recovery
        saveEndpointIps();

        // Save current ip_forward values to restore on cleanup
        final List<String> fwdOutput = new ArrayList<>();
        try {
            rootShell.run(fwdOutput, "cat /proc/sys/net/ipv4/ip_forward");
            if (!fwdOutput.isEmpty()) savedIpv4Forward = fwdOutput.get(0).trim();
        } catch (final Exception e) {
            Log.w(TAG, "Failed to read ipv4.ip_forward: " + e.getMessage());
        }
        fwdOutput.clear();
        try {
            rootShell.run(fwdOutput, "cat /proc/sys/net/ipv6/conf/all/forwarding");
            if (!fwdOutput.isEmpty()) savedIpv6Forward = fwdOutput.get(0).trim();
        } catch (final Exception e) {
            Log.w(TAG, "Failed to read ipv6 forwarding: " + e.getMessage());
        }

        // Enable IP forwarding
        runCommand("echo 1 > /proc/sys/net/ipv4/ip_forward");
        runCommand("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding");

        // Save routes to endpoints BEFORE setting up tunnel routing.
        // On Android the default route lives in per-network tables, not in main.
        // We add explicit host routes so that endpoints remain reachable.
        for (final String ip : activeEndpointIps) {
            final List<String> routeOutput = new ArrayList<>();
            if (ip.contains(":"))
                rootShell.run(routeOutput, "ip -6 route get " + ip + " | sed 's/ uid .*//'");
            else
                rootShell.run(routeOutput, "ip route get " + ip + " | sed 's/ uid .*//'");
            if (!routeOutput.isEmpty()) {
                final String route = routeOutput.get(0).trim();
                Log.d(TAG, "Saving endpoint route: " + route);
                runCommand("ip route add " + route + " table main 2>/dev/null");
            }
        }

        // Packets with fwmark (from our app) use main table — IPv4 + IPv6
        // (bypass tunnel for WireGuard UDP traffic to endpoints)
        runCommand("ip rule add fwmark " + FWMARK + " table main priority 10");
        runCommand("ip -6 rule add fwmark " + FWMARK + " table main priority 10");

        // Routes for AllowedIPs
        for (final Peer peer : config.getPeers()) {
            for (final InetNetwork addr : peer.getAllowedIps()) {
                final String route = addr.getAddress().getHostAddress() + "/" + addr.getMask();
                if (addr.getAddress() instanceof java.net.Inet6Address)
                    runCommand("ip -6 route add " + route + " dev " + TUN_INTERFACE + " table " + ROUTING_TABLE);
                else
                    runCommand("ip route add " + route + " dev " + TUN_INTERFACE + " table " + ROUTING_TABLE);
            }
        }

        // All traffic without fwmark goes through the tunnel table — IPv4 + IPv6
        runCommand("ip rule add not fwmark " + FWMARK + " table " + ROUTING_TABLE + " priority 100");
        runCommand("ip -6 rule add not fwmark " + FWMARK + " table " + ROUTING_TABLE + " priority 100");
        runCommand("ip rule add not fwmark " + FWMARK + " table main suppress_prefixlength 0 priority 90");
        runCommand("ip -6 rule add not fwmark " + FWMARK + " table main suppress_prefixlength 0 priority 90");

        // Exclude endpoints from tunnel routing (extra safeguard)
        for (final String ip : activeEndpointIps) {
            if (ip.contains(":"))
                runCommand("ip -6 rule add to " + ip + " table main priority 80");
            else
                runCommand("ip rule add to " + ip + " table main priority 80");
        }
    }

    void setupIptables(final Config config) throws Exception {
        // TCP MSS clamping — without this, TCP may negotiate MSS based on the physical
        // interface MTU instead of TUN, causing large segments to be dropped in the tunnel
        runCommand("iptables -t mangle -A POSTROUTING -o " + TUN_INTERFACE + " -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu");
        runCommand("ip6tables -t mangle -A POSTROUTING -o " + TUN_INTERFACE + " -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu");

        // Mark our app's UDP packets via iptables mangle (bypass tunnel for endpoint traffic)
        final int myUid = android.os.Process.myUid();
        runCommand("iptables -t mangle -A OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK);
        runCommand("ip6tables -t mangle -A OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK);

        // NAT for traffic through the tunnel
        runCommand("iptables -t nat -A POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE");
        runCommand("ip6tables -t nat -A POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE");

        // DNS redirect to the first DNS server from config
        activeDnsIp = null;
        for (final InetAddress dns : config.getInterface().getDnsServers()) {
            activeDnsIp = dns.getHostAddress();
            activeDnsIsV6 = dns instanceof java.net.Inet6Address;
            if (activeDnsIsV6) {
                runCommand("ip6tables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination [" + activeDnsIp + "]:53");
                runCommand("ip6tables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination [" + activeDnsIp + "]:53");
            } else {
                runCommand("iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination " + activeDnsIp + ":53");
                runCommand("iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination " + activeDnsIp + ":53");
            }
            break;
        }
    }

    /**
     * Clean up all networking resources: TUN interface, routing rules,
     * iptables rules, endpoint routes, ip_forward, and TUN permissions.
     *
     * @param tunFd TUN file descriptor to close, or -1 if already closed/owned by Go
     */
    void cleanup(final int tunFd) {
        // Merge current and saved endpoint IPs for complete crash recovery cleanup
        final List<String> savedIps = loadSavedEndpointIps();
        final Set<String> allEndpointIps = new ArraySet<>(activeEndpointIps);
        allEndpointIps.addAll(savedIps);

        // Network cleanup (deletes TUN first to prevent traffic leaks)
        performNetworkCleanup(rootShell, android.os.Process.myUid(),
                activeDnsIp, activeDnsIsV6,
                new ArrayList<>(allEndpointIps),
                savedIpv4Forward, savedIpv6Forward);

        // Close fd (only if Go didn't take ownership — error before awgTurnOn)
        if (tunFd >= 0) {
            try {
                closeTun(tunFd);
            } catch (final Exception e) {
                Log.w(TAG, "Cleanup failed [close TUN fd]: " + e.getMessage());
            }
        }

        activeDnsIp = null;
        activeEndpointIps.clear();
    }

    /**
     * Shared network cleanup logic — used both from the instance method
     * and from RootTunnelService after a crash (when no RootGoBackend instance exists).
     */
    static void performNetworkCleanup(final RootShell shell, final int uid,
            @Nullable final String dnsIp, final boolean dnsIsV6,
            final List<String> endpointIps,
            final String ipv4Forward, final String ipv6Forward) {
        // 1. Delete TUN interface FIRST — instantly blocks all traffic through the tunnel,
        //    preventing unencrypted traffic leaks while routing rules are being removed
        safeRun(shell, "ip link delete " + TUN_INTERFACE + " 2>/dev/null", "TUN interface");

        // 2. Remove routing rules by priority — IPv4 + IPv6
        safeRun(shell, "while ip rule del priority 10 2>/dev/null; do :; done; " +
                "while ip -6 rule del priority 10 2>/dev/null; do :; done; " +
                "while ip rule del priority 80 2>/dev/null; do :; done; " +
                "while ip -6 rule del priority 80 2>/dev/null; do :; done; " +
                "while ip rule del priority 90 2>/dev/null; do :; done; " +
                "while ip -6 rule del priority 90 2>/dev/null; do :; done; " +
                "while ip rule del priority 100 2>/dev/null; do :; done; " +
                "while ip -6 rule del priority 100 2>/dev/null; do :; done", "routing rules");

        // 3. Flush the routing table
        safeRun(shell, "ip route flush table " + ROUTING_TABLE + " 2>/dev/null; " +
                "ip -6 route flush table " + ROUTING_TABLE + " 2>/dev/null", "routing table");

        // 4. Remove NAT POSTROUTING rules
        safeRun(shell, "iptables -t nat -D POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE 2>/dev/null; " +
                "ip6tables -t nat -D POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE 2>/dev/null", "NAT POSTROUTING");

        // 5. Remove DNS redirect rules by saved IP
        if (dnsIp != null) {
            if (dnsIsV6) {
                safeRun(shell, "ip6tables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination [" + dnsIp + "]:53 2>/dev/null; " +
                        "ip6tables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination [" + dnsIp + "]:53 2>/dev/null", "DNS redirect");
            } else {
                safeRun(shell, "iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination " + dnsIp + ":53 2>/dev/null; " +
                        "iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination " + dnsIp + ":53 2>/dev/null", "DNS redirect");
            }
        }
        // Aggressive DNS DNAT cleanup for crash recovery (when dnsIp is lost)
        safeRun(shell,
                "iptables -t nat -S OUTPUT 2>/dev/null | grep '\\-\\-dport 53 -j DNAT' | sed 's/^-A /-D /' | while IFS= read -r rule; do iptables -t nat $rule 2>/dev/null; done; " +
                "ip6tables -t nat -S OUTPUT 2>/dev/null | grep '\\-\\-dport 53 -j DNAT' | sed 's/^-A /-D /' | while IFS= read -r rule; do ip6tables -t nat $rule 2>/dev/null; done",
                "DNS DNAT from iptables");

        // 6. Remove mangle rules
        safeRun(shell, "iptables -t mangle -D POSTROUTING -o " + TUN_INTERFACE + " -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null; " +
                "ip6tables -t mangle -D POSTROUTING -o " + TUN_INTERFACE + " -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null",
                "mangle MSS clamp");
        safeRun(shell, "iptables -t mangle -D OUTPUT -m owner --uid-owner " + uid + " -p udp -j MARK --set-mark " + FWMARK + " 2>/dev/null; " +
                "ip6tables -t mangle -D OUTPUT -m owner --uid-owner " + uid + " -p udp -j MARK --set-mark " + FWMARK + " 2>/dev/null",
                "mangle fwmark");

        // 7. Remove endpoint routes from the main table
        for (final String ip : endpointIps) {
            if (ip.contains(":"))
                safeRun(shell, "ip -6 route del " + ip + " table main 2>/dev/null", "endpoint route " + ip);
            else
                safeRun(shell, "ip route del " + ip + " table main 2>/dev/null", "endpoint route " + ip);
        }

        // 8. Restore ip_forward
        safeRun(shell, "echo " + ipv4Forward + " > /proc/sys/net/ipv4/ip_forward 2>/dev/null", "ip_forward restore");
        safeRun(shell, "echo " + ipv6Forward + " > /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null", "ip_forward restore");

        // 9. Restore /dev/net/tun and /dev/tun permissions
        safeRun(shell, "chmod 660 /dev/net/tun 2>/dev/null; chmod 660 /dev/tun 2>/dev/null", "TUN permissions");
    }

    static void safeRun(final RootShell shell, final String command, final String step) {
        try {
            shell.run(null, command);
        } catch (final Exception e) {
            Log.w(TAG, "Cleanup failed [" + step + "]: " + e.getMessage());
        }
    }

    private void saveEndpointIps() {
        try {
            final File file = new File(context.getCacheDir(), ENDPOINT_IPS_FILE);
            try (PrintWriter pw = new PrintWriter(file)) {
                for (final String ip : activeEndpointIps) pw.println(ip);
            }
        } catch (final Exception e) {
            Log.w(TAG, "Failed to save endpoint IPs: " + e.getMessage());
        }
    }

    private List<String> loadSavedEndpointIps() {
        final List<String> ips = new ArrayList<>();
        final File file = new File(context.getCacheDir(), ENDPOINT_IPS_FILE);
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) ips.add(line);
                }
            } catch (final Exception e) {
                Log.w(TAG, "Failed to load endpoint IPs: " + e.getMessage());
            }
            file.delete();
        }
        return ips;
    }

    private void runCommand(final String command) throws Exception {
        final int ret = rootShell.run(null, command);
        if (ret != 0)
            Log.w(TAG, "Root command returned " + ret + ": " + command);
    }
}
