Status: superseded

Historical design/implementation record; current scope and status live in docs/ROADMAP_1_1.md. This status does not claim every old checkbox was completed.

# Survival Foundation A — Items, Drops, Inventory — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Превратить креативную песочницу в основу survival-игры: стекаемые предметы, дроп блоков в инвентарь, режимы Creative/Survival и переработанное отображение инвентаря с числами стеков.

**Architecture:** Чистая, тестируемая логика (`ItemStack`, `Inventory`, drop-таблица) живёт в `com.mineclone.world` (пакет без зависимостей от GL). `Game` хранит `Inventory` и `GameMode`, в SURVIVAL расходует/добывает блоки. `Hud` рисует числа стеков и делегирует логику кликов (взять/положить/слить/разделить) в `Inventory`. Сохранение бампит формат до v6 с обратной совместимостью.

**Tech Stack:** Java 17 (records, switch-expr), LWJGL/JOML (только в UI-слое), самописный тест-раннер (`TestMain`, plain main + asserts), сборка через `run.ps1` / `run-tests.ps1`.

**Соглашения, зафиксированные из спеки:**
- Пустой слот инвентаря = `null` (НЕ `BlockType.AIR`).
- `MAX_STACK = 64`.
- `ItemStack.type` — всегда непустой `BlockType` (не `AIR`, не `null`).
- Стартовый инвентарь: SURVIVAL — пустой; CREATIVE — текущий набор блоков.

---

## Карта файлов

**Новые:**
- `src/main/java/com/mineclone/world/ItemStack.java` — пара (BlockType, count), мутабельна.
- `src/main/java/com/mineclone/world/Inventory.java` — обёртка `ItemStack[36]`, логика add/remove/click.
- `src/main/java/com/mineclone/world/GameMode.java` — enum CREATIVE/SURVIVAL.

**Изменяемые:**
- `src/main/java/com/mineclone/world/BlockType.java` — метод `getDrop()`.
- `src/main/java/com/mineclone/save/LevelData.java` — поля `ItemStack[] inventory` → через `Inventory`-данные + `GameMode gameMode`.
- `src/main/java/com/mineclone/save/SaveFormat.java` — `LEVEL_VERSION = 6`.
- `src/main/java/com/mineclone/save/SaveManager.java` — сериализация stacks + gameMode (v6), чтение старого формата.
- `src/main/java/com/mineclone/game/Game.java` — `Inventory inventory`, `GameMode gameMode`; place/break consume/produce; маршрут `E`; `/gamemode`; запрет полёта.
- `src/main/java/com/mineclone/game/Hud.java` — числа стеков в `drawHotbar`/`drawInventory`; клики-стеки; разделение survival/creative.
- `src/test/java/com/mineclone/TestMain.java` — новые проверки + правка `testLevelRoundTrip`.

**Команды сборки/тестов (Windows PowerShell):**
- Тесты: `.\run-tests.ps1` → ожидается `==== N passed, 0 failed ====`, exit 0.
- Компиляция без запуска: блок из `CLAUDE.md` (javac в `out/`).
- Ручной прогон: `.\run.ps1`.

---

## Task 1: ItemStack

**Files:**
- Create: `src/main/java/com/mineclone/world/ItemStack.java`
- Test: `src/test/java/com/mineclone/TestMain.java` (новый чек + регистрация в `main`)

- [ ] **Step 1: Написать падающий тест**

В `TestMain.java` добавить метод и зарегистрировать его в `main()` (рядом с прочими `run(...)`):

```java
// в main(), после строки про "BlockType.byId guards out-of-range ids":
run("ItemStack clamps and stacks", TestMain::testItemStack);
```

```java
private static void testItemStack() {
    ItemStack s = new ItemStack(BlockType.STONE, 1);
    assertEq("type", BlockType.STONE, s.type);
    assertEq("count", 1, s.count);
    assertTrue("isFull false at 1", !s.isFull());

    s.count = ItemStack.MAX_STACK;
    assertTrue("isFull true at MAX", s.isFull());

    // add returns leftover that didn't fit
    ItemStack t = new ItemStack(BlockType.DIRT, 60);
    int left = t.addUpTo(10); // 60 + 10 = 70 -> capped 64, leftover 6
    assertEq("count capped", ItemStack.MAX_STACK, t.count);
    assertEq("leftover", 6, left);

    ItemStack copy = t.copy();
    assertTrue("copy distinct", copy != t);
    assertEq("copy type", BlockType.DIRT, copy.type);
    assertEq("copy count", t.count, copy.count);
}
```

Добавить импорт в `TestMain.java`: `import com.mineclone.world.ItemStack;`

- [ ] **Step 2: Запустить тест — убедиться, что не компилируется/падает**

Run: `.\run-tests.ps1`
Expected: ошибка компиляции «cannot find symbol: class ItemStack» (тест ещё не может собраться).

- [ ] **Step 3: Реализовать ItemStack**

```java
package com.mineclone.world;

/** A stack of identical items. Items are blocks for now (tools come later).
 *  {@code type} is always a real block (never AIR). {@code count} is 1..MAX_STACK. */
public final class ItemStack {
    public static final int MAX_STACK = 64;

    public BlockType type;
    public int count;

    public ItemStack(BlockType type, int count) {
        this.type = type;
        this.count = Math.max(1, Math.min(MAX_STACK, count));
    }

    public boolean isFull() {
        return count >= MAX_STACK;
    }

    /** Adds up to {@code amount} items, capped at MAX_STACK.
     *  @return the leftover that did not fit. */
    public int addUpTo(int amount) {
        int space = MAX_STACK - count;
        int added = Math.max(0, Math.min(space, amount));
        count += added;
        return amount - added;
    }

    public ItemStack copy() {
        return new ItemStack(type, count);
    }
}
```

- [ ] **Step 4: Запустить тест — убедиться, что проходит**

Run: `.\run-tests.ps1`
Expected: строка `[PASS] ItemStack clamps and stacks`, итог `0 failed`, exit 0.

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/mineclone/world/ItemStack.java src/test/java/com/mineclone/TestMain.java
git commit -m "feat(items): ItemStack with stacking and clamp"
```

---

## Task 2: Inventory (add / removeOne / click semantics)

**Files:**
- Create: `src/main/java/com/mineclone/world/Inventory.java`
- Test: `src/test/java/com/mineclone/TestMain.java`

**Контракт слотов:** массив `ItemStack[36]`, индексы `0..8` — хотбар, `9..35` — основной. Пустой слот = `null`.

- [ ] **Step 1: Написать падающие тесты**

Зарегистрировать в `main()`:

```java
run("Inventory add merges then fills", TestMain::testInventoryAdd);
run("Inventory removeOne empties slot", TestMain::testInventoryRemoveOne);
run("Inventory left/right click stack ops", TestMain::testInventoryClick);
```

```java
private static void testInventoryAdd() {
    Inventory inv = new Inventory();
    int left = inv.add(BlockType.STONE, 10);
    assertEq("no leftover", 0, left);
    assertEq("slot0 count", 10, inv.get(0).count);

    // merges into the same existing stack first
    inv.add(BlockType.STONE, 5);
    assertEq("merged into slot0", 15, inv.get(0).count);
    assertTrue("slot1 still empty", inv.get(1) == null);

    // overflow spills into the next free slot
    inv.add(BlockType.STONE, 60); // 15 + 60 = 75 -> 64 in slot0, 11 in next free
    assertEq("slot0 full", 64, inv.get(0).count);
    assertEq("spill slot count", 11, inv.get(1).count);

    // full inventory returns leftover
    Inventory full = new Inventory();
    for (int i = 0; i < 36; i++) full.set(i, new ItemStack(BlockType.DIRT, 64));
    int rem = full.add(BlockType.DIRT, 5);
    assertEq("leftover when full", 5, rem);
}

