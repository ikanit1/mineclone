package com.mineclone.sim.ecology;

import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.world.*;
import com.mineclone.world.behavior.SpiderNestBehavior;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.shape.BodyCollision;
import org.joml.Vector3f;
import java.io.IOException;
import java.util.*;

/** Host lair lifecycle: finite persistent bodies, physical returns and loaded cave founding. */
public final class LairRuntime {
    private static final List<int[]> OFFSETS = offsets();
    private static List<int[]> offsets() {
        List<int[]> result = new ArrayList<>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) if (x != 0 || z != 0) result.add(new int[] { x, z });
        result.sort(Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
        return List.copyOf(result);
    }
    private record Slot(Lair lair, byte[] opaque) {}
    private final World world;
    private final WorldClock clock;
    private final EntityStore entities;
    private final Participants participants;
    private final Map<Long, List<Slot>> records = new LinkedHashMap<>();
    private final Map<Long, Lair> bodies = new HashMap<>();
    private final Set<Chunk> observed = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Long> initialChecked = new HashSet<>();
    private final Colonizer colonizer = new Colonizer();
    private float observationTimer;

    public LairRuntime(World world, WorldClock clock, EntityStore entities, Participants participants) {
        this.world = world; this.clock = clock; this.entities = entities; this.participants = participants;
    }
    public void clear() { records.clear(); bodies.clear(); observed.clear(); initialChecked.clear(); }

