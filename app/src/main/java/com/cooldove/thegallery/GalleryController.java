package com.cooldove.thegallery;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

/** Grid screen input: navigation, chord fast-scroll, face-button holds, and the hold wheel. */
final class GalleryController implements ScreenController {
    /** Fast-scroll works as soon as a direction is held; the hint appears after this delay to avoid flicker. */
    private static final long CHORD_HINT_DELAY_MS = 300;

    private final MainActivity activity;
    private final InteractionSystem interaction;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean fastTop;
    private boolean fastBottom;
    private boolean chordHintVisible;
    private Runnable chordHintReveal;
    private int[] wheelActions;
    private int[] wheelToggles;
    private boolean wheelUsesFaceButton;

    GalleryController(MainActivity activity, InteractionSystem interaction) {
        this.activity = activity;
        this.interaction = interaction;
    }

    @Override public void onEnter() {
        cancelChordHintReveal();
        fastTop = false;
        fastBottom = false;
        chordHintVisible = false;
        wheelActions = null;
        wheelToggles = null;
        wheelUsesFaceButton = false;
        refreshHints();
    }

    @Override public void refreshHints() {
        if (activity.current != Screen.GALLERY) return;
        if (interaction.isWheelVisible()) return;
        if (activity.grid == null || activity.photos.isEmpty()) {
            interaction.hidePanel();
            return;
        }
        interaction.show(buildPanel());
    }

    private HintPanel buildPanel() {
        int held = interaction.heldKey();
        HintPanel panel = HintPanel.face();
        if (chordHintVisible && fastTop) {
            panel.x("To Top", 0, () -> activity.scrollGridToEdge(true, false));
            return panel;
        }
        if (chordHintVisible && fastBottom) {
            panel.b("To Bottom", 0, () -> activity.scrollGridToEdge(false, false));
            return panel;
        }
        boolean holdA = held == KeyEvent.KEYCODE_BUTTON_A;
        boolean holdX = held == KeyEvent.KEYCODE_BUTTON_X;
        boolean holdB = held == KeyEvent.KEYCODE_BUTTON_B || held == KeyEvent.KEYCODE_BACK;
        panel.x(holdX ? "View options" : "Sort", holdX ? MainActivity.LONG_PRESS_MS : 0,
                        () -> activity.scrollGridToEdge(true, false))
                .y("Select", 0, null)
                .a(holdA ? "Actions" : "Open", holdA ? MainActivity.LONG_PRESS_MS : 0, null)
                .b(holdB ? "Exit" : "Hold to exit", holdB ? MainActivity.EXIT_HOLD_DURATION_MS : 0,
                        () -> activity.scrollGridToEdge(false, false));
        return panel;
    }

