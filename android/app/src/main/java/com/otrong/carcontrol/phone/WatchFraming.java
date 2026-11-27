package com.otrong.carcontrol.phone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class WatchFraming {
    private static final int MAX_FRAME_BYTES = BandMessage.MAX_BYTES;

    private WatchFraming() {}

    static byte[] readFrame(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        boolean overflow = false;
        while (true) {
            int next = input.read();
            if (next < 0) return null;
            if (next == '\n') {
                if (overflow || buffer.size() == 0) {
                    buffer.reset();
                    overflow = false;
                    continue;
                }
                return buffer.toByteArray();
            }
            if (next == '\r') continue;
            if (!overflow) {
                if (buffer.size() < MAX_FRAME_BYTES) buffer.write(next);
                else overflow = true;
            }
        }
    }

    static void writeFrame(OutputStream output, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > MAX_FRAME_BYTES) {
            throw new IOException("Frame size out of bounds: " + payload.length);
        }
        output.write(payload);
        output.write('\n');
        output.flush();
    }
}
