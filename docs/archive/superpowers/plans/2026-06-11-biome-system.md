Status: superseded

Historical design/implementation record; current scope and status live in docs/ROADMAP_1_1.md. This status does not claim every old checkbox was completed.

# Biome System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Система биомов в духе Minecraft: PLAINS / FOREST / DESERT / TUNDRA / OCEAN, влияющие на высоту рельефа, блоки поверхности и растительность.

**Architecture:** Новый `BiomeProvider` (3 низкочастотных Perlin-шума: континентальность/температура/влажность → таблица климата) выбирает `Biome` — enum с параметрами генерации. `World.generate()` сглаживает высотные параметры усреднением по сетке 5×5 сэмплов с шагом 4 блока и выбирает поверхность/деревья по биому. Два новых блока (`SNOWY_GRASS`, `CACTUS`) с процедурно сгенерированными спрайтами.

**Tech Stack:** Java 17+, без зависимостей. Тесты — самописный раннер `src/test/java/com/mineclone/TestMain.java` (plain main + asserts, БЕЗ JUnit), запуск `.\run-tests.ps1`. Спека: `docs/superpowers/specs/2026-06-11-biome-system-design.md`.

**Важные факты о кодовой базе:**
- Чанки 16×128×16. `World.SEA_LEVEL = 50`. Генерация в `World.generate()` двухпроходная: рельеф, затем деревья.
- `BlockType` — enum, id блока = ordinal, сериализуется в сейв байтом → новые блоки добавлять ТОЛЬКО в конец.
- `TextureAtlas.TILE_NAMES` — реестр тайлов, позиция в массиве = индекс тайла. Сейчас занято 0–46. Спрайты — PNG в `assets/textures/blocks/`, при отсутствии файла рисуется magenta-placeholder (игра не падает).
- Атлас кэшируется в `assets/atlas.png` — после добавления тайлов запускать `.\run.ps1 --regen-atlas`.
- Звуки: `assets/sounds/step/snow1..4.ogg`, `dig/snow1..4.ogg`, `step/cloth1..4.ogg`, `dig/cloth1..4.ogg` уже существуют. `Sounds` грузит файлы по префиксу = имя Material в lower-case автоматически.
- Тесты запускаются из корня репозитория, компиляция main+test через `run-tests.ps1`, exit code ≠ 0 при провале.

---

### Task 1: Новые блоки SNOWY_GRASS и CACTUS + спрайты

**Files:**
- Modify: `src/main/java/com/mineclone/world/BlockType.java`
- Modify: `src/main/java/com/mineclone/render/TextureAtlas.java:53-83` (массив TILE_NAMES)
- Create: `tools/GenBiomeSprites.java`
- Test: `src/test/java/com/mineclone/TestMain.java`

- [ ] **Step 1: Написать падающий тест на новые блоки**

В `TestMain.java` добавить в `main()` после строки `run("BlockType.byId guards out-of-range ids", ...)`:

```java
run("new biome blocks registered", TestMain::testBiomeBlocks);
```

И метод рядом с `testByIdGuard`:

```java
private static void testBiomeBlocks() {
    // Новые блоки добавлены В КОНЕЦ enum — старые ordinal не сдвинуты (иначе ломаются сейвы).
    assertEq("WATER_FLOW ordinal stays 16", 16, BlockType.WATER_FLOW.ordinal());
    BlockType sg = BlockType.valueOf("SNOWY_GRASS");
    BlockType ca = BlockType.valueOf("CACTUS");
    assertTrue("SNOWY_GRASS solid", sg.solid);
    assertTrue("CACTUS solid", ca.solid);
    assertTrue("SNOWY_GRASS byId round-trip", BlockType.byId((byte) sg.ordinal()) == sg);
    assertTrue("CACTUS byId round-trip", BlockType.byId((byte) ca.ordinal()) == ca);
}
```

- [ ] **Step 2: Запустить тесты, убедиться что новый тест падает**

Run: `.\run-tests.ps1`
Expected: компиляция падает с `cannot find symbol: SNOWY_GRASS` (это и есть «красный» этап — блоков ещё нет).

- [ ] **Step 3: Добавить блоки в BlockType**

В `BlockType.java` после строки `WATER_FLOW  (false, true,  false,  8,  8,  8, 0.16f, 0.35f, 0.78f, 0,  0f);` заменить `;` на `,` и добавить:

```java
    SNOWY_GRASS (true,  false, false, 48, 47,  2, 0.92f, 0.94f, 0.96f, 0,  0.6f),
    CACTUS      (true,  false, false, 49, 50, 50, 0.20f, 0.55f, 0.25f, 0,  0.4f);
```

(Сигнатура конструктора: solid, transparent, cutout, sideTile, topTile, bottomTile, particleR, particleG, particleB, emittedLight, hardness. Низ SNOWY_GRASS — тайл 2 = dirt.)

- [ ] **Step 4: Зарегистрировать тайлы в TextureAtlas**

В `TextureAtlas.java` в конец массива `TILE_NAMES` (после строки `"crack_5", "crack_6", "crack_7", "crack_8", "crack_9",`) добавить:

```java
            "snowy_grass_top",  // 47
            "snowy_grass_side", // 48
            "cactus_side",      // 49
            "cactus_top",       // 50
```

- [ ] **Step 5: Создать генератор спрайтов**

Create `tools/GenBiomeSprites.java`:

```java
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * One-off generator for biome block sprites (32x32 PNG) in assets/textures/blocks.
 * Run from the repo root:  java tools\GenBiomeSprites.java
 */
public class GenBiomeSprites {
    static final int S = 32;

    public static void main(String[] args) throws Exception {
        File dir = new File("assets/textures/blocks");
        if (!dir.isDirectory())
            throw new IllegalStateException("run from repo root, missing " + dir.getAbsolutePath());
        Random r = new Random(42);

        // --- snowy_grass_top: white snow with subtle noise ---
        BufferedImage top = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int v = 235 + r.nextInt(21);
                top.setRGB(x, y, rgb(v, v, Math.min(255, v + 4)));
            }
        ImageIO.write(top, "png", new File(dir, "snowy_grass_top.png"));

        // --- snowy_grass_side: grass_side base, top 8 px replaced with a ragged snow cap ---
        BufferedImage grassSide = ImageIO.read(new File(dir, "grass_side.png"));
        BufferedImage side = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        side.getGraphics().drawImage(grassSide, 0, 0, S, S, null);
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < S; x++) {
                boolean ragged = y == 7 && r.nextInt(3) == 0; // uneven bottom edge of the cap
                if (!ragged) {
                    int v = 230 + r.nextInt(26);
                    side.setRGB(x, y, rgb(v, v, Math.min(255, v + 4)));
                }
            }
        ImageIO.write(side, "png", new File(dir, "snowy_grass_side.png"));

        // --- cactus_side: green column with darker vertical ribs ---
        BufferedImage cs = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                boolean rib = (x % 8) < 2;
                int g = rib ? 90 + r.nextInt(15) : 130 + r.nextInt(25);
                cs.setRGB(x, y, rgb(20, g, 30));
            }
        for (int i = 0; i < S; i++) {
            cs.setRGB(0, i, rgb(15, 74, 24));
            cs.setRGB(S - 1, i, rgb(15, 74, 24));
        }
        ImageIO.write(cs, "png", new File(dir, "cactus_side.png"));

        // --- cactus_top: lighter green with a dark border ---
        BufferedImage ct = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                boolean border = x < 2 || y < 2 || x >= S - 2 || y >= S - 2;
                int g = border ? 80 : 150 + r.nextInt(20);
                ct.setRGB(x, y, rgb(20, g, 30));
            }
        ImageIO.write(ct, "png", new File(dir, "cactus_top.png"));

        System.out.println("OK: 4 sprites written to " + dir.getPath());
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
```

- [ ] **Step 6: Запустить генератор спрайтов**

Run: `java tools\GenBiomeSprites.java`
Expected: `OK: 4 sprites written to assets\textures\blocks`. Проверить: `Get-ChildItem assets\textures\blocks -Filter "snowy*"` показывает 2 файла, `-Filter "cactus*"` ещё 2.

- [ ] **Step 7: Запустить тесты, убедиться что проходят**

Run: `.\run-tests.ps1`
Expected: все тесты PASS, включая `new biome blocks registered`.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/mineclone/world/BlockType.java src/main/java/com/mineclone/render/TextureAtlas.java tools/GenBiomeSprites.java assets/textures/blocks/snowy_grass_top.png assets/textures/blocks/snowy_grass_side.png assets/textures/blocks/cactus_side.png assets/textures/blocks/cactus_top.png src/test/java/com/mineclone/TestMain.java
git commit -m "feat(world): add SNOWY_GRASS and CACTUS blocks with generated sprites"
```

---

### Task 2: Enum Biome с параметрами генерации

**Files:**
- Create: `src/main/java/com/mineclone/world/Biome.java`
- Test: `src/test/java/com/mineclone/TestMain.java`

- [ ] **Step 1: Написать падающий тест на параметры биомов**

В `TestMain.java` добавить в `main()`:

```java
run("biome params sane", TestMain::testBiomeParams);
```

Импорт сверху: `import com.mineclone.world.Biome;`

Метод:

```java
private static void testBiomeParams() {
    assertEq("5 biomes", 5, Biome.values().length);
    assertTrue("ocean floor below sea level", Biome.OCEAN.baseHeight < World.SEA_LEVEL);
    assertTrue("tundra surface is snowy grass", Biome.TUNDRA.surfaceBlock == BlockType.SNOWY_GRASS);
    assertTrue("desert surface is sand", Biome.DESERT.surfaceBlock == BlockType.SAND);
    assertTrue("forest denser than plains", Biome.FOREST.treesPer128 > Biome.PLAINS.treesPer128);
    assertTrue("ocean has no trees", Biome.OCEAN.treeType == Biome.TreeType.NONE);
    for (Biome b : Biome.values())
        assertTrue(b + " amplitude in (0,1]", b.amplitude > 0 && b.amplitude <= 1.0);
}
```

- [ ] **Step 2: Запустить тесты, убедиться что компиляция падает**

Run: `.\run-tests.ps1`
Expected: `cannot find symbol: class Biome`.

- [ ] **Step 3: Создать Biome.java**

```java
package com.mineclone.world;

/**
 * World-generation parameters per biome. Selected by {@link BiomeProvider}
 * from climate noise; never persisted (recomputed from the seed on demand).
 */
