package com.mario.lynkband;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mario.lynkband.transport.WatchIconCodec;
import java.util.Base64;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BandIconCodecTest {
    @Test public void cropsTransparentPaddingAndKeepsNativeGlyphProportions() {
        Bitmap source = Bitmap.createBitmap(140, 120, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(source);
            Paint ink = new Paint();
            ink.setColor(Color.GREEN);
            canvas.drawRect(20, 20, 110, 65, ink);
            assertPng(source, 72, 36);
            source.eraseColor(Color.TRANSPARENT);
            canvas.drawRect(20, 20, 60, 40, ink);
            assertPng(source, 40, 20);
            source.eraseColor(Color.TRANSPARENT);
            canvas.drawRect(20, 20, 51, 36, ink);
            assertEquals("", WatchIconCodec.encode(source, WatchIconCodec.Profile.BAND));
        } finally { source.recycle(); }
    }

    @Test public void lowersResolutionToMeetBudgetAndSkipsGlyphsThatWouldBecomeBlurry() {
        Bitmap source = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
        try {
            var random = new Random(0x4c796e6bL);
            // Alternating solid bands leave some detail after resizing but fit the byte budget.
            int[] colors = new int[72 * 72];
            for (int x = 0; x < 72; x++) {
                int color = 0xff000000 | random.nextInt(1 << 24);
                for (int y = 0; y < 72; y++) colors[y * 72 + x] = color;
            }
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 72; x++) colors[y * 72 + x] = 0xff000000 | random.nextInt(1 << 24);
            }
            source.setPixels(colors, 0, 72, 0, 0, 72, 72);
            Bitmap fitted = bandPng(source);
            try {
                assertTrue(fitted.getWidth() >= 32 && fitted.getWidth() < 72);
                assertEquals(fitted.getWidth(), fitted.getHeight());
            } finally { fitted.recycle(); }
            for (int i = 0; i < colors.length; i++) colors[i] = 0xff000000 | random.nextInt(1 << 24);
            source.setPixels(colors, 0, 72, 0, 0, 72, 72);
            assertEquals("", WatchIconCodec.encode(source, WatchIconCodec.Profile.BAND));
            assertFalse(WatchIconCodec.encode(source, WatchIconCodec.Profile.LEGACY).isEmpty());
        } finally { source.recycle(); }
    }

    private static void assertPng(Bitmap source, int width, int height) {
        Bitmap png = bandPng(source);
        try {
            assertEquals(width, png.getWidth());
            assertEquals(height, png.getHeight());
            assertEquals(Color.GREEN, png.getPixel(0, 0));
        } finally { png.recycle(); }
    }

    private static Bitmap bandPng(Bitmap source) {
        String encoded = WatchIconCodec.encode(source, WatchIconCodec.Profile.BAND);
        assertTrue(encoded.startsWith("iVBORw0KGgo") && encoded.length() <= 2048);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        assertTrue(bytes.length <= 1536);
        Bitmap png = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(png);
        return png;
    }
}
