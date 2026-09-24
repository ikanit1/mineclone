package com.mineclone.sim;

import com.mineclone.world.GameMode;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.Damageable;
import org.joml.Vector3fc;

/**
 * Someone the world is simulated for: the host's own player or an accepted guest.
 *
 * <p>The simulation sees players only through this view. It does not know
 * whether a participant is local or a network mirror, so mobs, spawning and
 * block ticks run the same code on a single-player host and on the dedicated
 * server. Vectors are live, read-only views owned by the participant; copy
 * them before keeping them past the current tick.
 */
public interface Participant extends Damageable {
    /** A participant's body in blocks, the same for everyone: mobs are pushed out of it. */
    float BODY_WIDTH = 0.6f;
    float BODY_HEIGHT = 1.8f;
    /** Eyes above the feet: where a participant looks and reaches from. */
    float EYE_HEIGHT = 1.62f;

    /** Network actor number, or 0 for an offline single player. */
    int id();

    /** Feet position in world coordinates. */
    Vector3fc position();

    /** Eye position; the returned view may be reused by the next call. */
    Vector3fc eye();

    GameMode mode();

    boolean alive();

    ArmorView armor();

    /** True for the player of this process; false for a guest's mirror. */
    boolean local();
}
