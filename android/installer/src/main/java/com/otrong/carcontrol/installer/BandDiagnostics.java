package com.otrong.carcontrol.installer;

import android.bluetooth.BluetoothSocketException;
import android.os.Build;

import java.io.EOFException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A bounded, private protocol trace. Never writes payloads, credentials, or addresses. */
final class BandDiagnostics {
    private static final int SEGMENT_BYTES = 64 * 1024;
    private static final Object FILE_LOCK = new Object();

    private final File recent;
    private final File previous;
    private final String header;

    BandDiagnostics(File directory, String appVersion, int androidApi, String manufacturer, String phoneModel) {
        recent = new File(directory, "vela-connection.log");
        previous = new File(directory, "vela-connection.previous.log");
        header = "# Vela diagnostics 1\n# installer=" + safeLabel(appVersion)
                + " android_api=" + androidApi + " phone=" + safeLabel(manufacturer) + "/" + safeLabel(phoneModel) + "\n";
    }

    private static String safeLabel(String label) {
        if (label == null) return "unknown";
        String safe = label.replaceAll("[^a-zA-Z0-9 ._-]", "_");
        return safe.substring(0, Math.min(safe.length(), 64));
    }

    Trace begin() {
        return new Trace(this, UUID.randomUUID().toString().substring(0, 8));
    }

    boolean hasEvents() {
        synchronized (FILE_LOCK) {
            return recent.length() > 0 || previous.length() > 0;
        }
    }

    byte[] snapshot() throws IOException {
        synchronized (FILE_LOCK) {
            if (!hasEvents()) throw new IOException("No Vela diagnostics");
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            writeText(destination, "# Exported " + Instant.now() + "\n");
            copy(previous, destination);
            copy(recent, destination);
            return destination.toByteArray();
        }
    }

    private static void copy(File file, OutputStream destination) throws IOException {
        if (!file.isFile()) return;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) destination.write(buffer, 0, count);
        }
    }

    private void append(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (FILE_LOCK) {
            try {
                if (recent.length() + bytes.length > SEGMENT_BYTES) {
                    Files.move(recent.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                boolean newSegment = recent.length() == 0;
                try (OutputStream output = Files.newOutputStream(recent.toPath(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                    if (newSegment) writeText(output, header);
                    output.write(bytes);
                }
            } catch (IOException | SecurityException ignored) {
                // Diagnostics must never prevent connection or installation.
            }
        }
    }

    private static void writeText(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
    }

    static final class Trace {
        static final Trace OFF = new Trace(null, "");

        private final BandDiagnostics owner;
        private final String id;
        private final long started = System.nanoTime();

        private Trace(BandDiagnostics owner, String id) {
            this.owner = owner;
            this.id = id;
        }

        // Callers supply fixed event names and field keys. Values are limited to protocol metadata.
        void event(String name, Object... fields) {
            if (owner == null) return;
            owner.append(line(name, fields).append('\n').toString());
        }

        void failure(String name, Throwable error, Object... fields) {
            if (owner == null) return;
            StringBuilder line = line(name, fields);
            for (int depth = 0; depth < 6 && error != null; depth++, error = error.getCause()) {
                line.append(depth == 0 ? " errors=" : ">").append(error.getClass().getSimpleName())
                        .append('/').append(reason(error));
                if (Build.VERSION.SDK_INT >= 34 && error instanceof BluetoothSocketException socket) {
                    line.append('/').append(socket.getErrorCode());
                }
            }
            owner.append(line.append('\n').toString());
        }

        private StringBuilder line(String name, Object[] fields) {
            StringBuilder text = new StringBuilder().append(Instant.now()).append(" run=").append(id)
                    .append(" +").append(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).append("ms ")
                    .append(name);
            for (int i = 0; i + 1 < fields.length; i += 2) {
                Object value = fields[i + 1];
                if (value instanceof Enum<?> label) text.append(' ').append(fields[i]).append('=').append(label.name());
                else if (value instanceof Integer || value instanceof Long || value instanceof Boolean) {
                    text.append(' ').append(fields[i]).append('=').append(value);
                }
            }
            return text;
        }
    }

    private static String reason(Throwable error) {
        if (error instanceof EOFException) return "eof";
        if (error instanceof SocketTimeoutException) return "timeout";
        if (error instanceof SecurityException) return "permission";
        if (error instanceof InterruptedException) return "interrupted";
        String message = error.getMessage();
        if (message == null) return "other";
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("read ret: -1") || lower.contains("end of stream")) return "eof";
        if (lower.contains("broken pipe") || lower.contains("epipe")) return "broken_pipe";
        if (lower.contains("connection reset") || lower.contains("econnreset")) return "reset";
        if (lower.contains("disconnected") || lower.contains("enotconn")) return "disconnected";
        if (lower.contains("closed") || lower.contains("ebadf")) return "closed";
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时")) return "timeout";
        if (lower.contains("too many bluetooth replies")) return "queue_full";
        return "other";
    }
}
