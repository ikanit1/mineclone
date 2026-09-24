package com.mineclone.save;

import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One player's checkpoint, in the same shape for the host's {@code level.dat},
 * a guest's {@code players/<uuid>.dat} and the network adapter.
 *
 * <p>Immutable: arrays and stacks are copied on the way in and on the way out,
 * so a record captured this tick cannot be changed by the next one. Sections the
 * build does not understand travel with the record untouched, and a damaged
 * {@code effects} section is kept byte for byte instead of being dropped.
 */
public final class PlayerRecord {
    /** Record schema; version 1 is the protocol-v7 checkpoint that preceded it. */
    public static final int VERSION = 2;
    /** The level section that carried survival progress before the record owned it. */
    public static final String LEGACY_PROGRESS_SECTION = "mineclone:survival_progress";
    /** HEAD, CHEST, LEGS, FEET, OFFHAND (CMB-01). */
    public static final int EQUIPMENT_SLOTS = 5;
    /** Upper bounds that reject garbage before allocating for it. */
    public static final int MAX_EQUIPMENT = 64, MAX_PENDING = 256, MAX_EFFECTS = 1024,
            MAX_NAMES = 4096, MAX_PROGRESS_BYTES = 65536;
    /** Breath in 20 Hz ticks: 15 seconds under water (SURV-03). */
    public static final float MAX_AIR = 300f;
    public static final float MAX_EXHAUSTION = 4f;
    /** Saturation granted to players saved before it existed. */
    public static final float LEGACY_SATURATION = 5f;

    public record Pose(double x, double y, double z, float yaw, float pitch, int selected) {
        public Pose {
            finite(x, y, z, yaw, pitch);
            if (selected < 0 || selected >= 9) throw new IllegalArgumentException("invalid selected slot " + selected);
        }
    }

    public record Vitals(float health, float hunger, float saturation, float exhaustion, float air) {
        public Vitals {
            finite(health, hunger, saturation, exhaustion, air);
            if (health < 0 || health > 20 || hunger < 0 || hunger > 20 || saturation < 0 || saturation > 20
                    || exhaustion < 0 || exhaustion > MAX_EXHAUSTION || air < 0 || air > MAX_AIR)
                throw new IllegalArgumentException("invalid player vitals");
        }

        /** Vitals of a player saved with health and hunger only; the rest start full. */
        public static Vitals legacy(float health, float hunger) {
            return new Vitals(health, hunger, Math.min(LEGACY_SATURATION, hunger), 0f, MAX_AIR);
        }

        /**
         * Vitals taken from a running game. A save must never fail on a value a
         * bug pushed out of range, so each one is clamped, and a non-finite one
         * falls back to full.
         */
        public static Vitals live(float health, float hunger, float saturation, float exhaustion, float air) {
            return new Vitals(clamp(health, 20f, 20f), clamp(hunger, 20f, 20f), clamp(saturation, 20f, 0f),
                    clamp(exhaustion, MAX_EXHAUSTION, 0f), clamp(air, MAX_AIR, MAX_AIR));
        }

        private static float clamp(float value, float max, float fallback) {
            return Float.isFinite(value) ? Math.max(0f, Math.min(max, value)) : fallback;
        }
    }

    /** Personal respawn point; independent of the world's common spawn. */
    public record Spawn(double x, double y, double z) {
        public Spawn { finite(x, y, z); }
    }

    /** A status effect by name, so reordering an enum never changes a save. */
    public record Effect(String id, long remainingTicks, int amplifier) {
        public Effect {
            if (id == null || id.isBlank() || remainingTicks < 0 || amplifier < 0 || amplifier > 255)
                throw new IllegalArgumentException("invalid effect");
        }
    }

    private final Pose pose;
    private final Vitals vitals;
    private final Spawn spawn;
    private final ItemStack[] inventory, equipment, pending;
    private final List<Effect> effects;
    private final List<String> advancements, recipes;
    private final byte[] progress;
    /** A damaged effects section, preserved verbatim until effects are replaced. */
    private final byte[] damagedEffects;
    private final Map<String, byte[]> extra;
    private final List<String> warnings;

