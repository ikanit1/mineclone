package com.mineclone.game;

import com.mineclone.sim.Participant;
import com.mineclone.world.GameMode;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.DamageSource;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.util.function.IntSupplier;

/**
 * The player of this process as the simulation sees it.
 *
 * <p>Lives in {@code game} rather than {@code sim} because it wraps
 * {@link Player}; the simulation package must stay free of the client. The id
 * is supplied, not stored: opening a room gives the same player an actor
 * number that did not exist when the world was loaded.
 */
public final class LocalParticipant implements Participant {
    private final Player player;
    private final IntSupplier id;
    private final Vector3f eye = new Vector3f();

    public LocalParticipant(Player player, IntSupplier id) {
        this.player = player;
        this.id = id;
    }

    @Override public int id() { return id.getAsInt(); }
    @Override public Vector3fc position() { return player.position; }

    @Override
    public Vector3fc eye() {
        return eye.set(player.position.x, player.position.y + Player.EYE_HEIGHT, player.position.z);
    }

    @Override public GameMode mode() { return player.isCreative() ? GameMode.CREATIVE : GameMode.SURVIVAL; }
    @Override public boolean alive() { return !player.isDead(); }
    @Override public ArmorView armor() { return ArmorView.NONE; }
    @Override public boolean local() { return true; }

    /** The player's own damage path: attacks share its invulnerability window, the world's harm does not. */
    @Override
    public boolean damage(DamageSource source, float amount) {
        return player.damage(source, amount);
    }
}