public enum Biome {
    //       baseHeight             amplitude  surface                 filler           trees/128  treeType
    PLAINS  (World.SEA_LEVEL + 6,   0.4,       BlockType.GRASS,        BlockType.DIRT,  1,         TreeType.OAK),
    FOREST  (World.SEA_LEVEL + 6,   0.7,       BlockType.GRASS,        BlockType.DIRT,  8,         TreeType.OAK),
    DESERT  (World.SEA_LEVEL + 6,   0.5,       BlockType.SAND,         BlockType.SAND,  2,         TreeType.CACTUS),
    TUNDRA  (World.SEA_LEVEL + 7,   0.8,       BlockType.SNOWY_GRASS,  BlockType.DIRT,  3,         TreeType.SPRUCE),
    OCEAN   (World.SEA_LEVEL - 18,  0.3,       BlockType.SAND,         BlockType.SAND,  0,         TreeType.NONE);

    public enum TreeType { OAK, SPRUCE, CACTUS, NONE }

    /** Base surface height in blocks (offset already applied to SEA_LEVEL). */
    public final int baseHeight;
    /** Multiplier for the terrain noise amplitude (1.0 = full 22-block swing). */
    public final double amplitude;
    public final BlockType surfaceBlock;
    /** Block used for the 4 layers under the surface (above stone). */
    public final BlockType fillerBlock;
    /** Vegetation density: a column spawns a tree when (hash & 0x7F) < treesPer128. */
    public final int treesPer128;
    public final TreeType treeType;

    Biome(int baseHeight, double amplitude, BlockType surfaceBlock, BlockType fillerBlock,
            int treesPer128, TreeType treeType) {
        this.baseHeight = baseHeight;
        this.amplitude = amplitude;
        this.surfaceBlock = surfaceBlock;
        this.fillerBlock = fillerBlock;
        this.treesPer128 = treesPer128;
        this.treeType = treeType;
    }
}
```

- [ ] **Step 4: Запустить тесты, убедиться что проходят**

Run: `.\run-tests.ps1`
Expected: PASS все, включая `biome params sane`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/mineclone/world/Biome.java src/test/java/com/mineclone/TestMain.java
git commit -m "feat(world): Biome enum with terrain/surface/vegetation params"
```

---

### Task 3: BiomeProvider — климатические шумы и таблица климата

**Files:**
- Create: `src/main/java/com/mineclone/world/BiomeProvider.java`
- Test: `src/test/java/com/mineclone/TestMain.java`

- [ ] **Step 1: Написать падающие тесты**

В `TestMain.java` добавить в `main()`:

```java
run("biome climate table", TestMain::testBiomeClassify);
run("biome provider deterministic", TestMain::testBiomeDeterminism);
run("biome provider covers all biomes", TestMain::testBiomeCoverage);
```

Импорт: `import com.mineclone.world.BiomeProvider;`

Методы:

```java
private static void testBiomeClassify() {
    // Аргументы: континентальность, температура, влажность (значения шума ~[-0.7, 0.7]).
    assertTrue("deep negative cont -> OCEAN",
            BiomeProvider.classify(-0.9, 0, 0) == Biome.OCEAN);
    assertTrue("ocean wins over cold",
            BiomeProvider.classify(-0.9, -0.9, 0) == Biome.OCEAN);
    assertTrue("cold -> TUNDRA",
            BiomeProvider.classify(0.5, -0.9, 0) == Biome.TUNDRA);
    assertTrue("hot+dry -> DESERT",
            BiomeProvider.classify(0.5, 0.9, -0.9) == Biome.DESERT);
    assertTrue("wet -> FOREST",
            BiomeProvider.classify(0.5, 0.0, 0.9) == Biome.FOREST);
    assertTrue("temperate default -> PLAINS",
            BiomeProvider.classify(0.5, 0.0, 0.0) == Biome.PLAINS);
}

private static void testBiomeDeterminism() {
    BiomeProvider a = new BiomeProvider(777L);
    BiomeProvider b = new BiomeProvider(777L);
    BiomeProvider c = new BiomeProvider(778L);
    boolean anyDiff = false;
    for (int x = -1000; x <= 1000; x += 67)
        for (int z = -1000; z <= 1000; z += 67) {
            assertTrue("same seed same biome @" + x + "," + z,
                    a.biomeAt(x, z) == b.biomeAt(x, z));
            if (a.biomeAt(x, z) != c.biomeAt(x, z))
                anyDiff = true;
        }
    assertTrue("different seeds differ somewhere", anyDiff);
}

private static void testBiomeCoverage() {
    // Все 5 биомов должны встречаться в разумной окрестности спавна —
    // это гейт на пороги классификации (слишком жёсткий порог = биом не существует).
    BiomeProvider p = new BiomeProvider(12345L);
    java.util.EnumSet<Biome> seen = java.util.EnumSet.noneOf(Biome.class);
    for (int x = -4000; x <= 4000; x += 32)
        for (int z = -4000; z <= 4000; z += 32)
            seen.add(p.biomeAt(x, z));
    assertEq("all five biomes occur within 4000 blocks", 5, seen.size());
}
```

- [ ] **Step 2: Запустить тесты, убедиться что компиляция падает**

Run: `.\run-tests.ps1`
Expected: `cannot find symbol: class BiomeProvider`.

- [ ] **Step 3: Создать BiomeProvider.java**

