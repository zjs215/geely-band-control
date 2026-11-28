package com.mario.lynkband;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import com.mario.lynkband.transport.WatchIconCodec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

final class CardContent {
    private static final String[][] SLOTS = {
            {"fr_unlock", "unlock", "pb_unlock"}, {"fr_window", "window", "pb_window"},
            {"fr_kjck", "kjck", "pb_kjck"}, {"fr_ccqk", "ccqk_on", "pb_ccqk_on"}
    };
    record Slot(String id, String signature, View target, ImageView icon, String bindingKey,
                boolean loading, boolean stateVisible) {}

    private CardContent() {}

    static String clean(String value) {
        String text = value.trim();
        return text.isEmpty() || text.startsWith("--") || text.equals("—") ? "" : text;
    }

    private static boolean named(View view, String name) {
        if (view.getId() == View.NO_ID) return false;
        try { return name.equals(view.getResources().getResourceEntryName(view.getId())); }
        catch (Resources.NotFoundException ignored) { return false; }
    }

    static View find(View root, String name) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if (named(root, name)) return root;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = find(group.getChildAt(i), name);
                if (child != null) return child;
            }
        }
        return null;
    }

    static String text(View root, String name) {
        View view = find(root, name);
        if (!(view instanceof TextView label)) return "";
        return clean(label.getText().toString());
    }

    // A description is usable only if it names an action. A generic category or a status
    // such as “已上锁” doesn't say what the provider will do when tapped.
    static String describedAction(Slot slot) {
        if (slot.icon() == null) return "";
        String icon = actionDescription(slot.icon());
        View parent = slot.icon().getParent() instanceof View v ? v : null;
        String frame = actionDescription(parent);
        if (!icon.isEmpty() && !frame.isEmpty() && !icon.equals(frame)) return "";
        return icon.isEmpty() ? frame : icon;
    }

    private static String actionDescription(View view) {
        if (view == null || view.getContentDescription() == null) return "";
        String action = promptAction(view.getContentDescription().toString().trim());
        return action == null ? "" : VoicePreset.display(action);
    }

    static boolean awaitingOwner(View root) { return find(root, "verify") != null; }

    // layout_widget can hide the entire vehicle panel while showing verify > ly_confirm.
    static View actionPrompt(View root) {
        View verify = find(root, "verify");
        if (verify == null || find(root, "widget_hint") != null || find(root, "ll_loading_auth") != null)
            return null;
        return readPrompt(verify) == null ? null : verify;
    }

    static View confirmTarget(View prompt, String expectedLabel) {
        Prompt contents = readPrompt(prompt);
        if (contents == null || expectedLabel == null || !contents.action.equals(promptAction(expectedLabel.trim())))
            return null;
        return clickable(contents.confirm) ? contents.confirm : null;
    }

    private record Prompt(String action, View confirm) {}

    // Accept the three official fields exactly once. A changed dialog, extra input or second
    // affirmative button must be handled on the phone, even if its text mentions the action.
    private static Prompt readPrompt(View verify) {
        if (!(verify instanceof ViewGroup overlay) || !named(verify, "verify")
                || verify.getVisibility() != View.VISIBLE || !verify.isEnabled()) return null;
        ViewGroup panel = null;
        for (int i = 0; i < overlay.getChildCount(); i++) {
            View child = overlay.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (panel != null || !(child instanceof ViewGroup group) || !named(child, "ly_confirm")) return null;
            panel = group;
        }
        if (panel == null || !panel.isEnabled()) return null;
        String action = null;
        View confirm = null;
        boolean cancel = false;
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (!(child instanceof TextView label)) return null;
            String value = label.getText().toString();
            if (action == null && named(child, "ty_confirm_title")) {
                if (!value.startsWith("确认") || !(value.endsWith("吗？") || value.endsWith("吗?"))) return null;
                action = promptAction(value.substring(2, value.length() - 2));
                if (action == null) return null;
            } else if (confirm == null && named(child, "tv_verify") && value.equals("确定")) {
                confirm = child;
            } else if (!cancel && named(child, "tv_cancel") && value.equals("取消")) {
                cancel = true;
            } else return null;
        }
        return action != null && confirm != null && cancel ? new Prompt(action, confirm) : null;
    }

    private static String promptAction(String label) {
        // The provider qualifies door lock actions in the question; never strip a generic noun
        // (e.g. 解锁后备箱 has a different target from 解锁车门).
        String action = switch (label) {
            case "上锁车门", "上锁车辆" -> "上锁";
            case "解锁车门", "解锁车辆" -> "解锁";
            default -> VoicePreset.canonical(label);
        };
        return VoiceScenes.concrete(action) ? action : null;
    }

    static boolean readable(View root) {
        return find(root, "rl_widget_cal_state") != null && find(root, "ll_loading_auth") == null;
    }

    static JSONObject snapshot(View root) throws JSONException {
        JSONObject data = new JSONObject();
        if (!readable(root)) return data;
        return data.put("vehicle", text(root, "widget_name"))
                .put("range", text(root, "tv_mileage")).put("unit", text(root, "txt_km"))
                .put("battery", text(root, "tv_electricity")).put("fuel", text(root, "txt_bar"))
                .put("condition", text(root, "condition_txt")).put("time", text(root, "time"));
    }

    static boolean readyForControls(View root) {
        String state = text(root, "condition_txt");
        return readable(root) && !awaitingOwner(root) && find(root, "widget_hint") == null
                && !text(root, "widget_name").isEmpty() && !text(root, "time").isEmpty()
                && !state.contains("行驶") && !state.contains("行车中");
    }

    static List<Slot> slots(View root, String identity) {
        return collectSlots(root, identity, false);
    }

    static List<Slot> visibleSlots(View root, String identity) {
        return collectSlots(root, identity, true);
    }

    private static List<Slot> collectSlots(View root, String identity, boolean includeReadOnly) {
        List<Slot> result = new ArrayList<>();
        boolean ready = readyForControls(root);
        if (!ready && (!includeReadOnly || !readable(root))) return result;
        String vehicle = text(root, "widget_name");
        if (vehicle.isEmpty()) return result;
        // A live 4.3.1 card can supply an exact status glyph without condition or time text.
        boolean stateVisible = !awaitingOwner(root) && find(root, "widget_hint") == null;
        for (String[] ids : SLOTS) {
            View frame = find(root, ids[0]);
            if (frame == null) continue;
            boolean loading = find(frame, ids[2]) != null;
            View image = find(frame, ids[1]);
            ImageView icon = image instanceof ImageView candidate && candidate.getDrawable() != null ? candidate : null;
            if (icon == null && !loading && !includeReadOnly) continue;
            View target = null;
            if (ready && !loading && icon != null && icon.isEnabled() && frame.isEnabled())
                target = clickable(icon) ? icon : clickable(frame) ? frame : null;
            if (target == null && !includeReadOnly) continue;
            String binding = identity + "/" + vehicle + "/" + ids[0];
            String key = binding + "/" + (icon == null ? "unknown" : iconHash(icon));
            result.add(new Slot(ids[0], key, target, icon, binding, loading, stateVisible));
        }
        return result;
    }

    static boolean refresh(View root) {
        if (awaitingOwner(root) || !readable(root)) return false;
        for (String id : new String[]{"ic_widget_sx", "fr_sx", "ic_sdsx", "ly_widget_time"}) {
            View view = find(root, id);
            if (clickable(view)) return view.performClick();
        }
        return false;
    }

    private static boolean clickable(View v) {
        return v != null && v.isEnabled() && v.isClickable() && v.hasOnClickListeners();
    }

    private static void drawIcon(ImageView icon, Bitmap bitmap) {
        Canvas canvas = new Canvas(bitmap);
        if (icon.getWidth() > 0 && icon.getHeight() > 0) {
            canvas.scale(48f / icon.getWidth(), 48f / icon.getHeight());
            icon.draw(canvas);
        } else {
            Drawable drawable = icon.getDrawable();
            android.graphics.Rect bounds = drawable.copyBounds();
            try {
                drawable.setBounds(0, 0, 48, 48);
                drawable.draw(canvas);
            } finally { drawable.setBounds(bounds); }
        }
    }

    static String watchIcon(ImageView icon) { return watchIcon(icon, 0, false); }

    static String watchIcon(ImageView icon, int matchedDrawable, boolean hd) {
        return controlIcon(icon, matchedDrawable, hd ? WatchIconCodec.Profile.HD : WatchIconCodec.Profile.LEGACY);
    }

    static String controlIcon(ImageView icon, int matchedDrawable, WatchIconCodec.Profile profile) {
        if (icon == null) return "";
        Drawable drawable = icon.getDrawable();
        if (drawable == null) return "";
        if (matchedDrawable != 0) {
            String official = WatchIconCodec.encodeResource(icon.getResources(), matchedDrawable, profile);
            if (!official.isEmpty()) return official;
        }
        // Preserve drawable tint/state and aspect ratio. The backing bitmap can have
        // more pixels than its density-adjusted intrinsic size or its widget view.
        int width = drawable.getIntrinsicWidth(), height = drawable.getIntrinsicHeight();
        if (drawable instanceof android.graphics.drawable.BitmapDrawable raster && raster.getBitmap() != null) {
            width = raster.getBitmap().getWidth();
            height = raster.getBitmap().getHeight();
        }
        if (width <= 0 || height <= 0) { width = 96; height = 96; }
        float scale = (float) WatchIconCodec.SOURCE_LIMIT / Math.max(width, height);
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) scale = Math.min(1f, scale);
        else scale = Math.min(scale, Math.max(1f, 96f / Math.max(width, height)));
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        Bitmap source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Rect previous = drawable.copyBounds();
        try {
            drawable.setBounds(0, 0, w, h);
            drawable.draw(new Canvas(source));
            return WatchIconCodec.encode(source, profile);
        } finally { drawable.setBounds(previous); source.recycle(); }
    }

    private static String iconHash(ImageView icon) {
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        try {
            drawIcon(icon, bitmap);
            ByteBuffer bytes = ByteBuffer.allocate(bitmap.getByteCount());
            bitmap.copyPixelsToBuffer(bytes);
            return android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(bytes.array()), android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        } finally { bitmap.recycle(); }
    }
}
