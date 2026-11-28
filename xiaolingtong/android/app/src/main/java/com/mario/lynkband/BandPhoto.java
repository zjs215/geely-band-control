package com.mario.lynkband;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Stateless Vela status extension. Offsets count characters in the unwrapped base64 string. */
final class BandPhoto {
    static final int MAX_CHARS = 16384;
    static final int HD_MAX_CHARS = 32768;
    static final String HD_CAPABILITY = "bandPhotoHd";
    private static final int CHUNK_CHARS = 2048;
    private static final int FRAME_BYTES = 4096;

    static boolean requested(JSONObject request) {
        Object offset = request.opt("photoOffset");
        return "status".equals(request.opt("type")) && Boolean.TRUE.equals(request.opt("bandPhoto"))
                && request.opt("carPhotoId") instanceof String && request.opt("photoTransferId") instanceof String
                && (offset instanceof Integer || offset instanceof Long);
    }

    static void append(JSONObject frame, JSONObject request, VehiclePhoto.Image photo) throws JSONException {
        JSONObject data = frame.getJSONObject("data");
        int maxChars = Boolean.TRUE.equals(request.opt(HD_CAPABILITY)) ? HD_MAX_CHARS : MAX_CHARS;
        if (photo == null || photo.base64().isEmpty() || photo.base64().length() > maxChars) {
            data.put("carPhotoId", "");
            return;
        }
        data.put("carPhotoId", photo.id());
        if (photo.id().equals(request.opt("carPhotoId"))) return;

        int length = photo.base64().length();
        long requestedOffset = request.getLong("photoOffset");
        int offset = photo.id().equals(request.opt("photoTransferId")) && requestedOffset >= 0 && requestedOffset < length
                ? (int) requestedOffset : 0;
        data.put("carPhotoChunk", "").put("carPhotoOffset", offset)
                .put("carPhotoLength", length).put("carPhotoMime", "image/png");
        int remaining = FRAME_BYTES - frame.toString().getBytes(StandardCharsets.UTF_8).length;
        // Android's JSONStringer may escape '/' even in unwrapped base64.
        int slashBytes = JSONObject.quote("/").length() - 2;
        int end = offset;
        while (end < length && end - offset < CHUNK_CHARS) {
            remaining -= photo.base64().charAt(end) == '/' ? slashBytes : 1;
            if (remaining < 0) break;
            end++;
        }
        if (end > offset) {
            data.put("carPhotoChunk", photo.base64().substring(offset, end));
        } else {
            data.remove("carPhotoChunk");
            data.remove("carPhotoLength");
            data.remove("carPhotoOffset");
            data.remove("carPhotoMime");
            data.put("carPhotoId", "");
        }
    }
}