```java
package com.mineclone.world;

/**
 * Deterministic biome source. Three low-frequency climate noises (derived
 * from the world seed) feed a Whittaker-style climate table. Biomes are
 * regions hundreds of blocks across; nothing is persisted.
 */
public class BiomeProvider {
    // Climate noise frequency per block — much lower than terrain noise (0.012),
    // so biome regions span hundreds of blocks.
    static final double CONT_FREQ = 0.0020;
    static final double TEMP_FREQ = 0.0018;
    static final double HUM_FREQ  = 0.0025;

    // Climate thresholds. fbm output is roughly [-0.7, 0.7]; tune these if a
    // biome is too rare/common (testBiomeCoverage gates existence of all five).
    static final double C_OCEAN = -0.25;
    static final double T_COLD  = -0.22;
    static final double T_HOT   =  0.18;
    static final double H_DRY   =  0.05;
    static final double H_WET   =  0.18;

    /** World-coordinate step of the height-smoothing sample grid. */
    public static final int GRID_STEP = 4;

    private final PerlinNoise continentalness;
    private final PerlinNoise temperature;
    private final PerlinNoise humidity;

    public BiomeProvider(long seed) {
        // Distinct derived seeds so climate fields are independent of terrain noise.
        this.continentalness = new PerlinNoise(seed ^ 0xC0FFEE5EED15BADL);
        this.temperature = new PerlinNoise(seed ^ 0x7E39A21B4C8D55E1L);
        this.humidity = new PerlinNoise(seed ^ 0x2545F4914F6CDD1DL);
    }

    /** Pure climate table; public for tests. Order matters: ocean wins, then cold. */
    public static Biome classify(double cont, double temp, double hum) {
        if (cont < C_OCEAN)
            return Biome.OCEAN;
        if (temp < T_COLD)
            return Biome.TUNDRA;
        if (temp > T_HOT && hum < H_DRY)
            return Biome.DESERT;
        if (hum > H_WET)
            return Biome.FOREST;
        return Biome.PLAINS;
    }

    public Biome biomeAt(int wx, int wz) {
        double cont = continentalness.fbm(wx * CONT_FREQ, wz * CONT_FREQ, 3, 2.0, 0.5);
        double temp = temperature.fbm(wx * TEMP_FREQ, wz * TEMP_FREQ, 3, 2.0, 0.5);
        double hum = humidity.fbm(wx * HUM_FREQ, wz * HUM_FREQ, 3, 2.0, 0.5);
        return classify(cont, temp, hum);
    }

    /**
     * Biome at a smoothing-grid point. Grid coords = floorDiv(world, GRID_STEP).
     * World.generate() samples biomes on this grid (4x4-block quantisation,
     * like Minecraft's biome resolution) and averages height params over a
     * 5x5 window to smooth biome borders.
     */
    public Biome biomeAtGrid(int gx, int gz) {
        return biomeAt(gx * GRID_STEP, gz * GRID_STEP);
    }
}
```

- [ ] **Step 4: Запустить тесты**

Run: `.\run-tests.ps1`
Expected: PASS все. Если `biome provider covers all biomes` падает — подвигать пороги `C_OCEAN`/`T_COLD`/`T_HOT`/`H_DRY`/`H_WET` (см. комментарий в коде: fbm даёт примерно [-0.7, 0.7], порог ближе к 0 = биом чаще) и перезапустить. Менять только пороги, не тест.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/mineclone/world/BiomeProvider.java src/test/java/com/mineclone/TestMain.java
git commit -m "feat(world): BiomeProvider with climate noise and Whittaker table"
```

---

### Task 4: Биомный рельеф и поверхность в World.generate() (проход 1)

**Files:**
- Modify: `src/main/java/com/mineclone/world/World.java` (конструктор + generate, строки ~14-81)
- Test: `src/test/java/com/mineclone/TestMain.java`

- [ ] **Step 1: Написать падающие тесты**

В `TestMain.java` добавить в `main()`:

```java
run("chunk surface matches biome", TestMain::testChunkSurfaceMatchesBiome);
run("biome borders have no cliffs", TestMain::testHeightSmoothness);
```

Импорты: `import com.mineclone.world.Chunk;` (Biome/BiomeProvider уже импортированы).

Вспомогательный метод + тесты:

```java
/** Y верхнего блока рельефа: сканируем сверху, пропуская воздух/воду/растительность. */
private static int surfaceY(Chunk c, int x, int z) {
    for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
        BlockType t = c.get(x, y, z);
        if (t == BlockType.AIR || t == BlockType.WATER || t == BlockType.WATER_FLOW
                || t == BlockType.LEAVES || t == BlockType.WOOD || t == BlockType.CACTUS)
            continue;
        return y;
    }
    return 0;
}

private static void testChunkSurfaceMatchesBiome() {
    long seed = 4242L;
    World w = new World(seed);
    BiomeProvider bp = new BiomeProvider(seed);
    for (int cx = -2; cx <= 2; cx++)
        for (int cz = -2; cz <= 2; cz++) {
            Chunk c = w.getChunk(cx, cz);
            for (int x = 0; x < Chunk.SIZE_X; x++)
                for (int z = 0; z < Chunk.SIZE_Z; z++) {
                    int wx = cx * Chunk.SIZE_X + x, wz = cz * Chunk.SIZE_Z + z;
                    int y = surfaceY(c, x, z);
                    Biome b = bp.biomeAtGrid(Math.floorDiv(wx, BiomeProvider.GRID_STEP),
                            Math.floorDiv(wz, BiomeProvider.GRID_STEP));
                    BlockType expected = (y <= World.SEA_LEVEL + 1) ? BlockType.SAND : b.surfaceBlock;
                    BlockType actual = c.get(x, y, z);
                    assertTrue("surface @" + wx + "," + wz + " biome=" + b
                            + " expected=" + expected + " got=" + actual, actual == expected);
                }
        }
}

