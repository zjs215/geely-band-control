package com.otrong.carcontrol.phone;

import org.json.JSONException;
import org.json.JSONObject;

record VehicleStatus(Integer totalKm, Integer electricKm, Integer fuelKm,
                     Integer electricPercent, Integer fuelPercent, String motion, long updatedAt,
                     String updatedLabel) {
    static final VehicleStatus EMPTY = new VehicleStatus(null, null, null, null, null, "", 0, "");

    JSONObject json() throws JSONException {
        return new JSONObject()
                .put("totalKm", totalKm)
                .put("electricKm", electricKm)
                .put("fuelKm", fuelKm)
                .put("electricPercent", electricPercent)
                .put("fuelPercent", fuelPercent)
                .put("motion", motion)
                .put("updatedAt", updatedAt)
                .put("updatedLabel", updatedLabel);
    }
}
