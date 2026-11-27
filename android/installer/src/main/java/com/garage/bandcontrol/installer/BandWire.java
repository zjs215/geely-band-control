package com.garage.bandcontrol.installer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** The Vela V2 frame format is adapted from minstall under the MIT license. */
final class BandWire implements AutoCloseable {
    static final int ACK = 1;
    static final int SESSION = 2;
    static final int DATA = 3;
    private static final int PREAMBLE = 0xa5;
    private static final byte[] EMPTY = new byte[0];

    record Frame(int type, int sequence, byte[] payload) {}

    private final OutputStream output;
    private final ArrayBlockingQueue<Frame> incoming = new ArrayBlockingQueue<>(64);
    private final Thread reader;
    private volatile IOException readFailure;

    BandWire(InputStream input, OutputStream output) {
        this.output = output;
        DataInputStream stream = new DataInputStream(new BufferedInputStream(input));
        reader = new Thread(() -> readFrames(stream), "Vela RFCOMM reader");
        reader.setDaemon(true);
        reader.start();
    }

    void write(byte[] bytes) throws IOException {
        output.write(bytes);
        output.flush();
    }

    Frame receive(long deadline) throws IOException, InterruptedException {
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException();
            Frame frame = incoming.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)),
                    TimeUnit.NANOSECONDS);
            if (frame != null) {
                if (frame.type() == DATA) write(encode(ACK, frame.sequence(), EMPTY));
                return frame;
            }
            if (readFailure != null) throw readFailure;
        }
    }

    private void readFrames(DataInputStream stream) {
        try {
            while (!Thread.currentThread().isInterrupted()) incoming.put(decode(stream));
        } catch (IOException error) {
            readFailure = error;
        } catch (InterruptedException canceled) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        // The caller owns the socket and closes it to wake any blocking read.
        reader.interrupt();
    }

    static byte[] encode(int type, int sequence, byte[] payload) {
        if (payload.length > 0xffff) throw new IllegalArgumentException("Vela frame too large");
        return ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) PREAMBLE).put((byte) PREAMBLE)
                .put((byte) type).put((byte) sequence)
                .putShort((short) payload.length).putShort((short) crc16(payload))
                .put(payload).array();
    }

    static Frame decode(DataInputStream stream) throws IOException {
        while (true) {
            int previous = 0;
            int next = stream.readUnsignedByte();
            while (previous != PREAMBLE || next != PREAMBLE) {
                previous = next;
                next = stream.readUnsignedByte();
            }
            int type = stream.readUnsignedByte() & 0x0f;
            int sequence = stream.readUnsignedByte();
            int length = readUnsignedShortLe(stream);
            int expectedCrc = readUnsignedShortLe(stream);
            byte[] payload = new byte[length];
            stream.readFully(payload);
            if (crc16(payload) == expectedCrc) return new Frame(type, sequence, payload);
        }
    }

    private static int readUnsignedShortLe(DataInputStream stream) throws IOException {
        return stream.readUnsignedByte() | (stream.readUnsignedByte() << 8);
    }

    static int crc16(byte[] payload) {
        int crc = 0;
        for (byte value : payload) {
            crc ^= value & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xa001);
            }
        }
        return crc;
    }
}
