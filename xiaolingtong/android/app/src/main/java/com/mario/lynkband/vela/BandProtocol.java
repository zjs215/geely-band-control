// Adapted for 小领通 from zjs215/geely-band-control (Apache-2.0).
package com.mario.lynkband.vela;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CCMBlockCipher;
import org.bouncycastle.crypto.modes.CCMModeCipher;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated Vela V2 quick-app transfer, adapted from minstall (MIT). */
final class BandProtocol implements AutoCloseable {
    static final String APP_PACKAGE = "com.mario.lynkband.phone";
    private static final byte[] START_SESSION = {
            1, 1, 3, 0, 1, 0, 0, 2, 2, 0, 0, (byte) 0xfc, 3, 2, 0, 32, 0, 4, 2, 0, 16, 39
    };
    private static final int PROTOBUF = 1;
    private static final int PLAIN = 1;
    private static final int ENCRYPTED = 2;
    private static final int TRANSFER_HEADER = 6;
    private static final int DEFAULT_SLICE = 4096;
    private static final int PACKETS_PER_WINDOW = 18;
    private static final int TRANSFER_ATTEMPTS = 3;
    private static final int IDLE_REPLY_MS = 650;

    enum Result { CONFIRMED, SENT }

    static final class InstallRejected extends InstallFailure {
        InstallRejected(String message) { super(message); }
    }

    private enum Stage {
        APP_PREPARE("请求安装"), MASS_PREPARE("协商传输"), TRANSFER("发送 RPK"), RECEIPT("等待安装回执");

        final String label;

        Stage(String label) { this.label = label; }
    }

    interface Progress {
        void report(String stage, int percent);
    }

    private final BandWire wire;
    private final BandDiagnostics.Trace trace;
    private BandMessages.Response receivedDuringTransfer;
    private int sequence;
    private int window = PACKETS_PER_WINDOW;
    private boolean authenticated;
    private boolean transferCompleted;
    private long authenticatedAt;
    private byte[] toBandKey;
    private byte[] fromBandKey;

    BandProtocol(BandWire wire) {
        this(wire, BandDiagnostics.Trace.OFF);
    }

    BandProtocol(BandWire wire, BandDiagnostics.Trace trace) {
        this.wire = wire;
        this.trace = trace;
    }

    Result install(byte[] rpk, int candidateVersion, Progress progress) throws Exception {
        transferCompleted = false;
        trace.event("install.begin", "bytes", rpk.length, "authenticated", authenticated,
                "wire_open", wire.isOpen(), "ms_since_auth", timeSinceAuth());
        if (!authenticated || !wire.isOpen()) {
            throw new InstallFailure("发送安装请求前连接已断开，请重试连接。");
        }
        wire.collectReplies(true);
        receivedDuringTransfer = null;
        Stage stage = Stage.APP_PREPARE;
        try {
            progress.report("正在核对设备上的小领通版本", -1);
            sendEncrypted(BandMessages.requestInstalledApps());
            BandMessages.InstalledApp current = await(8, "设备未返回已安装版本，请改用保存 RPK。", frame -> {
                byte[] body = protobufBody(frame, true);
                return body == null ? null : BandMessages.installedApp(body, APP_PACKAGE);
            });
            checkVersion(current, candidateVersion);
            progress.report("正在请求设备安装 RPK", -1);
            sendEncrypted(BandMessages.prepareApp(BandProtocol.APP_PACKAGE, rpk.length));
            BandMessages.Response app = awaitResponse(BandMessages.APP, BandMessages.PREPARE_APP, 30,
                    "等待手环准备安装超时");
            trace.event("install.app_prepare", "status", app.status(), "slice_hint", app.sliceLength());
            requireAccepted(app, "手环拒绝安装");

            // The watch uses MD5 as the transfer identifier.
            byte[] contentId = MessageDigest.getInstance("MD5").digest(rpk);
            stage = Stage.MASS_PREPARE;
            sendEncrypted(BandMessages.prepareTransfer(contentId, rpk.length));
            BandMessages.Response transfer = awaitResponse(BandMessages.MASS, BandMessages.PREPARE_MASS,
                    30, "等待手环准备传输超时");
            int sliceLength = transferSlice(transfer.sliceLength(), app.sliceLength());
            trace.event("install.mass_prepare", "status", transfer.status(), "slice_hint", transfer.sliceLength(),
                    "selected_slice", sliceLength, "window", window);
            requireAccepted(transfer, "手环拒绝接收安装包");
            stage = Stage.TRANSFER;
            sendPackage(rpk, contentId, sliceLength, progress);
            transferCompleted = true;

            stage = Stage.RECEIPT;
            progress.report("等待手环确认安装", -1);
            BandMessages.Response result;
            try {
                result = awaitResponse(BandMessages.APP, BandMessages.INSTALL_RESULT, 15,
                        "等待手环安装结果超时");
            } catch (IOException noReceipt) {
                if (noReceipt instanceof SessionRestarted) throw noReceipt;
                trace.failure("install.receipt_missing", noReceipt);
                return Result.SENT;
            }
            trace.event("install.receipt", "status", result.status());
            if (result.status() != 0) throw rejectedInstall(result.status());
            return Result.CONFIRMED;
        } catch (SessionRestarted restarted) {
            trace.failure("install.failed", restarted, "stage", stage);
            throw new InstallFailure(stage.label + "阶段，设备重新开启了蓝牙会话。请断开其他正在连接此设备的应用，再重试。", restarted);
        } catch (InstallFailure failure) {
            trace.failure("install.failed", failure, "stage", stage);
            throw failure;
        } catch (IOException failure) {
            trace.failure("install.failed", failure, "stage", stage);
            throw new InstallFailure(stage.label + "阶段蓝牙中断，请重连后再试。", failure);
        } finally {
            wire.collectReplies(false);
        }
    }

