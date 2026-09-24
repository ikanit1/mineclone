package com.mineclone.sim;

import com.mineclone.item.Items;
import com.mineclone.item.loot.LootContext;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.DroppedItem;
import com.mineclone.world.Explosion;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.EntityPhysics;
import com.mineclone.world.entity.Hittable;
import com.mineclone.world.entity.ItemEntity;
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
import java.util.Random;
import java.util.function.Consumer;
import org.joml.Vector3f;

/**
 * The simulation of one open world, run by the game's host and the dedicated
 * server alike, so neither keeps its own copy of the rules.
 *
 * <p>It holds the world's entities: mobs (SIM-03), items lying about, arrows,
 * explosions and what the dead leave behind, and what of all that a chunk
 * saves (SIM-04). What a tick means to players goes out through
 * {@link WorldEvents}. What the session cannot do by itself yet it asks of its
 * {@link Host}. {@code SessionParityTests} pins the behaviour to {@code Game} as
 * it was before the move.
 */
public final class WorldSession {
    /** How often herds and hunters look around. */
    public static final float SENSE_INTERVAL = 0.20f;
    /** How far apart two mobs can stand and still need pushing apart. */
    private static final float COLLISION_RANGE = 2.0f;
    /** More items than this and the oldest goes: a collapsing wall must not cost a frame. */
    public static final int MAX_ITEMS = 480;
    /** How often neighbouring stacks of the same item merge. */
    public static final float ITEM_MERGE_INTERVAL = 0.5f;
    /** Below this an item has fallen out of the world. */
    private static final float ITEM_VOID = -16f;
    /** How far a broken or placed block is heard by mobs, in blocks. */
    public static final float NOISE_BREAK = 16f, NOISE_PLACE = 10f;
    private static final java.util.function.Predicate<Participant> ANYONE = p -> true;

    /** What the session borrows from whoever runs it: the game's host or the dedicated server. */
    public interface Host {
        /**
         * The host's own player, whose body mobs are pushed out of — or, for
         * the dedicated server, the spawn point: where the world stays alive
         * and what mobs look at while no one is here. Only a body's share of
         * zero is ever applied to it.
         */
        Vector3f mobFocus();

        /** The host's own player holds a light source, and zombies hesitate before it. */
        default boolean focusHoldsLight() { return false; }

        /** Mobs are pushed out of the focus as out of a player's body; the server's spawn point is only a point. */
        default boolean focusIsBody() { return false; }

        /** False stops natural spawning (benchmarks); despawning goes on. */
        default boolean spawnMobs() { return true; }

        /** A mob's blow hurt this participant; a local player is thrown back and feels the elite's touch. */
        default void participantStruck(Participant participant, Mob mob) {}

        /** Who an arrow can hit besides mobs: the host's own player and the guests. */
        default void projectileTargets(List<Hittable> out) {}

        /** The player of this process, as the one who picks things up; null on the server. */
        default ItemCollector itemCollector() { return null; }

        /** A blast hurt this participant; a local player is thrown back from its source. */
        default void blasted(Participant participant, Mob source) {}
    }

    /**
     * The host's own player picking up what lies about. Guests ask the host
     * over the network instead, and the dedicated server has no one to ask.
     */
    public interface ItemCollector {
        /** Where a magnetized item flies: the middle of the body. */
        Vector3f magnetTarget();

        /** Takes items this tick at all: alive and playing, not in a menu. */
        boolean collecting();

        /** Has room for at least one of the stack. */
        boolean canTake(ItemStack stack);

        /** Gives what fits and returns how many are left over. */
        int give(ItemStack stack);

        /** Once per tick in which something was picked up. */
        default void collected(int stacks) {}

        /** A player's arrow stuck within reach: true if it was taken. */
        default boolean collectArrow(Projectile arrow) { return false; }
    }