private static void testHeightSmoothness() {
    // Усреднение 5x5 должно исключать обрывы на границах биомов: соседние
    // колонки различаются не более чем на 4 блока.
    long seed = 991L;
    World w = new World(seed);
    int prev = Integer.MIN_VALUE;
    for (int wx = -160; wx < 160; wx++) {
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        Chunk c = w.getChunk(cx, 0);
        int y = surfaceY(c, Math.floorMod(wx, Chunk.SIZE_X), 7);
        if (prev != Integer.MIN_VALUE)
            assertTrue("step at wx=" + wx + ": " + prev + " -> " + y,
                    Math.abs(y - prev) <= 4);
        prev = y;
    }
}
```

- [ ] **Step 2: Запустить тесты, убедиться что surface-тест падает**

Run: `.\run-tests.ps1`
Expected: компилируется, `chunk surface matches biome` — FAIL (генератор ещё не знает о биомах: в тундре лежит GRASS вместо SNOWY_GRASS и т.д.). `biome borders have no cliffs` может проходить (рельеф пока однородный) — это нормально.

- [ ] **Step 3: Подключить BiomeProvider к World и переписать проход 1**

В `World.java` добавить поле и инициализацию (после `private final PerlinNoise detailNoise;`):

```java
    public final BiomeProvider biomes;
```

В конструкторе после `this.detailNoise = ...`:

```java
        this.biomes = new BiomeProvider(seed);
```

В `generate()` заменить проход 1 целиком (от `int[][] heights = ...` до закрывающей скобки первого двойного цикла включительно) на:

```java
        int[][] heights = new int[Chunk.SIZE_X][Chunk.SIZE_Z];

        // Biome sample grid: chunk columns quantised to 4x4 blocks plus a
        // ±8-block margin so height params can be averaged over a 5x5 window.
        // Grid index = floorDiv(world, GRID_STEP); gx0 is the chunk's first
        // column's grid index minus the 2-cell window margin.
        final int G = 8; // 4 cells across the chunk + 2 margin cells each side
        int gx0 = cx * 4 - 2, gz0 = cz * 4 - 2;
        Biome[][] grid = new Biome[G][G];
        for (int gx = 0; gx < G; gx++)
            for (int gz = 0; gz < G; gz++)
                grid[gx][gz] = biomes.biomeAtGrid(gx0 + gx, gz0 + gz);

        // Pass 1: terrain only — no trees yet so leaves are never overwritten by later
        // columns.
        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int z = 0; z < Chunk.SIZE_Z; z++) {
                int wx = cx * Chunk.SIZE_X + x;
                int wz = cz * Chunk.SIZE_Z + z;

                // Smooth base height/amplitude over the 5x5 grid window so
                // biome borders slope instead of forming cliffs.
                int gi = x / 4 + 2, gj = z / 4 + 2;
                double base = 0, amp = 0;
                for (int ox = -2; ox <= 2; ox++)
                    for (int oz = -2; oz <= 2; oz++) {
                        Biome b = grid[gi + ox][gj + oz];
                        base += b.baseHeight;
                        amp += b.amplitude;
                    }
                base /= 25.0;
                amp /= 25.0;

                double n = heightNoise.fbm(wx * 0.012, wz * 0.012, 5, 2.0, 0.5);
                double d = detailNoise.fbm(wx * 0.05, wz * 0.05, 3, 2.0, 0.5);
                int height = (int) (base + n * 22 * amp + d * 4);
                height = Math.max(2, Math.min(Chunk.SIZE_Y - 4, height));
                heights[x][z] = height;

                // Point biome (4x4 quantised) picks the surface blocks; the
                // beach rule overrides every biome at the waterline.
                Biome biome = grid[gi][gj];
                boolean beach = height <= SEA_LEVEL + 1;
                BlockType surface = beach ? BlockType.SAND : biome.surfaceBlock;
                BlockType filler = beach ? BlockType.SAND : biome.fillerBlock;

                for (int y = 0; y < Chunk.SIZE_Y; y++) {
                    BlockType t;
                    if (y == 0)
                        t = BlockType.BEDROCK;
                    else if (y < height - 4)
                        t = BlockType.STONE;
                    else if (y < height)
                        t = filler;
                    else if (y == height)
                        t = surface;
                    else if (y <= SEA_LEVEL)
                        t = BlockType.WATER;
                    else
                        t = BlockType.AIR;
                    c.set(x, y, z, t);
                }
            }
        }
```

В проходе 2 (деревья) строка `if (c.get(x, height, z) != BlockType.GRASS)` пока остаётся как есть — деревья перерабатываются в Task 5.

- [ ] **Step 4: Запустить тесты**

Run: `.\run-tests.ps1`
Expected: PASS все, включая оба новых. Если `biome borders have no cliffs` падает с шагом 5-6 — проверить, что усреднение действительно делит на 25 и что окно window использует grid (типовая ошибка: использовать точечный biome для высоты).

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/mineclone/world/World.java src/test/java/com/mineclone/TestMain.java
git commit -m "feat(world): biome-driven terrain height and surface blocks"
```