private static void testInventoryRemoveOne() {
    Inventory inv = new Inventory();
    inv.set(3, new ItemStack(BlockType.WOOD, 2));
    inv.removeOne(3);
    assertEq("count decremented", 1, inv.get(3).count);
    inv.removeOne(3);
    assertTrue("slot emptied at 0", inv.get(3) == null);
}

private static void testInventoryClick() {
    Inventory inv = new Inventory();
    inv.set(0, new ItemStack(BlockType.STONE, 10));

    // Left-click empty cursor on a stack: pick it all up
    ItemStack cursor = inv.leftClick(0, null);
    assertEq("cursor took all", 10, cursor.count);
    assertTrue("slot now empty", inv.get(0) == null);

    // Left-click full cursor on empty slot: drop it all
    cursor = inv.leftClick(0, cursor);
    assertTrue("cursor cleared", cursor == null);
    assertEq("slot got 10", 10, inv.get(0).count);

    // Right-click empty cursor on a stack: take half (ceil)
    cursor = inv.rightClick(0, null); // 10 -> cursor 5, slot 5
    assertEq("cursor half", 5, cursor.count);
    assertEq("slot half", 5, inv.get(0).count);

    // Right-click holding same type on same type: deposit one
    cursor = inv.rightClick(0, cursor); // slot 5 -> 6, cursor 5 -> 4
    assertEq("slot +1", 6, inv.get(0).count);
    assertEq("cursor -1", 4, cursor.count);

    // Left-click same type merges up to max with remainder on cursor
    inv.set(0, new ItemStack(BlockType.STONE, 60));
    cursor = new ItemStack(BlockType.STONE, 10);
    cursor = inv.leftClick(0, cursor); // 60+10 -> slot 64, cursor 6
    assertEq("slot merged to max", 64, inv.get(0).count);
    assertEq("cursor remainder", 6, cursor.count);

    // Left-click different type swaps
    inv.set(1, new ItemStack(BlockType.DIRT, 3));
    cursor = new ItemStack(BlockType.WOOD, 2);
    cursor = inv.leftClick(1, cursor);
    assertEq("slot took wood", BlockType.WOOD, inv.get(1).type);
    assertEq("cursor took dirt", BlockType.DIRT, cursor.type);
}
```

Импорт: `import com.mineclone.world.Inventory;`

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `.\run-tests.ps1`
Expected: ошибка компиляции «cannot find symbol: class Inventory».

- [ ] **Step 3: Реализовать Inventory**

```java
package com.mineclone.world;

/** Player inventory: 36 slots (0..8 hotbar, 9..35 main). Empty slot = null.
 *  Pure logic, no rendering — unit-testable. */
public final class Inventory {
    public static final int SIZE = 36;
    public static final int HOTBAR = 9;

    private final ItemStack[] slots = new ItemStack[SIZE];

    public ItemStack get(int i) { return slots[i]; }
    public void set(int i, ItemStack s) { slots[i] = s; }
    public int size() { return SIZE; }

    /** Adds items, merging into matching stacks first, then into empty slots.
     *  @return leftover that did not fit. */
    public int add(BlockType type, int amount) {
        if (type == null || type == BlockType.AIR || amount <= 0) return amount;
        // pass 1: top up existing stacks of this type
        for (int i = 0; i < SIZE && amount > 0; i++) {
            ItemStack s = slots[i];
            if (s != null && s.type == type && !s.isFull())
                amount = s.addUpTo(amount);
        }
        // pass 2: fill empty slots
        for (int i = 0; i < SIZE && amount > 0; i++) {
            if (slots[i] == null) {
                int put = Math.min(ItemStack.MAX_STACK, amount);
                slots[i] = new ItemStack(type, put);
                amount -= put;
            }
        }
        return amount;
    }

    /** Decrements the stack in {@code slot} by one; clears the slot at zero. */
    public void removeOne(int slot) {
        ItemStack s = slots[slot];
        if (s == null) return;
        if (--s.count <= 0) slots[slot] = null;
    }

    /** Left-click interaction. Returns the new cursor stack (may be null). */
    public ItemStack leftClick(int slot, ItemStack cursor) {
        ItemStack s = slots[slot];
        if (cursor == null) {        // pick up the whole slot
            slots[slot] = null;
            return s;
        }
        if (s == null) {             // drop the whole cursor
            slots[slot] = cursor;
            return null;
        }
        if (s.type == cursor.type) { // merge, remainder stays on cursor
            int leftover = s.addUpTo(cursor.count);
            if (leftover == 0) return null;
            cursor.count = leftover;
            return cursor;
        }
        // different types: swap
        slots[slot] = cursor;
        return s;
    }

    /** Right-click interaction. Returns the new cursor stack (may be null). */
    public ItemStack rightClick(int slot, ItemStack cursor) {
        ItemStack s = slots[slot];
        if (cursor == null) {        // take half (ceil) onto cursor
            if (s == null) return null;
            int half = (s.count + 1) / 2;
            ItemStack taken = new ItemStack(s.type, half);
            s.count -= half;
            if (s.count <= 0) slots[slot] = null;
            return taken;
        }
        if (s == null) {             // deposit one into empty slot
            slots[slot] = new ItemStack(cursor.type, 1);
            if (--cursor.count <= 0) return null;
            return cursor;
        }
        if (s.type == cursor.type && !s.isFull()) { // deposit one onto same type
            s.count++;
            if (--cursor.count <= 0) return null;
            return cursor;
        }
        if (s.type != cursor.type) { // different types: swap
            slots[slot] = cursor;
            return s;
        }
        return cursor;               // same type but full: no-op
    }

    /** True when the selected slot has something placeable. */
    public boolean hasItem(int slot) {
        return slots[slot] != null;
    }
}
```

- [ ] **Step 4: Запустить — убедиться, что проходит**

Run: `.\run-tests.ps1`
Expected: 3 новые строки `[PASS]`, `0 failed`, exit 0.

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/mineclone/world/Inventory.java src/test/java/com/mineclone/TestMain.java
git commit -m "feat(items): Inventory with add/remove and click stack ops"
```

