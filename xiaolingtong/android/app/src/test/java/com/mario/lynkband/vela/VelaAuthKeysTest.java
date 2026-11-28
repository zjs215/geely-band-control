package com.mario.lynkband.vela;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class VelaAuthKeysTest {
    @Test public void canonicalizesBluetoothAddressOnly() {
        assertEquals("AA:BB:CC:DD:EE:FF", VelaAuthKeys.normalizeAddress(" aa:bb:cc:dd:ee:ff "));
        assertNull(VelaAuthKeys.normalizeAddress(null));
        assertNull(VelaAuthKeys.normalizeAddress("AA-BB-CC-DD-EE-FF"));
        assertNull(VelaAuthKeys.normalizeAddress("AA:BB:CC:DD:EE"));
        assertNull(VelaAuthKeys.normalizeAddress("not-an-address"));
    }

    @Test public void formatsOnlySixteenByteKeys() {
        assertEquals("00112233445566778899aabbccddeeff",
                VelaAuthKeys.format(new byte[]{0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                        (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd,
                        (byte) 0xee, (byte) 0xff}));
        assertThrows(IllegalArgumentException.class, () -> VelaAuthKeys.format(new byte[15]));
    }
}
