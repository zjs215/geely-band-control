package com.garage.bandcontrol.installer;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated Band 10 Pro quick-app transfer, adapted from minstall (MIT). */
final class BandProtocol {
    private static final byte[] HELLO = {
            (byte) 0xba, (byte) 0xdc, (byte) 0xfe, 0, (byte) 0xc0, 3, 0, 0, 1, 0, (byte) 0xef
    };
    private static final byte[] START_SESSION = {
            1, 1, 3, 0, 1, 0, 0, 2, 2, 0, 0, (byte) 0xfc, 3, 2, 0, 32, 0, 4, 2, 0, 16, 39
    };
    private static final int PROTOBUF = 1;
    private static final int PLAIN = 1;
    private static final int ENCRYPTED = 2;
    private static final int TRANSFER_HEADER = 6;
    private static final int DEFAULT_SLICE = 12288;
    private static final int PACKETS_PER_WINDOW = 18;

    enum Result { CONFIRMED, SENT }

    interface Progress {
        void report(String stage, int percent);
    }

    private final BandWire wire;
    private final ArrayDeque<BandMessages.Response> receivedDuringTransfer = new ArrayDeque<>();
    private int sequence;
    private byte[] toBandKey;
    private byte[] fromBandKey;

    BandProtocol(BandWire wire) {
        this.wire = wire;
    }

    Result install(byte[] key, int phoneApi, String phoneModel, byte[] rpk, Progress progress)
            throws Exception {
        try {
            progress.report("正在认证手环", -1);
            authenticate(key, phoneApi, phoneModel);

            progress.report("正在准备安装完整版 RPK", -1);
            sendEncrypted(BandMessages.prepareApp(BundledPackages.APP_PACKAGE, rpk.length));
            BandMessages.Response app = awaitResponse(BandMessages.APP, BandMessages.PREPARE_APP, 30,
                    "等待手环准备安装超时");
            requireAccepted(app, "手环拒绝安装");

            // MD5 is the transfer identifier expected by the watch. The RPK
            // itself is verified with SHA-256 against the bundled build manifest.
            byte[] contentId = MessageDigest.getInstance("MD5").digest(rpk);
            sendEncrypted(BandMessages.prepareTransfer(contentId, rpk.length));
            BandMessages.Response transfer = awaitResponse(BandMessages.MASS, BandMessages.PREPARE_MASS,
                    30, "等待手环准备传输超时");
            requireAccepted(transfer, "手环拒绝接收安装包");
            int sliceLength = transfer.sliceLength() > 0 ? transfer.sliceLength() : DEFAULT_SLICE;
            sendPackage(rpk, contentId, sliceLength, progress);

            progress.report("等待手环确认安装", -1);
            BandMessages.Response result;
            try {
                result = awaitResponse(BandMessages.APP, BandMessages.INSTALL_RESULT, 15,
                        "等待手环安装结果超时");
            } catch (IOException noReceipt) {
                return Result.SENT;
            }
            requireAccepted(result, "手环报告安装失败");
            return Result.CONFIRMED;
        } finally {
            if (toBandKey != null) Arrays.fill(toBandKey, (byte) 0);
            if (fromBandKey != null) Arrays.fill(fromBandKey, (byte) 0);
        }
    }

    private void authenticate(byte[] key, int phoneApi, String phoneModel) throws Exception {
        wire.write(HELLO);
        wire.write(BandWire.encode(BandWire.SESSION, 0, START_SESSION));
        await(15, "手环未响应蓝牙会话，请检查设备连接", frame ->
                frame.type() == BandWire.SESSION && frame.payload().length > 0
                        && frame.payload()[0] == 2 ? Boolean.TRUE : null);

        byte[] phoneNonce = new byte[16];
        new SecureRandom().nextBytes(phoneNonce);
        send(BandWire.DATA, BandMessages.join(new byte[]{PROTOBUF, PLAIN}, BandMessages.phoneNonce(phoneNonce)));
        BandMessages.Challenge challenge = await(10, "手环未返回认证消息", frame -> {
            byte[] body = protobufBody(frame, false);
            return body == null ? null : BandMessages.challenge(body);
        });
        byte[] material = sessionMaterial(key, phoneNonce, challenge.nonce());
        fromBandKey = Arrays.copyOfRange(material, 0, 16);
        toBandKey = Arrays.copyOfRange(material, 16, 32);
        byte[] nonce = new byte[12];
        System.arraycopy(material, 36, nonce, 0, 4);
        Arrays.fill(material, (byte) 0);

        byte[] expectedProof = hmac(fromBandKey, BandMessages.join(challenge.nonce(), phoneNonce));
        if (!MessageDigest.isEqual(expectedProof, challenge.hmac())) {
            throw new IOException("手环认证失败，请核对当前绑定的 AuthKey");
        }
        byte[] phoneProof = hmac(toBandKey, BandMessages.join(phoneNonce, challenge.nonce()));
        String region = Locale.getDefault().getCountry();
        byte[] deviceInfo = BandMessages.deviceInfo(phoneApi, phoneModel, region.isEmpty() ? "CN" : region);
        byte[] encryptedInfo = encryptDeviceInfo(toBandKey, nonce, deviceInfo);
        send(BandWire.DATA, BandMessages.join(new byte[]{PROTOBUF, PLAIN},
                BandMessages.authProof(phoneProof, encryptedInfo)));
        await(10, "手环未确认认证，请核对绑定状态", frame -> {
            byte[] body = protobufBody(frame, false);
            return body != null && BandMessages.authConfirmed(body) ? Boolean.TRUE : null;
        });
    }

