package com.garage.bandcontrol;

import com.garage.appearance.ThemeConfig;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class BandMessageTest {
    @Test public void executeAcknowledgmentIsOptInAndDoesNotCarryVehicleState() throws Exception {
        JSONObject input = new JSONObject().put("v", 1).put("id", "test-123").put("type", "execute")
                .put("token", "11111111-1111-1111-1111-111111111111").put("action", "lynk_unlock");
        assertEquals(false, decode(input).compactAck());
        assertEquals(false, decode(input.put("compactAck", "true")).compactAck());
        BandMessage request = decode(input.put("compactAck", true));
        assertTrue(request.compactAck());
        JSONObject reply = BandMessage.executeAck(request.id(), "unknown", "请核对结果");
        assertEquals(5, reply.length());
        assertEquals("execute_ack", reply.getString("type"));
        assertEquals("unknown", reply.getString("status"));
        assertEquals(request.id(), reply.getString("id"));
    }

    @Test public void helloRoundTripsWithTheProtocolAndSupportedFeatures() throws Exception {
        BandMessage request = decode(new JSONObject().put("v", 1).put("id", "test-123")
                .put("type", "hello").put("bandApp", "0.1.16").put("minPhoneApp", "0.1.14"));
        assertEquals("hello", request.type());
        JSONObject reply = BandMessage.helloReply(request.id(), BuildConfig.VERSION_NAME);
        assertEquals(1, reply.getInt("v"));
        assertEquals("hello-reply", reply.getString("type"));
        assertEquals("test-123", reply.getString("id"));
        assertEquals(BuildConfig.VERSION_NAME, reply.getString("phoneApp"));
        assertEquals(1, reply.getInt("protocol"));
        assertEquals("[\"confirm\",\"cancel\",\"refresh\",\"car_image\",\"theme_v1\",\"band_display\",\"control_icons\"]", reply.getJSONArray("features").toString());
    }

    @Test public void bandDisplayAcceptsOnlyTheCurrentRevisionAndIgnoresMalformedUpdates() throws Exception {
        JSONObject message = new JSONObject().put("v", 1).put("id", "test-123").put("type", "status");
        BandDisplay first = BandDisplay.INITIAL.accept(decode(message.put("bandDisplay", new BandDisplay(true, 0).json())).bandDisplay());
        assertEquals(new BandDisplay(true, 1), first);
        BandDisplay phoneEdit = first.select(false);
        assertEquals(new BandDisplay(false, 2), phoneEdit);
        assertSame(phoneEdit, phoneEdit.accept(new BandDisplay(true, 1)));
        assertSame(phoneEdit, phoneEdit.accept(new BandDisplay(false, 2)));
        assertNull(decode(message.put("bandDisplay", new JSONObject().put("twoPage", true).put("revision", "2"))).bandDisplay());
        assertNull(decode(message.put("type", "hello")).bandDisplay());
    }

    @Test public void themeIsOptionalWhenVehicleStateFillsTheReply() throws Exception {
        String id = "test-123";
        JSONObject state = BandMessage.reply(id, "ready", "车况");
        state.put("theme", ThemeConfig.CLASSIC.json());
        assertEquals(ThemeConfig.CLASSIC, ThemeConfig.fromJson(new JSONObject(BandMessage.encodeReply(id,
                () -> state, invalid -> fail(invalid.toString()))).getJSONObject("theme")));
        state.put("message", "中".repeat(655));
        JSONObject bounded = new JSONObject(BandMessage.encodeReply(id, () -> state, invalid -> fail(invalid.toString())));
        assertEquals(655, bounded.getString("message").length());
        assertNull(bounded.optJSONObject("theme"));
    }

    @Test public void preservesPhoneHapticsSettingInReplies() throws Exception {
        JSONObject state = BandMessage.reply("hap_001", "ready", "车况").put("haptics", false);
        JSONObject encoded = new JSONObject(BandMessage.encodeReply("hap_001",
                () -> state, invalid -> fail(invalid.toString())));
        assertEquals(false, encoded.getBoolean("haptics"));
    }

    @Test public void decodesCarImageChunkRequestAndEncodesBoundedReply() throws Exception {
        BandMessage request = decode(new JSONObject().put("v", 1).put("id", "img_001")
                .put("type", "car_image").put("hash", "abcDEF12_34-").put("index", 3));
        assertEquals("car_image", request.type());
        assertEquals("abcDEF12_34-", request.imageHash());
        assertEquals(3, request.imageIndex());

        assertNull(decode(new JSONObject().put("v", 1).put("id", "img_002").put("type", "car_image").put("hash", "bad!").put("index", 0)));
        assertNull(decode(new JSONObject().put("v", 1).put("id", "img_003").put("type", "car_image").put("hash", "abcDEF12").put("index", -1)));

        JSONObject reply = BandMessage.carImageReply("img_001", "abcDEF12_34-", 3, 5, "A".repeat(CarImage.CHUNK_CHARS));
        assertEquals("car_image_reply", reply.getString("type"));
        assertTrue(reply.toString().getBytes(StandardCharsets.UTF_8).length <= BandMessage.MAX_BYTES);

        BandMessage statusDay = decode(new JSONObject().put("v", 1).put("id", "sts_001")
                .put("type", "status").put("themeDay", true));
        assertEquals(Boolean.TRUE, statusDay.themeDay());
        assertNull(decode(new JSONObject().put("v", 1).put("id", "sts_002").put("type", "status")).themeDay());

        BandMessage iconReq = decode(new JSONObject().put("v", 1).put("id", "icn_001")
                .put("type", "control_icon").put("hash", "abcDEF12_34-").put("index", 1));
        assertEquals("control_icon", iconReq.type());
        assertEquals("abcDEF12_34-", iconReq.imageHash());
        assertEquals(1, iconReq.imageIndex());
        assertNull(decode(new JSONObject().put("v", 1).put("id", "icn_002")
                .put("type", "control_icon").put("hash", "abcDEF12_34-").put("index", ControlIcons.MAX_CHUNKS + 1)));

        JSONObject iconReply = BandMessage.controlIconReply("icn_001", "abcDEF12_34-", 0, 1, "A".repeat(CarImage.CHUNK_CHARS));
        assertEquals("control_icon_reply", iconReply.getString("type"));
        assertTrue(iconReply.toString().getBytes(StandardCharsets.UTF_8).length <= BandMessage.MAX_BYTES);

        JSONObject withIcons = BandMessage.reply("sts_003", "ready", "车况");
        withIcons.put("actionIcons", new JSONObject().put("trunk", new org.json.JSONArray().put("abcDEF12_34-").put(1)));
        withIcons.put("message", "中".repeat(655));
        JSONObject stripped = new JSONObject(BandMessage.encodeReply("sts_003", () -> withIcons, invalid -> fail(invalid.toString())));
        assertNull(stripped.optJSONObject("actionIcons"));
    }

    @Test public void allBrandControlsHaveDistinctWireNames() throws Exception {
        CarAction[] actions = CarAction.values();
        assertEquals(15, actions.length);
        for (CarAction action : actions) {
            BandMessage message = decode(new JSONObject().put("v", 1).put("id", "test-123")
                    .put("type", "prepare").put("action", action.id));
            assertEquals(action, message.action());
        }
    }

    @Test public void rejectsUnknownActionsAndUnconfirmedExecutions() throws Exception {
        JSONObject message = new JSONObject().put("v", 1).put("id", "test-123").put("type", "execute");
        assertNull(decode(message.put("action", "unknown")));
        assertNull(decode(message.put("action", CarAction.SENTRY.id)));
    }

    @Test public void validatesPrepareDirectionsForEachAction() throws Exception {
        JSONObject message = new JSONObject().put("v", 1).put("id", "test-123").put("type", "prepare");
        for (CarAction action : CarAction.values()) {
            message.put("action", action.id).remove("controlDirection");
            assertNull(decode(message).direction());
            for (ControlDirection direction : ControlDirection.values()) {
                BandMessage request = decode(message.put("controlDirection", direction.id));
                boolean expected = switch (action) {
                    case LOCK -> direction == ControlDirection.LOCK || direction == ControlDirection.UNLOCK;
                    case FIND, TRUNK, LYNK_UNLOCK, LYNK_WINDOW, LYNK_KJCK, LYNK_CCQK -> false;
                    default -> direction == ControlDirection.OPEN || direction == ControlDirection.CLOSE;
                };
                if (expected) assertSame(direction, request.direction());
                else assertNull(request);
            }
            assertNull(decode(message.put("controlDirection", "toggle")));
        }
    }

    @Test public void preservesBrandOnPrepareAndAcceptsLegacyMessages() throws Exception {
        JSONObject message = new JSONObject().put("v", 1).put("id", "test-123")
                .put("type", "prepare").put("action", CarAction.FIND.id);
        assertEquals("", decode(message).brand());
        assertEquals("GEELY", decode(message.put("brand", "GEELY")).brand());
    }

    @Test public void legacyLockAndWindowFieldsAreValidatedWithTheNewDirection() throws Exception {
        JSONObject message = new JSONObject().put("v", 1).put("id", "test-123").put("type", "prepare")
                .put("action", CarAction.LOCK.id);
        assertSame(ControlDirection.UNLOCK, decode(message.put("lockState", "locked")).direction());
        assertSame(ControlDirection.LOCK, decode(message.put("lockState", "unlocked")).direction());
        assertNull(decode(message.put("controlDirection", "unlock")));
        message.remove("controlDirection");
        assertNull(decode(message.put("lockState", "unknown")));
        assertNull(decode(message.put("action", CarAction.CLIMATE.id).put("lockState", "locked")));
        message.remove("lockState");
        message.put("action", CarAction.WINDOW_VENT.id);
        assertSame(ControlDirection.CLOSE, decode(message.put("windowDirection", "close")).direction());
        assertNull(decode(message.put("controlDirection", "open")));
        assertNull(decode(message.put("action", CarAction.CLIMATE.id)));
    }

    @Test public void replyBudgetCountsUtf8BytesAndAllowsExactly2048() throws Exception {
        String id = "test-123";
        JSONObject reply = BandMessage.reply(id, "ready", "");
        int room = BandMessage.MAX_BYTES - reply.toString().getBytes(StandardCharsets.UTF_8).length;
        String message = "中".repeat(room / 3) + "x".repeat(room % 3);
        reply.put("message", message);
        assertEquals(2048, reply.toString().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(reply.toString(), BandMessage.encodeReply(id, () -> reply, invalid -> fail(invalid.toString())));
        reply.put("message", message + "x");
        assertError(id, "too_large", BandMessage.encodeReply(id, () -> reply, invalid -> fail(invalid.toString())));
    }

    @Test public void aReplyBuilderExceptionReturnsAnImmediateCorrelatedError() throws Exception {
        for (Exception problem : new Exception[]{new JSONException("failed"), new IllegalStateException("failed")}) {
            AtomicReference<Exception> reported = new AtomicReference<>();
            String payload = BandMessage.encodeReply("test-123", () -> {
                if (problem instanceof JSONException invalid) throw invalid;
                throw (RuntimeException) problem;
            }, reported::set);
            assertError("test-123", "internal", payload);
            assertSame(problem, reported.get());
        }
    }

    private static void assertError(String id, String code, String payload) throws JSONException {
        JSONObject error = new JSONObject(payload);
        assertEquals(4, error.length());
        assertEquals(1, error.getInt("v"));
        assertEquals("reply", error.getString("type"));
        assertEquals(id, error.getString("id"));
        assertEquals(code, error.getString("error"));
    }

    private static BandMessage decode(JSONObject message) throws Exception {
        return BandMessage.decode(message.toString().getBytes(StandardCharsets.UTF_8));
    }
}
