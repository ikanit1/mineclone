---
tags: [plan, ui, inventory, data]
status: in-progress
date: 2026-09-16
spec: docs/superpowers/specs/2026-09-16-inventory-survival-creative-design.md
---

# План A: фундамент меню выживания и креатива

> Исполняется в этой же сессии по `superpowers:executing-plans`. Формат —
> как у недавних планов проекта: задачи с точными файлами, сигнатурами,
> форматами и именами тестов; дизайн и «почему» — в спеке, здесь не
> повторяются. Галочки ставятся по ходу.

**Цель:** данные предметов из JSON, компоненты стопки, новый формат сейва,
пакетный рендер интерфейса и общий каркас окон. Существующие окна (инвентарь,
сундук, печь, креатив) переезжают на каркас **без потери функций** и получают
общие взаимодействия спеки A6–A7. Новый контент (материалы, броня, вкладки
креатива) — планы B–D.

**Правило каждого коммита:** `.\run-tests.ps1` зелёный, игра собирается
(`run.ps1` / компиляция из CLAUDE.md), выживание играбельно — в том числе крафт.

**Базовая линия (13e2c24):** 226 тестов, 0 упавших.

## Карта файлов

```
src/main/java/com/mineclone/data/       Json, JsonObject, JsonException, ResourceId, DataPack
src/main/java/com/mineclone/item/       Item, ToolClass, ToolSpec, AttackSpec, FoodSpec,
                                        ItemRegistry, Items, Tag, TagRegistry, Categories,
                                        ComponentType, ItemComponents, Components, BlockState,
                                        LegacyItems
src/main/java/com/mineclone/world/      ItemStack (переписан), Inventory, Recipes и Smelting
                                        (перенесены на Item), Furnace; ToolType/FoodType — удалены
src/main/java/com/mineclone/save/       ItemStackCodec (новый), LevelData, SaveFormat,
                                        SaveManager, Options
src/main/java/com/mineclone/render/     UiRenderer (пакетный), TextRenderer, Font, Shaders,
                                        ItemIcons (новый)
src/main/java/com/mineclone/ui/         UiInput (+кнопки мыши и модификаторы)
src/main/java/com/mineclone/ui/container/
                                        SlotRole, SlotStorage, ArrayStorage, InventoryStorage,
                                        SlotGroup, SlotRef, ContainerMenu, DragSplit,
                                        TooltipLayout, Tooltip, Spring, ItemFlights,
                                        WindowContext, ContainerScreen, InventoryScreen,
                                        ChestScreen, FurnaceScreen, CreativeScreen
src/main/java/com/mineclone/game/       Game (состояние WINDOW), Hud (без окон), PickBlock,
                                        DebugKeys, Autopilot, ContextHint
assets/data/mineclone/                  categories.json, items/*.json, tags/items/*.json
tools/                                  BenchUi, ComparePreviews, CheckSaves, RenderHudPreview
src/test/java/com/mineclone/            InventoryTests (новый), правки TestMain/FeatureTests
```

Инструменты `tools/*.java` запускаются через `java file.java` — лаунчер читает
исходник в системной кодировке, поэтому **в инструментах нет кириллических
литералов** (строки берутся из скомпилированных классов). Сообщения в консоль —
латиницей.

---

## Задача 0. Базовая линия замера и снимков

- [x] `UiRenderer`: статический счётчик `drawCalls` (инкремент у каждого
      `glDrawArrays`), `resetDrawCalls()`, `drawCalls()`. `TextRenderer` — то же,
      в тот же счётчик. Картинку не меняет.
- [x] `tools/BenchUi.java`: скрытое окно 1280×720, атлас, два кегля шрифта,
      `UiRenderer`, `TextRenderer`, `Hud`; инвентарь на 20 предметов. Режимы:
      `inventory` (`hud.drawInventory`), `chest` (`hud.drawChest`), `creative`
      (`hud.drawCreativeMenu`). 60 кадров прогрева выбрасываются, затем 300 кадров
      с `glFinish`; печать `mode  ms/frame  draw calls/frame`.
- [x] Прогнать: `java -cp "out;libs/*" tools\BenchUi.java`, цифры записать в
      раздел «Замеры» этого плана.
- [x] Снимки старым рендером: `java -cp "out;libs/*" tools\RenderHudPreview.java`,
      скопировать `out-test/previews/*.png` в `out-test/previews-baseline/`.
- [x] `tools/ComparePreviews.java <base> <new>`: сравнивает одноимённые PNG;
      пиксель «разный», если любой канал отличается больше чем на 40; провал,
      если разных пикселей больше 0,3 % кадра или размеры не совпали; печать
      по файлу `name  diff%  OK|FAIL`, код выхода 1 при провале. Кадры, которых
      нет в одной из папок, перечисляются и не валят прогон.
- [x] Коммит `chore(ui): draw-call counter, UI bench and preview comparison tools`.

## Задача 1. JSON

**Файлы:** `data/Json.java`, `data/JsonObject.java`, `data/JsonException.java`,
тесты в `InventoryTests.java` (новый класс, `runAll(Runner)` как у
`FeatureTests`; вызов из `TestMain.main` перед итогом).

```java
public final class Json {
    /** Объект → LinkedHashMap, массив → ArrayList, число → Double, строка, Boolean, null. */
    public static Object parse(String text, String source);          // JsonException
    public static JsonObject parseObject(java.nio.file.Path file);   // корень обязан быть объектом
}
public final class JsonException extends RuntimeException {
    public final String source; public final int line, column;       // 1-based; message: "items/tools.json:12:7: ..."
}
public final class JsonObject {
    public JsonObject(java.util.Map<String, Object> map, String source, String path);
    public boolean has(String key);
    public java.util.Set<String> keys();
    public String string(String key);                  public String string(String key, String def);
    public int integer(String key);                    public int integer(String key, int def);
    public float number(String key);                   public float number(String key, float def);
    public boolean bool(String key, boolean def);
    public JsonObject object(String key);              public JsonObject objectOrNull(String key);
    public java.util.List<Object> array(String key);   public java.util.List<String> strings(String key);
    public void allowOnly(String... keys);             // лишний ключ → JsonException с путём
    public JsonException error(String key, String message);   // "<source>: <path>.<key>: message"
}
```

