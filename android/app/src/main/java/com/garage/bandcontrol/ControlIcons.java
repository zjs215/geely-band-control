package com.garage.bandcontrol;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;

final class ControlIcons {
    static final int MAX_SIDE = 64;
    static final int MAX_CHUNKS = 8;
    private static final int NIGHT_INK = Color.rgb(0xEE, 0xF7, 0xF5);
    private static final int DAY_INK = Color.rgb(0x15, 0x2C, 0x3C);

    private ControlIcons() {}

    static CarImage.Packaged extract(View view, Boolean themeDay) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Bitmap source = CarImage.captureSource(view);
        if (source == null) return null;
        try {
            Bitmap cropped = CarImage.trimTransparent(source, 8, 8);
            Bitmap subject = cropped != null ? cropped : source;
            Bitmap scaled = CarImage.scaleDown(subject, MAX_SIDE, MAX_SIDE);
            if (scaled == null) return null;
            try {
                Bitmap adapted = adaptContrast(scaled, themeDay);
                try {
                    byte[] png = CarImage.toPng(adapted);
                    return CarImage.packagePng(png, MAX_CHUNKS);
                } finally {
                    if (adapted != scaled) adapted.recycle();
                }
            } finally {
                if (scaled != subject) scaled.recycle();
                if (cropped != null) cropped.recycle();
            }
        } finally {
            source.recycle();
        }
    }

    static Bitmap adaptContrast(Bitmap source, Boolean themeDay) {
        if (themeDay == null) return source;
        int w = source.getWidth();
        int h = source.getHeight();
        int[] pixels = new int[w * h];
        source.getPixels(pixels, 0, w, 0, 0, w, h);
        long alphaSum = 0;
        long lumSum = 0;
        long chromaSum = 0;
        int visible = 0;
        for (int pixel : pixels) {
            int a = Color.alpha(pixel);
            if (a <= 24) continue;
            int r = Color.red(pixel);
            int g = Color.green(pixel);
            int b = Color.blue(pixel);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int lum = (r * 30 + g * 59 + b * 11) / 100;
            alphaSum += a;
            lumSum += (long) lum * a;
            chromaSum += (long) (max - min) * a;
            visible++;
        }
        if (visible == 0 || alphaSum == 0) return source;
        int avgLum = (int) (lumSum / alphaSum);
        int avgChroma = (int) (chromaSum / alphaSum);
        if (avgChroma > 24) return source;

        int targetRgb;
        if (!themeDay && avgLum < 115) targetRgb = NIGHT_INK;
        else if (themeDay && avgLum > 165) targetRgb = DAY_INK;
        else return source;

        int tr = Color.red(targetRgb);
        int tg = Color.green(targetRgb);
        int tb = Color.blue(targetRgb);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < pixels.length; i++) {
            int a = Color.alpha(pixels[i]);
            pixels[i] = a == 0 ? 0 : Color.argb(a, tr, tg, tb);
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }
}
