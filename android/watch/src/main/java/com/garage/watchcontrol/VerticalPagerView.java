package com.garage.watchcontrol;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;
import android.widget.ScrollView;

import java.util.function.BooleanSupplier;

final class VerticalPagerView extends ViewGroup {
    interface OnPageChangeListener {
        void onPageChanged(int page);
    }

    interface OnCrownHapticListener {
        void onCrownHaptic(boolean pageSwitched);
    }

    record CrownStep(int targetPage, float nextAccum) {}

    private static final long CROWN_DEBOUNCE_MS = 340;
    private static final long CROWN_ACCUM_WINDOW_MS = 260;
    static final float CROWN_TRIGGER_THRESHOLD = 1.4f;
    static final float CROWN_TICK_STEP = 0.65f;

    private final OverScroller scroller;
    private final int touchSlop;
    private final int minFlingVelocity;
    private final float refreshDistance;

    private VelocityTracker velocityTracker;
    private OnPageChangeListener pageChangeListener;
    private OnCrownHapticListener crownHapticListener;
    private ScrollView scrollableControls;
    private Runnable onRefreshPullChanged;
    private Runnable onRefreshRelease;
    private BooleanSupplier canPullRefresh;
    private boolean pullingRefresh;
    private int refreshPullStage;
    private float lastY;
    private float startX;
    private float startY;
    private boolean dragging;
    private int currentPage;
    private long lastCrownSwitchAtMs;
    private long lastCrownEventAtMs;
    private float crownScrollAccum;
    private float crownTickAccum;