- Разбор: строгий JSON плюс `//`-комментарии до конца строки и висячая запятая
  перед `]` и `}`. Экранирование `\" \\ \/ \b \f \n \r \t \uXXXX`.
- [x] Тесты: `json parses nested objects, arrays and escapes`,
      `json allows line comments and trailing commas`,
      `json errors carry file, line and column`,
      `json object accessors report the key path`,
      `json allowOnly rejects a typo`.
- [x] Коммит `feat(data): JSON parser with comments, trailing commas and precise errors`.

## Задача 2. Идентификаторы и пакет данных

- [x] `data/ResourceId.java`: `record ResourceId(String namespace, String path)`;
      `static ResourceId parse(String s, String defaultNamespace)` (без `:` —
      пространство по умолчанию); проверка `[a-z0-9_]+` для пространства и
      `[a-z0-9_./]+` для пути, иначе `IllegalArgumentException`;
      `toString()` → `ns:path`.
- [x] `data/DataPack.java`: корень `assets/data`.
      `List<Entry> files(String kind)` — все `<ns>/<kind>/**/*.json`, по
      пространству и пути; `record Entry(String namespace, String relPath, JsonObject json)`.
      `JsonObject root(String ns, String fileName)` — файл верхнего уровня или null.
- [x] Тесты: `resource ids parse with and without a namespace`,
      `resource ids reject upper case and spaces`,
      `data pack lists files by kind in a stable order` (временная папка).
- [x] Коммит `feat(data): resource ids and data pack scanning`.

## Задача 3. Предметы, категории, теги — только то, что уже есть в игре

**Данные** (`assets/data/mineclone/`):

- `categories.json`: `{"categories": [{"id","name"}...]}` — порядок сортировки:
  `building` Строительные, `decor` Декорации, `mechanics` Механика, `tools`
  Инструменты, `weapons` Оружие, `armor` Броня, `food` Еда, `potions` Зелья,
  `materials` Материалы, `misc` Разное.
- `items/blocks.json` — каждый нетехнический блок. Id и имена:

  | Блок | id | Имя | Категория |
  |---|---|---|---|
  | GRASS | grass | Трава | building |
  | DIRT | dirt | Земля | building |
  | STONE | stone | Камень | building |
  | SAND | sand | Песок | building |
  | WOOD | log | Бревно | building |
  | LEAVES | leaves | Листва | decor |
  | WATER | water | Вода | misc |
  | BEDROCK | bedrock | Коренная порода | building |
  | COBBLE | cobblestone | Булыжник | building |
  | PLANKS | planks | Доски | building |
  | TORCH | torch | Факел | decor |
  | GLASS | glass | Стекло | building |
  | DOOR_CLOSED | door | Дверь | mechanics |
  | STAIRS | stairs | Ступени | building |
  | SNOWY_GRASS | snowy_grass | Заснеженная трава | building |
  | CACTUS | cactus | Кактус | decor |
  | COAL_ORE | coal_ore | Угольная руда | building |
  | IRON_ORE | iron_ore | Железная руда | building |
  | GOLD_ORE | gold_ore | Золотая руда | building |
  | DIAMOND_ORE | diamond_ore | Алмазная руда | building |
  | FIRE | fire | Огонь | misc |
  | SNOW_LAYER | snow | Снег | decor |
  | CHEST | chest | Сундук | mechanics |
  | FURNACE | furnace | Печь | mechanics |
  | ICE | ice | Лёд | building |
  | MUD | mud | Грязь | building |
  | ASH | ash | Пепел | building |
  | MOSSY_COBBLE | mossy_cobblestone | Замшелый булыжник | building |
  | LAVA | lava | Лава | misc |
  | OBSIDIAN | obsidian | Обсидиан | building |
  | THIN_ICE | thin_ice | Тонкий лёд | building |
  | ROPE | rope | Верёвка | mechanics |
  | CHAIN | chain | Цепь | mechanics |
  | WEB | cobweb | Паутина | decor |
  | JOURNAL | journal | Дневник | decor |
  | BEDROLL | bedroll | Спальник | mechanics |

  Масса: камень и руды 2.5, земля/песок/грязь 1.5, дерево и доски 1.0, стекло
  и лёд 1.0, листва/снег/паутина/пепел 0.2, факел 0.1, сундук и печь 4.0,
  жидкости и огонь 1.0. Топливо (`fuel`, секунды; `COOK_TIME` = 8):
  `coal_ore` 64, `log` 12, `planks` 8, `stairs` 8.
- `items/tools.json` — двенадцать инструментов с прежними числами `ToolType`:

  | id | Имя | class | level | speed | durability | тайл |
  |---|---|---|---|---|---|---|
  | wooden_pickaxe | Деревянная кирка | pickaxe | 1 | 2.2 | 60 | wood_pickaxe |
  | stone_pickaxe | Каменная кирка | pickaxe | 2 | 4.0 | 132 | stone_pickaxe |
  | iron_pickaxe | Железная кирка | pickaxe | 3 | 6.5 | 251 | iron_pickaxe |
  | diamond_pickaxe | Алмазная кирка | pickaxe | 4 | 9.0 | 900 | diamond_pickaxe |
  | wooden_axe … diamond_axe | …топор | axe | 1–4 | те же | те же | *_axe |
  | wooden_shovel … diamond_shovel | …лопата | shovel | 1–4 | те же | те же | *_shovel |

  Имена тайлов — ровно те, что стоят в `TextureAtlas.TILE_NAMES` на позициях
  57–68 (сверить при реализации). Категория `tools`, масса 1.5 (кирка/топор) и
  1.0 (лопата), `max_stack` 1 неявно (есть `durability`).
- `items/food.json` — восемь: `beef` Сырая говядина (3), `porkchop` Сырая
  свинина (3), `chicken` Сырая курятина (2), `mutton` Сырая баранина (2),
  `cooked_beef` (7), `cooked_porkchop` (7), `cooked_chicken` (5),
  `cooked_mutton` (5) с именами из `FoodType`; тайлы 69–72 и 82–85 по именам
  `TILE_NAMES`; категория `food`, масса 0.3.
