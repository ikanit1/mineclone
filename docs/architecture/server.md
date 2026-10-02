# Dedicated server

Updated: 2026-10-02. Paths in backticks are relative to the repository root.

`server/ServerMain` starts without GLFW, rendering or audio. `/stop` saves and
exits cleanly. An existing unreadable level refuses startup with exit code 2;
see [saves.md](saves.md). `gradlew.bat serverZip` bundles the jar, dependencies,
item data, launcher and a LAN-only example configuration. Test the extracted
archive with `tools/TestServerPackage.ps1`.

Chunks load and simulate around all registered participants through
`WorldSession.centres()`. With no participants, the shared host focus is the
world spawn; lair emission still requires a nearby participant.

## Выделенный сервер (`com.mineclone.server`)

```powershell
java -cp "out;libs/*" com.mineclone.server.ServerMain [server.properties]
```

Мир живёт без игрока: тикает, сохраняется и ждёт. Раньше хозяин обязан был
сидеть в игре — вышел, и комната кончилась.

- Мир тикает `WorldSimulation` — **тем же классом, которым тикает игра**: два
  разных кода для одного и того же разошлись бы на первой же луже. Мир
  загружается вокруг всех участников; `WorldSession.centres()` передаёт их
  позиции в общий цикл тиков и печей, а мобы и их капы учитывают участников
  через ту же сессию. В пустом мире центром остаётся точка появления.
- **Ни одного импорта из `render`, `audio`, `lwjgl` и `game`** — это
  проверяется тестом, читающим исходники пакета. Одна строчка, списанная из
  `Game`, притащила бы окно.
- `ServerConfig` пишет `server.properties` сам при первом запуске и терпим к
  двум ловушкам Windows: метке порядка байтов в начале файла и обратным
  слэшам в пути. `ServerConsole` — `/list`, `/say`, `/save`, `/time`, `/room`,
  `/seed`, `/stop`; метка порядка байтов выбрасывается и оттуда — PowerShell
  ставит её перед первой строкой перенаправленного ввода.
- `upgrade-worldgen=false` по умолчанию: старые миры сохраняют свой генератор.
  При `true` V1-мир обновляется до V2 до загрузки чанков и открытия сети,
  после оригинального бэкапа и записи защитного журнала. Учитываются позиции
  и личные точки появления всех сохранённых игроков. Ошибка обновления даёт
  код выхода 2; сервер не продолжает запуск. V2-миры и новые миры не меняются
  от переключения этой настройки. См. [worldgen](worldgen.md).
- Мобы бьют и участников: пакет «хозяин ранил гостя» (`S_PLAYER_HURT`) пришёл
  вместе со снарядами, и прежнее ограничение всей игры по сети снято. С
  протоколом v8 он несёт вид урона, того, кто ударил, и точку удара: гость
  применяет его своим путём урона и отлетает от удара и взрыва, как игрок хозяина.

## Checks and tuning

The server's `WorldSession.ecology()` runs the same opt-in `LairRuntime` as the
graphical host: finite night emission, physical dawn returns, shared hostile
caps, deaths, broken hearts and loaded-cave founding. It restores protected
ecology and reserves lair body IDs before `ChunkLoader` adopts saved entities;
the ordinary world save captures the lair ledger and persistent spiders.
No rendering or client-only simulation is involved. Matching protocol 13 peers
are required for the appended nest block ID; packet shapes are unchanged.

The owner console accepts `/ecology [info|lair|on|off|advance <1-60>]` on the
simulation thread. `lair` prints nests in the world-spawn region: coordinates,
alive/destroyed state, destruction day, finite population/capacity and reserved
outside bodies. `on`, `off` and `advance` save the updated world; an unsupported
protected ecology section refuses these operations. The graphical owner's
diagnostic uses the player's region, and guests cannot invoke it. Behaviour and
acceptance evidence are tracked in
[spider-lairs validation](../changes/spider-lairs/validation.md).

`info` and `lair` inspect existing state without seeding a region or refreshing
field observations. Shared diagnostics include the stored three daily model
deltas and the nearest known nest/scar in the current and eight adjacent regions;
the location search is explicitly bounded and never generates terrain.

`run-server-test.ps1 -Stories` runs a NORMAL headless authority and two real
clients in one disposable valley. It checks ordinary nest/silk/trail/crop snapshots
and finite forest recovery, then independently reads saved terrain, histories and
healthy reserved body IDs before and after `/stop`. Complete nest-clearing and
thirty-day absence journeys run separately in actual Game and deterministic tests;
see [combined evidence](../changes/living-world-stories/validation.md).

`run-tests.ps1 -Only net,save` checks dedicated logic. `run-server-test.ps1` is the end-to-end LAN gate; `tools/TestServerPackage.ps1` checks extracted distribution boot and /stop.

Knobs: [TUNING.md](../TUNING.md). Entry points: [PROJECT_MAP.md](../PROJECT_MAP.md).
