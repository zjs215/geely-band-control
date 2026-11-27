package com.garage.bandcontrol.installer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BandProtocolTest {
    private static final byte[] TEST_KEY = hex("00112233445566778899aabbccddeeff");

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
        assertEquals(BandProtocol.Result.CONFIRMED, exchange(true));
    }

    @Test(timeout = 10000)
    public void reportsOnlyTransmissionWhenBandClosesWithoutReceipt() throws Exception {
        assertEquals(BandProtocol.Result.SENT, exchange(false));
    }

    private static BandProtocol.Result exchange(boolean sendReceipt) throws Exception {
        byte[] rpkFixture = new byte[2500];
        new Random(5).nextBytes(rpkFixture);
        ExecutorService band = Executors.newSingleThreadExecutor();
        try (PipedInputStream phoneIn = new PipedInputStream(8192);
             PipedOutputStream bandOut = new PipedOutputStream(phoneIn);
             PipedInputStream bandIn = new PipedInputStream(8192);
             PipedOutputStream phoneOut = new PipedOutputStream(bandIn);
             BandWire wire = new BandWire(phoneIn, phoneOut)) {
            Future<?> hardware = band.submit(() -> {
                try (PipedOutputStream output = bandOut) {
                    emulateBand(new DataInputStream(bandIn), output, rpkFixture, sendReceipt);
                }
                return null;
            });
            BandProtocol.Result result = new BandProtocol(wire).install(TEST_KEY, 36, "Test phone", rpkFixture,
                    (stage, percent) -> {});
            hardware.get(2, TimeUnit.SECONDS);
            return result;
        } finally {
            band.shutdownNow();
        }
    }

    private static void emulateBand(DataInputStream input, PipedOutputStream output, byte[] rpk, boolean sendReceipt)
            throws Exception {
        byte[] greeting = new byte[11];
        input.readFully(greeting);
        assertArrayEquals(hex("badcfe00c00300000100ef"), greeting);
        BandWire.Frame start = BandWire.decode(input);
        assertEquals(BandWire.SESSION, start.type());
        assertArrayEquals(hex("0101030001000002020000fc03020020000402001027"), start.payload());
        output.write(BandWire.encode(BandWire.SESSION, 0, new byte[]{2}));

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
        reply(output, 2, hex("0801101b1a00"), null);

        assertArrayEquals(BandMessages.prepareApp(BundledPackages.APP_PACKAGE, rpk.length),
                decrypted(nextData(input), toBand));
        reply(output, 3, hex("08141001b201041a020800"), toPhone);
        byte[] contentId = MessageDigest.getInstance("MD5").digest(rpk);
        assertArrayEquals(BandMessages.prepareTransfer(contentId, rpk.length), decrypted(nextData(input), toBand));
        reply(output, 4, hex("08161000c20106120410002860"), toPhone);

        ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        for (int part = 1; ; part++) {
            BandWire.Frame frame = nextData(input);
            ByteBuffer bytes = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(2, bytes.get());
            assertEquals(1, bytes.get());
            int count = bytes.getShort() & 0xffff;
            assertTrue(count > 18);
            assertEquals(part, bytes.getShort() & 0xffff);
            uploaded.write(frame.payload(), 6, bytes.remaining());
            if (part == count && sendReceipt) reply(output, 5, hex("08141002b2010422020800"), toPhone);
            if (part % 18 == 0 || part == count) {
                output.write(BandWire.encode(BandWire.ACK, frame.sequence(), new byte[0]));
                output.flush();
            }
            if (part == count) break;
        }
        assertArrayEquals(BandProtocol.envelope(rpk, contentId), uploaded.toByteArray());
        if (sendReceipt) {
            BandWire.Frame confirmation = BandWire.decode(input);
            assertEquals(BandWire.ACK, confirmation.type());
            assertEquals(5, confirmation.sequence());
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
