package com.mario.lynkband.vela;

import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Xiaomi SPP V1 on an already connected, user-selected RFCOMM socket. */
final class LegacyInstall implements AutoCloseable {
    private final DataOutputStream out;
    private final BlockingQueue<Frame> received = new ArrayBlockingQueue<>(128);
    private final Thread reader;
    private final BooleanSupplier cancelled;
    private volatile IOException readError;
    private volatile boolean closed;
    private int serial, counter;
    private byte[] rx, tx, rxNonce, txNonce;

    record Frame(int channel, int encoding, byte[] data) {}

    LegacyInstall(InputStream input, OutputStream output, BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        out = new DataOutputStream(output);
        reader = new Thread(() -> {
            DataInputStream in = new DataInputStream(new BufferedInputStream(input));
            try {
                while (!closed) {
                    Frame frame = read(in);
                    if (!received.offer(frame)) throw new IOException("V1 接收队列已满。");
                }
            } catch (IOException e) { readError = e; }
        }, "Vela V1 reader");
        reader.setDaemon(true);
        reader.start();
    }

    static Frame read(DataInputStream in) throws IOException {
        int magic = 0, scanned = 0;
        do {
            if (++scanned > 4096) throw new IOException("未识别到 V1 帧头。");
            magic = ((magic << 8) | in.readUnsignedByte()) & 0xffffff;
        } while (magic != 0xbadcfe);
        int channel = in.readUnsignedByte();
        int flags = in.readUnsignedByte();
        int size = in.readUnsignedByte() | (in.readUnsignedByte() << 8);
        in.readUnsignedByte(); // opcode
        in.readUnsignedByte(); // serial
        int encoding = in.readUnsignedByte();
        if (channel > 15 || (flags & 15) != 0 || size < 3 || encoding > 2)
            throw new IOException("V1 帧格式错误。");
        byte[] data = new byte[size - 3];
        in.readFully(data);
        if (in.readUnsignedByte() != 0xef) throw new IOException("V1 帧不完整。");
        return new Frame(channel, encoding, data);
    }

    private void send(int channel, int encoding, byte[] payload) throws IOException {
        if (cancelled.getAsBoolean() || closed) throw new IOException("操作已取消。");
        int size = payload.length + 3;
        if (size > 65535) throw new IOException("V1 帧超过设备限制。");
        out.write(new byte[]{(byte)0xba,(byte)0xdc,(byte)0xfe,(byte)channel,
                (byte)(channel == 0 ? 0xc0 : 0x80),(byte)size,(byte)(size >> 8),
                (byte)(channel == 0 ? 0 : 2),(byte)serial++,(byte)encoding});
        out.write(payload);
        out.write(0xef);
        out.flush();
    }

    private Frame next(long until) throws Exception {
        while (true) {
            if (cancelled.getAsBoolean()) throw new IOException("操作已取消。");
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return null;
            Frame frame = received.poll(Math.min(TimeUnit.MILLISECONDS.toNanos(100), remaining), TimeUnit.NANOSECONDS);
            if (frame != null) return frame;
            if (readError != null && received.isEmpty()) throw new IOException("V1 蓝牙连接已断开。", readError);
        }
    }

    private byte[] reply(int module, int action, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Frame frame;
        while ((frame = next(until)) != null) {
            if (frame.channel != 1 && frame.channel != 2) continue;
            byte[] data = frame.data;
            if (frame.encoding == 1) {
                if (rx == null) throw new IOException("V1 认证状态异常。");
                data = BandProtocol.ccm(false, rx, rxNonce, data);
            }
            var fields = BandMessages.parse(data);
            if (fields.number(1) != module || fields.number(2) != action) continue;
            if (fields.number(100) > 0) throw new IOException("V1 设备返回错误码 " + fields.number(100) + "。");
            return data;
        }
        throw new java.net.SocketTimeoutException("等待 V1 设备应答超时（" + module + "，" + action + "）。");
    }

    void greetingReceived() { serial = 2; }

    private void greet() throws Exception {
        send(0, 0, new byte[0]);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Frame frame;
        while ((frame = next(until)) != null) {
            if (frame.channel != 0) continue;
            if (frame.data.length > 0 && (frame.data[0] & 255) >= 2)
                throw new IOException("设备报告 SPP V2，请重新选择 V2。");
            break;
        }
    }

