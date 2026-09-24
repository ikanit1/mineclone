package com.mineclone.item.loot;

import com.mineclone.data.JsonObject;
import com.mineclone.data.ResourceId;
import com.mineclone.item.Item;
import com.mineclone.item.ItemRegistry;
import com.mineclone.item.ToolClass;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import java.util.*;
import java.util.random.RandomGenerator;

/** Validated immutable weighted pools. Constant legacy drops consume no random values. */
public final class LootTable {
    private static final int MAX_POOLS = 32, MAX_ENTRIES = 256, MAX_ROLLS = 256, MAX_UNITS = 4096;
    private record Range(int min, int max) {
        int sample(RandomGenerator random) { return min == max ? min : random.nextInt(min, max + 1); }
    }
    private record Damage(double min, double max) {
        void apply(ItemStack stack, RandomGenerator random) {
            double fraction = min == max ? min : random.nextDouble(min, max);
            stack.setDamage((int) Math.round(stack.item.durability * fraction));
        }
    }
    private record Condition(ToolClass toolClass, Integer toolLevel, Boolean killed, Boolean notCreative, Double chance) {
        boolean possible(LootContext context) {
            var tool = context.tool() == null || context.tool().count <= 0 ? null : context.tool().tool();
            return (toolClass == null || tool != null && tool.toolClass() == toolClass)
                    && (toolLevel == null || (tool == null ? 0 : tool.level()) >= toolLevel)
                    && (killed == null || killed == context.killedByParticipant())
                    && (!Boolean.TRUE.equals(notCreative) || context.mode() != GameMode.CREATIVE)
                    && (chance == null || chance > 0);
        }
        boolean matches(LootContext context, RandomGenerator random) {
            return possible(context) && (chance == null || chance == 1 || random.nextDouble() < chance);
        }
    }
    private record Entry(List<Item> items, int weight, Range count, List<Condition> conditions, List<Damage> functions) {}
    private record Pool(Range rolls, List<Entry> entries, List<Condition> conditions) {}
    private final ResourceId id;
    private final List<Pool> pools;
    private final List<Condition> conditions;

    private LootTable(ResourceId id, List<Pool> pools, List<Condition> conditions) {
        this.id = id; this.pools = List.copyOf(pools); this.conditions = List.copyOf(conditions);
    }
    public ResourceId id() { return id; }

    public static LootTable parse(ResourceId id, JsonObject root, ItemRegistry items) {
        root.allowOnly("pools", "conditions");
        var rawPools = root.array("pools");
        if (rawPools.size() > MAX_POOLS) throw root.error("pools", "at most " + MAX_POOLS + " pools");
        List<Pool> pools = new ArrayList<>();
        long maximumUnits = 0;
        for (int p = 0; p < rawPools.size(); p++) {
            JsonObject pool = root.element("pools", p);
            pool.allowOnly("rolls", "entries", "conditions");
            Range rolls = pool.has("rolls") ? range(pool, "rolls", 0, MAX_ROLLS) : new Range(1, 1);
            var rawEntries = pool.array("entries");
            if (rawEntries.isEmpty() || rawEntries.size() > MAX_ENTRIES)
                throw pool.error("entries", "must have 1.." + MAX_ENTRIES + " entries; use pools:[] for empty loot");
            List<Entry> entries = new ArrayList<>();
            int maximumEntryUnits = 0;
            for (int e = 0; e < rawEntries.size(); e++) {
                JsonObject entry = pool.element("entries", e);
                entry.allowOnly("item", "weight", "count", "conditions", "functions");
                List<Item> choices = resolveItems(entry, items, id.namespace());
                int weight = entry.has("weight") ? integer(entry, "weight", entry.raw("weight"), 1, 1_000_000) : 1;
                Range count = entry.has("count") ? range(entry, "count", 0, MAX_UNITS) : new Range(1, 1);
                List<Damage> functions = functions(entry);
                if (!functions.isEmpty() && choices.stream().anyMatch(item -> item.durability <= 0))
                    throw entry.error("functions", "set_damage requires durable items");
                entries.add(new Entry(choices, weight, count, conditions(entry), functions));
                maximumEntryUnits = Math.max(maximumEntryUnits, count.max);
            }
            maximumUnits += (long) rolls.max * maximumEntryUnits;
            if (maximumUnits > MAX_UNITS) throw pool.error("rolls", "table can generate more than " + MAX_UNITS + " item units");
            pools.add(new Pool(rolls, List.copyOf(entries), conditions(pool)));
        }
        return new LootTable(id, pools, conditions(root));
    }

    public List<ItemStack> roll(LootContext context) {
        Objects.requireNonNull(context, "context");
        RandomGenerator random = context.randomFor(id);
        List<ItemStack> result = new ArrayList<>();
        if (!matches(conditions, context, random)) return result;
        for (Pool pool : pools) {
            if (!matches(pool.conditions, context, random)) continue;
            int rolls = pool.rolls.sample(random);
            List<Entry> eligible = new ArrayList<>(pool.entries.size());
            for (int roll = 0; roll < rolls; roll++) {
                eligible.clear(); long totalWeight = 0;
                for (Entry entry : pool.entries) if (matches(entry.conditions, context, random)) {
                    eligible.add(entry); totalWeight += entry.weight;
                }
                if (eligible.isEmpty()) continue;
                Entry chosen = eligible.get(0);
                if (eligible.size() > 1) {
                    long at = random.nextLong(totalWeight);
                    for (Entry entry : eligible) {
                        if (at < entry.weight) { chosen = entry; break; }
                        at -= entry.weight;
                    }
                }
                Item item = chosen.items.size() == 1 ? chosen.items.get(0) : chosen.items.get(random.nextInt(chosen.items.size()));
                int count = chosen.count.sample(random);
                while (count > 0) {
                    int units = Math.min(item.maxStack, count);
                    ItemStack stack = new ItemStack(item, units);
                    for (Damage damage : chosen.functions) damage.apply(stack, random);
                    result.add(stack); count -= units;
                }
            }
        }
        return result;
    }