---

## Task 3: GameMode enum

**Files:**
- Create: `src/main/java/com/mineclone/world/GameMode.java`

- [ ] **Step 1: Реализовать enum (тривиальный, без отдельного теста — покрывается save round-trip в Task 6)**

```java
package com.mineclone.world;

/** World play mode. CREATIVE = infinite blocks + flight; SURVIVAL = drops/stacks. */
public enum GameMode {
    CREATIVE,
    SURVIVAL;

    public static GameMode byOrdinalSafe(int i) {
        GameMode[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : CREATIVE;
    }
}
```

- [ ] **Step 2: Скомпилировать**

Run: блок компиляции из `CLAUDE.md` (javac в `out/`).
Expected: компиляция без ошибок (нет вывода/exit 0).

- [ ] **Step 3: Коммит**

```bash
git add src/main/java/com/mineclone/world/GameMode.java
git commit -m "feat(world): GameMode enum (creative/survival)"
```

---

## Task 4: Drop table on BlockType

**Files:**
- Modify: `src/main/java/com/mineclone/world/BlockType.java`
- Test: `src/test/java/com/mineclone/TestMain.java`

- [ ] **Step 1: Написать падающий тест**

Зарегистрировать в `main()`:

```java
run("BlockType drop table", TestMain::testDropTable);
```

```java
private static void testDropTable() {
    assertEq("stone drops cobble", BlockType.COBBLE, BlockType.STONE.getDrop());
    assertEq("grass drops dirt", BlockType.DIRT, BlockType.GRASS.getDrop());
    assertEq("snowy grass drops dirt", BlockType.DIRT, BlockType.SNOWY_GRASS.getDrop());
    assertEq("leaves drop nothing", BlockType.AIR, BlockType.LEAVES.getDrop());
    assertEq("water drops nothing", BlockType.AIR, BlockType.WATER.getDrop());
    assertEq("dirt drops itself", BlockType.DIRT, BlockType.DIRT.getDrop());
    assertEq("wood drops itself", BlockType.WOOD, BlockType.WOOD.getDrop());
}
```

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `.\run-tests.ps1`
Expected: ошибка компиляции «cannot find symbol: method getDrop()».

- [ ] **Step 3: Реализовать getDrop()**

В `BlockType.java`, перед закрывающей `}` enum (после метода `byId`), добавить:

```java
/** What this block yields when broken in survival. AIR = no drop.
 *  Implemented as a switch (not a field) to avoid enum init-order issues. */
public BlockType getDrop() {
    return switch (this) {
        case STONE -> COBBLE;
        case GRASS, SNOWY_GRASS -> DIRT;
        case LEAVES, WATER, WATER_FLOW, AIR, DOOR_OPEN, TORCH -> AIR;
        default -> this;
    };
}
```

Примечание: TORCH дропает AIR временно (нет предмета-факела как стека-источника света при установке — выносится в подсистему C/полировку). Прочие блоки дропают сами себя.

- [ ] **Step 4: Запустить — убедиться, что проходит**

Run: `.\run-tests.ps1`
Expected: `[PASS] BlockType drop table`, `0 failed`.

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/mineclone/world/BlockType.java src/test/java/com/mineclone/TestMain.java
git commit -m "feat(world): block drop table (stone->cobble, grass->dirt, etc)"
```

---

## Task 5: LevelData carries Inventory data + GameMode

**Files:**
- Modify: `src/main/java/com/mineclone/save/LevelData.java`
- Test: `src/test/java/com/mineclone/TestMain.java` (правка `testLevelRoundTrip`)

**Подход:** хранить инвентарь как `ItemStack[]` (длиной `Inventory.SIZE`, элементы могут быть `null`) и `GameMode`. Сохранить обратную совместимость существующих сигнатур: старые конструкторы получают дефолты (`GameMode.CREATIVE`, инвентарь по режиму). `defaultInventory()` остаётся для старого кода (вернёт массив `ItemStack[]`).

- [ ] **Step 1: Переписать LevelData**

Полностью заменить содержимое `LevelData.java`:

```java
package com.mineclone.save;

import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;

/** Everything stored in level.dat. Immutable. */
public final class LevelData {
    public final String name;
    public final long seed;
    public final double px, py, pz;
    public final double spawnX, spawnY, spawnZ;
    public final float yaw, pitch;
    public final float timeOfDay;
    public final int selectedSlot;
    /** 36 slots; null = empty. */
    public final ItemStack[] inventory;
    public final GameMode gameMode;
    /** Unix-millisecond timestamp of the last save; 0 for pre-v5 saves. */
    public final long lastPlayed;

    public LevelData(long seed, double px, double py, double pz,
                     float yaw, float pitch, float timeOfDay, int selectedSlot) {
        this(seed, px, py, pz, 8.5, 80.0, 8.5, yaw, pitch, timeOfDay, selectedSlot);
    }

    public LevelData(long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot) {
        this(seed, px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch, timeOfDay, selectedSlot,
                creativeInventory());
    }

    public LevelData(long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     ItemStack[] inventory) {
        this("", seed, px, py, pz, spawnX, spawnY, spawnZ,
             yaw, pitch, timeOfDay, selectedSlot, inventory);
    }

    public LevelData(String name, long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     ItemStack[] inventory) {
        this(name, seed, px, py, pz, spawnX, spawnY, spawnZ,
             yaw, pitch, timeOfDay, selectedSlot, inventory,
             GameMode.CREATIVE, System.currentTimeMillis());
    }

    public LevelData(String name, long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     ItemStack[] inventory, GameMode gameMode, long lastPlayed) {
        this.name = name != null ? name : "";
        this.seed = seed;
        this.px = px; this.py = py; this.pz = pz;
        this.spawnX = spawnX; this.spawnY = spawnY; this.spawnZ = spawnZ;
        this.yaw = yaw; this.pitch = pitch;
        this.timeOfDay = timeOfDay;
        this.selectedSlot = selectedSlot;
        this.inventory = normalizeInventory(inventory);
        this.gameMode = gameMode != null ? gameMode : GameMode.CREATIVE;
        this.lastPlayed = lastPlayed;
    }

    /** Empty inventory (survival start). */
    public static ItemStack[] emptyInventory() {
        return new ItemStack[com.mineclone.world.Inventory.SIZE];
    }

    /** Default creative hotbar set (sandbox convenience). */
    public static ItemStack[] creativeInventory() {
        ItemStack[] inv = emptyInventory();
        BlockType[] hotbar = {
                BlockType.STONE, BlockType.DIRT, BlockType.GRASS, BlockType.PLANKS,
                BlockType.GLASS, BlockType.DOOR_CLOSED, BlockType.STAIRS, BlockType.TORCH, BlockType.WATER
        };
        for (int i = 0; i < hotbar.length; i++)
            inv[i] = new ItemStack(hotbar[i], 1);
        return inv;
    }

