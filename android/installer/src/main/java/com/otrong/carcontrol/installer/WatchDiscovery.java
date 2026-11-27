package com.otrong.carcontrol.installer;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Discovers Android Wireless Debugging pairing and connect endpoints on the local Wi-Fi via mDNS.
 */
final class WatchDiscovery {
    static final String PAIRING_SERVICE = "_adb-tls-pairing._tcp.";
    static final String CONNECT_SERVICE = "_adb-tls-connect._tcp.";

    record Endpoint(String serviceName, String ip, int port, boolean pairing) {}

    interface Listener {
        void onEndpointFound(Endpoint endpoint);
        void onDiscoveryFailed();
    }

    private final NsdManager nsd;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<PendingResolve> resolveQueue = new ArrayDeque<>();
    private NsdManager.DiscoveryListener pairingDiscovery;
    private NsdManager.DiscoveryListener connectDiscovery;
    private boolean resolving;
    private boolean started;

    private record PendingResolve(NsdServiceInfo serviceInfo, boolean pairing) {}

    WatchDiscovery(Context context, Listener listener) {
        this.nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    synchronized void start() {
        if (started || nsd == null) return;
        started = true;
        pairingDiscovery = createDiscoveryListener(true);
        connectDiscovery = createDiscoveryListener(false);
        try {
            nsd.discoverServices(PAIRING_SERVICE, NsdManager.PROTOCOL_DNS_SD, pairingDiscovery);
            nsd.discoverServices(CONNECT_SERVICE, NsdManager.PROTOCOL_DNS_SD, connectDiscovery);
        } catch (RuntimeException unavailable) {
            stop();
            main.post(listener::onDiscoveryFailed);
        }
    }

    synchronized void restart() {
        stop();
        start();
    }

    synchronized void stop() {
        started = false;
        resolveQueue.clear();
        resolving = false;
        stopListener(pairingDiscovery);
        stopListener(connectDiscovery);
        pairingDiscovery = null;
        connectDiscovery = null;
    }

    private void stopListener(NsdManager.DiscoveryListener discovery) {
        if (discovery == null || nsd == null) return;
        try {
            nsd.stopServiceDiscovery(discovery);
        } catch (IllegalArgumentException ignored) {
            // Discovery may already be stopped or never started.
        }
    }

    private NsdManager.DiscoveryListener createDiscoveryListener(boolean pairing) {
        return new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                synchronized (WatchDiscovery.this) {
                    if (pairing) pairingDiscovery = null;
                    else connectDiscovery = null;
                    if (pairingDiscovery == null && connectDiscovery == null) {
                        started = false;
                        main.post(listener::onDiscoveryFailed);
                    }
                }
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

            @Override public void onDiscoveryStarted(String serviceType) {}

            @Override public void onDiscoveryStopped(String serviceType) {}

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                enqueueResolve(serviceInfo, pairing);
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {}
        };
    }

    private synchronized void enqueueResolve(NsdServiceInfo serviceInfo, boolean pairing) {
        if (!started || nsd == null) return;
        resolveQueue.addLast(new PendingResolve(serviceInfo, pairing));
        if (!resolving) drainResolveQueue();
    }

    @SuppressWarnings("deprecation")
    private synchronized void drainResolveQueue() {
        if (!started || nsd == null || resolving) return;
        PendingResolve next = resolveQueue.pollFirst();
        if (next == null) return;
        resolving = true;
        try {
            nsd.resolveService(next.serviceInfo(), new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    onResolveFinished();
                }

                @Override public void onServiceResolved(NsdServiceInfo info) {
                    Endpoint endpoint = extractEndpoint(info, next.pairing());
                    if (endpoint != null) {
                        main.post(() -> {
                            synchronized (WatchDiscovery.this) {
                                if (!started) return;
                            }
                            listener.onEndpointFound(endpoint);
                        });
                    }
                    onResolveFinished();
                }
            });
        } catch (IllegalArgumentException invalid) {
            onResolveFinished();
        }
    }

    private synchronized void onResolveFinished() {
        resolving = false;
        drainResolveQueue();
    }

    @SuppressWarnings("deprecation")
    private static Endpoint extractEndpoint(NsdServiceInfo info, boolean pairing) {
        InetAddress host = info.getHost();
        List<InetAddress> addresses = host == null ? List.of() : List.of(host);
        String ip = selectLocalIp(addresses);
        int port = info.getPort();
        if (ip == null || port <= 0 || port > 65535) return null;
        return new Endpoint(info.getServiceName(), ip, port, pairing);
    }

    static String selectLocalIp(List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) return null;
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                String valid = validLocalIp(address);
                if (valid != null) return valid;
            }
        }
        for (InetAddress address : addresses) {
            String valid = validLocalIp(address);
            if (valid != null) return valid;
        }
        return null;
    }

    private static String validLocalIp(InetAddress address) {
        if (address == null) return null;
        String host = address.getHostAddress();
        if (host == null) return null;
        int zone = host.indexOf('%');
        if (zone > 0) host = host.substring(0, zone);
        try {
            return WatchAdb.localIp(host);
        } catch (IOException ignored) {
            return null;
        }
    }
}
