package com.otrong.carcontrol.phone;

enum CarAction {
    LOCK("lock", "车锁"),
    CLIMATE("climate", "空调"),
    WINDOW_VENT("window_vent", "车窗微开"),
    TRUNK("trunk", "后备箱"),
    FIND("find", "寻车"),
    WINDOW_OPEN("window_open", "车窗全开"),
    HEAT("heat", "极速升温"),
    COOL("cool", "极速降温"),
    PURIFY("purify", "净化"),
    DEFROST("defrost", "除霜"),
    SENTRY("sentry", "哨兵模式"),
    LYNK_UNLOCK("lynk_unlock", "车锁槽位（待验证）"),
    LYNK_WINDOW("lynk_window", "车窗槽位（待验证）"),
    LYNK_KJCK("lynk_kjck", "kjck（待验证）"),
    LYNK_CCQK("lynk_ccqk", "ccqk（待验证）");

    final String id;
    final String label;

    CarAction(String id, String label) {
        this.id = id;
        this.label = label;
    }

    boolean isWindow() {
        return this == WINDOW_VENT || this == WINDOW_OPEN;
    }

    boolean isSwitch() {
        return switch (this) {
            case CLIMATE, HEAT, COOL, PURIFY, DEFROST, SENTRY -> true;
            default -> false;
        };
    }

    boolean acceptsDirection(ControlDirection direction) {
        if (this == LOCK) return direction == ControlDirection.LOCK || direction == ControlDirection.UNLOCK;
        return (isWindow() || isSwitch()) && (direction == ControlDirection.OPEN || direction == ControlDirection.CLOSE);
    }

    static CarAction fromId(String id) {
        for (CarAction action : values()) {
            if (action.id.equals(id)) return action;
        }
        return null;
    }
}
