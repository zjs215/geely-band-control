package com.mario.lynkband.watch;

import android.graphics.Bitmap;
import com.mario.lynkband.solar.ThemeArt;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;
import com.mario.lynkband.transport.ControlCategory;
import com.mario.lynkband.transport.WatchPhotoWire;

final class WatchState {
    private static final String UNKNOWN_RESULT = "结果待核对，请查看官方车况";
    JSONObject data = new JSONObject();
    Bitmap carPhoto;
    boolean photoResolved;
    private boolean legacyPhotoOnly;
    private boolean retryLegacyPhoto;
    private String carPhotoId = "";
    JSONArray actions = new JSONArray();
    String status = "等待手机连接", note = "";
    private JSONObject pending, choice;
    private String choiceToken;
    private JSONObject loading;
    private String loadingVehicle;
    private long loadingDeadline;
    private static final long OPERATION_TIMEOUT = 45_000;
    private boolean phoneAllowsControls = true;
    private long expires;

    private long fastPollUntil;
    private boolean needsStatusAfterAck;
    private PhoneSolar phoneSolar;

    record PhoneSolar(boolean night, String info) {}

    PhoneSolar phoneSolar() { return phoneSolar; }

    enum ControlState {
        OPEN, CLOSED, LOADING, UNKNOWN, LEGACY;

        static ControlState of(JSONObject item) {
            if (item.has("controlState")) {
                return switch (item.optString("controlState")) {
                    case "open" -> OPEN;
                    case "closed" -> CLOSED;
                    case "loading" -> LOADING;
                    default -> UNKNOWN;
                };
            }
            String caption = item.optString("displayLabel");
            int dot = caption.lastIndexOf('.');
            if (dot <= 0) return LEGACY;
            return switch (caption.substring(dot + 1)) {
                case "开" -> OPEN;
                case "关" -> CLOSED;
                case "加载" -> LOADING;
                case "未知" -> UNKNOWN;
                default -> LEGACY;
            };
        }
    }

    static String controlName(JSONObject item) {
        if (item.opt("controlName") instanceof String name && !name.trim().isEmpty()) return name;
        if (item.opt("displayLabel") instanceof String caption && !caption.trim().isEmpty()) {
            return caption.replaceFirst("\\.(开|关|加载|未知)$", "");
        }
        return item.optString("label", "待标记");
    }

    static String tileCaption(JSONObject item) {
        String name = controlName(item);
        return switch (ControlState.of(item)) {
            case OPEN -> name + ".开";
            case CLOSED -> name + ".关";
            case LOADING -> name + ".加载";
            case UNKNOWN -> name + ".未知";
            case LEGACY -> name;
        };
    }

    static boolean actionable(JSONObject item) {
        if (item == null || !item.optBoolean("enabled", true)) return false;
        ControlState state = ControlState.of(item);
        return state != ControlState.UNKNOWN && state != ControlState.LOADING;
    }

    JSONObject loadingAction() { return loading; }

    boolean showsLoading(JSONObject item) {
        return loading != null && item != null && loading.optString("slot").equals(item.opt("slot"))
                && loading.optString("category").equals(item.optString("category"));
    }

    boolean canControl(JSONObject item) {
        // The phone observes one operation at a time. Preserve its identity until it settles.
        return loading == null && !needsStatusAfterAck && phoneAllowsControls
                && !"车况只读".equals(status) && actionable(item);
    }

    boolean canSyncTheme(long now) {
        return loading == null && !needsStatusAfterAck && !retryLegacyPhoto && now >= fastPollUntil;
    }

    long pollDelay(long now) {
        if (needsStatusAfterAck || retryLegacyPhoto) return 0;
        if (loading != null) return Math.min(500, Math.max(0, loadingDeadline - now));
        return now < fastPollUntil ? 500 : 4000;
    }

    private void finishUnknown() {
        loading = null;
        note = UNKNOWN_RESULT;
    }

