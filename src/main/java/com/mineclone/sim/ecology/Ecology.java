package com.mineclone.sim.ecology;

import com.mineclone.data.ResourceId;
import com.mineclone.save.SavedMob;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Host-owned finite populations. A reserved body is already part of the regional total. */
public final class Ecology implements Materializer.Populations {
    public static final String SAVE_SECTION = "ecology";
    private static final int RULE_MAGIC = 0x4C575231;
    private static final int MAX_REGIONS = 4096, MAX_RESERVATIONS = 65536;
    private final World world;
    private final WorldClock clock;
    private final EntityStore entities;
    private final Participants participants;
    private final Map<Long, EcologyRegion> regions = new LinkedHashMap<>();
    private final Map<Long, EcologyCodec.Reservation> reservations = new LinkedHashMap<>();
    private final Materializer materializer;
    private SpeciesTable species;
    private byte[] sectionTail = new byte[0], opaqueSection, rules = new byte[Integer.BYTES];
    private boolean enabled, supported = true, ruleWritten;
    private String recovery = "";
    private long lastTickDay = Long.MIN_VALUE;

    public Ecology(World world, WorldClock clock, EntityStore entities, Participants participants) {
        this.world = world; this.clock = clock; this.entities = entities; this.participants = participants;
        entities.bindClock(clock);
        materializer = new Materializer(world.seed ^ 0x4C5745434FL);
    }

    public boolean enabled() { return enabled && supported; }
    public boolean supported() { return supported; }
    public String recoveryMessage() { return recovery; }
    private SpeciesTable table() { if (species == null) species = SpeciesTable.get(); return species; }
    public static long day(double gameTime) { return Math.max(0, (long) Math.floor(gameTime / (Math.PI * 2))); }

    /** Restore before any chunk adoption. Newer layouts stay opaque; bad bytes need durable evidence first. */
    public void restore(Map<String, byte[]> sections, Consumer<byte[]> preserveEvidence) {
        regions.clear(); reservations.clear(); sectionTail = new byte[0]; opaqueSection = null;
        enabled = false; supported = true; ruleWritten = false; recovery = ""; lastTickDay = Long.MIN_VALUE;
        rules = sections.getOrDefault("rules", new byte[Integer.BYTES]).clone();
        if (rules.length >= 10 && ByteBuffer.wrap(rules, rules.length - 6, 4).getInt() == RULE_MAGIC) {
            ruleWritten = true;
            if (rules[rules.length - 2] != 1 || (rules[rules.length - 1] & 255) > 1) {
                supported = false;
                recovery = "Living World rules are newer; preserved unchanged";
            } else enabled = rules[rules.length - 1] != 0;
        }
        byte[] bytes = sections.get(SAVE_SECTION);
        if (bytes == null) return;
        if (!supported) { opaqueSection = bytes.clone(); return; }
        try {
            EcologyCodec.Snapshot saved = EcologyCodec.decode(bytes);
            for (EcologyRegion region : saved.regions()) regions.put(region.key(), region.copy());
            reservations.putAll(saved.reservations());
            for (long id : reservations.keySet()) entities.reserveId(id);
            sectionTail = saved.tail();
            var allSpecies = new java.util.HashSet<ResourceId>();
            for (var definition : table().definitions()) allSpecies.add(definition.id());
            for (var region : regions.values()) allSpecies.addAll(region.populations().keySet());
            if (allSpecies.size() > SpeciesTable.MAX_SPECIES) {
                opaqueSection = bytes.clone(); supported = false;
                recovery = "Ecology species table exceeds this build's limit; preserved unchanged";
                return;
            }
            refreshProtection();
        } catch (EcologyCodec.NewerVersionException newer) {
            opaqueSection = bytes.clone(); supported = false;
            recovery = "Living World data are newer; preserved unchanged";
        } catch (IOException | IllegalArgumentException corrupt) {
            try {
                preserveEvidence.accept(bytes.clone());
                recovery = "Damaged ecology preserved separately; populations rebuilt";
            } catch (RuntimeException failed) {
                opaqueSection = bytes.clone(); supported = false;
                recovery = "Cannot preserve damaged ecology; Living World disabled";
            }
        }
    }

    public void setEnabled(boolean value) {
        requireSupported();
        enabled = value; ruleWritten = true;
        if (value) {
            seedAroundParticipants(); registerLivingBodies(); registerPendingBodies();
            catchUp(day(clock.gameTime()));
        }
    }

