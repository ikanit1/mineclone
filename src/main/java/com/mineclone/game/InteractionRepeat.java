package com.mineclone.game;

/** Immediate press plus a bounded repeat cadence while a button stays held. */
public final class InteractionRepeat {
    private float remaining;
    public boolean update(float dt, boolean pressed, boolean held, boolean repeat) {
        if (!held && !pressed) { remaining = 0f; return false; }
        remaining = Math.max(0f, remaining - dt);
        if (pressed || (held && repeat && remaining <= 0f)) {
            remaining = 0.2f;
            return true;
        }
        return false;
    }
    public void reset() { remaining = 0f; }
}