    private static ItemStack[] normalizeInventory(ItemStack[] src) {
        ItemStack[] inv = emptyInventory();
        if (src == null) return inv;
        int n = Math.min(inv.length, src.length);
        for (int i = 0; i < n; i++) inv[i] = src[i]; // null preserved
        return inv;
    }
}
```

- [ ] **Step 2: Обновить testLevelRoundTrip под новый тип**

В `TestMain.java` заменить тело `testLevelRoundTrip()` на:

```java
private static void testLevelRoundTrip() throws Exception {
    SaveManager sm = freshManager();
    ItemStack[] inv = LevelData.creativeInventory();
    inv[0] = new ItemStack(BlockType.COBBLE, 17);
    LevelData in = new LevelData("Test World", 42L, 1.5, 2.5, 3.5,
            10.0, 20.0, 30.0, 0.1f, 0.2f, 0.3f, 4, inv,
            GameMode.SURVIVAL, 999L);
    sm.saveLevel("w1", in);
    LevelData out = sm.loadLevel("w1");
    assertTrue("loadLevel non-null", out != null);
    assertEq("seed", 42L, out.seed);
    assertEq("name", "Test World", out.name);
    assertEq("px", 1.5, out.px);
    assertEq("spawnY", 20.0, out.spawnY);
    assertEq("selectedSlot", 4, out.selectedSlot);
    assertEq("lastPlayed", 999L, out.lastPlayed);
    assertEq("gameMode", GameMode.SURVIVAL, out.gameMode);
    assertEq("inv[0] type", BlockType.COBBLE, out.inventory[0].type);
    assertEq("inv[0] count", 17, out.inventory[0].count);
}
```

Добавить импорты в `TestMain.java`: `import com.mineclone.world.GameMode;` (ItemStack уже импортирован в Task 1).

> Этот тест на этом шаге НЕ пройдёт — `SaveManager` ещё пишет старый формат. Он зелёный после Task 6. (Компиляция пройдёт, тест «красный» по значениям — ожидаемо.)

- [ ] **Step 3: Скомпилировать (ожидаемы ошибки в SaveManager и Game)**

Run: блок компиляции из `CLAUDE.md`.
Expected: ошибки в `SaveManager.java` (тип inventory) и `Game.java` — это нормально, чинятся в Task 6 и 7. На этом шаге НЕ коммитим, переходим к Task 6.

> Замечание для исполнителя: Tasks 5→6→7 — связанная тройка (типы LevelData/SaveManager/Game). Между ними проект не компилируется; коммит делаем после Task 6 (save-слой целостен) и после Task 7 (Game целостен). Это сознательное отступление от «коммит на каждом шаге» из-за инвазивной смены типа.

---

## Task 6: SaveManager / SaveFormat — v6 serialization

**Files:**
- Modify: `src/main/java/com/mineclone/save/SaveFormat.java` (LEVEL_VERSION → 6)
- Modify: `src/main/java/com/mineclone/save/SaveManager.java` (saveLevel, loadLevel, renameWorld)

- [ ] **Step 1: Бамп версии**

В `SaveFormat.java`:

```java
public static final int LEVEL_VERSION = 6;
```

- [ ] **Step 2: Переписать saveLevel (запись stacks + gameMode)**

Заменить тело лямбды в `saveLevel` (строки, пишущие inventory) так, чтобы итоговый метод был:

```java
public void saveLevel(String id, LevelData d) {
    try {
        writeGzipAtomic(levelFile(id), o -> {
            o.writeInt(SaveFormat.MAGIC);
            o.writeInt(SaveFormat.LEVEL_VERSION);
            o.writeUTF(d.name);
            o.writeLong(d.seed);
            o.writeLong(d.lastPlayed);
            o.writeDouble(d.px); o.writeDouble(d.py); o.writeDouble(d.pz);
            o.writeDouble(d.spawnX); o.writeDouble(d.spawnY); o.writeDouble(d.spawnZ);
            o.writeFloat(d.yaw); o.writeFloat(d.pitch);
            o.writeFloat(d.timeOfDay);
            o.writeInt(d.selectedSlot);
            o.writeInt(d.gameMode.ordinal());            // v6
            o.writeInt(d.inventory.length);
            for (com.mineclone.world.ItemStack s : d.inventory) {   // v6: type + count
                if (s == null) {
                    o.writeByte(com.mineclone.world.BlockType.AIR.ordinal());
                    o.writeShort(0);
                } else {
                    o.writeByte(s.type.ordinal());
                    o.writeShort(s.count);
                }
            }
        });
    } catch (IOException e) {
        System.err.println("saveLevel failed: " + e.getMessage());
    }
}
```

- [ ] **Step 3: Переписать loadLevel (чтение v6 + обратная совместимость)**

Заменить блок чтения inventory и финальный `return` в `loadLevel`:

```java
int slot = in.readInt();

com.mineclone.world.GameMode gameMode =
        (version >= 6) ? com.mineclone.world.GameMode.byOrdinalSafe(in.readInt())
                       : com.mineclone.world.GameMode.CREATIVE;

com.mineclone.world.ItemStack[] inventory = LevelData.emptyInventory();
if (version >= 6) {
    int n = Math.max(0, Math.min(256, in.readInt()));
    com.mineclone.world.ItemStack[] tmp =
            new com.mineclone.world.ItemStack[Math.max(com.mineclone.world.Inventory.SIZE, n)];
    for (int i = 0; i < n; i++) {
        int blockId = in.readUnsignedByte();
        int count = in.readShort();
        if (count > 0 && blockId > 0 && blockId < BlockType.VALUES.length)
            tmp[i] = new com.mineclone.world.ItemStack(BlockType.VALUES[blockId], count);
    }
    inventory = tmp;
} else if (version >= 3) {
    // old format: BlockType[] with implicit count = 1
    int n = Math.max(0, Math.min(128, in.readInt()));
    com.mineclone.world.ItemStack[] tmp =
            new com.mineclone.world.ItemStack[Math.max(com.mineclone.world.Inventory.SIZE, n)];
    for (int i = 0; i < n; i++) {
        int blockId = in.readUnsignedByte();
        if (blockId > 0 && blockId < BlockType.VALUES.length)
            tmp[i] = new com.mineclone.world.ItemStack(BlockType.VALUES[blockId], 1);
    }
    inventory = tmp;
}

return new LevelData(name, seed, px, py, pz, spawnX, spawnY, spawnZ,
        yaw, pitch, tod, slot, inventory, gameMode, lastPlayed);
