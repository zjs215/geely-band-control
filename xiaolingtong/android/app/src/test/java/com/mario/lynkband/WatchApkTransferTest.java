package com.mario.lynkband;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.mario.lynkband.transport.WatchWire;
import com.mario.lynkband.transport.WatchUpdateWire;

import static org.junit.Assert.*;

public class WatchApkTransferTest {
    @Test public void onlySelectedBondedAddressIsAccepted() {
        String selected = "00:11:22:33:44:AA";
        assertTrue(WatchApkTransfer.isSelectedPeer(selected, selected, true));
        assertFalse(WatchApkTransfer.isSelectedPeer(selected, selected, false));
        assertFalse(WatchApkTransfer.isSelectedPeer(selected, "00:11:22:33:44:BB", true));
        assertFalse(WatchApkTransfer.isSelectedPeer("", "", true));
    }

    @Test public void lengthIsBoundedInclusively() throws Exception {
        int limit = 16 * 1024 * 1024;
        assertEquals(1, WatchApkTransfer.checkedLength(1));
        assertEquals(limit, WatchApkTransfer.checkedLength(limit));
        assertThrows(IOException.class, () -> WatchApkTransfer.checkedLength(0));
        assertThrows(IOException.class, () -> WatchApkTransfer.checkedLength(limit + 1L));
    }

    @Test public void sendsBoundedFramesAndCompletesOnlyAfterMatchingStagedAck() throws Exception {
        File snapshot = File.createTempFile("watch-test-", ".apk");
        try {
            int limit = WatchApkTransfer.CHUNK_BYTES;
            byte[] apk = new byte[limit + 11];
            new Random(25L).nextBytes(apk);
            Files.write(snapshot.toPath(), apk);
            List<Long> progress = new ArrayList<>();
            WatchApkTransfer.Pending offer = new WatchApkTransfer.Pending("AA", snapshot, apk.length,
                    MessageDigest.getInstance("SHA-256").digest(apk), 29, active(), progress::add);
            JSONObject request = WatchUpdateWire.request(28, 0, null, false);
            JSONObject first = framedReply(offer, request);
            String transfer = first.getString("transfer");
            assertEquals(36, transfer.length());
            assertEquals(64, first.getString("sha256").length());
            assertEquals(apk.length, first.getInt("length"));
            ByteArrayOutputStream assembled = new ByteArrayOutputStream();
            WatchUpdateWire.Chunk firstChunk = WatchUpdateWire.chunk(first, 0, null, 0, null);
            assembled.write(firstChunk.bytes());
            assertThrows(WatchUpdateWire.InvalidUpdate.class,
                    () -> WatchUpdateWire.chunk(first, 1, null, 0, null));
            assertTrue(reply(offer, request(28, apk.length).put("transfer", transfer).put("staged", true)).has("error"));
            assertTrue(reply(offer, request(28, limit).put("transfer", "stale")).has("error"));
            assertEquals(1, offer.complete.getCount());
            JSONObject second = framedReply(offer, WatchUpdateWire.request(28, limit, transfer, false));
            assertThrows(WatchUpdateWire.InvalidUpdate.class,
                    () -> WatchUpdateWire.chunk(second, limit, transfer, apk.length, "wrong digest"));
            assembled.write(WatchUpdateWire.chunk(second, limit, transfer, apk.length, firstChunk.sha256()).bytes());
            assertArrayEquals(apk, assembled.toByteArray());
            assertEquals(List.of((long) limit, (long) apk.length), progress);
            assertEquals(1, offer.complete.getCount());
            WatchUpdateWire.acknowledged(framedReply(offer, WatchUpdateWire.request(28, apk.length, transfer, true)));
            assertEquals(0, offer.complete.getCount());
        } finally {
            Files.deleteIfExists(snapshot.toPath());
        }
    }

    @Test public void downgradeIsRejectedBeforeOpeningFile() throws Exception {
        WatchApkTransfer.Pending offer = new WatchApkTransfer.Pending("AA", null, 32,
                new byte[32], 28, active(), bytes -> fail());
        assertTrue(reply(offer, request(29, 0)).has("error"));
        assertEquals(0, offer.complete.getCount());
        assertTrue(offer.failure.getMessage().contains("版本"));
    }

    @Test public void reinstallsTheSameVersion() throws Exception {
        File snapshot = File.createTempFile("watch-test-", ".apk");
        try {
            byte[] apk = {1, 2, 3};
            Files.write(snapshot.toPath(), apk);
            WatchApkTransfer.Pending offer = new WatchApkTransfer.Pending("AA", snapshot, apk.length,
                    MessageDigest.getInstance("SHA-256").digest(apk), 29, active(), ignored -> {});
            JSONObject response = framedReply(offer, WatchUpdateWire.request(29, 0, null, false));
            WatchUpdateWire.Chunk chunk = WatchUpdateWire.chunk(response, 0, null, 0, null);
            assertArrayEquals(apk, chunk.bytes());
            WatchUpdateWire.acknowledged(framedReply(offer, WatchUpdateWire.request(29, apk.length, chunk.transfer(), true)));
            assertEquals(0, offer.complete.getCount());
        } finally {
            Files.deleteIfExists(snapshot.toPath());
        }
    }

    @Test public void noOfferRepliesWithoutClosingRegularWatchSession() throws Exception {
        JSONObject reply = WatchApkTransfer.reply("AA", request(28, 0));
        assertEquals("nonce", reply.getString("id"));
        assertEquals("wearUpdate", reply.getString("type"));
        assertTrue(reply.has("error"));
        assertFalse(reply.has("chunk"));
    }

    @Test public void timeoutAndCancelRemainBoundedAndExplainSetup() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicLong time = new AtomicLong();
        WatchApkTransfer.TransferGuard guard = new WatchApkTransfer.TransferGuard(cancelled::get, time::get, 180_000L);
        guard.waitingForWatch = true;
        time.set(179_999L);
        guard.check();
        time.incrementAndGet();
        IOException timeout = assertThrows(IOException.class, guard::check);
        assertTrue(timeout.getMessage().contains("接收手机更新"));
        assertTrue(timeout.getMessage().contains("ADB"));
        cancelled.set(true);
        assertTrue(assertThrows(IOException.class, guard::check).getMessage().contains("已取消"));
    }

    private static JSONObject framedReply(WatchApkTransfer.Pending offer, JSONObject request) throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        WatchWire.write(frame, request.toString());
        JSONObject incoming = new JSONObject(new String(WatchWire.read(new ByteArrayInputStream(frame.toByteArray())),
                java.nio.charset.StandardCharsets.UTF_8));
        frame.reset();
        WatchWire.writeReply(frame, offer.reply(incoming, new JSONObject().put("v", 1)
                .put("type", WatchUpdateWire.TYPE).put("id", incoming.getString("id"))).toString());
        JSONObject response = new JSONObject(new String(WatchWire.readReply(new ByteArrayInputStream(frame.toByteArray())),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(request.getString("id"), response.getString("id"));
        return response;
    }

    private static JSONObject request(long installed, int offset) throws Exception {
        return new JSONObject().put("v", 1).put("id", "nonce").put("type", "wearUpdate")
                .put("installed", installed).put("offset", offset);
    }

    private static JSONObject reply(WatchApkTransfer.Pending offer, JSONObject request) throws Exception {
        return offer.reply(request, new JSONObject());
    }

    private static WatchApkTransfer.TransferGuard active() {
        return new WatchApkTransfer.TransferGuard(() -> false, () -> 0, 180_000L);
    }
}
