package com.mario.lynkband.vela;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class SppGreetingTest {
    private static BufferedInputStream stream(byte... bytes) {
        return new BufferedInputStream(new ByteArrayInputStream(bytes));
    }
    private static byte[] reply(int version) {
        return new byte[]{(byte)0xba,(byte)0xdc,(byte)0xfe,0,(byte)0x80,4,0,0,1,0,(byte)version,(byte)0xef,42};
    }
    @Test public void consumesOnlyReportedVersion() throws Exception {
        for (int version : new int[]{1, 2, 3, 4}) {
            var in = stream(reply(version));
            var out = new ByteArrayOutputStream();
            assertEquals(version == 1 ? SppGreeting.Version.V1 : SppGreeting.Version.V2,
                    SppGreeting.probe(in, out, () -> false, 100));
            assertArrayEquals(SppGreeting.HELLO, out.toByteArray());
            assertEquals(42, in.read());
        }
    }
    @Test public void directV2AndPartialHeadersRemainReadable() throws Exception {
        var v2 = stream((byte)0xa5, (byte)0xa5, (byte)2);
        var out = new ByteArrayOutputStream();
        assertEquals(SppGreeting.Version.V2, SppGreeting.probe(v2, out, () -> false, 100));
        assertEquals(0xa5, v2.read());
        var partial = stream((byte)0xba);
        assertEquals(SppGreeting.Version.UNKNOWN, SppGreeting.probe(partial, out, () -> false, 50));
        assertEquals(0xba, partial.read());
    }
    @Test public void rejectsVersionZeroAndHonorsCancellationBeforeWriting() {
        var out = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> SppGreeting.probe(stream(), out, () -> true, 100));
        assertEquals(0, out.size());
        assertThrows(IOException.class, () -> SppGreeting.probe(stream(reply(0)), out, () -> false, 100));
    }
}