    private void authenticate(byte[] key, int api, String model) throws Exception {
        if (serial == 0) greet();
        byte[] local = new byte[16];
        new SecureRandom().nextBytes(local);
        send(2, 2, BandMessages.phoneNonce(local));
        BandMessages.Challenge peer = BandMessages.challenge(reply(1,26,10));
        if (peer == null) throw new IOException("V1 认证应答无效。");
        byte[] material = BandProtocol.sessionMaterial(key, local, peer.nonce());
        rx = Arrays.copyOfRange(material, 0, 16);
        tx = Arrays.copyOfRange(material, 16, 32);
        rxNonce = new byte[12]; txNonce = new byte[12];
        System.arraycopy(material,32,rxNonce,0,4);
        System.arraycopy(material,36,txNonce,0,4);
        Arrays.fill(material,(byte)0);
        if (!MessageDigest.isEqual(peer.hmac(), BandProtocol.hmac(rx,BandMessages.join(peer.nonce(),local))))
            throw new IOException("V1 设备密钥不匹配。");
        byte[] proof = BandProtocol.hmac(tx, BandMessages.join(local, peer.nonce()));
        send(2, 2, BandMessages.authProof(proof, BandProtocol.ccm(true, tx, txNonce, BandMessages.deviceInfo(api, model, "CN"))));
        var auth = BandMessages.authConfirmation(reply(1,27,10));
        if (auth == null || auth.status() != BandMessages.AuthStatus.APPROVED) throw new IOException("V1 设备未确认认证。");
    }

    private void command(byte[] data) throws Exception {
        byte[] nonce = txNonce.clone();
        ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putInt(8, ++counter);
        byte[] sequence = {(byte)counter,(byte)(counter >> 8)};
        send(2, 1, BandMessages.join(sequence,BandProtocol.ccm(true,tx,nonce,data)));
    }

    private BandMessages.InstalledApp installed() throws Exception {
        command(BandMessages.requestInstalledApps());
        var app = BandMessages.installedApp(reply(20,0,12), BandProtocol.APP_PACKAGE);
        if (app == null) throw new IOException("无法读取 V1 设备应用列表。");
        return app;
    }

    boolean install(byte[] key, int api, String model, byte[] rpk, int version, VelaInstaller.Progress progress) throws Exception {
        progress.report("正在认证 V1 设备", -1);
        authenticate(key, api, model);
        if (cancelled.getAsBoolean()) throw new IOException("操作已取消。");
        progress.authenticated();
        progress.report("正在核对 V1 安装版本", -1);
        BandProtocol.checkVersion(installed(),version);
        command(BandMessages.prepareApp(BandProtocol.APP_PACKAGE,rpk.length,version));
        var ready = BandMessages.response(reply(20,1,12));
        if (ready.status() != 0) throw new IOException("V1 安装预检未通过，状态码 " + ready.status() + "。");
        byte[] digest = MessageDigest.getInstance("MD5").digest(rpk);
        command(BandMessages.prepareTransfer(digest,rpk.length));
        var mass = BandMessages.parse(reply(22,0,12)).message(24).message(2);
        if (mass.number(2) != 0 || mass.number(4) > 0) throw new IOException("V1 设备未接受完整安装包传输。");
        if (mass.bytes(1).length > 0 && !MessageDigest.isEqual(digest,mass.bytes(1))) throw new IOException("V1 传输摘要不匹配。");
        int slice = mass.number(5);
        if (slice == -1) slice = 2048;
        if (slice < 64 || slice > 32768) throw new IOException("无效的 V1 分片长度。");
        byte[] envelope = BandProtocol.envelope(rpk,digest);
        int capacity = slice - 4;
        int count = (envelope.length + capacity - 1) / capacity;
        if (count > 65535) throw new IOException("V1 安装包分片数过多。");
        for (int part = 0; part < count; part++) {
            int offset = part * capacity;
            int size = Math.min(capacity,envelope.length - offset);
            byte[] block = ByteBuffer.allocate(size+4).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short)count).putShort((short)(part+1)).put(envelope,offset,size).array();
            send(5,1,BandProtocol.ccm(true,tx,txNonce,block));
            progress.report("正在发送 V1 安装包", (part+1)*100/count);
            Thread.sleep(30);
        }
        progress.report("等待 V1 安装回执", -1);
        byte[] receipt;
        try { receipt = reply(20,2,20); }
        catch (java.net.SocketTimeoutException missing) { return false; }
        var result = BandMessages.response(receipt);
        if (result.status() > 0) throw new IOException("V1 设备报告安装失败，状态码 " + result.status() + "。");
        var current = installed();
        return current.present() && current.versionCode() == version;
    }

    @Override public void close() {
        closed = true;
        reader.interrupt();
        if (rx != null) Arrays.fill(rx,(byte)0);
        if (tx != null) Arrays.fill(tx,(byte)0);
    }
}
