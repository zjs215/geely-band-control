// Adapted for 小领通 from zjs215/geely-band-control (Apache-2.0).
package com.mario.lynkband.vela;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BandProtocolTest {
    private static final byte[] TEST_KEY = hex("00112233445566778899aabbccddeeff");

    @Test public void acceptsFirstInstallAndUpdatesButRejectsDowngradeAndUnknownVersion() throws Exception {
        BandProtocol.checkVersion(new BandMessages.InstalledApp(false, -1), 49);
        BandProtocol.checkVersion(new BandMessages.InstalledApp(true, 48), 49);
        BandProtocol.checkVersion(new BandMessages.InstalledApp(true, 49), 49);
        org.junit.Assert.assertThrows(InstallFailure.class,
                () -> BandProtocol.checkVersion(new BandMessages.InstalledApp(true, 50), 49));
        org.junit.Assert.assertThrows(InstallFailure.class,
                () -> BandProtocol.checkVersion(new BandMessages.InstalledApp(true, -1), 49));
    }

    @Test
    public void framesAndCryptoMatchReferenceVectors() throws Exception {
        assertEquals(0xbb3d, BandWire.crc16("123456789".getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals(hex("0801101a1a15f201120a1000000000000000000000000000000000"),
                BandMessages.phoneNonce(new byte[16]));
        assertArrayEquals(hex("0a1000000000000000000000000000000000"), BandMessages.bytes(1, new byte[16]));
        assertArrayEquals(hex("080015000008421a03504f4320e0012a02434e"), BandMessages.deviceInfo(34, "POC", "CN"));
        byte[] key = hex("2b7e151628aed2a6abf7158809cf4f3c");
        byte[] plain = hex("6bc1bee22e409f96e93d7e117393172a");
        assertArrayEquals(hex("eed3267521be8d33e40d8b9a42022d3cb1f7d769"),
                BandProtocol.encryptDeviceInfo(key, hex("deadbeef0000000007000000"), plain));
        assertArrayEquals(hex("14f42f3141958835925097f1ac005c504d271f9022197c014f5a8dee8f83adaf"),
                BandProtocol.crypt(key, BandMessages.join(plain, plain)));
    }

    @Test
    public void readerSkipsLegacyGreetingAndBadChecksum() throws Exception {
        byte[] corrupt = BandWire.encode(BandWire.DATA, 4, new byte[]{1, 2});
        corrupt[corrupt.length - 1] ^= 1;
        byte[] stream = BandMessages.join(hex("badcfe00a500"), corrupt,
                BandWire.encode(BandWire.DATA, 5, new byte[]{3, 4}));
        BandWire.Frame frame = BandWire.decode(new DataInputStream(new ByteArrayInputStream(stream)));
        assertEquals(5, frame.sequence());
        assertArrayEquals(new byte[]{3, 4}, frame.payload());
    }

    @Test(timeout = 10000)
    public void installsWithBandReceiptDuringLastTransferWindow() throws Exception {
        assertEquals(BandProtocol.Result.CONFIRMED, exchange(true, false));
    }

    @Test(timeout = 10000)
    public void reportsOnlyTransmissionWhenBandClosesWithoutReceipt() throws Exception {
        assertEquals(BandProtocol.Result.SENT, exchange(false, false));
    }

    @Test(timeout = 10000)
    public void usesNegotiatedWindowAndResendsOnlyTheRejectedSuffix() throws Exception {
        assertEquals(BandProtocol.Result.CONFIRMED, exchange(true, true));
    }

    @Test
    public void usesInstallPrepareSliceWhenMassReplyOmitsIt() {
        assertEquals(96, BandProtocol.transferSlice(96, 1024));
        assertEquals(1024, BandProtocol.transferSlice(0, 1024));
        assertEquals(4096, BandProtocol.transferSlice(0, 0));
    }

    @Test
    public void distinguishesExplicitAuthenticationFromEmptyOrRejectedReplies() throws Exception {
        assertEquals(BandMessages.AuthStatus.APPROVED, BandMessages.authConfirmation(authConfirmation(1)).status());
        assertEquals(BandMessages.AuthStatus.REJECTED, BandMessages.authConfirmation(authConfirmation(0)).status());
        assertEquals(BandMessages.AuthStatus.UNSPECIFIED, BandMessages.authConfirmation(hex("0801101b1a00")).status());
        assertEquals(BandMessages.AuthStatus.APPROVED, BandMessages.authConfirmation(hex("080110051a024001")).status());
        assertEquals(BandMessages.AuthStatus.ERROR, BandMessages.authConfirmation(hex("0801101b1a02180a")).status());
        assertEquals(10, BandMessages.authError(hex("0801101a1a02180a"), BandMessages.AUTH_VERIFY));
        assertNull(BandMessages.authConfirmation(hex("08021002")));
    }

    @Test(timeout = 10000)
    public void verifiesAnUnspecifiedAuthReplyWithAnAuthenticatedRead() throws Exception {
        assertEquals(BandProtocol.Result.CONFIRMED, exchange(true, false, true));
    }

    @Test(timeout = 10000)
    public void installsOnTheSameSessionAfterAnIdleProbe() throws Exception {
        assertEquals(BandProtocol.Result.CONFIRMED, exchange(0, false, false, IdleProbe.REPLY));
    }

    @Test(timeout = 10000)
    public void installsWhenTheIdleProbeTimesOutButTheLinkStaysOpen() throws Exception {
        assertEquals(BandProtocol.Result.CONFIRMED, exchange(0, false, false, IdleProbe.NO_REPLY));
    }

    @Test(timeout = 10000)
    public void readsInstalledAppAfterExplicitWearableRejection() throws Exception {
        BandProtocol.InstallRejected result = assertThrows(BandProtocol.InstallRejected.class,
                () -> exchange(1, false, false));
        assertTrue(result.getMessage().contains("代码 1"));
        assertTrue(result.getMessage().contains("版本号为 49"));
    }

    private static BandProtocol.Result exchange(boolean sendReceipt, boolean rejectOnePart) throws Exception {
        return exchange(sendReceipt, rejectOnePart, false);
    }

    private static BandProtocol.Result exchange(boolean sendReceipt, boolean rejectOnePart, boolean emptyConfirmation)
            throws Exception {
        return exchange(sendReceipt ? 0 : -1, rejectOnePart, emptyConfirmation);
    }

    private static BandProtocol.Result exchange(int receiptCode, boolean rejectOnePart, boolean emptyConfirmation)
            throws Exception {
        return exchange(receiptCode, rejectOnePart, emptyConfirmation, IdleProbe.NONE);
    }

    @Test public void installsOverBleWithoutClassicGreeting() throws Exception {
        assertEquals(BandProtocol.Result.CONFIRMED, exchange(0, false, false, IdleProbe.NONE, true));
    }

    private enum IdleProbe { NONE, REPLY, NO_REPLY }

    private static BandProtocol.Result exchange(int receiptCode, boolean rejectOnePart, boolean emptyConfirmation,
                                                IdleProbe idleProbe) throws Exception {
        return exchange(receiptCode, rejectOnePart, emptyConfirmation, idleProbe, false);
    }

    private static BandProtocol.Result exchange(int receiptCode, boolean rejectOnePart, boolean emptyConfirmation,
                                                IdleProbe idleProbe, boolean ble) throws Exception {
        byte[] rpkFixture = new byte[2500];
        new Random(5).nextBytes(rpkFixture);
        ExecutorService band = Executors.newSingleThreadExecutor();
        CountDownLatch authenticated = new CountDownLatch(1);
        CountDownLatch idleAcknowledged = new CountDownLatch(1);
        try (PipedInputStream phoneIn = new PipedInputStream(8192);
             PipedOutputStream bandOut = new PipedOutputStream(phoneIn);
             PipedInputStream bandIn = new PipedInputStream(8192);
             PipedOutputStream phoneOut = new PipedOutputStream(bandIn);
             BandWire wire = new BandWire(phoneIn, phoneOut);
             BandProtocol protocol = new BandProtocol(wire)) {
            Future<?> hardware = band.submit(() -> {
                try (PipedOutputStream output = bandOut) {
                    emulateBand(new DataInputStream(bandIn), output, rpkFixture, receiptCode, rejectOnePart, emptyConfirmation,
                            authenticated, idleAcknowledged, idleProbe, ble);
                }
                return null;
            });
            if (ble) {
                protocol.openSession(244);
                protocol.authenticateSession(TEST_KEY, 36, "Test phone");
            } else protocol.authenticate(TEST_KEY, 36, "Test phone");
            authenticated.countDown();
            assertTrue(idleAcknowledged.await(2, TimeUnit.SECONDS));
            if (idleProbe != IdleProbe.NONE) {
                assertEquals(idleProbe == IdleProbe.REPLY, protocol.keepSessionAlive());
            }
            try {
                BandProtocol.Result result = protocol.install(rpkFixture, 49, (stage, percent) -> {});
                hardware.get(2, TimeUnit.SECONDS);
                return result;
            } catch (BandProtocol.InstallRejected rejection) {
                hardware.get(2, TimeUnit.SECONDS);
                assertTrue(protocol.transferCompleted());
                throw rejection;
            }
        } finally {
            band.shutdownNow();
        }
    }

    private static void emulateBand(DataInputStream input, PipedOutputStream output, byte[] rpk, int receiptCode,
                                    boolean rejectOnePart, boolean emptyConfirmation,
                                    CountDownLatch authenticated, CountDownLatch idleAcknowledged, IdleProbe idleProbe, boolean ble)
            throws Exception {
        if (!ble) {
            byte[] greeting = new byte[11];
            input.readFully(greeting);
            assertArrayEquals(hex("badcfe00c00300000100ef"), greeting);
        }
        BandWire.Frame start = BandWire.decode(input);
        assertEquals(BandWire.SESSION, start.type());
        assertArrayEquals(hex(ble ? "0101030001000002020000fc03020008000402002823" : "0101030001000002020000fc03020020000402001027"), start.payload());
        output.write(BandWire.encode(BandWire.SESSION, 0, rejectOnePart ? hex("020302000400") : new byte[]{2}));

        BandWire.Frame nonceFrame = nextData(input);
        assertEquals(0, nonceFrame.sequence());
        byte[] phoneNonce = Arrays.copyOfRange(nonceFrame.payload(), nonceFrame.payload().length - 16,
                nonceFrame.payload().length);
        byte[] nonce = hex("ffeeddccbbaa99887766554433221100");
        byte[] material = BandProtocol.sessionMaterial(TEST_KEY, phoneNonce, nonce);
        byte[] toPhone = Arrays.copyOfRange(material, 0, 16);
        byte[] toBand = Arrays.copyOfRange(material, 16, 32);
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(toPhone, "HmacSHA256"));
        byte[] proof = hmac.doFinal(BandMessages.join(nonce, phoneNonce));
        reply(output, 1, BandMessages.join(hex("0801101a"), BandMessages.bytes(3, BandMessages.bytes(31,
                BandMessages.join(BandMessages.bytes(1, nonce), BandMessages.bytes(2, proof))))), null);
        BandWire.Frame auth = nextData(input);
        assertEquals(1, auth.sequence());
        assertFalse(auth.payload().length < 4);
        reply(output, 2, emptyConfirmation ? hex("0801101b1a00") : authConfirmation(1), null);
        assertArrayEquals(BandMessages.requestDeviceInfo(), decrypted(nextData(input), toBand));
        byte[] info = BandMessages.join(BandMessages.bytes(2, new byte[]{'1'}), BandMessages.bytes(4, new byte[]{'Q'}));
        byte[] infoReply = BandMessages.join(hex("08021002"), BandMessages.bytes(4, BandMessages.bytes(3, info)));
        reply(output, 3, infoReply, toPhone);
        assertTrue(authenticated.await(2, TimeUnit.SECONDS));
        reply(output, 77, hex("080010001a00"), toPhone);
        BandWire.Frame authAck = BandWire.decode(input);
        assertEquals(BandWire.ACK, authAck.type());
        assertEquals(3, authAck.sequence());
        BandWire.Frame idleAck = BandWire.decode(input);
        assertEquals(BandWire.ACK, idleAck.type());
        assertEquals(77, idleAck.sequence());
        idleAcknowledged.countDown();

        if (idleProbe != IdleProbe.NONE) {
            assertArrayEquals(BandMessages.requestDeviceInfo(), decrypted(nextData(input), toBand));
            if (idleProbe == IdleProbe.REPLY) {
                reply(output, 78, infoReply, toPhone);
                BandWire.Frame probeAck = BandWire.decode(input);
                assertEquals(BandWire.ACK, probeAck.type());
                assertEquals(78, probeAck.sequence());
            }
        }
        assertArrayEquals(BandMessages.requestInstalledApps(), decrypted(nextData(input), toBand));
        byte[] installed = BandMessages.join(BandMessages.bytes(1,
                BandProtocol.APP_PACKAGE.getBytes(StandardCharsets.UTF_8)), BandMessages.number(3, 49));
        reply(output, 42, BandMessages.join(hex("08141000"),
                BandMessages.bytes(22, BandMessages.bytes(1, BandMessages.bytes(1, installed)))), toPhone);
        assertArrayEquals(BandMessages.prepareApp(BandProtocol.APP_PACKAGE, rpk.length),
                decrypted(nextData(input), toBand));
        reply(output, 4, hex(rejectOnePart ? "08141001b201061a0408001060" : "08141001b201041a020800"), toPhone);
        byte[] contentId = MessageDigest.getInstance("MD5").digest(rpk);
        assertArrayEquals(BandMessages.prepareTransfer(contentId, rpk.length), decrypted(nextData(input), toBand));
        reply(output, 5, hex(rejectOnePart ? "08161000c2010412021000" : "08161000c20106120410002860"), toPhone);

        ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        int window = rejectOnePart ? 4 : (ble ? 8 : 18);
        int firstPart = 1;
        if (rejectOnePart) {
            BandWire.Frame[] batch = new BandWire.Frame[window];
            for (int index = 0; index < batch.length; index++) batch[index] = nextData(input);
            output.write(BandWire.encode(BandWire.NAK, batch[1].sequence(), new byte[0]));
            for (int index = 1; index < batch.length; index++) {
                BandWire.Frame resent = nextData(input);
                assertEquals(batch[index].sequence(), resent.sequence());
                assertArrayEquals(batch[index].payload(), resent.payload());
            }
            for (BandWire.Frame frame : batch) uploaded.write(frame.payload(), 6, frame.payload().length - 6);
            output.write(BandWire.encode(BandWire.ACK, batch[batch.length - 1].sequence(), new byte[0]));
            firstPart = window + 1;
        }
        for (int part = firstPart; ; part++) {
            BandWire.Frame frame = nextData(input);
            ByteBuffer bytes = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(2, bytes.get());
            assertEquals(1, bytes.get());
            int count = bytes.getShort() & 0xffff;
            assertTrue(count > 18);
            assertEquals(part, bytes.getShort() & 0xffff);
            uploaded.write(frame.payload(), 6, bytes.remaining());
            if (part == count && receiptCode >= 0) {
                byte[] receipt = BandMessages.join(hex("08141002"), BandMessages.bytes(22,
                        BandMessages.bytes(4, BandMessages.number(1, receiptCode))));
                reply(output, 6, receipt, toPhone);
            }
            if (part % window == 0 || part == count) {
                output.write(BandWire.encode(BandWire.ACK, frame.sequence(), new byte[0]));
                output.flush();
            }
            if (part == count) break;
        }
        assertArrayEquals(BandProtocol.envelope(rpk, contentId), uploaded.toByteArray());
        if (receiptCode >= 0) {
            BandWire.Frame confirmation = BandWire.decode(input);
            assertEquals(BandWire.ACK, confirmation.type());
            assertEquals(6, confirmation.sequence());
        }
        if (receiptCode > 0) {
            assertArrayEquals(BandMessages.requestInstalledApps(), decrypted(nextData(input), toBand));
            byte[] target = BandMessages.join(BandMessages.bytes(1,
                    BandProtocol.APP_PACKAGE.getBytes(StandardCharsets.UTF_8)), BandMessages.number(3, 49));
            byte[] another = BandMessages.bytes(1, "com.example.other".getBytes(StandardCharsets.UTF_8));
            byte[] appList = BandMessages.join(BandMessages.bytes(1, target), BandMessages.bytes(1, another));
            reply(output, 7, BandMessages.join(hex("08141000"),
                    BandMessages.bytes(22, BandMessages.bytes(1, appList))), toPhone);
            BandWire.Frame listAck = BandWire.decode(input);
            assertEquals(BandWire.ACK, listAck.type());
            assertEquals(7, listAck.sequence());
        }
    }

    private static BandWire.Frame nextData(DataInputStream input) throws Exception {
        BandWire.Frame frame;
        do {
            frame = BandWire.decode(input);
        } while (frame.type() == BandWire.ACK);
        assertEquals(BandWire.DATA, frame.type());
        return frame;
    }

    private static byte[] authConfirmation(int result) {
        return BandMessages.join(hex("0801101b"),
                BandMessages.bytes(3, BandMessages.bytes(33, BandMessages.number(1, result))));
    }

    private static byte[] decrypted(BandWire.Frame frame, byte[] key) throws Exception {
        assertEquals(2, frame.payload()[1]);
        return BandProtocol.crypt(key, Arrays.copyOfRange(frame.payload(), 2, frame.payload().length));
    }

    private static void reply(PipedOutputStream output, int seq, byte[] content, byte[] key) throws Exception {
        byte[] data = key == null ? content : BandProtocol.crypt(key, content);
        output.write(BandWire.encode(BandWire.DATA, seq,
                BandMessages.join(new byte[]{1, (byte) (key == null ? 1 : 2)}, data)));
        output.flush();
    }

    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
