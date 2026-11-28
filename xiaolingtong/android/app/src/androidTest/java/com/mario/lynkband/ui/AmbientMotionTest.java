package com.mario.lynkband.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.FutureTask;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class AmbientMotionTest {
    private static final String[] THEMES = {"sky", "ridge", "city", "coast", "alpine", "bamboo",
            "prism", "circuit", "paw", "flux", "origami", "tape", "hamster", "enamel"};
    private static final int EDGE = 336;

    @Test
    public void eachThemeRendersAndAnimatesItsOwnOverlayInDayAndNight() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var check = new FutureTask<Void>(this::verifyOverlays, null);
        instrumentation.runOnMainSync(check);
        check.get();
    }

    private void verifyOverlays() {
        AmbientMotion motion = new AmbientMotion(InstrumentationRegistry.getInstrumentation().getTargetContext());
        for (boolean night : new boolean[]{false, true}) {
            Set<Integer> rendered = new HashSet<>();
            for (String theme : THEMES) {
                motion.setScene(theme, night);
                int[] first = frame(motion, 1.7);
                int[] next = frame(motion, 3.2);
                int changed = 0;
                for (int index = 0; index < first.length; index++) {
                    if (first[index] != next[index]) changed++;
                }
                assertTrue(theme + " must visibly change over time", changed > 60);
                assertTrue(theme + " must draw its own overlay", rendered.add(Arrays.hashCode(first)));
            }
        }
    }

    private static int[] frame(AmbientMotion motion, double seconds) {
        Bitmap bitmap = Bitmap.createBitmap(EDGE, EDGE, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(EDGE / 960f, EDGE / 960f);
            motion.drawScene(canvas, seconds);
            int[] pixels = new int[EDGE * EDGE];
            bitmap.getPixels(pixels, 0, EDGE, 0, 0, EDGE, EDGE);
            return pixels;
        } finally {
            bitmap.recycle();
        }
    }
}
