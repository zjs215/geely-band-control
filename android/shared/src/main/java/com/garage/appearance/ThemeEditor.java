package com.garage.appearance;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static com.garage.appearance.ThemeConfig.Mode;
import static com.garage.appearance.ThemeConfig.Scene;
import static com.garage.appearance.ThemeConfig.Variant;

public final class ThemeEditor {
    private final Activity activity;
    private final Context context;
    private final boolean wearable;
    private final boolean syncToWearables;
    private final String[] modes;
    private final ThemeDialogs phoneDialogs;
    private final ThemeConfig initial;
    private Scene scene;
    private Mode mode;
    private Variant variant;
    private int day;
    private int night;
    private boolean followPhone;
    private boolean showTimes;
    private Button sceneButton;
    private Button modeButton;
    private Button dayButton;
    private Button nightButton;
    private Button timesButton;
    private Preview dayPreview;
    private Preview nightPreview;

    private record Preview(FrameLayout frame, ImageView image, TextView label) {}

    private ThemeEditor(Activity activity, ThemeStyles styles, ThemeConfig initial, boolean wearable,
                        boolean followPhone, boolean syncToWearables) {
        this.activity = activity;
        this.phoneDialogs = wearable ? null : new ThemeDialogs(activity, styles);
        this.context = phoneDialogs == null ? styles.dialogContext(activity) : phoneDialogs.context();
        this.wearable = wearable;
        this.syncToWearables = syncToWearables;
        modes = new String[]{"自动日夜", syncToWearables ? ThemeConfig.PHONE_SYSTEM_LABEL : ThemeConfig.SYSTEM_LABEL,
                "固定日景", "固定夜景"};
        this.initial = initial;
        this.followPhone = followPhone;
        scene = initial.scene();
        mode = initial.mode();
        variant = initial.variant();
        day = initial.dayStartMinute();
        night = initial.nightStartMinute();
    }

    public static void showOnWatch(Activity activity, ThemeStyles styles, ThemeConfig initial, boolean followPhone,
                                   BiConsumer<ThemeConfig, Boolean> save) {
        new ThemeEditor(activity, styles, initial, true, followPhone, false).open("外观主题", save);
    }

    public static void showOnPhone(Activity activity, ThemeStyles styles, ThemeConfig initial,
                                   String title, boolean syncToWearables, Consumer<ThemeConfig> save) {
        new ThemeEditor(activity, styles, initial, false, false, syncToWearables)
                .open(title, (choice, ignored) -> save.accept(choice));
    }

    private void open(String title, BiConsumer<ThemeConfig, Boolean> save) {
        ScrollView scroll = new ScrollView(context);
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(wearable ? 8 : 20), dp(4), dp(wearable ? 8 : 20), dp(8));
        scroll.addView(fields);
        if (syncToWearables) {
            LinearLayout previews = new LinearLayout(context);
            dayPreview = addPreview(previews, 0);
            nightPreview = addPreview(previews, 8);
            fields.addView(previews);
        }
        sceneButton = addButton(fields, this::chooseScene);
        modeButton = addButton(fields, this::chooseMode);
        timesButton = addButton(fields, () -> {
            showTimes = !showTimes;
            updateFields();
        });
        dayButton = addButton(fields, () -> pickTime("日景开始", day, value -> { day = value; updateFields(); }));
        nightButton = addButton(fields, () -> pickTime("夜景开始", night, value -> { night = value; updateFields(); }));
        updateFields();

