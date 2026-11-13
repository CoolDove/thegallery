package com.dove.thegallery;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Global controller-facing interaction layer: owns key tracking, hold timing, and prompt panels.
 * Screens decide what to show; this class only renders it and reports hold progress.
 */
final class InteractionSystem {
    private static final int CLUSTER_BUTTON_DP = 26;
    private static final int CLUSTER_INTERVAL_DP = 30;
    private static final int CLUSTER_WIDTH_DP = 250;
    private static final int CLUSTER_HEIGHT_DP = 124;
    private static final int CLUSTER_CENTER_X_DP = 107;
    private static final int OVERLAY_FADE_MS = 140;

    private final MainActivity activity;
    private final FrameLayout root;
    private final Handler handler = new Handler();
    private final Set<Integer> pressedKeys = new HashSet<>();
    private final Set<Integer> swallowedKeyUps = new HashSet<>();
    private final ArrayList<HoldRingTarget> holdRingTargets = new ArrayList<>();

    private View promptOverlay;
    private View wheelOverlay;
    private TextView[] wheelLabels = new TextView[4];

    private int heldKey = KeyEvent.KEYCODE_UNKNOWN;
    private long heldSince;
    private long holdHintDelay;
    private boolean holdHintFired;
    private boolean holdLongPressed;
    private Runnable holdHintRunnable;
    private Runnable holdLongPressRunnable;
    private Runnable holdCompleteRunnable;
    private Runnable holdRingTick;