- `tags/items/`: `logs` (log), `planks` (planks), `wood` (имя «дерево»,
  псевдонимы `wood`, `древесина`; log, planks, stairs, door, chest и
  `#mineclone:wooden_tools`), `wooden_tools`,
  `stone_tools`, `iron_tools`, `diamond_tools`, `pickaxes`, `axes`, `shovels`,
  `ores` (4 руды). Вычисляемые (код) с JSON-дополнением имени:
  `light` («свет»), `durable` («прочность»), `fuel` («топливо»), `food`
  («еда»), `flammable` («горючее»).

**Код:**

```java
public enum ToolClass { PICKAXE, AXE, SHOVEL, SWORD }
public record ToolSpec(ToolClass toolClass, int level, float speed) {
    public boolean suits(BlockType b) { return b.preferredTool() == toolClass; }
}
public record AttackSpec(float damage, float speed) {}
public record FoodSpec(int nutrition) {}

public final class Item {
    public final ResourceId id;  public final String name;  public final String category;
    public final float mass;     public final int maxStack; public final int durability;
    public final boolean hidden; public final float fuelSeconds;
    public final BlockType block;          // null — не блок
    public final ToolSpec tool;            // null
    public final AttackSpec attack;        // null
    public final FoodSpec food;            // null
    public final int iconTile;             // тайл плоской иконки; у блока-куба −1
    public final boolean missing;          // заглушка неизвестного id
    java.util.Set<ResourceId> tags;        // заполняет TagRegistry после разрешения
    public java.util.Set<ResourceId> tags();
    public boolean hasTag(ResourceId tag);
}

public final class ItemRegistry {
    public static ItemRegistry load(DataPack pack);        // JsonException с путём при любой ошибке
    public Item get(String id);                            // "stone" или "mineclone:stone"; null — нет
    public Item require(String id);                        // IllegalArgumentException
    public Item forBlock(BlockType b);                     // null у AIR, WATER_FLOW, DOOR_OPEN
    public Item missing(ResourceId id);                    // кэшированная заглушка
    public java.util.List<Item> all();                     // порядок файлов, затем ключей
    public TagRegistry tags();
    public Categories categories();
}

public final class Items {
    public static ItemRegistry get();                      // ленивая загрузка из AppPaths.file("assets/data")
    public static void set(ItemRegistry r);                // для тестов
}
```

- Иконка блока: `iconTile = -1` для кубов; для крестов, слоёв и жидкостей —
  `sideTile` (у травы — `topTile`, как было). Поле `icon` в JSON у блока не
  обязательно.
- `ToolSpec.suits` — пока через `BlockType.preferredTool()`, который начинает
  возвращать `ToolClass` вместо `ToolType.Kind`.
- Строгая проверка: `allowOnly(name, icon, category, mass, max_stack,
  durability, hidden, fuel, block, tool, attack, food)`; неизвестная категория,
  тег, тайл или блок — ошибка с путём.
- `TagRegistry`: `Tag(ResourceId id, String name, List<String> aliases)`;
  `Set<Item> members(ResourceId)`; `List<Tag> find(String prefix)` — начало
  пути id, имени или псевдонима, без регистра, «ё» = «е»; `#`-ссылки внутри
  `values` разрешаются рекурсивно, цикл — ошибка.
- [x] Тесты: `every placeable block has exactly one item`,
      `technical blocks have no item`,
      `tools keep their old level, speed and durability` (таблица прежних
      значений `ToolType` прямо в тесте),
      `food keeps its old nutrition`,
      `computed tags find light sources and durable items`,
      `tag search matches russian aliases and treats yo as ye`,
      `tag includes resolve and a cycle is an error`,
      `registry rejects an unknown property with its path`,
      `missing items are cached placeholders`.
- [x] Коммит `feat(item): data-driven item registry, categories and tags`.

## Задача 4. Компоненты

```java
public final class ComponentType<T> {
    public interface Codec<T> {
        void write(java.io.DataOutput out, T value) throws java.io.IOException;
        T read(java.io.DataInput in) throws java.io.IOException;
    }
    public final String id; public final Codec<T> codec;
}
public final class Components {       // реестр известных типов
    public static final ComponentType<Integer> DAMAGE;          // "damage"
    public static final ComponentType<Boolean> UNBREAKABLE;     // "unbreakable"
    public static final ComponentType<String> CUSTOM_NAME;      // "custom_name"
    public static final ComponentType<java.util.List<String>> LORE;   // "lore", ≤ 4 строк
    public static final ComponentType<BlockState> BLOCK_STATE;  // "block_state"
    public static ComponentType<?> byId(String id);             // null — неизвестный
}
/** chest и furnace могут быть null; стопки копируются глубоко. */
public record BlockState(byte meta, java.util.List<ItemStack> chest, FurnaceState furnace) { … }
public record FurnaceState(ItemStack input, ItemStack fuel, ItemStack output,
                           float burnLeft, float burnMax, float cook) { … }
public final class ItemComponents {
    public static final ItemComponents EMPTY;
    public <T> T get(ComponentType<T> type);
    public <T> ItemComponents with(ComponentType<T> type, T value);   // value == null → without
    public ItemComponents without(ComponentType<?> type);
    public ItemComponents withRaw(String id, byte[] bytes);           // неизвестный тип
    public java.util.Set<String> ids();  public boolean isEmpty();  public int size();
    public void write(java.io.DataOutput out);                        // VarInt n; (UTF id, VarInt len, bytes)*
    public static ItemComponents read(java.io.DataInput in);
    // equals/hashCode — не зависят от порядка; значения сравниваются через equals,
    // стопки внутри BlockState — через ItemStack.contentEquals
}
```

- Значения неизменяемы; `LORE` хранится `List.copyOf`. `BlockState` делает
  глубокие копии стопок в конструкторе.
- VarInt — 7-битная запись, `data/VarInt.java` (`write`, `read`).
- [x] Тесты: `components compare regardless of insertion order`,
      `component codec round-trips every known type`,
      `unknown components survive a round trip byte for byte`,
      `with null removes a component`.
- [x] Коммит `feat(item): immutable stack components with binary codecs`.

## Задача 5. Стопка на реестре; удаление ToolType и FoodType