        AlertDialog dialog = builder().setTitle(title).setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            ThemeConfig value = draft();
            if (value == null) {
                Toast.makeText(activity, "日景和夜景开始时间需不同", Toast.LENGTH_SHORT).show();
                return;
            }
            save.accept(value, followPhone);
            dialog.dismiss();
        });
    }

    private void chooseScene() {
        String[] titles = Arrays.stream(Scene.values()).map(item -> item.title).toArray(String[]::new);
        if (!wearable) {
            choose("场景", titles, scene.ordinal(), index -> { scene = Scene.values()[index]; updateFields(); });
            return;
        }
        String[] choices = new String[titles.length + 1];
        choices[0] = "跟随手机";
        System.arraycopy(titles, 0, choices, 1, titles.length);
        choose("主题", choices, followPhone ? 0 : scene.ordinal() + 1, index -> {
            followPhone = index == 0;
            if (!followPhone) scene = Scene.values()[index - 1];
            showTimes = false;
            updateFields();
        });
    }

    private void chooseMode() {
        choose("切换方式", modes, modeIndex(), index -> {
            mode = switch (index) {
                case 0 -> Mode.SCHEDULE;
                case 1 -> Mode.SYSTEM;
                default -> Mode.FIXED;
            };
            if (mode == Mode.FIXED) variant = Variant.values()[index - 2];
            showTimes = false;
            updateFields();
        });
    }

    private void updateFields() {
        sceneButton.setText(followPhone ? "跟随手机" : scene.title);
        modeButton.setText(modes[modeIndex()]);
        dayButton.setText("日景 " + ThemeConfig.timeLabel(day));
        nightButton.setText("夜景 " + ThemeConfig.timeLabel(night));
        boolean showOptions = !followPhone && scene != Scene.CLASSIC;
        show(modeButton, showOptions);
        boolean scheduled = showOptions && mode == Mode.SCHEDULE;
        timesButton.setText(showTimes ? "收起时间设置"
                : "自定义时间 · " + ThemeConfig.timeLabel(day) + " / " + ThemeConfig.timeLabel(night));
        show(timesButton, scheduled);
        show(dayButton, scheduled && showTimes);
        show(nightButton, scheduled && showTimes);
        if (syncToWearables) updatePreviews();
    }

    private ThemeConfig draft() {
        if (followPhone) return initial;
        if (scene == Scene.CLASSIC) return ThemeConfig.CLASSIC;
        if (mode == Mode.SCHEDULE && day == night) return null;
        return new ThemeConfig(scene, mode, variant, day, night);
    }

    private Preview addPreview(LinearLayout row, int margin) {
        FrameLayout frame = new FrameLayout(context);
        frame.setClipToOutline(true);
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));

        TextView label = new TextView(context);
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setTextColor(Color.WHITE);
        label.setPadding(dp(10), dp(9), dp(10), dp(8));
        label.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x000d1626, 0xe60d1626}));
        frame.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(0, dp(112), 1);
        bounds.setMarginStart(dp(margin));
        row.addView(frame, bounds);
        return new Preview(frame, image, label);
    }

    private void updatePreviews() {
        ThemeConfig config = draft();
        Variant active = config == null ? null
                : config.activeVariant(ZonedDateTime.now(), ThemeStyles.systemVariant(context));
        // An invalid schedule still has two valid images to preview.
        if (config == null) config = new ThemeConfig(scene, Mode.FIXED, variant, day, night);
        show(nightPreview.frame(), scene != Scene.CLASSIC);
        renderPreview(dayPreview, config, Variant.DAY, active);
        if (scene != Scene.CLASSIC) renderPreview(nightPreview, config, Variant.NIGHT, active);
        else ThemeArtwork.show(nightPreview.image(), "", 0);
    }

    private void renderPreview(Preview preview, ThemeConfig config, Variant variant, Variant active) {
        boolean classic = config.scene() == Scene.CLASSIC;
        ThemeStyles.Palette palette = ThemeStyles.forVariant(variant);
        GradientDrawable background = new GradientDrawable();
        background.setColor(classic ? 0xff141f30 : palette.background());
        background.setCornerRadius(dp(12));
        preview.frame().setBackground(background);
        ThemeArtwork.show(preview.image(), config.wearableAsset(variant), 0);
        preview.label().setText(classic ? "经典预览" : variant.title + (variant == active ? " · 当前效果" : ""));
    }

    private int modeIndex() {
        return switch (mode) {
            case SCHEDULE -> 0;
            case SYSTEM -> 1;
            case FIXED -> variant.ordinal() + 2;
        };
    }

    private void choose(String title, String[] choices, int current, IntConsumer select) {
        builder().setTitle(title).setSingleChoiceItems(choices, current, (dialog, index) -> {
            select.accept(index);
            dialog.dismiss();
        }).setNegativeButton("返回", null).show();
    }

    private void pickTime(String title, int value, IntConsumer select) {
        if (!wearable) {
            TimePickerDialog picker = new TimePickerDialog(context,
                    (view, hour, minute) -> select.accept(hour * 60 + minute), value / 60, value % 60, true);
            picker.setTitle(title);
            picker.setCustomTitle(phoneDialogs.heading(picker.getContext(), title));
            picker.show();
            picker.getButton(AlertDialog.BUTTON_NEGATIVE).setText("取消");
            picker.getButton(AlertDialog.BUTTON_POSITIVE).setText("确定");
            phoneDialogs.decorate(picker);
            return;
        }
        NumberPicker hour = numberPicker(23, value / 60, "小时");
        NumberPicker minute = numberPicker(59, value % 60, "分钟");
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER);
        row.addView(hour, new LinearLayout.LayoutParams(dp(56), dp(105)));
        row.addView(minute, new LinearLayout.LayoutParams(dp(56), dp(105)));
        builder().setTitle(title).setView(row).setNegativeButton("返回", null)
                .setPositiveButton("确定", (dialog, which) -> select.accept(hour.getValue() * 60 + minute.getValue())).show();
    }

    private NumberPicker numberPicker(int max, int value, String label) {
        NumberPicker picker = new NumberPicker(context);
        picker.setMinValue(0);
        picker.setMaxValue(max);
        picker.setFormatter(number -> String.format(Locale.ROOT, "%02d", number));
        picker.setValue(value);
        picker.setWrapSelectorWheel(true);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        picker.setContentDescription(label);
        return picker;
    }

    private Button addButton(LinearLayout fields, Runnable click) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        button.setOnClickListener(view -> click.run());
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2);
        if (phoneDialogs != null) {
            phoneDialogs.styleField(button);
            bounds.topMargin = dp(7);
        }
        fields.addView(button, bounds);
        return button;
    }

    private AlertDialog.Builder builder() {
        return phoneDialogs == null ? new AlertDialog.Builder(context) : phoneDialogs.builder();
    }

    private static void show(View view, boolean shown) {
        view.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
