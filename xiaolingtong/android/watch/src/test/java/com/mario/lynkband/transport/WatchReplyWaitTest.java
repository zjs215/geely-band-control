package com.mario.lynkband.transport;

import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.Assert.*;

public class WatchReplyWaitTest {
    private static byte[] frame(boolean capable) throws Exception {
        return new JSONObject().put("v", 1).put("id", "one").put("type", "execute")
                .put(WatchReplyWait.CAPABILITY, capable).toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test public void slowPhoneCanReturnFinalReceiptOnSameStreamAfterOldTimeouts() throws Exception {
        String progress = WatchReplyWait.progressFor(frame(true));
        CompletableFuture<String> result = new CompletableFuture<>();
        var worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            worker.schedule(() -> result.complete("{\"v\":1,\"id\":\"one\",\"note\":\"done\"}"), 8100, TimeUnit.MILLISECONDS);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String receipt = WatchReplyWait.await(result, () -> WatchWire.writeReply(out, progress));
            WatchWire.writeReply(out, receipt);
            ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
            int count = 0;
            while (in.available() > 0) {
                JSONObject reply = new JSONObject(new String(WatchWire.readReply(in), StandardCharsets.UTF_8));
                if (WatchReplyWait.matches(reply, "one")) { count++; continue; }
                assertEquals("done", reply.getString("note"));
                assertEquals(0, in.available());
            }
            assertTrue(count >= 1);
        } finally { worker.shutdownNow(); }
    }

    @Test public void waitingIsBoundedAndCannotAcknowledgeOtherRequests() throws Exception {
        assertNull(WatchReplyWait.progressFor(frame(false)));
        JSONObject progress = new JSONObject(WatchReplyWait.progressFor(frame(true)));
        assertFalse(WatchReplyWait.matches(progress, "other"));
        assertFalse(WatchReplyWait.matches(progress, null));
        assertFalse(WatchReplyWait.matches(new JSONObject().put("id", "one").put("v",1), "one"));
        assertThrows(TimeoutException.class, () -> WatchReplyWait.await(new CompletableFuture<>(), () -> {}, 10, 40));
        JSONObject expired = new JSONObject(WatchReplyWait.expired(frame(true)));
        assertEquals("one", expired.getString("id"));
        assertFalse(expired.getBoolean("controlsAllowed"));
        assertEquals(0, expired.getJSONArray("actions").length());
    }
}
