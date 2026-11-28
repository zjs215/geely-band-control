package com.mario.lynkband;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mario.lynkband.transport.ControlCategory;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class VoiceSceneSetup {
    private final Activity ui;
    private final WristApp app;
    private final Map<String,Integer> counts = new HashMap<>();
    private final Set<ControlCategory> categories = new LinkedHashSet<>();

    VoiceSceneSetup(Activity ui, WristApp app) { this.ui = ui; this.app = app; }

    void show() {
        Set<String> custom = new LinkedHashSet<>();
        for (CardContent.Slot slot : CardContent.visibleSlots(app.card.content(), app.card.identity())) {
            String label = app.label(slot);
            categories.add(app.selectedItem(slot));
            if (slot.target() == null || !VoiceScenes.concrete(label)) continue;
            String canonical = VoicePreset.canonical(label);
            counts.put(canonical, counts.getOrDefault(canonical, 0) + 1);
            custom.add(canonical);
        }
        LinearLayout list = column();
        ControlCategory group = null;
        for (VoicePreset preset : VoicePreset.values()) {
            if (group != preset.group) { group = preset.group; header(list, group.title); }
            custom.remove(preset.label);
            action(list, preset.label, categories.contains(group));
        }
        if (!custom.isEmpty()) {
            header(list, "其他已标记动作");
            for (String name : custom) action(list, name, true);
        }
        if (app.voiceConfirmation()) {
            header(list, "二次确认");
            row(list, "确认", "15 秒内有效", () -> details("确认", VoiceScenes.CONFIRM, "只执行上一条已准备的动作。"));
            row(list, "取消", "撤销待执行动作", () -> details("取消", VoiceScenes.CANCEL, "撤销尚未执行的语音操作。"));
        }
        new AlertDialog.Builder(ui).setTitle("米家场景预设").setView(scroll(list))
                .setNegativeButton("关闭", null).show();
    }

    private void action(LinearLayout list, String label, boolean present) {
        int matches = counts.getOrDefault(label, 0);
        String state = matches == 1 ? "已匹配卡片" : matches > 1 ? "标记重复" : present ? "需核对动作" : "未匹配卡片";
        String hint = matches == 1 ? "执行前会重新核对这个卡片按钮。"
                : matches > 1 ? "有多个按钮使用同一动作名称，请在手机核对并保留一个对应标记。"
                : "请先在官方卡片选入支持此动作的按钮，再在手机控制项中选择对应的按钮类型。";
        row(list, label, state, () -> details("领克" + label, VoiceScenes.ACTION + label, hint));
    }

    private void details(String name, String command, String hint) {
        LinearLayout body = column();
        add(body, hint, 14, R.color.muted);
        header(body, "场景名称");
        add(body, name, 18, R.color.ink).setTextIsSelectable(true);
        header(body, "手机通知正文");
        TextView code = add(body, command, 17, R.color.link);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        TextView steps = add(body, "米家 → 智能／场景 → ＋ → 手动执行。添加「通知 → 向手机发送通知」，粘贴正文，按上面的场景名称保存。", 15, R.color.ink);
        steps.setPadding(0, dp(18), 0, dp(8));
        new AlertDialog.Builder(ui).setTitle(name).setView(scroll(body)).setNegativeButton("返回", null)
                .setNeutralButton("复制名称", (dialog, which) -> copy(name))
                .setPositiveButton("复制指令并打开米家", (dialog, which) -> {
                    copy(command);
                    Intent intent = ui.getPackageManager().getLaunchIntentForPackage(VoiceScenes.PACKAGE);
                    if (intent != null) {
                        try { ui.startActivity(intent); return; }
                        catch (ActivityNotFoundException ignored) { /* App changed since lookup. */ }
                    }
                    Toast.makeText(ui, "指令已复制，请安装或打开米家", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void copy(String value) {
        ui.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("米家场景",value));
    }
    private void header(LinearLayout parent, String value) {
        TextView heading = add(parent, value, 14, R.color.muted);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0,dp(16),0,dp(4));
    }
    private void row(LinearLayout parent, String label, String status, Runnable onClick) {
        LinearLayout row = new LinearLayout(ui);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        Button action = new Button(ui);
        action.setText(label);
        action.setAllCaps(false);
        action.setTextSize(16);
        action.setTextColor(ui.getColor(R.color.link));
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setMinHeight(dp(48));
        action.setMinWidth(0);
        action.setMaxLines(2);
        action.setEllipsize(TextUtils.TruncateAt.END);
        action.setPadding(dp(14), dp(8), dp(14), dp(8));
        action.setStateListAnimator(null);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(ui.getColor(R.color.control));
        fill.setCornerRadius(dp(12));
        int ripple = (ui.getColor(R.color.link) & 0x00ffffff) | 0x30000000;
        action.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), fill, null));
        action.setContentDescription("配置" + label + "，" + status);
        action.setOnClickListener(view -> onClick.run());
        LinearLayout.LayoutParams buttonLayout = new LinearLayout.LayoutParams(0, -2, 1);
        buttonLayout.setMarginEnd(dp(14));
        row.addView(action, buttonLayout);

        TextView state = add(row, status, 13, R.color.muted);
        state.setMaxWidth(dp(104));
        state.setGravity(Gravity.END);
        state.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(row);
    }
    private TextView add(LinearLayout parent, String label, int size, int color) {
        TextView text = new TextView(ui);
        text.setText(label); text.setTextSize(size); text.setTextColor(ui.getColor(color));
        parent.addView(text);
        return text;
    }
    private LinearLayout column() {
        LinearLayout list = new LinearLayout(ui);
        list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(24),dp(10),dp(24),dp(10));
        return list;
    }
    private ScrollView scroll(LinearLayout list) {
        ScrollView scroll = new ScrollView(ui); scroll.addView(list); return scroll;
    }
    private int dp(int n) { return Math.round(n * ui.getResources().getDisplayMetrics().density); }
}
