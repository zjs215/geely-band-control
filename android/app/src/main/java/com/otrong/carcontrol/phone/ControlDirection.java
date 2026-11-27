package com.otrong.carcontrol.phone;

enum ControlDirection {
    OPEN("open"),
    CLOSE("close"),
    LOCK("lock"),
    UNLOCK("unlock");

    final String id;

    ControlDirection(String id) {
        this.id = id;
    }

    static ControlDirection fromId(String id) {
        for (ControlDirection direction : values()) {
            if (direction.id.equals(id)) return direction;
        }
        return null;
    }

    static ControlDirection forLockState(String state) {
        return switch (state) {
            case WidgetTree.LOCKED -> UNLOCK;
            case WidgetTree.UNLOCKED -> LOCK;
            default -> null;
        };
    }
}