```java
public final class ItemStack {
    public final Item item;
    public int count;
    public ItemStack(Item item, int count);           // count в [1, item.maxStack]
    public ItemStack(BlockType block, int count);     // Items.get().forBlock(block); технический блок — IAE
    public static ItemStack of(String id);            // count 1
    public static ItemStack of(String id, int count);
    public BlockType block();  public ToolSpec tool();  public FoodSpec food();  public AttackSpec attack();
    public boolean hasDurability();  public int maxStack();
    public ItemComponents components();  public void setComponents(ItemComponents c);
    public <T> T get(ComponentType<T> t);  public <T> ItemStack set(ComponentType<T> t, T v);
    public int damage();  public void setDamage(int d);
    public float condition();                          // 1 у предметов без износа
    public boolean wear();                             // true — сломался; UNBREAKABLE не изнашивается
    public String displayName();                       // CUSTOM_NAME или item.name
    public int iconTile();
    public boolean stacksWith(ItemStack o);            // тот же item, равные компоненты, maxStack > 1
    public boolean isFull();  public int addUpTo(int amount);
    public ItemStack copy();  public ItemStack copyWithCount(int n);
    public boolean contentEquals(ItemStack o);         // item, count, компоненты
}
```

- `MAX_STACK` удаляется; везде `maxStack()`.
- `Inventory`: `int add(ItemStack s)` (сливает копии, возвращает остаток; `s`
  не хранится), `boolean canAdd(ItemStack s, int amount)`, `int contentHash()`;
  удаляются `add(BlockType,int)`, `addFood`, `canAdd(BlockType,int)`;
  `addItem` остаётся. Статические `leftClick/rightClick` — по `stacksWith` и
  `maxStack()`.
- `LegacyItems` (item): таблицы строк прежних порядков
  `TOOLS = {wooden_pickaxe, stone_pickaxe, iron_pickaxe, diamond_pickaxe, wooden_axe, …, diamond_shovel}`,
  `FOODS = {beef, porkchop, chicken, mutton, cooked_beef, cooked_porkchop, cooked_chicken, cooked_mutton}`;
  `Item block(int ordinal)`, `Item tool(int ordinal)`, `Item food(int ordinal)`.
- Переезд кода (всё, что нашёл `grep` по `ToolType|FoodType|isTool|isFood|.tool|.food|.type`):
  - `BlockType.preferredTool()` → `ToolClass`; `getDrop()` остаётся до плана B.
  - `Recipes` (полка до плана B): `Recipe(Item need, int needCount, Item handle, int handleCount, Item result, int resultCount)`,
    `count(Inventory, Item)`, прежние правила (рукоять того же вида, место под
    результат до списания). Таблица та же, ссылки — `Items.get().require(...)`.
  - `Smelting` (до плана B): `result` по таблице id → id
    (`beef→cooked_beef`, `porkchop→cooked_porkchop`, `chicken→cooked_chicken`,
    `mutton→cooked_mutton`, `sand→glass`, `cobblestone→stone`);
    `fuelSeconds` = `item.fuelSeconds`; инструменты и еда не горят (у них нет `fuel`).
  - `Furnace.fits`: `output.count < output.maxStack()`.
  - `MobType.drop()` → `String` id еды (`beef`, `porkchop`, `chicken`, `mutton`).
  - `ItemEntity.density`: еда 0.82, всё с износом 2.4, блоки — как было.
  - `ItemRenderer`: куб, если `block() != null` и форма куба; иначе `iconTile()`.
  - `HeldItemRenderer.render(atlas, ItemStack held, …)`: меши инструментов в
    `HashMap<Item, Mesh>` по `iconTile`; блоки — как было; `condition` из стопки.
  - `ContextHint`: `held.food() != null` → «съесть » + `held.displayName().toLowerCase()`.
  - `Game`: `currentBlock()` → `s.block()`; `canTake` → `inventory.canAdd(s, 1)`;
    `giveStack` → `inventory.add(s)`; `miningSpeed/canHarvest/wearHeldTool` —
    через `tool()`; креатив-выдача — `copyWithCount(maxStack())`;
    `giveMobDrop` → `ItemStack.of(drop, count)`; тост поломки — `displayName()`.
  - `LevelData.creativeInventory()` → стопки блоков через новый конструктор.
  - `Hud`: иконки, подписи (`displayName()` вместо `displayName(BlockType)`),
    креатив-меню берёт `Items.get().all()` без `hidden`.
  - Тесты `TestMain`, `FeatureTests` и инструменты `RenderHudPreview`,
    `RenderAtmospherePreview`, `RenderMobPreview` — на `ItemStack.of(...)`.
    Смысл каждого теста сохраняется; утверждения про `ToolType.X.level` —
    через `Items.get().require("x").tool.level()`.
- [x] Удалить `world/ToolType.java`, `world/FoodType.java`.
- [x] Тесты (новые): `stacks merge only with equal components`,
      `unbreakable tools never wear`, `custom name overrides the item name`,
      `inventory add keeps components apart`, `content hash changes when a count changes`.
- [ ] Полный `.\run-tests.ps1` зелёный; игра запускается, в выживании ломается
      блок, выпадает дроп, крафтится кирка, плавится мясо (ручная проверка
      автопилотом не нужна — это делает задача 14).
- [x] Коммит `refactor(item): stacks reference registry items; ToolType and FoodType retired`.

## Задача 6. Сохранения

**Формат стопки (`save/ItemStackCodec.java`):**

```
write(DataOutput, ItemStack)  : UTF id ("" — пусто), VarInt count, ItemComponents.write
read(DataInput)               : неизвестный id → Items.get().missing(id), компоненты сохраняются
readLegacy(DataInput)         : формат v8 (SLOT_EMPTY/BLOCK/TOOL/FOOD) через LegacyItems;
                                износ инструмента → DAMAGE
```

**`level.dat` v10:**

```
MAGIC, 10
UTF name, long seed, long lastPlayed, float health, float hunger,
double px py pz, double spawnX spawnY spawnZ, float yaw pitch, float timeOfDay,
int selectedSlot, int gameMode
VarInt sections
  UTF id, VarInt length, bytes
```

