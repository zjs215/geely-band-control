package com.mario.lynkband.ui;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

/** Transparent decorative layer for the shared square landscape illustrations. */
public final class AmbientMotion extends View {
    private enum Scene { SKY, RIDGE, CITY, COAST, ALPINE, BAMBOO, PRISM, CIRCUIT, PAW, FLUX,
        ORIGAMI, TAPE, HAMSTER, ENAMEL, GENERIC }

    private static final float ART_SIZE = 960f;
    // Bright star centers in watch_stars.webp, in original artwork pixels.
    private static final int[] STARS = {
            82,191, 202,465, 311,296, 452,230, 614,308, 705,280, 704,479, 204,110
    };
    private static final int[] CIRCUIT_X = {45, 184, 245, 358, 434, 542, 616, 794, 890};
    private static final int[] CIRCUIT_Y = {688, 688, 633, 633, 702, 702, 574, 574, 510};
    private static final int[] FACET_DAY = {0xff53e3cb, 0xff7058ef, 0xffdbf55f};
    private static final int[] FACET_NIGHT = {0xff32cbbd, 0xffa069f5, 0xffff78b3};
    private static final int[] RIBBON_COLORS = {0xff49dfff, 0xfffd64b1, 0xffff995c};
    // Offline samples from watch_city_night.webp. Each exclusive-right/bottom rectangle
    // bounds a connected group of warm lit pixels (R > 112, G > 78, R - B > 24).
    private static final int[] WINDOWS = {
            50,452,54,455,    88,473,93,478,    130,447,134,450,  182,463,186,467,
            241,467,246,470,  301,449,306,454,  367,505,370,508,  424,474,428,477,
            465,477,470,480,  510,456,513,459,  586,483,589,486,  645,488,648,492,
            732,448,735,451,  718,498,722,501,  769,467,774,470,  852,441,855,444,
            900,469,905,473,  922,508,927,512
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trail = new Path();
    private final Rect visible = new Rect();
    private final RadialGradient sunlight = glow(0xffffd892);
    private final RadialGradient starlight = glow(0xffc4e4ff);
    private final RadialGradient ember = glow(0xffff843a);
    private final RadialGradient aqua = glow(0xff4bf1ed);
    private final PowerManager power;
    private final int frameMs;
    private Scene scene = Scene.SKY;
    private boolean night, centerCrop, listening, policyAllowsMotion, scheduled;
    private long elapsedMs, lastFrameMs;

    public AmbientMotion(Context context) {
        super(context);
        power = context.getSystemService(PowerManager.class);
        boolean watch = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        frameMs = watch ? 125 : 84; // At most 8 fps on watch and 12 fps on phone.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setScene(String scene, boolean night) {
        Scene next = Scene.GENERIC;
        for (Scene candidate : Scene.values()) {
            if (candidate.name().equalsIgnoreCase(scene)) {
                next = candidate;
                break;
            }
        }
        if (this.scene == next && this.night == night) return;
        this.scene = next;
        this.night = night;
        elapsedMs = 0;
        invalidate();
    }

    /** Match the underlying ImageView's CENTER_CROP, or stretch to the view by default. */
    public void setCenterCrop(boolean centerCrop) {
        if (this.centerCrop == centerCrop) return;
        this.centerCrop = centerCrop;
        invalidate();
    }

    private final BroadcastReceiver powerChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshPolicy(); }
    };
    private final ContentObserver scaleChanged = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refreshPolicy(); }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        listening = true;
        IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        getContext().registerReceiver(powerChanged, filter);
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleChanged);
        getViewTreeObserver().addOnPreDrawListener(beforeDraw);
        refreshPolicy();
    }

    @Override protected void onDetachedFromWindow() {
        listening = false;
        stopFrames();
        getViewTreeObserver().removeOnPreDrawListener(beforeDraw);
        getContext().getContentResolver().unregisterContentObserver(scaleChanged);
        getContext().unregisterReceiver(powerChanged);
        super.onDetachedFromWindow();
    }

    private void refreshPolicy() {
        if (!listening) return;
        policyAllowsMotion = power != null && power.isInteractive() && !power.isPowerSaveMode()
                && Settings.Global.getFloat(getContext().getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        if (!policyAllowsMotion) stopFrames();
        invalidate();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (!visible) stopFrames();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) stopFrames();
        else if (listening) refreshPolicy();
    }

    private boolean canAnimate() {
        if (!listening || !policyAllowsMotion || !ValueAnimator.areAnimatorsEnabled()
                || !hasWindowFocus() || getWindowVisibility() != VISIBLE || !isShown()) return false;
        Display display = getDisplay();
        if (display == null || display.getState() != Display.STATE_ON || !getGlobalVisibleRect(visible)) return false;
        for (View view = this; ; ) {
            if (view.getAlpha() <= 0f) return false;
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) return true;
            view = (View) parent;
        }
    }

    // A pre-draw from scrolling, layout or a visibility change can restart an idle layer.
    // The layer itself wakes only while visible, with at most one delayed callback pending.
    private final ViewTreeObserver.OnPreDrawListener beforeDraw = this::scheduleIfVisible;

    private boolean scheduleIfVisible() {
        if (!canAnimate()) stopFrames();
        else if (!scheduled) {
            scheduled = true;
            if (lastFrameMs == 0) lastFrameMs = SystemClock.uptimeMillis();
            int delay = night && scene == Scene.CITY ? 200 : frameMs;
            postDelayed(frame, delay);
        }
        return true;
    }

    private final Runnable frame = () -> {
        scheduled = false;
        if (!canAnimate()) {
            lastFrameMs = 0;
            return;
        }
        long now = SystemClock.uptimeMillis();
        elapsedMs += Math.min(now - lastFrameMs, 250L);
        lastFrameMs = now;
        invalidate();
    };

    private void stopFrames() {
        if (scheduled) removeCallbacks(frame);
        scheduled = false;
        lastFrameMs = 0;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!policyAllowsMotion || !ValueAnimator.areAnimatorsEnabled()) return;
        float sx = getWidth() / ART_SIZE, sy = getHeight() / ART_SIZE;
        int save = canvas.save();
        if (centerCrop) {
            float scale = Math.max(sx, sy);
            canvas.translate(Math.round((getWidth() - ART_SIZE * scale) * .5f),
                    Math.round((getHeight() - ART_SIZE * scale) * .5f));
            sx = sy = scale;
        }
        canvas.scale(sx, sy);
        drawScene(canvas, elapsedMs / 1000.0);
        canvas.restoreToCount(save);
    }

    void drawScene(Canvas c, double t) {
        switch (scene) {
            case SKY, GENERIC -> {
                if (night) drawStars(c, t, STARS);
                else drawClouds(c, t);
            }
            case RIDGE -> drawEmbers(c, t);
            case CITY -> drawCity(c, t);
            case COAST -> drawBeacon(c, t);
            case ALPINE -> drawSnow(c, t);
            case BAMBOO -> drawLeaves(c, t);
            case PRISM -> drawPrism(c, t);
            case CIRCUIT -> drawCircuit(c, t);
            case PAW -> drawPaws(c, t);
            case FLUX -> drawRibbons(c, t);
            case ORIGAMI -> drawPaperFolds(c, t);
            case TAPE -> drawTapeReels(c, t);
            case HAMSTER -> drawEnvelopes(c, t);
            case ENAMEL -> drawMineralGrains(c, t);
        }
    }

    private static RadialGradient glow(int color) {
        return new RadialGradient(0, 0, 1, color, color & 0x00ffffff, Shader.TileMode.CLAMP);
    }

    private void halo(Canvas c, RadialGradient light, float x, float y, float rx, float ry, int alpha) {
        int save = c.save();
        c.translate(x, y);
        c.scale(rx, ry);
        paint.setColor(0xffffffff);
        paint.setShader(light);
        paint.setAlpha(alpha);
        c.drawCircle(0, 0, 1, paint);
        paint.setShader(null);
        c.restoreToCount(save);
    }

    private static float wave(double angle) { return (float) Math.sin(angle); }

    private void drawClouds(Canvas c, double t) {
        for (int i = 0; i < 3; i++) {
            float travel = phase(t + i * 3.9 + 1, 12);
            float x = -170 + 1300 * travel;
            float y = 188 + i * 122 + 11 * wave(t * .8 + i);
            paint.setColor(0xffa6ccdf);
            paint.setAlpha((int) (99 * fade(travel)));
            c.drawOval(x - 126, y - 8, x + 121, y + 35, paint);
            paint.setColor(0xfff2faff);
            paint.setAlpha((int) (157 * fade(travel)));
            c.drawOval(x - 126, y - 21, x + 117, y + 27, paint);
            c.drawOval(x - 71, y - 48, x + 22, y + 14, paint);
            c.drawOval(x + 2, y - 35, x + 70, y + 10, paint);
        }
    }

    private static float phase(double time, double period) {
        return (float) ((time % period) / period);
    }

    private static float fade(float progress) {
        return wave(Math.PI * progress);
    }

    private void drawEmbers(Canvas c, double t) {
        float flicker = wave(t * 5 + wave(t * 3));
        halo(c, ember, 475, 603, 147, 89 + 12 * flicker, night ? 198 : 135);
        for (int i = 0; i < 3; i++) {
            float x = 443 + i * 34;
            float y = 618;
            float tip = 515 + 21 * wave(t * 4.1 + i * 1.9);
            trail.rewind();
            trail.moveTo(x - 24, y);
            trail.cubicTo(x - 42, y - 33, x + 11, tip + 29, x + 16 * flicker, tip);
            trail.cubicTo(x + 46, tip + 33, x + 39, y - 9, x + 17, y + 4);
            trail.close();
            paint.setColor(i == 1 ? 0xffffca52 : 0xffff6c31);
            paint.setAlpha(night ? 218 : 174);
            c.drawPath(trail, paint);
        }
        for (int i = 0; i < 7; i++) {
            float progress = phase(t + i * .48, 3.2 + i % 3 * .4);
            float x = 480 + (i % 5 - 2) * (15 + progress * 17) + 39 * wave(progress * 4 + i);
            float y = 568 - progress * (183 + i % 4 * 22);
            float light = fade(progress);
            paint.setColor(night ? 0xffffe1a9 : 0xffffb05f);
            paint.setAlpha((int) (242 * light));
            c.drawOval(x - 3, y - 8 * light, x + 3, y + 8 * light, paint);
        }
    }

    private void drawBeacon(Canvas c, double t) {
        float swing = wave(t * .84);
        float shine = .72f + .28f * wave(t * .84 + Math.PI / 2);
        int save = c.save();
        c.rotate(24 * swing, 775, 420);
        trail.rewind();
        trail.moveTo(775, 420);
        trail.lineTo(296, 373);
        trail.lineTo(296, 473);
        trail.close();
        paint.setColor(night ? 0xffffe09a : 0xffa2f5ed);
        paint.setAlpha((int) ((night ? 105 : 83) * shine));
        c.drawPath(trail, paint);
        halo(c, starlight, 556, 420, 218, 24, (int) ((night ? 172 : 132) * shine));
        halo(c, sunlight, 687, 420, 95, 6, (int) (113 * shine));
        c.restoreToCount(save);
        halo(c, sunlight, 775, 420, 23, 19, (int) (177 * shine));
    }

    private void drawCity(Canvas c, double t) {
        if (night) drawWindows(c, t);
        for (int i = 0; i < 5; i++) {
            float travel = phase(t + i * .94, 4.8 + i * .31);
            float x = 72 + 816 * (i % 2 == 0 ? travel : 1 - travel);
            float y = 478 + i * 39;
            RadialGradient tint = i % 2 == 0 ? sunlight : starlight;
            halo(c, tint, x, y, 101, 15, (int) (201 * fade(travel)));
            paint.setColor(i % 2 == 0 ? 0xffffb452 : 0xff63ddff);
            paint.setAlpha((int) (226 * fade(travel)));
            c.drawRoundRect(x - 54, y - 5, x + 54, y + 5, 5, 5, paint);
        }
    }

    private void drawSnow(Canvas c, double t) {
        for (int i = 0; i < 21; i++) {
            float travel = phase(t + i * 1.87, 6.6 + i % 4 * .9);
            float x = 55 + (i * 137 % 826) + 32 * wave(t * .6 + i) + travel * 47;
            float y = 65 + travel * 806;
            float light = fade(travel);
            paint.setColor(night ? 0xffdeefff : 0xffeaffff);
            paint.setAlpha((int) (237 * light));
            if (i % 2 == 0) {
                int save = c.save();
                c.translate(x, y);
                c.rotate((float) (t * (17 + i % 4) + i * 11));
                paint.setStrokeWidth(3.1f);
                float arm = 13 + i % 5 * 2;
                for (int axis = 0; axis < 3; axis++) {
                    c.drawLine(-arm, 0, arm, 0, paint);
                    c.rotate(60);
                }
                c.restoreToCount(save);
            } else {
                c.drawCircle(x, y, 3.8f + i % 4, paint);
            }
        }
    }

    private void drawLeaves(Canvas c, double t) {
        for (int i = 0; i < 10; i++) {
            float travel = phase(t + i * 1.62, 6.8 + i % 3);
            float x = 72 + (i * 157 % 775) + 81 * wave(travel * 7 + i);
            float y = 119 + 675 * travel;
            int save = c.save();
            c.translate(x, y);
            c.rotate(-32 + 70 * wave(t * 1.07 + i));
            float size = 20 + i % 4 * 3;
            trail.rewind();
            trail.moveTo(-size, 0);
            trail.quadTo(-4, -18, size, -3);
            trail.quadTo(4, 14, -size, 0);
            paint.setColor(night ? 0xffaad56f : 0xff4e9f59);
            paint.setAlpha((int) (226 * fade(travel)));
            c.drawPath(trail, paint);
            paint.setColor(0xffdeefad);
            paint.setAlpha((int) (174 * fade(travel)));
            paint.setStrokeWidth(1.8f);
            c.drawLine(-size + 4, 0, size - 8, -3, paint);
            c.restoreToCount(save);
        }
    }

    private void drawPrism(Canvas c, double t) {
        int[] colors = night ? FACET_NIGHT : FACET_DAY;
        for (int i = 0; i < colors.length; i++) {
            float slide = wave(t * 1.03 + i * 1.8);
            float x = 302 + i * 140 + 104 * slide;
            float y = 456 + i * 31 + 52 * wave(t * .97 + i);
            int save = c.save();
            c.translate(x, y);
            c.rotate(-17 + 27 * wave(t * .82 + i * 2.2));
            trail.rewind();
            trail.moveTo(-135, -18);
            trail.lineTo(135, -101);
            trail.lineTo(83, 101);
            trail.close();
            paint.setColor(colors[i]);
            paint.setAlpha(night ? 161 : 145);
            c.drawPath(trail, paint);
            stroke(c, trail, colors[i], 3, 225);
            c.restoreToCount(save);
        }
    }

    private void drawCircuit(Canvas c, double t) {
        for (int i = 0; i < 3; i++) {
            float progress = phase(t + i * 1.28, 4.2) * (CIRCUIT_X.length - 1);
            int j = Math.min((int) progress, CIRCUIT_X.length - 2);
            float fraction = progress - j;
            float x = CIRCUIT_X[j] + (CIRCUIT_X[j + 1] - CIRCUIT_X[j]) * fraction;
            float y = CIRCUIT_Y[j] + (CIRCUIT_Y[j + 1] - CIRCUIT_Y[j]) * fraction - i * 59;
            trail.rewind();
            for (int node = 0; node <= j; node++) {
                float yy = CIRCUIT_Y[node] - i * 59;
                if (node == 0) trail.moveTo(CIRCUIT_X[node], yy);
                else trail.lineTo(CIRCUIT_X[node], yy);
            }
            trail.lineTo(x, y);
            int color = night ? 0xff38efd7 : 0xffffad43;
            stroke(c, trail, color, 12, 61);
            stroke(c, trail, color, 4, 214);
            halo(c, night ? aqua : sunlight, x, y, 39, 28, 205);
            paint.setColor(night ? 0xffc3fff5 : 0xffffe5a4);
            c.drawRoundRect(x - 7, y - 5, x + 7, y + 5, 3, 3, paint);
        }
    }

    private void drawPaws(Canvas c, double t) {
        for (int i = 0; i < 4; i++) {
            float progress = phase(t + (3 - i) * .52, 4.4);
            float light = Math.min(1, progress * 15) * Math.max(0, Math.min(1, (0.74f - progress) * 5));
            if (light == 0) continue;
            float x = 140 + i * 105, y = 659 - i * 84;
            int save = c.save();
            c.translate(x, y);
            c.rotate(i % 2 == 0 ? 26 : 40);
            c.scale(1.21f + .29f * light, 1.21f + .29f * light);
            paint.setColor(night ? 0xffffd5a4 : 0xff96365f);
            paint.setAlpha((int) (217 * light));
            c.drawOval(-16, 0, 16, 24, paint);
            for (int toe = 0; toe < 4; toe++)
                c.drawCircle((toe - 1.5f) * 11, toe == 1 || toe == 2 ? -12 : -6, 5.9f, paint);
            c.restoreToCount(save);
        }
    }

    private void drawRibbons(Canvas c, double t) {
        for (int i = 0; i < 3; i++) {
            int y = 485 + i * 80;
            float curl = 138 * wave(t * .87 + i * .83);
            trail.rewind();
            trail.moveTo(-40, y + 70);
            trail.cubicTo(198, y + curl, 566, y - curl - 162, 1000, y - 33);
            stroke(c, trail, RIBBON_COLORS[i], 31 - i * 5, night ? 116 : 103);
            stroke(c, trail, RIBBON_COLORS[i], 5, 186);
        }
    }

    private void drawPaperFolds(Canvas c, double t) {
        for (int i = 0; i < 2; i++) {
            float open = .5f + .5f * wave(t * 1.34 + i * 2.4);
            int left = i == 0 ? 0 : 960, direction = i == 0 ? 1 : -1;
            int edge = i == 0 ? 395 : 479;
            float tip = left + direction * (117 + 235 * open);
            float crease = edge + 185 + 88 * open;
            int color = i == 0 ? (night ? 0xffc69bff : 0xff72e2ff) : (night ? 0xfff55b96 : 0xffff7756);
            trail.rewind();
            trail.moveTo(left, edge);
            trail.lineTo(tip, crease);
            trail.lineTo(left, edge + 98);
            trail.close();
            paint.setColor(color);
            paint.setAlpha(103 + Math.round(open * 61));
            c.drawPath(trail, paint);
            paint.setColor(color);
            paint.setAlpha(229);
            paint.setStrokeWidth(4);
            c.drawLine(left, edge, tip, crease, paint);
        }
    }

    private void drawTapeReels(Canvas c, double t) {
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? 24 : 936;
            float y = 444;
            int color = night ? 0xff8cf7ed : 0xffffd085;
            int save = c.save();
            c.rotate((float) ((i == 0 ? 105 : -137) * t % 360), x, y);
            for (int part = 0; part < 4; part++) {
                trail.rewind();
                trail.addArc(x - 116, y - 116, x + 116, y + 116, part * 90 + 5, 29);
                stroke(c, trail, color, 10, 221);
            }
            paint.setColor(color);
            paint.setStrokeWidth(7);
            for (int spoke = 0; spoke < 3; spoke++) {
                c.drawLine(x + 32, y, x + 75, y, paint);
                c.rotate(120, x, y);
            }
            c.restoreToCount(save);
        }
        for (int i = 0; i < 13; i++) {
            float h = 14 + 54 * Math.abs(wave(t * (3 + i % 3) + 1.5 * i));
            float x = 277 + i * 33;
            paint.setColor(night ? 0xffc088ff : 0xffffaa32);
            paint.setAlpha(192);
            c.drawRect(x, 783 - h, x + 18, 783, paint);
        }
    }

    private void drawEnvelopes(Canvas c, double t) {
        for (int i = 0; i < 2; i++) {
            float progress = phase(t + 1.52 * i, 4.6);
            float opacity = fade(progress);
            float x = 168 + 280 * progress;
            float y = 682 - 189 * progress - 30 * wave(progress * Math.PI);
            int save = c.save();
            c.translate(x, y);
            c.rotate(-21 + 48 * wave(progress * Math.PI * 2));
            c.scale(i == 0 ? 1 : .72f, i == 0 ? 1 : .72f);
            paint.setColor(night ? 0xffc59cff : 0xfffb7586);
            paint.setAlpha(Math.round(230 * opacity));
            c.drawRoundRect(-60, -40, 60, 40, 11, 11, paint);
            trail.rewind();
            trail.moveTo(-57, -32);
            trail.lineTo(0, 9);
            trail.lineTo(57, -32);
            stroke(c, trail, night ? 0xfff7dfff : 0xffffe9a1, 6, Math.round(239 * opacity));
            c.restoreToCount(save);
        }
    }

    private void drawMineralGrains(Canvas c, double t) {
        for (int i = 0; i < 36; i++) {
            float travel = phase(t + i * .36, 4.8 + i % 3);
            float x = 53 + 855 * travel;
            float y = 670 - 178 * travel + (43 + 8 * (i % 6)) * wave(travel * 7.2 + i * .37);
            int color = i % 3 == 0 ? (night ? 0xffef83db : 0xffffb26b) : (night ? 0xff7df6ff : 0xffb6ffbc);
            paint.setColor(color);
            paint.setAlpha(Math.round((139 + 18 * (i % 6)) * fade(travel)));
            float width = 4 + i % 5;
            c.drawOval(x - width, y - width * .65f, x + width, y + width * .65f, paint);
        }
    }

    private void stroke(Canvas c, Path path, int color, float width, int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setStrokeWidth(width);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawStars(Canvas c, double t, int[] stars) {
        for (int i = 0; i < stars.length / 2; i++) {
            float x = stars[2*i], y = stars[2*i+1];
            float pulse = .5f + .5f * wave(t * (.76 + .09 * (i % 4)) + i * 2.4);
            pulse *= pulse;
            halo(c, starlight, x, y, 6 + 8*pulse, 6 + 8*pulse, (int) (18 + 102*pulse));
            paint.setColor(0xfff4faff);
            paint.setAlpha((int) (40 + 208*pulse));
            c.drawCircle(x, y, 1.6f + 1.5f*pulse, paint);
            if (i % 3 == 0 && pulse > .55f) {
                paint.setAlpha((int) (200 * (pulse - .55f)));
                paint.setStrokeWidth(1.5f);
                c.drawLine(x - 6*pulse, y, x + 6*pulse, y, paint);
                c.drawLine(x, y - 8*pulse, x, y + 8*pulse, paint);
            }
        }
        float comet = phase(t + 1.5, 7.4);
        if (comet < .54f) {
            float x = 160 + comet * 1180;
            float y = 121 + comet * 450;
            halo(c, starlight, x, y, 28, 16, (int) (190 * fade(comet / .54f)));
            paint.setStrokeWidth(3.8f);
            paint.setColor(0xffe4f5ff);
            paint.setAlpha((int) (177 * fade(comet / .54f)));
            c.drawLine(x - 106, y - 40, x, y, paint);
        }
    }

    private void drawWindows(Canvas c, double t) {
        for (int i = 0; i < WINDOWS.length / 4; i++) {
            int j = 4*i;
            float l = WINDOWS[j], top = WINDOWS[j+1], r = WINDOWS[j+2], b = WINDOWS[j+3];
            // Independent occupied/unoccupied plateaus with a short, soft transition.
            float level = Math.min(1f, Math.max(-1f,
                    2.2f * wave(t * (.52 + .06 * (i % 6)) + i * 2.39)));
            if (level > 0) {
                halo(c, sunlight, (l+r)*.5f, (top+b)*.5f, 10, 8, (int) (87*level));
                paint.setColor(0xffffe4a5);
                paint.setAlpha((int) (221*level));
            } else {
                paint.setColor(0xff2e2c36);
                paint.setAlpha((int) (-114*level));
            }
            c.drawRect(l, top, r, b, paint);
        }
    }
}
