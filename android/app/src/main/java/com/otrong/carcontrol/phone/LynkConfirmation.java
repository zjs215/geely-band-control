package com.otrong.carcontrol.phone;

/** Matches the official prompt to an already authorized, explicitly identified action. */
enum LynkConfirmation {
    LOCK("上锁车门", "确认上锁车辆", "确认锁车", "确认锁定车辆", "确认上锁车门", "确认上锁", "确认闭锁车辆", "确认闭锁"),
    UNLOCK("解锁车门", "确认解锁车辆", "确认解锁车门"),
    VENT("微开车窗", "确认微开车窗", "确认车窗微开"),
    OPEN("全开车窗", "确认打开车窗", "确认全开车窗", "确认车窗全开"),
    CLOSE("关闭车窗", "确认关闭车窗", "确认关窗"),
    WINDOW_TOGGLE("切换车窗"),
    LOCK_TOGGLE("切换车锁"),
    TRUNK_TOGGLE("切换后备箱", "确认打开后备箱", "确认开启后备箱", "确认关闭后备箱",
            "确认打开尾门", "确认开启尾门", "确认关闭尾门", "确认打开电动尾门", "确认开启电动尾门", "确认关闭电动尾门");

    final String label;
    private final String[] titles;

    LynkConfirmation(String label, String... titles) {
        this.label = label;
        this.titles = titles;
    }

    static LynkConfirmation forMark(String ownerCategory, ControlDirection direction) {
        if ("锁".equals(ownerCategory)) {
            if (direction == ControlDirection.LOCK) return LOCK;
            if (direction == ControlDirection.UNLOCK) return UNLOCK;
        }
        boolean vent = "车窗微开".equals(ownerCategory);
        if (vent || "车窗全开".equals(ownerCategory)) {
            if (direction == ControlDirection.CLOSE) return CLOSE;
            if (direction == ControlDirection.OPEN) return vent ? VENT : OPEN;
        }
        return null;
    }

    boolean matches(String title) {
        if (this == WINDOW_TOGGLE) return VENT.matches(title) || OPEN.matches(title) || CLOSE.matches(title);
        if (this == LOCK_TOGGLE) return LOCK.matches(title) || UNLOCK.matches(title);
        String text = title.trim();
        if (text.endsWith("？") || text.endsWith("?")) text = text.substring(0, text.length() - 1).trim();
        if (text.endsWith("吗") || text.endsWith("么")) text = text.substring(0, text.length() - 1).trim();
        if (text.startsWith("是否确认")) text = text.substring("是否".length());
        for (String candidate : titles) if (candidate.equals(text)) return true;
        return false;
    }
}
