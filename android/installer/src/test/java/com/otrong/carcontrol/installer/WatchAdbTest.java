package com.otrong.carcontrol.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class WatchAdbTest {
    @Test
    public void installsOnlyOntoAndroidWatchTargets() {
        assertTrue(WatchAdb.isWatch("M2501W1", ""));
        assertTrue(WatchAdb.isWatch("Wear OS", "feature:android.hardware.type.watch\r\n"));
        assertFalse(WatchAdb.isWatch("Android phone", "feature:android.hardware.type.watch.fake\n"));
    }

    @Test
    public void acceptsPrivateNumericAdbEndpoints() throws Exception {
        assertEquals("192.168.1.24", WatchAdb.localIp(" 192.168.1.24 "));
        assertTrue(WatchAdb.localIp("[fd23::9012]").startsWith("fd23:"));
        assertEquals(38155, WatchAdb.port("38155"));
        assertThrows(IOException.class, () -> WatchAdb.localIp("300.168.1.24"));
        assertThrows(IOException.class, () -> WatchAdb.localIp("8.8.8.8"));
        assertThrows(IOException.class, () -> WatchAdb.localIp("watch.example.com"));
        assertThrows(IllegalArgumentException.class, () -> WatchAdb.port("65536"));
    }

    @Test
    public void readsOnlyTheExactInstalledPackageAndDistinguishesCommandErrors() throws Exception {
        assertEquals(53, WatchAdb.installedCode("package:com.garage.bandcontrol.preview versionCode:72\r\n"
                + "package:com.garage.bandcontrol versionCode:53\r\n"));
        assertEquals(0, WatchAdb.installedCode(""));
        assertEquals(0, WatchAdb.installedCode("package:com.garage.bandcontrol.preview versionCode:72\n"));
        assertThrows(IOException.class, () -> WatchAdb.installedCode("Error: command unavailable\n"));
        assertThrows(IOException.class, () -> WatchAdb.installedCode("package:com.garage.bandcontrol versionCode:unknown\n"));
    }
}
