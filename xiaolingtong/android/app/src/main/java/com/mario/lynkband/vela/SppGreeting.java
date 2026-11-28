package com.mario.lynkband.vela;

import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class SppGreeting {
    static final byte[] HELLO = {(byte)0xba,(byte)0xdc,(byte)0xfe,0,(byte)0xc0,3,0,0,1,0,(byte)0xef};
    enum Version { V1, V2, UNKNOWN }

    // Only consume a complete, unencrypted version reply. Leave any V2 data for BandWire.
    static Version probe(BufferedInputStream in, OutputStream out, BooleanSupplier cancelled, int waitMillis)
            throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new IOException("操作已取消。");
        out.write(HELLO);
        out.flush();
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        final int limit = 265;
        byte[] frame = new byte[limit];
        in.mark(limit + 1);
        int count = 0, expected = 7;
        while (System.nanoTime() < until) {
            if (cancelled.getAsBoolean()) throw new IOException("操作已取消。");
            int available = in.available();
            if (available == 0) { Thread.sleep(15); continue; }
            int n = in.read(frame, count, Math.min(available, expected - count));
            if (n < 0) throw new EOFException("串口已断开。");
            count += n;
            if (count >= 2 && frame[0] == (byte)0xa5 && frame[1] == (byte)0xa5) {
                in.reset(); return Version.V2;
            }
            if (count < expected) continue;
            if (expected == 7) {
                int length = (frame[5] & 255) | ((frame[6] & 255) << 8);
                if (frame[0] != (byte)0xba || frame[1] != (byte)0xdc || frame[2] != (byte)0xfe ||
                        frame[3] != 0 || (frame[4] & 15) != 0 || length < 4 || length + 8 > limit) break;
                expected = length + 8;
            } else {
                if (frame[9] != 0 || frame[count - 1] != (byte)0xef) break;
                int version = frame[10] & 255;
                if (version == 1) return Version.V1;
                // Newer peers advertise values above two. Confirm compatibility with the V2 session reply.
                if (version >= 2) return Version.V2;
                throw new IOException("设备报告的串口协议版本为 " + version + "，暂未支持。");
            }
        }
        in.reset();
        return Version.UNKNOWN;
    }
}
