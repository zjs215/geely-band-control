package com.mario.lynkband.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/** A pull starting at the top refreshes once on release, without clicking the child underneath. */
public final class PullRefreshScrollView extends ScrollView {
    private final Runnable refresh;
    private final int slop;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean tracking, dragging;
    private float downX, downY, distance;

    public PullRefreshScrollView(Context context, Runnable refresh) {
        super(context);
        this.refresh = refresh;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        density = getResources().getDisplayMetrics().density;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
    }

    private float threshold() { return Math.max(4 * slop, Math.min(64 * density, getHeight() * .20f)); }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            tracking = !canScrollVertically(-1);
            dragging = false;
            distance = 0;
            downX = event.getX();
            downY = event.getY();
        } else if (tracking && action == MotionEvent.ACTION_MOVE) {
            float dy = event.getY() - downY;
            float dx = Math.abs(event.getX() - downX);
            if (!dragging && (dy < -slop || (dx > slop && dx > dy))) tracking = false;
            if (tracking && !dragging && dy > slop && dy > dx) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                dragging = true;
            }
            if (dragging) { distance = Math.max(0, dy); invalidate(); return true; }
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            boolean consumed = dragging;
            boolean trigger = consumed && action == MotionEvent.ACTION_UP && event.getY() - downY >= threshold();
            tracking = dragging = false;
            distance = 0;
            invalidate();
            if (trigger) refresh.run();
            if (consumed) return true;
        }
        return super.dispatchTouchEvent(event) || tracking;
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!dragging) return;
        String hint = distance >= threshold() ? "松开刷新" : "下拉刷新";
        float x = getWidth() / 2f;
        float y = getScrollY() + getPaddingTop() + 24 * density;
        float half = paint.measureText(hint) / 2 + 12 * density;
        paint.setColor(0xe8334859);
        canvas.drawRoundRect(x - half, y - 13 * density, x + half, y + 13 * density, 13 * density, 13 * density, paint);
        paint.setColor(0xffffffff);
        canvas.drawText(hint, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }
}