    /** One same day sequence for ordinary play, return after absence, and the owner command. */
    public void advanceDays(int days) {
        requireSupported();
        if (days < 1 || days > 60) throw new IllegalArgumentException("Ecology advance must be 1..60 days");
        seedAroundParticipants(); registerLivingBodies();
        clock.addHours(24.0 * days);
        catchUp(day(clock.gameTime()));
    }

    public void tick() {
        if (!enabled()) return;
        seedAroundParticipants(); registerLivingBodies();
        long now = day(clock.gameTime());
        if (now != lastTickDay) { catchUp(now); lastTickDay = now; }
        // Only bodies that have no loaded chunk to save fold back into the abstract population.
        entities.mobs.removeIf(m -> {
            if (!m.persistent() || m.dead || world.getChunkIfExists(
                    Math.floorDiv((int) Math.floor(m.position.x), Chunk.SIZE_X),
                    Math.floorDiv((int) Math.floor(m.position.z), Chunk.SIZE_Z)) != null) return false;
            for (int i = 0; i < participants.size(); i++)
                if (m.position.distanceSquared(participants.get(i).position()) <= 128f * 128f) return false;
            var held = reservations.remove(m.id);
            if (held != null) {
                EcologyRegion region = regions.get(held.regionKey());
                if (region != null) region.protectedCount(held.species(), Math.max(0, region.protectedCount(held.species()) - 1));
            }
            return true;
        });
    }

    public int materialize(float daylight) {
        if (!enabled()) return 0;
        return materializer.tryMaterialize(world, entities.mobs, participants, daylight, this);
    }

