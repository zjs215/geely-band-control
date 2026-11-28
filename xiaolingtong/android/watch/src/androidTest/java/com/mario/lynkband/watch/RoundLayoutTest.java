package com.mario.lynkband.watch;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.*;
import java.util.concurrent.FutureTask;

@RunWith(AndroidJUnit4.class)
public class RoundLayoutTest {
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();
    private WatchFace face;
    private WatchActivity activity;
    private final WatchState state = new WatchState();
    private JSONObject selected;
    // A valid legacy phone icon (solid red square) must never replace bundled artwork.
    private static final String OLD_PHONE_ICON =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP8zwACTGCSAQANHQEDgslx_wAAAABJRU5ErkJggg==";

    @Test
    public void homeShowsOnlySelectedWidgetTargets() throws Exception {
        activity =
                (WatchActivity)
                        device.startActivitySync(
                                new Intent(device.getTargetContext(), WatchActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            state.status = "已允许穿戴车控";
            state.photoResolved = true; // Completed snapshot from a phone without artwork.
            state.data =
                    new JSONObject()
                            .put("vehicle", "我的领克")
                            .put("range", "225")
                            .put("unit", "km")
                            .put("battery", "39%")
                            .put("fuel", "12.2 L")
                            .put("condition", "已驻车")
                            .put("time", "11/25 14:05");
            String[] actions = {"上锁", "关窗", "关空调", "后备箱"};
            String[] captions = {"车锁", "车窗", "空调", "后备箱"};
            String[] descriptions = {"车锁.关", "车窗.开", "空调.开", "后备箱.关"};
            int[] artwork = {R.drawable.watch_control_lock, R.drawable.watch_control_window_open,
                    R.drawable.watch_control_climate_open, R.drawable.watch_control_trunk};
            for (int i = 0; i < actions.length; i++)
                state.actions.put(
                        new JSONObject()
                                .put("slot", "slot" + i)
                                .put("key", "image" + i)
                                .put("label", actions[i])
                                .put("controlName", captions[i])
                                .put("controlState", i == 1 || i == 2 ? "open" : "closed")
                                .put("displayLabel", descriptions[i])
                                .put("enabled", true)
                                .put("icon", OLD_PHONE_ICON));
            device.runOnMainSync(
                    () -> {
                        face =
                                new WatchFace(
                                        screenContext(true),
                                        new WatchFace.Controls() {
                                            public void refresh() {}

                                            public void prepare(JSONObject a) {
                                                selected = a;
                                            }

                                            public void confirm() {}

                                            public void cancel() {}

                                            public void pair() {}

                                            public void reconnect() {}
                                        });
                        android.widget.FrameLayout fixture = new android.widget.FrameLayout(activity);
                        fixture.addView(face, 480, 480);
                        activity.setContentView(fixture);
                        face.show(state, true, true);
                    });
            device.waitForIdleSync();
            FutureTask<Void> check =
                    new FutureTask<>(
                            () -> {
                                layout();
                                assertNotNull(find(face, "默认领克车图"));
                                save("watch5-dashboard.png");
                                ViewGroup dial = (ViewGroup) face.getChildAt(0);
                                View motion = dial.getChildAt(1);
                                assertTrue(motion instanceof com.mario.lynkband.ui.AmbientMotion);
                                state.data.put("time", "11/25 14:06");
                                face.show(state, true, true);
                                layout();
                                assertSame(motion, dial.getChildAt(1));
                                ScrollView grid = (ScrollView) find(face, "车况与车控");
                                for (int i = 0; i < captions.length; i++) {
                                    View tile = find(face, descriptions[i]);
                                    assertNotNull(tile);
                                    assertCaptionFits(tile, descriptions[i]);
                                    ViewGroup emblem = (ViewGroup) ((ViewGroup) tile).getChildAt(0);
                                    assertTrue(emblem.getWidth() >= 64 && emblem.getWidth() <= tile.getWidth());
                                    assertEquals(emblem.getWidth(), emblem.getHeight());
                                    assertArtwork(emblem, artwork[i]);
                                    TextView caption = (TextView) ((ViewGroup) tile).getChildAt(1);
                                    assertEquals(20f, caption.getTextSize(), .1f);
                                    Bitmap drawn = Bitmap.createBitmap(70, 70, Bitmap.Config.ARGB_8888);
                                    emblem.draw(new Canvas(drawn));
                                    int tint = 0;
                                    for (int y = 0; y < 70 && Color.alpha(tint) < 250; y++)
                                        for (int x = 0; x < 70 && Color.alpha(tint) < 250; x++) tint = drawn.getPixel(x, y);
                                    drawn.recycle();
                                    assertTrue(Color.alpha(tint) >= 250);
                                    if (i == 1 || i == 2) assertTrue(Color.green(tint) > Color.red(tint));
                                    else {
                                        int expected = activity.getColor(R.color.ink);
                                        assertEquals(Color.red(expected), Color.red(tint), 1);
                                        assertEquals(Color.green(expected), Color.green(tint), 1);
                                        assertEquals(Color.blue(expected), Color.blue(tint), 1);
                                    }
                                    assertEquals(74+(i%2)*174,tile.getLeft());
                                    assertEquals(480+(i/2)*148,tile.getTop());
                                }
                                assertNull(find(face,"引擎，不可用"));
                                assertNull(find(face,"天窗"));
                                assertTrue(grid.canScrollVertically(1));
                                assertTrue(face.scrollWithCrown(-1));
                                assertTrue(grid.getScrollY() > 0);
                                face.scrollWithCrown(10);
                                assertEquals(0, grid.getScrollY());
                                assertEquals(480, grid.getHeight());
                                swipeUp(grid);
                                assertTrue(grid.getScrollY() > 0);
                                assertNull(selected);
                                grid.scrollTo(0, 190);
                                save("watch5-dashboard-grid.png");
                                grid.scrollTo(0, grid.getChildAt(0).getHeight());
                                View settings = find(face, "设置");
                                Rect footer = new Rect();
                                assertTrue(settings.getGlobalVisibleRect(footer));
                                int end = grid.getScrollY();
                                assertTrue(end > 0);
                                save("watch5-dashboard-lower.png");
                                state.data.put("fuel", "12.1 L");
                                face.show(state, true, true);
                                layout();
                                grid = (ScrollView) find(face, "车况与车控");
                                assertEquals(end, grid.getScrollY());
                                for (int i = 0; i < captions.length; i++) {
                                    View tile = find(face, descriptions[i]);
                                    assertNotNull(captions[i], tile);
                                    grid.scrollTo(0, tile.getTop() - 177);
                                    assertInsideDial(tile);
                                    tile.performClick();
                                    assertEquals("image" + i, selected.getString("key"));
                                    assertEquals(actions[i], selected.getString("label"));
                                }
                                JSONObject prep =
                                        state.request(
                                                "prepare", state.actions.getJSONObject(2), 100);
                                state.apply(
                                        new JSONObject()
                                                .put("id", prep.getString("id"))
                                                .put("v", 1)
                                                .put("status", state.status)
                                                .put("data", state.data)
                                                .put("actions", state.actions)
                                                .put("label", "关空调")
                                                .put("token", "fixture"), null,
                                        101);
                                face.show(state, true, true);
                                return null;
                            });
            device.runOnMainSync(check);
            check.get();
            device.waitForIdleSync();
            FutureTask<Void> confirmCheck =
                    new FutureTask<>(
                            () -> {
                                layout();
                                save("watch5-confirm.png");
                                assertInsideDial(find(face, "确认操作"));
                                assertCaptionFits(face, "关空调");
                                assertArtwork(face, R.drawable.watch_control_climate_open);
                                JSONObject execute = state.request("execute", null, 105);
                                face.show(state, true, true);
                                layout();
                                View waiting = find(face, "空调·执行中");
                                assertNotNull(waiting);
                                assertFalse(waiting.isEnabled());
                                assertFalse(waiting.isClickable());
                                assertNull(findImage(waiting)); // Execution replaces the glyph with a spinner.
                                assertNull(find(face, "空调.开"));
                                state.apply(new JSONObject().put("v", 1).put("id", execute.getString("id"))
                                        .put("type", "executeAck").put("accepted", true).put("note", "已转交领克卡片"), null, 110);
                                face.show(state, true, true);
                                layout();
                                assertNotNull(find(face, "空调·执行中"));
                                ScrollView waitingGrid = (ScrollView) find(face, "车况与车控");
                                waitingGrid.scrollTo(0, find(face, "空调·执行中").getTop() - 162);
                                return null;
                            });
            device.runOnMainSync(confirmCheck);
            confirmCheck.get();
            device.waitForIdleSync();
            android.os.SystemClock.sleep(240); // Ensures a visible frame of the indeterminate indicator.
            FutureTask<Void> outcomeCheck = new FutureTask<>(() -> {
                                layout();
                                assertCaptionFits(face, "空调·执行中");
                                save("watch5-executing.png");
                                JSONObject update = state.request("status", null, 120);
                                state.actions.getJSONObject(2).put("controlState", "closed").put("label", "开空调");
                                state.apply(new JSONObject().put("v", 1).put("id", update.getString("id"))
                                        .put("status", state.status).put("data", state.data).put("actions", state.actions), null, 121);
                                face.show(state, true, true);
                                layout();
                                assertNull(find(face, "空调·执行中"));
                                assertArtwork(find(face, "空调.关"), R.drawable.watch_control_climate);
                                state.actions.getJSONObject(1).put("enabled", true)
                                        .put("controlState", "unknown").put("displayLabel", "车窗.未知");
                                face.show(state, true, true);
                                layout();
                                assertNull(find(face, "车窗.未知，只读"));
                                assertNull(find(face, "车窗.未知"));
                                state.actions = new JSONArray();
                                String[] alternate = {"鸣笛寻车", "远程启动", "开启天窗", "关闭天窗"};
                                for (int i = 0; i < alternate.length; i++)
                                    state.actions.put(
                                            new JSONObject()
                                                    .put("slot", "slot" + i)
                                                    .put("key", "alt" + i)
                                                    .put("label", alternate[i])
                                                    .put("enabled", true));
                                state.actions.getJSONObject(1).put("category", "ENGINE");
                                face.show(state, true, true);
                                layout();
                                assertNull(find(face,"上锁"));
                                find(face,"鸣笛寻车").performClick();
                                assertEquals("alt0",selected.getString("key"));
                                find(face,"远程启动").performClick();
                                assertEquals("alt1",selected.getString("key"));
                                find(face,"关闭天窗").performClick();
                                assertEquals("alt3",selected.getString("key"));
                                state.actions=new JSONArray().put(state.actions.getJSONObject(1));
                                face.show(state,true,true);layout();
                                assertNull(find(face,"关闭天窗"));
                                assertNotNull(find(face,"远程启动"));
                                state.actions=new JSONArray();
                                face.show(state,true,true);layout();
                                assertNull(find(face,"远程启动"));
                                return null;
                            });
            device.runOnMainSync(outcomeCheck);
            outcomeCheck.get();
            checkReadOnlyGrid();
        } finally {
            device.runOnMainSync(activity::finish);
        }
    }

    private void checkReadOnlyGrid() throws Exception {
        JSONObject item = new JSONObject().put("slot", "w").put("key", "window")
                .put("label", "关窗").put("controlName", "车窗").put("enabled", true).put("controlState", "open");
        FutureTask<Void> check = new FutureTask<>(() -> {
            state.actions = new JSONArray().put(item);
            state.status = "已允许穿戴车控";
            face.show(state, true, true);
            layout();
            assertNotNull(find(face, "车窗.开"));
            state.status = "车况只读";
            face.show(state, true, true);
            layout();
            assertNull(find(face, "车窗.开"));
            assertNull(find(face, "车窗.开，只读"));
            find(face, "设置").performClick();
            layout();
            assertCaptionFits(face, BuildConfig.VERSION_NAME);
            ViewGroup returnButton = (ViewGroup) find(face, "返回车况");
            TextView returnCaption = (TextView) returnButton.getChildAt(returnButton.getChildCount() - 1);
            assertEquals(32f, returnCaption.getTextSize(), .1f);
            returnButton.performClick();
            layout();
            assertNotNull(find(face, "设置"));
            state.status = "已允许穿戴车控";
            item.put("controlState", "loading");
            face.show(state, true, true);
            layout();
            assertNull(find(face, "车窗.加载，只读"));
            return null;
        });
        device.runOnMainSync(check);
        check.get();
    }

    private static void swipeUp(ScrollView grid) {
        long down = android.os.SystemClock.uptimeMillis();
        int[] actions = {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_CANCEL
        };
        int[] y = {170, 106, 31, 31};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(down, down + i * 30L, actions[i], 90, y[i], 0);
            grid.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private android.content.Context screenContext(boolean round) {
        android.content.res.Configuration cfg = new android.content.res.Configuration(activity.getResources().getConfiguration());
        cfg.screenLayout = (cfg.screenLayout & ~android.content.res.Configuration.SCREENLAYOUT_ROUND_MASK)
                | (round ? android.content.res.Configuration.SCREENLAYOUT_ROUND_YES : android.content.res.Configuration.SCREENLAYOUT_ROUND_NO);
        return activity.createConfigurationContext(cfg);
    }

    @Test public void captureAdaptiveWatchShapes() throws Exception {
        activity = (WatchActivity) device.startActivitySync(new Intent(device.getTargetContext(), WatchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            JSONObject data = previewPhoto().put("vehicle", "小银魔").put("range", "217").put("unit", "km")
                    .put("battery", "31%").put("fuel", "12.2 L").put("time", "14:05");
            state.carPhoto = WatchPhoto.decode(data);
            data.remove("carPhoto");
            state.data = data;
            state.status = "已允许穿戴车控";
            String[] names = {"车锁", "车窗", "空调", "后备箱"};
            String[] actions = {"解锁", "关窗", "开空调", "开后备箱"};
            for (int i = 0; i < names.length; i++) {
                state.actions.put(new JSONObject().put("slot", "slot" + i).put("key", "key" + i)
                        .put("controlName", names[i]).put("label", actions[i]).put("controlState", i == 1 ? "open" : "closed"));
            }
            int[][] sizes = {{480,480}, {400,400}, {336,396}, {192,490}};
            for (int index = 0; index < sizes.length; index++) {
                final int n = index, w = sizes[index][0], h = sizes[index][1];
                device.runOnMainSync(() -> {
                    face = new WatchFace(screenContext(n == 0), new WatchFace.Controls() {
                        public void refresh() {} public void prepare(JSONObject item) {} public void confirm() {}
                        public void cancel() {} public void pair() {} public void reconnect() {}
                    });
                    android.widget.FrameLayout stage = new android.widget.FrameLayout(activity);
                    stage.addView(face, new android.widget.FrameLayout.LayoutParams(w,h));
                    activity.setContentView(stage);
                    face.setLocalSolar(false, "日出 06:00 · 日落 18:00");
                    face.show(state, true, true);
                });
                device.waitForIdleSync();
                FutureTask<Void> capture = new FutureTask<>(() -> {
                    assertEquals(w,face.getWidth()); assertEquals(h,face.getHeight());
                    assertCaptionFits(face,"217"); assertCaptionFits(face,"车锁.关");
                    ScrollView scroll = (ScrollView) find(face,"车况与车控");
                    View lock = find(face,"车锁.关"), window = find(face,"车窗.开");
                    assertEquals(n < 2, lock.getTop() == window.getTop());
                    for (int page = 0; page < 2; page++) {
                        scroll.scrollTo(0, page == 0 ? 0 : lock.getTop() - (n == 0 ? 76 : n == 3 ? 72 : 22));
                        Bitmap image = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                        face.draw(new Canvas(image));
                        try (OutputStream out = activity.openFileOutput("adaptive-" + n + "-" + page + ".png",0)) {
                            assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));
                        } finally {image.recycle();}
                    }
                    return null;
                });
                device.runOnMainSync(capture); capture.get();
            }
        } finally {device.runOnMainSync(activity::finish);}
    }

    @Test public void squareAndTallScreensUseTheWholeWindow() throws Exception {
        activity = (WatchActivity) device.startActivitySync(new Intent(device.getTargetContext(), WatchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            for (int i = 0; i < 4; i++) state.actions.put(new JSONObject().put("slot", "s" + i)
                    .put("key", "k" + i).put("label", "按钮" + i).put("enabled", true));
            FutureTask<Void> test = new FutureTask<>(() -> {
                int[] calls = new int[2];
                face = new WatchFace(screenContext(false), new WatchFace.Controls() {
                    public void refresh() { calls[0]++; } public void prepare(JSONObject item) {} public void confirm() {}
                    public void cancel() {} public void pair() {} public void reconnect() { calls[1]++; }
                });
                activity.setContentView(face);
                face.show(state, true, true);
                face.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
                face.setLocalSolar(false, "日间");
                assertArtwork(face, R.drawable.watch_landscape);
                face.setLocalSolar(true, "夜间");
                assertArtwork(face, R.drawable.watch_stars);
                face.setLocalSolar(false, "日间");
                for (int[] size : new int[][] {{400, 400}, {336, 396}, {396, 336}, {192, 490}}) {
                    int w = size[0], h = size[1];
                    face.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                    face.layout(0, 0, w, h);
                    ScrollView scroll = (ScrollView) find(face, "车况与车控");
                    assertEquals(w, scroll.getWidth());
                    assertEquals(h, scroll.getHeight());
                    View first = find(face, "按钮0"), second = find(face, "按钮1");
                    assertCaptionFits(first, "按钮0");
                    if (w == h) {
                        assertEquals(first.getTop(), second.getTop());
                        assertTrue(first.getRight() < second.getLeft());
                        assertTrue(first.getLeft() < w / 10);
                    } else {
                        assertEquals(first.getLeft(), second.getLeft());
                        assertTrue(first.getBottom() < second.getTop());
                    }
                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                    Rect footer = new Rect();
                    View refresh = find(face, "刷新车况，更新时间 待更新");
                    assertTrue(refresh.getLocalVisibleRect(footer));
                    assertTrue(refresh.isClickable());
                    assertCaptionFits(refresh, "  刷新车况 · 待更新");
                    assertTrue(refresh.performClick());
                }
                assertEquals(4, calls[0]);
                face.show(state, false, true);
                assertTrue(find(face, "刷新车况，更新时间 待更新").performClick());
                assertEquals(1, calls[1]);
                return null;
            });
            device.runOnMainSync(test);
            test.get();
        } finally { device.runOnMainSync(activity::finish); }
    }

    @Test public void oddGridCentersLastTileAndLayoutSelectionPersists() throws Exception {
        activity = (WatchActivity) device.startActivitySync(new Intent(device.getTargetContext(), WatchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SharedPreferences prefs = activity.getSharedPreferences("watchTheme", android.content.Context.MODE_PRIVATE);
        String key = "controlListLayout";
        boolean existed = prefs.contains(key), previous = prefs.getBoolean(key, false);
        try {
            prefs.edit().remove(key).apply();
            FutureTask<Void> check = new FutureTask<>(() -> {
                WatchFace.Controls events = new WatchFace.Controls() {
                    public void refresh() {} public void prepare(JSONObject item) { selected = item; }
                    public void confirm() {} public void cancel() {} public void pair() {} public void reconnect() {}
                };
                for (int i = 0; i < 3; i++) state.actions.put(new JSONObject().put("slot", "s" + i)
                        .put("key", "k" + i).put("label", "按钮" + i));
                face = new WatchFace(screenContext(true), events);
                activity.setContentView(face);
                face.show(state, true, true);
                layout();
                View first = find(face, "按钮0"), second = find(face, "按钮1"), last = find(face, "按钮2");
                assertEquals(first.getTop(), second.getTop());
                assertEquals(480, last.getLeft() + last.getRight());
                assertEquals(first.getWidth(), last.getWidth());
                assertTrue(last.getTop() > first.getBottom());

                find(face, "设置").performClick();
                find(face, "车控布局 · 网格").performClick();
                assertNotNull(find(face, "网格 · 已选"));
                find(face, "列表").performClick();
                assertNotNull(find(face, "列表 · 已选"));
                assertTrue(prefs.getBoolean(key, false));
                assertTrue(face.back());
                find(face, "返回车况").performClick();
                layout();
                View firstRow = find(face, "按钮0"), secondRow = find(face, "按钮1");
                assertEquals(firstRow.getLeft(), secondRow.getLeft());
                assertTrue(firstRow.getRight() - firstRow.getLeft() > last.getWidth());
                assertTrue(secondRow.getTop() > firstRow.getBottom());
                find(face, "按钮2").performClick();
                assertEquals("s2", selected.optString("slot"));

                face = new WatchFace(screenContext(true), events);
                activity.setContentView(face);
                face.show(state, true, true);
                layout();
                assertTrue(find(face, "按钮0").getTop() < find(face, "按钮1").getTop());
                state.actions = new JSONArray().put(state.actions.getJSONObject(0));
                face.show(state, true, true);
                find(face, "设置").performClick();
                assertNull(find(face, "车控布局 · 列表"));
                return null;
            });
            device.runOnMainSync(check);
            check.get();
        } finally {
            if (existed) prefs.edit().putBoolean(key, previous).apply();
            else prefs.edit().remove(key).apply();
            device.runOnMainSync(activity::finish);
        }
    }

    @Test public void photoTracksTheCurrentSnapshotBetweenEnergyAndControls() throws Exception {
        activity = (WatchActivity) device.startActivitySync(new Intent(device.getTargetContext(), WatchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            JSONObject first = photoFixture(Bitmap.CompressFormat.PNG, 120, 48, Color.RED);
            JSONObject next = photoFixture(Bitmap.CompressFormat.WEBP, 96, 32, Color.BLUE);
            String firstId = first.getString("carPhotoId"), nextId = next.getString("carPhotoId");
            JSONObject preview = previewPhoto().put("vehicle", "小银魔").put("range", "217").put("unit", "km")
                    .put("battery", "31%").put("fuel", "12.2 L").put("condition", "已驻车").put("time", "14:05");
            FutureTask<Void> check = new FutureTask<>(() -> {
                state.actions = new JSONArray().put(new JSONObject().put("slot", "a").put("label", "按钮"));
                face = new WatchFace(screenContext(true), new WatchFace.Controls() {
                    public void refresh() {} public void prepare(JSONObject a) {} public void confirm() {}
                    public void cancel() {} public void pair() {} public void reconnect() {}
                });
                android.widget.FrameLayout fixture = new android.widget.FrameLayout(activity);
                fixture.addView(face, 480, 480);
                activity.setContentView(fixture);
                face.show(state, true, true);
                layout();
                assertNull(find(face, "默认领克车图"));
                int waitingTop = find(face, "按钮").getTop();
                showPhotoReply("", new JSONObject()); // An older phone.
                int compactTop = find(face, "按钮").getTop();
                assertTrue(compactTop > waitingTop);
                assertNull(find(face, "车模图"));
                ImageView fallback = (ImageView) find(face, "默认领克车图");
                assertNotNull(fallback);
                assertEquals(540, ((BitmapDrawable) fallback.getDrawable()).getBitmap().getWidth());
                showPhotoReply("", first);
                ImageView photo = (ImageView) find(face, "车模图");
                assertNotNull(photo);
                assertEquals(ImageView.ScaleType.FIT_CENTER, photo.getScaleType());
                assertNull(find(face, "默认领克车图"));
                assertEquals(face.getWidth(), photo.getLeft() + photo.getRight());
                assertInsideDial(photo);
                assertNull(find(face, "油量 —"));
                View energy = (View) find(face, "电量 —").getParent();
                assertTrue(energy.getBottom() < photo.getTop());
                assertEquals(164, photo.getHeight());
                assertEquals(480, find(face, "按钮").getTop());
                assertTrue(photo.getBottom() < find(face, "按钮").getTop());
                Bitmap cached = state.carPhoto;
                assertEquals(120, cached.getWidth());
                showPhotoReply(firstId, new JSONObject().put("carPhotoId", firstId));
                assertSame(cached, state.carPhoto);
                showPhotoReply(firstId, next.put("vehicle", "另一辆车"));
                assertEquals(96, state.carPhoto.getWidth());
                showPhotoReply(nextId, new JSONObject().put("carPhotoId", firstId));
                assertNull(find(face, "车模图")); // Never reuse a bitmap for a different ID.
                showPhotoReply("", first);
                showPhotoReply(firstId, new JSONObject());
                assertNull(find(face, "车模图"));
                assertNotNull(find(face, "默认领克车图"));
                assertEquals(compactTop, find(face, "按钮").getTop());
                showPhotoReply("", first);
                showPhotoReply(firstId, new JSONObject().put("carPhotoId", firstId).put("carPhoto", "malformed!"));
                assertNull(find(face, "车模图"));
                assertNull(find(face, "默认领克车图"));
                assertEquals(0, state.pollDelay(2));
                showPhotoReply("", next);
                state.offline("断开");
                assertNull(state.carPhoto);
                state.actions = new JSONArray()
                        .put(new JSONObject().put("slot", "lock").put("controlName", "车锁").put("label", "解锁").put("controlState", "closed"))
                        .put(new JSONObject().put("slot", "ac").put("controlName", "空调").put("label", "开空调").put("controlState", "closed"));
                showPhotoReply("", preview);
                return null;
            });
            device.runOnMainSync(check);
            check.get();
            device.waitForIdleSync();
            FutureTask<Void> screenshot = new FutureTask<>(() -> {
                layout();
                assertCaptionFits(face, "小银魔");
                assertCaptionFits(face, "217");
                assertCaptionFits(face, "已连接");
                assertCaptionFits(face, "车锁.关");
                java.util.ArrayList<View> parked = new java.util.ArrayList<>();
                face.findViewsWithText(parked, "已驻车", View.FIND_VIEWS_WITH_TEXT);
                assertTrue(parked.isEmpty());
                save("watch5-dashboard-photo.png");
                ScrollView scroll = (ScrollView) find(face, "车况与车控");
                scroll.scrollTo(0, find(face, "车锁.关").getTop() - 180);
                assertInsideDial(find(face, "空调.关"));
                save("watch5-photo-controls.png");
                return null;
            });
            device.runOnMainSync(screenshot);
            screenshot.get();
            device.runOnMainSync(() -> {
                find(face, "设置").performClick();
                find(face, "主题和昼夜").performClick();
                layout();
                assertCaptionFits(face, "在手机上选择主题");
                face.back();
                face.back();
                layout();
                ((ScrollView) find(face, "车况与车控")).scrollTo(0, 0);
            });
            for (boolean night : new boolean[]{false, true}) {
                device.runOnMainSync(() -> face.setLocalSolar(night, "日出 06:00 · 日落 18:00"));
                device.waitForIdleSync();
                FutureTask<Void> capture = new FutureTask<>(() -> {
                    layout();
                    assertCaptionFits(face, "217");
                    save("theme-sky" + (night ? "-night.png" : "-day.png"));
                    return null;
                });
                device.runOnMainSync(capture);
                capture.get();
                if ("true".equals(InstrumentationRegistry.getArguments().getString("motionPreview"))) {
                    for (int k = 0; k < 18; k++) {
                        android.os.SystemClock.sleep(240);
                        String frame = "motion-sky"
                                + (night ? "-night-" : "-day-") + k + ".png";
                        FutureTask<Void> snap = new FutureTask<>(() -> { save(frame); return null; });
                        device.runOnMainSync(snap);
                        snap.get();
                    }
                }
            }
            checkSolarModes();
        } finally { device.runOnMainSync(activity::finish); }
    }

    private void checkSolarModes() throws Exception {
        SharedPreferences settings = activity.getSharedPreferences("watchTheme", 0);
        FutureTask<Void> check = new FutureTask<>(() -> {
            boolean hadMode = settings.contains("manual");
            boolean manual = settings.getBoolean("manual", false);
            try {
                settings.edit().putBoolean("manual", true).apply();
                String localTimes = "日出 06:00 · 日落 18:00";
                face.setLocalSolar(false, localTimes);
                find(face, "设置").performClick();
                find(face, "主题和昼夜").performClick();
                find(face, "日出日落").performClick();
                assertNotNull(find(face, "日出  06:00"));
                assertNotNull(find(face, "日落  18:00"));
                assertNull(find(face, "自动"));
                assertNull(find(face, "定位日出日落"));
                View dial = face.getChildAt(0);
                int localBackground = ((ColorDrawable) dial.getBackground()).getColor();

                String phoneTimes = "日出 07:06 · 日落 17:12";
                JSONObject request = state.request("status", null, 3);
                state.apply(new JSONObject().put("v", 1).put("id", request.getString("id"))
                        .put("status", state.status).put("data", state.data).put("actions", state.actions)
                        .put("themeNight", true).put("solarInfo", phoneTimes), null, 4);
                face.show(state, true, true);
                assertEquals(localBackground, ((ColorDrawable) dial.getBackground()).getColor());
                find(face, "跟随手机").performClick();
                layout();
                assertCaptionFits(face, phoneTimes);
                assertNotEquals(localBackground, ((ColorDrawable) dial.getBackground()).getColor());

                state.offline("断开");
                face.show(state, false, true);
                layout();
                assertCaptionFits(face, "手机未同步 · " + localTimes);
                assertEquals(localBackground, ((ColorDrawable) dial.getBackground()).getColor());
                find(face, "用户设定").performClick();
                assertNotNull(find(face, "日出  06:00"));
                assertNotNull(find(face, "日落  18:00"));
                find(face, "返回").performClick();
                layout();
                assertCaptionFits(face, "在手机上选择主题");
                face.back();
                face.back();
            } finally {
                SharedPreferences.Editor edit = settings.edit();
                if (hadMode) edit.putBoolean("manual", manual);
                else edit.remove("manual");
                edit.apply();
            }
            return null;
        });
        device.runOnMainSync(check);
        check.get();
    }

    private void showPhotoReply(String expectedId, JSONObject data) throws Exception {
        JSONObject request = state.request("status", null, 1);
        assertEquals(expectedId, request.getString("carPhotoId"));
        JSONObject reply = new JSONObject().put("v", 1).put("id", request.getString("id"))
                .put("status", "已允许穿戴车控").put("data", new JSONObject(data.toString())).put("actions", state.actions);
        state.apply(reply, WatchPhoto.decode(data), 2);
        assertFalse(state.data.has("carPhoto"));
        face.show(state, true, true);
        layout();
    }

    @Test public void oldPhoneFallbackDoesNotDeleteCachedArtwork() throws Exception {
        android.content.Context target = device.getTargetContext();
        File temporary = java.nio.file.Files.createTempDirectory(target.getCacheDir().toPath(), "watch-theme-").toFile();
        android.content.Context isolated = new android.content.ContextWrapper(target) {
            @Override public File getFilesDir() { return temporary; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(temporary.getName() + name, mode);
            }
        };
        byte[] artwork;
        try (InputStream in = target.getResources().openRawResource(R.drawable.watch_landscape);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int size; (size = in.read(buffer)) != -1;) out.write(buffer, 0, size);
            artwork = out.toByteArray();
        }
        String hash = com.mario.lynkband.solar.ThemeArt.digest(artwork);
        JSONObject oldOffer = new JSONObject().put("key", hash).put("title", "海岸灯塔").put("day", hash).put("night", hash);
        File file = new File(temporary, "visual-theme.dat");
        new com.mario.lynkband.solar.ThemeArt.Store(file).write(oldOffer, artwork, artwork);
        try {
            WatchThemes themes = new WatchThemes(isolated);
            assertEquals("coast", themes.scene());
            themes.acceptOffer(new JSONObject().put("landscapeSync", new JSONObject().put("scene", "COAST")));
            assertEquals("海岸灯塔", themes.title());
            assertNotNull(themes.preview(false));
            themes.acceptOffer(new JSONObject().put("landscapeSync", new JSONObject().put("scene", "RIDGE")));
            assertTrue(themes.waitingForLegacyArt());
            assertNull(themes.preview(false));
            assertTrue(file.isFile());
            themes = new WatchThemes(isolated);
            assertNull(themes.preview(false));
            themes.acceptOffer(new JSONObject().put("remoteTheme", oldOffer.put("scene", "coast")));
            assertEquals("海岸灯塔", themes.title());
            assertNotNull(themes.preview(true));
            themes.acceptOffer(new JSONObject().put("remoteTheme", JSONObject.NULL));
            assertNull(themes.preview(false));
            assertFalse(file.exists());
        } finally {
            new com.mario.lynkband.solar.ThemeArt.Store(file).delete();
            assertTrue(temporary.delete());
        }
    }

    @Test public void decoderRejectsWrongDigestDimensionsFormatAndSize() throws Exception {
        JSONObject valid = photoFixture(Bitmap.CompressFormat.PNG, 120, 48, Color.GREEN);
        assertEquals(120, WatchPhoto.decode(valid).getWidth());
        assertEquals(480, WatchPhoto.decode(photoFixture(Bitmap.CompressFormat.WEBP, 480, 160, Color.GREEN)).getWidth());
        assertNull(WatchPhoto.decode(valid.put("carPhotoId", new String(new char[64]).replace('\0', 'a'))));
        assertNull(WatchPhoto.decode(photoFixture(Bitmap.CompressFormat.PNG, 481, 16, Color.GREEN)));
        assertNull(WatchPhoto.decode(photoFixture(Bitmap.CompressFormat.PNG, 32, 241, Color.GREEN)));
        assertNull(WatchPhoto.decode(photoFixture(Bitmap.CompressFormat.JPEG, 120, 48, Color.GREEN)));
        assertNull(WatchPhoto.decode(wirePhoto(new byte[16385])));
    }

    private static JSONObject photoFixture(Bitmap.CompressFormat format, int width, int height, int color) throws Exception {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        image.eraseColor(color);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(image.compress(format, 95, out));
        image.recycle();
        return wirePhoto(out.toByteArray());
    }

    private JSONObject previewPhoto() throws Exception {
        String pkg = "com.lynkco.customer";
        try {
            android.content.res.Resources res = device.getContext().getPackageManager().getResourcesForApplication(pkg);
            for (String name : new String[]{"bc_car_side"}) {
                int id = res.getIdentifier(name, "mipmap", pkg);
                if (id == 0) continue;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                Bitmap original = BitmapFactory.decodeResource(res, id, options);
                if (original == null) continue;
                Rect crop = new Rect();
                int[] row = new int[original.getWidth()];
                for (int y = 0; y < original.getHeight(); y++) {
                    original.getPixels(row, 0, row.length, 0, y, row.length, 1);
                    for (int x = 0; x < row.length; x++) if (Color.alpha(row[x]) > 16) crop.union(x, y, x + 1, y + 1);
                }
                if (crop.isEmpty()) continue;
                Bitmap visible = Bitmap.createBitmap(original, crop.left, crop.top, crop.width(), crop.height());
                float scale = Math.min(1f, Math.min(256f / visible.getWidth(), 128f / visible.getHeight()));
                Bitmap thumb = Bitmap.createScaledBitmap(visible, Math.max(1, Math.round(visible.getWidth() * scale)),
                        Math.max(1, Math.round(visible.getHeight() * scale)), true);
                for (int quality : new int[]{86, 65, 45}) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (thumb.compress(Bitmap.CompressFormat.WEBP, quality, out) && out.size() <= 8192) {
                        android.util.Log.i("WristLynkTest", "Preview vehicle from installed model " + name);
                        return wirePhoto(out.toByteArray());
                    }
                }
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            // The watch need not have the phone application installed.
        }
        try (InputStream stream = device.getContext().getAssets().open("vehicle-fixture.webp")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            for (int n; (n = stream.read(buffer)) >= 0;) bytes.write(buffer, 0, n);
            android.util.Log.i("WristLynkTest", "Preview vehicle from standalone test fixture");
            return wirePhoto(bytes.toByteArray());
        }
    }

    private static JSONObject wirePhoto(byte[] bytes) throws Exception {
        StringBuilder id = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            id.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return new JSONObject().put("carPhotoId", id.toString()).put("carPhoto", Base64.encodeToString(bytes, Base64.NO_WRAP));
    }

    private void layout() {
        face.measure(
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        face.layout(0, 0, 480, 480);
    }

    private void save(String file) throws IOException {
        Bitmap image = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888);
        face.draw(new Canvas(image));
        for (int y = 0; y < 480; y += 12)
            for (int x = 0; x < 480; x += 12) {
                if (Math.hypot(x - 240, y - 240) > 242)
                    assertEquals(Color.BLACK, image.getPixel(x, y));
            }
        try (OutputStream out = activity.openFileOutput(file, 0)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }

    private static void assertCaptionFits(View root, String caption) {
        java.util.ArrayList<View> matches = new java.util.ArrayList<>();
        root.findViewsWithText(matches, caption, View.FIND_VIEWS_WITH_TEXT);
        assertEquals(caption, 1, matches.size());
        TextView text = (TextView) matches.get(0);
        assertEquals(caption, text.getText().toString());
        assertTrue(text.isShown());
        assertEquals(0, text.getLayout().getEllipsisCount(0));
    }

    private static View find(View v, String label) {
        if (label.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()))
            return v;
        if (v instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = find(group.getChildAt(i), label);
                if (child != null) return child;
            }
        return null;
    }

    private void assertInsideDial(View v) {
        assertNotNull(v);
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        face.offsetDescendantRectToMyCoords(v, r);
        for (int x : new int[] {r.left, r.right})
            for (int y : new int[] {r.top, r.bottom})
                assertTrue(r.toString(), Math.hypot(x - 240, y - 240) <= 240);
    }

    private static void assertArtwork(View root, int expectedResource) {
        assertNotNull(root);
        ImageView image = findImage(root);
        assertNotNull(image);
        Bitmap expected = ((BitmapDrawable) root.getContext().getDrawable(expectedResource)).getBitmap();
        Bitmap actual = ((BitmapDrawable) image.getDrawable()).getBitmap();
        assertTrue("Expected bundled " + root.getResources().getResourceEntryName(expectedResource), expected.sameAs(actual));
    }

    private static ImageView findImage(View v) {
        if (v instanceof ImageView image) return image;
        if (v instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageView child = findImage(group.getChildAt(i));
                if (child != null) return child;
            }
        return null;
    }
}