    private final World world;
    private final WorldClock clock;
    private final MobSpawner spawner;
    private final EntityStore entities;
    private final Participants participants;
    private final Host host;
    private final WorldEvents events;
    /** Pops of dropped stacks and loot rolls. */
    private final Random random;
    private final MobSpatialGrid collisionGrid = new MobSpatialGrid();
    /** Wolves whose bite killed this tick; sated after the loop, reused. */
    private final List<Mob> fed = new ArrayList<>();
    /** Arrow targets of this tick; reused. */
    private final List<Hittable> targets = new ArrayList<>();
    private final Consumer<Projectile> shots;
    /** Where the mob being ticked looks; copied from its target, never kept past its tick. */
    private final Vector3f aim = new Vector3f();
    /** This tick's centres of life; rebuilt by {@link #centres()}. */
    private final List<org.joml.Vector3fc> centres = new ArrayList<>();
    private float senseTimer;
    private float spawnTimer;
    private float mergeTimer;

    public WorldSession(World world, WorldClock clock, MobSpawner spawner, EntityStore entities,
                        Participants participants, Host host, WorldEvents events) {
        this.world = Objects.requireNonNull(world, "world");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.host = Objects.requireNonNull(host, "host");
        this.events = Objects.requireNonNull(events, "events");
        this.random = new Random(world.seed ^ 0x1735_D40BL);
        this.shots = entities.projectiles::add;
    }

    public World world() { return world; }

    public EntityStore entities() { return entities; }

    // ----------------------------------------------------------------- mobs

    /**
     * One step of every mob: senses, a target among the participants, the tick,
     * what came of it, collisions, then spawning.
     */
    public void tickMobs(float dt) {
        List<Mob> mobs = entities.mobs;
        Vector3f focus = host.mobFocus();
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

        Iterator<Mob> it = mobs.iterator();
        while (it.hasNext()) {
            Mob m = it.next();
            m.setPlayerTorch(torch);
            m.shotSink = shots;
            // A mob hunts its target; with no one to hunt it still looks at
            // the nearest participant, and at the focus in an empty world.
            Participant target = TargetSelector.select(m, participants, world, dt);
            Participant seen = target != null ? target
                    : participants.nearest(m.position.x, m.position.y, m.position.z, ANYONE);
            if (seen != null) aim.set(seen.position());
            else aim.set(focus);
            if (!m.updateLod(world, aim, dt, daylight, target != null)) continue;
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
            if (m.justAttacked && target != null && target.damage(new DamageSource(DamageType.MELEE,
                    DamageSource.NO_ATTACKER, m.type, m.position.x, m.position.y, m.position.z, 1f), m.attackDamage()))
                host.participantStruck(target, m);
            if (m.justExploded)
                explode(m.position.x, m.position.y + m.type.height * 0.5f, m.position.z, Explosion.RADIUS, m);
            if (m.burning) events.mobBurning(m);
            // Once at the moment of death; the corpse topples for a while and
            // only then leaves the list.
            if (m.dead && !m.deathEffectsDone) {
                m.deathEffectsDone = true;
                MobTactics.leaderFell(mobs, m);
                dropLoot(m);
                events.mobDied(m);
            }
            if (m.dead && m.deathTimer <= 0f)
                it.remove();
        }
        for (int i = 0; i < fed.size(); i++)
            Wildlife.sate(fed.get(i));
        fed.clear();

        separate(mobs, focus);

        // Mobs live around everyone: despawned only far from all, spawned
        // around each.
        List<org.joml.Vector3fc> around = centres();
        spawner.despawnFar(mobs, around);
        spawnTimer -= dt;
        if (host.spawnMobs() && spawnTimer <= 0f) {
            spawnTimer = MobSpawner.TICK_INTERVAL;
            spawner.trySpawn(world, mobs, around, daylight);
        }
    }

