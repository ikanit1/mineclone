# Items and data packs

Updated: 2026-09-24. Paths in backticks are relative to the repository root.

`item/Items` loads `assets/data` through `data/DataPack`. Namespaced ResourceId
strings identify items in saves and protocol messages. Data packs must be included
in server archives too; a boot without item JSON is not a valid distribution.

## Предметы и данные

Предмет — строка в `assets/data/<ns>/items/*.json`, а не значение
перечисления. `Item` собирает всё, что о нём известно: имя, категория,
масса, предел стопки, прочность, топливо, и необязательные части — блок
(`block`), инструмент (`tool`), оружие (`attack`), еда (`food`). Блок,
инструмент и еда перестали быть тремя видами стопки: вопрос «это инструмент»
задаётся предмету, а не форме стопки. ADR:
`knowledge/decisions/item-registry-and-components.md`.

- `ItemRegistry.load` строгий: неизвестная категория, тег, тайл или блок —
  `JsonException` с именем файла и путём до ключа. Опечатка обязана падать:
  тихо выпавший из креатива предмет ищется потом только глазами.
- **Технический блок предметом не бывает.** `AIR`, `WATER_FLOW` и
  `DOOR_OPEN` существуют внутри мира, но в руки не берутся, и предмет для
  них — ошибка данных.
- `TagRegistry` — теги перечисляют предметы и другие теги (`#mineclone:x`).
  Свет, прочность, топливо, еда и горючее **вычисляются** из полей предмета:
  список, составленный руками, разошёлся бы с ними на первом же новом блоке.
  Файл для вычисляемого тега всё равно нужен — он даёт имя и псевдонимы
  поиску («ё» читается как «е»).
- `ItemStack` держит ссылку на `Item` и число; всё, что различает две стопки
  одного предмета, живёт в `ItemComponents` — неизменяемом наборе, по
  равенству которого и решается, сливаются ли стопки. Известные компоненты:
  `DAMAGE`, `UNBREAKABLE`, `CUSTOM_NAME`, `LORE`, `BLOCK_STATE`. Чужой
  компонент хранится байтами и переживает сохранение нетронутым — у каждого
  в потоке записана длина именно для этого.
- Изнашиваемый предмет не стопкуется вовсе (`max_stack` 1): две кирки с
  разным износом иначе теряли бы одну из прочностей.
- `LegacyItems` — мост к сейвам, где предмет лежал номером перечисления.
  Порядок его таблиц — история, а не решение, и меняться не имеет права.

## Checks and tuning

Recipes and smelting: [recipes.md](recipes.md). Drops from blocks, mobs and chests: [loot.md](loot.md).

`run-tests.ps1 -Only core,inventory,save,data` covers recipes, loot, tool wear, missing IDs and item persistence. See [world.md](world.md) for adding a block without renumbering saved IDs.

Knobs: [TUNING.md](../TUNING.md). Entry points: [PROJECT_MAP.md](../PROJECT_MAP.md).

## Armor and food components (2 October 2026)

Strict `armor` parsing validates slot/defense/toughness/knockback resistance; armor
stack size is one. `food` supports saturation, eat_seconds and bounded effects
(duration ≤86400 s, amplifier ≤255, chance 0..1). HOE appends to tool classes.
The earlier batch added 205 native tiles; new item/crop/vegetation sprites and
four armor model sheets retain their source hashes and offline importer receipts.
See [source provenance](../../assets/textures/source/roadmap-1-1.md).

SPIDER_NEST adds a block item and original tile 205, bringing the atlas to 206
native tiles. Its strict `ecology/lairs/spider_nest.json` definition supplies initial
population, capacity, daily growth, emission and territory; LairTable loads it
through DataPack. The nest has no survival crafting recipe; it is encountered
through eligible cave founding while Living World is enabled.