    private PlayerRecord(Builder b) {
        pose = Objects.requireNonNull(b.pose, "pose");
        vitals = Objects.requireNonNull(b.vitals, "vitals");
        spawn = b.spawn;
        inventory = copy(b.inventory);
        equipment = copy(b.equipment);
        pending = copy(b.pending);
        if (inventory.length != Inventory.SIZE || equipment.length > MAX_EQUIPMENT || pending.length > MAX_PENDING)
            throw new IllegalArgumentException("invalid player item section size");
        if (b.effects.size() > MAX_EFFECTS || b.progress.length > MAX_PROGRESS_BYTES)
            throw new IllegalArgumentException("player section too large");
        effects = List.copyOf(b.effects);
        advancements = names(b.advancements);
        recipes = names(b.recipes);
        progress = b.progress.clone();
        damagedEffects = b.damagedEffects == null ? null : b.damagedEffects.clone();
        extra = copySections(b.extra);
        warnings = List.copyOf(b.warnings);
    }

    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return new Builder(this); }

    public Pose pose() { return pose; }
    public Vitals vitals() { return vitals; }
    /** Personal respawn point, or null to use the world's spawn. */
    public Spawn spawn() { return spawn; }
    public ItemStack[] inventory() { return copy(inventory); }
    public ItemStack[] equipment() { return copy(equipment); }
    /** Stacks that belonged to the player outside any slot: a cursor, a crafting grid. */
    public ItemStack[] pending() { return copy(pending); }
    public List<Effect> effects() { return effects; }
    public List<String> advancements() { return advancements; }
    public List<String> recipes() { return recipes; }
    /** Opaque survival-progress payload, owned by {@code game.SurvivalProgress}. */
    public byte[] progress() { return progress.clone(); }
    /** Sections written by another build, in their original order. */
    public Map<String, byte[]> extraSections() { return copySections(extra); }
    /** What could not be applied on reading; the data itself was preserved. */
    public List<String> warnings() { return warnings; }
    byte[] damagedEffects() { return damagedEffects == null ? null : damagedEffects.clone(); }

    /** The same player holding different items. */
    public PlayerRecord withItems(ItemStack[] newInventory, ItemStack[] newPending) {
        return toBuilder().inventory(newInventory).pending(newPending).build();
    }

    /**
     * Apply a protocol-v7 checkpoint. It carries pose, health, hunger, inventory,
     * pending stacks and progress only; everything else it cannot express —
     * saturation, air, equipment, effects, personal spawn, unknown sections —
     * stays as this record has it.
     */
    public PlayerRecord mergeLegacy(com.mineclone.net.PlayerData incoming) {
        return toBuilder()
                .pose(keepPrecision(incoming.x, pose.x()), keepPrecision(incoming.y, pose.y()),
                        keepPrecision(incoming.z, pose.z()), incoming.yaw, incoming.pitch, incoming.selected)
                .vitals(new Vitals(incoming.health, incoming.hunger, vitals.saturation(),
                        vitals.exhaustion(), vitals.air()))
                .inventory(incoming.inventory)
                .pending(incoming.pending)
                .progress(incoming.progress)
                .build();
    }

    /**
     * A float coordinate that still equals the stored double keeps the double,
     * so a round trip through a float-only path does not drift a saved position.
     */
    public static double keepPrecision(float current, double saved) {
        return current == (float) saved ? saved : current;
    }

    public static final class Builder {
        private Pose pose = new Pose(0, 80, 0, 0, 0, 0);
        private Vitals vitals = new Vitals(20, 20, LEGACY_SATURATION, 0, MAX_AIR);
        private Spawn spawn;
        private ItemStack[] inventory = new ItemStack[Inventory.SIZE];
        private ItemStack[] equipment = new ItemStack[EQUIPMENT_SLOTS];
        private ItemStack[] pending = new ItemStack[0];
        private List<Effect> effects = List.of();
        private List<String> advancements = List.of(), recipes = List.of();
        private byte[] progress = new byte[0];
        private byte[] damagedEffects;
        private final Map<String, byte[]> extra = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();

        public Builder() {}

        private Builder(PlayerRecord r) {
            pose = r.pose;
            vitals = r.vitals;
            spawn = r.spawn;
            inventory = r.inventory();
            equipment = r.equipment();
            pending = r.pending();
            effects = r.effects;
            advancements = r.advancements;
            recipes = r.recipes;
            progress = r.progress();
            damagedEffects = r.damagedEffects();
            extra.putAll(r.extraSections());
            warnings.addAll(r.warnings);
        }

        public Builder pose(double x, double y, double z, float yaw, float pitch, int selected) {
            pose = new Pose(x, y, z, yaw, pitch, selected);
            return this;
        }

        public Builder vitals(Vitals value) {
            vitals = Objects.requireNonNull(value, "vitals");
            return this;
        }

        public Builder inventory(ItemStack[] value) {
            inventory = copy(value == null ? new ItemStack[Inventory.SIZE] : value);
            return this;
        }

        public Builder equipment(ItemStack[] value) {
            equipment = copy(value == null ? new ItemStack[EQUIPMENT_SLOTS] : value);
            return this;
        }

        public Builder pending(ItemStack[] value) {
            pending = copy(value == null ? new ItemStack[0] : value);
            return this;
        }

        public Builder spawn(Spawn value) {
            spawn = value;
            return this;
        }

        /** Replaces the effects, including a damaged section kept from reading. */
        public Builder effects(List<Effect> value) {
            effects = List.copyOf(value);
            damagedEffects = null;
            warnings.clear();
            return this;
        }

        public Builder advancements(List<String> value) {
            advancements = List.copyOf(value);
            return this;
        }

        public Builder recipes(List<String> value) {
            recipes = List.copyOf(value);
            return this;
        }

        public Builder progress(byte[] value) {
            progress = value == null ? new byte[0] : value.clone();
            return this;
        }

        /** A section this build does not understand, kept for the build that wrote it. */
        public Builder section(String key, byte[] value) {
            if (key == null || key.isBlank() || PlayerRecordCodec.KNOWN.contains(key) || value == null)
                throw new IllegalArgumentException("invalid opaque player section " + key);
            extra.put(key, value.clone());
            return this;
        }

        Builder damagedEffects(byte[] bytes, String warning) {
            damagedEffects = bytes.clone();
            effects = List.of();
            warnings.add(warning);
            return this;
        }

        public PlayerRecord build() { return new PlayerRecord(this); }
    }

    private static List<String> names(List<String> values) {
        if (values.size() > MAX_NAMES) throw new IllegalArgumentException("too many player identifiers");
        Set<String> unique = new HashSet<>();
        for (String value : values)
            if (value == null || value.isBlank() || !unique.add(value))
                throw new IllegalArgumentException("invalid player identifier " + value);
        return List.copyOf(values);
    }

    private static void finite(double... values) {
        for (double value : values)
            if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite player field");
    }

    static ItemStack[] copy(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < result.length; i++) result[i] = source[i] == null ? null : source[i].copy();
        return result;
    }

    static Map<String, byte[]> copySections(Map<String, byte[]> source) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, value.clone()));
        return Collections.unmodifiableMap(result);
    }
}