```

Удалить прежний блок чтения inventory (`BlockType[] inventory = ...; if (version >= 3) {...}`) и прежний `return`.

- [ ] **Step 4: Починить renameWorld**

В `SaveManager.java` (около строки 247) вызов `new LevelData(...)` использует `d.inventory` (теперь `ItemStack[]`) и должен пробросить `gameMode`. Заменить на конструктор с полным набором:

```java
saveLevel(id, new LevelData(newName, d.seed,
        d.px, d.py, d.pz, d.spawnX, d.spawnY, d.spawnZ,
        d.yaw, d.pitch, d.timeOfDay, d.selectedSlot,
        d.inventory, d.gameMode, d.lastPlayed));
```

(Проверить точную сигнатуру вокруг строки 245-249 и подставить соответствующий конструктор `LevelData` с `gameMode`.)

- [ ] **Step 5: Запустить тесты — save round-trip зелёный**

Run: `.\run-tests.ps1`
Expected: `[PASS] level.dat save/load round-trip` (теперь с gameMode + count=17), все прочие save-тесты зелёные. `Game.java` ещё может не компилироваться — если `run-tests.ps1` собирает весь `src/main`, тесты не запустятся до Task 7. В этом случае выполнить Task 7, затем вернуться к этому шагу.

> Если `run-tests.ps1` компилирует всё `src/main/java`: этот шаг подтверждается только после Task 7. Зафиксировать здесь промежуточный коммит save-слоя нельзя без компилирующегося Game — поэтому коммит save-слоя делаем совместно после Task 7 (см. Task 7, Step «Коммит»).

- [ ] **Step 6: (если save-слой компилируется изолированно) — без коммита, идём в Task 7**

---

## Task 7: Game — Inventory + GameMode wiring

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java`

Заменяем поле `BlockType[] inventory` на `Inventory inventory` и добавляем `GameMode`. Все обращения `inventory[...]` / `currentBlock()` переводим на новые типы.

- [ ] **Step 1: Поля и импорты**

В шапке `Game.java` уже есть `import com.mineclone.world.*;` — `Inventory`, `ItemStack`, `GameMode` подтянутся. Заменить поле инвентаря (строка ~101):

```java
// было: private final BlockType[] inventory = com.mineclone.save.LevelData.defaultInventory();
private Inventory inventory = new Inventory();
private GameMode gameMode = GameMode.SURVIVAL;
```

И курсор (строка ~102):

```java
// было: private BlockType cursorItem = BlockType.AIR;
private ItemStack cursorItem = null;
```

`lastHeldBlock` (строка ~133): инициализатор `inventory[0]` больше не валиден. Заменить:

```java
private BlockType lastHeldBlock = BlockType.AIR;
```

- [ ] **Step 2: currentBlock()**

Заменить (строка ~165):

```java
private BlockType currentBlock() {
    ItemStack s = inventory.get(selectedSlot);
    return s == null ? BlockType.AIR : s.type;
}
```

- [ ] **Step 3: Загрузка/сохранение уровня**

Найти, где `LevelData` читается при заходе в мир (около строки 419, `selectedSlot = Math.floorMod(lvl.selectedSlot, 9)`), и где сохраняется (около строки 247, `gameTime, selectedSlot, inventory`).

При загрузке — наполнить `Inventory` и `gameMode` из `lvl`:

```java
selectedSlot = Math.floorMod(lvl.selectedSlot, 9);
gameMode = lvl.gameMode;
inventory = new Inventory();
for (int i = 0; i < com.mineclone.world.Inventory.SIZE && i < lvl.inventory.length; i++)
    inventory.set(i, lvl.inventory[i]);
```

При сохранении (строка ~247) собрать `ItemStack[]` из `Inventory`:

```java
// заменить аргумент inventory на снимок:
ItemStack[] invSnapshot = new ItemStack[com.mineclone.world.Inventory.SIZE];
for (int i = 0; i < invSnapshot.length; i++) {
    ItemStack s = inventory.get(i);
    invSnapshot[i] = s == null ? null : s.copy();
}
```

и передать в конструктор `LevelData` `invSnapshot` и `gameMode`. Подобрать точный конструктор `LevelData` (полный, с `gameMode`). Проверить точную форму вызова сохранения вокруг строк 244-250.

- [ ] **Step 4: startNewWorld — стартовый инвентарь по режиму**

Найти `startNewWorld` (упоминается в knowledge), где задаётся стартовое состояние. Новые миры — SURVIVAL с пустым инвентарём:

```java
gameMode = GameMode.SURVIVAL;
inventory = new Inventory(); // пустой
cursorItem = null;
selectedSlot = 0;
```

(Если в startNewWorld инвентарь раньше брался из `LevelData.defaultInventory()` — заменить на пустой `new Inventory()`.)

- [ ] **Step 5: respawn / cursorItem сбросы**

Строка ~430 `cursorItem = BlockType.AIR;` → `cursorItem = null;`. Проверить все присваивания `cursorItem` ниже (раздел рендера CREATIVE_MENU, Task 10 их трогает тоже).

- [ ] **Step 6: Скомпилировать всё, прогнать тесты**

Run: блок компиляции из `CLAUDE.md`, затем `.\run-tests.ps1`.
Expected: компиляция без ошибок; все тесты зелёные, включая обновлённый `testLevelRoundTrip` (Task 5/6). `0 failed`, exit 0.

> На этом шаге логика place/break ещё «креативная» (Task 8), а инвентарь рисуется по-старому (Task 10 чинит числа/палитру). Игра компилируется и запускается; стеки в UI могут пока не отображаться корректно — это ожидаемо до Task 10.

- [ ] **Step 7: Коммит (save-слой + Game wiring вместе)**

```bash
git add src/main/java/com/mineclone/save/LevelData.java \
        src/main/java/com/mineclone/save/SaveFormat.java \
        src/main/java/com/mineclone/save/SaveManager.java \
        src/main/java/com/mineclone/game/Game.java \
        src/test/java/com/mineclone/TestMain.java
git commit -m "feat(save): inventory as ItemStack[] + GameMode (level format v6)"
```

---

## Task 8: Survival place/break — consume & produce

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java` (handleInteraction, executeBlockBreak)

- [ ] **Step 1: Дроп в инвентарь при ломании (executeBlockBreak)**

В `executeBlockBreak` (строка ~1026), после `world.setBlock(x, y, z, BlockType.AIR);` добавить выдачу дропа только в SURVIVAL:

```java
world.setBlock(x, y, z, BlockType.AIR);
if (gameMode == GameMode.SURVIVAL) {
    BlockType drop = target.getDrop();
    if (drop != BlockType.AIR)
        inventory.add(drop, 1); // overflow (full inv) is discarded for now
}
```

- [ ] **Step 2: Расход блока при установке (handleInteraction, ветка place)**

В ветке правой кнопки (строка ~994), после успешной установки блока расходовать предмет в SURVIVAL. Дверь ставится в 2 блока, но расходуется 1 предмет двери.

Заменить блок установки (после `BlockType placing = currentBlock(); if (placing == null || placing == BlockType.AIR) return;`) так, чтобы после фактической установки вызвать расход. Конкретно — добавить флаг успеха и расход в конце:

```java
BlockType placing = currentBlock();
if (placing == null || placing == BlockType.AIR)
    return;