- Секции плана A: `inventory` (VarInt n; стопки), `pending` (VarInt n; стопки —
  курсор и сетка крафта). Секции B–D добавляются **без подъёма версии**.
- Неизвестная секция не теряется: `LevelData.extraSections`
  (`Map<String, byte[]>`, порядок сохраняется); `Game` держит её с загрузки и
  отдаёт при записи.
- `LevelData`: поля `pending` (`ItemStack[]`, может быть пустым) и
  `extraSections`; прежние конструкторы остаются (пустые значения).
- Чтение v1–9 — прежний код с `ItemStackCodec.readLegacy` вместо `readStack`.

**Чанк v6:** тот же порядок полей, что v5, стопки — `ItemStackCodec.write/read`;
v1–5 — `readLegacy`.

**`options.dat` v6:** после полей v5 — `boolean advancedTooltips`,
`boolean recipeBookOpen`, `boolean recipeBookCraftable`, `UTF recipeBookCategory`,
`int sortMode`. v1–5 — значения по умолчанию (`false, false, false, "all", 0`).

**`Game`:** при загрузке — стопки `pending` в инвентарь, остаток к ногам игрока
после первого кадра мира (когда чанк под ногами загружен); при записи —
`pending` = копия курсора открытого окна (сетка — план B).

**`tools/CheckSaves.java`** (только чтение): все миры каталога `saves`
(аргумент — другой каталог), `loadLevel` и все `c.*.dat` через `loadChunk`;
печать `world  chunks  stacks  missing  failures`; код 1 при любом отказе.
Ничего не пишет.

- [x] Тесты: `level v9 bytes load into registry items` (писатель v9 скопирован в
      тест, три вида слотов, износ), `chunk v5 chests, furnaces and dropped items migrate`,
      `level v10 round-trips components, missing items and pending stacks`,
      `unknown level sections survive a save`,
      `options v5 load with default inventory preferences`, `options v6 round-trip`.
- [x] Прогон `java -cp "out;libs/*" tools\CheckSaves.java` по 13 мирам — ноль
      отказов, ноль `missing`; вывод — в раздел «Замеры».
- [x] Коммит `feat(save): level v10 with sections, chunk v6 and options v6 on the new stack format`.

## Задача 7. Пакетный рендер интерфейса

**Шейдер** `Shaders.UI_BATCH_VERTEX/FRAGMENT` вместо `UI_*`/`TEXT_*`:

```glsl
// vertex: in vec2 aPos; in vec2 aUv; in vec4 aColor; in float aMode;
// fragment: mode 0 — заливка; 1 — атлас (uAtlas, discard при a < 0.1);
//           2 — стекло (uBackdrop по gl_FragCoord/uFbSize, та же формула, что сейчас);
//           3 — шрифт A (uFontA .r → альфа, discard < 0.01); 4 — шрифт B;
//           5 — произвольная текстура (uTex, discard при a < 0.1)
```

**`UiRenderer`:**

- VBO на вершины `x, y, u, v, r, g, b, a, mode` (9 float), 4 вершины на квадрат,
  общий индексный буфер (`int`), начальная ёмкость 8192 квадрата, рост вдвое.
- Блоки текстур: 0 атлас, 1 стекло, 2 шрифт A, 3 шрифт B, 4 произвольная.
  `setAtlas(int textureId)`, `registerFonts(Font a, Font b)`, `setBackdrop(int)`.
- `begin(w, h)` открывает пакет (повторный `begin` с тем же размером —
  продолжение); `end()` — сброс и прежнее GL-состояние (глубина и отсечение
  включены обратно). `flush()` — сброс без закрытия.
- `quad`, `quad4`, `texQuad` (атлас → режим 1, иначе режим 5 со сбросом при
  смене текстуры), `texQuad4`, `glass` — те же сигнатуры.
- `glyphs(Font font, String s, float x, float y, float r, float g, float b, float a)`;
  шрифт не из пары — режим 5 со сбросом.
- Трансформация: `pushTransform(float scale, float pivotX, float pivotY, float dx, float dy)`,
  `popTransform()` — применяется к вершинам при добавлении.
- Счётчик `drawCalls()` за кадр, `resetDrawCalls()`.

**`Font`:** `void glyphs(String s, float x, float y, GlyphSink sink)`,
`interface GlyphSink { void glyph(float x0, float y0, float x1, float y1, float s0, float t0, float s1, float t1); }`;
`buildString` остаётся для старых мест, если такие найдутся, иначе удаляется.

**`TextRenderer(UiRenderer ui)`:** `draw`, `drawShadowed`, `drawOutlined` — глифы в
пакет; вне открытого пакета сам делает `begin/end` вокруг строки.

**`MenuTheme.flush()`:** отложенный текст идёт одним `ui.begin` … `ui.end` —
один draw call на слой. `Hud`: счётчики стопок хотбара — в том же пакете,
что иконки.

- `Game.run`: `ui = new UiRenderer(); text = new TextRenderer(ui);`
  `ui.setAtlas(atlas.getTextureId()); ui.registerFonts(font, smallFont);`
  То же в `RenderHudPreview`, `BenchUi`.
- F3: строка `UI: N draw calls` (счётчик прошлого кадра).
- [x] `RenderHudPreview` → `ComparePreviews out-test/previews-baseline out-test/previews`
      — все кадры OK.
- [x] `BenchUi` — цифры «после рендера» в «Замеры».
- [x] Тесты (без GL): `batch transform scales around its pivot`
      (`UiRenderer.transformPoint` — статическая функция).
- [x] Коммит `perf(ui): one batched draw call per interface layer`.

## Задача 8. Иконки предметов

**`render/ItemIcons.java`:**

```java
public ItemIcons(UiRenderer ui, TextureAtlas atlas);
public void draw(ItemStack s, float x, float y, float size, float alpha, float yawDeg);
public void drawCount(TextRenderer text, Font small, int count, float x, float y, float size, int sw, int sh);
public static int boxFaces(float x0, float y0, float z0, float x1, float y1, float z1,
                           float yawDeg, float[] out, int offset);   // грани без аллокаций
```

