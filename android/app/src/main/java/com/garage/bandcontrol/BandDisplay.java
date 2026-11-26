package com.garage.bandcontrol;

import org.json.JSONException;
import org.json.JSONObject;

record BandDisplay(boolean twoPage, long revision) {
    static final String FEATURE = "band_display";
    static final BandDisplay INITIAL = new BandDisplay(false, 0);

    static BandDisplay parse(JSONObject value) {
        if (value == null || !(value.opt("twoPage") instanceof Boolean enabled)) return null;
        Object revision = value.opt("revision");
        if (!(revision instanceof Integer || revision instanceof Long)) return null;
        long number = ((Number) revision).longValue();
        return number >= 0 && number <= 9_007_199_254_740_991L ? new BandDisplay(enabled, number) : null;
    }

    JSONObject json() throws JSONException {
        return new JSONObject().put("twoPage", twoPage).put("revision", revision);
    }

    BandDisplay select(boolean enabled) {
        return revision > 0 && enabled == twoPage ? this : new BandDisplay(enabled, revision + 1);
    }

    BandDisplay accept(BandDisplay proposed) {
        return proposed != null && proposed.revision == revision ? select(proposed.twoPage) : this;
    }
}
