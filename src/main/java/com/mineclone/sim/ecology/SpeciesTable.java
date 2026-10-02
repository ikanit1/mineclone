package com.mineclone.sim.ecology;

import com.mineclone.data.DataPack;
import com.mineclone.data.JsonObject;
import com.mineclone.data.ResourceId;
import com.mineclone.world.Biome;
import com.mineclone.world.entity.MobType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Strict data-driven capacities, reproduction and food chains for finite wildlife. */
public final class SpeciesTable {
    public static final int MAX_SPECIES = 16;
    public static final int MAX_POPULATION = 1_000_000;
    public record Definition(ResourceId id, MobType type, Map<Biome, Integer> biomeCapacity,
                             double birthRate, double migrationRate, List<ResourceId> prey,
                             double predationRate, int groupMin, int groupMax, int minimum) {
        public Definition {
            biomeCapacity = Collections.unmodifiableMap(new EnumMap<>(biomeCapacity));
            prey = List.copyOf(prey);
        }
        public int capacity(Biome biome) { return biomeCapacity.getOrDefault(biome, 0); }
    }
    private final Map<ResourceId, Definition> definitions;
    private final Map<MobType, Definition> types;
    private SpeciesTable(Map<ResourceId, Definition> definitions) {
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        var byType = new EnumMap<MobType, Definition>(MobType.class);
        for (Definition d : definitions.values()) byType.put(d.type(), d);
        types = Collections.unmodifiableMap(byType);
    }
    private static final class Default { private static final SpeciesTable TABLE = load(new DataPack(Path.of("assets/data"))); }
    public static SpeciesTable get() { return Default.TABLE; }
    public List<Definition> definitions() { return List.copyOf(definitions.values()); }
    public Definition byId(ResourceId id) { return definitions.get(id); }
    public Definition byType(MobType type) { return types.get(type); }

    public static SpeciesTable load(DataPack pack) {
        var definitions = new LinkedHashMap<ResourceId, Definition>();
        var usedTypes = new java.util.HashSet<MobType>();
        for (DataPack.Entry entry : pack.files("ecology/species")) {
            JsonObject json = entry.json();
            json.allowOnly("id", "type", "capacity", "birthRate", "migrationRate", "prey",
                    "predationRate", "groupMin", "groupMax", "minimum");
            ResourceId id = ResourceId.parse(json.string("id"), entry.namespace());
            MobType type;
            try { type = MobType.valueOf(json.string("type").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw json.error("type", "unknown mob type"); }
            if (type.hostile) throw json.error("type", "ecology species must be an animal");
            if (!usedTypes.add(type)) throw json.error("type", "duplicate animal definition");
            var capacities = new EnumMap<Biome, Integer>(Biome.class);
            JsonObject capacity = json.object("capacity");
            for (String key : capacity.keys()) {
                Biome biome;
                try { biome = Biome.valueOf(key.toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException e) { throw capacity.error(key, "unknown biome"); }
                int n = capacity.integer(key);
                if (n < 0 || n > MAX_POPULATION) throw capacity.error(key, "capacity out of range");
                if (capacities.put(biome, n) != null) throw capacity.error(key, "duplicate biome");
            }
            double birth = rate(json, "birthRate", 1), migration = rate(json, "migrationRate", 1);
            double predation = rate(json, "predationRate", 100);
            var prey = new ArrayList<ResourceId>();
            for (String name : json.strings("prey")) {
                ResourceId preyId = ResourceId.parse(name, entry.namespace());
                if (preyId.equals(id) || prey.contains(preyId)) throw json.error("prey", "self or duplicate prey");
                prey.add(preyId);
            }
            if (prey.isEmpty() && predation != 0) throw json.error("predationRate", "predator has no prey");
            int min = json.integer("groupMin"), max = json.integer("groupMax"), floor = json.integer("minimum");
            if (min < 1 || max < min || max > 16) throw json.error("groupMax", "group bounds must be 1..16");
            if (floor < 0 || floor > MAX_POPULATION) throw json.error("minimum", "minimum out of range");
            Definition definition = new Definition(id, type, capacities, birth, migration, prey, predation, min, max, floor);
            if (definitions.put(id, definition) != null) throw json.error("id", "duplicate species");
            if (definitions.size() > MAX_SPECIES) throw json.error("id", "too many species");
        }
        if (definitions.isEmpty()) throw new IllegalArgumentException("No ecology species data");
        for (Definition d : definitions.values())
            for (ResourceId prey : d.prey())
                if (!definitions.containsKey(prey)) throw new IllegalArgumentException("Unknown ecology prey " + prey + " in " + d.id());
        return new SpeciesTable(definitions);
    }
    private static double rate(JsonObject json, String field, double max) {
        double n = json.number(field);
        if (!Double.isFinite(n) || n < 0 || n > max) throw json.error(field, "rate out of range");
        return n;
    }
}
