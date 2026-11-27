package com.otrong.carcontrol.installer;

enum InstallTarget {
    PHONE("pocket-car-control.apk", "galaxy-phone.apk"),
    BAND("wrist-car-control.rpk", "galaxy-vela-band.rpk"),
    BAND_LITE("wrist-car-control-lite.rpk", "galaxy-vela-band-lite.rpk"),
    WATCH("wrist-car-control-watch.apk", "galaxy-watch.apk");

    final String asset;
    final String previousAsset;

    InstallTarget(String asset, String previousAsset) {
        this.asset = asset;
        this.previousAsset = previousAsset;
    }

    BundledPackages.Package bundled(BundledPackages packages) {
        return switch (this) {
            case PHONE -> packages.phone;
            case BAND -> packages.band;
            case BAND_LITE -> packages.lite;
            case WATCH -> packages.watch;
        };
    }

    boolean isRpk() {
        return this == BAND || this == BAND_LITE;
    }
}
