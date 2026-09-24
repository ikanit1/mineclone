package com.mineclone.item.recipe;

import com.mineclone.data.DataPack;
import com.mineclone.data.JsonObject;
import com.mineclone.data.ResourceId;
import com.mineclone.item.Item;
import com.mineclone.item.ItemRegistry;
import com.mineclone.world.ItemStack;
import com.mineclone.world.Recipes;
import java.util.*;

/** Immutable, validated recipes loaded after item and tag references are resolved. */
public final class RecipeRegistry {
    public record Crafting(ResourceId id, int order, String group, Recipes.Recipe recipe) {}
    public record Smelt(ResourceId id, int order, Ingredient input, Item result, int count, float time) {
        public ItemStack output() { return new ItemStack(result, count); }
    }
    private record Output(Item item, int count) {}
    private final List<Crafting> crafting;
    private final List<Smelt> smelting;

    private RecipeRegistry(List<Crafting> crafting, List<Smelt> smelting) {
        this.crafting = List.copyOf(crafting);
        this.smelting = List.copyOf(smelting);
    }
    public static RecipeRegistry empty() { return new RecipeRegistry(List.of(), List.of()); }
    public static RecipeRegistry load(DataPack pack) { return ItemRegistry.load(pack).recipes(); }

    /** Also supports focused content validation against an already loaded item registry. */
    public static RecipeRegistry load(DataPack pack, ItemRegistry items) {
        List<Crafting> crafting = new ArrayList<>();
        List<Smelt> smelting = new ArrayList<>();
        Set<ResourceId> ids = new HashSet<>();
        for (String kind : List.of("recipes", "smelting")) {
            for (DataPack.Entry file : pack.files(kind)) {
                for (String key : file.json().keys()) {
                    JsonObject definition = file.json().object(key);
                    ResourceId id = resource(definition, "id", key, file.namespace());
                    if (!ids.add(id)) throw file.json().error(key, "duplicate recipe id " + id);
                    String type = definition.string("type", kind.equals("smelting") ? "smelting" : "shaped");
                    int order = definition.integer("order", 0);
                    if (type.equals("smelting")) {
                        definition.allowOnly("type", "input", "result", "time", "order", "group");
                        Ingredient input = ingredient(definition, "input", definition.string("input"), file.namespace(), items);
                        Output result = output(definition, file.namespace(), items);
                        float time = definition.number("time", 8f);
                        if (!Float.isFinite(time) || time <= 0) throw definition.error("time", "must be finite and positive");
                        smelting.add(new Smelt(id, order, input, result.item, result.count, time));
                    } else if (type.equals("shaped") || type.equals("shapeless")) {
                        definition.allowOnly("type", "pattern", "key", "ingredients", "result", "mirrored", "order", "group");
                        Output result = output(definition, file.namespace(), items);
                        Ingredient[] pattern;
                        int width, height;
                        boolean shapeless = type.equals("shapeless");
                        if (shapeless) {
                            if (definition.has("pattern") || definition.has("key"))
                                throw definition.error("pattern", "shapeless recipes use ingredients");
                            List<String> values = definition.strings("ingredients");
                            if (values.isEmpty() || values.size() > 9)
                                throw definition.error("ingredients", "must contain 1 to 9 ingredients");
                            width = values.size(); height = 1;
                            pattern = new Ingredient[values.size()];
                            for (int i = 0; i < values.size(); i++) pattern[i] = ingredient(definition,
                                    "ingredients[" + i + "]", values.get(i), file.namespace(), items);
                        } else {
                            if (definition.has("ingredients")) throw definition.error("ingredients", "shaped recipes use pattern/key");
                            List<String> rows = definition.strings("pattern");
                            if (rows.isEmpty() || rows.size() > 3) throw definition.error("pattern", "must have 1 to 3 rows");
                            width = rows.get(0).length(); height = rows.size();
                            if (width < 1 || width > 3) throw definition.error("pattern[0]", "must have 1 to 3 columns");
                            JsonObject legend = definition.object("key");
                            Map<Character, Ingredient> keys = new HashMap<>();
                            for (String symbol : legend.keys()) {
                                if (symbol.length() != 1 || symbol.equals(".") || symbol.equals(" "))
                                    throw legend.error(symbol, "key must be one non-empty pattern symbol");
                                keys.put(symbol.charAt(0), ingredient(legend, symbol, legend.string(symbol), file.namespace(), items));
                            }
                            Set<Character> used = new HashSet<>();
                            pattern = new Ingredient[width * height];
                            for (int y = 0; y < height; y++) {
                                if (rows.get(y).length() != width) throw definition.error("pattern[" + y + "]", "ragged pattern");
                                for (int x = 0; x < width; x++) {
                                    char symbol = rows.get(y).charAt(x);
                                    if (symbol == '.' || symbol == ' ') continue;
                                    Ingredient value = keys.get(symbol);
                                    if (value == null) throw definition.error("pattern[" + y + "]", "key " + symbol + " has no legend");
                                    pattern[y * width + x] = value;
                                    used.add(symbol);
                                }
                            }
                            if (used.isEmpty()) throw definition.error("pattern", "must contain an ingredient");
                            for (char symbol : keys.keySet()) if (!used.contains(symbol))
                                throw legend.error(String.valueOf(symbol), "unused pattern key");
                        }
                        boolean mirrored = definition.bool("mirrored", false);
                        if (shapeless && mirrored) throw definition.error("mirrored", "shapeless recipes cannot be mirrored");
                        Recipes.Recipe recipe = recipe(result, width, height, pattern, shapeless, mirrored);
                        crafting.add(new Crafting(id, order, definition.string("group", ""), recipe));
                    } else throw definition.error("type", "unknown recipe type " + type);
                }
            }
        }
        crafting.sort(Comparator.comparingInt(Crafting::order).thenComparing(entry -> entry.id().toString()));
        smelting.sort(Comparator.comparingInt(Smelt::order).thenComparing(entry -> entry.id().toString()));
        return new RecipeRegistry(crafting, smelting);
    }