    private void sendPackage(byte[] rpk, byte[] contentId, int sliceLength, Progress progress)
            throws Exception {
        if (sliceLength <= TRANSFER_HEADER || sliceLength > 0xffff) {
            throw new IOException("手环返回了无效的传输分片长度");
        }
        byte[] content = envelope(rpk, contentId);
        int chunkSize = sliceLength - TRANSFER_HEADER;
        int chunks = (content.length + chunkSize - 1) / chunkSize;
        if (chunks > 0xffff) throw new IOException("安装包超出手环传输限制");
        progress.report("正在发送完整版 RPK", 0);
        for (int chunk = 0; chunk < chunks;) {
            int windowEnd = Math.min(chunk + PACKETS_PER_WINDOW, chunks);
            for (; chunk < windowEnd; chunk++) {
                int offset = chunk * chunkSize;
                int size = Math.min(chunkSize, content.length - offset);
                byte[] payload = ByteBuffer.allocate(TRANSFER_HEADER + size).order(ByteOrder.LITTLE_ENDIAN)
                        .put((byte) 2).put((byte) PLAIN)
                        .putShort((short) chunks).putShort((short) (chunk + 1))
                        .put(content, offset, size).array();
                send(BandWire.DATA, payload);
            }
            int lastSequence = (sequence - 1) & 0xff;
            await(10, "手环未确认收到安装包分片", frame -> {
                BandMessages.Response response = response(frame);
                if (response != null && response.type() == BandMessages.APP
                        && response.action() == BandMessages.INSTALL_RESULT && response.status() >= 0) {
                    receivedDuringTransfer.addLast(response);
                }
                return frame.type() == BandWire.ACK && frame.sequence() == lastSequence ? Boolean.TRUE : null;
            });
            progress.report("正在发送完整版 RPK", chunk * 100 / chunks);
        }
    }

    static byte[] envelope(byte[] rpk, byte[] contentId) {
        ByteBuffer content = ByteBuffer.allocate(rpk.length + 26).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0).put((byte) BandMessages.APP_DATA).put(contentId)
                .putInt(rpk.length).put(rpk);
        CRC32 crc = new CRC32();
        crc.update(content.array(), 0, content.position());
        return content.putInt((int) crc.getValue()).array();
    }

    private BandMessages.Response awaitResponse(int type, int action, int seconds, String timeout)
            throws Exception {
        if (type == BandMessages.APP && action == BandMessages.INSTALL_RESULT
                && !receivedDuringTransfer.isEmpty()) return receivedDuringTransfer.removeFirst();
        return await(seconds, timeout, frame -> {
            BandMessages.Response result = response(frame);
            return result != null && result.type() == type && result.action() == action && result.status() >= 0
                    ? result : null;
        });
    }

    private BandMessages.Response response(BandWire.Frame frame) throws Exception {
        byte[] body = protobufBody(frame, true);
        return body == null ? null : BandMessages.response(body);
    }

    private byte[] protobufBody(BandWire.Frame frame, boolean allowEncrypted) throws Exception {
        byte[] payload = frame.payload();
        if (frame.type() != BandWire.DATA || payload.length < 2 || (payload[0] & 0x0f) != PROTOBUF) {
            return null;
        }
        if (payload[1] == PLAIN) return Arrays.copyOfRange(payload, 2, payload.length);
        if (allowEncrypted && payload[1] == ENCRYPTED) {
            return crypt(fromBandKey, Arrays.copyOfRange(payload, 2, payload.length));
        }
        return null;
    }

    private void sendEncrypted(byte[] body) throws Exception {
        send(BandWire.DATA, BandMessages.join(new byte[]{PROTOBUF, ENCRYPTED}, crypt(toBandKey, body)));
    }

    private void send(int type, byte[] payload) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        wire.write(BandWire.encode(type, sequence, payload));
        sequence = (sequence + 1) & 0xff;
    }

    private static void requireAccepted(BandMessages.Response result, String description) throws IOException {
        if (result.status() != 0) throw new IOException(description + "（设备代码 " + result.status() + "）");
    }

    private interface Decoder<T> {
        T decode(BandWire.Frame frame) throws Exception;
    }

    private <T> T await(int seconds, String timeout, Decoder<T> decoder) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            BandWire.Frame frame;
            try {
                frame = wire.receive(deadline);
            } catch (SocketTimeoutException error) {
                throw new SocketTimeoutException(timeout);
            }
            T match = decoder.decode(frame);
            if (match != null) return match;
        }
    }

    static byte[] parseKey(String value) {
        String key = value.trim();
        if (key.regionMatches(true, 0, "0x", 0, 2)) key = key.substring(2);
        if (!key.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("请输入 32 位十六进制 AuthKey");
        byte[] raw = new byte[16];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(key.substring(i * 2, i * 2 + 2), 16);
        }
        return raw;
    }

    static byte[] sessionMaterial(byte[] key, byte[] phoneNonce, byte[] watchNonce)
            throws GeneralSecurityException {
        byte[] intermediate = hmac(BandMessages.join(phoneNonce, watchNonce), key);
        byte[] label = "miwear-auth".getBytes(StandardCharsets.US_ASCII);
        byte[] first = hmac(intermediate, BandMessages.join(label, new byte[]{1}));
        byte[] second = hmac(intermediate, BandMessages.join(first, label, new byte[]{2}));
        Arrays.fill(intermediate, (byte) 0);
        return BandMessages.join(first, second);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] crypt(byte[] key, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
        return cipher.doFinal(data);
    }

    static byte[] encryptDeviceInfo(byte[] key, byte[] nonce, byte[] data) throws Exception {
        CCMBlockCipher cipher = new CCMBlockCipher(AESEngine.newInstance());
        cipher.init(true, new AEADParameters(new KeyParameter(key), 32, nonce));
        return cipher.processPacket(data, 0, data.length);
    }
}