    static void checkVersion(BandMessages.InstalledApp current, int candidate) throws InstallFailure {
        if (candidate <= 0 || (current.present() && current.versionCode() <= 0))
            throw new InstallFailure("无法核对安装版本，请在设备安装工具中检查。");
        if (current.present() && candidate < current.versionCode())
            throw new InstallFailure("RPK 版本低于此设备上的小领通，不能降级。");
    }

    boolean transferCompleted() {
        return transferCompleted;
    }

    private InstallRejected rejectedInstall(int code) throws InterruptedException {
        String result = code == 2 ? "RPK 已传完，手环报告校验失败（代码 2）。"
                : "RPK 已传完，手环报告安装失败（代码 " + code + "）。";
        try {
            trace.event("install.readback_requested");
            sendEncrypted(BandMessages.requestInstalledApps());
            BandMessages.InstalledApp installed = await(2, "设备未返回应用列表", frame -> {
                byte[] body = protobufBody(frame, true);
                return body == null ? null : BandMessages.installedApp(body, BandProtocol.APP_PACKAGE);
            });
            trace.event("install.readback", "present", installed.present(), "version_code", installed.versionCode());
            if (installed.present() && installed.versionCode() >= 0) {
                result += "\n手环应用列表中的小领通版本号为 " + installed.versionCode() + "。";
            } else if (installed.present()) {
                result += "\n手环应用列表里有小领通。";
            } else {
                result += "\n手环应用列表里未找到小领通。";
            }
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception unavailable) {
            trace.failure("install.readback_unavailable", unavailable);
        }
        return new InstallRejected(result);
    }

    void authenticate(byte[] key, int phoneApi, String phoneModel) throws Exception {
        wire.write(SppGreeting.HELLO);
        openSession(0);
        authenticateSession(key, phoneApi, phoneModel);
    }

    static byte[] startRequest(int blePayload) {
        byte[] request = START_SESSION.clone();
        if (blePayload > 0) {
            int slots = blePayload <= 200 ? 4 : 8;
            int timeout = (int)(slots * 0.8 + 3) * 1000;
            request[15] = (byte)slots;
            request[request.length - 2] = (byte)timeout;
            request[request.length - 1] = (byte)(timeout >> 8);
        }
        return request;
    }

    void openSession(int blePayload) throws Exception {
        byte[] request = startRequest(blePayload);
        wire.write(BandWire.encode(BandWire.SESSION, 0, request));
        byte[] start = await(15, "设备未响应 Vela V2 会话，请检查蓝牙连接或保存 RPK 后使用 AstroBox", frame ->
                frame.type() == BandWire.SESSION && frame.payload().length > 0
                        && frame.payload()[0] == 2 ? frame.payload() : null);
        window = Math.min(transferWindow(start), request[15] & 255);
        trace.event("auth.session_reply", "window", window);

    }

