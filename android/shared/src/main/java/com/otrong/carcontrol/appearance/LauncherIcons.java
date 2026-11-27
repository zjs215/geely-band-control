package com.otrong.carcontrol.appearance;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class LauncherIcons {
    private static final String[] TITLES = {"紫色软胶", "黑白几何", "红色复古", "钴蓝折角"};

    private LauncherIcons() {}

    public static void show(Context context, ThemeDialogs dialogs, String defaultAlias,
                            int purple, int mono, int coral, int cobalt) {
        String appId = context.getPackageName();
        List<ComponentName> aliases = List.of(
                new ComponentName(appId, defaultAlias),
                new ComponentName(appId, appId + ".LauncherIconMono"),
                new ComponentName(appId, appId + ".LauncherIconCoral"),
                new ComponentName(appId, appId + ".LauncherIconCobalt"));
        PackageManager packages = context.getPackageManager();
        int selected = 0;
        for (int i = 1; i < aliases.size(); i++) {
            if (packages.getComponentEnabledSetting(aliases.get(i)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                selected = i;
                break;
            }
        }

        int[] icons = {purple, mono, coral, cobalt};
        Context themed = dialogs.context();
        int size = Math.round(32 * themed.getResources().getDisplayMetrics().density);
        var choices = new ArrayAdapter<String>(themed, android.R.layout.select_dialog_singlechoice, TITLES) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                CheckedTextView item = (CheckedTextView) super.getView(position, convertView, parent);
                Drawable preview = themed.getDrawable(icons[position]);
                preview.setBounds(0, 0, size, size);
                item.setCompoundDrawablesRelative(preview, null, null, null);
                item.setCompoundDrawablePadding(size / 3);
                return item;
            }
        };
        int original = selected;
        dialogs.builder().setTitle("桌面图标")
                .setSingleChoiceItems(choices, selected, (dialog, choice) -> {
                    if (choice != original) {
                        select(packages, aliases, choice);
                        Toast.makeText(context, "桌面图标已切换", Toast.LENGTH_SHORT).show();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("关闭", null).show();
    }

    private static void select(PackageManager packages, List<ComponentName> aliases, int choice) {
        int flags = PackageManager.DONT_KILL_APP;
        int enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        int disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (Build.VERSION.SDK_INT >= 33) {
            var changes = new ArrayList<PackageManager.ComponentEnabledSetting>(aliases.size());
            for (int i = 0; i < aliases.size(); i++) {
                changes.add(new PackageManager.ComponentEnabledSetting(aliases.get(i), i == choice ? enabled : disabled, flags));
            }
            packages.setComponentEnabledSettings(changes);
            return;
        }
        // Enable the new entry first so the launcher always has an icon during the switch.
        packages.setComponentEnabledSetting(aliases.get(choice), enabled, flags);
        for (int i = 0; i < aliases.size(); i++) {
            if (i != choice) packages.setComponentEnabledSetting(aliases.get(i), disabled, flags);
        }
    }
}
