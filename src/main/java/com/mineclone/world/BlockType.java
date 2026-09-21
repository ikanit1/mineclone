package com.mineclone.world;

import com.mineclone.item.ToolClass;

public enum BlockType {
    AIR         (false, true,  false, -1, -1, -1, 0f,    0f,    0f,    0,  0f),
    GRASS       (true,  false, false,  1,  0,  2, 0.40f, 0.55f, 0.28f, 0,  0.6f),
    DIRT        (true,  false, false,  2,  2,  2, 0.47f, 0.33f, 0.22f, 0,  0.5f),
    STONE       (true,  false, false,  3,  3,  3, 0.47f, 0.47f, 0.47f, 0,  7.5f),
    SAND        (true,  false, false,  4,  4,  4, 0.86f, 0.78f, 0.55f, 0,  0.5f),
    WOOD        (true,  false, false,  5,  6,  6, 0.37f, 0.26f, 0.15f, 0,  2.0f),
    LEAVES      (true,  false, true,   7,  7,  7, 0.20f, 0.47f, 0.16f, 0,  0.2f),
    WATER       (false, true,  false,  8,  8,  8, 0.16f, 0.35f, 0.78f, 0,  0f),
    BEDROCK     (true,  false, false,  9,  9,  9, 0.20f, 0.20f, 0.20f, 0,  Float.MAX_VALUE),
    COBBLE      (true,  false, false, 10, 10, 10, 0.45f, 0.45f, 0.45f, 0,  7.5f),
    PLANKS      (true,  false, false, 11, 11, 11, 0.60f, 0.45f, 0.27f, 0,  1.5f),
    TORCH       (false, true,  false, 12, 12, 12, 1.0f,  0.85f, 0.40f, 15, 0.05f),
    GLASS       (true,  false, true,  14, 14, 14, 0.70f, 0.90f, 0.90f, 0,  0.3f),
    DOOR_CLOSED (true,  false, true,  15, 15, 15, 0.60f, 0.45f, 0.27f, 0,  1.5f),
    DOOR_OPEN   (false, true,  false, 15, 15, 15, 0.60f, 0.45f, 0.27f, 0,  1.5f),
    STAIRS      (true,  false, true,  11, 11, 11, 0.60f, 0.45f, 0.27f, 0,  1.5f),
    WATER_FLOW  (false, true,  false,  8,  8,  8, 0.16f, 0.35f, 0.78f, 0,  0f),
    SNOWY_GRASS (true,  false, false, 48, 47,  2, 0.92f, 0.94f, 0.96f, 0,  0.6f),
    CACTUS      (true,  false, false, 49, 50, 50, 0.20f, 0.55f, 0.25f, 0,  0.4f),
    // Руды. Новые значения добавляются СТРОГО В КОНЕЦ: порядковый номер
    // enum-константы лежит в сейвах как id блока, вставка в середину
    // переименует блоки во всех сохранённых мирах.
    COAL_ORE    (true,  false, false, 51, 51, 51, 0.16f, 0.16f, 0.18f, 0,  9.0f),
    IRON_ORE    (true,  false, false, 52, 52, 52, 0.66f, 0.55f, 0.44f, 0, 11.0f),
    GOLD_ORE    (true,  false, false, 53, 53, 53, 0.86f, 0.72f, 0.28f, 0, 11.0f),
    DIAMOND_ORE (true,  false, false, 54, 54, 54, 0.40f, 0.85f, 0.87f, 0, 13.0f),
    // Огонь: не твёрдый, свет почти как у факела, ломается мгновенно.
    FIRE        (false, true,  false, 55, 55, 55, 1.00f, 0.55f, 0.10f, 14, 0.0f),
    /**
     * Снежный покров: не твёрдый, поэтому сквозь него ходят, а высота слоя
     * живёт в meta (0..7). Твёрдым делать нельзя — коллизия в движке
     * кубическая, и слой в 1/8 блока стал бы ступенькой в полный блок.
     */
    SNOW_LAYER  (false, true,  false, 56, 56, 56, 0.92f, 0.95f, 0.99f, 0,  0.1f),
    /**
     * Сундук. Твёрдый, как и положено мебели; содержимое живёт не в блоке, а
     * в чанке ({@code Chunk.getChest}) — в id блока помещается один байт, а
     * в сундук двадцать семь стопок.
     */
    CHEST       (true,  false, false, 76, 77, 76, 0.55f, 0.40f, 0.22f, 0,  2.5f),
    /**
     * Печь. Лицевая грань отличается от боков тайлом, но сторона света не
     * хранится: поворот потребовал бы meta и пересборки меша при повороте,
     * а печь и так опознаётся по жерлу с любой стороны обзора.
     */
    FURNACE     (true,  false, false, 79, 81, 81, 0.42f, 0.38f, 0.35f, 0,  6.0f),
    /**
     * Лёд: замёрзшая вода тундры. Твёрдый и скользкий; разбитый, снова
     * становится водой — иначе замёрзшее озеро превращалось бы в яму.
     */
    ICE         (true,  false, false, 86, 86, 86, 0.70f, 0.84f, 0.97f, 0,  0.6f),
    // Собственные тайлы: продвинутые блоки больше не маскируются под землю,
    // камень, доски и стекло.
    MUD         (true,  false, false, 89, 89, 89, 0.24f, 0.17f, 0.12f, 0,  0.8f),
    ASH         (true,  false, false, 90, 90, 90, 0.20f, 0.20f, 0.21f, 0,  0.2f),
    MOSSY_COBBLE(true,  false, false, 91, 91, 91, 0.28f, 0.42f, 0.25f, 0,  6.5f),
    LAVA        (false, true,  false, 92, 114, 92, 1.00f, 0.27f, 0.03f, 15, 0f),
    OBSIDIAN    (true,  false, false, 93, 93, 93, 0.12f, 0.08f, 0.18f, 0, 24f),
    THIN_ICE    (true,  true,  false, 94, 94, 94, 0.76f, 0.90f, 1.00f, 0, 0.18f),
    ROPE        (false, true,  true,  95, 95, 95, 0.54f, 0.37f, 0.18f, 0, 0.15f),
    CHAIN       (false, true,  true,  96, 96, 96, 0.48f, 0.51f, 0.55f, 0, 1.2f),
    WEB         (false, true,  true,  97, 97, 97, 0.82f, 0.84f, 0.86f, 0, 0.1f),
    JOURNAL     (false, true,  true,  98, 98, 98, 0.50f, 0.31f, 0.17f, 0, 0.05f),
    /**
     * Спальник: пропускает ночь и переносит точку возрождения.
     *
     * Один блок, а не два, как кровать в MC: вторая половина существует там
     * ради рисунка, а не ради механики, зато тянет за собой мету поворота,
     * согласованную постановку, согласованную ломку и поворот текстуры в
     * мешере. Механика «переспать» ровно та же.
     *
     * Не твёрдый и низкий: по нему ходят, как по снежному слою, и рисуется
     * он тем же {@code emitLayer} — высота живёт в meta.
     */
    BEDROLL     (false, true,  false, 88, 87, 87, 0.62f, 0.24f, 0.22f, 0,  0.3f),
    /** Верстак: открывает полноценную сетку крафта 3×3. */
    CRAFTING_TABLE(true, false, false, 99, 100, 11, 0.58f, 0.40f, 0.22f, 0, 2.5f),
    // Append only: block ordinals are part of the save format.
    PODZOL      (true, false, false, 2, 106, 2, 0.36f, 0.25f, 0.14f, 0, 0.6f),
    PEAT        (true, false, false, 107, 107, 107, 0.24f, 0.22f, 0.13f, 0, 0.7f),
    DRY_GRASS   (true, false, false, 2, 108, 2, 0.65f, 0.58f, 0.28f, 0, 0.6f),
    RED_SAND    (true, false, false, 109, 109, 109, 0.71f, 0.35f, 0.17f, 0, 0.5f),
    TERRACOTTA  (true, false, false, 110, 110, 110, 0.62f, 0.31f, 0.21f, 0, 6f),
    LIMESTONE   (true, false, false, 111, 111, 111, 0.77f, 0.75f, 0.65f, 0, 5f),
    BASALT      (true, false, false, 112, 112, 112, 0.25f, 0.27f, 0.29f, 0, 9f),
    GRAVEL      (true, false, false, 113, 113, 113, 0.48f, 0.46f, 0.42f, 0, 0.6f);

