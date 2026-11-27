package com.otrong.carcontrol.installer;

import java.util.Locale;

enum WearableProfile {
    BAND_10_PRO(R.string.band_10_pro, InstallTarget.BAND),
    BAND_8_PRO(R.string.band_8_pro, InstallTarget.BAND_LITE),
    BAND_9_PRO(R.string.band_9_pro, InstallTarget.BAND_LITE),
    STRAIGHT_BAND(R.string.straight_band, InstallTarget.BAND_LITE),
    VELA_WATCH(R.string.vela_watch, InstallTarget.BAND),
    WATCH_5(R.string.watch_5, InstallTarget.WATCH);

    private static final String BAND_PREFIX = "(小米手环|xiaomi(smart)?band|mismartband|miband)";
    private static final String SUFFIX = "([-_]?[0-9a-f]{4,12})?";

    final int label;
    final InstallTarget target;

    WearableProfile(int label, InstallTarget target) {
        this.label = label;
        this.target = target;
    }

    static WearableProfile known(String value) {
        for (WearableProfile profile : values()) {
            if (profile.name().equals(value)) return profile;
        }
        return null;
    }

    static WearableProfile fromBluetoothName(String name) {
        if (name == null) return null;
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Zs}]+", "");
        if (normalized.matches(BAND_PREFIX + "10pro" + SUFFIX)) return BAND_10_PRO;
        if (normalized.matches(BAND_PREFIX + "8pro" + SUFFIX)) return BAND_8_PRO;
        if (normalized.matches(BAND_PREFIX + "9pro" + SUFFIX)) return BAND_9_PRO;
        if (normalized.matches(BAND_PREFIX + "10" + SUFFIX)) return STRAIGHT_BAND;
        if (normalized.matches("(xiaomiwatch5|小米watch5|小米手表5|m2501w1)" + SUFFIX)) return WATCH_5;
        return null;
    }
}
