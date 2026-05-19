# Поддержка нескольких миров — дизайн

Дата: 2026-05-20

## Обзор

Заменить единственный жёстко зашитый мир (`worldId = "world"`) на поддержку
нескольких миров: экран выбора мира для игры, создания и удаления, с
переключением миров «на лету» без перезапуска приложения.

## Текущее состояние

- `Game` хранит `final`-поля `world`, `mesher`, `loader`, `worldId`,
  создаваемые в конструкторе под один загруженный мир.
- `worldId` жёстко равен `SaveFormat.DEFAULT_WORLD_ID = "world"`.
- `startNewWorld()` обходит `final`-поля, записывая свежий `level.dat` и
  принудительно закрывая окно, чтобы следующий запуск загрузил новый мир.
- `MenuBackground` рендерит отдельный мир с `MENU_SEED` для фона меню —
  он независим от игрового мира.
- Раскладка сохранений: `saves/<id>/level.dat` +
  `saves/<id>/chunks/c.<x>.<z>.dat`. Все методы `SaveManager` принимают `id`,
  поэтому один экземпляр `SaveManager` обслуживает все миры.

## Цели

- Играть, создавать и удалять несколько миров из меню.
- Переключение миров без перезапуска приложения.
- Устойчивость к повреждённым файлам сохранений.
- Без регрессий целостности сохранений.

## Вне рамок (non-goals)

- Переименование миров (отложено; дизайн оставляет для этого место).
- Трекинг «последний раз сыгран» / сортировка по давности (отложено).
- Пользовательский ввод имени или seed (только авто-генерация).

## Дизайн

### 1. Идентификация мира

- **ID папки**: непрозрачный, безопасный для файловой системы —
  `world_<timestamp>`, где timestamp форматируется как
  `yyyy-MM-dd_HH-mm-ss` (например, `world_2026-05-20_15-30-05`).
  Генерируется один раз при создании из `System.currentTimeMillis()`.
  Уникален без сканирования `saves/`, удобен при ручном бэкапе.
- **Отображаемое имя (display name)**: человекочитаемое, хранится внутри
  `level.dat`. Авто-генерируется как `World N`, где N — наименьшее свободное
  целое среди имён существующих миров (вычисляется из списка миров, который
  всё равно сканируется).
- Старая папка `saves/world/` продолжает работать: у неё нет поля имени
  (формат v3), поэтому при загрузке её display name по умолчанию = `World`,
  и при ближайшем сохранении перезаписывается в v4 уже с именем.

### 2. Формат level.dat — LEVEL_VERSION 4, metadata-first

Метаданные (имя, seed) переносятся в начало файла, чтобы `listWorlds()` мог
завершать чтение рано, не распаковывая всю запись состояния игрока.

```
int    MAGIC
int    LEVEL_VERSION = 4
UTF    name            <- НОВОЕ, метаданные
long   seed            <- метаданные
double px, py, pz
double spawnX, spawnY, spawnZ
float  yaw, pitch
float  timeOfDay
int    selectedSlot
int    inventory.length
byte[] inventory
```

- Чтение: `if (version >= 4) name = in.readUTF();` затем `seed = in.readLong();`.
  Для `version < 4` поле `name` отсутствует — вызывающий код подставляет
  дефолт.
- `LastPlayed` намеренно опущен (вне рамок); при добавлении в будущем он
  встаёт сразу после `seed` как ещё одно ведущее поле метаданных под новым
  поднятием версии.

### 3. Дополнения SaveManager

- `WorldInfo` — `{ String id, String displayName, long seed, boolean corrupted }`.
- `loadWorldInfo(String id)` — читает только `MAGIC, VERSION, name, seed` из
  `level.dat` и останавливается. Обёрнут в try-catch; при любой ошибке
  возвращает `WorldInfo` с `corrupted = true`.
- `listWorlds()` — перечисляет подпапки `saves/`, содержащие `level.dat`,
  вызывает `loadWorldInfo` для каждой (каждая в своём try-catch, чтобы один
  битый файл не оборвал сканирование), возвращает `List<WorldInfo>`,
  отсортированный натуральным порядком по display name (`World 2` раньше
  `World 10`). Повреждённые миры присутствуют с `corrupted = true`.
- `deleteWorld(id)` — уже существует.
- `LevelData` получает поле `public final String name`.

### 4. Game: динамический жизненный цикл мира

Поля `world`, `mesher`, `loader`, `worldId`, `pendingLevel`, `worldSpawn`
становятся изменяемыми (не `final`). Конструктор больше не создаёт мир —
игра стартует сразу в состоянии `MENU`.

- `startWorld(String id)`:
  - `LevelData lvl = save.loadLevel(id)` (null = совсем новый мир).
  - `world = new World(seed)`, `mesher = new ChunkMesher(world)`,
    `loader = new ChunkLoader(world, mesher, save, id)`.
  - `worldId = id`.
  - Восстановить позицию игрока / камеру / инвентарь / `gameTime` /
    `worldSpawn` из `lvl` (логика, сейчас находящаяся в `run()`).
  - Прелоад спавн-чанков 3x3, `loader.drainLightFlood(...)`, поставить
    игрока на твёрдую землю.
  - `WaterSimulator.reset()`.
  - `beginLoadingToPlay()`.