    private static Recipes.Recipe recipe(Output result, int width, int height, Ingredient[] ingredients,
                                         boolean shapeless, boolean mirrored) {
        Item[] pattern = new Item[ingredients.length];
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < ingredients.length; i++) if (ingredients[i] != null) {
            pattern[i] = ingredients[i].example();
            counts.merge(pattern[i], 1, Integer::sum);
        }
        // Stable encounter order resolves equal-frequency preview ingredients.
        List<Map.Entry<Item, Integer>> summary = new ArrayList<>(counts.entrySet());
        summary.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        var first = summary.get(0);
        var second = summary.size() > 1 ? summary.get(1) : null;
        return new Recipes.Recipe(first.getKey(), first.getValue(), second == null ? null : second.getKey(),
                second == null ? 0 : second.getValue(), result.item, result.count, width, height, pattern,
                shapeless, mirrored, ingredients);
    }

    private static Ingredient ingredient(JsonObject object, String path, String value, String namespace, ItemRegistry items) {
        boolean tag = value.startsWith("#");
        ResourceId id = resource(object, path, tag ? value.substring(1) : value, namespace);
        if (tag) {
            if (!items.tags().has(id)) throw object.error(path, "unknown tag " + id);
            Set<Item> members = items.tags().members(id);
            if (members.isEmpty()) throw object.error(path, "empty tag " + id);
            return Ingredient.tag(id, members);
        }
        Item item = items.get(id);
        if (item == null) throw object.error(path, "unknown item " + id);
        return Ingredient.item(item);
    }

    private static Output output(JsonObject definition, String namespace, ItemRegistry items) {
        JsonObject where = definition;
        String path = "result", value;
        int count = 1;
        if (definition.raw("result") instanceof String text) value = text;
        else {
            where = definition.object("result"); where.allowOnly("item", "count");
            value = where.string("item"); count = where.integer("count", 1); path = "item";
        }
        ResourceId id = resource(where, path, value, namespace);
        Item item = items.get(id);
        if (item == null) throw where.error(path, "unknown result item " + id);
        if (count < 1 || count > item.maxStack) throw where.error("count", "result count must be 1.." + item.maxStack);
        return new Output(item, count);
    }

    private static ResourceId resource(JsonObject object, String path, String value, String namespace) {
        try { return ResourceId.parse(value, namespace); }
        catch (IllegalArgumentException bad) { throw object.error(path, bad.getMessage()); }
    }

    public List<Crafting> crafting() { return crafting; }
    public List<Smelt> smelting() { return smelting; }
    public Smelt smelting(Item input) {
        for (Smelt recipe : smelting) if (recipe.input.matches(input)) return recipe;
        return null;
    }
}
