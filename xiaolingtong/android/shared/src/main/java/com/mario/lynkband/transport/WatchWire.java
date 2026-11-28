package com.mario.lynkband.transport;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class WatchWire {
    public static final UUID SERVICE = UUID.fromString("4d24acc8-3b43-41c7-ad56-dd59ce35c561");
    private static final int LIMIT = 8192;
    public static final int REPLY_LIMIT = 32 * 1024;
    private WatchWire() {}

    public static byte[] read(InputStream in) throws IOException {
        return read(in, LIMIT);
    }
    public static byte[] readReply(InputStream in) throws IOException {
        return read(in, REPLY_LIMIT);
    }
    private static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(256);
        for (int next; (next = in.read()) >= 0;) {
            if (next == '\n' && frame.size() > 0) return frame.toByteArray();
            if (next == '\n' || frame.size() == limit) throw new IOException("Invalid watch frame");
            frame.write(next);
        }
        throw new EOFException("Watch link closed");
    }
    public static void write(OutputStream out, String json) throws IOException {
        write(out, json, LIMIT);
    }
    public static void writeReply(OutputStream out, String json) throws IOException {
        write(out, json, REPLY_LIMIT);
    }
    private static void write(OutputStream out, String json, int limit) throws IOException {
        byte[] frame = json.getBytes(StandardCharsets.UTF_8);
        if (frame.length == 0 || frame.length > limit || json.indexOf('\n') >= 0) throw new IOException("Invalid watch frame");
        out.write(frame);
        out.write('\n');
        out.flush();
    }
}
