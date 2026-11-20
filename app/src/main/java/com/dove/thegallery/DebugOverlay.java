package com.dove.thegallery;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Hidden diagnostics panel: app memory, cache tiers, and worker thread state.
 * It is passive — it takes no focus and consumes no keys, and every screen rebuild simply
 * re-attaches it on top because the panel lives on the activity root.
 */
final class DebugOverlay {
    private static final long REFRESH_MS = 500;
    private static final int TEXT_COLOR = 0xFF3DFF7A;
    private static final int OUTLINE_COLOR = 0xD9000000;
    private static final int SHADOW_COLOR = 0xB3000000;
    private static final float TEXT_SIZE_SP = 11f;

    private final MainActivity activity;
    private final FrameLayout root;
    private final Handler handler = new Handler();

    private View panel;
    private MonitorText text;
    private boolean visible;
    private long scheduledAt;
    private long uiLagMs;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            long now = SystemClock.uptimeMillis();
            uiLagMs = scheduledAt == 0 ? 0 : Math.max(0, now - scheduledAt);
            scheduledAt = now + REFRESH_MS;
            attach();
            if (text != null) text.setLines(activity.debugReport() + "\nUI    lag " + uiLagMs + " ms");
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    DebugOverlay(MainActivity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    boolean isVisible() { return visible; }

    void show() {
        if (visible) return;
        visible = true;
        if (panel == null) panel = build();
        attach();
        scheduledAt = 0;
        tick.run();
    }

    void hide() {
        if (!visible) return;
        visible = false;
        handler.removeCallbacks(tick);
        if (panel != null && panel.getParent() != null) root.removeView(panel);
    }

    /** Screens rebuild the root from scratch; re-attaching keeps the panel across those rebuilds. */
    private void attach() {
        if (panel == null || root == null) return;
        if (panel.getParent() == null) root.addView(panel, params());
    }

    private View build() {
        FrameLayout box = new FrameLayout(activity);
        box.setClipChildren(false);
        box.setFocusable(false);
        MonitorText view = new MonitorText();
        int padding = activity.dp(8);
        view.setPadding(padding, padding, padding, padding);
        box.addView(view, new FrameLayout.LayoutParams(-2, -2));
        text = view;
        return box;
    }

    private FrameLayout.LayoutParams params() {
        int margin = activity.dp(12);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    /**
     * Fixed-grid readout drawn by hand: no plate behind the text, just green glyphs with a dark
     * outline and drop shadow so they stay readable over bright photos and the light theme.
     */
    private final class MonitorText extends View {
        private final TextPaint fill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint outline = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final float lineHeight;
        private final float baselineOffset;
        private String[] lines = new String[0];
        private int contentWidth;

        MonitorText() {
            super(activity);
            Typeface typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            float size = TEXT_SIZE_SP * getResources().getDisplayMetrics().scaledDensity;
            fill.setTypeface(typeface);
            fill.setTextSize(size);
            fill.setColor(TEXT_COLOR);
            fill.setShadowLayer(activity.dp(2), 0f, activity.dp(1), SHADOW_COLOR);
            outline.setTypeface(typeface);
            outline.setTextSize(size);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeJoin(Paint.Join.ROUND);
            outline.setStrokeWidth(2f);
            outline.setColor(OUTLINE_COLOR);
            Paint.FontMetrics metrics = fill.getFontMetrics();
            lineHeight = metrics.descent - metrics.ascent;
            baselineOffset = -metrics.ascent;
        }

        void setLines(String value) {
            lines = value.split("\n", -1);
            int width = 0;
            for (String line : lines) width = Math.max(width, (int) Math.ceil(fill.measureText(line)));
            if (width != contentWidth) {
                contentWidth = width;
                requestLayout();
            }
            invalidate();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = contentWidth + getPaddingLeft() + getPaddingRight();
            int height = Math.round(lineHeight * lines.length) + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(
                    resolveSize(width, widthSpec), resolveSize(height, heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            float x = getPaddingLeft();
            float y = getPaddingTop() + baselineOffset;
            for (String line : lines) {
                canvas.drawText(line, x, y, outline);
                canvas.drawText(line, x, y, fill);
                y += lineHeight;
            }
        }
    }
}
