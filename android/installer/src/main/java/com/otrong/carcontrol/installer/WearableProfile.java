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
    private static final String SUFFIX = "(nfc|esim|sport|41mm|42mm|46mm)?([-_]?[0-9a-f]{4,12})?";

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
        String normalized = normalizeBluetoothName(name);
        if (normalized.matches(BAND_PREFIX + "10pro" + SUFFIX)) return BAND_10_PRO;
        if (normalized.matches(BAND_PREFIX + "8pro" + SUFFIX)) return BAND_8_PRO;
        if (normalized.matches(BAND_PREFIX + "9pro" + SUFFIX)) return BAND_9_PRO;
        if (normalized.matches(BAND_PREFIX + "(9|10)" + SUFFIX)) return STRAIGHT_BAND;
        if (normalized.matches("(xiaomiwatch5|小米watch5|小米手表5|m2501w1)" + SUFFIX)) return WATCH_5;
        if (normalized.matches("((xiaomi|小米)(watch|手表)(s[3-9]|h1)|(redmi|红米)(watch|手表)[4-9])" + SUFFIX)) {
            return VELA_WATCH;
        }
        return null;
    }

    static boolean isLikelyXiaomiWearable(String name) {
        if (fromBluetoothName(name) != null) return true;
        if (name == null) return false;
        String normalized = normalizeBluetoothName(name);
        return normalized.contains("xiaomiwatch")
                || normalized.contains("redmiwatch")
                || normalized.contains("小米watch")
                || normalized.contains("小米手表")
                || normalized.contains("红米手表")
                || normalized.contains("xiaomismartband")
                || normalized.contains("xiaomiband")
                || normalized.contains("mismartband")
                || normalized.contains("miband")
                || normalized.contains("redmiband")
                || normalized.contains("小米手环")
                || normalized.contains("红米手环");
    }

    private static String normalizeBluetoothName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Zs}]+", "");
    }
}