    public void restore(Collection<EcologyRegion> regions, Set<Long> animalIds) throws IOException {
        clear();
        for (EcologyRegion region : regions) {
            List<Slot> slots = new ArrayList<>();
            Set<String> positions = new HashSet<>();
            for (byte[] bytes : region.lairs()) {
                Lair lair = Lair.decode(bytes);
                if (lair != null) {
                    if (lair.type() != MobType.SPIDER || !lair.id().equals(settings().id()))
                        throw new Lair.UnsupportedDefinitionException("unsupported lair " + lair.id() + "/" + lair.type());
                    if (Math.floorDiv(lair.x(), 256) != region.x() || Math.floorDiv(lair.z(), 256) != region.z()
                            || !positions.add(lair.x() + ":" + lair.y() + ":" + lair.z()))
                        throw new IOException("lair region or duplicate heart");
                    for (long id : lair.bodyIds()) {
                        if (animalIds.contains(id) || bodies.putIfAbsent(id, lair) != null)
                            throw new IOException("duplicate lair body ID");
                        entities.reserveId(id);
                    }
                }
                slots.add(new Slot(lair, lair == null ? bytes.clone() : null));
            }
            if (!slots.isEmpty()) records.put(region.key(), slots);
        }
    }
    public List<Lair> lairs(long regionKey) {
        List<Lair> result = new ArrayList<>();
        for (Slot slot : records.getOrDefault(regionKey, List.of())) if (slot.lair != null) result.add(slot.lair);
        return Collections.unmodifiableList(result);
    }
    public void flush(Collection<EcologyRegion> regions) {
        for (EcologyRegion region : regions) {
            List<Slot> slots = records.get(region.key());
            if (slots == null) continue;
            List<byte[]> bytes = new ArrayList<>();
            try { for (Slot slot : slots) bytes.add(slot.lair == null ? slot.opaque.clone() : slot.lair.encode()); }
            catch (IOException invalid) { throw new java.io.UncheckedIOException(invalid); }
            region.lairs(bytes);
        }
    }
    private long key(int x, int z) { return EcologyRegion.key(Math.floorDiv(x, 256), Math.floorDiv(z, 256)); }
    private LairTable.Definition settings() { return LairTable.get().byType(MobType.SPIDER); }
    private boolean register(Lair lair) {
        List<Slot> slots = records.computeIfAbsent(key(lair.x(), lair.z()), ignored -> new ArrayList<>());
        if (slots.size() >= 4) {
            Slot replace = null;
            for (Slot slot : slots) if (slot.lair != null && !slot.lair.alive() && slot.lair.bodyIds().isEmpty()
                    && (replace == null || slot.lair.destroyedDay() < replace.lair.destroyedDay())) replace = slot;
            if (replace == null) return false;
            slots.remove(replace);
        }
        slots.add(new Slot(lair, null));
        return true;
    }
    private boolean knownHeart(int x, int y, int z) {
        for (Lair lair : lairs(key(x, z))) if (lair.x() == x && lair.y() == y && lair.z() == z) return true;
        return false;
    }
    private void observe(Collection<EcologyRegion> regions, int budget, long day) {
        Set<Long> keys = new HashSet<>();
        for (EcologyRegion region : regions) keys.add(region.key());
        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunk.isReadOnly() || !keys.contains(EcologyRegion.key(Math.floorDiv(chunk.cx, 16), Math.floorDiv(chunk.cz, 16)))) continue;
            if (!observed.contains(chunk) && budget > 0) {
                observed.add(chunk); budget--;
                initialChecked.remove(EcologyRegion.key(Math.floorDiv(chunk.cx, 16), Math.floorDiv(chunk.cz, 16)));
                if (chunk.extraSection(LairSpots.SECTION) == null) {
                    try { chunk.setExtraSection(LairSpots.SECTION, LairSpots.discover(world, chunk).encode()); }
                    catch (IOException invalid) { throw new java.io.UncheckedIOException(invalid); }
                }
            }
            for (int i = 0; i < chunk.nestCellCount(); i++) {
                int cell = chunk.nestCellAt(i), x = chunk.cx * 16 + cell % 16, y = cell / 256,
                        z = chunk.cz * 16 + cell / 16 % 16;
                if (!knownHeart(x, y, z) && register(new Lair(x, y, z, day, settings())))
                    scheduleFirstWeb(x, y, z, day);
            }
        }
    }
    private void checkHearts(long day) {
        for (List<Slot> slots : records.values()) for (Slot slot : slots) {
            Lair lair = slot.lair;
            if (lair == null || !lair.alive()) continue;
            Chunk chunk = world.getChunkIfExists(Math.floorDiv(lair.x(), 16), Math.floorDiv(lair.z(), 16));
            if (chunk != null && !chunk.isReadOnly() && world.getBlock(lair.x(), lair.y(), lair.z()) != BlockType.SPIDER_NEST)
                lair.destroy(day);
        }
    }
    /** A save or owner time change may occur before the next mob frame. */
    public void refreshHearts() { checkHearts(Ecology.day(clock.gameTime())); }

    private void scheduleFirstWeb(int x, int y, int z, long day) {
        // Replay can found a heart before the target clock day, while the block
        // dispatcher still points at the pre-replay tick. Anchor silk to birth.
        long born = clock.logicalDayTicks(day), interval = SpiderNestBehavior.INTERVAL;
        long due = born > ScheduledTicks.MAX_DUE - interval ? ScheduledTicks.MAX_DUE : born + interval;
        world.scheduleTickAt(x, y, z, BlockType.SPIDER_NEST, due);
    }
    private void found(EcologyRegion region, long day, boolean initial) {
        List<Lair> existing = lairs(region.key());
        if (existing.stream().anyMatch(Lair::alive)) return;
        if (initial && !existing.isEmpty()) return; // Reopening a cleared region never bypasses its delay.
        Colonizer.Spot spot = colonizer.select(world, region, existing, participants, day, initial);
        if (spot == null) return;
        Lair lair = new Lair(spot.x(), spot.y(), spot.z(), day, settings());
        if (!register(lair)) return;
        world.setBlock(spot.x(), spot.y(), spot.z(), BlockType.SPIDER_NEST);
        scheduleFirstWeb(spot.x(), spot.y(), spot.z(), day);
    }
    public void stepDay(Collection<EcologyRegion> regions, long day) {
        observe(regions, 32, day); checkHearts(day);
        for (int i = 0; i < participants.size(); i++) {
            var p = participants.get(i).position(); colonizer.recordTrail(p.x(), p.y(), p.z(), day);
        }
        for (List<Slot> slots : records.values()) for (Slot slot : slots) if (slot.lair != null) slot.lair.advance(day);
        for (EcologyRegion region : regions) found(region, day, false);
        flush(regions);
    }
    public void died(Mob mob) {
        Lair lair = bodies.remove(mob.id);
        if (lair != null) lair.died(mob.id);
    }
    public void fold(Mob mob) {
        Lair lair = bodies.remove(mob.id);
        if (lair != null) lair.returned(mob.id);
    }
    private Vector3f place(Lair lair, int offset) {
        for (int n = 0; n < OFFSETS.size(); n++) {
            int[] delta = OFFSETS.get(Math.floorMod(n + offset, OFFSETS.size()));
            int dx = delta[0], dz = delta[1];
            int x = lair.x() + dx, z = lair.z() + dz;
            Chunk chunk = world.getChunkIfExists(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            if (chunk == null || chunk.isReadOnly()) continue;
            float fx = x + .5f, fy = lair.y() + .001f, fz = z + .5f;
            if (!standable(fx, fy, fz)) continue;
            boolean occupied = false;
            for (Mob mob : entities.mobs) if (!mob.dead && mob.position.distanceSquared(fx, fy, fz) < 1.7f) { occupied = true; break; }
            if (!occupied) return new Vector3f(fx, fy, fz);
        }
        return null;
    }
    private boolean standable(float x, float y, float z) {
        float half = MobType.SPIDER.width * .5f;
        for (int sx = (int) Math.floor(x - half); sx <= (int) Math.floor(x + half); sx++)
            for (int sz = (int) Math.floor(z - half); sz <= (int) Math.floor(z + half); sz++) {
                Chunk chunk = world.getChunkIfExists(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16));
                if (chunk == null || chunk.isReadOnly()) return false;
                BlockType floor = world.getBlock(sx, (int) Math.floor(y) - 1, sz);
                if (!floor.solid || !com.mineclone.world.shape.Shapes.of(floor).fullCube(
                        world.getBlockMeta(sx, (int) Math.floor(y) - 1, sz))) return false;
            }
        return !BodyCollision.overlaps(world, x, y, z, MobType.SPIDER.width, MobType.SPIDER.height);
    }
    private boolean capAllows(Lair lair) {
        int scale = Math.max(1, Math.min(MobSpawner.MAX_CAP_SCALE, participants.size())), total = 0;
        for (Mob mob : entities.mobs) if (mob.type.hostile && !mob.dead) total++;
        if (total >= MobSpawner.HOSTILE_CAP * scale) return false;
        boolean nearby = false;
        for (int i = 0; i < participants.size(); i++) {
            var p = participants.get(i).position();
            if (p.distanceSquared(lair.x() + .5f, lair.y(), lair.z() + .5f) > 128f * 128f) continue;
            nearby = true; int local = 0;
            for (Mob mob : entities.mobs) if (mob.type.hostile && !mob.dead && mob.position.distanceSquared(p) <= 128f * 128f) local++;
            if (local >= MobSpawner.HOSTILE_CAP) return false;
        }
        return nearby;
    }
    public void tick(Collection<EcologyRegion> regions, float dt, boolean spawnAllowed) {
        long day = Ecology.day(clock.gameTime());
        checkHearts(day); // A broken heart cannot emit or recall a body during the observation interval.
        observationTimer -= dt;
        if (observationTimer <= 0f) {
            observationTimer = .5f; observe(regions, 2, day);
            for (int i = 0; i < participants.size(); i++) {
                var p = participants.get(i).position(); colonizer.recordTrail(p.x(), p.y(), p.z(), day);
            }
            for (EcologyRegion region : regions) if (!initialChecked.contains(region.key())) {
                found(region, day, true);
                if (!lairs(region.key()).isEmpty()) initialChecked.add(region.key());
            }
        }
        boolean night = clock.daylight() < MobSpawner.NIGHT_DAYLIGHT;
        Iterator<Mob> iterator = entities.mobs.iterator();
        while (iterator.hasNext()) {
            Mob mob = iterator.next();
            if (mob.type != MobType.SPIDER) continue;
            Lair lair = bodies.get(mob.id); mob.lairDestination = null;
            if (lair == null || mob.dead) continue;
            if (world.rules.difficulty() == Difficulty.PEACEFUL) { fold(mob); iterator.remove(); continue; }
            if (!lair.alive()) continue;
            Chunk heart = world.getChunkIfExists(Math.floorDiv(lair.x(), 16), Math.floorDiv(lair.z(), 16));
            if (heart == null || heart.isReadOnly()) continue;
            float dx = mob.position.x - (lair.x() + .5f), dz = mob.position.z - (lair.z() + .5f);
            if (!night && dx * dx + dz * dz <= 9f && Math.abs(mob.position.y - lair.y()) < 1.5f) {
                fold(mob); iterator.remove(); continue;
            }
            if (!night || dx * dx + dz * dz > lair.radius() * lair.radius()) {
                Vector3f home = place(lair, 0);
                if (home != null) mob.lairDestination = home;
            }
        }
        if (!night || !spawnAllowed || world.rules.difficulty() == Difficulty.PEACEFUL) return;
        for (List<Slot> slots : records.values()) for (Slot slot : slots) {
            Lair lair = slot.lair;
            if (lair == null || !lair.alive() || lair.available() <= 0 || !capAllows(lair)) continue;
            Chunk chunk = world.getChunkIfExists(Math.floorDiv(lair.x(), 16), Math.floorDiv(lair.z(), 16));
            if (chunk == null || chunk.isReadOnly()) continue;
            Vector3f at = place(lair, lair.bodyIds().size() * 7);
            if (at == null) continue;
            Mob mob = new Mob(MobType.SPIDER, at.x, at.y, at.z,
                    new Random(world.seed ^ ((long) lair.x() << 32) ^ lair.z() ^ clock.worldTicks()));
            entities.assignId(mob);
            if (lair.claim(mob.id)) { mob.persistent = true; mob.onGround = true; bodies.put(mob.id, lair); entities.mobs.add(mob); }
        }
    }
}
