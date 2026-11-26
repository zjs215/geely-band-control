package com.garage.bandcontrol;

import static com.garage.bandcontrol.ControlDirection.CLOSE;
import static com.garage.bandcontrol.ControlDirection.OPEN;

final class WindowControl {
    record Decision(ControlDirection direction, String blockedReason) {}

    private static final Decision OPEN_WINDOW = new Decision(OPEN, "");
    private static final Decision CLOSE_WINDOW = new Decision(CLOSE, "");
    private static final Decision UNKNOWN = new Decision(null, "请刷新银河卡片，核对车窗状态");

    private WindowControl() {}

    static Decision resolve(ControlDirection iconDirection) {
        if (iconDirection == CLOSE) return CLOSE_WINDOW;
        return iconDirection == OPEN ? OPEN_WINDOW : UNKNOWN;
    }
}
