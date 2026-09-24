package com.mineclone.item.loot;

import com.mineclone.data.ResourceId;
import com.mineclone.item.Item;
import com.mineclone.item.ItemRegistry;
import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import com.mineclone.world.entity.MobType;
import java.util.*;

/** A least fixed point of obtainable items. Cycles cannot make their own inputs reachable. */
public final class LootReachability {
    private LootReachability() {}

    /** Callers supply audited world sources; registered content alone is never a source. */
    public record Sources(Set<BlockType> generatedBlocks, Set<MobType> spawnedMobs,
                          Set<ResourceId> generatedChests, Set<Item> directItems) {
        public Sources {
            generatedBlocks = Set.copyOf(generatedBlocks);
            spawnedMobs = Set.copyOf(spawnedMobs);
            generatedChests = Set.copyOf(generatedChests);
            directItems = Set.copyOf(directItems);
        }
    }

    public record Result(Map<Item, String> sources, List<Item> unreachable) {
        public Result {
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            unreachable = List.copyOf(unreachable);
        }
        public boolean reachable(Item item) { return sources.containsKey(item); }
        public void requireAllReachable() {
            if (!unreachable.isEmpty()) throw new IllegalStateException("Items without a survival source: "
                    + unreachable.stream().map(item -> item.id.toString()).toList());
        }
    }

    public static Result analyze(ItemRegistry items, Sources roots) {
        Map<Item, String> reached = new LinkedHashMap<>();
        roots.directItems().stream().sorted(Comparator.comparing(item -> item.id))
                .forEach(item -> {
                    if (items.get(item.id) != item) throw new IllegalArgumentException("foreign source item " + item.id);
                    reached.put(item, "direct");
                });
        // Resolve every chest even if another path already yielded all its items: bad references are errors.
        List<LootTable> chests = roots.generatedChests().stream().sorted().map(id -> {
            if (!id.path().startsWith("chests/")) throw new IllegalArgumentException("not a chest table " + id);
            return items.loot().require(id.toString());
        }).toList();
        boolean changed;
        do {
            int before = reached.size();
            List<ItemStack> tools = new ArrayList<>();
            tools.add(null);
            reached.keySet().stream().filter(item -> item.tool != null).sorted(Comparator.comparing(item -> item.id))
                    .forEach(item -> tools.add(new ItemStack(item, 1)));
            for (ItemStack tool : tools) {
                var context = new LootContext(0, 0, 0, 0, tool, true, GameMode.SURVIVAL, null);
                for (BlockType block : BlockType.values())
                    if (roots.generatedBlocks().contains(block) && GameMode.SURVIVAL.canBreak(block))
                        add(reached, items.loot().blockSources(block, context), "block:" + block);
                for (MobType mob : MobType.values()) if (roots.spawnedMobs().contains(mob)) {
                    add(reached, items.loot().entitySources(mob, context), "mob:" + mob);
                    // Environmental deaths are also real sources in the current game.
                    var natural = new LootContext(0, 0, 0, 0, tool, false, GameMode.SURVIVAL, null);
                    add(reached, items.loot().entitySources(mob, natural), "mob:" + mob);
                }
            }
            // Opening a generated chest has no killer or mining tool.
            for (LootTable chest : chests)
                add(reached, chest.possibleItems(LootContext.chest(0, 0, 0, 0)), "chest:" + chest.id());
            boolean workbench = reached.containsKey(items.forBlock(BlockType.CRAFTING_TABLE));
            for (var entry : items.recipes().crafting()) {
                var recipe = entry.recipe();
                if (!recipe.fits(2, 2) && !workbench) continue;
                boolean inputs = true;
                for (var ingredient : recipe.ingredients())
                    if (ingredient != null && ingredient.alternatives().stream().noneMatch(reached::containsKey)) {
                        inputs = false; break;
                    }
                if (inputs) reached.putIfAbsent(recipe.result(), "recipe:" + entry.id());
            }
            if (reached.containsKey(items.forBlock(BlockType.FURNACE))
                    && reached.keySet().stream().anyMatch(item -> item.fuelSeconds > 0))
                for (var recipe : items.recipes().smelting())
                    if (recipe.input().alternatives().stream().anyMatch(reached::containsKey))
                        reached.putIfAbsent(recipe.result(), "smelting:" + recipe.id());
            changed = before != reached.size();
        } while (changed);
        List<Item> missing = items.all().stream().filter(item -> !item.hidden && !reached.containsKey(item))
                .sorted(Comparator.comparing(item -> item.id)).toList();
        return new Result(reached, missing);
    }

    private static void add(Map<Item, String> reached, Set<Item> outputs, String source) {
        outputs.stream().sorted(Comparator.comparing(item -> item.id)).forEach(item -> reached.putIfAbsent(item, source));
    }
}
