package com.otrong.carcontrol.phone;

/**
 * Pure-Java policy rules decoupling vehicle control authorization and voice dispatch from wearable links.
 */
final class ControlPolicy {
    private ControlPolicy() {}

    static boolean canArm(boolean hasCard, boolean hasReadyControl) {
        return hasCard && hasReadyControl;
    }

    static boolean shouldListenWidgets(boolean screenVisible, boolean wearServiceRunning,
                                       boolean voiceEnabled, boolean armed) {
        return screenVisible || wearServiceRunning || (voiceEnabled && armed);
    }

    static String liveControlNotice(String pauseMessage, boolean requireWearService,
                                    boolean wearServiceRunning, boolean armed) {
        if (pauseMessage != null && !pauseMessage.isEmpty()) return pauseMessage;
        if (requireWearService && !wearServiceRunning) return "请先启动手机端穿戴通信";
        return armed ? "" : "请在手机点击「允许车控」";
    }
}
