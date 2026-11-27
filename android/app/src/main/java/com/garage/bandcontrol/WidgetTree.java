package com.garage.bandcontrol;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class WidgetTree {
    static final String LOCKED = "locked";
    static final String UNLOCKED = "unlocked";
    record Button(View view, String slot, String fingerprint, Rect bounds, String label, String role,
                  WidgetIcons.Match match, LynkWidgetIcons.Kind lynkIcon, View iconView) {
        Button(View view, String slot, String fingerprint, Rect bounds, String label, String role,
               WidgetIcons.Match match, LynkWidgetIcons.Kind lynkIcon) {
            this(view, slot, fingerprint, bounds, label, role, match, lynkIcon, null);
        }
    }
    record OfficialConfirmation(View accept, View cancel, String title) {}

    final BrandConfig config;
    private final LynkWidgetIcons lynkIcons;
    WidgetTree(BrandConfig config) {
        this.config = config;
        lynkIcons = config == BrandConfig.LYNKCO ? new LynkWidgetIcons() : null;
    }

    VehicleStatus read(View root, long now, ZoneId zone) {
        if (loading(root) || showsZeekrGuest(root)) return VehicleStatus.EMPTY;
        View mileage = named(root, config.mileage);
        View alarm = named(root, config.alarm);
        View motion = named(alarm, config.motion);
        if (motion == null) motion = unique(alarm, view -> view instanceof TextView);
        return WidgetReadings.parse(config, distance(mileage, config.mileageValue, config.mileageUnit),
                energy(root, config.electric, config.electricBar),
                energy(root, config.oil, config.oilBar),
                text(motion), text(named(root, config.time)), now, zone);
    }

    boolean loading(View root) {
        return config.loading.stream().anyMatch(name -> named(root, name) != null);
    }

    boolean slotLoading(View root, String slotName) {
        return slotLoading(named(root, slotName));
    }

    boolean slotVisible(View root, String slotName) {
        return named(root, slotName) != null;
    }

    private boolean slotLoading(View slot) {
        BrandConfig.Slot model = slot == null ? null : config.slot(resource(slot));
        return model != null && named(slot, model.progress()) != null;
    }

    private WidgetReadings.Energy energy(View root, String containerName, String barName) {
        // RemoteViews can replace the included progress bar inside the energy container.
        View container = named(root, containerName);
        if (container == null) container = named(root, barName);
        Map<Integer, WidgetReadings.Distance> distances = new LinkedHashMap<>();
        Map<Integer, Integer> percents = new LinkedHashMap<>();
        collectEnergyReadings(container, distances, percents);
        WidgetReadings.Distance distance = distances.size() == 1 ? distances.values().iterator().next()
                : new WidgetReadings.Distance("", "");
        View visibleBar = unique(container, view -> view instanceof ProgressBar && config.energyBars.contains(resource(view)));
        Integer barPercent = progressPercent(visibleBar);
        Integer textPercent = percents.size() == 1 ? percents.values().iterator().next() : null;
        Integer percent = config == BrandConfig.GEELY && textPercent != null ? textPercent
                : barPercent != null ? barPercent : textPercent;
        return new WidgetReadings.Energy(distance, percent);
    }

    private Integer progressPercent(View view) {
        if (!(view instanceof ProgressBar bar) || bar.isIndeterminate()) return null;
        int minimum = bar.getMin();
        int maximum = bar.getMax();
        int progress = bar.getProgress();
        if (maximum <= minimum || progress < minimum || progress > maximum) return null;
        return (int) Math.round(100.0 * (progress - (long) minimum) / (maximum - (long) minimum));
    }

    private void collectEnergyReadings(
            View view,
            Map<Integer, WidgetReadings.Distance> distances,
            Map<Integer, Integer> percents) {
        if (view == null || view.getVisibility() != View.VISIBLE
                || (distances.size() > 1 && percents.size() > 1)) return;
        if (view instanceof TextView) {
            String unit = "";
            if (java.util.Objects.equals(config.energyValue, resource(view)) && view.getParent() instanceof ViewGroup row) {
                unit = text(unique(row, other -> other.getParent() == row && java.util.Objects.equals(config.energyUnit, resource(other))));
            }
            WidgetReadings.Distance candidate = new WidgetReadings.Distance(text(view), unit);
            Integer km = candidate.kilometers();
            if (km != null) distances.putIfAbsent(km, candidate);
            Integer pct = candidate.percent();
            if (pct != null) percents.putIfAbsent(pct, pct);
        } else if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectEnergyReadings(group.getChildAt(i), distances, percents);
            }
        }
    }

    private WidgetReadings.Distance distance(View container, String number, String unit) {
        return new WidgetReadings.Distance(text(named(container, number)), text(named(container, unit)));
    }

    List<Button> buttons(ViewGroup root, String providerVersion, boolean controlsReady, WidgetIcons icons) {
        List<Button> buttons = new ArrayList<>();
        if (confirmationVisible(root) || showsZeekrGuest(root)) return buttons;

        View settings = config == BrandConfig.ZEEKR
                ? unique(root, view -> (config.settings.equals(resource(view)) || "iv_widget_setting".equals(resource(view)))
                        && clickable(view, root))
                : named(root, config.settings);
        if (clickable(settings, root)) {
            buttons.add(new Button(settings, config.settings, "", bounds(root, settings), "配置", config.settings,
                    null, null));
        }
        View refresh = loading(root) ? null : unique(root,
                view -> config.refreshViews.contains(resource(view)) && clickable(view, root));
        if (refresh != null) {
            buttons.add(new Button(refresh, config.refresh, "", bounds(root, refresh), "刷新车况", config.refresh,
                    null, null));
        }

        if (!controlsReady && config.slotMode == BrandConfig.SlotMode.DYNAMIC) return buttons;
        int lockIcons = 0;
        for (BrandConfig.Slot model : config.slotModels) {
            String slotName = model.container();
            View slot = named(root, slotName);
            if (slot == null || slotLoading(slot)) continue;
            View icon = named(slot, model.icon());
            if (!(icon instanceof ImageView image) || image.getDrawable() == null
                    || icon.getWidth() <= 0 || icon.getHeight() <= 0) continue;
            try {
                WidgetIcons.Match match = icons.identify(image, providerVersion);
                if (match != null && match.action() == CarAction.LOCK) lockIcons++;
                View click = unique(slot, view -> clickable(view, root));
                if (click == null) continue;
                String label = description(click);
                if (label.isEmpty()) label = description(icon);
                String identity = providerVersion + "|" + slotName + "|" + resource(click) + "|" + label;
                LynkWidgetIcons.Kind lynkIcon = lynkIcons == null || loading(root)
                        ? null : lynkIcons.identify(image, label, providerVersion);
                buttons.add(new Button(click, slotName, fingerprint(icon, identity), bounds(root, click), label, "",
                        match, lynkIcon, icon));
            } catch (RuntimeException unsupportedDrawable) {
                // A failed icon render leaves this slot unavailable until its identity can be checked.
            }
        }
        // The unconfigured template uses the closed-lock image in every slot.
        if (lockIcons > 1) {
            buttons.removeIf(this::recognizedLock);
        }
        return buttons;
    }

    private boolean recognizedLock(Button button) {
        return button.match() != null && button.match().action() == CarAction.LOCK;
    }

    boolean confirmationVisible(View root) {
        return named(root, config.confirm) != null;
    }

    boolean showsZeekrGuest(View root) {
        return config == BrandConfig.ZEEKR
                && (named(root, "fl_visitor") != null || named(root, config.mileage) == null);
    }


    ImageView vehicleImage(ViewGroup root) {
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        if (config == BrandConfig.ZEEKR) {
            View car = named(root, "iv_vehicle_image");
            return car instanceof ImageView image && image.getDrawable() != null ? image : null;
        }
        if (config == BrandConfig.LYNKCO) {
            View car = named(root, "car_img");
            return car instanceof ImageView image && image.getDrawable() != null ? image : null;
        }
        List<ImageView> candidates = new ArrayList<>();
        collectVehicleImages(root, candidates);
        ImageView best = null;
        int bestScore = -1;
        for (ImageView candidate : candidates) {
            int w = candidate.getWidth();
            int h = candidate.getHeight();
            if (w < 48 || h < 24) continue;
            if (w >= root.getWidth() * 0.92f && h >= root.getHeight() * 0.85f) continue;
            if (w <= h * 1.15f && w < root.getWidth() * 0.25f) continue;
            String name = resource(candidate).toLowerCase(java.util.Locale.ROOT);
            int bonus = (name.contains("car") || name.contains("vehicle") || name.contains("model")) ? 100_000 : 0;
            int score = bonus + w * h;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private void collectVehicleImages(View view, List<ImageView> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        String entry = resource(view);
        if (config.slots.contains(entry) || java.util.Arrays.asList(config.electric, config.oil, config.alarm, config.mileage, config.confirm, config.settings, config.refresh).contains(entry)) return;
        if (view instanceof ImageView image) {
            if (image.getDrawable() != null && image.getWidth() > 0 && image.getHeight() > 0) {
                out.add(image);
            }
        } else if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collectVehicleImages(group.getChildAt(i), out);
        }
    }

    OfficialConfirmation confirmation(ViewGroup root) {
        View panel = named(root, config.confirm);
        View accept = named(panel, config.accept);
        View cancel = named(panel, config.cancel);
        String title = text(named(panel, config.confirmTitle));
        if (title.isEmpty() || !clickable(accept, root) || !clickable(cancel, root)) return null;
        return new OfficialConfirmation(accept, cancel, title);
    }

    private boolean clickable(View view, View root) {
        if (view == null || !view.isClickable() || !view.hasOnClickListeners()) return false;
        for (View current = view; current != null; ) {
            if (!current.isEnabled() || current.getVisibility() != View.VISIBLE
                    || current.getWidth() <= 0 || current.getHeight() <= 0) return false;
            if (current == root) return true;
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return false;
    }

    private String description(View view) {
        CharSequence label = view.getContentDescription();
        return label == null ? "" : label.toString().trim();
    }

    private String text(View view) {
        return view instanceof TextView text ? text.getText().toString().trim() : "";
    }

    private View named(View root, String name) {
        return name == null ? null : unique(root, view -> name.equals(resource(view)));
    }

    private View unique(View root, Predicate<View> matches) {
        List<View> found = new ArrayList<>(2);
        collect(root, matches, found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private void collect(View view, Predicate<View> matches, List<View> found) {
        if (view == null || view.getVisibility() != View.VISIBLE || found.size() >= 2) return;
        if (matches.test(view)) found.add(view);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), matches, found);
        }
    }

    private Rect bounds(ViewGroup root, View view) {
        Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight());
        root.offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }

    private String resource(View view) {
        if (view.getId() == View.NO_ID) return "";
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Resources.NotFoundException invalid) {
            return "";
        }
    }

    private String fingerprint(View view, String descriptor) {
        Bitmap image = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(image);
            canvas.scale(48f / view.getWidth(), 48f / view.getHeight());
            view.draw(canvas);
            ByteBuffer pixels = ByteBuffer.allocate(image.getByteCount());
            image.copyPixelsToBuffer(pixels);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(descriptor.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(pixels.array()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        } finally {
            image.recycle();
        }
    }
}