boolean placed = false;
byte meta = 0;
if (placing == BlockType.DOOR_CLOSED) {
    meta = facingFromCamera();
    if (world.getBlock(px, py + 1, pz) == BlockType.AIR
            && !playerOccupies(px, py + 1, pz)) {
        sound.playOneOfAt(sounds.place(placing), blockSoundPosition(px, py, pz),
                0.8f, 0.85f + 0.2f * (float) Math.random());
        world.setBlock(px, py, pz, BlockType.DOOR_CLOSED, meta);
        world.setBlock(px, py + 1, pz, BlockType.DOOR_CLOSED, (byte) (meta | 0x4));
        placed = true;
    }
} else {
    if (placing == BlockType.STAIRS)
        meta = stairFacingFromCamera();
    sound.playOneOfAt(sounds.place(placing), blockSoundPosition(px, py, pz),
            0.8f, 0.85f + 0.2f * (float) Math.random());
    world.setBlock(px, py, pz, placing, meta);
    placed = true;
}
if (placed && gameMode == GameMode.SURVIVAL)
    inventory.removeOne(selectedSlot);
```

(Заменяет существующий if/else блока установки; сохраняет прежнюю логику двери/лестницы.)

- [ ] **Step 3: Скомпилировать + ручная проверка**

Run: блок компиляции, затем `.\run.ps1`.
Manual: новый мир (SURVIVAL). Сломать камень → в хотбаре/инвентаре появляется COBBLE со счётом, увеличивается при ломании ещё. Поставить блок → счёт уменьшается, при 0 слот пустеет и блок больше не ставится. Сломать землю → DIRT; листья → ничего.

- [ ] **Step 4: Коммит**

```bash
git add src/main/java/com/mineclone/game/Game.java
git commit -m "feat(survival): break drops into inventory, place consumes stack"
```

---

## Task 9: Game — E routing, flight lock, /gamemode

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java`

- [ ] **Step 1: Запрет полёта в SURVIVAL**

В `Player.update` тоггл полёта по F находится в `Player`. Перехват проще в `Game`: после `player.update(...)` форсить `flying=false` в SURVIVAL. В `updatePlaying`, сразу после `handleHotbar();` (строка ~611) и до `player.update`, добавить:

```java
if (gameMode == GameMode.SURVIVAL && player.flying)
    player.flying = false;
```

(Команда `/fly` остаётся доступной как чит — она ставит `player.flying` уже после этой проверки в кадре консоли; этого достаточно: в SURVIVAL F-тоггл будет немедленно гаситься, но явная команда сработает на свой кадр. Для устойчивого чит-полёта в survival — отдельная полировка, вне A.)

- [ ] **Step 2: Маршрут клавиши E (survival inventory vs creative picker)**

`updatePlaying`, блок `if (input.keyPressed(GLFW.GLFW_KEY_E))` (строка ~581). Состояние одно (`CREATIVE_MENU`), но содержимое экрана выберем по `gameMode` в рендере (Task 10). Достаточно открыть тот же `State.CREATIVE_MENU`:

Оставить как есть (открытие `State.CREATIVE_MENU` по E). Различие creative/survival делает рендер в Task 10. Здесь — без изменений (документируем, что E открывает «инвентарь-экран», а его наполнение зависит от режима).

- [ ] **Step 3: Команда /gamemode**

В `executeCommand` (switch по `parts[0]`, строка ~1057) добавить case:

```java
case "/gamemode", "/gm" -> {
    if (parts.length >= 2) {
        String m = parts[1].toLowerCase();
        if (m.startsWith("c")) { gameMode = GameMode.CREATIVE; showCommandToast("Gamemode: Creative"); }
        else if (m.startsWith("s")) {
            gameMode = GameMode.SURVIVAL;
            player.flying = false;
            showCommandToast("Gamemode: Survival");
        } else showCommandToast("Usage: /gamemode <creative|survival>");
    } else showCommandToast("Usage: /gamemode <creative|survival>");
}
```

- [ ] **Step 4: Обновить /help**

Найти `showCommandHelp()` и дописать строку про `/gamemode` в список команд (формат — как у существующих строк помощи в этом методе).

- [ ] **Step 5: Скомпилировать + ручная проверка**