- Форма по блоку: куб; `STAIRS` — нижний полублок и верхняя задняя четверть;
  `SNOW_LAYER` — бокс высотой 2/8; `BEDROLL` — 4/8; кресты, вода, лава и не-блоки
  — плоский тайл. Яркость граней — прежняя (`isoCubeFaces`: 1.0 / 0.80 / 0.62).
- `missing` — пурпурно-чёрная шахматка 2×2 и знак «?».
- Полоска прочности — прежняя; вращение выбранного слота — прежнее.
- `Hud.drawHotbar` и старые окна `Hud` переходят на `ItemIcons`;
  `Hud.isoCubeFaces` удаляется (тест `testIsoCubeFaces`, если есть, — на `boxFaces`).
- [x] Тесты: `box faces of a cube match the old iso cube at 45 degrees`,
      `stairs icon has more faces than a cube`,
      `box faces write into the caller buffer without allocating` (сравнение
      результата двух вызовов в один буфер).
- [x] `ComparePreviews` — кадры с кубиками OK; ступени и слои в новом кадре
      `hud-icon-shapes` (новый кадр `RenderHudPreview`).
- [x] Коммит `feat(ui): item icons by block shape, shared by hotbar and windows`.

## Задача 9. Логика окон

**`ui/UiInput`:** `rightDown/rightPressed/rightReleased`,
`middlePressed`, `shift()`, `alt()`; билдер — `rightDown(…)`, `rightClick()`,
`middleClick()`, `held(...)` для Shift/Ctrl/Delete. `Game` заполняет из `Input`.

**`ui/container`:**

```java
public enum SlotRole { HOTBAR, MAIN, BAG, ARMOR, OFFHAND, ACCESSORY, CONTAINER, CRAFT_GRID,
    CRAFT_RESULT, FURNACE_INPUT, FURNACE_FUEL, FURNACE_OUTPUT, CREATIVE_SOURCE, EDITOR, TRASH }

public interface SlotStorage {
    int size();  ItemStack get(int i);  void set(int i, ItemStack s);
    default boolean canPlace(int i, ItemStack s) { return true; }
    default int maxCount(int i, ItemStack s) { return s.maxStack(); }
    default boolean canTake(int i) { return true; }
    default ItemStack take(int i, int amount) { … }      // по умолчанию — отрезать от стопки
    default void changed(int i) { }
}
public final class ArrayStorage implements SlotStorage   // ItemStack[] + фильтр canPlace + колбэк changed
public final class InventoryStorage implements SlotStorage   // окно в Inventory [from, from+count)
public final class SlotGroup { public final String id; public final SlotRole role;
    public final SlotStorage storage; public final int columns; }
public record SlotRef(SlotGroup group, int index) { ItemStack get(); void set(ItemStack s); }

public final class ContainerMenu {
    public ContainerMenu(java.util.List<SlotGroup> groups);
    public ItemStack cursor();  public void setCursor(ItemStack s);
    public void route(SlotRole from, SlotRole... to);            // таблица Shift-переносов окна
    public record Move(SlotRef from, SlotRef to, ItemStack icon) {}   // from == null — с курсора
    public java.util.List<Move> moves();                         // за последнюю операцию
    public java.util.List<ItemStack> dropped();                  // выбросить в мир; забирает вызывающий
    public void leftClick(SlotRef s);   public void rightClick(SlotRef s);
    public void shiftClick(SlotRef s);  public void doubleClick(SlotRef s);
    public void numberKey(SlotRef s, int hotbarIndex);
    public void drop(SlotRef s, boolean wholeStack);  public void dropCursor(boolean wholeStack);
    public void beginDrag(boolean right);  public void dragOver(SlotRef s);
    public java.util.Map<SlotRef, Integer> dragPreview();  public boolean dragging();  public void endDrag();
    public void cloneFull(SlotRef s);   // средняя кнопка в креативе
    public void delete(SlotRef s);      // Delete+клик в креативе
    public java.util.List<ItemStack> closeAll();   // курсор и возвращаемые группы — отдать игроку
}
public final class DragSplit {
    /** @return прибавка каждому слоту; остаток = cursor − сумма */
    public static int[] distribute(int cursorCount, boolean right, int[] existing, int[] limits);
}
```

Правила (спека A6 и D1):

- ЛКМ/ПКМ — прежние правила `Inventory`, плюс фильтры `canPlace/maxCount` и
  слоты «только взять» (`FURNACE_OUTPUT`, `CRAFT_RESULT`): клик курсором со
  стыкуемой стопкой забирает содержимое слота в курсор до предела.
- `CREATIVE_SOURCE`: ЛКМ — копия ×1, повторный ЛКМ тем же — +1; ПКМ — как ЛКМ;
  стопка с курсора, положенная на источник, удаляется; `cloneFull` — копия на
  предел стопки; Shift — полная стопка в хотбар (долить → пустой → выбранный).
- `TRASH`: клик со стопкой — удалить курсор.
- Shift: сначала неполные стыкуемые слоты целевых групп по порядку, потом
  пустые. Маршруты задаёт экран (таблица спеки A6).
- Протяжка: слоты добавляются, пока их меньше числа предметов на курсоре; слот
  подходит, если пуст или стыкуется и не полон; ЛКМ — поровну `floor(n/k)` с
  потолком слота, ПКМ — по одному; одна клетка — обычный клик.
- Двойной клик: сначала неполные стопки, потом полные, из всех групп кроме
  `CRAFT_RESULT`, `CREATIVE_SOURCE`, `TRASH`, до предела курсора.
- Цифра: обмен с `HOTBAR[n]` с учётом `canPlace` обеих сторон.
- [x] Тесты: `left and right clicks keep the old inventory rules`,
      `take-only slots pull into a matching cursor`,
      `drag split shares evenly and keeps the remainder on the cursor`,
      `right drag places one per slot`, `drag stops adding slots past the cursor count`,
      `a one-slot drag is a plain click`,
      `shift-click fills partial stacks before empty slots`,
      `shift-click follows the window routes`,
      `double click collects partial stacks first`,
      `number key swaps with the hotbar and respects slot filters`,
      `q drops one and ctrl-q drops the stack`,
      `creative source clicks add one, middle click gives a full stack`,
      `a stack dropped on the creative source or trash disappears`,
      `closing a window returns the cursor to the player`.
