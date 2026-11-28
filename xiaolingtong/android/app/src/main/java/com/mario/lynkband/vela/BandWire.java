// Adapted for 小领通 from zjs215/geely-band-control (Apache-2.0).
package com.mario.lynkband.vela;

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
    static final int NAK = 0;
    static final int ACK = 1;
    static final int SESSION = 2;
    static final int DATA = 3;
    private static final int PREAMBLE = 0xa5;
    private static final byte[] EMPTY = new byte[0];

    record Frame(int type, int sequence, boolean fast, byte[] payload) {}

    private final OutputStream output;
    private final BandDiagnostics.Trace trace;
    private final ArrayBlockingQueue<Frame> incoming = new ArrayBlockingQueue<>(64);
    private final Thread reader;
    private volatile IOException readFailure;
    private volatile boolean closed;
    private boolean collecting = true;

    BandWire(InputStream input, OutputStream output) {
        this(input, output, BandDiagnostics.Trace.OFF);
    }

    BandWire(InputStream input, OutputStream output, BandDiagnostics.Trace trace) {
        this.output = output;
        this.trace = trace;
        DataInputStream stream = new DataInputStream(new BufferedInputStream(input));
        reader = new Thread(() -> readFrames(stream), "Vela RFCOMM reader");
        reader.setDaemon(true);
        reader.start();
    }

    synchronized void write(byte[] bytes) throws IOException {
        try {
            output.write(bytes);
            output.flush();
        } catch (IOException error) {
            boolean framed = bytes.length >= 8 && (bytes[0] & 0xff) == PREAMBLE && (bytes[1] & 0xff) == PREAMBLE;
            trace.failure("wire.write_failed", error, "bytes", bytes.length, "local_close", closed,
                    "type", framed ? bytes[2] & 0x0f : -1, "seq", framed ? bytes[3] & 0xff : -1);
            throw error;
        }
    }

    Frame receive(long deadline) throws IOException, InterruptedException {
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException();
            Frame frame = incoming.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)),
                    TimeUnit.NANOSECONDS);
            if (frame != null) return frame;
            if (readFailure != null) throw readFailure;
            if (closed) throw new IOException("Bluetooth session closed");
        }
    }

    private void readFrames(DataInputStream stream) {
        int lastType = -1;
        int lastSeq = -1;
        int received = 0;
        try {
            while (!closed) {
                Frame frame = decode(stream, trace);
                lastType = frame.type();
                lastSeq = frame.sequence();
                received++;
                if (frame.type() == SESSION && frame.payload().length > 0) {
                    trace.event("wire.session_command", "command", frame.payload()[0] & 0xff, "seq", frame.sequence());
                }
                if (frame.type() == DATA && !frame.fast()) write(encode(ACK, frame.sequence(), EMPTY));
                synchronized (incoming) {
                    if (!collecting) {
                        if (frame.type() == DATA) {
                            trace.event("wire.idle_data", "seq", frame.sequence(), "bytes", frame.payload().length);
                        }
                    } else if (!incoming.offer(frame)) {
                        throw new IOException("Too many Bluetooth replies");
                    }
                }
            }
        } catch (IOException error) {
            if (!closed) trace.failure("wire.read_failed", error, "received_frames", received,
                    "last_type", lastType, "last_seq", lastSeq);
            readFailure = error;
        }
    }

    void collectReplies(boolean enabled) {
        synchronized (incoming) {
            if (collecting != enabled) incoming.clear();
            collecting = enabled;
        }
    }

    boolean isOpen() {
        return !closed && readFailure == null;
    }

    @Override
    public void close() {
        // The caller owns the socket and closes it to wake any blocking read.
        closed = true;
        incoming.clear();
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
        return decode(stream, BandDiagnostics.Trace.OFF);
    }

    private static Frame decode(DataInputStream stream, BandDiagnostics.Trace trace) throws IOException {
        while (true) {
            int previous = 0;
            int next = stream.readUnsignedByte();
            while (previous != PREAMBLE || next != PREAMBLE) {
                previous = next;
                next = stream.readUnsignedByte();
            }
            int flags = stream.readUnsignedByte();
            int type = flags & 0x0f;
            int sequence = stream.readUnsignedByte();
            int length = readUnsignedShortLe(stream);
            int expectedCrc = readUnsignedShortLe(stream);
            byte[] payload = new byte[length];
            stream.readFully(payload);
            if (crc16(payload) == expectedCrc) return new Frame(type, sequence, (flags & 0x10) != 0, payload);
            trace.event("wire.bad_crc", "type", type, "seq", sequence, "bytes", length);
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