Run: блок компиляции, затем `.\run.ps1`.
Manual: в SURVIVAL F не включает полёт. `/gamemode creative` → F работает, полёт включается. `/gamemode survival` → полёт гаснет. `/help` показывает `/gamemode`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/mineclone/game/Game.java
git commit -m "feat(survival): /gamemode command and flight lock in survival"
```

---

## Task 10: Hud — stack counts + unified survival/creative inventory

**Files:**
- Modify: `src/main/java/com/mineclone/game/Hud.java`
- Modify: `src/main/java/com/mineclone/game/Game.java` (вызовы drawHotbar/drawInventory/drawCreativeMenu, обработка действий)

**Цель:** числа стеков на хотбаре и в панели; в SURVIVAL по E — панель инвентаря без бесконечной палитры с логикой кликов через `Inventory`; в CREATIVE по E — пикер блоков (как сейчас), клик кладёт бесконечный стек.

- [ ] **Step 1: Хелпер рисования числа стека**

В `Hud.java` добавить приватный метод (рядом с `drawItemIcon`):

```java
/** Draws a stack-count number at the bottom-right of a slot, when count > 1. */
private void drawCount(int sw, int sh, int count, float slotX, float slotY, float slotSize) {
    if (count <= 1) return;
    String s = Integer.valueOf(count).toString();
    float cw = font.textWidth(s);
    float tx = slotX + slotSize - cw - 2f;
    float ty = slotY + slotSize - 2f;
    text.drawShadowed(font, s, tx, ty, sw, sh, 1f, 1f, 1f);
}
```

- [ ] **Step 2: drawHotbar — принять Inventory и рисовать числа**

Изменить сигнатуру `drawHotbar` на приём `Inventory`:

```java
public void drawHotbar(int screenW, int screenH, com.mineclone.world.Inventory inv, int selected) {
    int n = 9;
    float slot = 52, pad = 4;
    float totalW = n * slot + (n - 1) * pad;
    float x0 = screenW / 2f - totalW / 2f;
    float y0 = screenH - slot - 16;

    ui.begin(screenW, screenH);
    ui.quad(x0 - 6, y0 - 6, totalW + 12, slot + 12, 0f, 0f, 0f, 0.45f);
    for (int i = 0; i < n; i++) {
        float x = x0 + i * (slot + pad);
        ui.quad(x, y0, slot, slot, 0.15f, 0.15f, 0.18f, 0.7f);
        com.mineclone.world.ItemStack s = inv.get(i);
        if (s != null) {
            int tile = (s.type == BlockType.GRASS) ? s.type.topTile : s.type.sideTile;
            float[] uv = TextureAtlas.uv(tile);
            float inset = 6;
            ui.texQuad(x + inset, y0 + inset, slot - 2 * inset, slot - 2 * inset,
                    atlas.getTextureId(), uv[0], uv[1], uv[2], uv[3], 1f, 1f, 1f, 1f);
        }
        if (i == selected) {
            float t = 3;
            ui.quad(x - t, y0 - t, slot + 2 * t, t, 1f, 1f, 1f, 0.95f);
            ui.quad(x - t, y0 + slot, slot + 2 * t, t, 1f, 1f, 1f, 0.95f);
            ui.quad(x - t, y0, t, slot, 1f, 1f, 1f, 0.95f);
            ui.quad(x + slot, y0, t, slot, 1f, 1f, 1f, 0.95f);
        }
    }
    ui.end();

    for (int i = 0; i < n; i++) {
        float x = x0 + i * (slot + pad);
        com.mineclone.world.ItemStack s = inv.get(i);
        if (s != null)
            drawCount(screenW, screenH, s.count, x + 6, y0 + 6, slot - 12);
    }
}
```

- [ ] **Step 3: Новый survival-инвентарь drawInventory(Inventory)**

Заменить публичную `drawInventory` (строка ~930) на версию без палитры, работающую c `Inventory` и `ItemStack` курсором, возвращающую клик-намерение. Ввести новый возвращаемый тип-намерение:

```java
/** Outcome of an inventory click: which slot and which button, or none. */
public static final class SlotClick {
    public final int slot;      // -1 = none / not a slot
    public final boolean right;
    public final boolean trash; // clicked the trash box
    private SlotClick(int slot, boolean right, boolean trash) {
        this.slot = slot; this.right = right; this.trash = trash;
    }
    public static SlotClick none()  { return new SlotClick(-1, false, false); }
    public static SlotClick at(int slot, boolean right) { return new SlotClick(slot, right, false); }
    public static SlotClick trash() { return new SlotClick(-1, false, true); }
}
```

```java
public SlotClick drawInventory(int w, int h, double mx, double my,
        boolean clicked, boolean rightClicked,
        com.mineclone.world.Inventory inv, int selectedSlot,
        com.mineclone.world.ItemStack cursor) {
    float slot = 42f, gap = 5f;
    float panelW = 9 * slot + 8 * gap + 52f;
    float panelH = 360f;
    float panelX = w / 2f - panelW / 2f;
    float panelY = h / 2f - panelH / 2f;
    float invX = panelX + 22f;
    float titleY = panelY + 34f;
    float mainLabelY = panelY + 60f;
    float mainY = panelY + 78f;
    float hotbarLabelY = panelY + 250f;
    float hotbarY = panelY + 268f;
    float trashX = panelX + panelW - 70f;
    float trashY = panelY + 18f;

    SlotClick action = SlotClick.none();
    com.mineclone.world.ItemStack hovered = null;
    float hoverX = 0, hoverY = 0;

    ui.begin(w, h);
    ui.quad(0, 0, w, h, 0f, 0f, 0f, 0.65f);
    ui.quad(panelX, panelY, panelW, panelH, 0.74f, 0.74f, 0.70f, 1f);
    ui.quad(panelX, panelY, panelW, 4f, 1f, 1f, 1f, 0.45f);
    ui.quad(panelX, panelY + panelH - 4f, panelW, 4f, 0f, 0f, 0f, 0.45f);
    ui.quad(trashX, trashY, 44f, 44f, 0.28f, 0.12f, 0.12f, 0.95f);
    ui.quad(trashX + 10f, trashY + 12f, 24f, 4f, 0.95f, 0.95f, 0.95f, 0.85f);
    ui.quad(trashX + 13f, trashY + 18f, 18f, 16f, 0.80f, 0.80f, 0.80f, 0.85f);

    // main 27 slots (indices 9..35)
    for (int row = 0; row < 3; row++) {
        for (int col = 0; col < 9; col++) {
            int slotIndex = 9 + row * 9 + col;
            float x = invX + col * (slot + gap);
            float y = mainY + row * (slot + gap);
            boolean hov = hov(mx, my, x, y, slot, slot);
            drawSlotBack(x, y, slot, hov);
            com.mineclone.world.ItemStack s = inv.get(slotIndex);
            if (s != null) {
                drawItemIcon(s.type, x + 6f, y + 6f, slot - 12f, 1f);
                drawCount(w, h, s.count, x + 6f, y + 6f, slot - 12f);
            }
            if (hov) {
                hovered = s; hoverX = x; hoverY = y;
                if (clicked)      action = SlotClick.at(slotIndex, false);
                else if (rightClicked) action = SlotClick.at(slotIndex, true);
            }
        }
    }

    // hotbar 9 slots (indices 0..8)
    for (int col = 0; col < 9; col++) {
        float x = invX + col * (slot + gap);
        float y = hotbarY;
        boolean hov = hov(mx, my, x, y, slot, slot);
        drawSlotBack(x, y, slot, hov);
        com.mineclone.world.ItemStack s = inv.get(col);
        if (s != null) {
            drawItemIcon(s.type, x + 6f, y + 6f, slot - 12f, 1f);
            drawCount(w, h, s.count, x + 6f, y + 6f, slot - 12f);
        }
        if (col == selectedSlot) {
            ui.quad(x - 3f, y - 3f, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
            ui.quad(x - 3f, y + slot, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
            ui.quad(x - 3f, y, 3f, slot, 1f, 1f, 1f, 0.95f);
            ui.quad(x + slot, y, 3f, slot, 1f, 1f, 1f, 0.95f);
        }
        if (hov) {
            hovered = s; hoverX = x; hoverY = y;
            if (clicked)      action = SlotClick.at(col, false);
            else if (rightClicked) action = SlotClick.at(col, true);
        }
    }

    boolean trashHover = hov(mx, my, trashX, trashY, 44f, 44f);
    if ((clicked || rightClicked) && trashHover)
        action = SlotClick.trash();

    if (cursor != null) {
        drawItemIcon(cursor.type, (float) mx - 18f, (float) my - 18f, 36f, 1f);
        drawCount(w, h, cursor.count, (float) mx - 18f, (float) my - 18f, 36f);
    }
    ui.end();

    text.drawShadowed(font, "Inventory", panelX + 22f, titleY, w, h, 0.20f, 0.20f, 0.20f);
    text.drawShadowed(font, "Inventory", invX, mainLabelY, w, h, 0.20f, 0.20f, 0.20f);
    text.drawShadowed(font, "Hotbar", invX, hotbarLabelY, w, h, 0.20f, 0.20f, 0.20f);

    if (hovered != null) {
        String name = displayName(hovered.type);
        float twd = font.textWidth(name);
        float tx = Math.min(w - twd - 12f, Math.max(8f, hoverX + 4f));
        text.drawShadowed(font, name, tx, hoverY - 8f, w, h, 1f, 1f, 1f);
    }

    return action;
}
```

Старый вложенный класс `InventoryAction` и его использование удаляются (заменены `SlotClick`).

- [ ] **Step 4: Game — обработка survival-инвентаря по режиму**

В рендере `Game` блок `case CREATIVE_MENU` (строка ~1721) сейчас всегда зовёт `drawInventory(... inventory, selectedSlot, cursorItem)` со старым API. Заменить на ветвление по `gameMode`:

```java
case CREATIVE_MENU -> {
    hud.drawHotbar(vw, vh, inventory, selectedSlot);
    if (gameMode == GameMode.CREATIVE) {
        BlockType picked = hud.drawCreativeMenu(vw, vh, mx, my, clicked, inventory, selectedSlot);
        if (picked != null && picked != BlockType.AIR)
            inventory.set(selectedSlot, new ItemStack(picked, picked == BlockType.AIR ? 1 : ItemStack.MAX_STACK));
    } else {
        Hud.SlotClick sc = hud.drawInventory(vw, vh, mx, my, clicked, rightClicked,
                inventory, selectedSlot, cursorItem);
        if (sc.trash) {
            cursorItem = null;
        } else if (sc.slot >= 0) {
            cursorItem = sc.right
                    ? inventory.rightClick(sc.slot, cursorItem)
                    : inventory.leftClick(sc.slot, cursorItem);
        }
    }
}
```

(Если в этом блоке `rightClicked`/`mx`/`my`/`clicked` называются иначе — использовать существующие локальные имена из окружающего кода рендера.)

- [ ] **Step 5: Game — drawCreativeMenu принимает Inventory**

Изменить сигнатуру `Hud.drawCreativeMenu` с `BlockType[] hotbar` на `com.mineclone.world.Inventory inv` (используется только для индикатора выбранного слота внизу — заменить чтение `hotbar` на `inv`, либо убрать неиспользуемый параметр). Минимально-инвазивно: оставить тело как есть, поменяв тип параметра и не обращаясь к нему по индексам блоков (текущий код использует только `selectedSlot` для подсветки — `hotbar` фактически не читается в теле; проверить и удалить обращения, если есть).

- [ ] **Step 6: Game — прочие вызовы drawHotbar**

Найти все вызовы `hud.drawHotbar(vw, vh, inventory, selectedSlot)` (строки ~1660, 1680, 1722) — теперь `inventory` имеет тип `Inventory`, сигнатура совпадает. Убедиться, что компилируется.

- [ ] **Step 7: Скомпилировать + прогнать тесты + ручная проверка**

Run: блок компиляции, `.\run-tests.ps1`, затем `.\run.ps1`.
Expected: компиляция чистая; тесты `0 failed`.
Manual (SURVIVAL): сломать несколько камней → на хотбаре число растёт (напр. «×5»). Нажать E → панель инвентаря без палитры, числа стеков видны; ЛКМ берёт стек на курсор, ЛКМ по пустому слоту кладёт; ПКМ берёт половину/кладёт по одному; одинаковые сливаются; корзина очищает курсор. Закрыть E.
Manual (CREATIVE): `/gamemode creative`, E → пикер блоков, клик кладёт полный стек в выбранный слот; на хотбаре видно число 64.

- [ ] **Step 8: Коммит**

```bash
git add src/main/java/com/mineclone/game/Hud.java src/main/java/com/mineclone/game/Game.java
git commit -m "feat(hud): stack counts + survival inventory with click stack ops"
```

---

## Task 11: Финальная верификация

**Files:** none (проверка)

- [ ] **Step 1: Полный прогон тестов**

Run: `.\run-tests.ps1`
Expected: `==== N passed, 0 failed ====`, exit 0. Должны присутствовать `[PASS]` для: ItemStack, Inventory add/removeOne/click, drop table, level round-trip (с gameMode и count).

- [ ] **Step 2: Совместимость старого сейва**

Manual: запустить `.\run.ps1`, открыть мир, созданный ДО изменений (если есть в `saves/`). Ожидание: мир грузится, режим = CREATIVE, инвентарь читается (стеки count=1), краша нет. Если старых миров нет — пропустить, отметив это.

- [ ] **Step 3: Цикл сохранения SURVIVAL**

Manual: новый мир (SURVIVAL), добыть блоки (стеки > 1), выйти в меню (автосейв), зайти снова → инвентарь со стеками и режим SURVIVAL сохранены.

- [ ] **Step 4: Итоговый статус**

Сверить с разделами спеки `2026-06-16-survival-items-inventory-design.md`: режимы, ItemStack/Inventory, drop-таблица, дроп-в-инвентарь, расход при установке, числа стеков, разделение creative/survival UI, формат v6 с обратной совместимостью — все на месте. Отметить отложенное (физические дропы, 3D-иконки) как вне A.

---

## Self-Review (заполняется автором плана)

**Spec coverage:**
- Режимы Creative/Survival, `/gamemode`, запрет полёта → Task 3, 7, 9. ✓
- ItemStack + MAX_STACK 64 → Task 1. ✓
- Inventory (add/removeOne/merge/split) → Task 2. ✓
- Drop-таблица (STONE→COBBLE, GRASS→DIRT, LEAVES→ничего) → Task 4. ✓
- Дроп-в-инвентарь при ломании, расход при установке → Task 8. ✓
- Числа стеков на хотбаре и в панели → Task 10. ✓
- Объединение creative/survival UI (палитра только в creative) → Task 10. ✓
- Сохранение: ItemStack[] + gameMode, v6, чтение старого → Task 5, 6. ✓
- Тесты на чистой логике → Tasks 1,2,4,5/6. ✓
- 3D-иконки и физические дропы — явно вне A. ✓

**Placeholder scan:** код приведён во всех шагах; «подобрать точную сигнатуру/имена локальных переменных» в Tasks 6/7/10 — это указания сверить с конкретными строками файла, не заглушки логики.

**Type consistency:** `ItemStack(type,count)`, `Inventory.get/set/add/removeOne/leftClick/rightClick`, `GameMode.byOrdinalSafe`, `LevelData.emptyInventory/creativeInventory`, `Hud.SlotClick`, `drawHotbar(Inventory)`, `drawInventory(...)->SlotClick`, `drawCreativeMenu(Inventory)` — имена согласованы между задачами.

**Известное отступление:** Tasks 5→6→7 — связанная тройка с некомпилирующимся промежутком; коммит после Task 7. Это осознанно из-за инвазивной смены типа `inventory`.