- [x] Коммит `feat(ui): container menu logic with drag split, shift routes and creative rules`.

## Задача 10. Подсказки, пружины, перелёты

```java
public final class Spring { public float value, velocity;
    public Spring(float frequency, float dampingRatio);
    public void update(float target, float dt); }       // полу-неявный Эйлер, подшаги ≤ 1/240 с
public final class SpringVec2 { … то же по x и y … }
public final class TooltipLayout {
    public enum Corner { RIGHT_BELOW, LEFT_BELOW, RIGHT_ABOVE, LEFT_ABOVE }
    public record Placement(Corner corner, float x, float y) {}
    public static Placement place(float ax, float ay, float aw, float ah,
                                  float tw, float th, float screenW, float screenH,
                                  float gap, float margin);
}
public final class Tooltip {           // содержимое без GL
    public record Line(String text, float[] rgb) {}
    public static java.util.List<Line> lines(ItemStack s, boolean advanced);
}
public final class ItemFlights {
    public void launch(ItemStack icon, float fx, float fy, float tx, float ty, SlotRef target);
    public void update(float dt);  public boolean hides(SlotRef slot);
    public void forEach(FlightSink sink);   // текущая позиция и масштаб каждой иконки
}
```

- Подсказка A7: своё имя — янтарным; lore — серым; «Неразрушимый»;
  расширенные — id, `damage/durability` у предметов с износом, теги (до 6 и «…»),
  «+N компонентов»; строка «+данные блока» при `BLOCK_STATE`.
- Перелёт — 0,18 с, сглаженный; слот назначения скрывает иконку до прилёта.
- [x] Тесты: `a critically damped spring does not overshoot`,
      `an underdamped spring overshoots a little and settles`,
      `spring stays stable at a 50 ms frame`,
      `tooltip prefers right-below and flips at the right and bottom edges`,
      `tooltip is clamped when no corner fits`,
      `advanced tooltip shows id, durability numbers and tags`,
      `flights hide their target until they land`.
- [x] Коммит `feat(ui): springs, edge-aware tooltips and slot-to-slot flights`.

## Задача 11. Экраны и перевод игры на них

**`WindowContext`:**

```java
public interface WindowContext {
    Inventory inventory();  int selectedSlot();  GameMode mode();
    void throwStack(ItemStack s);     // перед игроком
    void give(ItemStack s);           // в инвентарь, остаток — throwStack
    void click(float volume, float pitch);
    void toast(String text);
    boolean advancedTooltips();
    KeyBindings keys();
}
```

**`ContainerScreen implements Screen`** (база):

- раскладка групп в прямоугольники слотов (слот 42, зазор 5 — как сейчас);
- разбор `UiInput`: наведение, нажатие/отпускание ЛКМ и ПКМ, протяжка
  (нажатие с непустым курсором запускает, отпускание завершает), Shift,
  двойной клик (`MenuTheme.DOUBLE_CLICK`), цифры по `KeyBindings.slot(i)`,
  Q/Ctrl+Q над слотом, клик мимо панелей со стопкой на курсоре, средняя кнопка
  и Delete в креативе; клавиша `INVENTORY` или Esc — `MenuAction.back()`;
- отрисовка одним пакетом: затемнение, стекло панелей, слоты с пружиной
  укладки, иконки `ItemIcons`, счётчики, предпросмотр протяжки (числа будущих
  стопок полупрозрачно), перелёты, курсор на `SpringVec2`, подсказка
  `TooltipLayout` с пружиной позиции;
- появление окна: масштаб `Spring` 0,96 → 1 (частота 18, затухание 0,6) через
  `pushTransform`, фейд от `ScreenStack`;
- `closed()` → `ctx.give` для всего из `closeAll()`; `dropped()` → `ctx.throwStack`.

**Экраны:**

| Экран | Группы | Shift-маршруты | Особенности |
|---|---|---|---|
| `InventoryScreen` | MAIN 3×9, HOTBAR, TRASH | MAIN→HOTBAR, HOTBAR→MAIN | полка рецептов как сейчас (до плана B) |
| `ChestScreen` | CONTAINER 27, MAIN, HOTBAR | игрок→CONTAINER, CONTAINER→MAIN,HOTBAR | `changed` → `world.markChestDirty`; закрыть, если сундук исчез |
| `FurnaceScreen` | FURNACE_INPUT/FUEL/OUTPUT, MAIN, HOTBAR | игрок→FUEL (топливо), INPUT (плавится), печь→MAIN,HOTBAR | пламя и стрелка из `Hud`; закрыть, если печь исчезла |
| `CreativeScreen` | CREATIVE_SOURCE (все нескрытые предметы, прокрутка 10 колонок), HOTBAR, TRASH | SOURCE→HOTBAR | вкладки — план D |

**`Game`:**

- `State.CREATIVE_MENU`, `CHEST_MENU`, `FURNACE_MENU` → `State.WINDOW`;
  `ScreenStack windows`; `openWindow(ContainerScreen)`, `closeWindow()`;
  `updateWindow(dt)` — мир тикает как в прежних трёх методах, экран
  проверяет, жив ли его блок (`boolean valid()`).
- Открытие: E в игре → `InventoryScreen` или `CreativeScreen` по режиму;
  сундук и печь — как сейчас, но экранами; `swallowMouseUntilUp` заменяет
  пустой ввод первого кадра, как у меню (`menuInputBlocked`).
- `drawUi`: `case WINDOW` — хотбар не рисуется (он в окне), сердца и сытость —
  да; кадр `windows.frame(theme)`; `BACK` с корня → `closeWindow()`.
- `cursorItem`, `clickFurnaceSlot`, `addToChest`, ветки `drawInventory/Chest/
  Furnace/CreativeMenu` удаляются; `Hud.SlotClick`, `drawInventory`,
  `drawChest`, `drawFurnace`, `drawCreativeMenu`, `drawSlotBack` удаляются.
- Размытие мира под окном (`menuOpen`) — по `State.WINDOW`.
- [x] `RenderHudPreview`: кадры `hud-inventory`, `hud-chest`, `hud-furnace`,
      `hud-creative` рисуются экранами через заглушку `WindowContext`;
      новые кадры `window-drag-split` (протяжка с числами), `window-tooltip-edge`
      (подсказка у правого нижнего края), `window-advanced-tooltip`.