    private void observeSnapshot(long now) {
        if (loading == null) return;
        String vehicle = data.optString("vehicle");
        if (now >= loadingDeadline || (!vehicle.isEmpty() && !vehicle.equals(loadingVehicle))) {
            finishUnknown();
            return;
        }
        if (vehicle.isEmpty()) return; // Provider may hide the complete panel during verification.
        ControlState initial = ControlState.of(loading);
        for (int i = 0; i < actions.length(); i++) {
            JSONObject item = actions.optJSONObject(i);
            if (!showsLoading(item)) continue;
            ControlState current = ControlState.of(item);
            if ((initial == ControlState.CLOSED && current == ControlState.OPEN)
                    || (initial == ControlState.OPEN && current == ControlState.CLOSED)) {
                note = controlName(loading) + " · 已完成";
                loading = null;
            }
            return;
        }
    }

    boolean busy() { return pending != null; }
    boolean readingStatus() { return busy() && "status".equals(pending.optString("type")); }
    boolean readingTheme() { return busy() && ThemeArt.TRANSFER.equals(pending.optString("type")); }
    boolean reading() { return readingStatus() || readingTheme(); }
    boolean controlPending() { return busy() && !reading(); }
    String preparingTitle() {
        return busy() && "prepare".equals(pending.optString("type")) ? pending.optString("label") : "";
    }
    boolean confirming() { return choice != null; }
    String title() { return choice == null ? "" : choice.optString("label"); }
    ControlState confirmState() { return choice == null ? ControlState.LEGACY : ControlState.of(choice); }
    ControlCategory confirmCategory() {
        return ControlCategory.resolve(choice == null ? "" : choice.optString("category"), title());
    }
    void cancel() { choice = null; choiceToken = null; expires = 0; }

    JSONObject request(String type, JSONObject action, long now) throws JSONException {
        if (busy() || (needsStatusAfterAck && !type.equals("status"))) return null;
        if (loading != null && now >= loadingDeadline) finishUnknown();
        if (type.equals("prepare") && !canControl(action)) return null;
        JSONObject request = new JSONObject().put("v", 1).put("id", UUID.randomUUID().toString())
                .put("type", type).put("visualActions", true).put("localWatchIcons", true)
                .put("supportsControlState", true).put("carPhotoId", carPhotoId);
        if (!legacyPhotoOnly) request.put(WatchPhotoWire.HD_CAPABILITY, true);
        if ("status".equals(type)) request.put("watchSolar", true);
        if (type.equals("execute")) {
            if (choice == null) return null;
            if (now >= expires) { cancel(); note = "确认已过期，请重新选择"; return null; }
            action = choice;
            request.put("compactExecuteAck", true).put("token", choiceToken);
            loading = choice;
            loadingVehicle = data.optString("vehicle");
            loadingDeadline = now + OPERATION_TIMEOUT;
            cancel();
        } else if (confirming()) return null;
        if (action != null) {
            for (String name : new String[]{"slot", "key", "label"}) request.put(name, action.getString(name));
        }
        pending = request;
        return request;
    }

