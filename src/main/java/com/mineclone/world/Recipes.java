package com.mineclone.world;

import com.mineclone.item.Item;
import com.mineclone.item.ItemRegistry;
import com.mineclone.item.Items;
import com.mineclone.item.recipe.Ingredient;
import com.mineclone.item.recipe.RecipeRegistry;

import java.util.ArrayList;
import java.util.List;

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
     * справочника и старых тестов: два наиболее частых ингредиента.
     * {@code ingredients} задаёт точные предметы/теги; {@code pattern} — их
     * стабильные представители для существующего интерфейса предпросмотра.
     */
    public record Recipe(Item need, int needCount, Item handle, int handleCount,
                         Item result, int resultCount,
                         int width, int height, Item[] pattern,
                         boolean shapeless, boolean mirrored, Ingredient[] ingredients) {
        public Recipe {
            pattern = pattern.clone();
            if (ingredients == null) {
                ingredients = new Ingredient[pattern.length];
                for (int i = 0; i < pattern.length; i++)
                    if (pattern[i] != null) ingredients[i] = Ingredient.item(pattern[i]);
            } else ingredients = ingredients.clone();
            if (ingredients.length != pattern.length) throw new IllegalArgumentException("Ingredient pattern length mismatch");
        }

        /** Source-compatible constructor for exact-item callers. */
        public Recipe(Item need, int needCount, Item handle, int handleCount, Item result, int resultCount,
                      int width, int height, Item[] pattern, boolean shapeless, boolean mirrored) {
            this(need, needCount, handle, handleCount, result, resultCount, width, height,
                    pattern, shapeless, mirrored, null);
        }

        @Override
        public Item[] pattern() {
            return pattern.clone();
        }

        @Override public Ingredient[] ingredients() { return ingredients.clone(); }

        public Ingredient ingredientAt(int x, int y) {
            return x < 0 || y < 0 || x >= width || y >= height ? null : ingredients[y * width + x];
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

    private static ItemRegistry cachedRegistry;
    private static Recipe[] cachedRecipes;

    /** Reload the immutable view when tests or a data reload replace the item registry. */
    private static synchronized Recipe[] table() {
        var registry = Items.get();
        if (cachedRegistry != registry) {
            cachedRecipes = registry.recipes().crafting().stream()
                    .map(RecipeRegistry.Crafting::recipe).toArray(Recipe[]::new);
            cachedRegistry = registry;
        }
        return cachedRecipes;
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
        return assign(recipe, inventorySlots(inv), false) != null;
    }

    /**
     * Совместимый программный крафт для тестов и будущей книги рецептов.
     * Игровой интерфейс использует {@link #match} и {@link #consume}.
     */
    public static boolean craft(Inventory inv, Recipe recipe) {
        int[] consume = assign(recipe, inventorySlots(inv), false);
        if (consume == null)
            return false;
        ItemStack out = result(recipe);
        if (!inv.canAdd(out, out.count))
            return false;
        for (int i = 0; i < consume.length; i++) if (consume[i] > 0) {
            ItemStack stack = inv.get(i);
            stack.count -= consume[i];
            if (stack.count <= 0) inv.set(i, null);
        }
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
            // A successful shapeless match uses every occupied grid cell exactly once.
            for (int i = 0; i < grid.length; i++) if (grid[i] != null) shrink(grid, i);
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
                Ingredient expected = null;
                int rx = gx - ox, ry = gy - oy;
                if (rx >= 0 && ry >= 0 && rx < recipe.width && ry < recipe.height)
                    expected = recipe.ingredientAt(mirror ? recipe.width - 1 - rx : rx, ry);
                ItemStack actual = grid[gy * gridWidth + gx];
                if (expected == null ? actual != null : actual == null || actual.count <= 0 || !expected.matches(actual.item))
                    return false;
            }
        return true;
    }

    private static boolean shapelessMatches(Recipe recipe, ItemStack[] grid) {
        int occupied = 0;
        for (ItemStack stack : grid) if (stack != null) occupied++;
        return occupied == recipe.ingredientCount() && assign(recipe, grid, true) != null;
    }

    private static ItemStack[] inventorySlots(Inventory inventory) {
        ItemStack[] slots = new ItemStack[inventory.size()];
        for (int i = 0; i < slots.length; i++) slots[i] = inventory.get(i);
        return slots;
    }

    /** Bipartite matching prevents a broad tag from consuming an exact ingredient's only item. */
    private static int[] assign(Recipe recipe, ItemStack[] slots, boolean onePerCell) {
        Ingredient[] wanted = java.util.Arrays.stream(recipe.ingredients).filter(java.util.Objects::nonNull)
                .toArray(Ingredient[]::new);
        List<Item> tokens = new ArrayList<>();
        List<Integer> tokenSlots = new ArrayList<>();
        for (int slot = 0; slot < slots.length; slot++) {
            ItemStack stack = slots[slot];
            if (stack == null || stack.count <= 0) continue;
            int copies = onePerCell ? 1 : Math.min(stack.count, wanted.length);
            for (int copy = 0; copy < copies; copy++) { tokens.add(stack.item); tokenSlots.add(slot); }
        }
        if (tokens.size() < wanted.length) return null;
        int[] owners = new int[tokens.size()];
        java.util.Arrays.fill(owners, -1);
        for (int ingredient = 0; ingredient < wanted.length; ingredient++)
            if (!assignOne(ingredient, wanted, tokens, owners, new boolean[tokens.size()])) return null;
        int[] consumed = new int[slots.length];
        for (int token = 0; token < owners.length; token++) if (owners[token] >= 0) consumed[tokenSlots.get(token)]++;
        return consumed;
    }

    private static boolean assignOne(int ingredient, Ingredient[] wanted, List<Item> tokens, int[] owners, boolean[] visited) {
        for (int token = 0; token < tokens.size(); token++) {
            if (visited[token] || !wanted[ingredient].matches(tokens.get(token))) continue;
            visited[token] = true;
            if (owners[token] < 0 || assignOne(owners[token], wanted, tokens, owners, visited)) {
                owners[token] = ingredient;
                return true;
            }
        }
        return false;
    }

    private static void shrink(ItemStack[] grid, int index) {
        if (grid[index] != null && --grid[index].count <= 0)
            grid[index] = null;
    }
}