    /** Graph reachability: all outputs with non-zero probability for this exact tool/mode/killer. */
    public Set<Item> possibleItems(LootContext context) {
        Set<Item> result = new LinkedHashSet<>();
        if (!possible(conditions, context)) return Set.of();
        for (Pool pool : pools) if (pool.rolls.max > 0 && possible(pool.conditions, context))
            for (Entry entry : pool.entries) if (entry.count.max > 0 && possible(entry.conditions, context))
                result.addAll(entry.items);
        return Collections.unmodifiableSet(result);
    }

    private static boolean possible(List<Condition> conditions, LootContext context) {
        for (Condition condition : conditions) if (!condition.possible(context)) return false;
        return true;
    }
    private static boolean matches(List<Condition> conditions, LootContext context, RandomGenerator random) {
        for (Condition condition : conditions) if (!condition.matches(context, random)) return false;
        return true;
    }
    private static List<Item> resolveItems(JsonObject entry, ItemRegistry items, String namespace) {
        String text = entry.string("item"); boolean tag = text.startsWith("#");
        ResourceId ref;
        try { ref = ResourceId.parse(tag ? text.substring(1) : text, namespace); }
        catch (IllegalArgumentException bad) { throw entry.error("item", bad.getMessage()); }
        if (tag) {
            if (!items.tags().has(ref)) throw entry.error("item", "unknown tag " + ref);
            List<Item> choices = items.tags().members(ref).stream().sorted(Comparator.comparing(item -> item.id)).toList();
            if (choices.isEmpty()) throw entry.error("item", "empty tag " + ref);
            return choices;
        }
        Item item = items.get(ref);
        if (item == null) throw entry.error("item", "unknown item " + ref);
        return List.of(item);
    }
    private static List<Condition> conditions(JsonObject parent) {
        if (!parent.has("conditions")) return List.of();
        var raw = parent.array("conditions");
        if (raw.size() > 32) throw parent.error("conditions", "at most 32 conditions");
        List<Condition> result = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            JsonObject value = parent.element("conditions", i);
            value.allowOnly("tool_class", "tool_level_min", "killed_by_participant", "random_chance", "not_creative");
            if (value.keys().isEmpty()) throw parent.error("conditions[" + i + "]", "empty condition");
            ToolClass toolClass = null;
            if (value.has("tool_class")) {
                toolClass = ToolClass.byName(value.string("tool_class"));
                if (toolClass == null) throw value.error("tool_class", "unknown tool class");
            }
            Integer level = value.has("tool_level_min") ? integer(value, "tool_level_min", value.raw("tool_level_min"), 0, 255) : null;
            Double chance = value.has("random_chance") ? number(value, "random_chance", value.raw("random_chance"), 0, 1) : null;
            result.add(new Condition(toolClass, level, bool(value, "killed_by_participant"), bool(value, "not_creative"), chance));
        }
        return List.copyOf(result);
    }
    private static Boolean bool(JsonObject value, String key) {
        if (!value.has(key)) return null;
        if (!(value.raw(key) instanceof Boolean flag)) throw value.error(key, "expected true or false");
        return flag;
    }
    private static List<Damage> functions(JsonObject parent) {
        if (!parent.has("functions")) return List.of();
        var raw = parent.array("functions");
        if (raw.size() > 16) throw parent.error("functions", "at most 16 functions");
        List<Damage> result = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            JsonObject value = parent.element("functions", i); value.allowOnly("set_damage");
            Object damage = value.raw("set_damage");
            double min, max;
            if (damage instanceof List<?> list) {
                if (list.size() != 2) throw value.error("set_damage", "range must contain min and max");
                min = number(value, "set_damage[0]", list.get(0), 0, 1);
                max = number(value, "set_damage[1]", list.get(1), 0, 1);
            } else min = max = number(value, "set_damage", damage, 0, 1);
            if (min > max) throw value.error("set_damage", "minimum exceeds maximum");
            result.add(new Damage(min, max));
        }
        return List.copyOf(result);
    }
    private static Range range(JsonObject object, String key, int lower, int upper) {
        Object raw = object.raw(key); int min, max;
        if (raw instanceof List<?> values) {
            if (values.size() != 2) throw object.error(key, "range must contain min and max");
            min = integer(object, key + "[0]", values.get(0), lower, upper);
            max = integer(object, key + "[1]", values.get(1), lower, upper);
        } else min = max = integer(object, key, raw, lower, upper);
        if (min > max) throw object.error(key, "minimum exceeds maximum");
        return new Range(min, max);
    }
    private static int integer(JsonObject object, String key, Object raw, int lower, int upper) {
        double number = number(object, key, raw, lower, upper);
        if (number != Math.rint(number)) throw object.error(key, "expected an integer");
        return (int) number;
    }
    private static double number(JsonObject object, String key, Object raw, double lower, double upper) {
        if (!(raw instanceof Double value) || !Double.isFinite(value) || value < lower || value > upper)
            throw object.error(key, "expected a finite number in " + lower + ".." + upper);
        return value;
    }
}
