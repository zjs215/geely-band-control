package com.mario.lynkband;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class BandPhotoTest {
    private JSONObject status() throws Exception {
        return new JSONObject().put("type", "status").put("bandPhoto", true).put("carPhotoId", "")
                .put("photoOffset", 0).put("photoTransferId", "");
    }

    private JSONObject reply(JSONObject request, VehiclePhoto.Image photo) throws Exception {
        JSONObject data = new JSONObject();
        BandPhoto.append(new JSONObject().put("data", data), request, photo);
        return data;
    }

    @Test public void requiresExplicitTypedOptInToStatusOnly() throws Exception {
        assertTrue(BandPhoto.requested(status()));
        for (String field : new String[]{"bandPhoto", "carPhotoId", "photoOffset", "photoTransferId"}) {
            JSONObject missing = status();
            missing.remove(field);
            assertFalse(BandPhoto.requested(missing));
        }
        for (String type : new String[]{"refresh", "prepare", "execute"})
            assertFalse(BandPhoto.requested(status().put("type", type)));
        assertFalse(BandPhoto.requested(status().put("bandPhoto", "true")));
        assertFalse(BandPhoto.requested(status().put("photoOffset", "0")));
        assertFalse(BandPhoto.requested(status().put("photoOffset", 0.5)));
    }

    @Test public void reassemblesChangedImageAndReturnsOnlyIdWhenCached() throws Exception {
        String payload = "aB+/".repeat(1100) + "zA==";
        VehiclePhoto.Image photo = new VehiclePhoto.Image("digest", payload);
        JSONObject request = status().put("carPhotoId", "older");
        StringBuilder received = new StringBuilder();
        while (received.length() < payload.length()) {
            JSONObject data = reply(request, photo);
            assertEquals(photo.id(), data.getString("carPhotoId"));
            assertEquals(received.length(), data.getInt("carPhotoOffset"));
            assertEquals(payload.length(), data.getInt("carPhotoLength"));
            assertEquals("image/png", data.getString("carPhotoMime"));
            String chunk = data.getString("carPhotoChunk");
            assertEquals(Math.min(2048, payload.length() - received.length()), chunk.length());
            assertEquals(chunk, reply(request, photo).getString("carPhotoChunk"));
            received.append(chunk);
            request.put("photoTransferId", photo.id()).put("photoOffset", received.length());
        }
        assertEquals(payload, received.toString());
        JSONObject stable = reply(status().put("carPhotoId", photo.id()), photo);
        assertEquals(1, stable.length());
        assertEquals(photo.id(), stable.getString("carPhotoId"));
    }

    @Test public void budgetsWholeUtf8FrameIncludingChineseStatusAndEscapedSlashes() throws Exception {
        var image = new VehiclePhoto.Image("a".repeat(64), "/".repeat(4000));
        var request = status();
        int offset = 0;
        while (offset < 4000) {
            JSONObject data = new JSONObject().put("vehicle", "领克".repeat(480));
            JSONObject frame = new JSONObject().put("v", 1).put("id", "current").put("data", data);
            BandPhoto.append(frame, request, image);
            assertTrue(frame.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096);
            assertEquals(offset, data.getInt("carPhotoOffset"));
            String chunk = data.getString("carPhotoChunk");
            assertTrue(chunk.length() > 0 && chunk.length() < 2048);
            assertEquals(image.base64().substring(offset, offset + chunk.length()), chunk);
            offset += chunk.length();
            request.put("photoOffset", offset).put("photoTransferId", image.id());
        }
    }

    @Test public void restartsOnImageChangeOrInvalidOffsetAndClearsUnavailable() throws Exception {
        VehiclePhoto.Image photo = new VehiclePhoto.Image("current", "A".repeat(BandPhoto.MAX_CHARS));
        JSONObject request = status().put("photoTransferId", "previous").put("photoOffset", 2048);
        assertEquals(0, reply(request, photo).getInt("carPhotoOffset"));
        request.put("photoTransferId", photo.id());
        for (long bad : new long[]{-1, BandPhoto.MAX_CHARS, (1L << 32) + 4})
            assertEquals(0, reply(request.put("photoOffset", bad), photo).getInt("carPhotoOffset"));
        assertEquals("", reply(request, null).getString("carPhotoId"));
        JSONObject over = reply(request, new VehiclePhoto.Image("oversize", photo.base64() + "AAAA"));
        assertEquals(1, over.length());
        assertEquals("", over.getString("carPhotoId"));
    }

    @Test public void hdPayloadRequiresTypedOptInAndKeepsLegacyChunkLimit() throws Exception {
        VehiclePhoto.Image image = new VehiclePhoto.Image("hd", "AQID".repeat(BandPhoto.HD_MAX_CHARS / 4));
        assertEquals("", reply(status(), image).getString("carPhotoId"));
        assertEquals("", reply(status().put(BandPhoto.HD_CAPABILITY, "true"), image).getString("carPhotoId"));
        JSONObject request = status().put(BandPhoto.HD_CAPABILITY, true);
        JSONObject first = reply(request, image);
        assertEquals(BandPhoto.HD_MAX_CHARS, first.getInt("carPhotoLength"));
        assertEquals(2048, first.getString("carPhotoChunk").length());
        JSONObject last = reply(request.put("photoTransferId", image.id()).put("photoOffset", 32764), image);
        assertEquals("AQID", last.getString("carPhotoChunk"));
    }
}