    /**
     * Where the world is alive this tick: every participant, in id order, or
     * the focus while no one is here. Block ticks and furnaces
     * ({@code WorldSimulation}) run around the same points. The list is reused.
     */
    public List<org.joml.Vector3fc> centres() {
        centres.clear();
        for (int i = 0; i < participants.size(); i++)
            centres.add(participants.get(i).position());
        if (centres.isEmpty())
            centres.add(host.mobFocus());
        return centres;
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

    /**
     * What a dead mob leaves where it fell. A wolf's prey was eaten; a creative
     * killer takes nothing. Anything else rolls the mob's loot table — natural
     * deaths too, whatever mode the host plays in.
     */
    public void dropLoot(Mob m) {
        if (m.eaten)
            return;
        Participant killer = m.killer == DamageSource.NO_ATTACKER ? null : participants.getById(m.killer);
        GameMode mode = killer == null ? GameMode.SURVIVAL : killer.mode();
        if (mode == GameMode.CREATIVE)
            return;
        // No tool: a killer's held item says nothing about an arrow's kill, and
        // no entity table asks for a weapon yet.
        var context = new LootContext(world.seed,
                (int) Math.floor(m.position.x), (int) Math.floor(m.position.y), (int) Math.floor(m.position.z),
                null, m.killedByParticipant, mode, random);
        for (var drop : Items.get().loot().entityDrops(m.type, context))
            dropStack(drop, m.position.x, m.position.y + m.type.height * 0.5f, m.position.z);
    }

    /** A sound a mob may come to check: a block broken or placed, by anyone. */
    public void noise(float x, float y, float z, float loudness) {
        for (Mob m : entities.mobs)
            m.hearNoise(x, y, z, loudness);
    }

    // ---------------------------------------------------------- explosions

    /**
     * A blast: the blocks its centre can see go, harm falls off to the edge for
     * every participant and mob, and then it is shown. {@link Explosion} decides
     * which blocks; walls between keep what is behind them.
     */
    public void explode(float x, float y, float z, float radius, Mob source) {
        for (int[] at : Explosion.destroyed(world, x, y, z, radius))
            world.setBlock(at[0], at[1], at[2], BlockType.AIR);
        for (int i = 0; i < participants.size(); i++) {
            Participant p = participants.get(i);
            float d = p.position().distance(x, y, z);
            if (d >= radius)
                continue;
            float damage = Explosion.damageAt(d, radius, Explosion.MAX_DAMAGE);
            if (damage > 0f && p.damage(new DamageSource(DamageType.EXPLOSION, DamageSource.NO_ATTACKER,
                    source == null ? null : source.type, x, y, z, 1f), damage))
                host.blasted(p, source);
        }
        for (Mob other : entities.mobs) {
            if (other == source || other.dead)
                continue;
            float d = other.position.distance(x, y, z);
            float damage = Explosion.damageAt(d, radius, Explosion.MAX_DAMAGE);
            if (damage > 0f)
                other.hurt(damage, x, z, 1.8f, false);
        }
        events.explosion(x, y, z, source);
    }

    // ---------------------------------------------------------------- items

    /** Puts an item into the world. Past {@link #MAX_ITEMS} the oldest goes. */
    public void addItem(ItemEntity item) {
        List<ItemEntity> items = entities.items;
        if (items.size() >= MAX_ITEMS)
            items.remove(0);
        items.add(item);
    }

    /** Drops a stack with a small hop: loot, a spilled chest, a broken block's drop. */
    public void dropStack(ItemStack stack, float x, float y, float z) {
        if (stack == null || stack.count <= 0)
            return;
        addItem(ItemEntity.popped(stack, x, y, z, random));
    }

    /** Items restored with a chunk enter the world once the chunk is live. */
    public void adoptChunkItems(Chunk chunk) {
        for (DroppedItem d : chunk.takePendingItems())
            entities.items.add(ItemEntity.restored(d, random));
    }

    /** What falling blocks broke on landing: a torch under sand drops as an item. */
    public void adoptFallingDrops() {
        for (DroppedItem d : world.falling.drainDrops())
            entities.items.add(ItemEntity.restored(d, random));
    }

    /**
     * Items fall, float, merge and expire; the host's own player picks up what
     * it reaches. An item in a chunk that is not here yet waits: physics would
     * read air there and drop it through ground that is only missing from memory.
     */
    public void tickItems(float dt) {
        List<ItemEntity> items = entities.items;
        if (items.isEmpty())
            return;
        ItemCollector collector = host.itemCollector();
        Vector3f target = collector == null ? null : collector.magnetTarget();
        boolean collecting = collector != null && collector.collecting();
        int picked = 0;
        for (Iterator<ItemEntity> it = items.iterator(); it.hasNext(); ) {
            ItemEntity e = it.next();
            int cx = Math.floorDiv((int) Math.floor(e.position.x), Chunk.SIZE_X);
            int cz = Math.floorDiv((int) Math.floor(e.position.z), Chunk.SIZE_Z);
            if (world.getChunkIfExists(cx, cz) == null)
                continue;
            boolean take = collecting && collector.canTake(e.stack);
            e.update(world, target, take, dt);
            if (take && e.readyForPickup(target)) {
                int before = e.stack.count;
                e.stack.count = collector.give(e.stack);
                if (e.stack.count < before)
                    picked++;
            }
            if (e.expired() || e.position.y < ITEM_VOID)
                it.remove();
        }
        if (picked > 0)
            collector.collected(picked);

        mergeTimer -= dt;
        if (mergeTimer <= 0f) {
            mergeTimer = ITEM_MERGE_INTERVAL;
            ItemEntity.mergeNearby(items);
            items.removeIf(ItemEntity::expired);
        }
    }

    // ---------------------------------------------------------- projectiles

    /**
     * Arrows fly and strike mobs, the host's player and the guests; a stuck
     * arrow of a player's goes back to the host's own player when it walks by.
     */
    public void tickProjectiles(float dt) {
        List<Projectile> shots = entities.projectiles;
        if (shots.isEmpty())
            return;
        targets.clear();
        targets.addAll(entities.mobs);
        host.projectileTargets(targets);
        ItemCollector collector = host.itemCollector();
        for (Iterator<Projectile> it = shots.iterator(); it.hasNext(); ) {
            Projectile p = it.next();
            p.step(world, dt, targets);
            if (p.dead) {
                it.remove();
                continue;
            }
            if (p.stuck && p.fromPlayer && collector != null && collector.collectArrow(p))
                it.remove();
        }
        targets.clear();
    }

    // --------------------------------------------------------------- saving

    /**
     * What to write for a chunk, or null when nothing changed. Items lying in it
     * — live or restored and not yet adopted — go with it: had one been there
     * at the last write or is one there now, the chunk is written even if its
     * blocks are the same. Marks the chunk written.
     */
    public ChunkSnapshot snapshotChunk(Chunk c) {
        if (c.isReadOnly())
            return null;
        List<DroppedItem> dropped = itemsInChunk(c);
        if (!c.modified && dropped.isEmpty() && c.savedItems == 0)
            return null;
        byte[] blocks = c.copyBlocks(), meta = c.copyMeta();
        world.falling.snapshot(c, blocks, meta, dropped);
        ChunkSnapshot snapshot = new ChunkSnapshot(c.cx, c.cz, blocks, meta, c.copyChests(), c.copyFurnaces(),
                dropped, c.copyExtraSections());
        c.savedItems = dropped.size();
        c.modified = false;
        return snapshot;
    }

    /** Copies of the items lying in a chunk, for a write on another thread. */
    private List<DroppedItem> itemsInChunk(Chunk c) {
        List<DroppedItem> out = c.copyPendingItems();
        for (ItemEntity e : entities.items) {
            if (e.stack == null || e.stack.count <= 0)
                continue;
            if (Math.floorDiv((int) Math.floor(e.position.x), Chunk.SIZE_X) != c.cx
                    || Math.floorDiv((int) Math.floor(e.position.z), Chunk.SIZE_Z) != c.cz)
                continue;
            out.add(new DroppedItem(e.stack.copy(), e.position.x, e.position.y, e.position.z, e.age));
        }
        return out;
    }
}
