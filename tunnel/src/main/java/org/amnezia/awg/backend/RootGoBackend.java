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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import static org.amnezia.awg.GoBackend.*;

/**
 * Реализация {@link Backend}, использующая root-доступ для создания TUN-интерфейса
 * и настройки маршрутизации через iptables/ip route, без использования Android VPN API.
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
    // Сохраняем endpoint IP для точечного удаления правил при cleanup
    private final List<String> activeEndpointIps = new ArrayList<>();
    // Сохраняем DNS IP для точечного удаления правил при cleanup
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

            // Очистка возможных остатков от предыдущего запуска
            cleanupRootResources();

            // Резолвинг DNS для endpoint-ов
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

            // Разрешаем доступ к /dev/net/tun (или /dev/tun на некоторых устройствах)
            // Создаём /dev/net/tun если его нет, но есть /dev/tun
            runRootCommand("mkdir -p /dev/net 2>/dev/null; " +
                    "[ ! -e /dev/net/tun ] && [ -e /dev/tun ] && ln -s /dev/tun /dev/net/tun 2>/dev/null; " +
                    "[ -e /dev/net/tun ] && chmod 666 /dev/net/tun; " +
                    "[ -e /dev/tun ] && chmod 666 /dev/tun");

            // SELinux: разрешаем приложению открывать TUN-устройство
            runRootCommand("magiskpolicy --live 'allow untrusted_app tun_device chr_file { read write open ioctl }' 2>/dev/null || " +
                    "supolicy --live 'allow untrusted_app tun_device chr_file { read write open ioctl }' 2>/dev/null || " +
                    "setenforce 0 2>/dev/null");

            // Создаём TUN-интерфейс через JNI
            tunFd = openTun(TUN_INTERFACE);
            if (tunFd < 0) {
                tunFd = -1;
                throw new BackendException(Reason.TUN_CREATION_ERROR);
            }

            Log.d(TAG, "TUN fd=" + tunFd + " created for " + TUN_INTERFACE);

            try {
                // Настраиваем интерфейс через root
                final int mtu = config.getInterface().getMtu().orElse(1280);
                for (final InetNetwork addr : config.getInterface().getAddresses()) {
                    runRootCommandStrict("ip addr add " + addr.getAddress().getHostAddress() + "/" + addr.getMask() + " dev " + TUN_INTERFACE);
                }
                runRootCommandStrict("ip link set " + TUN_INTERFACE + " mtu " + mtu);
                runRootCommandStrict("ip link set " + TUN_INTERFACE + " up");

                // Запускаем amneziawg-go
                final String goConfig = config.toAwgUserspaceString();
                Log.d(TAG, "Go backend " + awgVersion());
                currentTunnelHandle = awgTurnOn(tunnel.getName(), tunFd, goConfig);

                if (currentTunnelHandle < 0) {
                    // Go НЕ взял ownership — fd ещё наш, cleanup его закроет
                    throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, currentTunnelHandle);
                }
                // Go успешно взял ownership над fd — не закрываем вручную
                tunFd = -1;

                // Настраиваем маршрутизацию и iptables
                setupRouting(config);
                setupIptables(config);
            } catch (final Exception e) {
                // При любой ошибке: останавливаем Go (если запущен) и чистим ресурсы
                if (currentTunnelHandle >= 0) {
                    awgTurnOff(currentTunnelHandle);
                    tunFd = -1; // Go закрыл fd при turnOff
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
        // Собираем endpoint IP для исключения из маршрутизации
        activeEndpointIps.clear();
        for (final Peer peer : config.getPeers()) {
            final InetEndpoint ep = peer.getEndpoint().orElse(null);
            if (ep == null) continue;
            final InetEndpoint resolved = ep.getResolved().orElse(null);
            if (resolved != null)
                activeEndpointIps.add(resolved.getHost());
        }

        // Включаем IP forwarding
        runRootCommand("echo 1 > /proc/sys/net/ipv4/ip_forward");
        runRootCommand("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding");

        // Правило: помеченные fwmark пакеты (от нашего приложения) используют main таблицу
        // (обход туннеля для UDP трафика WireGuard к endpoint-у)
        runRootCommand("ip rule add fwmark " + FWMARK + " table main priority 10");

        // Помечаем UDP пакеты нашего приложения fwmark через iptables mangle
        final int myUid = android.os.Process.myUid();
        runRootCommand("iptables -t mangle -A OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK);
        runRootCommand("ip6tables -t mangle -A OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK);

        // Добавляем маршруты для AllowedIPs
        for (final Peer peer : config.getPeers()) {
            for (final InetNetwork addr : peer.getAllowedIps()) {
                final String route = addr.getAddress().getHostAddress() + "/" + addr.getMask();
                if (addr.getAddress() instanceof java.net.Inet6Address)
                    runRootCommand("ip -6 route add " + route + " dev " + TUN_INTERFACE + " table " + ROUTING_TABLE);
                else
                    runRootCommand("ip route add " + route + " dev " + TUN_INTERFACE + " table " + ROUTING_TABLE);
            }
        }

        // Правила маршрутизации: весь трафик без fwmark идёт через таблицу туннеля
        runRootCommand("ip rule add not fwmark " + FWMARK + " table " + ROUTING_TABLE + " priority 100");
        runRootCommand("ip rule add not fwmark " + FWMARK + " table main suppress_prefixlength 0 priority 90");

        // Исключаем endpoint-ы сервера из маршрутизации через туннель (дополнительная защита)
        for (final String ip : activeEndpointIps) {
            if (ip.contains(":"))
                runRootCommand("ip -6 rule add to " + ip + " table main priority 80");
            else
                runRootCommand("ip rule add to " + ip + " table main priority 80");
        }
    }

    private void setupIptables(final Config config) throws Exception {
        // NAT для трафика через туннель
        runRootCommand("iptables -t nat -A POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE");
        runRootCommand("ip6tables -t nat -A POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE");

        // DNS-редирект на первый DNS сервер из конфига
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

            // Удаляем правила маршрутизации по приоритету (в цикле для надёжности)
            rootShell.run(null, "while ip rule del priority 10 2>/dev/null; do :; done; " +
                    "while ip rule del priority 80 2>/dev/null; do :; done; " +
                    "while ip rule del priority 90 2>/dev/null; do :; done; " +
                    "while ip rule del priority 100 2>/dev/null; do :; done");

            // Очищаем таблицу маршрутизации
            rootShell.run(null, "ip route flush table " + ROUTING_TABLE + " 2>/dev/null; " +
                    "ip -6 route flush table " + ROUTING_TABLE + " 2>/dev/null");

            // Удаляем NAT POSTROUTING (точечное удаление)
            rootShell.run(null, "iptables -t nat -D POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE 2>/dev/null; " +
                    "ip6tables -t nat -D POSTROUTING -o " + TUN_INTERFACE + " -j MASQUERADE 2>/dev/null");

            // Удаляем DNS-редирект (точечное удаление)
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

            // Удаляем mangle правила (точечное удаление)
            rootShell.run(null, "iptables -t mangle -D OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK + " 2>/dev/null; " +
                    "ip6tables -t mangle -D OUTPUT -m owner --uid-owner " + myUid + " -p udp -j MARK --set-mark " + FWMARK + " 2>/dev/null");

            // Удаляем TUN-интерфейс
            rootShell.run(null, "ip link delete " + TUN_INTERFACE + " 2>/dev/null");

            // Восстанавливаем права /dev/net/tun и /dev/tun
            rootShell.run(null, "chmod 660 /dev/net/tun 2>/dev/null; chmod 660 /dev/tun 2>/dev/null");

            activeEndpointIps.clear();

            // Закрываем fd только если Go не получил ownership (ошибка при awgTurnOn)
            if (tunFd >= 0) {
                closeTun(tunFd);
                tunFd = -1;
            }
        } catch (final Exception e) {
            Log.w(TAG, "Error during cleanup: " + e.getMessage());
        }
    }
}
