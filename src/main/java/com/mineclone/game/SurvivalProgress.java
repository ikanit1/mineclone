package com.mineclone.game;

import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

import java.util.Map;

/**
 * Короткая цепочка целей, которая знакомит игрока с основным циклом выживания.
 *
 * <p>Прогресс монотонный: потраченные на рецепт доски или сломанная кирка не
 * отбрасывают игрока назад. При открытии старого мира цепочка умеет восстановить
 * разумную точку по самому продвинутому предмету в инвентаре.
 */
public final class SurvivalProgress {

    public static final String SAVE_SECTION = "mineclone:survival_progress";

    public record Objective(String title, String detail, int current, int target,
                            int completed, int total, boolean finished) {
    }

    private record Goal(String title, String detail, String[] items, int amount) {
    }

    private static final Goal[] GOALS = {
            new Goal("Добудьте бревно", "Срубите дерево рукой", ids("log"), 1),
            new Goal("Сделайте доски", "Откройте инвентарь [E] и создайте 4 доски", ids("planks"), 4),
            new Goal("Сделайте верстак", "Заполните досками квадрат 2×2", ids("crafting_table"), 1),
            new Goal("Сделайте палки", "Положите две доски вертикально в сетке 2×2", ids("stick"), 4),
            new Goal("Создайте деревянную кирку", "На верстаке: 3 доски сверху и 2 палки", ids("wooden_pickaxe"), 1),
            new Goal("Добудьте 8 булыжника", "Камень быстрее добывать киркой", ids("cobblestone"), 8),
            new Goal("Создайте каменную кирку", "Следующий шаг — железная руда", ids("stone_pickaxe"), 1),
            new Goal("Создайте печь", "Поставьте её, чтобы готовить еду на топливе", ids("furnace"), 1),
            new Goal("Приготовьте еду", "Положите мясо и топливо в печь",
                    ids("cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton"), 1),
            new Goal("Выплавьте 3 слитка железа", "Руду и уголь положите в печь", ids("iron_ingot"), 3),
            new Goal("Создайте железную кирку", "Ею можно добыть алмазную руду", ids("iron_pickaxe"), 1),
            new Goal("Найдите 3 алмаза", "Алмазная руда встречается глубоко под землёй", ids("diamond"), 3),
            new Goal("Создайте алмазную кирку", "Самый быстрый инструмент в цепочке", ids("diamond_pickaxe"), 1),
            new Goal("Сделайте спальник", "Закрепите точку возрождения перед вылазкой", ids("bedroll"), 1),
    };

    /** Предмет доказывает, что все более ранние шаги уже были пройдены. */
    private static final Map<String, Integer> EVIDENCE = Map.ofEntries(
            Map.entry("log", 0), Map.entry("planks", 1), Map.entry("crafting_table", 2),
            Map.entry("stick", 3), Map.entry("wooden_pickaxe", 4),
            Map.entry("cobblestone", 5), Map.entry("stone_pickaxe", 6), Map.entry("furnace", 7),
            Map.entry("cooked_beef", 8), Map.entry("cooked_porkchop", 8),
            Map.entry("cooked_chicken", 8), Map.entry("cooked_mutton", 8),
            Map.entry("iron_ingot", 9), Map.entry("iron_pickaxe", 10),
            Map.entry("diamond", 11), Map.entry("diamond_pickaxe", 12),
            Map.entry("bedroll", 13));

    private int stage;

    public SurvivalProgress() {
        this(0);
    }

    private SurvivalProgress(int stage) {
        this.stage = Math.max(0, Math.min(GOALS.length, stage));
    }

    /**
     * Продвигает цепочку по содержимому инвентаря.
     *
     * @return название последней выполненной цели или {@code null}
     */
    public String update(Inventory inventory) {
        String completed = null;
        while (stage < GOALS.length && satisfied(stage, inventory)) {
            completed = GOALS[stage].title;
            stage++;
        }
        return completed;
    }

    /** Восстанавливает старый сейв без всплывающих сообщений. */
    public void synchronize(Inventory inventory) {
        update(inventory);
    }

    public Objective objective(Inventory inventory) {
        if (stage >= GOALS.length)
            return new Objective("Путь выживания завершён",
                    "Стройте базу, исследуйте руины и готовьтесь к ночи",
                    1, 1, GOALS.length, GOALS.length, true);
        Goal g = GOALS[stage];
        return new Objective(g.title, g.detail, count(inventory, g.items), g.amount,
                stage, GOALS.length, false);
    }

    public int stage() {
        return stage;
    }

    /** Версия + номер этапа. Секция специально маленькая и независимо версионируется. */
    public byte[] encode() {
        return new byte[] { 2, (byte) stage };
    }

    /**
     * Whether {@link #decode} understands this payload. One it does not — a
     * newer build's progress — must be kept as it is, not replaced by a fresh
     * chain, or opening the world here would erase that build's progress.
     */
    public static boolean readable(byte[] bytes) {
        return bytes != null && bytes.length == 2 && (bytes[0] == 1 || bytes[0] == 2);
    }

    public static SurvivalProgress decode(byte[] bytes) {
        if (bytes == null || bytes.length < 2)
            return new SurvivalProgress();
        if (bytes[0] == 1) {
            int old = Math.min(bytes[1] & 0xFF, 12);
            int[] migrated = { 0, 1, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 };
            return new SurvivalProgress(migrated[old]);
        }
        if (bytes[0] != 2)
            return new SurvivalProgress();
        return new SurvivalProgress(bytes[1] & 0xFF);
    }

    private static boolean satisfied(int index, Inventory inventory) {
        Goal g = GOALS[index];
        if (count(inventory, g.items) >= g.amount)
            return true;
        // Поздний предмет не заставляет повторять вводные шаги в старом мире.
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s == null)
                continue;
            Integer rank = EVIDENCE.get(s.item.id.path());
            if (rank != null && rank > index)
                return true;
        }
        return false;
    }

    private static int count(Inventory inventory, String[] ids) {
        int amount = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s == null)
                continue;
            String id = s.item.id.path();
            for (String wanted : ids)
                if (wanted.equals(id)) {
                    amount += s.count;
                    break;
                }
        }
        return amount;
    }

    private static String[] ids(String... ids) {
        return ids;
    }
}
