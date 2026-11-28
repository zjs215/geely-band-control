package com.mario.lynkband;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** The real 4.3.1 RemoteViews layout, populated locally with a transparent bitmap fixture. */
@RunWith(AndroidJUnit4.class)
public final class OfficialVehiclePhotoTest {
    private final VehiclePhoto extractor = new VehiclePhoto(VehiclePhoto.Target.WATCH);
    private final VehiclePhoto band = new VehiclePhoto(VehiclePhoto.Target.BAND);
    private Resources resources;
    private View root;
    private ImageView car;

    @Before public void inflateOfficialCard() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try { resources = context.getPackageManager().getResourcesForApplication(LynkWidget.OWNER); }
        catch (PackageManager.NameNotFoundException missing) { assumeTrue("Install the China Lynk 4.3.1 APK", false); }
        int layout = resources.getIdentifier("layout_widget", "layout", LynkWidget.OWNER);
        assertNotEquals(0, layout);
        root = new RemoteViews(LynkWidget.OWNER, layout).apply(context, new FrameLayout(context));
        car = (ImageView) view("car_img");
    }

    private View view(String name) {
        View found = root.findViewById(resources.getIdentifier(name, "id", LynkWidget.OWNER));
        assertNotNull(name, found);
        return found;
    }

    private void live() {
        view("rl_widget_state").setVisibility(View.GONE);
        view("rl_widget_cal_state").setVisibility(View.VISIBLE);
        ((TextView) view("widget_name")).setText("测试车辆");
    }

    private Bitmap sample() {
        Bitmap bitmap = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setColor(Color.GREEN);
        new Canvas(bitmap).drawRect(100, 100, 700, 200, paint);
        return bitmap;
    }

    @Test public void exportsOnlyCurrentImageLayerAndReusesEncodingUntilPixelsChange() throws Exception {
        live();
        Bitmap source = sample();
        car.setBackgroundColor(Color.RED);
        car.setImageBitmap(source);
        VehiclePhoto.Image image = extractor.read(root, "fixture");
        assertNotNull(image);
        assertSame(image, extractor.read(root, "fixture"));
        byte[] payload = Base64.decode(image.base64(), Base64.DEFAULT);
        assertEquals(Base64.encodeToString(payload, Base64.NO_WRAP), image.base64());
        assertTrue(payload.length <= 8192);
        assertEquals(String.format(Locale.ROOT, "%064x", new BigInteger(1,
                MessageDigest.getInstance("SHA-256").digest(payload))), image.id());
        Bitmap decoded = BitmapFactory.decodeByteArray(payload, 0, payload.length);
        assertEquals(256, decoded.getWidth());
        assertEquals(96, decoded.getHeight());
        assertEquals(0, Color.alpha(decoded.getPixel(0, 0))); // Neither the view background nor the card was drawn.
        assertTrue(Color.green(decoded.getPixel(128, 48)) > 240);
        source.eraseColor(Color.BLUE);
        VehiclePhoto.Image updated = extractor.read(root, "fixture");
        assertNotNull(updated);
        assertNotEquals(image.id(), updated.id());
    }

    @Test public void skipsProviderSilhouetteEmptyAndHiddenImagesAndIndependentControls() {
        assertNull(extractor.read(root, "fixture")); // As initially inflated (logged out).
        live();
        assertNotNull(car.getDrawable());
        assertNull(extractor.read(root, "fixture")); // Visible default @mipmap/car_side_line.
        ((ImageView) view("unlock")).setImageBitmap(sample());
        car.setImageDrawable(null);
        assertNull(extractor.read(root, "fixture"));
        car.setImageBitmap(Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888));
        assertNull(extractor.read(root, "fixture"));
        car.setImageBitmap(sample());
        assertNotNull(extractor.read(root, "fixture"));
        car.setVisibility(View.INVISIBLE);
        assertNull(extractor.read(root, "fixture"));
        car.setVisibility(View.VISIBLE);
        for (String overlay : new String[]{"verify", "widget_hint", "ll_loading_auth"}) {
            view(overlay).setVisibility(View.VISIBLE);
            assertNull(overlay, extractor.read(root, "fixture"));
            view(overlay).setVisibility(View.GONE);
        }
        view("rl_widget_cal_state").setVisibility(View.GONE);
        assertNull(extractor.read(root, "fixture"));
    }

    @Test public void bandPngPreservesAlphaAndNeverReusesAPreviousVehiclesBitmap() {
        live();
        Bitmap source = sample();
        car.setImageBitmap(source);
        VehiclePhoto.Image image = band.read(root, "binding");
        assertNotNull(image);
        assertSame(image, band.read(root, "binding"));
        byte[] payload = Base64.decode(image.base64(), Base64.NO_WRAP);
        BitmapFactory.Options options = new BitmapFactory.Options();
        Bitmap decoded = BitmapFactory.decodeByteArray(payload, 0, payload.length, options);
        assertEquals("image/png", options.outMimeType);
        assertEquals(160, decoded.getWidth());
        assertEquals(60, decoded.getHeight());
        assertEquals(Color.TRANSPARENT, decoded.getPixel(0, 0));
        assertEquals(Color.GREEN, decoded.getPixel(80, 30));
        assertNull(band.read(root, "different-binding"));
        ((TextView) view("widget_name")).setText("另一辆车");
        assertNull(band.read(root, "binding"));
        source.eraseColor(Color.BLUE);
        VehiclePhoto.Image updated = band.read(root, "binding");
        assertNotNull(updated);
        assertNotEquals(image.id(), updated.id());
        car.setVisibility(View.INVISIBLE);
        assertNull(band.read(root, "binding"));
    }

    @Test public void highEntropySourceFitsBothTransferBudgets() {
        live();
        int[] pixels = new int[256 * 128];
        Random random = new Random(123);
        for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
        car.setImageBitmap(Bitmap.createBitmap(pixels, 256, 128, Bitmap.Config.ARGB_8888));
        VehiclePhoto.Image image = extractor.read(root, "fixture");
        assertNotNull(image);
        byte[] payload = Base64.decode(image.base64(), Base64.DEFAULT);
        assertTrue(payload.length <= 8192);
        Bitmap decoded = BitmapFactory.decodeByteArray(payload, 0, payload.length);
        assertTrue(decoded.getWidth() <= 256);
        assertTrue(decoded.getHeight() <= 128);
        VehiclePhoto.Image png = band.read(root, "fixture");
        assertNotNull(png);
        assertTrue(png.base64().length() <= BandPhoto.MAX_CHARS);
    }
}
