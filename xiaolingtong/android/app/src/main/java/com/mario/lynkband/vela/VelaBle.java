package com.mario.lynkband.vela;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;

import java.io.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** A single authenticated install uses one selected GATT peripheral. */
@SuppressLint("MissingPermission")
final class VelaBle extends BluetoothGattCallback implements AutoCloseable {
    private enum Event { CONNECT, SERVICES, SUBSCRIBE, WRITE, MTU }
    private record Reply(Event event, int status) {}
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]> packets = new ArrayBlockingQueue<>(256);
    private final BooleanSupplier cancelled;
    private final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx, rx;
    private volatile IOException failure;
    private volatile int chunk = 20;
    private int writeType;

    VelaBle(BooleanSupplier cancelled) { this.cancelled = cancelled; }

    void connect(Context context, BluetoothDevice device) throws IOException {
        gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IOException("无法打开 BLE 连接。");
        await(Event.CONNECT);
        if (!gatt.discoverServices()) throw new IOException("无法查询 BLE 服务。");
        await(Event.SERVICES);
        for (BluetoothGattService service : gatt.getServices()) {
            if (!matches(service.getUuid(), "fe95")) continue;
            for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                if (matches(ch.getUuid(), "005f")) tx = ch;
                if (matches(ch.getUuid(), "005e")) rx = ch;
            }
            if (tx != null && rx != null) break;
            tx = rx = null;
        }
        if (tx == null || rx == null) throw new IOException("设备没有公布小米 BLE V2 安装通道。");
        int props = tx.getProperties();
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        else throw new IOException("BLE 发送特征不可写。");
        // A rejected MTU request leaves the ATT default of 23 (20 data bytes).
        if (gatt.requestMtu(247)) waitReply(Event.MTU, 4, true);
        BluetoothGattDescriptor ccc = rx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        boolean notify = (rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        if (ccc == null || !gatt.setCharacteristicNotification(rx, true)) throw new IOException("无法订阅 BLE 接收通道。");
        byte[] value = notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) started = gatt.writeDescriptor(ccc, value) == BluetoothStatusCodes.SUCCESS;
        else { ccc.setValue(value); started = gatt.writeDescriptor(ccc); }
        if (!started) throw new IOException("设备未接受 BLE 订阅请求。");
        await(Event.SUBSCRIBE);
    }

    static boolean matches(UUID uuid, String shortId) {
        return uuid.toString().substring(0, 8).endsWith(shortId);
    }

    int payloadSize() { return chunk; }

    private void check() throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("操作已取消。");
        if (failure != null) throw failure;
        if (System.nanoTime() >= deadline) throw new IOException("BLE 安装超时。");
    }

    private void await(Event event) throws IOException { waitReply(event, 12, false); }
    private void waitReply(Event event, int seconds, boolean optional) throws IOException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            check();
            Reply reply = poll(replies);
            if (reply == null || reply.event != event) continue;
            if (!optional && reply.status != BluetoothGatt.GATT_SUCCESS)
                throw new IOException("BLE " + event + " 失败，系统状态码 " + reply.status + "。");
            return;
        }
        if (!optional) throw new IOException("等待 BLE " + event + " 应答超时。");
    }

    private static <T> T poll(BlockingQueue<T> queue) throws IOException {
        try { return queue.poll(150, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }

    final InputStream input = new InputStream() {
        private byte[] packet;
        private int offset;
        @Override public int read(byte[] dst, int from, int count) throws IOException {
            if (count == 0) return 0;
            while (packet == null || offset == packet.length) {
                check();
                packet = poll(packets);
                offset = 0;
            }
            int size = Math.min(count, packet.length - offset);
            System.arraycopy(packet, offset, dst, from, size);
            offset += size;
            return size;
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; read(one, 0, 1); return one[0] & 255;
        }
    };

    final OutputStream output = new OutputStream() {
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}); }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            int end = offset + count;
            while (offset < end) {
                check();
                byte[] part = Arrays.copyOfRange(bytes, offset, Math.min(end, offset + chunk));
                boolean started;
                if (Build.VERSION.SDK_INT >= 33) started = gatt.writeCharacteristic(tx, part, writeType) == BluetoothStatusCodes.SUCCESS;
                else { tx.setWriteType(writeType); tx.setValue(part); started = gatt.writeCharacteristic(tx); }
                if (!started) throw new IOException("系统拒绝写入 BLE 数据。");
                await(Event.WRITE);
                offset += part.length;
            }
        }
    };

    @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) replies.offer(new Reply(Event.CONNECT, status));
        else if (state == BluetoothProfile.STATE_DISCONNECTED) failure = new IOException("BLE 已断开，系统状态码 " + status + "。");
    }
    @Override public void onServicesDiscovered(BluetoothGatt g, int status) { replies.offer(new Reply(Event.SERVICES, status)); }
    @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) chunk = Math.min(244, mtu - 3);
        replies.offer(new Reply(Event.MTU, status));
    }
    @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { replies.offer(new Reply(Event.SUBSCRIBE, status)); }
    @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) { replies.offer(new Reply(Event.WRITE, status)); }
    @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) { accept(ch, value); }
    @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) { accept(ch, ch.getValue()); }
    private void accept(BluetoothGattCharacteristic ch, byte[] data) {
        if (ch == rx && data != null && data.length > 0 && !packets.offer(data.clone())) failure = new IOException("BLE 接收队列已满。");
    }

    @Override public void close() {
        failure = new IOException("BLE 连接已关闭。");
        if (gatt != null) { try { gatt.disconnect(); } finally { gatt.close(); } }
    }
}