- `createWorld()`:
  - `id = "world_" + formattedTimestamp()`.
  - Выбрать display name `World N`.
  - Сгенерировать случайный seed, вычислить спавн (`findDefaultSpawn`).
  - Записать свежий `level.dat` версии v4 (чтобы мир появился в списке и
    seed/имя сохранились).
  - `startWorld(id)`.
- `unloadWorld()` — вызывается при выходе в меню:
  - `saveAll()`, затем `save.flushAndAwait()`.
  - `loader.shutdown()` — останавливает фоновые пулы gen/mesh.
  - **В основном (GL) потоке**: уничтожить каждый `Mesh` в `chunkMeshes` и
    `waterMeshes` (`glDeleteBuffers`/`glDeleteVertexArrays` через
    `Mesh.destroy()`), затем `clear()` обеих карт. Это уже исполняется в
    основном потоке — `unloadWorld` вызывается только из пути обновления
    меню/паузы, никогда из потока loader.
  - `WaterSimulator.reset()`.
  - Обнулить `world`, `mesher`, `loader`, `pendingLevel`, чтобы GC мог
    освободить кучу (массивы чанков, данные мешей).

`startNewWorld()` и флаг `newWorldPending` удаляются — их заменяют
`createWorld()` / `unloadWorld()`.

### 5. Поток меню и UI

- Кнопки главного меню: **Singleplayer / Settings / Quit** (вместо
  Continue / New World).
- `Singleplayer` открывает экран выбора мира — под-режим меню
  `inWorldSelect` (аналог `inSettings`).
- `Hud.drawWorldSelect(...)`:
  - Заголовок «Select World».
  - Прокручиваемый список строк-миров; прокрутка колесом мыши через
    `input.getScroll()`, смещение зажимается (clamp). Каждая строка: display
    name (крупно), seed (мелкий текст), кнопка `Delete` (мелкая, справа).
    Повреждённые миры: строка показывает «Corrupted» и только кнопку
    `Delete`.
  - Снизу: кнопки `New World` и `Back`.
  - Возвращает `WorldSelectAction { String playId, String deleteId,
    boolean newWorld, boolean back }`.
- `MenuAction` получает `PLAY` (минимальное добавление; основное действие
  несёт структура `WorldSelectAction`).
- Удаление использует существующий модал `drawConfirm`: клик по `Delete`
  выставляет `pendingDeleteId` и показывает подтверждение; при подтверждении
  → `save.deleteWorld(id)` → пере-сканировать список.

### 6. Переходы состояний

```
MENU --Singleplayer--> MENU + inWorldSelect
MENU + inWorldSelect --клик по миру--> startWorld --> LOADING --> PLAYING
MENU + inWorldSelect --New World--> createWorld --> LOADING --> PLAYING
MENU + inWorldSelect --Back--> MENU
PLAYING --ESC--> PAUSED
PAUSED --Main Menu--> unloadWorld --> MENU
```

### 7. Сохранение при закрытии окна

Цикл `run()` уже завершается финальным сохранением; при динамических мирах
оно должно выполняться только если мир загружен:

```java
while (!window.shouldClose()) { ... }
if (world != null) { saveAll(); save.flushAndAwait(); }
cleanup();
```

GLFW при клике по «крестику» лишь выставляет флаг `glfwWindowShouldClose` —
процесс не убивается. Цикл `while (!window.shouldClose())` штатно
завершается, и код после цикла (финальное сохранение) исполняется в основном
потоке. Отдельный `glfwSetWindowCloseCallback` не нужен — он понадобился бы
только для *отмены* закрытия.

## Затрагиваемые файлы

- `SaveFormat.java` — `LEVEL_VERSION = 4`; при желании хелпер формата
  timestamp.
- `LevelData.java` — добавить поле `name`.
- `SaveManager.java` — `WorldInfo`, `loadWorldInfo`, `listWorlds`; чтение/
  запись v4 в `loadLevel`/`saveLevel`.
- `Game.java` — изменяемые поля мира, `startWorld`, `createWorld`,
  `unloadWorld`, обработка экрана выбора мира; удалить `startNewWorld` и
  `newWorldPending`.
- `Hud.java` — `drawWorldSelect`, рендер строк-миров, прокрутка; изменение
  кнопок меню.

## Риски и корнер-кейсы

- **GL-удаления вне основного потока** — снято: `unloadWorld` исполняется
  только в основном потоке.
- **Потоки loader, обращающиеся к освобождённому миру** — `loader.shutdown()`
  (`shutdownNow()`) вызывается до обнуления `world`; любой незавершённый
  `Ready` отбрасывается при очистке `chunkMeshes`.
- **Повреждённый level.dat** — try-catch на каждый мир в `listWorlds`;
  показывается как «Corrupted», остаётся удаляемым.
- **Пустая `saves/`** — экран выбора показывает только `New World` и `Back`.
- **Конкурентная запись чанков во время `unloadWorld`** —
  `save.flushAndAwait()` дренирует очередь записи чанков до
  `loader.shutdown()`.
