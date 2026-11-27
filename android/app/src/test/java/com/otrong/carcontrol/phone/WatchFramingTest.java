package com.otrong.carcontrol.phone;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public final class WatchFramingTest {
    @Test public void readsMultipleDelimitedUtf8FramesAndSkipsEmptyLines() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WatchFraming.writeFrame(out, "{\"v\":1,\"type\":\"hello\"}");
        out.write('\n');
        out.write('\r');
        WatchFraming.writeFrame(out, "{\"v\":1,\"message\":\"已发送\"}");

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertEquals("{\"v\":1,\"type\":\"hello\"}", new String(WatchFraming.readFrame(in), StandardCharsets.UTF_8));
        assertEquals("{\"v\":1,\"message\":\"已发送\"}", new String(WatchFraming.readFrame(in), StandardCharsets.UTF_8));
        assertNull(WatchFraming.readFrame(in));
    }

    @Test public void dropsOversizedIncomingFramesAndRejectsOversizedWrites() throws Exception {
        String oversized = "{\"x\":\"" + "a".repeat(BandMessage.MAX_BYTES) + "\"}";
        String valid = "{\"v\":1,\"type\":\"status\"}";
        byte[] stream = (oversized + "\n" + valid + "\n").getBytes(StandardCharsets.UTF_8);

        ByteArrayInputStream in = new ByteArrayInputStream(stream);
        assertEquals(valid, new String(WatchFraming.readFrame(in), StandardCharsets.UTF_8));
        assertNull(WatchFraming.readFrame(in));

        assertThrows(IOException.class, () -> WatchFraming.writeFrame(new ByteArrayOutputStream(), oversized));
    }
}