    InteractionSystem(MainActivity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    static final class HoldResult {
        boolean hintReached;
        boolean longPressed;
    }

    // ---------------------------------------------------------------- key state

    void track(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) pressedKeys.add(key);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            pressedKeys.remove(key);
        }
    }

    boolean isPressed(int key) { return pressedKeys.contains(key); }

    boolean consumeSwallowed(int key) { return swallowedKeyUps.remove(key); }

    void swallowAllPressed() { swallowedKeyUps.addAll(pressedKeys); }

    void clearSwallowed() { swallowedKeyUps.clear(); }

    // ---------------------------------------------------------------- hold timing

    boolean beginHold(int key, long hintDelayMs, long longPressMs, long completeMs,
                      Runnable onHint, Runnable onLongPress, Runnable onComplete) {
        if (heldKey != KeyEvent.KEYCODE_UNKNOWN) return false;
        heldKey = key;
        heldSince = SystemClock.uptimeMillis();
        holdHintDelay = hintDelayMs;
        holdHintFired = false;
        holdLongPressed = false;
        if (onHint != null) {
            holdHintRunnable = () -> {
                if (heldKey != key) return;
                holdHintFired = true;
                onHint.run();
            };
            handler.postDelayed(holdHintRunnable, hintDelayMs);
        }
        if (longPressMs > 0 && onLongPress != null) {
            holdLongPressRunnable = () -> {
                if (heldKey != key) return;
                holdLongPressed = true;
                onLongPress.run();
            };
            handler.postDelayed(holdLongPressRunnable, longPressMs);
        }
        if (completeMs > 0 && onComplete != null) {
            holdCompleteRunnable = () -> {
                if (heldKey != key) return;
                onComplete.run();
            };
            handler.postDelayed(holdCompleteRunnable, completeMs);
        }
        return true;
    }

    HoldResult endHold(int key) {
        HoldResult result = new HoldResult();
        if (heldKey != key) return result;
        long elapsed = SystemClock.uptimeMillis() - heldSince;
        result.hintReached = holdHintFired || elapsed >= holdHintDelay;
        result.longPressed = holdLongPressed;
        cancelHoldState();
        return result;
    }

    void cancelHold() { cancelHoldState(); }

    /** Cancel any in-progress hold and swallow its key-up before a UI change. */
    void cancelHoldForUiChange() {
        int key = heldKey;
        cancelHoldState();
        if (key != KeyEvent.KEYCODE_UNKNOWN) swallowedKeyUps.add(key);
    }

    boolean isHoldingAny() { return heldKey != KeyEvent.KEYCODE_UNKNOWN; }

    int heldKey() { return heldKey; }

    /** Mark a hold complete before running an early action, preventing its progress ring from restarting. */
    void completeHoldProgress() {
        heldSince = SystemClock.uptimeMillis() - getMaximumHoldRingDuration();
        for (HoldRingTarget target : holdRingTargets) target.ring.setProgress(1f);
        stopRingTicker();
    }

    private long getMaximumHoldRingDuration() {
        long duration = 0;
        for (HoldRingTarget target : holdRingTargets) duration = Math.max(duration, target.duration);
        return duration;
    }

    private void cancelHoldState() {
        if (holdHintRunnable != null) handler.removeCallbacks(holdHintRunnable);
        if (holdLongPressRunnable != null) handler.removeCallbacks(holdLongPressRunnable);
        if (holdCompleteRunnable != null) handler.removeCallbacks(holdCompleteRunnable);
        holdHintRunnable = null;
        holdLongPressRunnable = null;
        holdCompleteRunnable = null;
        heldKey = KeyEvent.KEYCODE_UNKNOWN;
        holdHintFired = false;
        holdLongPressed = false;
        removePromptOverlay();
    }

    // ---------------------------------------------------------------- panels

    void show(HintPanel panel) {
        removePromptOverlay();
        if (root == null) return;
        FrameLayout cluster = new FrameLayout(activity);
        int diameter = dp(CLUSTER_BUTTON_DP);
        int interval = CLUSTER_INTERVAL_DP;
        int centerX = dp(CLUSTER_CENTER_X_DP), centerY = dp(CLUSTER_HEIGHT_DP) / 2;
        addFaceButton(cluster, "switch_x_96dpi", panel.x, centerX, centerY - dp(interval), diameter, Gravity.RIGHT);
        addFaceButton(cluster, "switch_y", panel.y, centerX - dp(interval), centerY, diameter, Gravity.LEFT);
        addFaceButton(cluster, "switch_a_96dpi", panel.a, centerX + dp(interval), centerY, diameter, Gravity.RIGHT);
        addFaceButton(cluster, "switch_b_96dpi", panel.b, centerX, centerY + dp(interval), diameter, Gravity.RIGHT);
        root.addView(cluster, clusterParams(Gravity.RIGHT));
        promptOverlay = cluster;
        startRingTicker();
    }

    void hidePanel() { removePromptOverlay(); }

    void showWheel(HintPanel panel) {
        removeWheelOverlay();
        removePromptOverlay();
        FrameLayout cluster = new FrameLayout(activity);
        int diameter = dp(CLUSTER_BUTTON_DP);
        int interval = CLUSTER_INTERVAL_DP;
        int centerX = dp(CLUSTER_CENTER_X_DP), centerY = dp(CLUSTER_HEIGHT_DP) / 2;
        wheelLabels = new TextView[4];
        wheelLabels[HintPanel.UP] = addWheelButton(cluster, HintPanel.UP, panel.dpad[HintPanel.UP], centerX, centerY - dp(interval), diameter);
        wheelLabels[HintPanel.DOWN] = addWheelButton(cluster, HintPanel.DOWN, panel.dpad[HintPanel.DOWN], centerX, centerY + dp(interval), diameter);
        wheelLabels[HintPanel.LEFT] = addWheelButton(cluster, HintPanel.LEFT, panel.dpad[HintPanel.LEFT], centerX - dp(interval), centerY, diameter);
        wheelLabels[HintPanel.RIGHT] = addWheelButton(cluster, HintPanel.RIGHT, panel.dpad[HintPanel.RIGHT], centerX + dp(interval), centerY, diameter);
        FrameLayout overlay = new FrameLayout(activity);
        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x59000000, 0x99000000}));
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, dp(CLUSTER_HEIGHT_DP), Gravity.BOTTOM));
        overlay.addView(cluster, clusterParams(Gravity.LEFT));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
        wheelOverlay = overlay;
    }

    boolean isWheelVisible() { return wheelOverlay != null; }

    /** Update one wheel caption in place; never rebuilds the wheel or its scrim. */
    void updateWheelText(int direction, String value) {
        if (direction < 0 || direction >= wheelLabels.length) return;
        TextView text = wheelLabels[direction];
        if (text == null) return;
        text.setText(value);
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        text.measure(unspecified, unspecified);
        ViewGroup.LayoutParams params = text.getLayoutParams();
        if (params != null) {
            params.width = text.getMeasuredWidth();
            params.height = text.getMeasuredHeight();
            text.setLayoutParams(params);
        }
    }

    void closeWheel() {
        View closing = wheelOverlay;
        wheelOverlay = null;
        wheelLabels = new TextView[4];
        if (closing != null && closing.getParent() == root) {
            closing.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction(() -> root.removeView(closing)).start();
        }
    }

    void reset() {
        cancelHoldState();
        removeWheelOverlay();
        pressedKeys.clear();
        swallowedKeyUps.clear();
    }

    // ---------------------------------------------------------------- rendering

    private void addFaceButton(FrameLayout cluster, String iconName, HintPanel.Hint hint,
                               int cx, int cy, int diameter, int captionSide) {
        if (!hint.visible) return;
        ImageView icon = activity.icon(iconName);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(diameter, diameter, Gravity.TOP | Gravity.LEFT);
        iconParams.leftMargin = cx - diameter / 2;
        iconParams.topMargin = cy - diameter / 2;
        cluster.addView(icon, iconParams);
        if (hint.action != null) {
            icon.setClickable(true);
            icon.setContentDescription(hint.caption);
            icon.setOnClickListener(v -> hint.action.run());
        }
        if (hint.holdMs > 0) addHoldRing(cluster, cx, cy, diameter + dp(10), hint.holdMs);
        if (!hint.caption.isEmpty()) addCaption(cluster, hint.caption, cx, cy, diameter, captionSide);
    }

    private TextView addWheelButton(FrameLayout cluster, int direction, HintPanel.Hint hint,
                                    int cx, int cy, int diameter) {
        DpadIcon icon = new DpadIcon(direction);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(diameter, diameter, Gravity.TOP | Gravity.LEFT);
        iconParams.leftMargin = cx - diameter / 2;
        iconParams.topMargin = cy - diameter / 2;
        cluster.addView(icon, iconParams);
        if (hint.action != null) {
            icon.setClickable(true);
            icon.setContentDescription(hint.caption);
            icon.setOnClickListener(v -> hint.action.run());
        }
        if (hint.holdMs > 0) addHoldRing(cluster, cx, cy, diameter + dp(10), hint.holdMs);
        if (hint.caption.isEmpty()) return null;
        int captionSide = direction == HintPanel.LEFT ? Gravity.LEFT : Gravity.RIGHT;
        return addCaption(cluster, hint.caption, cx, cy, diameter, captionSide);
    }

    private TextView addCaption(FrameLayout cluster, String caption, int cx, int cy, int diameter, int captionSide) {
        TextView text = activity.label(caption, 12);
        text.setPadding(dp(4), dp(2), dp(4), dp(2));
        text.setBackgroundColor(0xcc111315);
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        text.measure(unspecified, unspecified);
        int textWidth = text.getMeasuredWidth();
        int textHeight = text.getMeasuredHeight();
        int gap = dp(3);
        int radius = diameter / 2 + gap;
        int textCenterX = cx;
        int textCenterY = cy;
        if (captionSide == Gravity.TOP) textCenterY = cy - radius - textHeight / 2;
        else if (captionSide == Gravity.BOTTOM) textCenterY = cy + radius + textHeight / 2;
        else if (captionSide == Gravity.LEFT) textCenterX = cx - radius - textWidth / 2;
        else if (captionSide == Gravity.RIGHT) textCenterX = cx + radius + textWidth / 2;
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(textWidth, textHeight, Gravity.TOP | Gravity.LEFT);
        textParams.leftMargin = textCenterX - textWidth / 2;
        textParams.topMargin = textCenterY - textHeight / 2;
        cluster.addView(text, textParams);
        return text;
    }

    private void addHoldRing(FrameLayout cluster, int cx, int cy, int size, long duration) {
        HoldRing ring = new HoldRing();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = cx - size / 2;
        params.topMargin = cy - size / 2;
        cluster.addView(ring, params);
        holdRingTargets.add(new HoldRingTarget(ring, duration));
    }

    private void startRingTicker() {
        if (holdRingTargets.isEmpty() || heldKey == KeyEvent.KEYCODE_UNKNOWN) return;
        if (holdRingTick != null) handler.removeCallbacks(holdRingTick);
        holdRingTick = new Runnable() {
            @Override public void run() {
                long elapsed = SystemClock.uptimeMillis() - heldSince;
                boolean active = false;
                for (HoldRingTarget target : holdRingTargets) {
                    float progress = Math.min(1f, elapsed / (float) target.duration);
                    target.ring.setProgress(progress);
                    if (progress < 1f) active = true;
                }
                if (active && heldKey != KeyEvent.KEYCODE_UNKNOWN) handler.postDelayed(this, 16);
                else holdRingTick = null;
            }
        };
        handler.post(holdRingTick);
    }

    private void stopRingTicker() {
        if (holdRingTick != null) handler.removeCallbacks(holdRingTick);
        holdRingTick = null;
    }

    private void removePromptOverlay() {
        stopRingTicker();
        holdRingTargets.clear();
        if (promptOverlay != null && promptOverlay.getParent() == root) root.removeView(promptOverlay);
        promptOverlay = null;
    }

    private void removeWheelOverlay() {
        View closing = wheelOverlay;
        wheelOverlay = null;
        wheelLabels = new TextView[4];
        if (closing != null) {
            closing.animate().cancel();
            if (closing.getParent() == root) root.removeView(closing);
        }
    }

    private FrameLayout.LayoutParams clusterParams(int horizontalGravity) {
        return new FrameLayout.LayoutParams(dp(CLUSTER_WIDTH_DP), dp(CLUSTER_HEIGHT_DP), horizontalGravity | Gravity.BOTTOM);
    }

    private int dp(int value) { return activity.dp(value); }

    private static final class HoldRingTarget {
        final HoldRing ring;
        final long duration;
        HoldRingTarget(HoldRing ring, long duration) { this.ring = ring; this.duration = duration; }
    }

    private final class HoldRing extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;

        HoldRing() {
            super(activity);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(dp(3));
            trackPaint.setColor(0x40ffffff);
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeWidth(dp(3));
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setColor(0xffffffff);
        }

        void setProgress(float value) {
            if (progress == value) return;
            progress = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = (Math.min(getWidth(), getHeight()) - dp(3)) / 2f;
            canvas.drawCircle(cx, cy, radius, trackPaint);
            if (progress > 0f) canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, 360f * progress, false, progressPaint);
        }
    }

    private final class DpadIcon extends View {
        private final int direction;
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeArmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DpadIcon(int direction) {
            super(activity);
            this.direction = direction;
            circlePaint.setStyle(Paint.Style.FILL);
            circlePaint.setColor(0xff384162);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(2));
            ringPaint.setColor(0xffd7dde5);
            armPaint.setColor(0x59d3e1e7);
            activeArmPaint.setColor(0xffd3e1e7);
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f - dp(1);
            canvas.drawCircle(cx, cy, radius, circlePaint);
            canvas.drawCircle(cx, cy, radius - dp(1), ringPaint);
            float halfThickness = radius * 0.19f;
            float corner = halfThickness;
            for (int side = 0; side <= 3; side++) {
                boolean active = side == direction;
                Paint paint = active ? activeArmPaint : armPaint;
                float length = radius * (active ? 0.78f : 0.58f);
                if (side == HintPanel.UP) canvas.drawRoundRect(cx - halfThickness, cy - length, cx + halfThickness, cy, corner, corner, paint);
                else if (side == HintPanel.DOWN) canvas.drawRoundRect(cx - halfThickness, cy, cx + halfThickness, cy + length, corner, corner, paint);
                else if (side == HintPanel.LEFT) canvas.drawRoundRect(cx - length, cy - halfThickness, cx, cy + halfThickness, corner, corner, paint);
                else canvas.drawRoundRect(cx, cy - halfThickness, cx + length, cy + halfThickness, corner, corner, paint);
            }
        }
    }
}
