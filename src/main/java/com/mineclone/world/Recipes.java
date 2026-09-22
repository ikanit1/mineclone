package com.mineclone.world;

import com.mineclone.item.Item;
import com.mineclone.item.Items;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft-подобные рецепты: форма лежит в настоящей сетке 2×2 или 3×3.
 *
 * <p>Рецепт можно сдвигать внутри сетки, а асимметричные формы (топор,
 * ступени) — отражать. Пустая клетка внутри формы тоже значима: восемь
 * булыжников кольцом дают печь, а заполненные девять — уже не этот рецепт.
 */
public final class Recipes {

    /**
     * {@code need/handle} оставлены как краткая сводка ингредиентов для
     * справочника и старых тестов. Источник истины — {@code pattern}.
     */
    public record Recipe(Item need, int needCount, Item handle, int handleCount,
                         Item result, int resultCount,
                         int width, int height, Item[] pattern,
                         boolean shapeless, boolean mirrored) {
        public Recipe {
            pattern = pattern.clone();
        }

        @Override
        public Item[] pattern() {
            return pattern.clone();
        }

        public Item at(int x, int y) {
            return x < 0 || y < 0 || x >= width || y >= height
                    ? null : pattern[y * width + x];
        }

        public String resultName() {
            return result.name;
        }

        public boolean fits(int gridWidth, int gridHeight) {
            return shapeless ? ingredientCount() <= gridWidth * gridHeight
                    : width <= gridWidth && height <= gridHeight;
        }

        public int ingredientCount() {
            int n = 0;
            for (Item item : pattern)
                if (item != null)
                    n++;
            return n;
        }
    }

    private record Placement(Recipe recipe, int ox, int oy, boolean mirror) {
    }

    private static Item item(String id) {
        return Items.get().require(id);
    }

