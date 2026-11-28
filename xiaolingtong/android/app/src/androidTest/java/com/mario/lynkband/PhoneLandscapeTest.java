package com.mario.lynkband;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PhoneLandscapeTest {
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();
    private final Context target = device.getTargetContext();
    private final SharedPreferences prefs = PhoneLandscape.preferences(target);

    @Test public void launcherRendersAllLandscapePairs() throws Exception {
        Map<String, ?> original = prefs.getAll();
        try {
            for (PhoneLandscape scene : PhoneLandscape.values()) {
                for (boolean night : new boolean[]{false, true}) {
                    // Put both boundaries well away from the current time, including around midnight.
                    int now = LocalTime.now().toSecondOfDay() / 60;
                    int before = (now + 1440 - 360) % 1440, after = (now + 360) % 1440;
                    prefs.edit().putBoolean("manual", true).putInt("riseMinute", night ? after : before)
                            .putInt("setMinute", night ? before : after).commit();
                    scene.select(prefs);
                    capture(scene, night);
                }
            }
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            for (String key : new String[]{"landscape", "manual", "riseMinute", "setMinute"}) {
                Object value = original.get(key);
                if (value instanceof String text) restore.putString(key, text);
                else if (value instanceof Boolean flag) restore.putBoolean(key, flag);
                else if (value instanceof Integer minute) restore.putInt(key, minute);
                else restore.remove(key);
            }
            restore.commit();
        }
    }

    private void capture(PhoneLandscape scene, boolean night) throws Exception {
        Intent launcher = target.getPackageManager().getLaunchIntentForPackage(target.getPackageName());
        assertNotNull(launcher);
        MainActivity activity = (MainActivity) device.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            device.waitForIdleSync();
            CompletableFuture<Integer> copied = new CompletableFuture<>();
            FutureTask<Bitmap> snapshot = new FutureTask<>(() -> {
                int expectedMode = night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
                assertEquals(expectedMode, activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
                assertEquals(expectedMode, new AlertDialog.Builder(activity).getContext().getResources()
                        .getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
                ImageView hero = activity.findViewById(R.id.phone_landscape_image);
                Rect visible = new Rect();
                assertTrue(hero.getGlobalVisibleRect(visible));
                assertEquals(hero.getHeight(), visible.height());
                assertTrue(hero.getWidth() > 0 && hero.getHeight() > 0);
                Bitmap expected = ((BitmapDrawable) activity.getDrawable(scene.image(night))).getBitmap();
                assertTrue(((BitmapDrawable) hero.getDrawable()).getBitmap().sameAs(expected));
                View screen = activity.findViewById(android.R.id.content);
                android.widget.ScrollView scroll = activity.findViewById(R.id.phone_scroll);
                assertEquals(screen.getWidth(), hero.getWidth());
                assertEquals(Math.round(screen.getHeight() * 2f / 3f), hero.getHeight());
                assertEquals(ImageView.ScaleType.CENTER_CROP, hero.getScaleType());
                int[] before = new int[2], after = new int[2];
                hero.getLocationInWindow(before);
                scroll.scrollTo(0, scroll.getHeight());
                assertTrue(scroll.getScrollY() > 0);
                hero.getLocationInWindow(after);
                assertArrayEquals(before, after);
                scroll.scrollTo(0, 0);
                int[] position = new int[2];
                screen.getLocationInWindow(position);
                Rect bounds = new Rect(position[0], position[1], position[0] + screen.getWidth(), position[1] + screen.getHeight());
                Bitmap image = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
                PixelCopy.request(activity.getWindow(), bounds, image, copied::complete, new Handler(Looper.getMainLooper()));
                return image;
            });
            device.runOnMainSync(snapshot);
            Bitmap image = snapshot.get();
            assertEquals(PixelCopy.SUCCESS, copied.get(10, TimeUnit.SECONDS).intValue());
            // Sample the gutter outside the scrolling cards, well below the fade.
            assertEquals(scene.baseColor(night), image.getPixel(2, image.getHeight() * 4 / 5));
            assertEquals(scene.baseColor(night), image.getPixel(2, image.getHeight() * 9 / 10));
            File directory = target.getExternalFilesDir("phone-theme");
            assertNotNull(directory);
            String name = "phone-" + scene.name().toLowerCase(Locale.ROOT) + (night ? "-night.png" : "-day.png");
            try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { image.recycle(); }
        } finally {
            device.runOnMainSync(activity::finish);
            device.waitForIdleSync();
        }
    }
}
