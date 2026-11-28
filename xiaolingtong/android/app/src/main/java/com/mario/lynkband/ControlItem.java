package com.mario.lynkband;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.widget.ImageView;
import com.mario.lynkband.transport.ControlCategory;
import java.util.Arrays;

/** State and action are intentionally separate: a green, open window offers Close. */
record ControlItem(ControlCategory category, String state, String action, int matchedDrawable) {
    private ControlItem(ControlCategory category, String state, String action) { this(category, state, action, 0); }
    private record Match(String direction, int drawable) {}
    private static final String[] ICON_SUFFIXES = {"on", "on2", "ons", "on3", "off", "off2", "offs", "off3"};
    private record Reference(Resources resources, android.content.res.Configuration config, int id) {}
    private static final class ReferenceIcons {
        static final LruCache<Reference, int[]> cache = new LruCache<>(48);
    }
    String label() {
        if (category == ControlCategory.OTHER) return "选择按钮类型";
        if (state.equals("momentary")) return category.title;
        return category.title + "." + switch (state) {
            case "open" -> "开";
            case "closed" -> "关";
            case "loading" -> "加载";
            default -> "未知";
        };
    }

    org.json.JSONObject wireAction(CardContent.Slot slot, boolean withState, boolean permitted) throws org.json.JSONException {
        org.json.JSONObject item = new org.json.JSONObject().put("slot", slot.id()).put("key", slot.signature())
                .put("label", action.isEmpty() ? label() : action);
        if (withState) {
            item.put("category", category.name()).put("displayLabel", label())
                    .put("controlName", category == ControlCategory.OTHER ? "待选择" : category.title)
                    .put("enabled", permitted && slot.target() != null && !action.isEmpty());
            // Previous watch and band versions already support action-only captions.
            if (state.equals("momentary")) item.put("momentary", true);
            else item.put("controlState", state);
        }
        return item;
    }

    static ControlItem resolve(ControlCategory category, CardContent.Slot slot) {
        if (!slot.stateVisible()) return new ControlItem(category, "unknown", "");
        if (slot.loading()) return new ControlItem(category, "loading", "");
        if (category == ControlCategory.OTHER || slot.icon() == null)
            return new ControlItem(category, "unknown", "");
        if (category == ControlCategory.FIND) {
            ImageView icon = slot.icon();
            if (icon.getDrawable() == null) return new ControlItem(category, "unknown", "");
            int[] actual = pixels(icon.getDrawable());
            for (String name : new String[]{"ic_ck_xc", "ic_ck_xc2", "ic_ck_xcs"}) {
                int id = icon.getResources().getIdentifier(name, "mipmap", LynkWidget.OWNER);
                if (id != 0 && matches(icon.getResources(), id, actual)) {
                    return new ControlItem(category, "momentary", slot.target() == null ? "" : category.title, id);
                }
            }
            if (category.title.equals(CardContent.describedAction(slot)))
                return new ControlItem(category, "momentary", slot.target() == null ? "" : category.title);
            return new ControlItem(category, "unknown", "");
        }
        String[] pair = switch (category) {
            case WINDOW -> new String[]{"cc", "开车窗", "关车窗"};
            case CLIMATE -> new String[]{"kt", "开空调", "关空调"};
            case LOCK -> new String[]{"js", "解锁", "上锁"};
            case TRUNK -> new String[]{"hbx", "开后备箱", "关后备箱"};
            case SUNROOF -> new String[]{"tc", "开天窗", "关天窗"};
            case ENGINE -> new String[]{"yq", "启动引擎", "关闭引擎"};
            default -> null;
        };
        if (pair == null) return new ControlItem(category, "unknown", "");
        Match match = recognizedIcon(slot.icon(), pair[0]);
        String direction = match.direction();
        if (direction.isEmpty() && slot.target() != null) {
            String action = CardContent.describedAction(slot);
            if (action.equals(pair[1])) direction = "closed";
            if (action.equals(pair[2])) direction = "open";
        }
        return switch (direction) {
            case "open" -> new ControlItem(category, "open", slot.target() == null ? "" : pair[2], match.drawable());
            case "closed" -> new ControlItem(category, "closed", slot.target() == null ? "" : pair[1], match.drawable());
            default -> new ControlItem(category, "unknown", "");
        };
    }

    private static Match recognizedIcon(ImageView icon, String prefix) {
        Resources res = icon.getResources();
        int[] actual = pixels(icon.getDrawable());
        String result = "";
        int matchedId = 0;
        for (String suffix : ICON_SUFFIXES) {
            int id = res.getIdentifier("ic_ck_" + prefix + "_" + suffix, "mipmap", LynkWidget.OWNER);
            if (id == 0) continue;
            if (matches(res, id, actual)) {
                String matched = suffix.startsWith("on") ? "open" : "closed";
                if (!result.isEmpty() && !result.equals(matched)) return new Match("", 0);
                matchedId = result.isEmpty() ? id : 0;
                result = matched;
            }
        }
        return new Match(result, matchedId);
    }

    private static boolean matches(Resources res, int id, int[] actual) {
        Reference key = new Reference(res, new android.content.res.Configuration(res.getConfiguration()), id);
        int[] reference = ReferenceIcons.cache.get(key);
        if (reference == null) {
            reference = pixels(res.getDrawable(id, null));
            ReferenceIcons.cache.put(key, reference);
        }
        return Arrays.equals(actual, reference);
    }

    private static int[] pixels(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Rect bounds = drawable.copyBounds();
        try {
            drawable.setBounds(0, 0, 48, 48);
            drawable.draw(new Canvas(bitmap));
            int[] pixels = new int[48 * 48];
            bitmap.getPixels(pixels, 0, 48, 0, 0, 48, 48);
            return pixels;
        } finally {
            drawable.setBounds(bounds);
            bitmap.recycle();
        }
    }
}
