package com.mario.lynkband.watch;

import com.mario.lynkband.transport.WatchWire;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class WatchStateTest {
    final WatchState state = new WatchState();
    JSONObject slot() throws Exception {
        return new JSONObject().put("slot","fr_window").put("key","car/icon/closed").put("label","开启通风");
    }
    JSONObject reply(JSONObject request) throws Exception {
        return new JSONObject().put("v",1).put("id",request.getString("id")).put("status","已连接")
                .put("data",new JSONObject().put("range","318").put("battery","0%").put("fuel","21L").put("time","12:08"))
                .put("actions",new JSONArray().put(slot()));
    }
    void apply(String raw, long now) throws Exception {
        state.apply(new JSONObject(raw), null, now);
    }
    void prepare() throws Exception {
        JSONObject prep = state.request("prepare",slot(),10);
        apply(reply(prep).put("label","开启通风").put("token","one").toString(),20);
    }
    @Test public void roundTripUsesProductionFramesAndKeepsUnitsAndTime() throws Exception {
        JSONObject req = state.request("status",null,1);
        ByteArrayOutputStream tx = new ByteArrayOutputStream();
        WatchWire.write(tx, req.toString());
        JSONObject inbound = new JSONObject(new String(WatchWire.read(new ByteArrayInputStream(tx.toByteArray())),StandardCharsets.UTF_8));
        assertTrue(inbound.getBoolean("supportsControlState"));
        assertEquals("", inbound.getString("carPhotoId"));
        ByteArrayOutputStream rx = new ByteArrayOutputStream();
        WatchWire.write(rx,reply(inbound).toString());
        apply(new String(WatchWire.read(new ByteArrayInputStream(rx.toByteArray())), StandardCharsets.UTF_8),2);
        assertEquals("0%",state.data.getString("battery"));
        assertEquals("12:08",state.data.getString("time"));
        assertEquals("21L",state.data.getString("fuel"));
        assertEquals("开启通风",state.actions.getJSONObject(0).getString("label"));
    }
    @Test public void everyRequestAsksForLocalIconsAndKeepsLegacyActionMetadata() throws Exception {
        JSONObject window = window("closed").put("icon", "legacy-png");
        for (String type : new String[]{"status", "refresh", "prepare", "execute"}) {
            JSONObject request = state.request(type, "prepare".equals(type) ? window : null, 10);
            assertEquals(type, Boolean.TRUE, request.get("visualActions"));
            assertEquals(type, Boolean.TRUE, request.get("localWatchIcons"));
            JSONObject response = panel(request, "A", new JSONObject(window.toString()));
            if ("prepare".equals(type)) response.put("token", "once").put("label", window.getString("label"));
            state.apply(response, null, 11);
            JSONObject item = state.actions.getJSONObject(0);
            assertFalse(item.has("icon"));
            assertEquals("WINDOW", item.getString("category"));
            assertEquals("closed", item.getString("controlState"));
            assertEquals(window.getString("key"), item.getString("key"));
        }
    }

    @Test public void phoneSolarIsOptionalAndBelongsOnlyToTheCurrentStatusConnection() throws Exception {
        JSONObject request = state.request("status", null, 1);
        assertEquals(Boolean.TRUE, request.get("watchSolar"));
        state.apply(reply(request).put("themeNight", true).put("solarInfo", "日出 07:00 · 日落 17:00"), null, 2);
        assertEquals(new WatchState.PhoneSolar(true, "日出 07:00 · 日落 17:00"), state.phoneSolar());

        JSONObject refresh = state.request("refresh", null, 3);
        assertFalse(refresh.has("watchSolar"));
        state.apply(reply(refresh).put("themeNight", false), null, 4);
        assertTrue(state.phoneSolar().night());

        request = state.request("status", null, 5);
        state.apply(reply(request).put("themeNight", "false"), null, 6);
        assertNull(state.phoneSolar());

        request = state.request("status", null, 7);
        state.apply(reply(request).put("themeNight", false).put("solarInfo", "x".repeat(81)), null, 8);
        assertEquals(new WatchState.PhoneSolar(false, ""), state.phoneSolar());

        request = state.request("status", null, 9);
        state.apply(reply(request), null, 10);
        assertNull(state.phoneSolar());

        request = state.request("status", null, 11);
        state.apply(reply(request).put("themeNight", true), null, 12);
        state.offline("断开");
        assertNull(state.phoneSolar());
    }

    @Test public void themeChunkDoesNotReplaceVehicleSnapshotOrBlockTheNextControl() throws Exception {
        JSONObject status = state.request("status", null, 1);
        state.apply(reply(status), null, 2);
        JSONObject transfer = state.request("themeAsset", null, 3);
        assertTrue(state.reading());
        assertTrue(state.readingTheme());
        assertFalse(state.controlPending());
        state.apply(new JSONObject().put("v", 1).put("id", transfer.getString("id"))
                .put("type", "themeAsset"), null, 4);
        assertFalse(state.busy());
        assertEquals("318", state.data.getString("range"));
        assertNotNull(state.request("prepare", slot(), 5));
    }

    @Test public void confirmedActionIsSentOnlyOnce() throws Exception {
        prepare();
        assertTrue(state.confirming());
        assertNull(state.request("status",null,21));
        JSONObject execution = state.request("execute",null,21);
        assertEquals("one",execution.getString("token"));
        assertEquals("car/icon/closed",execution.getString("key"));
        assertNull(state.request("execute",null,22));
    }
    @Test public void stateCaptionIsNeverUsedAsTheAction() throws Exception {
        JSONObject climate = new JSONObject().put("slot", "climate").put("key", "ac")
                .put("label", "关空调").put("displayLabel", "空调.开");
        JSONObject prep = state.request("prepare", climate, 10);
        assertTrue(prep.getBoolean("supportsControlState"));
        assertEquals("关空调", prep.getString("label"));
        assertFalse(prep.has("displayLabel"));
        assertEquals("关空调", state.preparingTitle());
        apply(reply(prep).put("actions", new JSONArray().put(climate))
                .put("label", "关空调").put("token", "ticket").toString(), 11);
        assertEquals("关空调", state.title());
        JSONObject execute = state.request("execute", null, 12);
        assertTrue(execute.getBoolean("supportsControlState"));
        assertEquals("关空调", execute.getString("label"));
        assertFalse(execute.has("displayLabel"));
    }
    @Test public void unknownAndLoadingNeverArmEvenWithAnEnabledFlagOrToken() throws Exception {
        for (String value : new String[]{"unknown", "loading", "future"}) {
            JSONObject blocked = slot().put("enabled", true).put("controlState", value);
            assertNull(state.request("prepare", blocked, 10));
            JSONObject prep = state.request("prepare", slot().put("controlState", "closed"), 11);
            apply(reply(prep).put("actions", new JSONArray().put(blocked)).put("token", "stale")
                    .put("label", blocked.getString("label")).toString(), 12);
            assertFalse(state.confirming());
            assertNull(state.request("execute", null, 13));
        }
    }

    @Test public void namesAndLegacyStateUseOnlyReportedInformation() throws Exception {
        JSONObject old = slot();
        assertEquals("开启通风", WatchState.controlName(old));
        assertTrue(WatchState.actionable(old));
        old.put("displayLabel", "车窗.加载");
        assertEquals("车窗", WatchState.controlName(old));
        assertFalse(WatchState.actionable(old));
        old.put("controlState", "open").put("controlName", "右车窗");
        assertTrue(WatchState.actionable(old));
        assertEquals("右车窗", WatchState.controlName(old));
    }

    @Test public void legacyRefusalStartsOnlyABoundedPollingWindow() throws Exception {
        prepare();
        assertEquals(4000, state.pollDelay(21));
        JSONObject execute = state.request("execute", null, 22);
        apply(reply(execute).put("note", "确认已失效，请重新选择").toString(), 100);
        assertNull(state.loadingAction());
        for (int now = 600; now < 5100; now += 500) {
            assertEquals(500, state.pollDelay(now));
            JSONObject poll = state.request("status", null, now);
            assertFalse(poll.has("token"));
            apply(reply(poll).toString(), now);
        }
        assertEquals(4000, state.pollDelay(5100));
        state.offline("断开");
        assertEquals(4000, state.pollDelay(101));
    }

    private JSONObject window(String stateName) throws Exception {
        return slot().put("key", "fixture/车/窗/" + stateName).put("category", "WINDOW")
                .put("controlName", "车窗").put("controlState", stateName);
    }

    private JSONObject panel(JSONObject request, String vehicle, JSONObject... rows) throws Exception {
        JSONArray actions = new JSONArray();
        for (JSONObject row : rows) actions.put(row);
        return reply(request).put("data", new JSONObject().put("vehicle", vehicle)).put("actions", actions);
    }

    private JSONObject startWindow() throws Exception {
        JSONObject window = window("closed");
        JSONObject prep = state.request("prepare", window, 10);
        assertFalse(prep.has("compactExecuteAck"));
        state.apply(panel(prep, "A", window).put("token", "once").put("label", window.getString("label")), null, 20);
        JSONObject execute = state.request("execute", null, 21);
        assertEquals(Boolean.TRUE, execute.get("compactExecuteAck"));
        assertTrue(state.showsLoading(window));
        assertFalse(state.loadingAction().has("token"));
        return execute;
    }

    private JSONObject ack(JSONObject request, Object accepted) throws Exception {
        return new JSONObject().put("v", 1).put("id", request.getString("id"))
                .put("type", "executeAck").put("accepted", accepted).put("note", "已受理");
    }

    private void poll(String vehicle, long now, JSONObject... rows) throws Exception {
        JSONObject poll = state.request("status", null, now);
        assertFalse(poll.has("token"));
        state.apply(panel(poll, vehicle, rows), null, now);
    }

    @Test public void compactAckWaitsForMatchingVehicleSlotCategoryAndOppositeState() throws Exception {
        JSONObject execute = startWindow();
        JSONObject previousData = state.data;
        state.apply(ack(execute, true), null, 22);
        assertSame(previousData, state.data);
        assertEquals(0, state.pollDelay(22));
        assertNull(state.request("prepare", window("closed"), 22));
        poll("A", 23, window("closed"));
        poll("", 2000); // The provider's confirmation overlay has no vehicle or controls.
        poll("A", 5000, window("loading"));
        poll("A", 7000, window("open").put("slot", "other"), window("open").put("category", "LOCK"));
        assertNotNull(state.loadingAction());
        assertEquals(500, state.pollDelay(7000));
        assertNull(state.request("prepare", window("open").put("slot", "other"), 7100));
        poll("A", 7700, window("open"));
        assertNull(state.loadingAction());
        assertTrue(state.note.contains("已完成"));
        assertEquals(4000, state.pollDelay(7701));
        assertNull(state.request("execute", null, 7710));
    }

    @Test public void rejectedAckBlocksStaleActionsUntilFirstStatus() throws Exception {
        JSONObject execute = startWindow();
        state.apply(ack(execute, false).put("note", "确认已失效"), null, 22);
        assertNull(state.loadingAction());
        assertEquals("确认已失效", state.note);
        assertFalse(state.canControl(state.actions.getJSONObject(0)));
        assertNull(state.request("prepare", window("closed"), 23));
        poll("A", 24, window("closed"));
        assertTrue(state.canControl(state.actions.getJSONObject(0)));
        assertNull(state.request("execute", null, 25));
    }

    @Test public void uncertainDispatchAndLegacyReplyRemainBoundedWithoutClaimingSuccess() throws Exception {
        JSONObject execute = startWindow();
        state.apply(ack(execute, JSONObject.NULL), null, 22);
        poll("", 23);
        poll("A", 45021, window("closed"));
        assertNull(state.loadingAction());
        assertTrue(state.note.contains("待核对"));
        execute = startWindow();
        state.apply(panel(execute, "A", window("closed")).put("note", "已转交"), null, 22);
        assertNotNull(state.loadingAction());
        poll("B", 23, window("open"));
        assertNull(state.loadingAction());
        assertTrue(state.note.contains("待核对"));
        execute = startWindow();
        state.apply(ack(execute, true), null, 22);
        state.offline("断开");
        assertNull(state.loadingAction());
        assertTrue(state.note.contains("待核对"));
    }

    private void invalid(JSONObject response) throws Exception {
        try { state.apply(response, null, 22); fail(); }
        catch (org.json.JSONException expected) { assertTrue(state.busy()); }
    }

    @Test public void mismatchedAndMalformedReceiptsNeverConsumeThePendingRequest() throws Exception {
        JSONObject poll = state.request("status", null, 1);
        invalid(ack(poll, true));
        state.apply(reply(poll), null, 2);
        JSONObject execute = startWindow();
        invalid(ack(execute, true).put("id", "other"));
        invalid(ack(execute, true).put("v", "1"));
        invalid(ack(execute, true).put("type", "status"));
        invalid(ack(execute, "true"));
        invalid(ack(execute, true).put("note", JSONObject.NULL));
        invalid(new JSONObject().put("v", 1).put("id", execute.getString("id")));
        state.offline("断开");
        assertTrue(state.note.contains("待核对"));
        assertNull(state.request("execute", null, 30));
    }

    @Test public void expiredActionCannotExecute() throws Exception {
        prepare();
        assertNull(state.request("execute",null,12020));
        assertFalse(state.confirming());
    }
    @Test public void cancellationAndDisconnectionRevokeActions() throws Exception {
        prepare(); state.cancel();
        assertNull(state.request("execute",null,30));
        prepare(); state.offline("断开");
        assertEquals(0,state.actions.length());
        assertNull(state.request("execute",null,30));
    }
    @Test public void lostAcknowledgementIsIndeterminate() throws Exception {
        prepare(); state.request("execute",null,25); state.offline("断开");
        assertTrue(state.note.contains("核对"));
        state.offline("正在连接手机");
        JSONObject status = state.request("status",null,30);
        assertFalse(status.has("token"));
        apply(reply(status).toString(),31);
        assertTrue(state.note.contains("核对"));
        assertNull(state.request("execute",null,32));
    }
    @Test public void wrongReplyOrChangedLabelCannotExecute() throws Exception {
        JSONObject req = state.request("prepare",slot(),10);
        try { apply(reply(req).put("id","other").toString(),11); fail(); }
        catch (org.json.JSONException expected) { assertTrue(state.busy()); }
        apply(reply(req).put("label","解锁").put("token","t").toString(),12);
        assertFalse(state.confirming());
    }
    @Test public void incompleteOrOversizedFramesAreRejected() throws Exception {
        for (byte[] data : new byte[][]{{'a'}, new byte[8300]}) {
            try { WatchWire.read(new ByteArrayInputStream(data)); fail(); } catch (IOException expected) {}
        }
    }
    @Test public void refreshNeverCreatesAConfirmation() throws Exception {
        JSONObject req = state.request("refresh",null,10);
        apply(reply(req).put("note","已请求刷新").toString(),11);
        assertFalse(state.confirming()); assertEquals("已请求刷新",state.note);
    }
    @Test public void readonlySlotsNeverPrepareAndConfirmationUsesMatchedCategoryAndState() throws Exception {
        assertNull(state.request("prepare", slot().put("enabled",false), 0));
        JSONObject req = state.request("prepare", slot(), 10);
        JSONObject other = slot().put("key","other-key").put("category","LOCK").put("controlState","closed");
        JSONObject current = slot().put("category","WINDOW").put("controlState","open").put("icon","obsolete");
        apply(reply(req).put("actions",new JSONArray().put(other).put(current))
                .put("label","开启通风").put("token","ticket").toString(), 11);
        assertEquals(com.mario.lynkband.transport.ControlCategory.WINDOW, state.confirmCategory());
        assertEquals(WatchState.ControlState.OPEN, state.confirmState());
        assertFalse(state.request("execute",null,12).has("icon"));
    }
    @Test public void changedSlotInPrepareReplyCannotExecute() throws Exception {
        JSONObject req = state.request("prepare",slot(),10);
        JSONObject changed = slot().put("key","different-glyph");
        apply(reply(req).put("actions",new JSONArray().put(changed)).put("label","开启通风")
                .put("token","old").toString(),11);
        assertFalse(state.confirming());
        assertNull(state.request("execute",null,12));
    }
}