    private static Recipe shaped(String out, int outCount, boolean mirrored,
            String[] rows, String... keys) {
        if (rows.length == 0)
            throw new IllegalArgumentException("empty recipe");
        int width = rows[0].length();
        Map<Character, Item> legend = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i += 2)
            legend.put(keys[i].charAt(0), item(keys[i + 1]));
        Item[] pattern = new Item[width * rows.length];
        for (int y = 0; y < rows.length; y++) {
            if (rows[y].length() != width)
                throw new IllegalArgumentException("ragged recipe " + out);
            for (int x = 0; x < width; x++) {
                char c = rows[y].charAt(x);
                if (c != '.') {
                    Item ingredient = legend.get(c);
                    if (ingredient == null)
                        throw new IllegalArgumentException("unknown recipe key " + c + " in " + out);
                    pattern[y * width + x] = ingredient;
                }
            }
        }
        return recipe(item(out), outCount, width, rows.length, pattern, false, mirrored);
    }

    private static Recipe shapeless(String out, int outCount, String... ingredients) {
        Item[] pattern = new Item[ingredients.length];
        for (int i = 0; i < ingredients.length; i++)
            pattern[i] = item(ingredients[i]);
        return recipe(item(out), outCount, ingredients.length, 1, pattern, true, false);
    }

    private static Recipe recipe(Item result, int resultCount, int width, int height,
            Item[] pattern, boolean shapeless, boolean mirrored) {
        LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
        for (Item ingredient : pattern)
            if (ingredient != null)
                counts.merge(ingredient, 1, Integer::sum);
        var it = counts.entrySet().iterator();
        var first = it.next();
        var second = it.hasNext() ? it.next() : null;
        return new Recipe(first.getKey(), first.getValue(),
                second == null ? null : second.getKey(), second == null ? 0 : second.getValue(),
                result, resultCount, width, height, pattern, shapeless, mirrored);
    }

    private static Recipe tool(String material, String result, String[] shape, boolean mirror) {
        return shaped(result, 1, mirror, shape, "M", material, "S", "stick");
    }

    private static Recipe[] all;

    /** Таблица строится лениво после загрузки реестра предметов. */
    private static synchronized Recipe[] table() {
        if (all != null)
            return all;
        List<Recipe> recipes = new ArrayList<>();

        // Карманная сетка 2×2.
        recipes.add(shapeless("planks", 4, "log"));
        recipes.add(shaped("stick", 4, false, new String[] { "P", "P" }, "P", "planks"));
        recipes.add(shaped("crafting_table", 1, false,
                new String[] { "PP", "PP" }, "P", "planks"));
        recipes.add(shaped("torch", 4, false,
                new String[] { "C", "S" }, "C", "coal", "S", "stick"));

        // Верстак 3×3: формы совпадают с привычными Minecraft-силуэтами.
        String[] pickaxe = { "MMM", ".S.", ".S." };
        String[] axe = { "MM.", "MS.", ".S." };
        String[] shovel = { ".M.", ".S.", ".S." };
        String[] sword = { ".M.", ".M.", ".S." };
        String[][] tiers = {
                { "planks", "wooden" }, { "cobblestone", "stone" },
                { "iron_ingot", "iron" }, { "diamond", "diamond" },
                { "gold_ingot", "gold" }, { "copper_ingot", "copper" }
        };
        for (String[] tier : tiers) {
            recipes.add(tool(tier[0], tier[1] + "_pickaxe", pickaxe, false));
            recipes.add(tool(tier[0], tier[1] + "_axe", axe, true));
            recipes.add(tool(tier[0], tier[1] + "_shovel", shovel, false));
            recipes.add(tool(tier[0], tier[1] + "_sword", sword, false));
        }

        // Лук и стрелы. Тетива — паутина: своей нити в игре нет, а паутина
        // уже стоит в шаблоне данжа, так что лук добывается в выживании и
        // стоит похода под землю. Форма — привычный майнкрафтовский силуэт.
        recipes.add(shaped("bow", 1, false,
                new String[] { ".SY", "S.Y", ".SY" }, "S", "stick", "Y", "cobweb"));
        // Наконечник из гравия: кремня отдельным предметом нет, а гравий и
        // есть то, из чего его набивают. Оперение опущено — у рецепта может
        // быть только два разных ингредиента.
        recipes.add(shaped("arrow", 2, false,
                new String[] { "G", "S" }, "G", "gravel", "S", "stick"));

        recipes.add(shaped("chest", 1, false,
                new String[] { "PPP", "P.P", "PPP" }, "P", "planks"));
        recipes.add(shaped("furnace", 1, false,
                new String[] { "CCC", "C.C", "CCC" }, "C", "cobblestone"));
        recipes.add(shaped("stairs", 4, true,
                new String[] { "P..", "PP.", "PPP" }, "P", "planks"));
        recipes.add(shaped("door", 3, false,
                new String[] { "PP", "PP", "PP" }, "P", "planks"));
        recipes.add(shaped("bedroll", 1, false,
                new String[] { "LLL", "PPP" }, "L", "leaves", "P", "planks"));

        all = recipes.toArray(Recipe[]::new);
        return all;
    }

    private Recipes() {
    }

    /** Все рецепты — для книги и тестов. */
    public static Recipe[] all() {
        return table().clone();
    }

    /** Сколько предметов этого вида лежит в инвентаре. */
    public static int count(Inventory inv, Item item) {
        if (item == null)
            return 0;
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s != null && s.item == item)
                n += s.count;
        }
        return n;
    }

    /** Хватает ли суммарных материалов, без учёта их позиции в сетке. */
    public static boolean canCraft(Inventory inv, Recipe recipe) {
        for (var e : ingredientCounts(recipe).entrySet())
            if (count(inv, e.getKey()) < e.getValue())
                return false;
        return true;
    }

    /**
     * Совместимый программный крафт для тестов и будущей книги рецептов.
     * Игровой интерфейс использует {@link #match} и {@link #consume}.
     */
    public static boolean craft(Inventory inv, Recipe recipe) {
        if (!canCraft(inv, recipe))
            return false;
        ItemStack out = result(recipe);
        if (!inv.canAdd(out, out.count))
            return false;
        for (var e : ingredientCounts(recipe).entrySet())
            take(inv, e.getKey(), e.getValue());
        inv.add(out);
        return true;
    }

    /** Рецепт, который в точности совпал с содержимым сетки. */
    public static Recipe match(ItemStack[] grid, int gridWidth) {
        Placement p = placement(grid, gridWidth);
        return p == null ? null : p.recipe;
    }

    /**
     * Забирает по одному ингредиенту из каждой занятой клетки и возвращает
     * полный результат. Если форма уже не совпадает — ничего не меняет.
     */
    public static ItemStack consume(ItemStack[] grid, int gridWidth) {
        Placement p = placement(grid, gridWidth);
        if (p == null)
            return null;
        if (p.recipe.shapeless) {
            boolean[] used = new boolean[grid.length];
            for (Item wanted : p.recipe.pattern) {
                for (int i = 0; i < grid.length; i++)
                    if (!used[i] && grid[i] != null && grid[i].item == wanted) {
                        shrink(grid, i);
                        used[i] = true;
                        break;
                    }
            }
        } else {
            for (int y = 0; y < p.recipe.height; y++)
                for (int x = 0; x < p.recipe.width; x++) {
                    int px = p.mirror ? p.recipe.width - 1 - x : x;
                    if (p.recipe.at(px, y) != null)
                        shrink(grid, (p.oy + y) * gridWidth + p.ox + x);
                }
        }
        return result(p.recipe);
    }

    /** Готовая стопка результата. */
    public static ItemStack result(Recipe recipe) {
        return new ItemStack(recipe.result, recipe.resultCount);
    }

    /** Рецепты, для которых в инвентаре хватает ресурсов. */
    public static List<Recipe> available(Inventory inv) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe recipe : table())
            if (canCraft(inv, recipe))
                out.add(recipe);
        return out;
    }

    private static Placement placement(ItemStack[] grid, int gridWidth) {
        if (grid == null || gridWidth <= 0 || grid.length % gridWidth != 0)
            return null;
        int gridHeight = grid.length / gridWidth;
        for (Recipe recipe : table()) {
            if (!recipe.fits(gridWidth, gridHeight))
                continue;
            if (recipe.shapeless && shapelessMatches(recipe, grid))
                return new Placement(recipe, 0, 0, false);
            if (recipe.shapeless)
                continue;
            for (int oy = 0; oy <= gridHeight - recipe.height; oy++)
                for (int ox = 0; ox <= gridWidth - recipe.width; ox++) {
                    if (shapedMatches(recipe, grid, gridWidth, gridHeight, ox, oy, false))
                        return new Placement(recipe, ox, oy, false);
                    if (recipe.mirrored
                            && shapedMatches(recipe, grid, gridWidth, gridHeight, ox, oy, true))
                        return new Placement(recipe, ox, oy, true);
                }
        }
        return null;
    }

    private static boolean shapedMatches(Recipe recipe, ItemStack[] grid,
            int gridWidth, int gridHeight, int ox, int oy, boolean mirror) {
        for (int gy = 0; gy < gridHeight; gy++)
            for (int gx = 0; gx < gridWidth; gx++) {
                Item expected = null;
                int rx = gx - ox, ry = gy - oy;
                if (rx >= 0 && ry >= 0 && rx < recipe.width && ry < recipe.height)
                    expected = recipe.at(mirror ? recipe.width - 1 - rx : rx, ry);
                ItemStack actual = grid[gy * gridWidth + gx];
                if ((actual == null ? null : actual.item) != expected)
                    return false;
            }
        return true;
    }

    private static boolean shapelessMatches(Recipe recipe, ItemStack[] grid) {
        IdentityHashMap<Item, Integer> wanted = ingredientCounts(recipe);
        IdentityHashMap<Item, Integer> actual = new IdentityHashMap<>();
        for (ItemStack stack : grid)
            if (stack != null)
                actual.merge(stack.item, 1, Integer::sum);
        return actual.equals(wanted);
    }

    private static IdentityHashMap<Item, Integer> ingredientCounts(Recipe recipe) {
        IdentityHashMap<Item, Integer> counts = new IdentityHashMap<>();
        for (Item ingredient : recipe.pattern)
            if (ingredient != null)
                counts.merge(ingredient, 1, Integer::sum);
        return counts;
    }

    private static void take(Inventory inv, Item item, int amount) {
        for (int i = 0; i < inv.size() && amount > 0; i++) {
            ItemStack stack = inv.get(i);
            if (stack == null || stack.item != item)
                continue;
            int used = Math.min(amount, stack.count);
            stack.count -= used;
            amount -= used;
            if (stack.count <= 0)
                inv.set(i, null);
        }
    }

    private static void shrink(ItemStack[] grid, int index) {
        if (grid[index] != null && --grid[index].count <= 0)
            grid[index] = null;
    }
}
