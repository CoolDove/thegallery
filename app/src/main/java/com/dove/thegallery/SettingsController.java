package com.dove.thegallery;

import android.view.KeyEvent;

/** Settings screen input: linear focus navigation, activation, and LB/RB sub-page switching. */
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
        HintPanel panel = HintPanel.backOnly(activity::closeSettings);
        // The appearance page's single row is a value: A cycles it.
        if (activity.settingsPage == MainActivity.SETTINGS_PAGE_APPEARANCE) {
            panel.a("Change", 0, () -> activity.cycleThemeMode(1));
            panel.a.visible = true;
        }
        interaction.show(panel);
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
        boolean firstPress = event.getRepeatCount() == 0;

        if (key == KeyEvent.KEYCODE_BUTTON_L1) {
            if (firstPress) activity.switchSettingsPage(-1);
        } else if (key == KeyEvent.KEYCODE_BUTTON_R1) {
            if (firstPress) activity.switchSettingsPage(1);
        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean step = key == KeyEvent.KEYCODE_DPAD_RIGHT;
            // The appearance page has one value row, so the D-pad adjusts it instead of hunting focus.
            if (activity.settingsPage == MainActivity.SETTINGS_PAGE_APPEARANCE) {
                activity.cycleThemeMode(step ? 1 : -1);
            } else if (!activity.settingsActions.isEmpty()) {
                activity.settingsFocus = Math.floorMod(activity.settingsFocus + (step ? 1 : -1), activity.settingsActions.size());
                activity.focusSettingsAction();
            }
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
            if (!activity.settingsActions.isEmpty()) {
                int step = key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                activity.settingsFocus = Math.floorMod(activity.settingsFocus + step, activity.settingsActions.size());
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
