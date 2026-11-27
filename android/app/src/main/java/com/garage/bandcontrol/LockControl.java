package com.garage.bandcontrol;

final class LockControl {
    private LockControl() {}

    static String blockedReason(String lockState) {
        return ControlDirection.forLockState(lockState) == null ? "请在手机核对车门锁态" : "";
    }
}