    @Override public boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP && interaction.consumeSwallowed(key)) return true;

        if (interaction.isWheelVisible()) return handleWheelKey(event);

        boolean down = action == KeyEvent.ACTION_DOWN;
        boolean gridReady = activity.grid != null && !activity.photos.isEmpty();

        if (activity.grid != null && isDpadKey(key)) updateChordState();

        if (gridReady && ((fastTop && key == KeyEvent.KEYCODE_BUTTON_X) || (fastBottom && key == KeyEvent.KEYCODE_BUTTON_B))) {
            if (down && event.getRepeatCount() == 0) activity.scrollGridToEdge(fastTop, false);
            return true;
        }

        int held = interaction.heldKey();
        if (down && event.getRepeatCount() == 0 && isDpadKey(key)
                && (held == KeyEvent.KEYCODE_BUTTON_A || held == KeyEvent.KEYCODE_BUTTON_X)) {
            openWheel(held);
            return handleWheelKey(event);
        }

        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (down) {
                if (event.getRepeatCount() == 0) {
                    interaction.beginHold(key, MainActivity.HOLD_HINT_DELAY_MS, 0, MainActivity.EXIT_HOLD_DURATION_MS,
                            this::refreshHints, null, activity::finish);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                boolean initiated = interaction.heldKey() == key;
                InteractionSystem.HoldResult result = interaction.endHold(key);
                if (initiated && !result.longPressed && !result.hintReached && !activity.hasFolder()) activity.pickFolder();
                refreshHints();
            }
            return true;
        }

        if (down && (key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_MENU)) {
            cancelChordHintReveal();
            fastTop = false;
            fastBottom = false;
            chordHintVisible = false;
            activity.resetButtonHoldForUiChange();
            activity.showSettings();
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_Y) return true;

        if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_BUTTON_X) {
            if (down) {
                if (event.getRepeatCount() == 0) {
                    final int heldKey = key;
                    interaction.beginHold(key, MainActivity.HOLD_HINT_DELAY_MS, MainActivity.LONG_PRESS_MS, 0,
                            this::refreshHints, () -> openWheel(heldKey), null);
                }
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                boolean initiated = interaction.heldKey() == key;
                InteractionSystem.HoldResult result = interaction.endHold(key);
                if (initiated && !result.longPressed && !result.hintReached) activity.performFaceButtonShortPress(key);
                refreshHints();
                return true;
            }
            return true;
        }

        if (down && key == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (!gridReady) activity.pickFolder();
            else activity.openSelectedPhoto();
            return true;
        }
        if (down && isDpadKey(key)) {
            if (!gridReady) return true;
            activity.moveGridSelection(key);
            return true;
        }
        return false;
    }

    private void updateChordState() {
        boolean top = interaction.isPressed(KeyEvent.KEYCODE_DPAD_UP) || interaction.isPressed(KeyEvent.KEYCODE_DPAD_LEFT);
        boolean bottom = interaction.isPressed(KeyEvent.KEYCODE_DPAD_DOWN) || interaction.isPressed(KeyEvent.KEYCODE_DPAD_RIGHT);
        boolean directionHeld = top || bottom;

        if (!directionHeld) {
            cancelChordHintReveal();
            boolean wasVisible = chordHintVisible;
            fastTop = false;
            fastBottom = false;
            chordHintVisible = false;
            if (wasVisible) refreshHints();
            return;
        }

        boolean changed = top != fastTop || bottom != fastBottom;
        fastTop = top;
        fastBottom = bottom;
        if (chordHintVisible) {
            if (changed) refreshHints();
            return;
        }
        if (chordHintReveal == null) {
            chordHintReveal = () -> {
                chordHintReveal = null;
                if (activity.current != Screen.GALLERY) return;
                if (!interaction.isPressed(KeyEvent.KEYCODE_DPAD_UP) && !interaction.isPressed(KeyEvent.KEYCODE_DPAD_LEFT)
                        && !interaction.isPressed(KeyEvent.KEYCODE_DPAD_DOWN) && !interaction.isPressed(KeyEvent.KEYCODE_DPAD_RIGHT)) return;
                chordHintVisible = true;
                refreshHints();
            };
            handler.postDelayed(chordHintReveal, CHORD_HINT_DELAY_MS);
        }
    }

    private void cancelChordHintReveal() {
        if (chordHintReveal != null) {
            handler.removeCallbacks(chordHintReveal);
            chordHintReveal = null;
        }
    }

    private void openWheel(int heldKey) {
        HintPanel panel = HintPanel.wheel();
        if (heldKey == KeyEvent.KEYCODE_BUTTON_A) {
            panel.up("Delete", 0, null).down("", 0, null).left("Share", 0, null).right("", 0, null);
            wheelActions = new int[]{1, 0, 2, 0};
            wheelToggles = new int[4];
            wheelUsesFaceButton = true;
        } else if (heldKey == KeyEvent.KEYCODE_BUTTON_X) {
            panel.up(MainActivity.SORTS[activity.sort], 0, null)
                    .down(activity.imageSizeLabel(), 0, null)
                    .left("", 0, null).right("", 0, null);
            wheelActions = new int[4];
            wheelToggles = new int[]{1, 2, 0, 0};
            wheelUsesFaceButton = true;
        } else {
            return;
        }
        interaction.showWheel(panel);
    }

    private boolean handleWheelKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (wheelUsesFaceButton && key == interaction.heldKey()) {
                interaction.closeWheel();
                interaction.cancelHold();
                refreshHints();
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            interaction.closeWheel();
            interaction.cancelHoldForUiChange();
            refreshHints();
            return true;
        }
        if (key == interaction.heldKey() || event.getRepeatCount() > 0) return true;
        int index = key == KeyEvent.KEYCODE_DPAD_UP ? HintPanel.UP
                : key == KeyEvent.KEYCODE_DPAD_DOWN ? HintPanel.DOWN
                : key == KeyEvent.KEYCODE_DPAD_LEFT ? HintPanel.LEFT
                : key == KeyEvent.KEYCODE_DPAD_RIGHT ? HintPanel.RIGHT : -1;
        if (index < 0) return true;
        int action = wheelActions == null ? 0 : wheelActions[index];
        int toggle = wheelToggles == null ? 0 : wheelToggles[index];
        if (toggle == 1) {
            activity.applySortFromWheel();
            interaction.updateWheelText(HintPanel.UP, MainActivity.SORTS[activity.sort]);
        } else if (toggle == 2) {
            activity.cycleDisplayModeFromWheel();
            interaction.updateWheelText(HintPanel.DOWN, activity.imageSizeLabel());
        } else if (action == 1) {
            interaction.closeWheel();
            interaction.cancelHoldForUiChange();
            activity.showDeleteConfirmation();
        } else if (action == 2) {
            interaction.closeWheel();
            interaction.cancelHoldForUiChange();
            activity.showShareScreen();
        }
        return true;
    }

    private static boolean isDpadKey(int key) {
        return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
    }
}
