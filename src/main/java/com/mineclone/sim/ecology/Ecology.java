package com.mineclone.sim.ecology;

import com.mineclone.data.ResourceId;
import com.mineclone.save.SavedMob;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.world.Chunk;
import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.shape.BodyCollision;
import org.joml.Vector3f;
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
    private final LairRuntime lairs;
    private final BurnScars scars;
    private float scarTimer;
    private static final ResourceId RABBIT = ResourceId.of("rabbit");
    private FieldHabitat fields = new FieldHabitat();
    private byte[] opaqueFields;
    private SpeciesTable species;
    private byte[] sectionTail = new byte[0], opaqueSection, rules = new byte[Integer.BYTES];
    private boolean enabled, supported = true, ruleWritten;
    private String recovery = "";
    private long lastTickDay = Long.MIN_VALUE;

    public Ecology(World world, WorldClock clock, EntityStore entities, Participants participants) {
        this.world = world; this.clock = clock; this.entities = entities; this.participants = participants;
        entities.bindClock(clock);
        materializer = new Materializer(world.seed ^ 0x4C5745434FL);
        lairs = new LairRuntime(world, clock, entities, participants);
        scars = new BurnScars(world, clock);
        world.setFuelBurnedHandler(this::fuelBurned);
        world.setBurnedHabitat((x, y, z) -> enabled() && scars.damagedAt(x, y, z, day(clock.gameTime())));
        world.setNaturalGrowthSpace(this::growthSpaceClear);
    }

    public boolean enabled() { return enabled && supported; }
    public boolean supported() { return supported; }
    public LairRuntime lairs() { return lairs; }
    public BurnScars scars() { return scars; }
    public void retired(Mob mob) { if (supported) lairs.fold(mob); }
    public void tickLairs(float dt, boolean spawnAllowed) {
        if (enabled()) lairs.tick(regions.values(), dt, spawnAllowed);
        else for (Mob mob : entities.mobs) mob.lairDestination = null;
    }
    public String recoveryMessage() { return recovery; }
    private SpeciesTable table() { if (species == null) species = SpeciesTable.get(); return species; }
    public static long day(double gameTime) { return Math.max(0, (long) Math.floor(gameTime / (Math.PI * 2))); }

    /** Restore before any chunk adoption. Newer layouts stay opaque; bad bytes need durable evidence first. */
    public void restore(Map<String, byte[]> sections, Consumer<byte[]> preserveEvidence) {
        lairs.clear(); world.setLivingWorldEnabled(false);
        scars.clear(); scarTimer = 0;
        regions.clear(); reservations.clear(); sectionTail = new byte[0]; opaqueSection = null;
        fields = new FieldHabitat(); opaqueFields = null;
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
        if (bytes == null) { restoreFields(sections, preserveEvidence); return; }
        if (!supported) {
            opaqueSection = bytes.clone(); restoreFields(sections, preserveEvidence); return;
        }
        try {
            EcologyCodec.Snapshot saved = EcologyCodec.decode(bytes);
            for (EcologyRegion region : saved.regions()) regions.put(region.key(), region.copy());
            reservations.putAll(saved.reservations());
            for (long id : reservations.keySet()) entities.reserveId(id);
            sectionTail = saved.tail();
            lairs.restore(regions.values(), reservations.keySet());
            scars.restore(regions.values());
            var allSpecies = new java.util.HashSet<ResourceId>();
            for (var definition : table().definitions()) allSpecies.add(definition.id());
            for (var region : regions.values()) allSpecies.addAll(region.populations().keySet());
            if (allSpecies.size() > SpeciesTable.MAX_SPECIES) {
                opaqueSection = bytes.clone(); supported = false;
                recovery = "Ecology species table exceeds this build's limit; preserved unchanged";
                restoreFields(sections, preserveEvidence);
                return;
            }
            refreshProtection();
        } catch (EcologyCodec.NewerVersionException | Lair.NewerVersionException | Lair.UnsupportedDefinitionException
                 | BurnScars.NewerVersionException newer) {
            opaqueSection = bytes.clone(); supported = false;
            recovery = "Living World data are newer; preserved unchanged";
        } catch (IOException | IllegalArgumentException corrupt) {
            try {
                preserveEvidence.accept(bytes.clone());
                regions.clear(); reservations.clear(); lairs.clear(); scars.clear(); sectionTail = new byte[0];
                recovery = "Damaged ecology preserved separately; populations rebuilt";
            } catch (RuntimeException failed) {
                opaqueSection = bytes.clone(); supported = false;
                recovery = "Cannot preserve damaged ecology; Living World disabled";
            }
        }
        restoreFields(sections, preserveEvidence);
    }

    private void restoreFields(Map<String, byte[]> sections, Consumer<byte[]> preserveEvidence) {
        byte[] bytes = sections.get(FieldHabitat.SECTION);
        if (bytes == null) { world.setLivingWorldEnabled(enabled()); return; }
        if (!supported) { opaqueFields = bytes.clone(); world.setLivingWorldEnabled(false); return; }
        try { fields = FieldHabitat.decode(bytes); }
        catch (FieldHabitat.NewerVersionException newer) {
            opaqueFields = bytes.clone(); supported = false;
            opaqueSection = sections.get(SAVE_SECTION) == null ? null : sections.get(SAVE_SECTION).clone();
            recovery = "Living World field data are newer; preserved unchanged";
        } catch (IOException | IllegalArgumentException damaged) {
            try {
                preserveEvidence.accept(bytes.clone());
                for (EcologyRegion region : regions.values()) region.foodBonus(RABBIT, 0);
                recovery = "Damaged field habitat preserved separately; rebuilt from loaded land";
            } catch (RuntimeException failed) {
                opaqueFields = bytes.clone(); supported = false;
                opaqueSection = sections.get(SAVE_SECTION) == null ? null : sections.get(SAVE_SECTION).clone();
                recovery = "Cannot preserve damaged field habitat; Living World disabled";
            }
        }
        world.setLivingWorldEnabled(enabled());
    }

    public void setEnabled(boolean value) {
        requireSupported();
        enabled = value; ruleWritten = true;
        world.setLivingWorldEnabled(enabled());
        scarTimer = 0;
        if (!value) for (Mob mob : entities.mobs) mob.burnDestination = null;
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
        lairs.refreshHearts();
        clock.addHours(24.0 * days);
        catchUp(day(clock.gameTime()));
    }

    public void tick() {
        world.setLivingWorldEnabled(enabled());
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
            lairs.fold(m);
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
        refreshFields();
        refreshProtection();
        first = Math.max(first, target - 60);
        for (long next = first; next < target; ) {
            ++next;
            if (enabled()) {
                scars.stepDay(regions.values(), next);
                refreshHabitat(next);
                lairs.stepDay(regions.values(), next);
            }
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
        lairs.died(mob);
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
        if (enabled()) { registerLivingBodies(); refreshFields(); }
        if (supported) {
            lairs.refreshHearts(); lairs.flush(regions.values());
            if (enabled()) {
                long before = scars.revision();
                scars.tickLoaded(regions.values(), day(clock.gameTime()));
                if (before != scars.revision()) refreshHabitat();
            }
            scars.flush(regions.values());
        }
        if (opaqueFields != null) into.put(FieldHabitat.SECTION, opaqueFields.clone());
        else if (!fields.empty()) {
            try { into.put(FieldHabitat.SECTION, fields.encode()); }
            catch (IOException impossible) { throw new java.io.UncheckedIOException(impossible); }
        } else into.remove(FieldHabitat.SECTION);
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
        if (!Float.isFinite(x) || !Float.isFinite(z)) return new String[] { "Ecology: invalid diagnostic position" };
        int rx = regionCoordinate(x), rz = regionCoordinate(z);
        EcologyRegion region = regions.get(EcologyRegion.key(rx, rz));
        var lines = new ArrayList<String>();
        lines.add("Ecology " + (enabled() ? "ON" : "OFF") + " | region " + rx + ", " + rz
                + (region == null ? " | not observed" : " | snapshot day " + region.lastDay()));
        lines.add("Known locations: current + 8 adjacent regions; horizontal distance");
        nearbyInfo(lines, x, z, rx, rz);
        if (region == null) {
            lines.add("Population, capacity and model trend unavailable until region is observed");
            return lines.toArray(String[]::new);
        }
        lines.add("Species: total / capacity | bodies | model 3d sum [oldest->latest]");
        for (var definition : table().definitions()) {
            int[] trend = region.trend(definition.id()); int delta = trend[0] + trend[1] + trend[2];
            lines.add(definition.type().name() + ": " + region.population(definition.id()) + " / " + EcologyModel.effectiveCapacity(region, definition.id())
                    + " | " + represented(region.x(), region.z(), definition.id()) + " | " + signed(delta)
                    + " [" + signed(trend[0]) + "," + signed(trend[1]) + "," + signed(trend[2]) + "]");
        }
        lines.add("Model trend starts with zero slots; direct births/deaths are excluded");
        lines.add("Fields: " + fields.crops(region.key()) + " crop estimate | rabbit food +" + region.foodBonus(RABBIT));
        lines.add("Lairs in region: " + lairs.lairs(region.key()).size());
        for (Lair lair : lairs.lairs(region.key())) lines.add("Spider nest " + lair.x() + "," + lair.y() + "," + lair.z()
                + (lair.alive() ? " alive" : " destroyed") + " | spiders " + lair.population() + "/" + lair.capacity()
                + " | outside " + lair.bodyIds().size());
        lines.add("Losses: hunt " + region.lossCount(EcologyRegion.LossCause.PLAYER) + ", predators "
                + region.lossCount(EcologyRegion.LossCause.PREDATOR) + ", fire " + region.lossCount(EcologyRegion.LossCause.FIRE));
        if (!recovery.isEmpty()) lines.add(recovery);
        return lines.toArray(String[]::new);
    }

    public String[] lairInfo(float x, float z) {
        if (!supported) return new String[] { "Ecology unavailable", recovery };
        if (!Float.isFinite(x) || !Float.isFinite(z)) return new String[] { "Ecology: invalid diagnostic position" };
        int rx = regionCoordinate(x), rz = regionCoordinate(z);
        long key = EcologyRegion.key(rx, rz);
        var lines = new ArrayList<String>();
        lines.add("Spider lairs | region " + rx + "," + rz);
        List<Lair> local = lairs.lairs(key);
        for (Lair lair : local) lines.add("Nest " + lair.x() + "," + lair.y() + "," + lair.z()
                + (lair.alive() ? " alive" : " destroyed day " + lair.destroyedDay())
                + " | spiders " + lair.population() + "/" + lair.capacity() + " | outside " + lair.bodyIds().size());
        if (local.isEmpty()) lines.add("No known spider nest in this region");
        lines.add("Known locations: current + 8 adjacent regions; horizontal distance");
        nearestLairInfo(lines, nearestLair(x, z, rx, rz), x, z);
        return lines.toArray(String[]::new);
    }

    private static String signed(int value) { return (value >= 0 ? "+" : "") + value; }

    private record BurnSite(int x, int y, int z, long burnDay, boolean plantedOnce) {}

    /** Bounded lookup of already known records; diagnostics never discover or refresh terrain. */
    private void nearbyInfo(List<String> lines, float x, float z, int rx, int rz) {
        nearestLairInfo(lines, nearestLair(x, z, rx, rz), x, z);
        int sites = 0, fresh = 0, planted = 0;
        long today = day(clock.gameTime());
        List<BurnScars.Scar> local = scars.scars(EcologyRegion.key(rx, rz));
        for (var scar : local) for (var site : scar.sites()) {
            sites++;
            if (site.burnDay() <= today && today - site.burnDay() < BurnScars.SAPLING_DAY) fresh++;
            if (site.plantedOnce()) planted++;
        }
        lines.add("Burn history: " + local.size() + " records | " + sites + " sites | " + fresh + " fresh | " + planted + " planted once");
        BurnSite nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            for (var scar : dx == 0 && dz == 0 ? local : scars.scars(EcologyRegion.key(rx + dx, rz + dz)))
                for (var site : scar.sites()) {
                    int sx = scar.cx() * 16 + site.localX(), sy = site.groundY(), sz = scar.cz() * 16 + site.localZ();
                    double candidate = Math.hypot(sx + .5 - x, sz + .5 - z);
                    if (candidate < distance || candidate == distance && nearest != null
                            && locationBefore(sx, sy, sz, nearest.x, nearest.y, nearest.z)) {
                        distance = candidate; nearest = new BurnSite(sx, sy, sz, site.burnDay(), site.plantedOnce());
                    }
                }
        if (nearest == null) lines.add("Nearest scar: none known in nearby regions");
        else lines.add("Nearest scar: " + nearest.x + "," + nearest.y + "," + nearest.z + " | " + Math.round(distance) + "m"
                + " | " + (nearest.burnDay <= today ? "age " + (today - nearest.burnDay) + "d" : "future day " + nearest.burnDay)
                + " | " + (nearest.plantedOnce ? "planted once" : "not planted"));
    }

    private Lair nearestLair(float x, float z, int rx, int rz) {
        Lair nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            for (Lair lair : lairs.lairs(EcologyRegion.key(rx + dx, rz + dz))) {
                double candidate = Math.hypot(lair.x() + .5 - x, lair.z() + .5 - z);
                if (candidate < distance || candidate == distance && nearest != null
                        && locationBefore(lair.x(), lair.y(), lair.z(), nearest.x(), nearest.y(), nearest.z())) {
                    distance = candidate; nearest = lair;
                }
            }
        return nearest;
    }

    private static boolean locationBefore(int x, int y, int z, int otherX, int otherY, int otherZ) {
        return x < otherX || x == otherX && (y < otherY || y == otherY && z < otherZ);
    }

    private static void nearestLairInfo(List<String> lines, Lair lair, float x, float z) {
        if (lair == null) { lines.add("Nearest nest: none known in nearby regions"); return; }
        lines.add("Nearest nest: " + lair.x() + "," + lair.y() + "," + lair.z() + " | "
                + Math.round(Math.hypot(lair.x() + .5 - x, lair.z() + .5 - z)) + "m | "
                + (lair.alive() ? "alive" : "destroyed day " + lair.destroyedDay()));
        lines.add("Nest population: " + lair.population() + "/" + lair.capacity() + " | outside " + lair.bodyIds().size());
    }

    private void requireSupported() { if (!supported) throw new IllegalStateException(recovery); }

    /** Loaded land updates the per-chunk greenery sample; unseen terrain keeps its last known habitat. */
    private void refreshHabitat() {
        refreshHabitat(day(clock.gameTime()));
    }

    private void refreshHabitat(long logicalDay) {
        var changed = new java.util.HashSet<Long>();
        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunk.isReadOnly()) continue;
            EcologyRegion region = regions.get(EcologyRegion.key(Math.floorDiv(chunk.cx, 16), Math.floorDiv(chunk.cz, 16)));
            if (region == null) continue;
            observeHabitat(chunk, region, logicalDay);
            changed.add(region.key());
        }
        for (long key : changed) updateCapacity(regions.get(key));
    }

    private void observeHabitat(Chunk chunk, EcologyRegion region, long logicalDay) {
        int value = 0;
        for (int x : new int[] { 4, 12 }) for (int z : new int[] { 4, 12 }) {
            BlockType surface = BlockType.AIR;
            for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
                var block = chunk.get(x, y, z);
                if (block.solid || block == BlockType.WATER) { surface = block; break; }
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
        cells[Math.floorMod(chunk.cx, 16) * 16 + Math.floorMod(chunk.cz, 16)] =
                (byte) Math.min(value / 4, scars.greeneryCeiling(chunk.cx, chunk.cz, logicalDay));
        region.greenery(cells);
    }

    private void updateCapacity(EcologyRegion region) {
        for (var definition : table().definitions()) {
            int sum = 0;
            for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++)
                sum += definition.capacity(world.biomes.biomeAt(region.x() * 256 + x * 64 + 32, region.z() * 256 + z * 64 + 32));
            region.capacity(definition.id(), (int) Math.round(sum / 16.0 * region.averageGreenery()));
        }
    }

    private void fuelBurned(int x, int y, int z, BlockType fuel) {
        if (!enabled()) return;
        Chunk chunk = world.getChunkIfExists(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (chunk == null || chunk.isReadOnly()) return;
        for (int ground = y - 1; ground >= Math.max(0, y - 16); ground--) {
            BlockType block = world.getBlock(x, ground, z);
            boolean soil = block == BlockType.GRASS || block == BlockType.SNOWY_GRASS
                    || block == BlockType.DRY_GRASS || block == BlockType.PODZOL
                    || block == BlockType.DIRT && scars.knownAt(x, ground, z);
            if (soil) {
                EcologyRegion region = regionAt(x, z);
                long today = day(clock.gameTime());
                if (region != null && scars.burned(region, x, ground, z, today)) {
                    world.setBlock(x, ground, z, BlockType.DIRT);
                    observeHabitat(chunk, region, today);
                    updateCapacity(region);
                    scarTimer = 0;
                }
                return;
            }
            if (block != BlockType.AIR && block != BlockType.FIRE && block != BlockType.ASH
                    && block != BlockType.WOOD && block != BlockType.LEAVES && block != BlockType.SAPLING
                    && block != BlockType.TALL_GRASS && block != BlockType.FLOWER_RED
                    && block != BlockType.FLOWER_YELLOW && block != BlockType.SNOW_LAYER) return;
        }
    }

    /** Apply arriving saved scars and route living bodies through ordinary navigation. */
    public void tickBurnScars(float dt) {
        if (!enabled()) {
            for (Mob mob : entities.mobs) mob.burnDestination = null;
            return;
        }
        scarTimer -= dt;
        if (scarTimer > 0) return;
        scarTimer = .5f;
        long today = day(clock.gameTime()), before = scars.revision();
        scars.tickLoaded(regions.values(), today);
        if (before != scars.revision()) refreshHabitat(today);
        for (Mob mob : entities.mobs)
            mob.burnDestination = !mob.dead && !mob.type.hostile && mob.persistent() ? retreat(mob, today) : null;
    }

    private Vector3f retreat(Mob mob, long today) {
        float nearest = 36f, awayX = 0, awayZ = 0;
        boolean found = false, fresh = false;
        int rx = regionCoordinate(mob.position.x), rz = regionCoordinate(mob.position.z);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            for (BurnScars.Scar scar : scars.scars(EcologyRegion.key(rx + dx, rz + dz)))
                for (BurnScars.Site site : scar.sites()) {
                    if (site.burnDay() > today || today - site.burnDay() >= 3) continue;
                    int cell = site.index(), ground = cell / 256;
                    if (Math.abs(mob.position.y - (ground + 1)) > 3) continue;
                    fresh = true;
                    float ax = mob.position.x - (scar.cx() * 16 + cell % 16 + .5f);
                    float az = mob.position.z - (scar.cz() * 16 + cell / 16 % 16 + .5f);
                    float distance = ax * ax + az * az;
                    if (distance < nearest) { nearest = distance; awayX = ax; awayZ = az; found = true; }
                }
        // Keep a clear refuge while crossing the grove. Choosing a new direction
        // from each newly nearest scar can turn a fleeing body back into the fire.
        Vector3f held = mob.burnDestination;
        if (fresh && held != null && Math.hypot(held.x - mob.position.x, held.z - mob.position.z) > 1.5) {
            int x = (int) Math.floor(held.x), y = (int) Math.floor(held.y), z = (int) Math.floor(held.z);
            Chunk chunk = world.getChunkIfExists(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            if (chunk != null && !chunk.isReadOnly() && world.getBlock(x, y - 1, z).solid
                    && !world.burnDamaged(x, y - 1, z)
                    && !BodyCollision.overlaps(world, held.x, held.y, held.z, mob.type.width, mob.type.height))
                return held;
        }
        if (!found) return null;
        double angle = Math.atan2(awayZ, awayX);
        for (int radius : new int[] { 12, 24, 48 })
        for (int turn : new int[] { 0, 1, -1, 2, -2, 3, -3, 4 }) {
            double direction = angle + turn * Math.PI / 4;
            int x = (int) Math.floor(mob.position.x + Math.cos(direction) * radius);
            int z = (int) Math.floor(mob.position.z + Math.sin(direction) * radius);
            Chunk chunk = world.getChunkIfExists(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            if (chunk == null || chunk.isReadOnly()) continue;
            for (int y = Math.min(126, (int) Math.floor(mob.position.y) + 2);
                 y >= Math.max(1, (int) Math.floor(mob.position.y) - 4); y--) {
                if (!world.getBlock(x, y - 1, z).solid || world.burnDamaged(x, y - 1, z)) continue;
                if (!BodyCollision.overlaps(world, x + .5f, y + .001f, z + .5f, mob.type.width, mob.type.height))
                    return new Vector3f(x + .5f, y + .001f, z + .5f);
            }
        }
        return null;
    }

    private boolean growthSpaceClear(int x, int y, int z) {
        for (int i = 0; i < participants.size(); i++) {
            var participant = participants.get(i);
            var p = participant.position();
            if (participant.alive() && overlapsCell(p.x(), p.y(), p.z(), .6f, 1.8f, x, y, z)) return false;
        }
        for (Mob mob : entities.mobs)
            if (!mob.dead && overlapsCell(mob.position.x, mob.position.y, mob.position.z,
                    mob.type.width, mob.type.height, x, y, z)) return false;
        return true;
    }

    private static boolean overlapsCell(float px, float py, float pz, float width, float height,
                                        int x, int y, int z) {
        float half = width * .5f;
        return px + half > x && px - half < x + 1 && pz + half > z && pz - half < z + 1
                && py + height > y && py < y + 1;
    }

    /** Loaded fields update an estimate; unloaded chunks retain their last observed count. */
    private void refreshFields() {
        for (Chunk chunk : world.getLoadedChunks()) observeField(chunk);
        for (EcologyRegion region : regions.values())
            if (fields.known(region.key())) region.foodBonus(RABBIT, fields.bonus(region.key()));
    }

    /** Capture the final crop estimate before the host snapshots and evicts writable terrain. */
    public void observeField(Chunk chunk) {
        if (!enabled() || chunk.isReadOnly()) return;
        long key = EcologyRegion.key(Math.floorDiv(chunk.cx, 16), Math.floorDiv(chunk.cz, 16));
        EcologyRegion region = regions.get(key);
        if (region == null) return;
        fields.observe(chunk);
        region.foodBonus(RABBIT, fields.bonus(key));
    }
}
