package com.mario.lynkband;

import com.mario.lynkband.transport.ControlCategory;

enum VoicePreset {
    LOCK(ControlCategory.LOCK, "上锁", "锁车", "闭锁", "落锁"),
    UNLOCK(ControlCategory.LOCK, "解锁", "开锁"),
    FIND(ControlCategory.FIND, "寻车", "找车"),
    START(ControlCategory.ENGINE, "启动引擎", "启动发动机", "引擎启动", "发动机启动", "远程启动"),
    STOP(ControlCategory.ENGINE, "关闭引擎", "关闭发动机", "停止引擎", "引擎熄火", "发动机熄火", "熄火"),
    OPEN_TRUNK(ControlCategory.TRUNK, "打开后备箱", "开后备箱", "打开尾门", "开启后备箱"),
    CLOSE_TRUNK(ControlCategory.TRUNK, "关闭后备箱", "关后备箱", "关闭尾门"),
    OPEN_ROOF(ControlCategory.SUNROOF, "打开天窗", "开天窗", "开启天窗"),
    CLOSE_ROOF(ControlCategory.SUNROOF, "关闭天窗", "关天窗"),
    AIR_ON(ControlCategory.CLIMATE, "打开空调", "开启空调", "开空调", "空调开启"),
    AIR_OFF(ControlCategory.CLIMATE, "关闭空调", "关空调", "空调关闭"),
    OPEN_WINDOW(ControlCategory.WINDOW, "打开车窗", "开车窗", "开窗", "车窗全开", "开启车窗"),
    CLOSE_WINDOW(ControlCategory.WINDOW, "关闭车窗", "关车窗", "关窗", "车窗全关"),
    VENT_WINDOW(ControlCategory.WINDOW, "车窗通风", "车窗微开");

    final ControlCategory group;
    final String label;
    private final String[] aliases;

    VoicePreset(ControlCategory group, String label, String... aliases) {
        this.group = group;
        this.label = label;
        this.aliases = aliases;
    }
    static String display(String name) {
        return switch (canonical(name)) {
            case "打开车窗" -> "开车窗";
            case "关闭车窗" -> "关车窗";
            case "打开空调" -> "开空调";
            case "关闭空调" -> "关空调";
            case "打开天窗" -> "开天窗";
            case "关闭天窗" -> "关天窗";
            case "打开后备箱" -> "开后备箱";
            case "关闭后备箱" -> "关后备箱";
            default -> canonical(name);
        };
    }

    static String canonical(String name) {
        for (VoicePreset p : values()) {
            if (p.label.equals(name)) return p.label;
            for (String alias : p.aliases) if (alias.equals(name)) return p.label;
        }
        return name;
    }
}
