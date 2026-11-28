package com.mario.lynkband.transport;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

public final class WatchUpdateWire {
    public static final String TYPE = "wearUpdate";
    public static final int MAX_APK_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CHUNK_BYTES = 20 * 1024;
    private static final int MAX_CHUNK_TEXT = ((MAX_CHUNK_BYTES + 2) / 3) * 4;
    private static final Pattern TRANSFER = Pattern.compile("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private WatchUpdateWire() {}

    public static boolean versionAllowed(long current, long candidate) {
        return current > 0 && candidate >= current;
    }

    public static JSONObject request(long installed, int offset, String transfer, boolean staged) throws JSONException {
        JSONObject message = new JSONObject().put("v", 1).put("id", UUID.randomUUID().toString())
                .put("type", TYPE).put("installed", installed).put("offset", offset);
        if (transfer != null) message.put("transfer", transfer);
        if (staged) message.put("staged", true);
        return message;
    }

    public static Chunk chunk(JSONObject reply, int offset, String transfer, int length, String sha256)
            throws InvalidUpdate {
        validate(reply);
        if (reply.has("staged")) throw incompatible();
        String token = string(reply, "transfer");
        String hash = string(reply, "sha256");
        int total = integer(reply, "length");
        if (!TRANSFER.matcher(token).matches() || !SHA256.matcher(hash).matches()
                || total < 1 || total > MAX_APK_BYTES || integer(reply, "offset") != offset
                || (transfer != null && (!transfer.equals(token) || length != total || !sha256.equals(hash))))
            throw incompatible();
        String encoded = string(reply, "chunk");
        if (encoded.length() > MAX_CHUNK_TEXT) throw incompatible();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw incompatible(); }
        if (bytes.length == 0 || bytes.length > MAX_CHUNK_BYTES || bytes.length > total - offset
                || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw incompatible();
        return new Chunk(token, total, hash, bytes);
    }

    public static void acknowledged(JSONObject reply) throws InvalidUpdate {
        validate(reply);
        if (!Boolean.TRUE.equals(reply.opt("staged")) || reply.has("chunk")) throw incompatible();
    }

    private static void validate(JSONObject reply) throws InvalidUpdate {
        if (integer(reply, "v") != 1 || !TYPE.equals(string(reply, "type"))) throw incompatible();
        if (reply.has("error")) {
            String error = string(reply, "error");
            if (error.isEmpty() || error.length() > 200 || reply.has("chunk")) throw incompatible();
            throw new InvalidUpdate(error);
        }
    }

    private static String string(JSONObject reply, String name) throws InvalidUpdate {
        if (reply.opt(name) instanceof String value) return value;
        throw incompatible();
    }

    private static int integer(JSONObject reply, String name) throws InvalidUpdate {
        if (reply.opt(name) instanceof Integer value) return value;
        if (reply.opt(name) instanceof Long value && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE)
            return value.intValue();
        throw incompatible();
    }

    private static InvalidUpdate incompatible() { return new InvalidUpdate("手机更新协议不兼容"); }

    public record Chunk(String transfer, int length, String sha256, byte[] bytes) {}

    public static final class InvalidUpdate extends IOException {
        public InvalidUpdate(String message) { super(message); }
    }
}
