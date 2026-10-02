package com.mineclone.sim;

import com.mineclone.item.Items;
import com.mineclone.item.loot.LootContext;
import com.mineclone.save.ChunkSectionCodec;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.SavedMob;
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
import com.mineclone.world.entity.MobType;
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
    /** Scheduled block ticks run a world tick at most (BLK-04). */
    public static final int TICK_BUDGET = 512;
    /**
     * The most a scheduled tick is told it is late: two world days. A crop
     * left for a week catches up two days' worth, not seven.
     */
    public static final long MAX_TICK_LATENESS = 2 * WorldClock.TICKS_PER_DAY;

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
        /** Forced game weather, or null to use the shared world-seed/clock weather front. */
        default com.mineclone.world.Weather.State hearingWeather() { return null; }

        /** A mob's blow hurt this participant; a local player is thrown back and feels the elite's touch. */
        default void participantStruck(Participant participant, Mob mob) {}

        /** Who an arrow can hit besides mobs: the host's own player and the guests. */
        default void projectileTargets(List<Hittable> out) {}

        /** The player of this process, as the one who picks things up; null on the server. */
        default ItemCollector itemCollector() { return null; }

        /** A blast hurt this participant; a local player is thrown back from its source. */
        default void blasted(Participant participant, Mob source) {}
        default void bred(int actor){}
        default void exploded(float x,float y,float z){}
        default void effectApplied(Participant participant,com.mineclone.world.effect.StatusEffect effect,float seconds){}

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
    private final com.mineclone.sim.ecology.Ecology ecology;
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
    private final java.util.Map<Participant, TrailPosition> playerTrailPositions = new java.util.WeakHashMap<>();
    private final java.util.Map<Mob, TrailPosition> animalTrailPositions = new java.util.WeakHashMap<>();
    private final java.util.Set<Chunk> adoptedChunks=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final com.mineclone.world.entity.Breeding breeding=new com.mineclone.world.entity.Breeding();
    private float senseTimer;
    private float spawnTimer;
    private float mergeTimer;
    private float residencyTimer;
    private boolean hadLivingResidency;

    public WorldSession(World world, WorldClock clock, MobSpawner spawner, EntityStore entities,
                        Participants participants, Host host, WorldEvents events) {
        this.world = Objects.requireNonNull(world, "world");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.host = Objects.requireNonNull(host, "host");
        this.events = Objects.requireNonNull(events, "events");
        breeding.bred=host::bred;
        this.random = new Random(world.seed ^ 0x1735_D40BL);
        this.shots = entities.projectiles::add;
        this.ecology = new com.mineclone.sim.ecology.Ecology(world, clock, entities, participants);
        breeding.born = ecology::born;
        // A session means this world is the real one: whatever takes a chest
        // away spills it here, and blocks that lose their support fall.
        world.simulate(this::dropStack, clock.worldTicks());
        world.setTrampleDay(com.mineclone.sim.ecology.Trample.day(clock.gameTime()));
    }

    public World world() { return world; }

    public EntityStore entities() { return entities; }
    public com.mineclone.sim.ecology.Ecology ecology() { return ecology; }

    // ----------------------------------------------------------------- mobs

    /**
     * One step of every mob: senses, a target among the participants, the tick,
     * what came of it, collisions, then spawning.
     */
    public void tickMobs(float dt) {
        ecology.tick();
        ecology.tickBurnScars(dt);
        updateAnimalResidency(dt);
        ecology.tickLairs(dt, host.spawnMobs());
        List<Mob> mobs = entities.mobs;
        // Whoever added a mob (spawner, command, autopilot) left its id to us (MOB-02).
        entities.assignIds();
        Vector3f focus = host.mobFocus();
        float daylight = clock.daylight();

        // Who grazes with whom, who is a threat and who is prey — before the
        // tick: a mob chooses where to go already knowing where its kin are.
        senseTimer -= dt;
        if (senseTimer <= 0f) {
            senseTimer = SENSE_INTERVAL;
            MobHerd.update(mobs);
            Wildlife.sense(mobs, ecology.enabled());
        }
        MobTactics.updateGroup(mobs, clock.dayPhase(), dt);
        boolean torch = host.focusHoldsLight();

        Iterator<Mob> it = mobs.iterator();
        while (it.hasNext()) {
            Mob m = it.next();
            m.cropRaidsEnabled = ecology.enabled();
            if (world.rules.difficulty() == com.mineclone.world.Difficulty.PEACEFUL && m.type.hostile) {
                // A kill before changing difficulty still consumes its member.
                if (m.dead) ecology.died(m);
                else ecology.retired(m);
                it.remove();
                continue;
            }
            if(m.id==0)m.id=clock.nextEntityId();
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
                if (prey.damage(DamageSource.byMob(DamageType.MELEE, m.type, m.position.x, m.position.y,
                        m.position.z, 0.6f), Wildlife.BITE_DAMAGE)) {
                    events.mobBit(m, prey);
                    if (prey.dead) fed.add(m);
                }
            }
            if (m.justStepSound) events.mobStep(m);
            if (m.justAttacked && target != null && target.damage(DamageSource.byMob(DamageType.MELEE,
                    m.type, m.position.x, m.position.y, m.position.z, 1f), m.attackDamage()*world.rules.difficulty().mobDamage))
                {
                    host.participantStruck(target,m);
                    if(m.elite==com.mineclone.world.entity.MobTactics.Elite.VENOMOUS)host.effectApplied(target,com.mineclone.world.effect.StatusEffect.POISON,7);
                    if(m.elite==com.mineclone.world.entity.MobTactics.Elite.FROST)host.effectApplied(target,com.mineclone.world.effect.StatusEffect.CHILLED,4);
                    if(m.elite==com.mineclone.world.entity.MobTactics.Elite.BURNING)host.effectApplied(target,com.mineclone.world.effect.StatusEffect.STUN,1.1f);
                }
            if (m.justExploded)
                explode(m.position.x, m.position.y + m.type.height * 0.5f, m.position.z, Explosion.RADIUS, m);
            if (m.burning) events.mobBurning(m);
            // Once at the moment of death; the corpse topples for a while and
            // only then leaves the list.
            if (m.dead && !m.deathEffectsDone) {
                ecology.died(m);
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

        breeding.tick(world,mobs,participants,dt,clock::nextEntityId);
        separate(mobs, focus);
        trackFootsteps(mobs);

        // Mobs live around everyone: despawned only far from all, spawned
        // around each.
        List<org.joml.Vector3fc> around = centres();
        spawner.despawnFar(mobs, around);
        spawnTimer -= dt;
        if (host.spawnMobs() && spawnTimer <= 0f) {
            spawnTimer = MobSpawner.TICK_INTERVAL;
            spawner.advance(MobSpawner.TICK_INTERVAL);
            if (ecology.enabled()) {
                ecology.materialize(daylight);
                spawner.trySpawnHostiles(world, mobs, around, daylight);
            } else spawner.trySpawn(world, mobs, around, daylight);
        }
    }

    /** Host-only footsteps: guests see the resulting block changes through the existing channel. */
    private void trackFootsteps(List<Mob> mobs) {
        long day = com.mineclone.sim.ecology.Trample.day(clock.gameTime());
        world.setTrampleDay(day);
        for (int i = 0; i < participants.size(); i++) {
            Participant p = participants.get(i);
            if (!p.alive()) {
                playerTrailPositions.remove(p);
                continue;
            }
            var position = p.position();
            TrailPosition previous = playerTrailPositions.get(p);
            if (previous == null) playerTrailPositions.put(p, new TrailPosition(position));
            else previous.walk(world, position, day);
        }
        for (Mob mob : mobs) {
            if (mob.dead || !mob.persistent() || !mob.onGround) continue;
            TrailPosition previous = animalTrailPositions.get(mob);
            if (previous == null) animalTrailPositions.put(mob, new TrailPosition(mob.position));
            else previous.walk(world, mob.position, day);
        }
    }

    /** Suppresses teleport tracks and records only newly entered ground cells. */
    private static final class TrailPosition {
        private float x, y, z;
        TrailPosition(org.joml.Vector3fc at) { set(at); }
        void walk(World world, org.joml.Vector3fc at, long day) {
            float dx = at.x() - x, dy = at.y() - y, dz = at.z() - z;
            if (dx * dx + dy * dy + dz * dz <= 9f
                    && ((int) Math.floor(at.x()) != (int) Math.floor(x)
                    || (int) Math.floor(at.z()) != (int) Math.floor(z)))
                com.mineclone.sim.ecology.Trample.step(world, at.x(), at.y(), at.z(), day);
            set(at);
        }
        private void set(org.joml.Vector3fc at) { x = at.x(); y = at.y(); z = at.z(); }
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
                EntityPhysics.separate(world, a.position, a.type.width, a.type.height, 0.25f,
                        b.position, b.type.width, b.type.height, 0.25f);
            }
            if (body)
                EntityPhysics.separate(world, focus, Participant.BODY_WIDTH, Participant.BODY_HEIGHT, 0f,
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
                null, m.killedByParticipant, mode, random,0,!m.baby());
        for (var drop : Items.get().loot().entityDrops(m.type, context))
            dropStack(drop, m.position.x, m.position.y + m.type.height * 0.5f, m.position.z);
    }

    /** A sound a mob may come to check: a block broken or placed, by anyone. */
    public void noise(float x, float y, float z, float loudness) {
        if (ecology.enabled()) {
            var weather = host.hearingWeather();
            if (weather == null) weather = com.mineclone.world.Weather.sample(world.seed, clock.frontSeconds());
            loudness = com.mineclone.sim.ecology.WeatherHearing.atSource(world, weather, x, y, z, loudness);
        }
        for (Mob m : entities.mobs)
            m.hearNoise(x, y, z, loudness);
    }

    // ---------------------------------------------------------- explosions

    /**
     * A blast: the blocks its centre can see go, harm falls off to the edge for
     * every participant and mob, and then it is shown. {@link Explosion} decides
     * which blocks; walls between keep what is behind them.
     */
    public void lightning(float x,float y,float z){var source=DamageSource.byMob(DamageType.LIGHTNING,null,x,y,z,0);for(int i=0;i<participants.size();i++){var p=participants.get(i);if(p.position().distanceSquared(x,y,z)<=4)p.damage(source,5);}for(var mob:entities.mobs)if(!mob.dead&&mob.position.distanceSquared(x,y,z)<=4)mob.damage(source,5);}

    public void explode(float x, float y, float z, float radius, Mob source) {
        if(world.rules.mobGriefing())for(int[] at:Explosion.destroyed(world,x,y,z,radius)){
            var block=world.getBlock(at[0],at[1],at[2]);int meta=world.getBlockMeta(at[0],at[1],at[2])&255;
            world.setBlock(at[0],at[1],at[2],BlockType.AIR);
            if(random.nextFloat()<.3f){var loot=new LootContext(world.seed,at[0],at[1],at[2],null,false,GameMode.SURVIVAL,random,meta);
                for(var drop:Items.get().loot().explosionDrops(block,loot))dropStack(drop,at[0]+.5f,at[1]+.5f,at[2]+.5f);}
        }
        MobType by = source == null ? null : source.type;
        DamageSource blast = DamageSource.byMob(DamageType.EXPLOSION, by, x, y, z, 1f);
        for (int i = 0; i < participants.size(); i++) {
            Participant p = participants.get(i);
            float d = p.position().distance(x, y, z);
            if (d >= radius)
                continue;
            float damage = Explosion.damageAt(d, radius, Explosion.MAX_DAMAGE);
            if (damage > 0f && p.damage(blast, damage))
                host.blasted(p, source);
        }
        // Mobs are thrown harder than players by the same blast.
        DamageSource throwing = DamageSource.byMob(DamageType.EXPLOSION, by, x, y, z, 1.8f);
        for (Mob other : entities.mobs) {
            if (other == source || other.dead)
                continue;
            float d = other.position.distance(x, y, z);
            float damage = Explosion.damageAt(d, radius, Explosion.MAX_DAMAGE);
            if (damage > 0f)
                other.damage(throwing, damage);
        }
        host.exploded(x,y,z);
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

    /**
     * The items and mobs standing in these chunks leave the world — after the chunks
     * were written: animals are in the write (MOB-01), hostile mobs simply go, as
     * distance would have taken them anyway. One pass for the whole eviction.
     */
    public void releaseEntitiesIn(java.util.Set<Long> chunkKeys) {
        if (chunkKeys.isEmpty())
            return;
        entities.items.removeIf(e -> chunkKeys.contains(chunkKey(e.position)));
        entities.mobs.removeIf(m -> chunkKeys.contains(chunkKey(m.position)));
    }

    private static long chunkKey(Vector3f p) {
        return World.key(Math.floorDiv((int) Math.floor(p.x), Chunk.SIZE_X),
                Math.floorDiv((int) Math.floor(p.z), Chunk.SIZE_Z));
    }

    /**
     * Items and mobs restored with a chunk enter the world once the chunk is live. A
     * mob of a type this build does not know stays with the chunk and is written back;
     * a mob whose id is already live does not enter twice (MOB-02).
     */
    public void adoptChunkItems(Chunk chunk) {
        if (ecology.enabled()) hadLivingResidency = true;
        if (adoptedChunks.add(chunk)) {
            var sections = chunk.copyExtraSections();
            byte[] saved = sections.remove(EntityPersistence.SECTION);
            boolean receipt = sections.containsKey(EntityPersistence.SPAWN_RECEIPT);
            if (saved != null) {
                // Detached snapshots historically exposed entities in extra. Normal
                // disk loading already moved them to pending, so merge by identity.
                List<SavedMob> pending = chunk.copyPendingMobs();
                var ids = new java.util.HashSet<Long>();
                for (SavedMob record : pending) ids.add(record.id());
                for (SavedMob record : EntityPersistence.decodeRecords(saved))
                    if (ids.add(record.id())) pending.add(record);
                chunk.setPendingMobs(pending);
                receipt = true;
            }
            boolean initialHerd = saved == null && !receipt && chunk.savedMobs == 0
                    && chunk.copyPendingMobs().isEmpty() && !chunk.restoredFromSave
                    && world.genPolicy().featuresAt(chunk.cx, chunk.cz).livestockAtGen();
            if (initialHerd && !ecology.enabled()) {
                for (Mob mob : com.mineclone.world.entity.SpawnTable.get().initialHerd(world, chunk)) {
                    mob.id = clock.nextEntityId();
                    if (entities.adopt(mob)) ecology.adopted(mob);
                }
            }
            if (saved != null || initialHerd) {
                sections.put(EntityPersistence.SPAWN_RECEIPT, new byte[] { 1 });
                chunk.restoreExtraSections(sections);
                chunk.modified = true;
            }
        }
        for (DroppedItem d : chunk.takePendingItems())
            entities.items.add(ItemEntity.restored(d, random));
        List<SavedMob> unknown = null;
        for (SavedMob saved : chunk.takePendingMobs()) {
            entities.reserveId(saved.id());
            ecology.adopted(saved);
            MobType type = MobType.byName(saved.type());
            if (type == null || (ecology.enabled() && !nearLivingParticipant(saved.x(), saved.y(), saved.z()))) {
                if (unknown == null)
                    unknown = new ArrayList<>();
                unknown.add(saved);
                continue;
            }
            Mob m = saved.toMob(random);
            if (entities.adopt(m)) ecology.adopted(m);
        }
        if (unknown != null)
            chunk.returnPendingMobs(unknown);
    }

    /** Distant saved animals keep their identity and reservation without using an active mob slot. */
    private void updateAnimalResidency(float dt) {
        if (!ecology.enabled()) {
            if (hadLivingResidency)
                for (Chunk chunk : world.getLoadedChunks()) adoptChunkItems(chunk);
            hadLivingResidency = false;
            residencyTimer = 0;
            return;
        }
        hadLivingResidency = true;
        residencyTimer -= dt;
        if (residencyTimer > 0) return;
        residencyTimer = MobSpawner.TICK_INTERVAL;
        for (Iterator<Mob> it = entities.mobs.iterator(); it.hasNext(); ) {
            Mob mob = it.next();
            if (!mob.persistent() || mob.dead || !(mob.health > 0) || mob.id <= 0
                    || nearLivingParticipant(mob.position.x, mob.position.y, mob.position.z)) continue;
            Chunk chunk = world.getChunkIfExists(Math.floorDiv((int) Math.floor(mob.position.x), Chunk.SIZE_X),
                    Math.floorDiv((int) Math.floor(mob.position.z), Chunk.SIZE_Z));
            if (chunk == null || chunk.isReadOnly()) continue;
            List<SavedMob> pending = chunk.copyPendingMobs();
            if (pending.size() >= ChunkSectionCodec.MAX_MOB_RECORDS
                    || pending.stream().anyMatch(saved -> saved.id() == mob.id)) continue;
            pending.add(SavedMob.from(mob));
            chunk.returnPendingMobs(pending);
            chunk.modified = true;
            animalTrailPositions.remove(mob);
            it.remove();
        }
        for (Chunk chunk : world.getLoadedChunks())
            if (chunkNearLivingParticipant(chunk)) adoptChunkItems(chunk);
    }

    private boolean nearLivingParticipant(float x, float y, float z) {
        float rangeSq = MobSpawner.LOCAL_RANGE * MobSpawner.LOCAL_RANGE;
        for (int i = 0; i < participants.size(); i++) {
            Participant participant = participants.get(i);
            if (participant.alive() && participant.position().distanceSquared(x, y, z) <= rangeSq) return true;
        }
        return false;
    }

    private boolean chunkNearLivingParticipant(Chunk chunk) {
        float minX = chunk.cx * (float) Chunk.SIZE_X, maxX = minX + Chunk.SIZE_X;
        float minZ = chunk.cz * (float) Chunk.SIZE_Z, maxZ = minZ + Chunk.SIZE_Z;
        float rangeSq = MobSpawner.LOCAL_RANGE * MobSpawner.LOCAL_RANGE;
        for (int i = 0; i < participants.size(); i++) {
            Participant participant = participants.get(i);
            if (!participant.alive()) continue;
            var at = participant.position();
            float dx = at.x() - Math.max(minX, Math.min(maxX, at.x()));
            float dz = at.z() - Math.max(minZ, Math.min(maxZ, at.z()));
            if (dx * dx + dz * dz <= rangeSq) return true;
        }
        return false;
    }

    /** Eviction clears adoption state; releaseEntitiesIn removes bodies after every snapshot succeeds. */
    public void forgetChunk(Chunk chunk) { adoptedChunks.remove(chunk); }

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

    // ------------------------------------------------------ scheduled ticks

    /**
     * Blocks that asked for a tick get it: every world tick since the last
     * call, {@link #TICK_BUDGET} at most each (BLK-04). Only where the world
     * is simulated — the host, a single-player game, the dedicated server.
     *
     * @return ticks run
     */
    public int tickScheduled() {
        world.setTrampleDay(com.mineclone.sim.ecology.Trample.day(clock.gameTime()));
        world.setSimulationDaylight(clock.daylight());
        return world.runScheduledTicks(clock.worldTicks(), TICK_BUDGET, MAX_TICK_LATENESS);
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
        ecology.observeField(c);
        List<DroppedItem> dropped = itemsInChunk(c);
        List<SavedMob> animals = mobsInChunk(c);
        if (!c.modified && dropped.isEmpty() && c.savedItems == 0 && animals.isEmpty() && c.savedMobs == 0)
            return null;
        byte[] blocks = c.copyBlocks(), meta = c.copyMeta();
        world.falling.snapshot(c, blocks, meta, dropped);
        long ticksAt = world.scheduledTickTime();
        long[] ticks = new long[0];
        if (c.hasScheduledTicks()) {
            // Restored ticks the world has not adopted yet still count from their save.
            long origin = c.scheduledTicks().origin();
            ticksAt = origin == com.mineclone.world.ScheduledTicks.ADOPTED ? ticksAt : origin;
            ticks = c.scheduledTicks().entries();
        }
        var sections = new java.util.LinkedHashMap<>(c.copyExtraSections());
        sections.put(EntityPersistence.SECTION, EntityPersistence.encodeRecords(animals));
        sections.put(EntityPersistence.SPAWN_RECEIPT, new byte[] { 1 });
        ChunkSnapshot snapshot = new ChunkSnapshot(c.cx, c.cz, blocks, meta, c.copyChests(), c.copyFurnaces(),
                dropped, sections, ticksAt, ticks, animals);
        c.savedItems = dropped.size();
        c.savedMobs = animals.size();
        c.modified = false;
        return snapshot;
    }

    /**
     * The persistent mobs standing in a chunk — restored ones not yet adopted and
     * records of unknown types first — as records for a write on another thread. The
     * section holds at most {@link com.mineclone.save.SavedMobCodec#MAX_RECORDS}.
     * An over-limit snapshot fails before any record or live body can be discarded.
     */
    private List<SavedMob> mobsInChunk(Chunk c) {
        List<SavedMob> out = c.copyPendingMobs();
        java.util.Set<Long> recorded = new java.util.HashSet<>();
        for (SavedMob saved : out) recorded.add(saved.id());
        entities.assignIds();
        for (Mob m : entities.mobs) {
            if (!m.persistent() || m.dead || !(m.health > 0f) || m.id <= 0 || recorded.contains(m.id))
                continue;
            if (Math.floorDiv((int) Math.floor(m.position.x), Chunk.SIZE_X) != c.cx
                    || Math.floorDiv((int) Math.floor(m.position.z), Chunk.SIZE_Z) != c.cz)
                continue;
            out.add(SavedMob.from(m));
            recorded.add(m.id);
        }
        if (out.size() > com.mineclone.save.SavedMobCodec.MAX_RECORDS)
            throw new IllegalStateException("too many persistent mobs in chunk " + c.cx + "," + c.cz);
        return out;
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
            out.add(new DroppedItem(e.stack.copy(), e.position.x, e.position.y, e.position.z, e.age,e.lifetime));
        }
        return out;
    }
}
