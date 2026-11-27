package com.otrong.carcontrol.phone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Runs the same scanner against a fixture and, when installed, the official 4.3.1 layout. */
@SuppressLint("DiscouragedApi")
public final class LynkCardInstrumentation extends Instrumentation {
    private static final BrandConfig BRAND = BrandConfig.LYNKCO;
    private static final String UNLOCK = "fr_unlock";
    private static final String WINDOW = "fr_window";
    private final WidgetTree tree = new WidgetTree(BRAND);
    private final WidgetIcons icons = new WidgetIcons(BRAND);
    private boolean requireOfficial;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        requireOfficial = arguments != null && "true".equals(arguments.getString("requireOfficial"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        FutureTask<String> check = new FutureTask<>(this::exerciseCards);
        runOnMainSync(check);
        try {
            result.putString("stream", "\n" + check.get() + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            result.putString("stream", "\nFAIL: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } catch (ExecutionException failure) {
            result.putString("stream", "\nFAIL: " + failure.getCause() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String exerciseCards() throws Exception {
        Context context = getTargetContext();
        Context test = getContext();
        Resources resources = test.getResources();
        String owner = test.getPackageName();
        scan(fixture(test, resources, owner), resources, owner, "fixture");

        PackageInfo pkg;
        try {
            pkg = context.getPackageManager().getPackageInfo(BRAND.packageName, 0);
        } catch (PackageManager.NameNotFoundException missing) {
            if (requireOfficial) throw new AssertionError("Install official Lynk 4.3.1 before this test.");
            return "PASS fixture. SKIP official Lynk 4.3.1 (provider absent).";
        }
        if (!"4.3.1".equals(pkg.versionName)) {
            if (requireOfficial) throw new AssertionError("Expected Lynk 4.3.1, found " + pkg.versionName);
            return "PASS fixture. SKIP official Lynk 4.3.1 (found " + pkg.versionName + ").";
        }
        resources = context.getPackageManager().getResourcesForApplication(BRAND.packageName);
        ViewGroup real = (ViewGroup) new RemoteViews(BRAND.packageName,
                id(resources, BRAND.packageName, BRAND.normalLayout, "layout")).apply(context, new FrameLayout(context));
        show(real, resources, BRAND.packageName, "rl_widget_state", false);
        show(real, resources, BRAND.packageName, "rl_widget_cal_state", true);
        show(real, resources, BRAND.packageName, "ll_loading_auth", false);
        show(real, resources, BRAND.packageName, "fr_sx", false);
        scan(real, resources, BRAND.packageName, pkg.getLongVersionCode() + ":test");
        return "PASS fixture and installed official Lynk 4.3.1 resources.";
    }

    private void scan(ViewGroup root, Resources resources, String owner, String version) {
        show(root, resources, owner, BRAND.confirm, false);
        ImageView first = enableSlot(root, resources, owner, UNLOCK, "打开后备箱", Color.WHITE);
        ImageView second = enableSlot(root, resources, owner, WINDOW, "关闭车窗", Color.GRAY);
        layout(root);
        WidgetTree.Button original = button(root, version, UNLOCK);
        expect(original != null && original.lynkIcon() == LynkWidgetIcons.Kind.TRUNK, "opening state");
        expect(state(root, UNLOCK, original) == LynkWidgets.SlotState.READY, "opening is ready");

        first.setContentDescription("关闭后备箱");
        WidgetTree.Button toggled = button(root, version, UNLOCK);
        expect(toggled != null && !original.fingerprint().equals(toggled.fingerprint()), "closing changes target identity");
        expect(state(root, UNLOCK, toggled) == LynkWidgets.SlotState.READY, "closing is ready");

        first.setVisibility(View.GONE);
        show(root, resources, owner, BRAND.slot(UNLOCK).progress(), true);
        expect(button(root, version, UNLOCK) == null, "loading has no click target");
        expect(state(root, UNLOCK, null) == LynkWidgets.SlotState.LOADING, "visible slot is loading");
        expect(button(root, version, WINDOW) != null, "other slot remains available");
        show(root, resources, owner, BRAND.slot(UNLOCK).progress(), false);
        expect(tree.slotVisible(root, UNLOCK), "slot container keeps its position");
        expect(state(root, UNLOCK, null) == LynkWidgets.SlotState.UNKNOWN, "missing icon yields unknown state");
        first.setVisibility(View.VISIBLE);
        first.setContentDescription("不可识别");
        layout(root);
        expect(state(root, UNLOCK, button(root, version, UNLOCK)) == LynkWidgets.SlotState.UNKNOWN,
                "unrecognizable button remains disabled");

        first.setContentDescription("关闭车窗");
        second.setContentDescription("打开后备箱");
        WidgetTree.Button moved = button(root, version, WINDOW);
        expect(moved != null && moved.lynkIcon() == LynkWidgetIcons.Kind.TRUNK, "label follows moved icon");
        expect(!original.fingerprint().equals(moved.fingerprint()), "old target identity cannot follow the move");

        first.setContentDescription(null);
        String previous = "";
        for (String suffix : List.of("off", "on", "off2", "on2")) {
            int glyph = resources.getIdentifier("ic_ck_cc_" + suffix, "mipmap", owner);
            if (glyph == 0) continue;
            first.setImageDrawable(resources.getDrawable(glyph, null));
            layout(root);
            WidgetTree.Button official = button(root, version, UNLOCK);
            expect(official != null && official.lynkIcon() == LynkWidgetIcons.Kind.WINDOW,
                    "official window glyph " + suffix);
            expect(!official.fingerprint().equals(previous), "official state changes identity");
            previous = official.fingerprint();
        }

        show(root, resources, owner, BRAND.confirm, true);
        ((TextView) view(root, resources, owner, BRAND.confirmTitle)).setText("是否确认操作");
        view(root, resources, owner, BRAND.accept).setOnClickListener(v -> {});
        view(root, resources, owner, BRAND.cancel).setOnClickListener(v -> {});
        layout(root);
        expect(tree.confirmation(root) != null, "official confirmation is recognized");
        expect(button(root, version, UNLOCK) == null, "ordinary buttons hidden by confirmation");
        expect(state(root, UNLOCK, null) == LynkWidgets.SlotState.CONFIRMING, "confirmation pauses the control");
    }

    private WidgetTree.Button button(ViewGroup root, String version, String slot) {
        return tree.buttons(root, version, true, icons).stream()
                .filter(candidate -> candidate.slot().equals(slot)).findFirst().orElse(null);
    }

    private LynkWidgets.SlotState state(View root, String slot, WidgetTree.Button button) {
        return LynkWidgets.slotState(tree, root, slot, button, false);
    }

    private static ViewGroup fixture(Context context, Resources resources, String owner) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        for (String name : List.of(UNLOCK, WINDOW)) {
            BrandConfig.Slot slot = BRAND.slot(name);
            FrameLayout container = new FrameLayout(context);
            add(root, container, resources, owner, name);
            add(container, new ImageView(context), resources, owner, slot.icon());
            add(container, new View(context), resources, owner, slot.progress());
        }
        LinearLayout confirmation = new LinearLayout(context);
        confirmation.setOrientation(LinearLayout.VERTICAL);
        add(root, confirmation, resources, owner, BRAND.confirm);
        for (String name : List.of(BRAND.confirmTitle, BRAND.accept, BRAND.cancel)) {
            add(confirmation, new TextView(context), resources, owner, name);
        }
        return root;
    }

    private static void add(ViewGroup parent, View child, Resources resources, String owner, String name) {
        child.setId(id(resources, owner, name, "id"));
        parent.addView(child, new ViewGroup.LayoutParams(-1, 52));
    }

    private static ImageView enableSlot(View root, Resources resources, String owner, String name, String label, int color) {
        BrandConfig.Slot slot = BRAND.slot(name);
        View container = view(root, resources, owner, name);
        container.setVisibility(View.VISIBLE);
        container.setOnClickListener(null);
        container.setClickable(false);
        show(root, resources, owner, slot.progress(), false);
        ImageView image = (ImageView) view(root, resources, owner, slot.icon());
        image.setVisibility(View.VISIBLE);
        image.setImageDrawable(new ColorDrawable(color));
        image.setContentDescription(label);
        image.setOnClickListener(v -> {});
        return image;
    }

    private static int id(Resources resources, String owner, String name, String type) {
        int resource = resources.getIdentifier(name, type, owner);
        expect(resource != 0, "missing " + type + " " + name);
        return resource;
    }

    private static View view(View root, Resources resources, String owner, String name) {
        View view = root.findViewById(id(resources, owner, name, "id"));
        expect(view != null, "missing view " + name);
        return view;
    }

    private static void show(View root, Resources resources, String owner, String name, boolean visible) {
        view(root, resources, owner, name).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 800, 500);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
