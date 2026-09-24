package com.mineclone.sim;

import com.mineclone.world.World;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.entity.EntityPhysics;
import com.mineclone.world.entity.Mob;
import org.joml.Vector3fc;

/**
 * Who a mob is after, among the participants.
 *
 * <p>Only the living outside creative mode are candidates
 * ({@link Participants#HOSTILE_TARGETS}). A new target is the nearest one the
 * mob can see, else the nearest. A mob keeps its target — a pack does not swap
 * victims every step — unless someone it can see is markedly nearer
 * ({@link #SWITCH_MARGIN}), or the target has been out of sight for
 * {@link #LOST_TIME} and someone else is in view. A participant who hit the
 * mob within {@link Mob#REVENGE_TIME} comes first: that is how a wolf pays back.
 *
 * <p>With a single candidate the answer is always that candidate, seen or not:
 * range, darkness and the sight cone stay the mob's own business, so a lone
 * player's world behaves exactly as it did before there were targets to choose.
 *
 * <p>Lives in {@code sim}, not beside {@link Mob}, because it reads
 * participants; the per-mob memory it keeps is on the mob.
 */
public final class TargetSelector {
    /** How long a target may stay out of sight before the mob looks for another. */
    public static final float LOST_TIME = 3f;
    /** How much nearer another participant must be to take the mob's attention. */
    public static final float SWITCH_MARGIN = 0.30f;
    private static final float KEEP_FACTOR = (1f - SWITCH_MARGIN) * (1f - SWITCH_MARGIN);

    private TargetSelector() {}

    /** The mob's target for this tick, or null when no one can be one. Allocates nothing. */
    public static Participant select(Mob mob, Participants participants, World world, float dt) {
        if (mob.revengeTimer > 0f)
            mob.revengeTimer = Math.max(0f, mob.revengeTimer - dt);
        int candidates = 0;
        Participant only = null;
        for (int i = 0; i < participants.size(); i++) {
            Participant p = participants.get(i);
            if (Participants.HOSTILE_TARGETS.test(p)) {
                candidates++;
                only = p;
            }
        }
        if (candidates == 0)
            return take(mob, null);
        if (candidates == 1)
            return take(mob, only);
        if (mob.revengeTimer > 0f && mob.revengeId != DamageSource.NO_ATTACKER) {
            Participant grudge = participants.getById(mob.revengeId);
            if (grudge != null && Participants.HOSTILE_TARGETS.test(grudge))
                return take(mob, grudge);
        }
        Participant current = mob.targetId == DamageSource.NO_ATTACKER ? null : participants.getById(mob.targetId);
        if (current != null && !Participants.HOSTILE_TARGETS.test(current))
            current = null;
        if (current == null) {
            Participant seen = nearestSeen(mob, participants, world, null);
            return take(mob, seen != null ? seen : participants.nearest(mob.position.x, mob.position.y,
                    mob.position.z, Participants.HOSTILE_TARGETS));
        }
        mob.targetUnseen = sees(mob, current, world) ? 0f : mob.targetUnseen + dt;
        // Only someone in view can take the mob's attention: a nearer player behind
        // a wall would be lost again at once, and the mob would flip between them.
        Participant other = nearestSeen(mob, participants, world, current);
        if (other != null && (mob.targetUnseen > LOST_TIME
                || distanceSq(mob, other) < distanceSq(mob, current) * KEEP_FACTOR))
            return take(mob, other);
        return current;
    }

    /** The nearest candidate the mob can see, {@code except} left out; null when it sees none. */
    private static Participant nearestSeen(Mob mob, Participants participants, World world, Participant except) {
        Participant seen = null;
        float seenDistance = Float.POSITIVE_INFINITY;
        for (int i = 0; i < participants.size(); i++) {
            Participant p = participants.get(i);
            if (p == except || !Participants.HOSTILE_TARGETS.test(p))
                continue;
            float d = distanceSq(mob, p);
            if (d < seenDistance && sees(mob, p, world)) {
                seenDistance = d;
                seen = p;
            }
        }
        return seen;
    }

    private static Participant take(Mob mob, Participant target) {
        int id = target == null ? DamageSource.NO_ATTACKER : target.id();
        if (id != mob.targetId) {
            mob.targetId = id;
            mob.targetUnseen = 0f;
        }
        return target;
    }

    private static float distanceSq(Mob mob, Participant p) {
        return p.position().distanceSquared(mob.position.x, mob.position.y, mob.position.z);
    }

    /** From the mob's eyes to the participant's, as the mob's own sight check looks. */
    private static boolean sees(Mob mob, Participant p, World world) {
        Vector3fc eye = p.eye();
        return EntityPhysics.lineOfSight(world, mob.position.x, mob.position.y + mob.type.height * 0.85f,
                mob.position.z, eye.x(), eye.y(), eye.z());
    }
}
