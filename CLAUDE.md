# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

No Maven/Gradle wrapper — uses a self-contained PowerShell script that downloads LWJGL + JOML jars on first run:

```powershell
.\run.ps1          # compile + launch
.\run.ps1 --regen-atlas   # force-regenerate the procedural texture atlas
```

**Compile only (no launch):**
```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

No tests exist. The game has no test suite.

## Architecture

### Entry point & game loop
`Main` → creates `Window` (GLFW+OpenGL 3.3 core) → `Game.run()`. The game loop is in `Game` and ticks at variable delta-time with a 50 ms cap. State machine: `MENU → PLAYING ↔ PAUSED`.

### World & chunks
- `World` owns a `ConcurrentHashMap<Long, Chunk>`. Chunks are 16×128×16 (`Chunk.SIZE_*`).
- `World.generate()` is **two-pass**: terrain first (stores heights in `int[][]`), then trees. This matters — single-pass overwrites leaves.
- `ChunkLoader` streams chunk generation onto a background thread pool; results drip back as `Ready` records which `Game` uploads to GPU.
- `Chunk.computeSkyLight()` runs a column-flood BFS then one self-weighted blur pass to soften gradients.
- `World.setBlock()` calls `computeSkyLight()` on the modified chunk and marks edge-adjacent neighbours dirty.

### Rendering pipeline (all in `render/`)
Each frame in `Game.render()`:

1. **Opaque chunks** — `chunkShader` (CHUNK_VERTEX/FRAGMENT in `Shaders`). Per-vertex light (`aLight`) encodes AO × face-directional × sky-fraction. `centroid out vec2 vUv` prevents MSAA UV extrapolation bleeding. Light shaped with `pow(l, 0.75)` before texture multiply.
2. **Block outline** — `BlockOutline` draws 12-edge wireframe around the raycast hit.
3. **Particles** — `ParticleSystem` camera-facing quads spawned on block break.
4. **HUD** — `Crosshair`, `Hud` (hotbar, menus, debug overlay via `Font`/`TextRenderer`/`UiRenderer`).

### Meshing
`ChunkMesher.buildData()` runs on background threads (returns `MeshData`, uploaded on main thread). Per-vertex AO uses `AO_TABLE = {1.0, 0.86, 0.74, 0.62}`. Face culling rule: a face is drawn when the neighbour is `AIR`, `transparent`, or `cutout` (the `cutout` case covers leaves — they have holes but are not `transparent`).

### Texture atlas
`TextureAtlas` procedurally generates a 256×256 atlas (16 tiles × 16 px each) and saves it to `assets/atlas.png`. Pass `--regen-atlas` or delete the file to regenerate. Filter is `GL_NEAREST` (no mipmaps — the atlas tiles are edge-to-edge and mipmaps cause bleed).

### Shaders
All GLSL is inlined as Java string literals in `Shaders.java`. There are five programs: `CHUNK`, `LINE` (block outline), `PARTICLE`, `TEXT`, `UI`.

### Sound
`SoundEngine` wraps OpenAL. `Sounds` maps `BlockType` → OGG file lists under `assets/sounds/`. Sounds play one-shot with randomised pitch/volume.

## Knowledge Base

Obsidian vault: `e:\mineclone\knowledge`

Структура заметок:
- `architecture/` — архитектура системы
- `decisions/` — ADR-решения (почему сделано именно так)
- `bugs/` — известные проблемы и их корневые причины
- `features/` — описания фич и планы
- `_COMMUNITY_*.md` — обзоры 17 архитектурных кластеров кодовой базы (сгенерированы graphify)
- `graph.canvas` — визуальная карта проекта с группировкой по кластерам

## Knowledge Graph (graphify)

Граф знаний: `e:\mineclone\graphify-out\`

- `graph.json` — машиночитаемый граф (511 узлов, 1296 рёбер, 17 сообществ)
- `graph.html` — интерактивная визуализация (открыть в браузере)
- `GRAPH_REPORT.md` — аудит-отчёт с god-нодами и неожиданными связями

**17 архитектурных кластеров:**
| ID | Кластер | Узлов |
|---|---|---|
| 0 | Sound System | 89 |
| 1 | Chunk & World Management | 71 |
| 2 | Render Pipeline | 54 |
| 3 | HUD & UI | 48 |
| 4 | Window & OpenGL | 41 |
| 5 | Game Core & Save | 41 |
| 6 | Block Types & World Data | 39 |
| 7 | Input System | 30 |
| 8 | Game Features Design | 25 |
| 9 | Architecture Docs & ADRs | 25 |
| 10 | Sound Engine Core | 15 |
| 11 | Held Item Renderer | 12 |
| 12 | Terrain Noise | 8 |
| 13 | Mesh Primitives | 6 |
| 14 | Shaders | 3 |
| 15 | Chunk Persistence | 3 |
| 16 | Ground Movement Plan | 1 |

**`Game`** — главный хаб (bridge node): соединяет Sound System, Chunk Management, Render Pipeline, HUD и Game Core.

Перед тем как предлагать решение по архитектуре или рендерингу, читай релевантные заметки из `knowledge/` и `graphify-out/GRAPH_REPORT.md`. Если фиксируешь нетривиальное решение — предложи добавить ADR в `knowledge/decisions/`.

Запуск с доступом к vault и графу:
```powershell
claude --add-dir "E:\mineclone\knowledge" --add-dir "E:\mineclone\graphify-out"
```

## Key constants & tuning knobs
| Location | Constant | Effect |
|---|---|---|
| `Game` | `RENDER_RADIUS = 6` | Chunk draw distance |
| `ChunkMesher` | `FACE_LIGHT[]` | Per-face directional brightness |
| `ChunkMesher` | `AO_TABLE[]` | AO corner darkening curve |
| `Game.render()` | `uAmbient = 0.22f` | Shadow floor |
| `Shaders.CHUNK_FRAGMENT` | `pow(l, 0.75)` | Gamma/tone curve |
| `World` | `SEA_LEVEL = 50` | Water/sand threshold |
