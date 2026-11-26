package com.garage.bandcontrol;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Identifies the displayed icon. It does not establish the vehicle's state or the next action. */
final class LynkWidgetIcons {
    enum Kind {
        LOCK("车锁", ""),
        CLOSED_LOCK("车锁", "闭锁图标"),
        OPEN_LOCK("车锁", "开锁图标"),
        WINDOW("车窗", ""),
        WINDOW_VENT("车窗", "微开"),
        WINDOW_OPEN("车窗", "全开"),
        TRUNK("后备箱", ""),
        FIND("寻车", ""),
        ENGINE("发动机", ""),
        SUNROOF("天窗", ""),
        HEAT("快速升温", ""),
        COOL("快速降温", ""),
        CHARGE_PORT("充电口", "");

        final String category;
        final String detail;

        Kind(String category, String detail) {
            this.category = category;
            this.detail = detail;
        }

        String label() { return detail.isEmpty() ? category : category + "（" + detail + "）"; }
    }

    private static final int SIDE = 96;
    private static final String PACKAGE = "com.lynkco.customer";
    private record Icon(BitSet silhouette, Kind kind) {}
    private record Catalog(String version, int density, List<Icon> icons) {}

    private final Map<Resources, Catalog> catalogs = new WeakHashMap<>();

    Kind identify(ImageView image, String description, String providerVersion) {
        return match(fromDescription(description), fromIcon(image, providerVersion));
    }

    private static Kind match(Kind described, Kind drawn) {
        if (described == null) return drawn;
        if (drawn == null) return described;
        if (!described.category.equals(drawn.category)) return null;
        return described.detail.isEmpty() ? drawn : described;
    }

    private static Kind fromDescription(String value) {
        return switch (value) {
            case "车锁", "锁车", "上锁", "解锁", "闭锁", "锁定车辆", "上锁车辆", "解锁车辆", "解锁车门",
                    "上锁车门", "车门已解锁", "车门已上锁", "车门未锁", "车辆已解锁", "车辆已上锁" -> Kind.LOCK;
            case "车窗", "开窗", "打开车窗", "开启车窗", "关窗", "关闭车窗" -> Kind.WINDOW;
            case "车窗微开", "微开车窗" -> Kind.WINDOW_VENT;
            case "车窗全开", "全开车窗" -> Kind.WINDOW_OPEN;
            case "后备箱", "打开后备箱", "开启后备箱", "关闭后备箱",
                    "尾门", "打开尾门", "开启尾门", "关闭尾门", "电动尾门" -> Kind.TRUNK;
            case "寻车" -> Kind.FIND;
            case "发动机", "启动发动机", "关闭发动机" -> Kind.ENGINE;
            case "天窗", "打开天窗", "开启天窗", "关闭天窗" -> Kind.SUNROOF;
            case "快速升温", "极速升温" -> Kind.HEAT;
            case "快速降温", "极速降温" -> Kind.COOL;
            case "充电口", "充电口盖", "打开充电口", "关闭充电口", "打开充电口盖", "关闭充电口盖" -> Kind.CHARGE_PORT;
            default -> null;
        };
    }

    private Kind fromIcon(ImageView image, String providerVersion) {
        Drawable current = image.getDrawable().getCurrent();
        if (!(current instanceof BitmapDrawable drawable)) return null;
        BitSet silhouette = silhouette(drawable.getBitmap());
        if (silhouette == null) return null;

        Resources resources = image.getResources();
        int density = resources.getDisplayMetrics().densityDpi;
        Catalog catalog = catalogs.get(resources);
        if (catalog == null || !catalog.version().equals(providerVersion) || catalog.density() != density) {
            List<Icon> icons = new ArrayList<>();
            add(resources, icons, Kind.CLOSED_LOCK, "ic_ck_js_off", "ic_ck_js_off2", "ic_ck_js_offs", "ic_kjck_js_off");
            add(resources, icons, Kind.OPEN_LOCK, "ic_ck_js_on", "ic_ck_js_on2", "ic_ck_js_ons", "ic_kjck_js_on");
            add(resources, icons, Kind.WINDOW, "ic_ck_cc_off", "ic_ck_cc_off2", "ic_ck_cc_offs",
                    "ic_ck_cc_on", "ic_ck_cc_on2", "ic_ck_cc_ons", "ic_kjck_ccqk_off", "ic_kjck_ccqk_on");
            add(resources, icons, Kind.TRUNK, "ic_ck_hbx_off", "ic_ck_hbx_off2", "ic_ck_hbx_off3",
                    "ic_ck_hbx_on", "ic_ck_hbx_on2", "ic_ck_hbx_on3");
            add(resources, icons, Kind.FIND, "ic_kjck_cx");
            add(resources, icons, Kind.ENGINE, "ic_ck_yq_off", "ic_ck_yq_off2", "ic_ck_yq_offs",
                    "ic_ck_yq_on", "ic_ck_yq_on2", "ic_ck_yq_ons");
            add(resources, icons, Kind.SUNROOF, "ic_ck_tc_off", "ic_ck_tc_off2", "ic_ck_tc_on", "ic_ck_tc_on2");
            add(resources, icons, Kind.HEAT, "ic_hj_jssw_off", "ic_hj_jssw_off2", "ic_hj_jssw_on", "ic_hj_jssw_on2");
            add(resources, icons, Kind.COOL, "ic_hj_jsjw_off", "ic_hj_jsjw_off2", "ic_hj_jsjw_on", "ic_hj_jsjw_on2");
            add(resources, icons, Kind.CHARGE_PORT, "icon_vehicle_widget_charging_port_close", "icon_vehicle_widget_charging_port_close2",
                    "icon_vehicle_widget_charging_port_open", "icon_vehicle_widget_charging_port_open2");
            catalog = new Catalog(providerVersion, density, icons);
            catalogs.put(resources, catalog);
        }
        Kind match = null;
        for (Icon icon : catalog.icons()) {
            if (!silhouette.equals(icon.silhouette())) continue;
            if (match != null && match != icon.kind()) {
                if (!match.category.equals(icon.kind().category)) return null;
                match = Kind.LOCK;
            } else {
                match = icon.kind();
            }
        }
        return match;
    }

    @SuppressLint("DiscouragedApi")
    private static void add(Resources resources, List<Icon> icons, Kind kind, String... names) {
        BitmapFactory.Options unscaled = new BitmapFactory.Options();
        unscaled.inScaled = false;
        for (String name : names) {
            int id = resources.getIdentifier(name, "mipmap", PACKAGE);
            if (id == 0) continue;
            addBitmap(icons, kind, BitmapFactory.decodeResource(resources, id, unscaled));
            addBitmap(icons, kind, BitmapFactory.decodeResource(resources, id));
        }
    }

    private static void addBitmap(List<Icon> icons, Kind kind, Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            BitSet shape = silhouette(bitmap);
            if (shape != null) icons.add(new Icon(shape, kind));
        } finally {
            bitmap.recycle();
        }
    }

    private static BitSet silhouette(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || !bitmap.hasAlpha()) return null;
        if (bitmap.getConfig() == Bitmap.Config.HARDWARE) return null;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true);
        try {
            int[] pixels = new int[SIDE * SIDE];
            scaled.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE);
            BitSet shape = new BitSet(pixels.length);
            for (int i = 0; i < pixels.length; i++) {
                if ((pixels[i] >>> 24) >= 128) shape.set(i);
            }
            return shape.isEmpty() || shape.cardinality() == pixels.length ? null : shape;
        } finally {
            if (scaled != bitmap) scaled.recycle();
        }
    }
}