---

### Task 5: Растительность по биомам (проход 2): дубы, ели, кактусы

**Files:**
- Modify: `src/main/java/com/mineclone/world/World.java` (проход 2 в generate, строки ~83-112)
- Test: `src/test/java/com/mineclone/TestMain.java`

- [ ] **Step 1: Написать падающий тест-инвариант**

В `TestMain.java` добавить в `main()`:

```java
run("vegetation matches biome rules", TestMain::testVegetationInvariants);
```

Метод:

```java
private static void testVegetationInvariants() {
    long seed = 31337L;
    World w = new World(seed);
    boolean sawCactus = false, sawTrunk = false;
    for (int cx = -6; cx <= 6; cx++)
        for (int cz = -6; cz <= 6; cz++) {
            Chunk c = w.getChunk(cx, cz);
            for (int x = 0; x < Chunk.SIZE_X; x++)
                for (int z = 0; z < Chunk.SIZE_Z; z++)
                    for (int y = 1; y < Chunk.SIZE_Y; y++) {
                        BlockType t = c.get(x, y, z);
                        BlockType below = c.get(x, y - 1, z);
                        if (t == BlockType.CACTUS) {
                            sawCactus = true;
                            assertTrue("cactus on sand/cactus @" + x + "," + y + "," + z,
                                    below == BlockType.SAND || below == BlockType.CACTUS);
                            assertTrue("cactus above water line", y > World.SEA_LEVEL + 1);
                        }
                        if (t == BlockType.WOOD && below != BlockType.WOOD) {
                            sawTrunk = true;
                            assertTrue("trunk base on grass/snowy grass, got " + below,
                                    below == BlockType.GRASS || below == BlockType.SNOWY_GRASS);
                        }
                    }
        }
    // 13x13 чанков (208x208 блоков) обязаны содержать хоть какую-то растительность.
    assertTrue("saw at least one trunk", sawTrunk);
    assertTrue("saw at least one cactus (desert exists near spawn for this seed)", sawCactus);
}
```

- [ ] **Step 2: Запустить тесты, убедиться что падает**

Run: `.\run-tests.ps1`
Expected: `vegetation matches biome rules` — FAIL: либо `saw at least one cactus` (кактусов ещё нет), либо `trunk base on grass...` (старый код сажает дубы только на GRASS — в тундре теперь SNOWY_GRASS, поэтому там нет ни дубов, ни елей). Любой из этих провалов — корректный «красный».

Если падает только из-за `saw at least one cactus` / отсутствия пустыни на этом сиде — после реализации Step 3 тест сам найдёт кактусы; если нет, подобрать другой seed в тесте (это допустимая правка теста, зафиксировать в коммите).

- [ ] **Step 3: Переписать проход 2 в World.generate()**

Заменить весь блок «Pass 2: trees» (от комментария до конца внешнего двойного цикла) на:

```java
        // Pass 2: vegetation — all terrain exists so leaves land correctly.
        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int z = 0; z < Chunk.SIZE_Z; z++) {
                Biome biome = grid[x / 4 + 2][z / 4 + 2];
                if (biome.treeType == Biome.TreeType.NONE)
                    continue;
                int height = heights[x][z];
                if (height <= SEA_LEVEL + 1) // never plant on beaches / under water
                    continue;
                if (c.get(x, height, z) != biome.surfaceBlock)
                    continue;
                int wx = cx * Chunk.SIZE_X + x;
                int wz = cz * Chunk.SIZE_Z + z;
                long h = mix(wx, wz, seed);
                // Keep whole canopies inside the chunk: 2-block margin.
                if ((h & 0x7F) >= biome.treesPer128 || x < 2 || x >= Chunk.SIZE_X - 2
                        || z < 2 || z >= Chunk.SIZE_Z - 2)
                    continue;
                switch (biome.treeType) {
                    case OAK -> placeOak(c, x, height, z, h);
                    case SPRUCE -> placeSpruce(c, x, height, z, h);
                    case CACTUS -> placeCactus(c, x, height, z, h);
                    case NONE -> { }
                }
            }
        }
```

И добавить три приватных метода в `World` (после `generate()`):

