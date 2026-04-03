/*
 * Copyright © 2024 AmneziaWG. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.backend;

import android.content.Context;
import android.os.Build;
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
    static final String IP_FORWARD_FILE = "root_ip_forward.txt";

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
    // Saved sysctl values to restore on cleanup
    private String savedRpFilterAll = "1";
    private String savedBeLiberal = "0";

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
            if (!fwdOutput.isEmpty()) savedIpv4Forward = sanitizeForwardValue(fwdOutput.get(0).trim());
        } catch (final Exception e) {
            Log.w(TAG, "Failed to read ipv4.ip_forward: " + e.getMessage());
        }
        fwdOutput.clear();
        try {
            rootShell.run(fwdOutput, "cat /proc/sys/net/ipv6/conf/all/forwarding");
            if (!fwdOutput.isEmpty()) savedIpv6Forward = sanitizeForwardValue(fwdOutput.get(0).trim());
        } catch (final Exception e) {
            Log.w(TAG, "Failed to read ipv6 forwarding: " + e.getMessage());
        }

        // Save rp_filter BEFORE enabling ip_forward — on 3.x kernels enabling
        // forwarding can silently reset rp_filter to 1
        fwdOutput.clear();
        try {
            rootShell.run(fwdOutput, "cat /proc/sys/net/ipv4/conf/all/rp_filter");
            if (!fwdOutput.isEmpty()) savedRpFilterAll = sanitizeSysctlValue(fwdOutput.get(0).trim());
        } catch (final Exception e) {
            Log.w(TAG, "Failed to read rp_filter: " + e.getMessage());
        }

        // Save nf_conntrack_tcp_be_liberal (may fail if nf_conntrack not yet loaded)
        fwdOutput.clear();
        try {
            rootShell.run(fwdOutput, "cat /proc/sys/net/netfilter/nf_conntrack_tcp_be_liberal 2>/dev/null");
            if (!fwdOutput.isEmpty()) savedBeLiberal = sanitizeSysctlValue(fwdOutput.get(0).trim());
        } catch (final Exception e) {
            Log.w(TAG, "Failed to read nf_conntrack_tcp_be_liberal: " + e.getMessage());
        }

        // Persist sysctl values to disk for crash recovery
        saveSysctlValues();

        // Enable IP forwarding
        runCommand("echo 1 > /proc/sys/net/ipv4/ip_forward");
        runCommand("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding");

        // Reverse path filtering on the TUN interface.
        // Always disable rp_filter on awg0 itself — there is no reason for the kernel
        // to validate return paths on a userspace TUN device.
        runCommand("echo 0 > /proc/sys/net/ipv4/conf/" + TUN_INTERFACE + "/rp_filter");
        // On 3.x kernels (Android < 7) rp_filter does not consult policy routing
        // (ip rules), so strict mode (1) drops reply packets arriving on awg0 because
        // the main table routes them via the physical interface.  Effective rp_filter =
        // max(conf/all, conf/<iface>), so conf/all must also be relaxed.  Value 2
        // (loose) still validates that a route to the source exists in any table.
        // On 4.x+ kernels this is unnecessary and may interfere with Android's
        // connectivity management, so we leave conf/all untouched.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            runCommand("echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter");
        }

        // Liberal TCP window tracking in conntrack — MASQUERADE relies on conntrack
        // to de-NAT reply packets.  On 3.x kernels strict window tracking may mark
        // legitimate TCP segments as INVALID after window scaling ramps up (~10 s),
        // causing MASQUERADE de-NAT to silently fail and breaking long-lived
        // connections (streaming, large downloads).  This is a no-op if nf_conntrack
        // is not yet loaded; setupIptables() retries after MASQUERADE loads it.
        runCommand("echo 1 > /proc/sys/net/netfilter/nf_conntrack_tcp_be_liberal 2>/dev/null");

        // Save routes to endpoints BEFORE setting up tunnel routing.
        // On Android the default route lives in per-network tables, not in main.
        // We add explicit host routes so that endpoints remain reachable.
        for (final String ip : activeEndpointIps) {
            final List<String> routeOutput = new ArrayList<>();
            final boolean isV6 = ip.contains(":");
            if (isV6)
                rootShell.run(routeOutput, "ip -6 route get " + ip + " | sed 's/ uid .*//'");
            else
                rootShell.run(routeOutput, "ip route get " + ip + " | sed 's/ uid .*//'");
            if (!routeOutput.isEmpty()) {
                final String route = routeOutput.get(0).trim();
                Log.d(TAG, "Saving endpoint route: " + route);
                runCommand((isV6 ? "ip -6" : "ip") + " route add " + route + " table main 2>/dev/null");
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

        // suppress_prefixlength 0: use main table for specific routes but not for default.
        // On Android 5.x the ip utility does not support suppress_prefixlength — add a
        // fallback: explicit rule for the connected subnet so local traffic stays off the tunnel.
        // IMPORTANT: must run BEFORE adding priority 100 rules, otherwise ip route get
        // will resolve through awg0 and the fallback won't detect the real interface.
        // Active interface is detected dynamically (wlan0, rmnet_data0, eth0, etc.)
        // All shell commands use awk instead of grep -o/-oE because toolbox grep
        // on Android 5.x does not support -o and -E flags
        if (rootShell.run(null, "ip rule add not fwmark " + FWMARK + " table main suppress_prefixlength 0 priority 90") != 0) {
            Log.w(TAG, "suppress_prefixlength not supported, adding connected subnet fallback");
            final List<String> devOutput = new ArrayList<>();
            rootShell.run(devOutput, "ip route get 8.8.8.8 2>/dev/null | awk '/dev /{for(i=1;i<=NF;i++)if($i==\"dev\"){print $(i+1);exit}}'");
            if (!devOutput.isEmpty()) {
                final String dev = devOutput.get(0).trim();
                if (!dev.isEmpty() && dev.matches("[a-zA-Z0-9_.-]+")) {
                    final List<String> subnetOutput = new ArrayList<>();
                    rootShell.run(subnetOutput, "ip route show scope link dev " + dev + " 2>/dev/null | awk 'match($1,/^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+\\/[0-9]/){print $1;exit}'");
                    if (!subnetOutput.isEmpty()) {
                        final String subnet = subnetOutput.get(0).trim();
                        if (!subnet.isEmpty())
                            runCommand("ip rule add to " + subnet + " table main priority 90");
                    }
                }
            }
        }
        // IPv6: suppress_prefixlength fallback is less critical (IPv6 on Android 5.x is rare),
        // and ip -6 route get may not work on old kernels, so we try without guarantees
        if (rootShell.run(null, "ip -6 rule add not fwmark " + FWMARK + " table main suppress_prefixlength 0 priority 90") != 0) {
            final List<String> dev6Output = new ArrayList<>();
            rootShell.run(dev6Output, "ip -6 route get 2001:4860:4860::8888 2>/dev/null | awk '/dev /{for(i=1;i<=NF;i++)if($i==\"dev\"){print $(i+1);exit}}'");
            if (!dev6Output.isEmpty()) {
                final String dev6 = dev6Output.get(0).trim();
                if (!dev6.isEmpty() && dev6.matches("[a-zA-Z0-9_.-]+")) {
                    final List<String> subnet6Output = new ArrayList<>();
                    rootShell.run(subnet6Output, "ip -6 route show scope link dev " + dev6 + " 2>/dev/null | awk 'match($1,/^[0-9a-f]*:[0-9a-f:]*\\/[0-9]/){print $1;exit}'");
                    if (!subnet6Output.isEmpty()) {
                        final String subnet6 = subnet6Output.get(0).trim();
                        if (!subnet6.isEmpty())
                            runCommand("ip -6 rule add to " + subnet6 + " table main priority 90");
                    }
                }
            }
        }

        // All traffic without fwmark goes through the tunnel table — IPv4 + IPv6
        runCommand("ip rule add not fwmark " + FWMARK + " table " + ROUTING_TABLE + " priority 100");
        runCommand("ip -6 rule add not fwmark " + FWMARK + " table " + ROUTING_TABLE + " priority 100");

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

        // Retry be_liberal now that MASQUERADE has loaded nf_conntrack
        runCommand("echo 1 > /proc/sys/net/netfilter/nf_conntrack_tcp_be_liberal 2>/dev/null");

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
                savedIpv4Forward, savedIpv6Forward,
                savedRpFilterAll, savedBeLiberal);

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
        new File(context.getCacheDir(), IP_FORWARD_FILE).delete();
    }

    /**
     * Shared network cleanup logic — used both from the instance method
     * and from RootTunnelService after a crash (when no RootGoBackend instance exists).
     */
    static void performNetworkCleanup(final RootShell shell, final int uid,
            @Nullable final String dnsIp, final boolean dnsIsV6,
            final List<String> endpointIps,
            final String ipv4Forward, final String ipv6Forward,
            final String rpFilterAll, final String beLiberal) {
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

        // 6. Remove mangle rules — delete by pattern to catch both old
        //    (--clamp-mss-to-pmtu) and new (--set-mss) rules
        safeRun(shell,
                "iptables -t mangle -S POSTROUTING 2>/dev/null | grep -- '-o " + TUN_INTERFACE + " .* -j TCPMSS' | sed 's/^-A /-D /' | while IFS= read -r rule; do iptables -t mangle $rule 2>/dev/null; done; " +
                "ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -- '-o " + TUN_INTERFACE + " .* -j TCPMSS' | sed 's/^-A /-D /' | while IFS= read -r rule; do ip6tables -t mangle $rule 2>/dev/null; done",
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

        // 8a. Restore rp_filter and conntrack tcp_be_liberal.
        // conf/awg0/rp_filter needs no restore — the TUN interface was already deleted in step 1.
        // conf/all/rp_filter is only modified on 3.x kernels (Android < 7).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            safeRun(shell, "echo " + rpFilterAll + " > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null", "rp_filter restore");
        }
        safeRun(shell, "echo " + beLiberal + " > /proc/sys/net/netfilter/nf_conntrack_tcp_be_liberal 2>/dev/null", "be_liberal restore");

        // 9. Flush conntrack — stale MASQUERADE entries may prevent new connections
        //    after tunnel restart (especially on older kernels)
        safeRun(shell, "conntrack -F 2>/dev/null", "conntrack flush");

        // 10. Restore /dev/net/tun and /dev/tun permissions
        safeRun(shell, "chmod 660 /dev/net/tun 2>/dev/null; chmod 660 /dev/tun 2>/dev/null", "TUN permissions");
    }

    /**
     * ip_forward only accepts "0" or "1" — guard against shell injection
     * when restoring from a file on disk.
     */
    static String sanitizeForwardValue(final String value) {
        if ("0".equals(value) || "1".equals(value)) return value;
        Log.w(TAG, "Unexpected ip_forward value: " + value + ", defaulting to 0");
        return "0";
    }

    /**
     * Sysctl values accept "0", "1", or "2" (rp_filter uses 0/1/2) —
     * guard against shell injection when restoring from a file on disk.
     */
    static String sanitizeSysctlValue(final String value) {
        if ("0".equals(value) || "1".equals(value) || "2".equals(value)) return value;
        Log.w(TAG, "Unexpected sysctl value: " + value + ", defaulting to 0");
        return "0";
    }

    static void safeRun(final RootShell shell, final String command, final String step) {
        try {
            shell.run(null, command);
        } catch (final Exception e) {
            Log.w(TAG, "Cleanup failed [" + step + "]: " + e.getMessage());
        }
    }

    private void saveSysctlValues() {
        try {
            final File file = new File(context.getCacheDir(), IP_FORWARD_FILE);
            try (PrintWriter pw = new PrintWriter(file)) {
                pw.println(savedIpv4Forward);
                pw.println(savedIpv6Forward);
                pw.println(savedRpFilterAll);
                pw.println(savedBeLiberal);
            }
        } catch (final Exception e) {
            Log.w(TAG, "Failed to save sysctl values: " + e.getMessage());
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