    public final boolean solid;
    public final boolean transparent;
    public final boolean cutout;
    public final int sideTile, topTile, bottomTile;
    public final float[] particleColor;
    public final int emittedLight;
    public final float hardness;

    BlockType(boolean solid, boolean transparent, boolean cutout,
            int side, int top, int bottom,
            float pr, float pg, float pb, int emittedLight, float hardness) {
        this.solid = solid;
        this.transparent = transparent;
        this.cutout = cutout;
        this.sideTile = side;
        this.topTile = top;
        this.bottomTile = bottom;
        this.particleColor = new float[] { pr, pg, pb };
        this.emittedLight = emittedLight;
        this.hardness = hardness;
    }

    public static final BlockType[] VALUES = values();

    /**
     * Рисуется двумя пересекающимися плоскостями, а не кубом.
     * Отдельным полем делать не стали: это свойство модели, а не блока, и
     * ради двух значений пришлось бы править конструктор у всех констант.
     */
    public boolean isCross() {
        return this == TORCH || this == FIRE || this == ROPE || this == CHAIN
                || this == WEB || this == JOURNAL;
    }

    /**
     * Каким инструментом блок положено копать. Влияет только на скорость —
     * что вообще поддаётся, решает {@link #requiredToolLevel()}.
     */
    public ToolClass preferredTool() {
        return switch (this) {
            case STONE, COBBLE, MOSSY_COBBLE, COAL_ORE, IRON_ORE, GOLD_ORE, DIAMOND_ORE,
                    BEDROCK, ICE, THIN_ICE, OBSIDIAN, CHAIN, TERRACOTTA, LIMESTONE, BASALT
                    -> ToolClass.PICKAXE;
            case WOOD, PLANKS, STAIRS, DOOR_CLOSED, DOOR_OPEN, LEAVES, ROPE, CRAFTING_TABLE
                    -> ToolClass.AXE;
            case DIRT, GRASS, SAND, SNOWY_GRASS, SNOW_LAYER, MUD, ASH,
                    PODZOL, PEAT, DRY_GRASS, RED_SAND, GRAVEL -> ToolClass.SHOVEL;
            default -> null;
        };
    }