    void apply(JSONObject reply, Bitmap decodedPhoto, long now) throws JSONException {
        if (pending == null || !Integer.valueOf(1).equals(reply.opt("v"))
                || !pending.getString("id").equals(reply.opt("id")))
            throw new JSONException("Wrong request ID or version");
        JSONObject request = pending;
        String type = request.getString("type");
        if (ThemeArt.TRANSFER.equals(type)) {
            pending = null;
            return;
        }
        if (reply.has("type")) {
            if (!"executeAck".equals(reply.opt("type")) || !"execute".equals(type)
                    || !Boolean.TRUE.equals(request.opt("compactExecuteAck"))
                    || !(reply.opt("note") instanceof String outcome) || outcome.trim().isEmpty()
                    || (!(reply.opt("accepted") instanceof Boolean) && reply.opt("accepted") != JSONObject.NULL))
                throw new JSONException("Unexpected execute acknowledgement");
            note = outcome;
            if (Boolean.FALSE.equals(reply.opt("accepted")) ||
                    (Boolean.TRUE.equals(reply.opt("accepted")) && loading != null && loading.optBoolean("momentary"))) {
                loading = null;
            }
            else if (now >= loadingDeadline) finishUnknown();
            // Keep the last display until the status poll catches up. Its actions are stale.
            needsStatusAfterAck = true;
            fastPollUntil = now + 5000;
            pending = null;
            return;
        }
        // Older phones always return the full snapshot, even when the watch opted in.
        JSONObject nextData = reply.getJSONObject("data");
        JSONArray nextActions = reply.getJSONArray("actions");
        String nextStatus = reply.getString("status");
        String nextNote = reply.has("note") ? reply.getString("note") : note;
        if (!nextData.optString("vehicle").equals(data.optString("vehicle"))) legacyPhotoOnly = false;
        data = nextData;
        String incomingId = data.opt("carPhotoId") instanceof String id ? id : "";
        // An ID-only reply can reuse exactly the image we acknowledged on this connection.
        boolean hasPhotoBytes = data.remove("carPhoto") != null;
        if (hasPhotoBytes || !carPhotoId.equals(incomingId)) {
            carPhoto = decodedPhoto;
            carPhotoId = decodedPhoto == null ? "" : incomingId;
        }
        retryLegacyPhoto = hasPhotoBytes && decodedPhoto == null && request.optBoolean(WatchPhotoWire.HD_CAPABILITY);
        if (retryLegacyPhoto) legacyPhotoOnly = true;
        photoResolved = request.has("carPhotoId") && !retryLegacyPhoto;
        // Older phones still send PNGs with visual metadata. Keep them out of display and confirmation state.
        for (int i = 0; i < nextActions.length(); i++) {
            JSONObject item = nextActions.optJSONObject(i);
            if (item != null) item.remove("icon");
        }
        actions = nextActions;
        status = nextStatus;
        note = nextNote;
        pending = null;
        if ("status".equals(type)) {
            needsStatusAfterAck = false;
            phoneSolar = null;
            if (reply.opt("themeNight") instanceof Boolean night) {
                String info = reply.opt("solarInfo") instanceof String text && text.length() <= 80 ? text.trim() : "";
                phoneSolar = new PhoneSolar(night, info);
            }
        }
        phoneAllowsControls = reply.optBoolean("controlsAllowed", !"车况只读".equals(status));
        if (!phoneAllowsControls || "车况只读".equals(status)) cancel();
        if ("execute".equals(type)) {
            fastPollUntil = now + 5000;
            // Legacy dispatch replies have only user-facing text as their refusal signal.
            switch (reply.optString("note")) {
                case "确认已失效，请重新选择", "卡片按钮已变化，请重新同步", "领克卡片未接受操作",
                        "正在等候上一次领克卡片操作", "请在手机检查穿戴车控设置和卡片状态" -> loading = null;
            }
        }
        observeSnapshot(now);
        if ("prepare".equals(type) && reply.opt("token") instanceof String token && !token.isEmpty()
                && request.getString("label").equals(reply.opt("label"))) {
            for (int i = 0; i < actions.length(); i++) {
                JSONObject item = actions.optJSONObject(i);
                if (canControl(item) && request.getString("key").equals(item.optString("key"))
                        && request.getString("slot").equals(item.optString("slot"))
                        && request.getString("label").equals(item.optString("label"))) {
                    choice = new JSONObject(item.toString());
                    choiceToken = token;
                    expires = now + 12000;
                    return;
                }
            }
            note = "卡片动作已变化，请重新选择";
        }
    }

    void offline(String reason) {
        boolean unacknowledged = pending != null && "execute".equals(pending.optString("type"));
        note = unacknowledged || loading != null || UNKNOWN_RESULT.equals(note) ? UNKNOWN_RESULT : "";
        loading = null;
        pending = null;
        fastPollUntil = 0;
        needsStatusAfterAck = false;
        phoneAllowsControls = false;
        phoneSolar = null;
        cancel();
        carPhoto = null;
        carPhotoId = "";
        legacyPhotoOnly = false;
        retryLegacyPhoto = false;
        photoResolved = true;
        data = new JSONObject(); actions = new JSONArray(); status = reason;
    }
}