    VerticalPagerView(Context context) {
        super(context);
        scroller = new OverScroller(context);
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        minFlingVelocity = config.getScaledMinimumFlingVelocity();
        refreshDistance = 50 * getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setOnPageChangeListener(OnPageChangeListener listener) {
        this.pageChangeListener = listener;
    }

    void setOnCrownHapticListener(OnCrownHapticListener listener) {
        this.crownHapticListener = listener;
    }

    void setScrollableControls(ScrollView controls) {
        scrollableControls = controls;
    }

    void setOnPullRefresh(Runnable onChanged, Runnable onRelease, BooleanSupplier canRefresh) {
        onRefreshPullChanged = onChanged;
        onRefreshRelease = onRelease;
        canPullRefresh = canRefresh;
    }

    String pullRefreshText() {
        return switch (refreshPullStage) {
            case 1 -> "上拉刷新";
            case 2 -> "松手刷新";
            default -> "";
        };
    }

    int currentPage() {
        return currentPage;
    }

    void setCurrentPage(int page, boolean animate) {
        int maxPage = Math.max(0, getChildCount() - 1);
        int target = Math.max(0, Math.min(page, maxPage));
        int height = getHeight();
        if (height <= 0) {
            currentPage = target;
            notifyPageChanged();
            return;
        }
        int targetY = target * height;
        if (target != currentPage) {
            resetRefreshPull();
            currentPage = target;
            notifyPageChanged();
        }
        if (animate) {
            scroller.abortAnimation();
            scroller.startScroll(0, getScrollY(), 0, targetY - getScrollY(), 260);
            invalidate();
        } else {
            scroller.abortAnimation();
            scrollTo(0, targetY);
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        int childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec);
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int childTop = i * height;
            child.layout(0, childTop, width, childTop + height);
        }
        if (!dragging && scroller.isFinished()) {
            scrollTo(0, currentPage * height);
        }
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            resetRefreshPull();
            startX = event.getX();
            startY = event.getY();
            lastY = startY;
            dragging = !scroller.isFinished();
            if (dragging) scroller.abortAnimation();
            ensureTracker(event);
            return dragging;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = Math.abs(event.getX() - startX);
            float dy = Math.abs(event.getY() - startY);
            if (dy > touchSlop && dy > dx) {
                if (currentPage == 1 && scrollableControls != null
                        && scrollableControls.canScrollVertically(event.getY() < startY ? 1 : -1)) return false;
                dragging = true;
                lastY = event.getY();
                ensureTracker(event);
                updateRefreshPull(event);
                return true;
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            resetRefreshPull();
            dragging = false;
            recycleTracker();
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (getChildCount() <= 1 || getHeight() <= 0) return super.onTouchEvent(event);
        ensureTracker(event);
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished()) scroller.abortAnimation();
                startX = event.getX();
                startY = event.getY();
                lastY = startY;
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                float y = event.getY();
                float deltaY = lastY - y;
                lastY = y;
                if (updateRefreshPull(event)) return true;
                int maxScroll = (getChildCount() - 1) * getHeight();
                int nextY = Math.max(0, Math.min(maxScroll, Math.round(getScrollY() + deltaY)));
                scrollTo(0, nextY);
                dragging = true;
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false;
                if (pullingRefresh) {
                    boolean ready = action == MotionEvent.ACTION_UP && refreshStage(event) == 2;
                    resetRefreshPull();
                    recycleTracker();
                    if (ready && canPullRefresh.getAsBoolean()) onRefreshRelease.run();
                    return true;
                }
                velocityTracker.computeCurrentVelocity(1000);
                float vy = velocityTracker.getYVelocity();
                recycleTracker();
                int height = getHeight();
                int target;
                if (Math.abs(vy) >= minFlingVelocity * 2) {
                    target = vy < 0 ? currentPage + 1 : currentPage - 1;
                } else {
                    target = (getScrollY() + height / 2) / height;
                }
                setCurrentPage(target, true);
                return true;
            }
            default -> {
                return super.onTouchEvent(event);
            }
        }
    }

    private boolean updateRefreshPull(MotionEvent event) {
        int stage = refreshStage(event);
        if (!pullingRefresh && (stage == 0 || currentPage != 1 || getScrollY() != getHeight()
                || canPullRefresh == null || !canPullRefresh.getAsBoolean()
                || (scrollableControls != null && scrollableControls.canScrollVertically(1)))) return false;
        pullingRefresh = true;
        setRefreshPullStage(stage);
        return true;
    }

    private int refreshStage(MotionEvent event) {
        if (event.getPointerCount() != 1) return 0;
        return refreshStage(startY - event.getY(), Math.abs(event.getX() - startX), touchSlop, refreshDistance);
    }

    static int refreshStage(float up, float sideways, float slop, float threshold) {
        if (up <= slop || up <= sideways) return 0;
        return up >= threshold ? 2 : 1;
    }

    private void setRefreshPullStage(int stage) {
        if (refreshPullStage == stage) return;
        refreshPullStage = stage;
        if (onRefreshPullChanged != null) onRefreshPullChanged.run();
    }

    private void resetRefreshPull() {
        pullingRefresh = false;
        setRefreshPullStage(0);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (handleCrownMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    boolean handleCrownMotion(MotionEvent event) {
        if (event == null || event.getAction() != MotionEvent.ACTION_SCROLL) return false;
        if (!event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
                && !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            return false;
        }
        float scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL);
        if (scroll == 0f) scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (scroll == 0f || getChildCount() <= 1) return false;

        long now = SystemClock.uptimeMillis();
        boolean expired = lastCrownEventAtMs > 0 && now - lastCrownEventAtMs > CROWN_ACCUM_WINDOW_MS;
        if (expired || (scroll * crownTickAccum < 0f)) {
            crownTickAccum = 0f;
        }
        CrownStep step = stepCrownScroll(
                currentPage, getChildCount(), scroll, crownScrollAccum, now, lastCrownEventAtMs, lastCrownSwitchAtMs);
        lastCrownEventAtMs = now;
        crownScrollAccum = step.nextAccum();
        crownTickAccum += scroll;

        if (step.targetPage() >= 0) {
            lastCrownSwitchAtMs = now;
            crownScrollAccum = 0f;
            crownTickAccum = 0f;
            setCurrentPage(step.targetPage(), true);
            if (crownHapticListener != null) crownHapticListener.onCrownHaptic(true);
            return true;
        }
        if (Math.abs(crownTickAccum) >= CROWN_TICK_STEP) {
            crownTickAccum = 0f;
            if (crownHapticListener != null) crownHapticListener.onCrownHaptic(false);
        }
        return true;
    }

    static CrownStep stepCrownScroll(
            int currentPage,
            int pageCount,
            float scroll,
            float prevAccum,
            long nowMs,
            long lastEventMs,
            long lastSwitchMs) {
        if (scroll == 0f || pageCount <= 1) return new CrownStep(-1, 0f);
        if (lastSwitchMs > 0 && nowMs - lastSwitchMs < CROWN_DEBOUNCE_MS) {
            return new CrownStep(-1, 0f);
        }
        boolean expired = lastEventMs > 0 && nowMs - lastEventMs > CROWN_ACCUM_WINDOW_MS;
        float base = (expired || (scroll * prevAccum < 0f)) ? 0f : prevAccum;
        float nextAccum = base + scroll;
        if (Math.abs(nextAccum) < CROWN_TRIGGER_THRESHOLD) {
            return new CrownStep(-1, nextAccum);
        }
        int target = Math.max(0, Math.min(pageCount - 1, nextAccum < 0f ? currentPage + 1 : currentPage - 1));
        if (target == currentPage) {
            return new CrownStep(-1, 0f);
        }
        return new CrownStep(target, 0f);
    }

    static int nextPageFromCrown(int currentPage, int pageCount, float scroll, long nowMs, long lastSwitchMs) {
        return stepCrownScroll(currentPage, pageCount, scroll, 0f, nowMs, nowMs, lastSwitchMs).targetPage();
    }

    private void ensureTracker(MotionEvent event) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
    }

    private void recycleTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void notifyPageChanged() {
        if (pageChangeListener != null) {
            pageChangeListener.onPageChanged(currentPage);
        }
    }
}
