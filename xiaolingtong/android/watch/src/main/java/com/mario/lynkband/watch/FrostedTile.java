package com.mario.lynkband.watch;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.util.Random;

/** Static glass texture shared by the tiles, including while their icons animate. */
final class FrostedTile extends Drawable {
    private static final Bitmap GRAIN = grain();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texture = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF();
    private final int[] colors;
    private final float radius;
    private final int[] edgeColors;

    FrostedTile(boolean dark, float radius, float stroke) {
        this.radius = radius;
        colors = dark ? new int[]{0x696c839c, 0x42203750, 0x60253548}
                : new int[]{0x9ef5fbff, 0x68deedf4, 0x82e1edf5};
        edgeColors = new int[]{0xceffffff, 0x28ffffff, 0x22546d86, 0x88daeefb};
        texture.setShader(new BitmapShader(GRAIN, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
    }

    private static Bitmap grain() {
        Random random = new Random(17);
        int[] pixels = new int[48 * 48];
        for (int i = 0; i < pixels.length; i++) {
            int tone = random.nextBoolean() ? 255 : 0;
            pixels[i] = Color.argb(random.nextInt(6), tone, tone, tone);
        }
        return Bitmap.createBitmap(pixels, 48, 48, Bitmap.Config.ARGB_8888);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        panel.set(bounds);
        panel.inset(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2);
        if (!panel.isEmpty()) {
            fill.setShader(new LinearGradient(panel.left, panel.top, panel.centerX(), panel.bottom,
                    colors, new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
                    edgeColors, new float[]{0f, .44f, .67f, 1f}, Shader.TileMode.CLAMP));
        }
    }

    @Override public void draw(Canvas canvas) {
        canvas.drawRoundRect(panel, radius, radius, fill);
        canvas.drawRoundRect(panel, radius, radius, texture);
        canvas.drawRoundRect(panel, radius, radius, edge);
    }

    @Override public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        texture.setAlpha(alpha);
        edge.setAlpha(alpha);
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter);
        texture.setColorFilter(filter);
        edge.setColorFilter(filter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
