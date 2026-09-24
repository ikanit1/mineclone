package com.mineclone.net;

import com.mineclone.game.Player;
import com.mineclone.sim.Participant;
import com.mineclone.world.GameMode;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.DamageSource;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A guest as the host's simulation sees it.
 *
 * <p>Position is the last accepted snapshot, not the eased drawing position.
 * Damage cannot be applied here: the guest owns its health, so a hit is sent
 * as {@code S_PLAYER_HURT} and the guest applies its own invulnerability
 * window. A mode or health the host has not heard yet reads as creative/alive,
 * so an unannounced guest is never a hostile target by accident.
 */
public final class RemoteParticipant implements Participant {
    private final RemotePlayer player;
    private final Vector3f eye = new Vector3f();

    public RemoteParticipant(RemotePlayer player) {
        this.player = player;
    }

    RemotePlayer player() { return player; }

    @Override public int id() { return player.actor; }
    @Override public Vector3fc position() { return player.acceptedPosition(); }

    @Override
    public Vector3fc eye() {
        Vector3fc at = player.acceptedPosition();
        return eye.set(at.x(), at.y() + Player.EYE_HEIGHT, at.z());
    }

    @Override public GameMode mode() { return GameMode.byOrdinalSafe(player.gameMode); }

    @Override
    public boolean alive() {
        return !player.isDead() && Float.isFinite(player.health) && player.health > 0f;
    }

    @Override public ArmorView armor() { return ArmorView.NONE; }
    @Override public boolean local() { return false; }

    /** @return true when the hit was sent; the guest may still refuse it inside its window */
    @Override
    public boolean damage(DamageSource source, float amount) {
        return alive() && mode() != GameMode.CREATIVE && player.damage(source, amount);
    }
}
