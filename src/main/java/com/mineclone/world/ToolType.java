package com.mineclone.world;

/**
 * Инструменты: три класса на четырёх материалах.
 *
 * Класс решает, ЧТО инструмент копает быстро, материал — насколько быстро,
 * сколько он живёт и что вообще поддаётся. Кирка из дерева ломает камень, но
 * не берёт алмаз — именно этот порог и заставляет спускаться глубже.
 *
 * Значения новых констант добавляются строго в конец: порядковый номер лежит
 * в сейве как id предмета.
 */
public enum ToolType {
    //             класс         уровень  скорость  прочность  тайл
    WOOD_PICKAXE   (Kind.PICKAXE, 1, 2.2f,  60,  57, "Деревянная кирка"),
    STONE_PICKAXE  (Kind.PICKAXE, 2, 4.0f, 132,  58, "Каменная кирка"),
    IRON_PICKAXE   (Kind.PICKAXE, 3, 6.5f, 251,  59, "Железная кирка"),
    DIAMOND_PICKAXE(Kind.PICKAXE, 4, 9.0f, 900,  60, "Алмазная кирка"),

    WOOD_AXE       (Kind.AXE,     1, 2.2f,  60,  61, "Деревянный топор"),
    STONE_AXE      (Kind.AXE,     2, 4.0f, 132,  62, "Каменный топор"),
    IRON_AXE       (Kind.AXE,     3, 6.5f, 251,  63, "Железный топор"),
    DIAMOND_AXE    (Kind.AXE,     4, 9.0f, 900,  64, "Алмазный топор"),

    WOOD_SHOVEL    (Kind.SHOVEL,  1, 2.2f,  60,  65, "Деревянная лопата"),
    STONE_SHOVEL   (Kind.SHOVEL,  2, 4.0f, 132,  66, "Каменная лопата"),
    IRON_SHOVEL    (Kind.SHOVEL,  3, 6.5f, 251,  67, "Железная лопата"),
    DIAMOND_SHOVEL (Kind.SHOVEL,  4, 9.0f, 900,  68, "Алмазная лопата");

    /** Что инструмент умеет: кирка — камень, топор — дерево, лопата — рыхлое. */
    public enum Kind { PICKAXE, AXE, SHOVEL }

    public final Kind kind;
    /** Уровень материала 1..4. Блок поддаётся, если уровень не ниже требуемого. */
    public final int level;
    /** Во сколько раз быстрее голых рук по «своему» материалу. */
    public final float speed;
    /** Сколько блоков инструмент выдержит, прежде чем сломаться. */
    public final int durability;
    public final int tile;
    public final String displayName;

    ToolType(Kind kind, int level, float speed, int durability, int tile, String displayName) {
        this.kind = kind;
        this.level = level;
        this.speed = speed;
        this.durability = durability;
        this.tile = tile;
        this.displayName = displayName;
    }

    public static final ToolType[] VALUES = values();

    /** Безопасное чтение из сейва: неизвестный id — не предмет, а пусто. */
    public static ToolType byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }

    /** Подходит ли инструмент к блоку — только по классу, без учёта уровня. */
    public boolean suits(BlockType block) {
        return block.preferredTool() == kind;
    }
}
