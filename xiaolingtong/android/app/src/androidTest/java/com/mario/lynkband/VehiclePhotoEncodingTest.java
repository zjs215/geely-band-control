package com.mario.lynkband;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class VehiclePhotoEncodingTest {
    @Test public void hdProfilesTrimTransparentPaddingAndPreserveMoreVehiclePixels() {
        Bitmap source = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setColor(Color.GREEN);
        new Canvas(source).drawRect(100, 100, 700, 200, paint);
        try {
            assertPhoto(source, VehiclePhoto.Target.WATCH, 256, false);
            assertPhoto(source, VehiclePhoto.Target.BAND, 160, false);
            assertPhoto(source, VehiclePhoto.Target.WATCH_HD, 480, true);
            assertPhoto(source, VehiclePhoto.Target.BAND_HD, 288, true);
        } finally { source.recycle(); }
    }

    private static void assertPhoto(Bitmap source, VehiclePhoto.Target profile, int width, boolean trimmed) {
        VehiclePhoto.Image photo = new VehiclePhoto(profile).encode(source);
        assertNotNull(photo);
        byte[] payload = Base64.decode(photo.base64(), Base64.DEFAULT);
        assertTrue(payload.length <= profile.budget);
        Bitmap decoded = BitmapFactory.decodeByteArray(payload, 0, payload.length);
        assertNotNull(decoded);
        try {
            assertEquals(width, decoded.getWidth());
            assertEquals(0, Color.alpha(decoded.getPixel(0, 0)));
            int x = decoded.getWidth() / 16;
            int y = decoded.getHeight() / 2;
            assertEquals(trimmed ? 255 : 0, Color.alpha(decoded.getPixel(x, y)));
        } finally { decoded.recycle(); }
    }
}