```java
    /** Classic oak: 4-7 trunk, blobby canopy over the top 3 levels. */
    private static void placeOak(Chunk c, int x, int height, int z, long h) {
        int th = 4 + (int) ((h >>> 7) & 0x3);
        int top = height + th;
        if (top + 2 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= th; i++)
            c.set(x, height + i, z, BlockType.WOOD);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= 2; dy++) {
                    int lx = x + dx, ly = top - 2 + dy, lz = z + dz;
                    int rad = (dy == 2) ? 1 : 2;
                    if (Math.abs(dx) + Math.abs(dz) <= rad + 1
                            && c.inBounds(lx, ly, lz)
                            && c.get(lx, ly, lz) == BlockType.AIR) {
                        c.set(lx, ly, lz, BlockType.LEAVES);
                    }
                }
    }

    /** Spruce: 5-8 trunk, conical rings of leaves, single-leaf tip. */
    private static void placeSpruce(Chunk c, int x, int height, int z, long h) {
        int th = 5 + (int) ((h >>> 7) & 0x3);
        int top = height + th;
        if (top + 2 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= th; i++)
            c.set(x, height + i, z, BlockType.WOOD);
        int[] radii = { 2, 1, 2, 1, 1 }; // bottom to top, ending at the trunk top
        for (int level = 0; level < radii.length; level++) {
            int ly = top - (radii.length - 1) + level;
            int rad = radii[level];
            for (int dx = -rad; dx <= rad; dx++)
                for (int dz = -rad; dz <= rad; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > rad)
                        continue; // diamond ring — conical silhouette
                    int lx = x + dx, lz = z + dz;
                    if (c.inBounds(lx, ly, lz) && c.get(lx, ly, lz) == BlockType.AIR)
                        c.set(lx, ly, lz, BlockType.LEAVES);
                }
        }
        c.set(x, top + 1, z, BlockType.LEAVES);
    }

    /** Cactus: 1-3 column straight up from the sand. */
    private static void placeCactus(Chunk c, int x, int height, int z, long h) {
        int ch = 1 + (int) ((h >>> 7) % 3);
        if (height + ch + 1 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= ch; i++)
            c.set(x, height + i, z, BlockType.CACTUS);
    }
```

- [ ] **Step 4: Запустить тесты**

Run: `.\run-tests.ps1`
Expected: PASS все. Если `saw at least one cactus` падает — у сида 31337 рядом со спавном нет пустыни; перебрать сиды (31337 → 1001, 555, 777...) в тесте, пока в радиусе 6 чанков не будет и леса/равнин, и пустыни. Зафиксировать выбранный сид.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/mineclone/world/World.java src/test/java/com/mineclone/TestMain.java
git commit -m "feat(world): per-biome vegetation - oaks, spruces, cacti"
```

---

### Task 6: Звуки для новых блоков

**Files:**
- Modify: `src/main/java/com/mineclone/audio/Sounds.java:18-20, 64-76`
- Test: `src/test/java/com/mineclone/TestMain.java`

- [ ] **Step 1: Написать падающий тест**

В `TestMain.java` добавить в `main()`:

```java
run("sound materials for biome blocks", TestMain::testBiomeSoundMaterials);
```

Импорт: `import com.mineclone.audio.Sounds;`

Метод:

```java
private static void testBiomeSoundMaterials() {
    Sounds s = new Sounds();
    assertEq("snowy grass -> SNOW", Sounds.Material.valueOf("SNOW"),
            s.materialOf(BlockType.SNOWY_GRASS));
    assertEq("cactus -> CLOTH", Sounds.Material.valueOf("CLOTH"),
            s.materialOf(BlockType.CACTUS));
}
```

- [ ] **Step 2: Запустить тесты, убедиться что падает**

Run: `.\run-tests.ps1`
Expected: FAIL — `No enum constant ...Material.SNOW` (материалов ещё нет).

- [ ] **Step 3: Добавить материалы и маппинг**

В `Sounds.java` строка 19, расширить enum:

```java
    public enum Material {
        GRASS, STONE, SAND, WOOD, GRAVEL, GLASS, SNOW, CLOTH, NONE
    }
```

(Файлы `step/snow1..4.ogg`, `dig/snow1..4.ogg`, `step/cloth1..4.ogg`, `dig/cloth1..4.ogg` уже лежат в assets — конструктор подхватит их автоматически по префиксу имени материала.)

В `materialOf()` перед `default -> Material.NONE;` добавить:

```java
            case SNOWY_GRASS -> Material.SNOW;
            case CACTUS -> Material.CLOTH;
```

- [ ] **Step 4: Запустить тесты**

Run: `.\run-tests.ps1`
Expected: PASS все.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/mineclone/audio/Sounds.java src/test/java/com/mineclone/TestMain.java
git commit -m "feat(audio): snow and cloth sound materials for biome blocks"
```

---

### Task 7: Биом в F3 debug-оверлее

**Files:**
- Modify: `src/main/java/com/mineclone/game/Hud.java:97-113` (drawDebug)
- Modify: `src/main/java/com/mineclone/game/Game.java:~1672` (вызов drawDebug)

Юнит-тестов нет (рендер-код вне тестового контура) — проверка визуальная в Task 8.

- [ ] **Step 1: Добавить параметр biome в Hud.drawDebug**

Сигнатуру строки 97-99 заменить на:

```java
    public void drawDebug(int screenW, int screenH, int fps, Vector3f pos,
            int chunkX, int chunkZ, int loadedChunks, int drawnChunks,
            BlockType target, byte targetMeta, boolean wireframe, int skyLight, int blockLight,
            String biome) {
```

В массив `lines` после строки `"Chunk: " + ...` добавить:

```java
                "Biome: " + biome,
```

- [ ] **Step 2: Передать биом из Game**

В `Game.java` найти вызов `hud.drawDebug(...)` (~строка 1672). Перед ним уже вычислены `bx`, `bz` (блочные координаты игрока). Заменить вызов на:

```java
                    hud.drawDebug(vw, vh, fpsCurrent, player.position, pcx, pcz,
                            countLoadedChunks(), drawnChunks, tgt, tgtMeta, wireframe, skyL, blkL,
                            world.biomes.biomeAt(bx, bz).name());
```

- [ ] **Step 3: Скомпилировать (главный код, без запуска)**