    void authenticateSession(byte[] key, int phoneApi, String phoneModel) throws Exception {
        trace.event("auth.start");
        byte[] phoneNonce = new byte[16];
        new SecureRandom().nextBytes(phoneNonce);
        send(BandWire.DATA, BandMessages.join(new byte[]{PROTOBUF, PLAIN}, BandMessages.phoneNonce(phoneNonce)));
        BandMessages.Challenge challenge = await(10, "手环未返回认证消息", frame -> {
            byte[] body = protobufBody(frame, false);
            if (body == null) return null;
            int error = BandMessages.authError(body, BandMessages.AUTH_VERIFY);
            if (error > 0) throwAuthError(error);
            return BandMessages.challenge(body);
        });
        trace.event("auth.challenge_received");
        byte[] material = sessionMaterial(key, phoneNonce, challenge.nonce());
        fromBandKey = Arrays.copyOfRange(material, 0, 16);
        toBandKey = Arrays.copyOfRange(material, 16, 32);
        byte[] nonce = new byte[12];
        System.arraycopy(material, 36, nonce, 0, 4);
        Arrays.fill(material, (byte) 0);

        byte[] expectedProof = hmac(fromBandKey, BandMessages.join(challenge.nonce(), phoneNonce));
        if (!MessageDigest.isEqual(expectedProof, challenge.hmac())) {
            trace.event("auth.proof_rejected");
            throw new InstallFailure("手环认证失败，请核对当前绑定的 AuthKey");
        }
        byte[] phoneProof = hmac(toBandKey, BandMessages.join(phoneNonce, challenge.nonce()));
        String region = Locale.getDefault().getCountry();
        byte[] deviceInfo = BandMessages.deviceInfo(phoneApi, phoneModel, region.isEmpty() ? "CN" : region);
        byte[] encryptedInfo = encryptDeviceInfo(toBandKey, nonce, deviceInfo);
        send(BandWire.DATA, BandMessages.join(new byte[]{PROTOBUF, PLAIN},
                BandMessages.authProof(phoneProof, encryptedInfo)));
        BandMessages.AuthReply reply = await(10, "手环未确认认证，请核对绑定状态", frame -> {
            byte[] body = protobufBody(frame, true);
            return body == null ? null : BandMessages.authConfirmation(body);
        });
        trace.event("auth.confirmation", "result", reply.status(), "code", reply.errorCode());
        if (reply.status() == BandMessages.AuthStatus.ERROR) throwAuthError(reply.errorCode());
        if (reply.status() == BandMessages.AuthStatus.REJECTED) {
            throw new InstallFailure("设备拒绝了认证确认。请检查当前手机的蓝牙配对，再重试。");
        }
        checkSessionReady(reply.status() == BandMessages.AuthStatus.APPROVED);
        authenticatedAt = System.nanoTime();
        authenticated = true;
        trace.event("auth.verified");
        // ACKs still reach the device while the user decides whether to install.
        wire.collectReplies(false);
    }

    private void throwAuthError(int code) throws InstallFailure {
        trace.event("auth.account_error", "code", code);
        if (code == 10) throw new InstallFailure("设备报告连接数已达上限（代码 10）。请断开其他连接后重试。");
        throw new InstallFailure("设备拒绝认证（代码 " + code + "）。请核对蓝牙配对和日志。");
    }

    private void checkSessionReady(boolean explicitlyApproved) throws Exception {
        trace.event("auth.readiness_probe", "explicit_confirmation", explicitlyApproved);
        try {
            readDeviceInfo(1200);
            trace.event("auth.readiness_reply");
        } catch (SocketTimeoutException noInfo) {
            trace.event("auth.readiness_timeout", "explicit_confirmation", explicitlyApproved);
            if (!wire.isOpen()) {
                throw new InstallFailure("设备在认证后检查连接时断开。请先检查系统蓝牙配对。", noInfo);
            }
            if (!explicitlyApproved) {
                throw new InstallFailure("设备未确认认证，且加密通道未应答。请检查蓝牙配对后重试。", noInfo);
            }
        } catch (SessionRestarted restarted) {
            throw new InstallFailure("设备在认证后重新开启了蓝牙会话，请重试连接。", restarted);
        } catch (IOException failure) {
            throw new InstallFailure("设备在认证后检查连接时断开。请先检查系统蓝牙配对。", failure);
        }
    }

