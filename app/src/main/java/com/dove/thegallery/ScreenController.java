package com.dove.thegallery;

import android.view.KeyEvent;

interface ScreenController {
    /** Called after the screen UI is built: reset per-screen state and publish its hints. */
    void onEnter();

    /** Re-publish the hint panel for the current state (used after overlays close). */
    void refreshHints();

    boolean onKey(KeyEvent event);
}
