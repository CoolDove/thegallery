package com.dove.thegallery;

import android.view.KeyEvent;

/** Settings screen input: linear focus navigation and activation. */
final class SettingsController implements ScreenController {
    private final MainActivity activity;
    private final InteractionSystem interaction;

    SettingsController(MainActivity activity, InteractionSystem interaction) {
        this.activity = activity;
        this.interaction = interaction;
    }

    @Override public void onEnter() { refreshHints(); }

    @Override public void refreshHints() {
        if (activity.current != Screen.SETTINGS) return;
        interaction.show(HintPanel.backOnly(activity::closeSettings));
    }

    @Override public boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP && interaction.consumeSwallowed(key)) return true;
        if (action == KeyEvent.ACTION_UP && (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK)) {
            activity.closeSettings();
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN) return true;

        if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!activity.settingsActions.isEmpty()) {
                activity.settingsFocus = (activity.settingsFocus + 1) % activity.settingsActions.size();
                activity.focusSettingsAction();
            }
        } else if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!activity.settingsActions.isEmpty()) {
                activity.settingsFocus = Math.floorMod(activity.settingsFocus - 1, activity.settingsActions.size());
                activity.focusSettingsAction();
            }
        } else if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (!activity.settingsActions.isEmpty()) activity.settingsActions.get(activity.settingsFocus).performClick();
        } else if (key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_MENU) {
            activity.closeSettings();
        }
        return true;
    }
}
