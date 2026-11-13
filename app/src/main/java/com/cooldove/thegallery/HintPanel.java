package com.cooldove.thegallery;

/** Declarative description of the hints currently shown by {@link InteractionSystem}. */
final class HintPanel {
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    final boolean wheel;
    final Hint x = new Hint();
    final Hint y = new Hint();
    final Hint a = new Hint();
    final Hint b = new Hint();
    final Hint[] dpad = {new Hint(), new Hint(), new Hint(), new Hint()};

    private HintPanel(boolean wheel) { this.wheel = wheel; }

    static HintPanel face() { return new HintPanel(false); }

    /** Face panel with only the B button, used by Settings and Share. */
    static HintPanel backOnly(Runnable action) {
        HintPanel panel = new HintPanel(false);
        panel.b("Back", 0, action);
        panel.x.visible = false;
        panel.y.visible = false;
        panel.a.visible = false;
        return panel;
    }

    static HintPanel wheel() { return new HintPanel(true); }

    HintPanel x(String caption, long holdMs, Runnable action) { x.set(caption, holdMs, action); return this; }
    HintPanel y(String caption, long holdMs, Runnable action) { y.set(caption, holdMs, action); return this; }
    HintPanel a(String caption, long holdMs, Runnable action) { a.set(caption, holdMs, action); return this; }
    HintPanel b(String caption, long holdMs, Runnable action) { b.set(caption, holdMs, action); return this; }
    HintPanel up(String caption, long holdMs, Runnable action) { return direction(UP, caption, holdMs, action); }
    HintPanel down(String caption, long holdMs, Runnable action) { return direction(DOWN, caption, holdMs, action); }
    HintPanel left(String caption, long holdMs, Runnable action) { return direction(LEFT, caption, holdMs, action); }
    HintPanel right(String caption, long holdMs, Runnable action) { return direction(RIGHT, caption, holdMs, action); }

    private HintPanel direction(int direction, String caption, long holdMs, Runnable action) {
        dpad[direction].set(caption, holdMs, action);
        return this;
    }

    static final class Hint {
        boolean visible = true;
        String caption = "";
        long holdMs;
        Runnable action;

        void set(String caption, long holdMs, Runnable action) {
            this.caption = caption == null ? "" : caption;
            this.holdMs = holdMs;
            this.action = action;
        }
    }
}
