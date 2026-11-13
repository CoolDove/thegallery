package com.cooldove.thegallery;

import android.view.KeyEvent;

/** Share screen input: back only, all other keys are swallowed. */
final class ShareController implements ScreenController {
    private final MainActivity activity;
    private final InteractionSystem interaction;

    ShareController(MainActivity activity, InteractionSystem interaction) {
        this.activity = activity;
        this.interaction = interaction;
    }

    @Override public void onEnter() { refreshHints(); }

    @Override public void refreshHints() {
        if (activity.current != Screen.SHARE) return;
        interaction.show(HintPanel.backOnly(activity::closeShareScreen));
    }

    @Override public boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP && interaction.consumeSwallowed(key)) return true;
        if (action == KeyEvent.ACTION_UP && (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK)) {
            activity.closeShareScreen();
            return true;
        }
        return true;
    }
}