    synchronized boolean keepSessionAlive() throws Exception {
        if (!authenticated || !wire.isOpen()) throw new IOException("Bluetooth session closed");
        wire.collectReplies(true);
        trace.event("idle.keepalive_probe");
        try {
            readDeviceInfo(IDLE_REPLY_MS);
            trace.event("idle.keepalive_reply");
            return true;
        } catch (SocketTimeoutException noReply) {
            boolean open = wire.isOpen();
            trace.event("idle.keepalive_timeout", "wire_open", open);
            if (!open) {
                authenticated = false;
                throw noReply;
            }
            return false;
        } catch (Exception failure) {
            authenticated = false;
            trace.failure("idle.keepalive_failed", failure);
            throw failure;
        } finally {
            wire.collectReplies(false);
        }
    }

    private void readDeviceInfo(int timeoutMs) throws Exception {
        sendEncrypted(BandMessages.requestDeviceInfo());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            BandWire.Frame frame = wire.receive(deadline);
            checkPeerRestart(frame);
            byte[] payload = frame.payload();
            if (frame.type() != BandWire.DATA || payload.length < 2 || payload[1] != ENCRYPTED) continue;
            byte[] body = protobufBody(frame, true);
            if (body == null) continue;
            try {
                if (BandMessages.isDeviceInfoReply(body)) return;
            } catch (IOException unrelated) {
                trace.failure("device_info.unrecognized", unrelated);
            }
        }
    }

    long timeSinceAuth() {
        return authenticatedAt != 0 ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - authenticatedAt) : -1L;
    }

    @Override
    public synchronized void close() {
        authenticated = false;
        if (toBandKey != null) Arrays.fill(toBandKey, (byte) 0);
        if (fromBandKey != null) Arrays.fill(fromBandKey, (byte) 0);
        toBandKey = null;
        fromBandKey = null;
    }

    private void sendPackage(byte[] rpk, byte[] contentId, int sliceLength, Progress progress)
            throws Exception {
        if (sliceLength <= TRANSFER_HEADER || sliceLength > 0xffff) {
            throw new InstallFailure("手环返回了无效的传输分片长度");
        }
        byte[] content = envelope(rpk, contentId);
        int chunkSize = sliceLength - TRANSFER_HEADER;
        int chunks = (content.length + chunkSize - 1) / chunkSize;
        if (chunks > 0xffff) throw new InstallFailure("安装包超出手环传输限制");
        progress.report("正在发送 RPK", 0);
        trace.event("transfer.begin", "frames", chunks, "frame_data_bytes", chunkSize);
        for (int chunk = 0; chunk < chunks;) {
            int windowEnd = Math.min(chunk + window, chunks);
            int firstSequence = sequence;
            byte[][] frames = new byte[windowEnd - chunk][];
            int frameIndex = 0;
            for (; chunk < windowEnd; chunk++) {
                int offset = chunk * chunkSize;
                int size = Math.min(chunkSize, content.length - offset);
                byte[] payload = ByteBuffer.allocate(TRANSFER_HEADER + size).order(ByteOrder.LITTLE_ENDIAN)
                        .put((byte) 2).put((byte) PLAIN)
                        .putShort((short) chunks).putShort((short) (chunk + 1))
                        .put(content, offset, size).array();
                frames[frameIndex++] = BandWire.encode(BandWire.DATA, sequence, payload);
                sequence = (sequence + 1) & 0xff;
            }
            sendWindow(frames, firstSequence, windowEnd - frames.length + 1);
            progress.report("正在发送 RPK", chunk * 100 / chunks);
        }
    }

    private void sendWindow(byte[][] frames, int firstSequence, int firstPart) throws Exception {
        int next = 0;
        for (int attempt = 0; attempt < TRANSFER_ATTEMPTS && next < frames.length; attempt++) {
            trace.event("transfer.window_send", "part", firstPart + next, "seq", (firstSequence + next) & 0xff,
                    "frames", frames.length - next, "attempt", attempt + 1);
            for (int index = next; index < frames.length; index++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try {
                    wire.write(frames[index]);
                } catch (IOException failure) {
                    trace.event("transfer.write_failed_at", "part", firstPart + index, "seq", (firstSequence + index) & 0xff);
                    throw failure;
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                while (next < frames.length) {
                    BandWire.Frame frame = receive(deadline);
                    int index = (frame.sequence() - firstSequence) & 0xff;
                    if (frame.type() == BandWire.ACK && index < frames.length) {
                        next = Math.max(next, index + 1);
                    } else if (frame.type() == BandWire.NAK && index >= next && index < frames.length) {
                        trace.event("transfer.nak", "part", firstPart + index, "seq", frame.sequence());
                        next = index;
                        break;
                    } else {
                        BandMessages.Response response = response(frame);
                        if (receivedDuringTransfer == null && response != null && response.type() == BandMessages.APP
                                && response.action() == BandMessages.INSTALL_RESULT && response.status() >= 0) {
                            receivedDuringTransfer = response;
                            trace.event("transfer.early_receipt", "status", response.status());
                        }
                    }
                }
            } catch (SocketTimeoutException noAckYet) {
                trace.event("transfer.ack_timeout", "part", firstPart + next, "attempt", attempt + 1);
                // Retransmit only the outstanding suffix, with the original sequence numbers.
            }
        }
        if (next != frames.length) throw new InstallFailure("发送 RPK 阶段，设备未确认收到分片。");
        trace.event("transfer.window_acked", "through_part", firstPart + frames.length - 1,
                "seq", (firstSequence + frames.length - 1) & 0xff);
    }

    static int transferSlice(int mass, int app) {
        return mass > 0 ? mass : app > 0 ? app : DEFAULT_SLICE;
    }

    static int transferWindow(byte[] start) {
        for (int offset = 1; offset + 3 <= start.length;) {
            int key = start[offset] & 0xff;
            int length = (start[offset + 1] & 0xff) | ((start[offset + 2] & 0xff) << 8);
            offset += 3;
            if (length > start.length - offset) break;
            if (key == 3 && length == 2) {
                int count = (start[offset] & 0xff) | ((start[offset + 1] & 0xff) << 8);
                if (count > 0) return Math.min(count, PACKETS_PER_WINDOW);
            }
            offset += length;
        }
        return PACKETS_PER_WINDOW;
    }

    private BandWire.Frame receive(long deadline) throws IOException, InterruptedException {
        BandWire.Frame frame = wire.receive(deadline);
        if (authenticated) checkPeerRestart(frame);
        return frame;
    }

    private void checkPeerRestart(BandWire.Frame frame) throws SessionRestarted {
        if (frame.type() == BandWire.SESSION && frame.payload().length > 0
                && frame.payload()[0] == 1) {
            trace.event("wire.peer_restarted");
            throw new SessionRestarted();
        }
    }

    private static final class SessionRestarted extends IOException {
        SessionRestarted() { super("设备重新开启了蓝牙会话"); }
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
                && receivedDuringTransfer != null) return receivedDuringTransfer;
        return await(seconds, timeout, frame -> {
            BandMessages.Response result = response(frame);
            return result != null && result.type() == type && result.action() == action && result.status() >= 0
                    ? result : null;
        });
    }

    private BandMessages.Response response(BandWire.Frame frame) throws Exception {
        byte[] body = protobufBody(frame, true);
        if (body == null) return null;
        try {
            return BandMessages.response(body);
        } catch (IOException invalid) {
            throw new InstallFailure("设备返回了无法解析的安装消息。", invalid);
        }
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

    private static void requireAccepted(BandMessages.Response result, String description) throws InstallFailure {
        if (result.status() != 0) throw new InstallFailure(description + "（设备代码 " + result.status() + "）");
    }

    private interface Decoder<T> {
        T decode(BandWire.Frame frame) throws Exception;
    }

    private <T> T await(int seconds, String timeout, Decoder<T> decoder) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            BandWire.Frame frame;
            try {
                frame = receive(deadline);
            } catch (SocketTimeoutException error) {
                throw new InstallFailure(timeout, error);
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

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
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
        return ccm(true, key, nonce, data);
    }

    static byte[] ccm(boolean encrypt, byte[] key, byte[] nonce, byte[] data) throws Exception {
        CCMModeCipher cipher = CCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(encrypt, new AEADParameters(new KeyParameter(key), 32, nonce));
        byte[] output = new byte[cipher.getOutputSize(data.length)];
        int written = cipher.processBytes(data, 0, data.length, output, 0);
        written += cipher.doFinal(output, written);
        return written == output.length ? output : Arrays.copyOf(output, written);
    }
}
