# Dedicated server

Updated: 2026-09-24. Paths in backticks are relative to the repository root.

`server/ServerMain` starts without GLFW, rendering or audio. `/stop` saves and
exits cleanly. An existing unreadable level refuses startup with exit code 2;
see [saves.md](saves.md). `gradlew.bat serverZip` bundles the jar, dependencies,
item data, launcher and a LAN-only example configuration. Test the extracted
archive with `tools/TestServerPackage.ps1`.

Current simulation limitation: chunks load around all connected players, but
world ticks, furnaces and mob simulation use the first player's center. Do not
claim per-player simulation coverage until the server roadmap task fixes it.

## Выделенный сервер (`com.mineclone.server`)

```powershell
java -cp "out;libs/*" com.mineclone.server.ServerMain [server.properties]
```

Мир живёт без игрока: тикает, сохраняется и ждёт. Раньше хозяин обязан был
сидеть в игре — вышел, и комната кончилась.

- Мир тикает `WorldSimulation` — **тем же классом, которым тикает игра**: два
  разных кода для одного и того же разошлись бы на первой же луже. Мир
  загружается вокруг всех участников, но `DedicatedServer.centre()` пока выбирает
  первого участника для радиуса тиков, печей и мобов. Это текущее ограничение,
  а не завершённая симуляция вокруг каждого игрока.
- **Ни одного импорта из `render`, `audio`, `lwjgl` и `game`** — это
  проверяется тестом, читающим исходники пакета. Одна строчка, списанная из
  `Game`, притащила бы окно.
- `ServerConfig` пишет `server.properties` сам при первом запуске и терпим к
  двум ловушкам Windows: метке порядка байтов в начале файла и обратным
  слэшам в пути. `ServerConsole` — `/list`, `/say`, `/save`, `/time`, `/room`,
  `/seed`, `/stop`; метка порядка байтов выбрасывается и оттуда — PowerShell
  ставит её перед первой строкой перенаправленного ввода.
- Мобы бьют и участников: пакет «хозяин ранил гостя» (`S_PLAYER_HURT`) пришёл
  вместе со снарядами, и прежнее ограничение всей игры по сети снято.

## Checks and tuning

`run-tests.ps1 -Only net,save` checks dedicated logic. `run-server-test.ps1` is the end-to-end LAN gate; `tools/TestServerPackage.ps1` checks extracted distribution boot and /stop.

Knobs: [TUNING.md](../TUNING.md). Entry points: [PROJECT_MAP.md](../PROJECT_MAP.md).
