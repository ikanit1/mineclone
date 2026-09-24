package com.mineclone.save;

import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import java.util.*;

/** Immutable player checkpoint shared by owner and guest storage. Mutable values never escape. */
public final class PlayerRecord {
    public static final int VERSION = 1;
    public static final String LEGACY_PROGRESS_SECTION = "mineclone:survival_progress";
    public static final int MAX_PENDING = 256;
    public record Pose(double x, double y, double z, float yaw, float pitch, int selected) {
        public Pose {
            finite(x, y, z, yaw, pitch);
            if (selected < 0 || selected >= 9) throw new IllegalArgumentException("invalid selected slot");
        }
    }
    public record Vitals(float health, float hunger, float saturation, float air) {
        public Vitals {
            finite(health, hunger, saturation, air);
            if (health < 0 || health > 20 || hunger < 0 || hunger > 20 || saturation < 0 || air < 0)
                throw new IllegalArgumentException("invalid player vitals");
        }
    }
    /** Personal respawn point; independent of the world's common spawn. */
    public record Spawn(double x, double y, double z) { public Spawn { finite(x, y, z); } }
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
    private final byte[] progress, retainedEffects;
    private final Map<String, byte[]> extra;
    private final List<String> warnings;

    private PlayerRecord(Builder b) {
        pose = Objects.requireNonNull(b.pose); vitals = Objects.requireNonNull(b.vitals); spawn = b.spawn;
        inventory = copy(b.inventory); equipment = copy(b.equipment); pending = copy(b.pending);
        if (inventory.length != Inventory.SIZE || equipment.length > 64 || pending.length > MAX_PENDING)
            throw new IllegalArgumentException("invalid player item section size");
        effects = List.copyOf(b.effects); advancements = names(b.advancements); recipes = names(b.recipes);
        if (effects.size() > 1024 || b.progress.length > 65536) throw new IllegalArgumentException("player section too large");
        progress = b.progress.clone(); retainedEffects = b.retainedEffects == null ? null : b.retainedEffects.clone();
        extra = copySections(b.extra); warnings = List.copyOf(b.warnings);
    }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return new Builder(this); }
    public Pose pose() { return pose; }
    public Vitals vitals() { return vitals; }
    public Spawn spawn() { return spawn; }
    public ItemStack[] inventory() { return copy(inventory); }
    public ItemStack[] equipment() { return copy(equipment); }
    public ItemStack[] pending() { return copy(pending); }
    public List<Effect> effects() { return effects; }
    public List<String> advancements() { return advancements; }
    public List<String> recipes() { return recipes; }
    public byte[] progress() { return progress.clone(); }
    public Map<String, byte[]> extraSections() { return copySections(extra); }
    public List<String> warnings() { return warnings; }
    byte[] retainedEffects() { return retainedEffects == null ? null : retainedEffects.clone(); }
    public PlayerRecord withItems(ItemStack[] inventory, ItemStack[] pending) {
        return toBuilder().inventory(inventory).pending(pending).build();
    }

    public static PlayerRecord fromLegacy(LevelData data) { return data.player; }
    public static PlayerRecord fromPlayerData(com.mineclone.net.PlayerData data) { return data.record(); }

    /** v7 only carries these fields. Keep every section omitted by its fixed packet body. */
    public PlayerRecord mergeLegacy(com.mineclone.net.PlayerData incoming) {
        return toBuilder().pose(preserve(incoming.x,pose.x()), preserve(incoming.y,pose.y()), preserve(incoming.z,pose.z()), incoming.yaw, incoming.pitch, incoming.selected)
                .vitals(incoming.health, incoming.hunger, vitals.saturation(), vitals.air())
                .inventory(incoming.inventory).pending(incoming.pending).progress(incoming.progress).build();
    }
    private static double preserve(float current,double saved) { return current==(float)saved ? saved : current; }

    public static final class Builder {
        private Pose pose = new Pose(0, 80, 0, 0, 0, 0);
        private Vitals vitals = new Vitals(20, 20, 5, 20);
        private Spawn spawn;
        private ItemStack[] inventory = new ItemStack[Inventory.SIZE], equipment = new ItemStack[6], pending = new ItemStack[0];
        private List<Effect> effects = List.of();
        private List<String> advancements = List.of(), recipes = List.of();
        private byte[] progress = new byte[0], retainedEffects;
        private final Map<String, byte[]> extra = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        public Builder() {}
        private Builder(PlayerRecord r) {
            pose = r.pose; vitals = r.vitals; spawn = r.spawn;
            inventory = r.inventory(); equipment = r.equipment(); pending = r.pending();
            effects = r.effects; advancements = r.advancements; recipes = r.recipes;
            progress = r.progress(); retainedEffects = r.retainedEffects(); extra.putAll(r.extraSections()); warnings.addAll(r.warnings);
        }
        public Builder pose(double x, double y, double z, float yaw, float pitch, int selected) { pose = new Pose(x,y,z,yaw,pitch,selected); return this; }
        public Builder vitals(float health, float hunger, float saturation, float air) { vitals = new Vitals(health,hunger,saturation,air); return this; }
        public Builder inventory(ItemStack[] value) { inventory = copy(value == null ? new ItemStack[Inventory.SIZE] : value); return this; }
        public Builder equipment(ItemStack[] value) { equipment = copy(value == null ? new ItemStack[6] : value); return this; }
        public Builder pending(ItemStack[] value) { pending = copy(value == null ? new ItemStack[0] : value); return this; }
        public Builder spawn(Spawn value) { spawn = value; return this; }
        public Builder effects(List<Effect> value) { effects = List.copyOf(value); retainedEffects = null; warnings.clear(); return this; }
        public Builder advancements(List<String> value) { advancements = List.copyOf(value); return this; }
        public Builder recipes(List<String> value) { recipes = List.copyOf(value); return this; }
        public Builder progress(byte[] value) { progress = value == null ? new byte[0] : value.clone(); return this; }
        public Builder section(String key, byte[] value) {
            if (key == null || key.isBlank() || PlayerRecordCodec.KNOWN.contains(key) || value == null)
                throw new IllegalArgumentException("invalid opaque player section " + key);
            extra.put(key, value.clone()); return this;
        }
        Builder damagedEffects(byte[] bytes, String warning) {
            retainedEffects = bytes.clone(); effects = List.of(); warnings.add(warning); return this;
        }
        public PlayerRecord build() { return new PlayerRecord(this); }
    }
    private static List<String> names(List<String> values) {
        if (values.size() > 4096) throw new IllegalArgumentException("too many player identifiers");
        Set<String> unique = new HashSet<>();
        for (String value : values) if (value == null || value.isBlank() || !unique.add(value))
            throw new IllegalArgumentException("invalid player identifier");
        return List.copyOf(values);
    }
    private static void finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite player field");
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
