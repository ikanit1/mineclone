# Save System + Main/Pause Menu Revamp — Design

Date: 2026-05-16
Status: Approved (pending written-spec review)

## 1. Overview

Add world persistence to the voxel engine and rebuild the main/pause menus
around it. UX is deliberately simple now (a single world, "Continue" / "New
World"), but the on-disk layout and `com.mineclone.save` API are designed so
that adding a Minecraft-Java-style multi-world list later is an additive UI
change, not an engine rewrite. The main menu gets a living 3D background, a
layered pixel title, and stone buttons.

There is currently **zero persistence**: `Game` does `new World(1337L)` with a
hardcoded seed; nothing survives a restart, and in-game settings reset every
launch.

## 2. Goals / Non-goals

**Goals**
- Persist the world (player edits), player state, time of day, and global
  settings across launches.
- Per-chunk save files so the architecture scales to an effectively infinite
  streamed world.
- Autosave (pause / quit / periodic) + manual save, with dirty chunks flushed
  on unload so memory stays bounded.
- Restyle main menu (orbiting 3D world background, layered title, stone
  buttons) and integrate save controls into main + pause menus.

**Non-goals (deferred)**
- Multiple named worlds / world-list screen (layout is ready for it; UI is
  not built).
- Seed input UI ("New World" uses a random seed for now).
- Per-world settings (settings are global, like Minecraft `options.txt`).
- Networking, mob entities, inventory beyond the existing hotbar.

## 3. Locked decisions

| Topic | Decision |
|---|---|
| Save UX | Single world. Main menu: Continue (enabled iff a save exists) / New World / Settings / Quit. |
| World id | Directory `saves/<id>/`, default id `world`. SaveManager is keyed by id so multi-world is additive later. |
| Chunk persistence | **Whole-chunk snapshot** of any chunk modified after generation: full `blockIds` + `metas` arrays, GZIP. Not a per-block delta. |
| Light | Never persisted. Recomputed deterministically from restored blocks on load. |
| Water | Restored exactly from the snapshot (blocks + meta levels). Simulator treats restored state as resting; it does NOT re-flow on load. |
| Autosave | On entering PAUSED, on window-close/quit, periodic (~120 s) in PLAYING, plus a manual "Save" button. |
| Chunk flush | A dirty chunk is written to disk when it unloads (leaves render radius), on a background thread. |
| New World | Confirmation dialog (old world deleted), random seed. Seed input deferred. |
| Settings | Global `options.dat`, loaded at startup, saved on Settings "Done". Reachable from main menu and pause. |
| Menu background | Dedicated menu world on a curated fixed `MENU_SEED`, generated at startup; camera orbits. |
| Menu camera | Curated seed AND camera samples terrain-top height at the orbit center; `camY = terrainTop + offset`, pitched slightly down. Never clips even if seed/generator change. |
| Menu visual | Direction A: live orbiting 3D world + dark vignette; layered pixel title; stone-textured beveled buttons. |

## 4. On-disk format

```
saves/
  world/
    level.dat                 # world header
    chunks/
      c.0.0.dat               # whole-chunk snapshot for chunk (0,0)
      c.1.-3.dat
options.dat                   # global settings (sibling of saves/, not per-world)
```

All files: `GZIPOutputStream` over `DataOutputStream`. Every file begins with
`int MAGIC` then `int FORMAT_VERSION` so the format can evolve without breaking
old saves (unknown/newer version → fail gracefully with a logged message; the
menu treats an unreadable save as "no save").

- **level.dat**: `MAGIC, FORMAT_VERSION, seed (long), playerX/Y/Z (double),
  yaw (float), pitch (float), timeOfDay (float, = current `gameTime`),
  selectedHotbarSlot (int)`.
- **c.X.Z.dat**: `MAGIC, FORMAT_VERSION, blockIds (byte[32768]),
  metas (byte[32768])` where `32768 = Chunk.SIZE_X*SIZE_Y*SIZE_Z`
  (16·128·16). Index order matches the engine's existing chunk indexing.
  Raw 64 KB; GZIP on run-heavy voxel data ≈ 1–5 KB typical. Only chunks
  modified after generation are ever written.
- **options.dat**: `MAGIC, FORMAT_VERSION, renderDist (int), fov (int),
  brightness (float), volume (float)`.

## 5. `com.mineclone.save` package

New package; all disk I/O isolated here. The engine calls a clean API and
never touches file layout.

- `LevelData` — record/POJO mirroring level.dat fields.
- `ChunkSnapshot` — `{ int cx, cz; byte[] blockIds; byte[] metas; }`.
- `Options` — `{ int renderDist, fov; float brightness, volume; }`.
- `SaveManager`:
  - `boolean hasSave(String worldId)`
  - `LevelData loadLevel(String worldId)` / `void saveLevel(String worldId, LevelData)`
  - `ChunkSnapshot loadChunk(String worldId, int cx, int cz)` (null if absent/unreadable)
  - `void saveChunk(String worldId, ChunkSnapshot)`
  - `void deleteWorld(String worldId)`
  - `Options loadOptions()` / `void saveOptions(Options)`
  - Reads/writes that can be deferred (chunk writes) run on a background
    single-thread executor; `saveLevel`/`saveOptions` are small and synchronous
    at save points.

## 6. World / Chunk / ChunkLoader integration

`Chunk` gains two booleans:
- `populated` — set true once initial generation (terrain + trees) for that
  chunk is complete.
- `dirty` — set true by `World.setBlock(...)` only when `chunk.populated`.
  This is the *only* tracking needed: snapshotting the whole array means we do
  not care *which* edit (player, command, water sim) changed it — just that
  something did after generation. The previously-proposed per-block `delta`
  map and `transient` setBlock path are removed.

**Chunk generation/load path (in `ChunkLoader`):**
1. Generate terrain + trees from `seed` (existing two-pass logic).
2. `chunk.populated = true`.
3. `SaveManager.loadChunk(worldId, cx, cz)`:
   - present → overwrite the chunk's block+meta arrays with the snapshot
     (this restores player builds, water blocks, water levels exactly), leave
     `dirty = false`.
   - absent → keep generated terrain.
4. Recompute skylight + block light for the chunk (existing
   `computeSkyLight()` / propagation) and mark edge-adjacent neighbours dirty
   for light, exactly as generation already does. Light is a pure function of
   the now-final blocks, so this is deterministic and safe.
5. Water: the simulator must NOT re-flow restored water on load. Restored
   `WATER` / flow cells (level encoded in meta) are the resting state; the
   simulator only acts on subsequent runtime edits. (Implementation: load path
   does not enqueue restored water cells into the simulator's active set.)

**Chunk unload path:** when a chunk leaves render radius and is evicted, if
`dirty`, build a `ChunkSnapshot` from its arrays and `SaveManager.saveChunk`
on the background executor before discarding. This bounds memory for an
infinite world (no global delta kept in RAM).

`Game` constructs `World` from the loaded/seeded `LevelData` instead of the
hardcoded `1337L`.

## 7. Autosave & lifecycle

`saveAll(worldId)`:
- write `level.dat` from current player/world state;
- snapshot + write every loaded chunk with `dirty == true`, then clear its
  `dirty` flag.

Triggers:
- transition PLAYING → PAUSED;
- window-close / Quit (Quit performs `saveAll` first, then closes);
- a ~120 s periodic timer while in PLAYING;
- pause-menu "Save" button → `saveAll` + a brief "Сохранено" toast.

Chunk writes during normal play happen at unload (Section 6). `saveAll` is for
explicit/periodic full flushes of currently-loaded dirty chunks.

## 8. Menu world & camera

No new `State`; extend `State.MENU`.
- At startup `Game` creates `menuWorld = new World(MENU_SEED)` and a small
  `ChunkLoader` covering a 3–4 chunk radius around the orbit center.
- `renderMenu()` renders `menuWorld` with the existing `chunkShader`. Camera
  orbits: `angle += dt * 0.05`; position on a circle of radius R around the
  orbit center; `camY = terrainTopAt(centerX, centerZ) + OFFSET`; look vector
  aimed at the center, pitched slightly down. Sampling terrain height makes it
  robust if the seed or generator changes.
- A translucent radial vignette quad is drawn over the world, then the UI.
- "Continue" loads `saves/world`; disabled (greyed) when `!hasSave`.
  "New World" → confirm dialog → `deleteWorld` → random seed → enter PLAYING.

## 9. Menu UI: title, buttons, screens

- **Title "MINECLONE"**: large scaled bitmap font via existing
  `Font`/`TextRenderer`, drawn in layers — dark outline (4 offset passes) +
  hard drop shadow + light-gold main fill. No new image asset.
- **Buttons**: generate `assets/textures/blocks/ui_button.png` and
  `ui_button_hover.png` (stone, bevelled). `UiRenderer` gains a textured-quad
  path (it is currently colour-quad only). A button = textured quad + centered
  label; hover swaps to the hover texture. Width ~260, centered, vertical
  stack.
- **`Hud.MenuAction`** extends to: `NONE, CONTINUE, NEW_WORLD,
  NEW_WORLD_CONFIRM, SAVE, RESUME, SETTINGS, SETTINGS_BACK, QUIT`.
  - `drawMainMenu` → title art + [Continue (iff save) / New World / Settings /
    Quit].
  - `drawPauseMenu` → [Back / Save / Settings / Quit].
  - new `drawConfirm(text)` → Yes/No, used by New World.
- Quit path always runs `saveAll` before closing the window.

## 10. Settings persistence

`Game` at startup: `options = SaveManager.loadOptions()` (defaults if absent)
and apply to `renderRadius`, `fovDegrees`, `brightness`, `volume`. The
existing `Hud.drawSettings(float[] values)` is unchanged; on "Done" Game maps
`values` back and calls `SaveManager.saveOptions(...)`. Reachable from the new
main-menu Settings button and the existing pause Settings.

## 11. Isolation & boundaries

- New package `com.mineclone.save` (`SaveManager`, `LevelData`,
  `ChunkSnapshot`, `Options`) owns all serialization/file layout. Engine
  depends only on its API.
- `World`/`Chunk` changes are minimal: `populated`, `dirty`, snapshot
  get/apply helpers; no file knowledge.
- UI changes are local to `Hud` plus the new textured-quad path in
  `UiRenderer`.
- Each unit is independently understandable: SaveManager (disk ↔ DTOs),
  World/Chunk (in-memory state + dirty tracking), Hud/menu (presentation).

## 12. Accepted trade-offs

- **Generator-change seam**: whole-chunk snapshots freeze generated terrain
  for *touched* chunks. Changing the world generator later leaves modified
  chunks on old terrain while untouched chunks regenerate → a visible seam.
  Same class of issue as Minecraft version changes. Accepted for this project;
  recorded here so it is a conscious decision, not a surprise.
- **Snapshot size vs delta**: a touched chunk stores its generated terrain
  too, not just edits. Mitigated by GZIP (~1–5 KB) and writing only dirty
  chunks. Chosen for correctness/simplicity: it eliminates the cross-chunk
  water re-simulation ordering hazard and the `transient`/`delta` bookkeeping.

## 13. Verification (no test suite exists)

Manual, since the project has no tests:
- Build (`javac` per CLAUDE.md), run.
- Place/break blocks, build a structure spanning ≥2 chunks; trigger autosave;
  quit; relaunch; Continue → structure and player position/look intact.
- Water: place a water source, let it spread, save, quit, reload → water is in
  the same resting shape, with no re-flood, including across a chunk border.
- Change a setting, quit, relaunch → setting persisted.
- New World with an existing save → confirm dialog → fresh random world.
- Menu: world visibly orbits, camera never inside terrain, title + stone
  buttons render, Continue greyed when no save.

## 14. Future scaling (informational, not in scope)

- Multi-world: enumerate `saves/*`, add a world-list screen, pass chosen id to
  `SaveManager` (already keyed by id). No engine changes.
- Seed input: add a text field to the New World flow; `level.dat` already
  stores the seed.
- Region packing (Anvil-style N×N chunks per file) can replace per-chunk
  files behind the unchanged `SaveManager` API if file count ever matters.
