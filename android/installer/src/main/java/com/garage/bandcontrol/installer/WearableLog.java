package com.garage.bandcontrol.installer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads binding records from an explicitly selected Mi Fitness ZIP. */
final class WearableLog {
    private static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_LINE_BYTES = 256 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_DEVICES = 64;
    private static final String[] ADDRESS_FIELDS = {"mac", "macAddress", "deviceMac", "bluetoothAddress",
            "deviceAddress", "bleMac", "bleAddress", "btMac", "btAddress", "address"};
    private static final Pattern KEY_FIELD = Pattern.compile(
            "(?i)(?<![\\w])(?:\"(?:encryptKey|authKey)\"\\s*:\\s*\""
                    + "|(?:encryptKey|authKey)\\s*=\\s*)(?:0x)?([0-9a-f]{32})(?=$|[\"\\s,})])");
    private static final Pattern ADDRESS = Pattern.compile("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}");
    private static final Pattern KEY = Pattern.compile("(?:0[xX])?[0-9a-fA-F]{32}");

    private WearableLog() {}

    static Keys read(InputStream source) throws IOException, InterruptedException {
        Keys keys = new Keys();
        try (ZipInputStream zip = new ZipInputStream(source)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int entries = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES || entry.getSize() > MAX_EXPANDED_BYTES - total) {
                    throw tooLarge();
                }
                String name = entry.getName();
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                String file = name.substring(slash + 1).toLowerCase(Locale.ROOT);
                boolean log = !entry.isDirectory() && (file.endsWith(".log") || file.matches(".+\\.log\\.\\d+"));
                LogLines lines = log ? new LogLines(keys, priority(file)) : null;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    total += count;
                    if (total > MAX_EXPANDED_BYTES) throw tooLarge();
                    if (lines != null) lines.append(buffer, count);
                }
                if (lines != null) lines.finish();
            }
            if (entries == 0) throw new InstallFailure("请选择小米运动健康导出的日志 ZIP。");
            if (!keys.found()) {
                throw new InstallFailure("这份日志里未找到可用的 AuthKey。请先连接手环并同步，再重新导出日志。");
            }
            if (keys.devices.isEmpty() && keys.ambiguousUnbound) {
                throw new InstallFailure("日志中有多把 AuthKey，但缺少对应的蓝牙地址。请连接目标手环后重新导出日志。");
            }
            return keys;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            keys.close();
            throw failure;
        }
    }

    private static InstallFailure tooLarge() {
        return new InstallFailure("日志内容过多。请重新导出当前手环的日志后重试。");
    }

    private static int priority(String filename) {
        return switch (filename) {
            case "xiaomifit.main.log" -> 3;
            case "xiaomifit.device.log" -> 2;
            case "transfer.device.log" -> 1;
            default -> 0;
        };
    }

    static String address(String value) {
        String address = value.trim().replace('-', ':');
        if (!ADDRESS.matcher(address).matches() || address.equals("00:00:00:00:00:00")
                || address.equals("02:00:00:00:00:00")) return "";
        return address.toUpperCase(Locale.ROOT);
    }

    private static final class LogLines {
        private final Keys keys;
        private final int priority;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private boolean tooLong;

        LogLines(Keys keys, int priority) {
            this.keys = keys;
            this.priority = priority;
        }

        void append(byte[] buffer, int count) throws InstallFailure {
            int from = 0;
            for (int i = 0; i < count; i++) {
                if (buffer[i] == '\n') {
                    appendChunk(buffer, from, i);
                    finish();
                    from = i + 1;
                }
            }
            appendChunk(buffer, from, count);
        }

        private void appendChunk(byte[] buffer, int from, int to) {
            if (tooLong) return;
            if (line.size() + to - from > MAX_LINE_BYTES) {
                tooLong = true;
                line.reset();
            } else {
                line.write(buffer, from, to - from);
            }
        }

        void finish() throws InstallFailure {
            if (!tooLong && line.size() != 0) {
                scanLine(new String(line.toByteArray(), StandardCharsets.UTF_8), keys, priority);
            }
            line.reset();
            tooLong = false;
        }
    }

    private static void scanLine(String line, Keys keys, int priority) throws InstallFailure {
        if (!line.contains("encryptKey") && !line.contains("authKey")) return;

        // Mi Fitness prefixes serialized device objects with a log tag. Scope
        // the key to its device object, including the nested detail field.
        boolean parsed = false;
        int start = line.indexOf('{');
        while (start >= 0) {
            int end = objectEnd(line, start);
            if (end < 0) break;
            try {
                JSONObject object = new JSONObject(line.substring(start, end));
                parsed |= readObject(object, "", 0, keys, priority);
            } catch (JSONException invalidObject) {
                // The log prefix can itself contain brace-delimited text.
            }
            start = line.indexOf('{', end);
        }
        if (parsed) return;

        // Some log versions emit just the field. It has no dependable
        // address and can only be used when it is the sole unbound key.
        Matcher fields = KEY_FIELD.matcher(line);
        while (fields.find()) {
            keys.add("", fields.group(1), priority);
        }
    }

    private static int objectEnd(String line, int start) {
        int depth = 0;
        boolean quoted = false;
        for (int i = start; i < line.length(); i++) {
            char value = line.charAt(i);
            if (quoted) {
                if (value == '\\') i++;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{' || value == '[') {
                if (++depth > MAX_DEPTH) return -1;
            } else if (value == '}' || value == ']') {
                if (--depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private static boolean readObject(JSONObject object, String parentAddress, int depth, Keys keys, int priority)
            throws InstallFailure {
        if (depth >= MAX_DEPTH) return false;
        String device = "";
        boolean conflictingAddress = false;
        for (String field : ADDRESS_FIELDS) {
            String candidate = address(object.optString(field, ""));
            if (!candidate.isEmpty()) {
                if (!device.isEmpty() && !device.equals(candidate)) {
                    conflictingAddress = true;
                    break;
                }
                device = candidate;
            }
        }
        if (conflictingAddress) device = "";
        else if (device.isEmpty()) device = parentAddress;
        String key = object.optString("encryptKey", "");
        if (!KEY.matcher(key).matches()) key = object.optString("authKey", "");
        boolean found = keys.add(device, key, priority);
        Iterator<String> names = object.keys();
        while (names.hasNext()) {
            String name = names.next();
            Object child = object.opt(name);
            boolean sameDevice = name.equals("detail") || name.equals("device")
                    || name.equals("deviceInfo") || name.equals("deviceDetail");
            if (child instanceof JSONObject nested) {
                found |= readObject(nested, sameDevice ? device : "", depth + 1, keys, priority);
            } else if (child instanceof JSONArray array) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null) found |= readObject(item, "", depth + 1, keys, priority);
                }
            } else if (sameDevice && child instanceof String text && text.startsWith("{")
                    && objectEnd(text, 0) == text.length()) {
                try {
                    found |= readObject(new JSONObject(text), device, depth + 1, keys, priority);
                } catch (JSONException invalidDetail) {
                    // Other fields in this record may still be usable.
                }
            }
        }
        return found;
    }

    static final class Keys implements AutoCloseable {
        private record Key(byte[] value, int priority) {}
        private final Map<String, Key> devices = new HashMap<>();
        private Key unbound;
        private boolean ambiguousUnbound;

        private boolean add(String address, String text, int priority) throws InstallFailure {
            if (!KEY.matcher(text).matches() || text.matches("(?:0[xX])?0{32}")) return false;
            byte[] value = BandProtocol.parseKey(text);
            if (address.isEmpty()) {
                if (ambiguousUnbound) {
                    Arrays.fill(value, (byte) 0);
                } else if (unbound == null) {
                    unbound = new Key(value, priority);
                } else if (!Arrays.equals(unbound.value(), value)) {
                    Arrays.fill(unbound.value(), (byte) 0);
                    Arrays.fill(value, (byte) 0);
                    unbound = null;
                    ambiguousUnbound = true;
                } else {
                    Arrays.fill(value, (byte) 0);
                }
                return true;
            }
            Key previous = devices.get(address);
            if (previous == null && devices.size() >= MAX_DEVICES) {
                Arrays.fill(value, (byte) 0);
                throw tooLarge();
            }
            if (previous == null || previous.priority() <= priority) {
                if (previous != null) Arrays.fill(previous.value(), (byte) 0);
                devices.put(address, new Key(value, priority));
            } else {
                Arrays.fill(value, (byte) 0);
            }
            return true;
        }

        private boolean found() {
            return !devices.isEmpty() || unbound != null || ambiguousUnbound;
        }

        String uniqueAddress() {
            if (devices.size() != 1 || ambiguousUnbound) return "";
            Map.Entry<String, Key> entry = devices.entrySet().iterator().next();
            return unbound == null || Arrays.equals(entry.getValue().value(), unbound.value()) ? entry.getKey() : "";
        }

        boolean matches(String selectedAddress) {
            return key(selectedAddress) != null;
        }

        byte[] copyFor(String selectedAddress) {
            Key key = key(selectedAddress);
            return key == null ? null : key.value().clone();
        }

        private Key key(String selectedAddress) {
            if (selectedAddress.isEmpty()) return null;
            return devices.isEmpty() && !ambiguousUnbound ? unbound : devices.get(selectedAddress);
        }

        @Override
        public void close() {
            devices.values().forEach(key -> Arrays.fill(key.value(), (byte) 0));
            devices.clear();
            if (unbound != null) Arrays.fill(unbound.value(), (byte) 0);
            unbound = null;
            ambiguousUnbound = false;
        }
    }
}
