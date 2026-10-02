package com.mineclone.sim.ecology;

import com.mineclone.data.DataPack;
import com.mineclone.data.JsonObject;
import com.mineclone.data.ResourceId;
import com.mineclone.world.entity.MobType;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Strict data-driven population and territory bounds for finite hostile lairs. */
public final class LairTable {
    public static final int MAX_DEFINITIONS = 16, MAX_CAPACITY = 32, MAX_RADIUS = 128;
    public static final int MAX_ID_LENGTH = 128;

    public record Definition(ResourceId id, MobType type, int initial, int capacity,
                             int growth, int emit, int radius) {
        public Definition {
            Objects.requireNonNull(id, "lair id");
            Objects.requireNonNull(type, "lair mob type");
            if (id.toString().length() > MAX_ID_LENGTH) throw new IllegalArgumentException("lair id too long");
            if (!type.hostile) throw new IllegalArgumentException("lair mob must be hostile");
            if (capacity < 1 || capacity > MAX_CAPACITY) throw new IllegalArgumentException("lair capacity must be 1..32");
            if (initial < 0 || initial > capacity) throw new IllegalArgumentException("initial lair population out of range");
            if (growth < 0 || growth > capacity) throw new IllegalArgumentException("lair daily growth out of range");
            if (emit < 1 || emit > capacity) throw new IllegalArgumentException("lair emission out of range");
            if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("lair radius out of range");
        }
    }

    private final Map<ResourceId, Definition> definitions;
    private final Map<MobType, Definition> types;

    private LairTable(Map<ResourceId, Definition> definitions) {
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        var byType = new EnumMap<MobType, Definition>(MobType.class);
        for (Definition definition : definitions.values()) byType.put(definition.type(), definition);
        types = Collections.unmodifiableMap(byType);
    }

    private static final class Default {
        private static final LairTable TABLE = load(new DataPack(Path.of("assets/data")));
    }

    public static LairTable get() { return Default.TABLE; }
    public List<Definition> definitions() { return List.copyOf(definitions.values()); }
    public Definition byId(ResourceId id) { return definitions.get(id); }
    public Definition byType(MobType type) { return types.get(type); }

    public static LairTable load(DataPack pack) {
        var definitions = new LinkedHashMap<ResourceId, Definition>();
        var usedTypes = new java.util.HashSet<MobType>();
        for (DataPack.Entry entry : pack.files("ecology/lairs")) {
            JsonObject json = entry.json();
            json.allowOnly("id", "type", "initial", "capacity", "growth", "emit", "radius");
            ResourceId id;
            try { id = ResourceId.parse(json.string("id"), entry.namespace()); }
            catch (IllegalArgumentException invalid) { throw json.error("id", invalid.getMessage()); }
            if (id.toString().length() > MAX_ID_LENGTH) throw json.error("id", "lair id too long");
            MobType type;
            try { type = MobType.valueOf(json.string("type").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw json.error("type", "unknown mob type"); }
            if (!type.hostile) throw json.error("type", "lair mob must be hostile");
            int capacity = bounded(json, "capacity", 1, MAX_CAPACITY);
            int initial = bounded(json, "initial", 0, capacity);
            int growth = bounded(json, "growth", 0, capacity);
            int emit = bounded(json, "emit", 1, capacity);
            int radius = bounded(json, "radius", 1, MAX_RADIUS);
            if (!usedTypes.add(type)) throw json.error("type", "duplicate lair mob definition");
            Definition definition = new Definition(id, type, initial, capacity, growth, emit, radius);
            if (definitions.put(id, definition) != null) throw json.error("id", "duplicate lair definition");
            if (definitions.size() > MAX_DEFINITIONS) throw json.error("id", "too many lair definitions");
        }
        if (definitions.isEmpty()) throw new IllegalArgumentException("No lair data");
        return new LairTable(definitions);
    }

    private static int bounded(JsonObject json, String field, int min, int max) {
        int value = json.integer(field);
        if (value < min || value > max) throw json.error(field, "value must be " + min + ".." + max);
        return value;
    }
}
