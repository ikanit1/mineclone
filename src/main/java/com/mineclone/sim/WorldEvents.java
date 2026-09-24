package com.mineclone.sim;

import com.mineclone.world.entity.Mob;

/**
 * What the simulation tells whoever shows it: sounds, particles, footprints,
 * sound cues. The game implements it; the dedicated server passes {@link #NONE}.
 *
 * <p>Calls arrive on the simulation thread in simulation order, right after the
 * mob's own tick. An implementation only reads: it must not change a mob, the
 * world or the entity lists, or the server — which shows nothing — would
 * simulate a different world ({@code SessionParityTests} checks it).
 */
public interface WorldEvents {
    WorldEvents NONE = new WorldEvents() {};

    /** Idle voice: a moo, a growl, sometimes a howl. */
    default void mobVoice(Mob mob) {}

    /** Just fell into water; {@code mob.splashSpeed} says how hard. */
    default void mobSplash(Mob mob) {}

    /** Crossed its rage threshold. */
    default void mobEnraged(Mob mob) {}

    /** A bird took off. */
    default void mobTookOff(Mob mob) {}

    /** A predator's bite landed on its prey. */
    default void mobBit(Mob predator, Mob prey) {}

    /** A footstep. */
    default void mobStep(Mob mob) {}

    /** Every tick a mob is on fire. */
    default void mobBurning(Mob mob) {}

    /** Once, as a mob dies; the corpse stays for {@code Mob.DEATH_TIME}. */
    default void mobDied(Mob mob) {}
}
