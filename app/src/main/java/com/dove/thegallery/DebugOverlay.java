package com.dove.thegallery;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Hidden diagnostics panel: app memory, cache tiers, and worker thread state.
 * It is passive — it takes no focus and consumes no keys, and every screen rebuild simply
 * re-attaches it on top because the panel lives on the activity root.
 */
final class DebugOverlay {
    private static final long REFRESH_MS = 500;
    private static final int PANEL_COLOR = 0xF214161B;

    private final MainActivity activity;
    private final FrameLayout root;
    private final Handler handler = new Handler();

    private View panel;
    private TextView text;
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
            if (text != null) text.setText(activity.debugReport() + "\nUI    lag " + uiLagMs + " ms");
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
        box.setBackground(activity.theme.bordered(PANEL_COLOR, 12f, 1f, activity.theme.outline));
        box.setElevation(activity.dp(14));
        box.setFocusable(false);
        TextView view = new TextView(activity);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(10f);
        view.setTextColor(0xffe8edf7);
        view.setLineSpacing(0f, 0.95f);
        int padding = activity.dp(10);
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
}