Run: `.\run-tests.ps1` (компилирует и main, и test)
Expected: компиляция OK, все тесты PASS.

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/mineclone/game/Hud.java src/main/java/com/mineclone/game/Game.java
git commit -m "feat(hud): show current biome in F3 debug overlay"
```

---

### Task 8: Ручная проверка, документация, ADR

**Files:**
- Modify: `CLAUDE.md` (таблица tuning knobs)
- Create: `knowledge/decisions/biome-system.md`

- [ ] **Step 1: Перегенерировать атлас и запустить игру**

Run: `.\run.ps1 --regen-atlas`

Чек-лист визуальной проверки (создать новый мир, летать в creative/спринтом):
1. F3 показывает строку `Biome: ...`, значение меняется при перемещении.
2. Найти каждый из 5 биомов (тундра — белая трава и ели; пустыня — песок и кактусы; лес — плотные дубы; равнины — редкие дубы; океан — большая вода).
3. Границы биомов: высота меняется склоном, без отвесных стен.
4. Кактусы стоят только на песке, ели — конусные, у тундровой травы снежная шапка сбоку и земля снизу.
5. Шаги по снежной траве звучат снегом (приглушённый хруст), ломание кактуса — cloth-звук.
6. Спрайты не magenta (placeholder = опечатка в имени тайла/файла).

Если биомы слишком мелкие/крупные — крутить `*_FREQ` в `BiomeProvider`; слишком редкие — пороги. После любой правки перезапускать `.\run-tests.ps1` (testBiomeCoverage — гейт).

- [ ] **Step 2: Обновить CLAUDE.md**

В таблицу «Key constants & tuning knobs» добавить строки:

```markdown
| `BiomeProvider` | `CONT/TEMP/HUM_FREQ` | Biome region size |
| `BiomeProvider` | `C_OCEAN, T_COLD, T_HOT, H_DRY, H_WET` | Biome rarity thresholds |
| `Biome` | `baseHeight / amplitude / treesPer128` | Per-biome terrain & vegetation |
```

И в раздел «World & chunks» после строки про two-pass добавить:

```markdown
- Biomes: `BiomeProvider` (3 climate noises -> Whittaker table, 4x4-block quantisation, 5x5 height-param smoothing). Biomes are never persisted — recomputed from the seed.
```

- [ ] **Step 3: Написать ADR**

Create `knowledge/decisions/biome-system.md`:

```markdown
# ADR: Система биомов — климатические шумы + сглаживание параметров

Дата: 2026-06-11
Статус: принято
Спека: docs/superpowers/specs/2026-06-11-biome-system-design.md

## Решение

Биом точки мира = чистая функция сида: три низкочастотных Perlin-шума
(континентальность, температура, влажность) -> пороговая таблица климата
(`BiomeProvider.classify`). Биом нигде не сохраняется.

## Почему так

- **Не Voronoi-ячейки:** климатическая таблица даёт осмысленные соседства
  (тундра не граничит с пустыней) и большие естественные регионы.
- **Не сохранение биома в чанк:** функция от сида детерминирована, формат
  сейва не меняется, старые миры продолжают работать (новые чанки в них
  генерируются уже с биомами — допустим шов, как в самом Minecraft).
- **Сглаживание высот через усреднение параметров** (baseHeight/amplitude
  по окну 5x5 сэмплов с шагом 4 блока), а не через интерполяцию самих высот:
  дёшево (64 climate-сэмпла на чанк) и гарантирует склоны вместо обрывов
  (тест `biome borders have no cliffs`).
- **Биом квантован 4x4 блока** (как в Minecraft) — выбор блоков поверхности
  использует сэмпл сетки, а не поблочный шум: меньше вызовов шума, границы
  поверхности выглядят естественно рваными.
- **Отдельные блоки вместо тонировки** (SNOWY_GRASS вместо покраски GRASS):
  ноль изменений в мешере/шейдерах/формате вершин. Цена — новые id блоков
  (добавлены строго в конец enum: id = ordinal живёт в сейвах).

## Ограничения v1

Лёд, снежный слой-покров, цветы, берёзы, болота, реки — вне скоупа.
Кактус — полный куб (без вреза и урона).
```

- [ ] **Step 4: Прогнать тесты финально и закоммитить**

Run: `.\run-tests.ps1`
Expected: все PASS.

```powershell
git add CLAUDE.md knowledge/decisions/biome-system.md
git commit -m "docs: biome system ADR and tuning knobs"
```

---

## Самопроверка покрытия спеки

| Требование спеки | Задача |
|---|---|
| BiomeProvider: 3 шума, таблица климата, пороги | Task 3 |
| Biome enum с параметрами | Task 2 |
| Сглаживание высот 5×5 шаг 4, кэш сэмплов на чанк | Task 4 (grid 8×8 на чанк) |
| Поверхность по биому + правило пляжа | Task 4 |
| Деревья: дуб/ель/кактус, плотность по биому | Task 5 |
| Блоки SNOWY_GRASS, CACTUS + тайлы 47-50 + спрайты | Task 1 |
| Звуки snow/cloth | Task 6 |
| Совместимость сейвов (блоки в конец, биом не хранится) | Task 1 (тест ordinal), Task 8 (ADR) |
| F3: строка Biome | Task 7 |
| Тесты: детерминизм, таблица, гладкость, поверхность | Tasks 3, 4, 5 |
| Ручная проверка + тюнинг | Task 8 |