- [x] `BenchUi` переводится на экраны; цифры «после» в «Замеры». Цель — не
      больше 3 draw call на инвентарь и 4 на креатив.
- [x] Коммит `feat(ui): inventory, chest, furnace and creative windows on the container framework`.

## Задача 12. Пипетка и F3-сочетания

- `game/PickBlock.java` (без GL):
  `static int pick(Inventory inv, int selected, Item item, boolean creative, ItemStack creativeStack)`
  → новый выбранный слот; правила спеки A6.
- `BLOCK_STATE` при Ctrl в креативе: meta; содержимое сундука (`world.getChest`);
  печь (`world.getFurnace` → `FurnaceState`: три стопки, `burnLeft`, `burnMax`, `cook`).
- Постановка стопки с `BLOCK_STATE`: meta (кроме двери — у неё своя логика
  двух половин), сундук (`createChest` + копии стопок, `markChestDirty`),
  печь (`createFurnace` + копия состояния).
- `game/DebugKeys.java` (без GL):
  ```java
  enum Action { TOGGLE_DEBUG, TOGGLE_ADVANCED_TOOLTIPS, CYCLE_META }
  java.util.List<Action> update(boolean f3Down, boolean hPressed, boolean middlePressed);
  ```
  F3 переключает отладку при отпускании, если за время удержания не было сочетания.
- `Game`: средняя кнопка без F3 — пипетка; F3+средняя — прежний перебор meta;
  F3+H — `advancedTooltips` с тостом «Расширенные подсказки: вкл/выкл» и
  записью `options.dat`; отладка по `TOGGLE_DEBUG`; команда `/debug` — как была.
- [x] Тесты: `survival pick selects a hotbar match or swaps from storage`,
      `survival pick does nothing without the item`,
      `creative pick fills the selected slot or the first empty one`,
      `ctrl pick copies chest contents into block state`,
      `placing a block state restores meta and chest contents` (на `World` без GL,
      как соседние тесты мира),
      `f3 toggles debug on release only without a combo`,
      `f3+h toggles advanced tooltips and suppresses the debug toggle`.
- [x] Коммит `feat(game): pick block with block state, F3 combos and advanced tooltips`.

## Задача 13. Автопилот окон

- `Input`: `injectMouseButton(int button, boolean down)` (действует со
  следующего `update`), `overrideCursor(double x, double y)` / `clearCursorOverride()`.
- `Autopilot.Driver`: `mouseAt(float vx, float vy)` (виртуальные координаты),
  `mouseButton(int button, boolean down)`, `holdKey(int key, boolean down)`,
  `windowSlotCenter(String groupId, int index)` (центр слота открытого окна),
  `inventory()`, `debugShown()`, `advancedTooltips()`.
- Шаги после входа в мир (выживание):
  1. положить 10 булыжника в `MAIN[0]`; E — `state == WINDOW`, снимок `window-inventory`;
  2. ЛКМ по `MAIN[0]` — стопка на курсоре; зажатая ЛКМ по `MAIN[1..3]`,
     отпустить — в слотах 3/3/3, на курсоре 1; снимок `window-drag`;
  3. Esc — окно закрыто, курсор вернулся в инвентарь (всего 10 булыжника);
  4. F3 зажать, H нажать, F3 отпустить — `advancedTooltips == true`, отладка не показана;
  5. `/gamemode creative`, E — открыт креатив, снимок `window-creative`, Esc.
- [x] Прогон:
      `java -Dmineclone.autopilot=out-test/autopilot/shots -Dmineclone.savesDir=out-test/autopilot/saves -cp "out;libs/*" com.mineclone.Main`
      — код 0.
- [x] Коммит `test(ui): autopilot drives the inventory, drag split, F3+H and creative window`.

## Задача 14. Документы

- [ ] ADR `knowledge/decisions/item-registry-and-components.md`: данные,
      компоненты, формат стопки и секции `level.dat`, миграция, итог `CheckSaves`.
- [ ] ADR `knowledge/decisions/inventory-windows.md`: пакетный рендер (цифры
      `BenchUi` до/после), каркас окон, правила кликов, анимации, пипетка, F3+H.
- [ ] `CLAUDE.md`: разделы «Предметы и данные», «Окна инвентаря»; раздел про
      `ItemStack` и `ToolType` переписан; ручки настройки (`ContainerScreen`
      размеры слота, `Spring` окна, `ItemFlights` длительность, `TooltipLayout`
      поля); команды `BenchUi`, `ComparePreviews`, `CheckSaves`.
- [ ] План: статус `done`, замеры и отступления.
- [ ] Коммит `docs: item registry and inventory windows ADRs, CLAUDE.md`.

---

## Замеры

| Что | До | После |
|---|---|---|
| Инвентарь, draw calls / кадр | 476 | 25 → **1** (задача 11) |
| Инвентарь, мс / кадр | 2,33 | 0,50 → **0,16** |
| Сундук, draw calls / кадр (мс) | 633 (3,05) | 36 (0,68) → **1 (0,10)** |
| Креатив, draw calls / кадр | 355 | 3 → **1** |
| Креатив, мс / кадр | 1,45 | 0,15 → **0,11** |
| CheckSaves: миры / чанки / стопки / missing / отказы | — | 13 / 1588 / 132 / 0 / 0 |

## Решения, принятые по ходу

- **Секции в `level.dat` вместо подъёма версии в каждой части.** Спека
  перечисляет в v10 экипировку, эффекты и правила мира. Формат v10 сразу
  делается секционным, и планы C и D добавляют свои секции без v11/v12;
  неизвестная секция переживает запись.
- **Окраска по маске и блик чар — в плане C**, когда появятся зелья, яйца,
  кожаная броня и чары: в плане A нет ни одного предмета, на котором их можно
  проверить.
- **Действие `SWAP_HANDS` — в плане C** вместе с левой рукой: клавиша без
  действия была бы мёртвой в раскладке.
- **`creative.dat` — в плане D**: пресетов и избранного до него нет.
- **Мусорный слот выживания сохраняется** (был в старом окне): план A — паритет.
