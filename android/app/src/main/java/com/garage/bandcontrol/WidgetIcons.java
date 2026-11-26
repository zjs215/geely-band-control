package com.garage.bandcontrol;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class WidgetIcons {
    record Match(CarAction action, String lockState, ControlDirection direction) {
        Match(CarAction action, String lockState) {
            this(action, lockState, null);
        }
    }

    private record Icon(Bitmap bitmap, Match match) {}
    private record Catalog(String version, int density, List<Icon> icons) {}

    private final BrandConfig config;
    WidgetIcons(BrandConfig config) { this.config = config; }

    private final Map<Resources, Catalog> catalogs = new WeakHashMap<>();

    Match identify(ImageView image, String version) {
        if (config.iconPrefix == null) return null;
        if (!(image.getDrawable() instanceof BitmapDrawable drawable)) return null;
        Resources resources = image.getResources();
        int density = resources.getDisplayMetrics().densityDpi;
        Catalog catalog = catalogs.get(resources);
        if (catalog == null || !catalog.version().equals(version) || catalog.density() != density) {
            catalog = new Catalog(version, density, load(resources));
            catalogs.put(resources, catalog);
        }
        Bitmap bitmap = drawable.getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return null;
        Match match = null;
        for (Icon icon : catalog.icons()) {
            if (!bitmap.sameAs(icon.bitmap())) continue;
            if (match != null && !match.equals(icon.match())) return null;
            match = icon.match();
        }
        return match;
    }

    private List<Icon> load(Resources resources) {
        List<Icon> icons = new ArrayList<>();
        if (config == BrandConfig.GEELY) {
            add(icons, resources, new Match(CarAction.LOCK, WidgetTree.LOCKED), "black_lock", "white_lock");
            add(icons, resources, new Match(CarAction.LOCK, WidgetTree.UNLOCKED), "black_unlock", "white_unlock");
            add(icons, resources, new Match(CarAction.CLIMATE, "", ControlDirection.OPEN), "black_air", "white_air");
            add(icons, resources, new Match(CarAction.CLIMATE, "", ControlDirection.CLOSE),
                    "black_air_opened", "white_air_opened");
            add(icons, resources, new Match(CarAction.FIND, ""),
                    "black_findcar", "white_findcar", "black_findcar_opened", "white_findcar_opened");
            return icons;
        }
        add(icons, resources, new Match(CarAction.LOCK, WidgetTree.LOCKED),
                "door_lock_off_black", "door_lock_off_white");
        add(icons, resources, new Match(CarAction.LOCK, WidgetTree.UNLOCKED), "door_lock_on");
        addDirectionalVariants(icons, resources, CarAction.CLIMATE, "climate");
        addDirectionalVariants(icons, resources, CarAction.WINDOW_VENT, "ventilate");
        addVariants(icons, resources, CarAction.TRUNK, "trunk");
        add(icons, resources, new Match(CarAction.TRUNK, ""), "trunk_open");
        addVariants(icons, resources, CarAction.FIND, "find_car");
        addDirectionalVariants(icons, resources, CarAction.WINDOW_OPEN, "window");
        addDirectionalVariants(icons, resources, CarAction.HEAT, "fast_heat");
        addDirectionalVariants(icons, resources, CarAction.COOL, "fast_cool");
        addDirectionalVariants(icons, resources, CarAction.PURIFY, "air_clean");
        addDirectionalVariants(icons, resources, CarAction.DEFROST, "defrost");
        addDirectionalVariants(icons, resources, CarAction.SENTRY, "vstd");
        return icons;
    }

    private void addVariants(List<Icon> icons, Resources resources, CarAction action, String name) {
        add(icons, resources, new Match(action, ""), name + "_off_black", name + "_off_white", name + "_on");
    }

    private void addDirectionalVariants(List<Icon> icons, Resources resources, CarAction action, String name) {
        add(icons, resources, new Match(action, "", ControlDirection.OPEN),
                name + "_off_black", name + "_off_white", name + "_off");
        add(icons, resources, new Match(action, "", ControlDirection.CLOSE),
                name + "_on", name + "_on_black", name + "_on_white",
                name + "_open", name + "_opened", name + "_open_black", name + "_open_white");
    }

    @SuppressLint("DiscouragedApi")
    private void add(List<Icon> icons, Resources resources, Match match, String... names) {
        BitmapFactory.Options unscaled = new BitmapFactory.Options();
        unscaled.inScaled = false;
        TypedValue value = new TypedValue();
        for (String name : names) {
            // Resolve the installed provider's resource names instead of using build-specific IDs.
            int id = resources.getIdentifier(config.iconPrefix + name, "mipmap", config.packageName);
            if (id == 0) id = resources.getIdentifier(config.iconPrefix + name, "drawable", config.packageName);
            if (id == 0) continue;
            try {
                if (resources.getDrawable(id, null) instanceof BitmapDrawable drawable
                        && drawable.getBitmap() != null) {
                    icons.add(new Icon(drawable.getBitmap(), match));
                    resources.getValue(id, value, true);
                    if (value.density != TypedValue.DENSITY_NONE
                            && value.density != resources.getDisplayMetrics().densityDpi) {
                        Bitmap raw = BitmapFactory.decodeResource(resources, id, unscaled);
                        if (raw != null) icons.add(new Icon(raw, match));
                    }
                }
            } catch (Resources.NotFoundException unavailable) {
                // A missing variant can still be marked manually for the current card.
            }
        }
    }
}
