package com.mario.lynkband.vela;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VelaAuthKeysInstrumentedTest {
    @Test public void storesCiphertextPerAddressAndClearsIt() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String address = "AA:BB:CC:DD:EE:F0";
        byte[] key = VelaInstaller.key("00112233445566778899aabbccddeeff");
        try {
            VelaAuthKeys.clear(context, address);
            VelaAuthKeys.save(context, address, key);
            String stored = context.getSharedPreferences(VelaAuthKeys.PREFS, Context.MODE_PRIVATE)
                    .getString("AA:BB:CC:DD:EE:F0", null);
            assertNotNull(stored);
            assertNotEquals(VelaAuthKeys.format(key), stored);
            assertArrayEquals(key, VelaAuthKeys.load(context, "aa:bb:cc:dd:ee:f0"));
            VelaAuthKeys.clear(context, address);
            assertNull(VelaAuthKeys.load(context, address));
        } finally {
            VelaAuthKeys.clear(context, address);
            java.util.Arrays.fill(key, (byte) 0);
        }
    }
}
