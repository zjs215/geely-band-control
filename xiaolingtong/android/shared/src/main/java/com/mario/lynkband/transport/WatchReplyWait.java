package com.mario.lynkband.transport;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Optional transport progress frames. One request still has exactly one application reply. */
public final class WatchReplyWait {
    public static final String CAPABILITY = "supportsWorking";
    public static final int SILENCE_MS = 7500;
    public static final int DEADLINE_MS = 20000;
    private WatchReplyWait() {}

    public static String progressFor(byte[] frame) {
        if (frame.length > 4096) return null;
        try {
            JSONObject req = new JSONObject(new String(frame, StandardCharsets.UTF_8));
            String id = req.getString("id");
            if (req.optInt("v") != 1 || id.isEmpty() || id.length() > 80 || !req.optBoolean(CAPABILITY)) return null;
            return new JSONObject().put("v", 1).put("id", id).put("working", true).toString();
        } catch (JSONException invalid) { return null; }
    }

    /** If the UI queue was stalled, decline before executing. A following status poll can recover. */
    public static String expired(byte[] frame) {
        if (frame.length > 4096) return null;
        try {
            JSONObject req = new JSONObject(new String(frame, StandardCharsets.UTF_8));
            String id = req.getString("id");
            if (req.optInt("v") != 1 || id.isEmpty() || id.length() > 80) return null;
            return new JSONObject().put("v", 1).put("id", id).put("status", "等待手机卡片同步")
                    .put("controlsAllowed", false).put("data", new JSONObject()).put("actions", new org.json.JSONArray())
                    .put("note", "手机卡片繁忙，本次请求已过期").toString();
        } catch (JSONException invalid) { return null; }
    }

    public static boolean matches(JSONObject response, String pendingId) {
        return pendingId != null && response.optInt("v") == 1 && pendingId.equals(response.optString("id"))
                && Boolean.TRUE.equals(response.opt("working"));
    }

    public interface Notify { void send() throws IOException; }

    public static <T> T await(Future<T> result, Notify notify) throws ExecutionException, InterruptedException, IOException, TimeoutException {
        if (notify == null) return result.get(6, TimeUnit.SECONDS);
        return await(result, notify, 2000, DEADLINE_MS);
    }

    static <T> T await(Future<T> result, Notify notify, long intervalMs, long limitMs)
            throws ExecutionException, InterruptedException, IOException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limitMs);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Phone reply deadline");
            try { return result.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(intervalMs)), TimeUnit.NANOSECONDS); }
            catch (TimeoutException waiting) {
                if (System.nanoTime() >= deadline) throw waiting;
                notify.send();
            }
        }
    }
}