    /**
     * Минимальный уровень инструмента, при котором блок вообще даёт дроп.
     * Ноль — берётся руками. Это и есть вертикаль прогресса: дерево пускает
     * к камню, камень к железу, железо к алмазу.
     */
    public int requiredToolLevel() {
        return switch (this) {
            case STONE, COBBLE, MOSSY_COBBLE, COAL_ORE, TERRACOTTA, LIMESTONE, BASALT -> 1;
            case IRON_ORE -> 2;
            case GOLD_ORE, DIAMOND_ORE, OBSIDIAN -> 3;
            default -> 0;
        };
    }

    /** Высота блока задаётся meta, а не единичным кубом. */
    public boolean isLayered() {
        return this == SNOW_LAYER || this == BEDROLL;
    }

    /**
     * Насколько блок скользкий под ногами: доля обычного сцепления с землёй.
     * Единица — обычный грунт; лёд держит вчетверо хуже, и разгон и торможение
     * на нём растягиваются в скольжение.
     */
    public float grip() {
        return this == ICE || this == THIN_ICE ? 0.22f : this == MUD || this == PEAT ? 0.58f : 1f;
    }

    /** Loose materials need a block below them, including when placed by the player. */
    public boolean hasGravity() {
        return this == SAND || this == RED_SAND || this == GRAVEL || this == ASH;
    }

    public boolean isSoil() {
        return this == DIRT || this == GRASS || this == SNOWY_GRASS || this == PODZOL
                || this == DRY_GRASS || this == PEAT || this == MUD;
    }

    public float walkSpeedMultiplier() {
        return this == PEAT ? 0.72f : this == MUD ? 0.80f : this == ASH ? 0.90f : 1f;
    }

    /** Горит и может быть съеден огнём. */
    public boolean isFlammable() {
        return this == WOOD || this == PLANKS || this == LEAVES || this == ROPE
                || this == JOURNAL || this == CRAFTING_TABLE;
    }

    public static BlockType byId(byte id) {
        int i = id & 0xFF;
        // Out-of-range ids only occur from a corrupt/foreign/truncated chunk file.
        // Treat unknown blocks as AIR instead of throwing AIOOBE mid-mesh.
        return i < VALUES.length ? VALUES[i] : AIR;
    }

    /** What this block yields when broken in survival. AIR = no drop. */
    public BlockType getDrop() {
        return switch (this) {
            case STONE -> COBBLE;
            case GRASS, SNOWY_GRASS, PODZOL, DRY_GRASS -> DIRT;
            case LEAVES, WATER, WATER_FLOW, LAVA, AIR, DOOR_OPEN, FIRE, SNOW_LAYER,
                    ICE, THIN_ICE, WEB, JOURNAL -> AIR;
            default -> this;
        };
    }
}
