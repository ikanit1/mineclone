package com.mineclone.sim;

import com.mineclone.world.World;
import com.mineclone.world.entity.EntityPhysics;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobHerd;
import com.mineclone.world.entity.MobSpatialGrid;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobTactics;
import com.mineclone.world.entity.Projectile;
import com.mineclone.world.entity.Wildlife;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.joml.Vector3f;

/**
 * The simulation of one open world, run by the game's host and the dedicated
 * server alike, so neither keeps its own copy of the rules.
 *
 * <p>Step one (SIM-03) holds the mobs: senses, tactics, the tick itself, bites,
 * deaths, collisions, despawning and spawning, in the order the game ran them.
 * What a tick means to players goes out through {@link WorldEvents}. What the
 * session cannot do by itself yet it asks of its {@link Host}.
 * {@code SessionParityTests} pins the behaviour to {@code Game.updateMobs} as it
 * was before the move.
 */
public final class WorldSession {
    /** How often herds and hunters look around. */
    public static final float SENSE_INTERVAL = 0.20f;
    /** How far apart two mobs can stand and still need pushing apart. */
    private static final float COLLISION_RANGE = 2.0f;

    /**
     * What the session still borrows from whoever runs it. The single focus and
     * its blow give way to participants (SIM-05, SIM-07); explosions and drops
     * move into the session (SIM-04).
     */
    public interface Host {
        /**
         * The point mobs live around: the host's own player, or the dedicated
         * server's first guest. Mobs read it; only a body's share of zero is
         * ever applied to it.
         */
        Vector3f mobFocus();

        /** Whether hostile mobs hunt the focus: not in creative. */
        boolean hostileMobs();

        /** The focus holds a light source, and zombies hesitate before it. */
        default boolean focusHoldsLight() { return false; }

        /** Mobs are pushed out of the focus as out of a player's body; a server's focus is only a point. */
        default boolean focusIsBody() { return false; }

        /** Where mob arrows go; null while nothing would fly them, and then skeletons close in instead. */
        default Consumer<Projectile> mobShots() { return null; }

        /** False stops natural spawning (benchmarks); despawning goes on. */
        default boolean spawnMobs() { return true; }

        /** A mob's blow reached the focus. */
        default void mobStruck(Mob mob) {}

        /** A creeper's fuse ran out; the creeper is already dead. */
        default void mobExploded(Mob mob) {}

        /** Once, as a mob dies: what it leaves behind. */
        default void mobLoot(Mob mob) {}
    }

    private final World world;
    private final WorldClock clock;
    private final MobSpawner spawner;
    private final EntityStore entities;
    private final Host host;
    private final WorldEvents events;
    private final MobSpatialGrid collisionGrid = new MobSpatialGrid();
    /** Wolves whose bite killed this tick; sated after the loop, reused. */
    private final List<Mob> fed = new ArrayList<>();
    private float senseTimer;
    private float spawnTimer;

    public WorldSession(World world, WorldClock clock, MobSpawner spawner, EntityStore entities,
                        Host host, WorldEvents events) {
        this.world = Objects.requireNonNull(world, "world");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.host = Objects.requireNonNull(host, "host");
        this.events = Objects.requireNonNull(events, "events");
    }

    public World world() { return world; }

    public EntityStore entities() { return entities; }

    /** One step of every mob: senses, the tick, what came of it, collisions, then spawning. */
    public void tickMobs(float dt) {
        List<Mob> mobs = entities.mobs;
        Vector3f focus = host.mobFocus();
        boolean hostile = host.hostileMobs();
        float daylight = clock.daylight();

        // Who grazes with whom, who is a threat and who is prey — before the
        // tick: a mob chooses where to go already knowing where its kin are.
        senseTimer -= dt;
        if (senseTimer <= 0f) {
            senseTimer = SENSE_INTERVAL;
            MobHerd.update(mobs);
            Wildlife.sense(mobs);
        }
        MobTactics.updateGroup(mobs, clock.dayPhase(), dt);
        boolean torch = host.focusHoldsLight();
        Consumer<Projectile> shots = host.mobShots();

        Iterator<Mob> it = mobs.iterator();
        while (it.hasNext()) {
            Mob m = it.next();
            m.setPlayerTorch(torch);
            m.shotSink = shots;
            if (!m.updateLod(world, focus, dt, daylight, hostile)) continue;
            if (m.justIdleSound) events.mobVoice(m);
            if (m.justSplashed) events.mobSplash(m);
            if (m.justEnraged) events.mobEnraged(m);
            if (m.justTookOff) events.mobTookOff(m);
            if (m.justBitMob != null) {
                Mob prey = m.justBitMob;
                if (prey.hurt(Wildlife.BITE_DAMAGE, m.position.x, m.position.z, 0.6f, false)) {
                    events.mobBit(m, prey);
                    if (prey.dead) fed.add(m);
                }
            }
            if (m.justStepSound) events.mobStep(m);
            if (m.justAttacked) host.mobStruck(m);
            if (m.justExploded) host.mobExploded(m);
            if (m.burning) events.mobBurning(m);
            // Once at the moment of death; the corpse topples for a while and
            // only then leaves the list.
            if (m.dead && !m.deathEffectsDone) {
                m.deathEffectsDone = true;
                MobTactics.leaderFell(mobs, m);
                host.mobLoot(m);
                events.mobDied(m);
            }
            if (m.dead && m.deathTimer <= 0f)
                it.remove();
        }
        for (int i = 0; i < fed.size(); i++)
            Wildlife.sate(fed.get(i));
        fed.clear();

        separate(mobs, focus);

        spawner.despawnFar(mobs, focus);
        spawnTimer -= dt;
        if (host.spawnMobs() && spawnTimer <= 0f) {
            spawnTimer = MobSpawner.TICK_INTERVAL;
            spawner.trySpawn(world, mobs, focus, daylight);
        }
    }

    /**
     * Without pushing apart a herd sticks into one point and a mob stands
     * inside the player. Each pair is pushed once, by the earlier mob in the
     * list; the focus is never moved — a player runs its own physics.
     */
    private void separate(List<Mob> mobs, Vector3f focus) {
        collisionGrid.rebuild(mobs);
        boolean body = host.focusIsBody();
        for (int i = 0; i < mobs.size(); i++) {
            Mob a = mobs.get(i);
            List<Mob> near = collisionGrid.nearby(a, COLLISION_RANGE);
            for (int k = 0; k < near.size(); k++) {
                Mob b = near.get(k);
                if (b == a || MobSpatialGrid.order(b) <= i)
                    continue;
                EntityPhysics.separate(a.position, a.type.width, a.type.height, 0.25f,
                        b.position, b.type.width, b.type.height, 0.25f);
            }
            if (body)
                EntityPhysics.separate(focus, Participant.BODY_WIDTH, Participant.BODY_HEIGHT, 0f,
                        a.position, a.type.width, a.type.height, 0.5f);
        }
    }
}
