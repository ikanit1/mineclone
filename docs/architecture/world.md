# World, chunks and block changes

Updated: 2026-09-25. Paths in backticks are relative to the repository root.

`world/World.java` owns loaded chunks; `world/Chunk.java` owns block data, light,
metadata, containers and meshes. Read [chunk publication](chunk-publication.md)
before changing generation, restoration or worker access.

To add a block: append its enum constant to `world/BlockType.java`, define its
properties in `world/BlockProps.java`, its shape in `world/shape/Shapes.java`
and its behaviour in `world/behavior/Behaviors.java` (all three switches are
exhaustive, so a block without them does not compile; see
[blocks](blocks.md#shapes-blk-02)), wire PNG tile names in `render/TextureAtlas.java`,
then add the item JSON and recipes/tags under `assets/data/mineclone/`. Existing
block IDs are saved ordinals: never insert/reorder/remove the existing prefix.
`BlockOrdinalTests` freezes both IDs and pre-migration properties. Read
[items and data](items-data.md) and [saves](saves.md) before adding persistence.

Chunks are generated detached, restored from a verified save, then published once.
After publication, container mutation belongs to the main simulation thread;
`-Dmineclone.checkThreadOwnership=true` enforces this during diagnostics.

## World & chunks

- `World` owns a `ConcurrentHashMap<Long, Chunk>`. Chunks are 16×128×16 (`Chunk.SIZE_*`).
- `Rivers` размывает колонну рельефа ниже уровня моря — прямо внутри прохода
  рельефа, до всего остального: дальше все проходы читают массив высот, и
  река обязана быть в нём заранее. Реки и озёра это **высота, а не блок**:
  воду наливает то же правило `y <= SEA_LEVEL`, что наполняет океан, а
  песчаное дно даёт правило пляжа. Размыв затухает с высотой — иначе
  двумерная маска режет горы отвесным каньоном.
  ADR: `knowledge/decisions/rivers-and-lakes.md`.
- **Растительность заглядывает за границу чанка** на `LEAF_REACH` блоков:
  крона шире прежнего отступа от края, и дерево соседа обязано дотянуться
  листвой к нам. Писать в чужой чанк нельзя (генерация идёт в фоновых
  потоках), поэтому дерево соседа пересчитывается заново — отсюда
  `columnHeight()` отдельной функцией: высота за границей должна совпасть
  бит в бит, иначе дерево повиснет в воздухе. Отмена дерева пещерой
  проверяется одним предикатом `Caves.isCave` для своих и чужих колонн:
  реши мы её по блоку, у соседа ответ был бы другим и крона оказалась бы
  нарисована только с одной стороны границы.
- `World.generateDetached()` is **four-pass**: рельеф (высоты в `int[][]`) → пещеры
  (`Caves`) → руды (`OreGenerator`) → деревья. Порядок не переставляется:
  резать после растительности — деревья повиснут над своей пещерой, ставить
  руду до выреза — половина жил уедет в пустоту.
- `Caves` режет тоннели пересечением двух 3D-шумов (`a² + b² < t²`): один шум
  с порогом даёт разрозненные пузыри, а не связные ходы. Дно океана защищено
  (`SEABED_GUARD`), на суше вырез доходит до поверхности — так появляются входы.
- `OreGenerator` раскладывает жилы случайным блужданием, зажатым в свою
  высотную полосу, и только вместо камня. Детерминирован по (сид, чанк).
- `Structures` ставит руины, хижины, обелиски и подземелья после растительности, по
  шаблонам из символов. Постройка целиком живёт внутри одного чанка:
  генерация пишет только в свой чанк, и строение на границе обрезалось бы
  посередине. Пятно расчищается от деревьев и подсыпается грунтом.
- Biomes: `BiomeProvider` (3 climate noises → Whittaker table, 4×4-block quantisation, 5×5 height-param smoothing). Biomes are never persisted — recomputed from the seed.
- `ChunkLoader` streams chunk generation onto a background thread pool; results drip back as `Ready` records which `Game` uploads to GPU.
- `Chunk.computeSkyLight()` runs a column-flood BFS then one self-weighted blur pass to soften gradients.
- `World.setBlock()` сначала зовёт `onRemoved` уходящего блока — сундук и печь
  высыпаются там, кто бы их ни убрал, — а после правки ставит соседей, которым
  нужна опора, в очередь `NeighbourUpdates` (BLK-03, [blocks](blocks.md#behaviours-blk-03)).
  И то и другое включается, только когда мир симулирует сессия
  (`World.simulate`): у зеркала гостя стока предметов и очереди нет.
- `World.scheduleTick()` будит блок в клетке через N тиков мира; тики живут в
  `ScheduledTicks` чанка, исполняет их `WorldSession.tickScheduled()` после
  случайных тиков, сохраняет секция чанка `ticks` (BLK-04,
  [blocks](blocks.md#scheduled-ticks-blk-04)). Восстановленный чанк отдаёт свои
  тики симуляции при публикации (`World.tickArrivals`), не патчем после неё.
- `World.setBlock()` пересчитывает свет и будит воду **только когда это нужно**:
  `WaterSimulator` будится при смене проходимости или когда в замене участвует
  вода (снятие источника проходимость не меняет, но бассейн обязан стечь).
  Соседние чанки на границе помечаются грязными.
- **Небесный свет правится точечно.** Смена прозрачности зовёт
  `Chunk.updateSkyLightAt` — ограниченный BFS вокруг правки, а не полную
  перезаливку чанка (та стоила 5–15 мс в кадре и была главным рывком при
  копании). Свет хранится дважды: `skyRaw` — результат BFS, `skyLight` —
  размытая копия, которую читают меш и игра; продолжить заливку по сглаженным
  числам нельзя, размытие необратимо. Прямой столб неба идёт вниз без
  затухания, поэтому и гаснет, и возвращается целиком. Прозрачность ведётся
  маской `clearMask` рядом с `solidMask`, а условие пересчёта спрашивает ту же
  `Chunk.letsSkyThrough`, что и сама заливка. ADR:
  `knowledge/decisions/incremental-sky-light.md`.
- **Чтение блока не берёт монитор.** `PaletteStorage` отдаёт читателю один
  `volatile`-снимок «палитра + слова + разрядность», слова читаются через
  `VarHandle` в режиме opaque. Прежний `synchronized get` стоил 16.3 нс на
  ячейку (с JDK 15 смещённые блокировки выключены, каждый вход в монитор —
  настоящий CAS); стало 1.9 нс. Меш чанка читает блоки сотни тысяч раз.
- **Спокойная вода не обходится заново.** `WaterSimulator` осматривает чанк
  один раз после загрузки (`scanned`), а `trySpread` считает карту стока только
  когда есть куда течь. Раньше тик воды над океаном стоил 11 мс и повторялся
  пять раз в секунду. ADR: `knowledge/decisions/water-simulation-cost.md`.
- `BlockTicker` — случайные тики блоков в радиусе 4 чанков вокруг игрока
  (3 позиции на чанк, 4 Гц). На нём живут: рост и вырождение травы, рост
  кактуса, распад оторванной листвы, горение и распространение огня,
  накопление и таяние снега.
- Слои переменной высоты (`SNOW_LAYER`) хранят толщину в meta и рисуются
  через `ChunkMesher.emitLayer` — тот же приём, что у воды. Менять только meta
  надо через `World.setSnowLevel`: `setBlock` рано выходит на неизменном типе.
- **Блоки среды** (`ICE`, `THIN_ICE`, `MUD`, `ASH`, `MOSSY_COBBLE`, `LAVA`,
  `OBSIDIAN`, `ROPE`, `CHAIN`, `WEB`, `JOURNAL`) добавлены в конец
  `BlockType` и намеренно переиспользуют уже существующие тайлы атласа: id
  остаются безопасными для сейвов, и ни один сгенерированный плейсхолдер не
  утечёт в мир. Лёд живёт на тиках: вода в мороз становится `THIN_ICE`, тот
  за несколько тиков дозревает до `ICE`, а `ICE` в тепле тает обратно в воду —
  иначе замёрзшее озеро превращалось бы в яму.
- `FluidThermodynamics` — общие правила для воды и лавы: вязкость (лава течёт
  вшестеро медленнее), и что получается при встрече. Солидифицируется всегда
  клетка лавы, а что из неё выйдет — решает геометрия: сбоку булыжник, вода
  сверху на источник — обсидиан, любая другая вертикальная встреча — камень.
  Одна таблица на тики, частицы и тесты.
- `LavaSimulator` — лава своим тиком, раз в `TICK_INTERVAL` (1.5 с) и на три
  клетки в стороны: с темпом воды лава перестаёт читаться как расплав. Падение
  живёт дальше стока, а чанк, как и у воды, осматривается один раз после
  загрузки. Встреча с водой уходит в `FluidThermodynamics`.
- `FallingBlocks` — песок и гравий падают по событию, а не обходом мира каждый
  кадр: колонна ставится в очередь, когда из-под неё убрали опору. Потолок
  `MAX_ACTIVE` и `STARTS_PER_FRAME` держат обвал большой стены в рамках кадра,
  а падающий блок рисуется настоящим кубом (`FallingBlockRenderer`) — из меша
  чанка он на это время уходит.
- `TerrainDeformation` — след остаётся не только декалью, но и в самом мире:
  снежный слой под ногой проседает по meta, а мокрая земля становится грязью.
- `StructureStability` — связность тяжёлой постройки: BFS с потолком
  `SEARCH_LIMIT` проверяет, доходит ли компонента из камня и руды до грунта.
- `RopeSimulation` — лёгкий верле для висящих верёвок и цепей: хранятся
  текущие и прошлые позиции, поэтому инерция есть без явных скоростей.
- `ItemEntity` — предмет на земле: подпрыгивает, покачивается, вращается, а
  ближе `MAGNET_RANGE` летит к игроку, если в инвентаре есть место.
  Одинаковые стопки рядом сливаются — сотня булыжников из разбитой стены это
  пара стопок, а не сотня сущностей. В сейв чанка едет как `DroppedItem`:
  выпавшее из разбитого сундука не имеет права исчезнуть оттого, что игрок
  вышел из игры.

## The game package: what `Game` hands out (SIM-08)

`game/Game.java` runs the frame: window and state machine, world lifecycle,
rendering passes and the HUD. What does not need the frame lives beside it, in
the same package, so two people can change the bow and the chat without
editing one file:

| Class | What it owns | How it reaches the game |
|---|---|---|
| `InteractionController` (BLK-03) | dig, place, use, pipette | `InteractionController.Host` |
| `CommandProcessor` | `/time`, `/weather`, `/tp`, `/gamemode`, `/fill` and the rest; the help lines | `CommandProcessor.CommandTarget` — `CommandProcessorTests` runs every command against a fake, without a window |
| `GameNetContext` | the `NetContext` a `Multiplayer` session sees: the player's pose and record, remote block edits and actions, containers from the host, chat, knockback | package-private members of `Game`, read on every call |
| `WeaponController` | the bow's draw, the charged throw, what leaves the hand | package-private members of `Game`; `Game` draws the arc from `bowDraw()` and `throwCharge()` |
| `FootstepFeedback` | steps, footprints, kicked snow, landings, other players' steps; the walked distance the view bob runs on | package-private members of `Game` |
| `AmbientEffects` | cave, rain, storm and underwater beds, the room's echo, stream and lava loops, fish, leaves, fireflies, torch smoke, breath in the cold | package-private members of `Game` |

The last four were moved whole — the method bodies are the ones `Game` had —
and read `Game`'s current world, player and clock through fields that are now
package-private instead of private. They hold only their own timers and
counters. A test or tool that used to reflect on a moved method goes through the
helper: `InventorySafetySmoke` throws through `weapons`, `CreativeGameSmoke`
still calls `executeCommand`, which `Game` keeps as a one-line forward.
`Game.java` is 5456 lines after this pass (6850 before BLK-03).

## Checks and tuning

`run-tests.ps1 -Only core,chunk` covers block properties, lighting, publication races, restored deltas and containers. Use `worldgen` for terrain and `save` for format safety.

Knobs: [TUNING.md](../TUNING.md). Entry points: [PROJECT_MAP.md](../PROJECT_MAP.md).
