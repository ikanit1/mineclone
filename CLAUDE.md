# Working in Mineclone

Read the subsystem document below before editing it. The current plan is
[docs/ROADMAP_1_1.md](docs/ROADMAP_1_1.md); historical design files are background,
not evidence that a feature works today. Java 17, LWJGL 3.3.6, JOML and JLayer.

## Build and run

Run from the repository root (Windows PowerShell 5 or PowerShell 7):

```powershell
.\run.ps1                    # dependencies + generated version + compile + game
.\run.ps1 -CompileOnly       # same compilation, no GLFW window or game process
.\run-tests.ps1              # compile and run all headless regression checks
.\run-tests.ps1 -Only save,net
.\run-tests.ps1 -Skip chunk -List
.\tools\TestRunnerCli.ps1    # after test compilation: validate CLI and failure exit
.\run-net-test.ps1           # real host + guest, LAN; requires current out/
.\run-server-test.ps1        # dedicated server + real game clients
.\gradlew.bat test           # invokes the same TestMain runner
.\gradlew.bat mineTests -PtestOnly=save
.\gradlew.bat serverZip
.\tools\TestServerPackage.ps1
java tools/GenProjectMap.java
```

The Gradle 9.2.0 wrapper is checked in. `check`, `test` and `mineTests` run the same
zero-dependency suite; no JUnit is required. PowerShell builds use `out/` and
`out-test/`, Gradle uses `build/`. `gradle.properties` is the single version and
library-version source; both build paths generate `core.BuildInfo` from its
`.java.template`. Do not compile only tracked Java files and omit generated BuildInfo.
HUD, title and startup logs use that version. `jpackage` / `portableZip` produce
the desktop package; `serverZip` includes item data and a LAN-only configuration.

[TESTING](docs/TESTING.md) explains categories, reports and validation boundaries.
[TOOLS](docs/TOOLS.md) catalogs previews, importers, diagnostics and outputs.
[Benchmarks](docs/architecture/benchmarks.md) explains the eleven real workloads
and the boundaries of their measurements.
Build outputs, logs and scratch artifacts belong under ignored `out*`/`build/`
folders; check `.gitignore` before creating a new top-level output directory.

## Rules that protect worlds and multiplayer

- `BlockType` ordinals are persisted IDs. Append new constants; never reorder,
  insert into, or remove the existing prefix. `BlockOrdinalTests` freezes IDs and
  properties. Shared block policy belongs in `BlockProps`, not new scattered switches.
- New persistent features get an explicit save section with a bounded payload,
  migration/read policy and round-trip coverage. Preserve unknown sections/IDs.
  Missing, corrupt and newer-version data are different results. Read [saves](docs/architecture/saves.md).
- Generate -> restore saved state -> publish a chunk. Published containers belong
  to the simulation thread. Do not publish terrain and later patch saved data on
  a worker. Read [chunk publication](docs/architecture/chunk-publication.md).
- Host state is authoritative. `C_` packets are commands, `S_` packets snapshots,
  `X_` packets shared events. Incompatible formats require `NetProto.VERSION` changes
  and matching peers. Item/container changes need conservation and ownership checks.
- Keep dedicated-server code independent of GLFW/OpenGL/audio. Console diagnostics
  should use Latin text for predictable Windows logs; player-visible text may be localized.
- Preserve user saves. Use temporary directories for tests. Destructive maintenance
  needs a recoverable backup and clear authorization; ordinary repairs stay autonomous.
- A test count, compilation or generated screenshot is not proof of gameplay. Run
  the relevant real render/input/network path, and state what remains unverified.
  LAN success requires both host and guest PASS markers. Photon uses cloud slots
  and remains explicit opt-in (`run-net-test.ps1 -Photon`).
- Update the relevant architecture document and test registration when a subsystem
  changes. Update the changelog for user-visible changes. Regenerate PROJECT_MAP
  after adding/removing top-level classes or changing their Javadoc purpose.

## Package and subsystem map

| Package / boundary | Read before changing |
|---|---|
| `Main`, `core` | Window lifecycle, input and generated BuildInfo; [UI](docs/architecture/ui-menus.md) |
| `game` | Game lifecycle, HUD and frame orchestration; [world](docs/architecture/world.md) |
| `world` | Chunks, blocks, fluids, crafting; [world](docs/architecture/world.md) |
| `world` generation | Biomes, caves, rivers, ores, structures; [worldgen](docs/architecture/worldgen.md) |
| `world/entity`, `world/ai` | Mobs, physics, navigation; [combat](docs/architecture/combat-mobs.md) |
| `sim` | Shared world simulation, participants and WorldClock; [simulation](docs/architecture/simulation.md), [server](docs/architecture/server.md) |
| `render` | GPU lifetime, meshing, shadows, water/post; [rendering](docs/architecture/rendering.md) |
| `audio` | OpenAL, acoustic probes, rain; [audio](docs/architecture/audio.md) |
| `audio` music, `game/MusicSense` | Catalog, scheduling and PCM streaming; [music](docs/architecture/music.md) |
| `data`, `item` | JSON packs, ResourceId, registry, recipes and loot; [items-data](docs/architecture/items-data.md), [loot](docs/architecture/loot.md) |
| `ui` | Screen stack and input; [ui-menus](docs/architecture/ui-menus.md) |
| `ui/container` | Inventory/chest/furnace gestures; [containers](docs/architecture/containers.md) |
| `save` | Format readers, atomic writes, protected worlds; [saves](docs/architecture/saves.md) |
| `net` | Protocol, session authority, inventory sync; [network](docs/architecture/network.md) |
| `net/connect`, `net/direct` | Connection ladder, LAN/NAT; [network](docs/architecture/network.md) |
| `net/photon` | Photon wire transport; [network](docs/architecture/network.md) |
| `server` | Headless host, console and configuration; [server](docs/architecture/server.md) |
| `assets/data` | Item, recipe, loot and tag data shipped with game and server; [items-data](docs/architecture/items-data.md) |
| `src/test/java` | TestMain registry, CoreTests and focused suites; [TESTING](docs/TESTING.md) |
| `tools` | Offline importers/previews/benchmarks; [TOOLS](docs/TOOLS.md) |

To add a block, start at [world.md](docs/architecture/world.md), then items/data,
textures and persistence; there is no need to read all of `Game.java` first.
[PROJECT_MAP](docs/PROJECT_MAP.md) is generated from current source Javadoc, one
row per top-level type. [TUNING](docs/TUNING.md) preserves the subsystem knob table;
[CONTROLS](docs/CONTROLS.md) lists controls. [ADR index](knowledge/decisions/README.md)
records architectural choices. Old graph exports are historical snapshots and
are not required input to architecture work.

## Known boundaries

Generation has terrain/caves/ores/vegetation passes, river height shaping and
post-vegetation structures. The texture atlas is PNG-backed, 512 x 512, with
32-pixel tiles; the shader pipeline has many families and post passes.
Music currently ships 14 cataloged MP3s. Structures use one candidate per 10 x 10
chunk region (3/4 chance), with four templates including DUNGEON.
Dedicated chunks load around everyone, while simulation currently centers on
the first player. Mob snapshots currently broadcast to all; interest filtering
is roadmap work. Check source before claiming these limitations are resolved.