    private void seedAroundParticipants() {
        for (int i = 0; i < participants.size(); i++) {
            var pos = participants.get(i).position();
            int rx = regionCoordinate(pos.x()), rz = regionCoordinate(pos.z());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) region(rx + dx, rz + dz);
        }
    }

    private static int regionCoordinate(float value) { return Math.floorDiv((int) Math.floor(value), EcologyRegion.SIZE); }
    public EcologyRegion regionAt(float x, float z) { return region(regionCoordinate(x), regionCoordinate(z)); }

    private EcologyRegion region(int x, int z) {
        long key = EcologyRegion.key(x, z);
        EcologyRegion old = regions.get(key);
        if (old != null) return old;
        if (regions.size() >= MAX_REGIONS) return null;
        EcologyRegion added = new EcologyRegion(x, z, day(clock.gameTime()));
        // Sampling climate is cheap and never generates terrain. Mixed regions use an area-weighted capacity.
        for (var definition : table().definitions()) {
            int sum = 0;
            for (int bx = 0; bx < 4; bx++) for (int bz = 0; bz < 4; bz++)
                sum += definition.capacity(world.biomes.biomeAt(x * 256 + bx * 64 + 32, z * 256 + bz * 64 + 32));
            int capacity = Math.round(sum / 16f);
            added.capacity(definition.id(), capacity);
            added.population(definition.id(), (int) Math.floor(capacity * 0.7));
        }
        regions.put(key, added);
        return added;
    }

    private void catchUp(long target) {
        long first = target;
        for (var region : regions.values()) first = Math.min(first, region.lastDay());
        if (first >= target) return;
        refreshHabitat();
        refreshProtection();
        first = Math.max(first, target - 60);
        for (long next = first; next < target; ) {
            ++next;
            List<EcologyRegion> result = EcologyModel.step(regions.values(), table(), next, world.seed);
            regions.clear(); for (var region : result) regions.put(region.key(), region);
        }
    }

    private void registerLivingBodies() {
        entities.assignIds();
        for (Mob mob : entities.mobs) if (mob.persistent() && !mob.dead) adopted(mob);
    }

    /** Enabling in an already open world also accounts for bodies still parked in loaded chunks. */
    private void registerPendingBodies() {
        for (Chunk chunk : world.getLoadedChunks())
            for (SavedMob saved : chunk.copyPendingMobs()) {
                entities.reserveId(saved.id());
                adopted(saved);
            }
    }

    /** A restored body keeps its original reservation even if it walked across a region boundary. */
    public void adopted(Mob mob) {
        if (mob.dead || !mob.persistent()) return;
        adopted(mob.id, mob.type, mob.position.x, mob.position.z);
    }

    /** A saved animal already belongs to the total even while its body is too far to activate. */
    public void adopted(SavedMob saved) {
        if (!saved.persistent() || !(saved.health() > 0)) return;
        MobType type = MobType.byName(saved.type());
        if (type != null) adopted(saved.id(), type, saved.x(), saved.z());
    }

    private void adopted(long id, MobType type, float x, float z) {
        if (!enabled() || id <= 0 || reservations.containsKey(id) || reservations.size() >= MAX_RESERVATIONS) return;
        var definition = table().byType(type);
        if (definition == null) return;
        EcologyRegion region = regionAt(x, z);
        if (region == null) return;
        int represented = represented(region.x(), region.z(), definition.id());
        // Old worlds already contain animals: adopt those bodies rather than deleting them during migration.
        region.population(definition.id(), Math.max(region.population(definition.id()), represented + 1));
        entities.reserveId(id);
        reservations.put(id, new EcologyCodec.Reservation(id, region.x(), region.z(), definition.id()));
        region.protectedCount(definition.id(), represented + 1);
    }

    /** A genuine breeding birth adds an animal; materialization only reserves one. */
    public void born(Mob child) {
        if (!enabled() || child.dead || reservations.containsKey(child.id)) return;
        var definition = table().byType(child.type);
        if (definition == null) return;
        EcologyRegion region = regionAt(child.position.x, child.position.z);
        if (region == null) return;
        region.population(definition.id(), Math.min(SpeciesTable.MAX_POPULATION, region.population(definition.id()) + 1));
        entities.assignId(child);
        adopted(child);
    }

    /** Exactly once at death, including a predator bite, player attack, fire or fall. */
    public void died(Mob mob) {
        if (!supported || !mob.persistent()) return;
        EcologyCodec.Reservation held = reservations.remove(mob.id);
        if (held == null) return;
        EcologyRegion region = regions.get(EcologyRegion.key(held.regionX(), held.regionZ()));
        if (region != null) {
            region.population(held.species(), Math.max(0, region.population(held.species()) - 1));
            region.protectedCount(held.species(), Math.max(0, region.protectedCount(held.species()) - 1));
            region.addLoss(mob.killedByParticipant ? EcologyRegion.LossCause.PLAYER : mob.eaten ? EcologyRegion.LossCause.PREDATOR
                    : mob.burning ? EcologyRegion.LossCause.FIRE : EcologyRegion.LossCause.OTHER);
        }
    }

    private int represented(int x, int z, ResourceId species) {
        EcologyRegion region = regions.get(EcologyRegion.key(x, z));
        return region == null ? 0 : region.protectedCount(species);
    }

    private void refreshProtection() {
        for (var region : regions.values())
            for (ResourceId id : new ArrayList<>(region.protectedCounts().keySet()))
                if (table().byId(id) != null) region.protectedCount(id, 0);
        for (var held : reservations.values()) {
            EcologyRegion region = regions.get(held.regionKey());
            if (region != null) region.protectedCount(held.species(), 0);
        }
        for (var held : reservations.values()) {
            EcologyRegion region = regions.get(EcologyRegion.key(held.regionX(), held.regionZ()));
            if (region != null) region.protectedCount(held.species(), region.protectedCount(held.species()) + 1);
        }
    }

    @Override public int available(MobType type, float x, float z) {
        if (!enabled()) return 0;
        var definition = table().byType(type);
        EcologyRegion region = regionAt(x, z);
        return definition == null || region == null ? 0 : Math.max(0,
                region.population(definition.id()) - represented(region.x(), region.z(), definition.id()));
    }

    @Override public boolean claim(Mob mob) {
        if (!enabled() || reservations.size() >= MAX_RESERVATIONS || available(mob.type, mob.position.x, mob.position.z) == 0) return false;
        entities.assignId(mob);
        while (reservations.containsKey(mob.id)) { mob.id = 0; entities.assignId(mob); }
        var definition = table().byType(mob.type);
        EcologyRegion region = regionAt(mob.position.x, mob.position.z);
        reservations.put(mob.id, new EcologyCodec.Reservation(mob.id, region.x(), region.z(), definition.id()));
        region.protectedCount(definition.id(), region.protectedCount(definition.id()) + 1);
        return true;
    }

    @Override public int groupMin(MobType type) { return table().byType(type).groupMin(); }
    @Override public int groupMax(MobType type) { return table().byType(type).groupMax(); }

    /** Capture detached bytes on the simulation thread, before the save queue takes ownership. */
    public void writeSections(Map<String, byte[]> into) {
        // A close/save may run after damage but before the next mob tick. The
        // corpse is not saved, so release its count now; presentation still runs
        // normally at deathEffectsDone and the later died call is idempotent.
        for (Mob mob : entities.mobs) if (mob.dead) died(mob);
        if (enabled()) registerLivingBodies();
        if (opaqueSection != null) into.put(SAVE_SECTION, opaqueSection.clone());
        else if (!regions.isEmpty()) {
            refreshProtection();
            try {
                into.put(SAVE_SECTION, EcologyCodec.encode(new EcologyCodec.Snapshot(new ArrayList<>(regions.values()), reservations, sectionTail)));
            } catch (IOException impossible) { throw new java.io.UncheckedIOException(impossible); }
        } else into.remove(SAVE_SECTION);
        if (ruleWritten && supported) {
            int base = rules.length >= 10 && ByteBuffer.wrap(rules, rules.length - 6, 4).getInt() == RULE_MAGIC
                    ? rules.length - 6 : Math.max(4, rules.length);
            byte[] out = Arrays.copyOf(rules, base + 6);
            ByteBuffer.wrap(out, base, 4).putInt(RULE_MAGIC); out[base + 4] = 1; out[base + 5] = (byte) (enabled ? 1 : 0);
            into.put("rules", out);
        }
    }

    public String[] info(float x, float z) {
        if (!supported) return new String[] { "Ecology unavailable", recovery };
        EcologyRegion region = regionAt(x, z);
        if (region == null) return new String[] { "Ecology: region limit reached" };
        var lines = new ArrayList<String>();
        lines.add("Ecology " + (enabled() ? "ON" : "OFF") + " | region " + region.x() + ", " + region.z() + " | day " + region.lastDay());
        lines.add("Species: total / capacity | bodies | change over 3 days");
        for (var definition : table().definitions()) {
            int[] trend = region.trend(definition.id()); int delta = trend[0] + trend[1] + trend[2];
            lines.add(definition.type().name() + ": " + region.population(definition.id()) + " / " + region.capacity(definition.id())
                    + " | " + represented(region.x(), region.z(), definition.id()) + " | " + (delta >= 0 ? "+" : "") + delta);
        }
        lines.add("Lairs: " + region.lairs().size() + " | burn scars: " + region.scars().size());
        lines.add("Losses: hunt " + region.lossCount(EcologyRegion.LossCause.PLAYER) + ", predators "
                + region.lossCount(EcologyRegion.LossCause.PREDATOR) + ", fire " + region.lossCount(EcologyRegion.LossCause.FIRE));
        if (!recovery.isEmpty()) lines.add(recovery);
        return lines.toArray(String[]::new);
    }

    private void requireSupported() { if (!supported) throw new IllegalStateException(recovery); }

    /** Loaded land updates the per-chunk greenery sample; unseen terrain keeps its last known habitat. */
    private void refreshHabitat() {
        var changed = new java.util.HashSet<Long>();
        for (Chunk chunk : world.getLoadedChunks()) {
            EcologyRegion region = regions.get(EcologyRegion.key(Math.floorDiv(chunk.cx, 16), Math.floorDiv(chunk.cz, 16)));
            if (region == null) continue;
            int value = 0;
            for (int x : new int[] { 4, 12 }) for (int z : new int[] { 4, 12 }) {
                com.mineclone.world.BlockType surface = com.mineclone.world.BlockType.AIR;
                for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
                    var block = chunk.get(x, y, z);
                    if (block.solid || block == com.mineclone.world.BlockType.WATER) { surface = block; break; }
                }
                value += switch (surface) {
                    case GRASS, LEAVES, PODZOL -> 255;
                    case DRY_GRASS -> 220;
                    case SNOWY_GRASS, SNOW_LAYER, SAND, RED_SAND -> 180;
                    case DIRT, GRAVEL -> 96;
                    case ASH -> 16;
                    case WATER -> 0;
                    default -> 32;
                };
            }
            byte[] cells = region.greenery();
            cells[Math.floorMod(chunk.cx, 16) * 16 + Math.floorMod(chunk.cz, 16)] = (byte) (value / 4);
            region.greenery(cells); changed.add(region.key());
        }
        for (long key : changed) {
            EcologyRegion region = regions.get(key);
            for (var definition : table().definitions()) {
                int sum = 0;
                for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++)
                    sum += definition.capacity(world.biomes.biomeAt(region.x() * 256 + x * 64 + 32, region.z() * 256 + z * 64 + 32));
                region.capacity(definition.id(), (int) Math.round(sum / 16.0 * region.averageGreenery()));
            }
        }
    }
}
