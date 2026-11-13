package com.dove.thegallery;

import android.view.KeyEvent;

/** Full-screen viewer input: image stepping, back, and delete-by-hold. */
final class ViewerController implements ScreenController {
    private final MainActivity activity;
    private final InteractionSystem interaction;

    ViewerController(MainActivity activity, InteractionSystem interaction) {
        this.activity = activity;
        this.interaction = interaction;
    }

    @Override public void onEnter() { refreshHints(); }

    @Override public void refreshHints() {
        if (activity.current != Screen.VIEWER) return;
        int held = interaction.heldKey();
        if (held == KeyEvent.KEYCODE_BUTTON_B || held == KeyEvent.KEYCODE_BACK) {
            interaction.show(HintPanel.face()
                    .b("Exit", MainActivity.EXIT_HOLD_DURATION_MS, null));
        } else if (held == KeyEvent.KEYCODE_BUTTON_X) {
            interaction.show(HintPanel.face()
                    .x("Delete", MainActivity.LONG_PRESS_MS, null)
                    .b("Back", 0, null));
        } else if (held == KeyEvent.KEYCODE_BUTTON_Y) {
            interaction.show(HintPanel.face()
                    .y("Share", MainActivity.LONG_PRESS_MS, null)
                    .b("Back", 0, null));
        } else {
            interaction.hidePanel();
        }
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
        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (down) {
                if (event.getRepeatCount() == 0) {
                    interaction.beginHold(key, MainActivity.HOLD_HINT_DELAY_MS, 0, MainActivity.EXIT_HOLD_DURATION_MS,
                            this::refreshHints, null, activity::finish);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                boolean initiated = interaction.heldKey() == key;
                InteractionSystem.HoldResult result = interaction.endHold(key);
                if (initiated && !result.longPressed && !result.hintReached) activity.showGrid();
                refreshHints();
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
                return true;
            }
            return true;
        }

        if (key == KeyEvent.KEYCODE_BUTTON_A) return true;

        if (down && !activity.photos.isEmpty()
                && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            activity.selected = Math.floorMod(activity.selected + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1), activity.photos.size());
            activity.displayPhoto();
            return true;
        }
        return false;
    }
}
