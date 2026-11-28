package com.mario.lynkband;

import com.mario.lynkband.transport.WatchWire;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class WatchPhotoFrameTest {
    @Test public void onlyRepliesAcceptLargeFramesAndTheLimitUsesUtf8Bytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String atLimit = "图".repeat(10922) + "aa";
        byte[] expected = atLimit.getBytes(StandardCharsets.UTF_8);
        assertEquals(32768, expected.length);
        WatchWire.writeReply(out, atLimit);
        assertArrayEquals(expected, WatchWire.readReply(new ByteArrayInputStream(out.toByteArray())));
        assertThrows(IOException.class, () -> WatchWire.read(new ByteArrayInputStream(out.toByteArray())));
        assertThrows(IOException.class, () -> WatchWire.write(out, atLimit));
        assertThrows(IOException.class, () -> WatchWire.writeReply(out, atLimit + "a"));
        byte[] tooLarge = (atLimit + "a\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> WatchWire.readReply(new ByteArrayInputStream(tooLarge)));
    }
}
