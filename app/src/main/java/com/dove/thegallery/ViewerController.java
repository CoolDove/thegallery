package com.dove.thegallery;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

/**
 * Full-screen viewer input: image stepping, zoom/pan, the info overlay, back, and
 * delete-by-hold. Prompts are hidden until a face button is pressed.
 */
final class ViewerController implements ScreenController {
    /** Prompts stay up this long after the last face button is released. */
    private static final long HINT_HIDE_DELAY_MS = 1500;

    private final MainActivity activity;
    private final InteractionSystem interaction;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean hintVisible;
    private Runnable hintHide;

    ViewerController(MainActivity activity, InteractionSystem interaction) {
        this.activity = activity;
        this.interaction = interaction;
    }

    @Override public void onEnter() {
        cancelHintHide();
        hintVisible = false;
        interaction.hidePanel();
    }

    @Override public void refreshHints() {
        if (activity.current != Screen.VIEWER) return;
        if (!hintVisible) {
            interaction.hidePanel();
            return;
        }
        // Only the button actually being held fills its ring.
        int held = interaction.heldKey();
        boolean xHeld = held == KeyEvent.KEYCODE_BUTTON_X;
        boolean yHeld = held == KeyEvent.KEYCODE_BUTTON_Y;
        boolean bHeld = held == KeyEvent.KEYCODE_BUTTON_B || held == KeyEvent.KEYCODE_BACK;
        boolean adjusted = activity.viewerIsAdjusted();
        interaction.show(HintPanel.face()
                .x("Delete", xHeld ? MainActivity.LONG_PRESS_MS : 0, null)
                .y("Share", yHeld ? MainActivity.LONG_PRESS_MS : 0, null)
                .a(activity.viewerInfoVisible() ? "Hide info" : "Info", 0, activity::toggleViewerInfo)
                .b(adjusted ? "Fit" : "Exit", !adjusted && bHeld ? MainActivity.EXIT_HOLD_DURATION_MS : 0,
                        adjusted ? activity::resetViewerFit : activity::showGrid));
        // Panels shown outside a press (e.g. after a dialog closes) still auto-hide.
        if (!isFaceButtonPressed() && hintHide == null) scheduleHintHide();
    }

    /** Any face button brings the prompts back for a moment. */
    private void revealHints() {
        hintVisible = true;
        cancelHintHide();
        refreshHints();
    }

    /** Called after a face button is released; hides the prompts once none remain held. */
    private void scheduleHintHide() {
        cancelHintHide();
        hintHide = () -> {
            hintHide = null;
            if (activity.current != Screen.VIEWER) return;
            if (isFaceButtonPressed()) return;
            hintVisible = false;
            interaction.hidePanel();
        };
        handler.postDelayed(hintHide, HINT_HIDE_DELAY_MS);
    }

    private void cancelHintHide() {
        if (hintHide != null) handler.removeCallbacks(hintHide);
        hintHide = null;
    }

    private boolean isFaceButtonPressed() {
        return interaction.isPressed(KeyEvent.KEYCODE_BUTTON_A)
                || interaction.isPressed(KeyEvent.KEYCODE_BUTTON_X)
                || interaction.isPressed(KeyEvent.KEYCODE_BUTTON_Y)
                || interaction.isPressed(KeyEvent.KEYCODE_BUTTON_B)
                || interaction.isPressed(KeyEvent.KEYCODE_BACK);
    }

    @Override public boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP && interaction.consumeSwallowed(key)) return true;
        if (interaction.isWheelVisible()) {
            interaction.closeWheel();
            return true;
        }

        boolean down = action == KeyEvent.ACTION_DOWN;
        boolean face = key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_BUTTON_X
                || key == KeyEvent.KEYCODE_BUTTON_Y || key == KeyEvent.KEYCODE_BUTTON_B
                || key == KeyEvent.KEYCODE_BACK;
        if (down && event.getRepeatCount() == 0 && face) revealHints();

        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            // Away from best fit, B restores the size and position instead of leaving.
            if (down) {
                if (event.getRepeatCount() == 0) {
                    if (activity.viewerIsAdjusted()) {
                        activity.resetViewerFit();
                        refreshHints();
                    } else {
                        interaction.beginHold(key, MainActivity.HOLD_HINT_DELAY_MS, 0, MainActivity.EXIT_HOLD_DURATION_MS,
                                this::refreshHints, null, activity::finish);
                    }
                }
            } else if (action == KeyEvent.ACTION_UP) {
                boolean initiated = interaction.heldKey() == key;
                InteractionSystem.HoldResult result = interaction.endHold(key);
                if (initiated && !result.longPressed && !result.hintReached) activity.showGrid();
                refreshHints();
                if (!isFaceButtonPressed()) scheduleHintHide();
            }
            return true;
        }

        if (key == KeyEvent.KEYCODE_BUTTON_X) {
            if (down) {
                if (event.getRepeatCount() == 0) {
                    interaction.beginHold(key, MainActivity.HOLD_HINT_DELAY_MS, MainActivity.LONG_PRESS_MS, 0,
                            this::refreshHints, activity::showDeleteConfirmation, null);
                }
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                interaction.endHold(key);
                refreshHints();
                if (!isFaceButtonPressed()) scheduleHintHide();
                return true;
            }
            return true;
        }

        if (key == KeyEvent.KEYCODE_BUTTON_Y) {
            if (down) {
                if (event.getRepeatCount() == 0) {
                    interaction.beginHold(key, MainActivity.HOLD_HINT_DELAY_MS, MainActivity.LONG_PRESS_MS, 0,
                            this::refreshHints, activity::showShareScreen, null);
                }
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                interaction.endHold(key);
                refreshHints();
                if (!isFaceButtonPressed()) scheduleHintHide();
                return true;
            }
            return true;
        }

        if (key == KeyEvent.KEYCODE_BUTTON_A) {
            if (down && event.getRepeatCount() == 0) activity.toggleViewerInfo();
            if (!isFaceButtonPressed()) scheduleHintHide();
            return true;
        }

        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            // One press is one step: only the initial DOWN of a press zooms, auto-repeat is ignored.
            if (down && event.getRepeatCount() == 0) {
                activity.viewerZoom(key == KeyEvent.KEYCODE_DPAD_UP
                        ? ViewerImageTransform.ZOOM_IN : ViewerImageTransform.ZOOM_OUT);
            }
            return true;
        }

        if (down && !activity.photos.isEmpty()
                && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            activity.viewerStep = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            activity.selected = Math.floorMod(activity.selected + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1), activity.photos.size());
            activity.displayPhoto();
            refreshHints();
            return true;
        }
        return false;
    }
}
