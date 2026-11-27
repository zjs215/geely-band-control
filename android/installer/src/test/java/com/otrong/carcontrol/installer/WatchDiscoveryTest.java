package com.otrong.carcontrol.installer;

import org.junit.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class WatchDiscoveryTest {
    @Test public void prefersLocalIpv4OverIpv6AndRejectsPublicIps() throws Exception {
        InetAddress publicV4 = InetAddress.getByName("8.8.8.8");
        InetAddress localV6 = InetAddress.getByName("fe80::1");
        InetAddress localV4 = InetAddress.getByName("192.168.31.88");

        assertEquals("192.168.31.88", WatchDiscovery.selectLocalIp(List.of(publicV4, localV6, localV4)));
        assertEquals("fe80:0:0:0:0:0:0:1", WatchDiscovery.selectLocalIp(List.of(publicV4, localV6)));
        assertNull(WatchDiscovery.selectLocalIp(List.of(publicV4)));
        assertNull(WatchDiscovery.selectLocalIp(List.of()));
    }
}
