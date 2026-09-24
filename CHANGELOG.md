# Changelog

Versions below 0.9.0 predate git tags and are reconstructed from the commit history by feature milestones; their dates are taken from the commits.

## [Unreleased]

### Fixed
- A dedicated server keeps the items lying in a world it opens: it used to leave them out of the world and erase them from their chunk on its first save.
- The world lives around every player, not the first: block ticks, furnaces, spawning and despawning run near each guest on a host or a dedicated server, and a server's snow and rain now follow the weather fronts.
- On a dedicated server, zombies and other melee mobs strike the guests (their blows used to land on no one), and a guest's broken or placed block draws mobs like the host's own.
- On a dedicated server, arrows fly and land (a guest's arrow used to hang where it was shot, and every projectile packet kept growing), creepers blow craters and hurt guests, and killed mobs drop their loot.
- A dedicated server no longer stops with an exception — without saving — at the first item lying on the ground (a guest's throw, a broken chest, sand falling on a torch).
- The bedroll can be crafted in survival: leaves now drop a leaves block one time in four. Before, leaves dropped nothing while the bedroll's recipe needs three of them, so sleeping and its respawn point were creative-only.
- A guest's checkpoint over protocol v7 no longer resets what that protocol cannot carry (equipment, effects, personal spawn, sections of newer builds).
- Guests breaking or replacing a chest or furnace now spill its complete contents on the authority before the container is removed.
- Unreadable worlds refuse to open; damaged chunks are preserved in quarantine and newer mandatory formats stay read-only.
- Saved chunks become visible only after terrain, containers, lighting and opaque data have been restored; late loads cannot replace live edits.
- Reloading an evicted chunk uses its latest queued checkpoint while disk writes are pending; older completions and failed writes cannot discard that checkpoint.
- Dedicated-server autosaves preserve the single-player owner's inventory, pending stacks, pose and vitals. Reopening the active world captures its latest state first.
- The crosshair keeps its two-pixel thickness with triangles; outlines use a portable line width so forward-compatible OpenGL contexts no longer report invalid values.

### Added
- One player record (`PlayerRecord`) for the host's level, guests' `players/<uuid>.dat` (now version 2; version 1 files migrate behind a backup) and the network adapter, with sections for vitals, equipment, effects, personal spawn, advancements and recipes.
- Block, mob and chest drops load from validated JSON loot tables with the M0 drops preserved; a reachability check reports recipes whose result cannot be obtained in survival.
- World generation is versioned: V1 is the 1.0 generator, frozen by golden hashes of 120 chunks proven identical to the v1.0.0-alpha build; each world records its generator and the 1.1 generation changes it was created with, and a world needing a generator or change this build lacks is refused instead of having its unedited land regenerated differently.
- Each world keeps a chunk ledger (`chunks/ledger.dat`): the generator version every chunk was first made with. Once an old world is upgraded to the 1.1 generator, the land it has seen stays exactly as it was and only unexplored land changes; `tools/SeamReport.java` lists where the two meet. A damaged ledger is kept aside as evidence and rebuilt; it never stops a world from opening.
- The simulation knows its participants: the host's own player and every accepted guest, with allocation-free nearest/radius queries and a shared damage vocabulary (`DamageSource`, `DamageType`).
- Session and manual world ZIP backups, protected migration backups, restore-as-copy UI, and dedicated-server `/backup`.
- Level v11 and chunk v7 with minimum reader versions and bounded named sections. Unknown data survives rewriting; level writes queue immutable snapshots.
- Shared double-precision world clock with saved simulation ticks, plus asynchronous GPU timing for rendering phases.
- Categorized tests, runner reports, CI/nightly, shared BuildInfo and a tested standalone server archive.
- Historical save fixtures, block ordinal/property fixtures, architecture documentation and preserved documentation archives.
- Eleven reproducible benchmark scenes with frame, GPU, queue, allocation, network and save metrics; frozen build snapshots and repeated-run aggregation retain measurement provenance.

### Changed
- The game's host and the dedicated server tick mobs, items, arrows and blasts and save chunk entities with one shared session (`WorldSession`), pinned to the previous behavior by a recorded parity hash; mobs on a dedicated server are now pushed apart instead of standing inside one another, and their skeletons shoot.
- Hostile mobs choose a target among all players — the nearest one in view, kept until someone in view is markedly nearer or it has been out of sight for three seconds — and go after whoever hit them first; a lone player's world behaves as before.
- A mob killed by a creative player drops nothing; natural deaths drop their loot whatever mode the host plays in, and a survival guest's kill in a creative host's world drops as usual. Guests also take creeper blast damage.
- All 35 crafting recipes and nine smelting recipes load from validated JSON with their legacy order, shapes and outputs preserved. Tag ingredients and configured smelting times/counts are executed by the real crafting/furnace paths.
- Block behavior flags and numeric properties now come from one exhaustive `BlockProps` table without changing existing block IDs.
- Historical root generators and obsolete manual test sources moved to `tools/legacy`; generated scratch artifacts are kept outside the source tree.

### Art sources
- Added armor, hoe and crop source sheets with exact generation prompts for RND-09. Runtime imports and their gameplay content remain scheduled separately.

## [1.0.0-alpha] - 2026-09-21

Official 1.0.0-alpha release milestone.

### Added & Refined
- Complete Inventory & Window management overhaul (`ContainerMenu`, crafting grid 2x2/3x3, chest, furnace, creative UI).
- Enhanced block state picking, F3 info overlays, and advanced item tooltips.
- Full Day/Night atmosphere with realistic volumetric weather drift, sky gradient, moon phases, and custom particles.
- Multiplayer via built-in Photon Cloud & LAN socket server with compressed chunk deltas.
- Portable Windows app image packaging with bundled JVM runtime (`Mineclone.exe`).

## [0.9.0] - 2026-09-21

First tagged release. Ships as a portable Windows app image (`Mineclone.exe` with
a bundled JRE) - no install, no JDK.

### Fixed
- **Falling water refilled its spread budget, so a single source could flood a
  stepped hillside.** A fall was resetting the flow level to source strength (and a
  flow cell was re-levelled against its own column), which contradicted both the
  comments around it and the invariant the tests are named for. A fall preserves
  the distance from the source again.
- **Snow, rain and volumetric fog swayed back and forth instead of drifting.**
  Particle position was `current wind × total elapsed time`, and the wind pulses
  by design (`Weather.wind` mixes a ~4.8 s gust). Multiplied by an ever-growing
  clock, that pulse moved the whole field at once — barely visible in the first
  seconds of a session, hundreds of blocks after ten minutes. Drift is now the
  integral of the wind (`WeatherDrift`), so a gust speeds the snow up instead of
  teleporting it; the same fix covers the storm-driven fall speed.
  `knowledge/bugs/precipitation-swayed-with-gusts.md`.

### Player model
- **The head turns before the body does.** The model used to pivot as one piece
  with the camera, which reads as a weather vane rather than a person. Now it
  follows Minecraft's rule: the head is always on the camera's heading, the body
  turns toward where the feet are going, and the two stay within a 75° cone
  (`BodyRotation`). Past the cone the body is pulled up to the limit, not all the
  way to the head. The cone holds while walking too — strafing puts the movement
  direction at a right angle to the view, and without it the neck would twist 90°.
  Remote players use the same class: the body heading is derived from movement, so
  it costs nothing on the wire.
- A new, much more detailed player skin, and `tools/ImportPlayerSkin.java` to cut
  a 4×2 sheet into it. The player gets a sheet to themselves — the camera gets
  closer to them than to anything else, and sharing tiles with five mobs was
  costing them resolution.

### Multiplayer lobby
- **The Photon key ships with the build.** Nobody signs up for a Photon account
  to join a friend for one evening, so `NetSettings.DEFAULT_APP_ID` is built in
  and the screen's key field is an override. What it costs: the free 100
  concurrent players are shared by everyone on this build, and the key is
  readable by anyone who has its files. `-Dmineclone.photonAppId` or
  `MINECLONE_PHOTON_APPID` overrides everything, which is what the checks use.
- **A room browser.** The network screen now lists the open Photon rooms with
  their headcount — click one to fill the room name, full ones are marked in
  red. The lobby connection is held by the game (`RoomBrowser`), not by the
  screen, which lives for a single frame; it closes the moment a session starts,
  because a lobby and a room are two separate connections and two of the hundred
  free slots.
- `.\run-net-test.ps1 -Photon` runs the two-process game test **through Photon
  Cloud** instead of a local socket: name server → master → game server, chunk
  deltas, block edits both ways. Proven against the real cloud, not a mock.

### Multiplayer
- **Play together, over Photon Cloud or a LAN.** A host opens one of their worlds
  as a room; guests join it, see each other, and build in the same world. Players
  are drawn with the same model, shader and lighting as the local one, with a name
  tag above the head, and the chat lives in the console field (`T`, a line without
  a leading `/`).
- Photon ships **no Java SDK**, so the client is written here: `PhotonPeer` speaks
  Photon Realtime's WebSocket + `Json` entry point over the JDK's own
  `java.net.http.WebSocket`, with no third-party jar. It walks the standard
  NameServer → Master → GameServer path and carries the game's own binary packets
  as base64 payloads. ADR: `knowledge/decisions/multiplayer-photon.md`.
- **The world is not sent, only the difference is.** Generation is deterministic
  per seed, so a guest builds the same terrain itself; the host answers a chunk
  request with the cells that differ from a fresh generation, computed on a
  background thread. An untouched chunk costs an empty delta — which is the common
  case, so joining a world costs tens of kilobytes instead of tens of megabytes.
- Authority is the host's: water, lava, falling blocks, random ticks, mobs,
  furnaces and item pickup all run there and arrive as snapshots. A guest still
  applies its own block edits immediately — a pick that stalls for half a round
  trip ruins the game worse than any divergence — and the host broadcasts the
  result to everyone, including the sender.
- A second transport, `LanTransport`, plays the same protocol over plain TCP for
  a local network, with no account and no App ID. `LoopbackTransport` runs two
  sessions inside one process, and the tests stand on it.
- New: `tools/PhotonSmoke.java` — two clients create a room, join it, exchange an
  event and leave, against real Photon Cloud with your own App ID.
- options.dat keeps the nickname, App ID, region, room and host address (the v7
  tagged tail, so old files still load).

### Performance
- Sky light is now updated incrementally around an edit instead of reflooding the
  whole chunk. A block edit costs **0.005 ms average / 0.30 ms worst** instead of
  1.10 / 14.76 ms — this was the hitch felt on every dig and place.
- Block reads no longer take a monitor: `PaletteStorage` publishes an immutable
  palette snapshot and reads words through a `VarHandle`. **16.3 ns → 1.9 ns** per
  cell (a chunk mesh reads blocks hundreds of thousands of times). The end-to-end
  mesh build measurement is too noisy on this machine to quote a figure; the
  isolated read benchmark is the honest number.
- Water simulation skips the drop-distance search when no side can be filled, and
  scans a loaded chunk once instead of on every mesh upload. The 0.18 s water tick
  over an ocean went from **11.4 ms to 4.8 ms** worst case, and to nothing at all
  once the world settles.
- Chunk meshes upload as a single interleaved vertex buffer (2 GL objects instead
  of 7, one upload instead of six) and no longer allocate a zero-filled `repeat`
  array when there are no repeats.
- Hardware occlusion now reads the previous frame's query results and issues all
  boxes in one batch, instead of a conditional render whose query could never be
  ready. Removes ~2000 GL calls per frame.
- Block-light flood no longer allocates an `int[4]` per visited cell, clears its
  removal cube per chunk instead of per world cell, and skips neighbours that hold
  no block light at all.
- The menu background no longer generates its 3×3 spawn chunks synchronously on the
  first frame (that frame cost ~200 ms).
- `ChunkMesher` walks the chunk in memory order (y, z, x).

### Added
- Full settings menu: five tabs (Graphics, Screen, Game, Controls, Sound) with
  individual quality knobs instead of a single three-way "Shaders" switch.
  Presets now just fill those knobs in and step aside.
- Screen settings: window mode (windowed / borderless / fullscreen), monitor
  resolution, render scale (50–100 %), antialiasing, frame limit, vsync, GUI scale.
- Graphics settings: shadow quality, bloom, god rays, volumetric fog, water
  reflections, particle budget, precipitation density, entity render distance,
  occlusion culling, distant-chunk LOD.
- Game settings: camera shake, on-screen status effects, context hints, advanced
  tooltips, and an FPS counter that also shows the worst frame of the window.
- `options.dat` v7: a self-describing tagged tail, so a new setting no longer bumps
  the file version and an unknown key is skipped by its value kind.
- `tools/BenchWater.java` — deterministic measurement of the water tick.

### Crafting
- **A crafting grid instead of a shelf of suggestions.** The inventory has a 2x2
  grid and the crafting table opens a 3x3 one, both driven by one `CraftingGrid`,
  so "what does this make" is answered in a single place rather than once per
  screen. Recipes now carry a shape (with optional mirroring) beside the shapeless
  ones, and a shapeless recipe fits any grid large enough to hold it. Whatever is
  left in the grid goes back to the player on close instead of vanishing.

### World simulation
- **Lava flows on its own clock** - one wave every 1.5 s and three cells sideways.
  At water's pace a lava front stops reading as melt. Falls outlive the spread, and
  a chunk is scanned once after loading, as water is.
- **Water and lava meet by geometry.** The lava cell is the one that solidifies:
  side contact makes cobble, water landing on a lava *source* makes obsidian, and
  any other vertical meeting chills it to stone. One table for ticks, particles and
  tests.
- **Sand and gravel fall on events**, not on a per-frame sweep of the world: a
  column is queued when its support is taken away. `MAX_ACTIVE` and
  `STARTS_PER_FRAME` keep a collapsing wall inside the frame, and a block in flight
  is drawn as a real cube (`FallingBlockRenderer`) instead of staying in the chunk
  mesh.
- New `WorldGenerationTests`: biome soils, structure spacing across region
  boundaries, chunk seams, and mass conservation for falling blocks.

### Survival
- **A short chain of goals** (`SurvivalProgress`) that walks a new player through
  the survival loop. Progress is monotonic - planks spent on a recipe and a broken
  pickaxe never push you back - and an old world recovers a sensible point from the
  most advanced item in the inventory. It rides in its own save section, so the
  level format did not have to move for it.

### Art
- Twenty-five new tiles: biome soils (podzol, peat, dry grass, red sand,
  terracotta, limestone, basalt, gravel), materials (stick, coal, ingots, diamond),
  lava and its flow, mud, ash, mossy cobble, obsidian, thin ice, rope, chain, web,
  journal and the crafting table.
- Tools were redrawn as a 4x2 sheet and are cut into tiles by
  `tools/ImportToolTextures.java` and `tools/ImportBiomeTextures.java`; the source
  sheets and the pre-import snapshots are kept beside them, because a redraw
  without them starts from nothing.
- Particles (spark, drop, smoke, flame, snow, lava) are drawn as 8x8 masks in
  `tools/DrawPixelParticles.java` and scaled with no smoothing: a smoothed particle
  in a blocky world reads as a smudge, not a pixel.
- `GenBlockTextures` only writes the sprites that are missing, so hand-drawn art is
  no longer overwritten by a blind run; `--force-tools` regenerates exactly the
  tools.

### Packaging
- `gradlew portableZip` produces `Mineclone-<version>-windows-portable.zip`: a
  jpackage app image with a bundled runtime and the assets, launched by
  `Mineclone.exe`.
- **Fixed: the portable image shipped two versions of LWJGL.** The jpackage input
  directory was filled by a `Copy` task, which leaves behind whatever an earlier
  build put there - 3.3.3 from an old build sat on the classpath in front of the
  current 3.3.6. It is a `Sync` task now.

### Added (earlier in this milestone)
- Positional 3D audio support in the OpenAL sound engine.
- World-space sound playback for block breaks, block placement, doors, footsteps, landing, swimming, splashes, and flowing water.
- First-person held-item renderer with equip, swing, walking bob, and underwater tint.
- Inventory/creative inventory UI with cursor item swapping, hotbar persistence, and trash action.
- Cloud and star texture assets for richer sky rendering.
- Save data support for spawn position and inventory contents.

### Changed
- Refined listener orientation for 3D audio so camera pitch does not skew sound placement.
- Updated water and heart texture assets.
- Improved sky rendering and shader support for new visual effects.
- Deferred chunk meshing around pending light flood work to avoid stale lighting artifacts.

### Notes
- Verified before tagging: 326/326 tests green (`run-tests.ps1`), and the packaged
  `Mineclone.exe` passes the in-game menu autopilot end to end.
- Local preview trees (`out-net/`, `out-video-review*/`) and run logs are ignored
  rather than versioned.

## [0.8.0] - 2026-05-19

### Added
- Health system fields and player damage handling.
- Fall damage tracking with MLG water negation.
- Death state, death detection, death screen, and respawn flow.
- Heart HUD, MC-style heart sprites, and larger heart display.
- Fall damage sounds and water splash particles.
- Water-drop sprite for splash particles.
- Landing puff particles for hard falls.
- Water exit jump at the water surface.

### Fixed
- Moved death checks before interaction to prevent actions after reaching 0 HP.
- Prevented pause escape from resuming play at 0 HP.
- Added `updateDead` handling to the game loop.
- Drew hearts in the dead state.
- Refreshed `inWater` after movement for MLG behavior.
- Reset regen timer consistently.
- Adjusted hearts row alignment and display size.
- Removed passive buoyancy so idle players sink by default.

## [0.7.0] - 2026-05-19

### Added
- Infinite water source creation.
- MC-style water physics design and implementation notes.
- Underwater effects design and implementation notes.
- Smooth FOV reduction while the camera is submerged.

### Fixed
- Waterfall behavior, including bottom-of-fall spread and surface seams.
- Water surface rendering from both sides.
- Water simulator reset on new world creation.
- Several water infinite-source and flow-distance regressions, including reverted experimental fixes.
- `activateAround` call signature after water activation changes.

### Changed
- Added safety guard and clarifying comments around water source flush logic.

## [0.6.0] - 2026-05-17

### Added
- Loading screen.
- In-game console commands.
- Return-to-main-menu flow.
- MC-like water physics with buoyancy, inertia, and force model.
- MC-like ground movement with acceleration ramp and weak air control.

### Fixed
- Reduced water buoyancy so the player sinks while idle.
- Swallowed carried-over left mouse clicks when entering panels.
- Gated game keys behind the console while typing.

### Chores
- Excluded build outputs and runtime state from version control.
- Untracked compiled outputs now covered by `.gitignore`.

## [0.5.0] - 2026-05-17

### Added
- On-disk save format constants.
- `LevelData` and `ChunkSnapshot` carriers.
- `SaveManager` disk I/O for level, chunks, deletion, and async writes.
- Headless round-trip harness for the save package.
- Chunk modification tracking and snapshot/restore accessors.
- Save/load world flow with `level.dat`, delta-patched chunks, autosave on pause/timer/quit.
- Options persistence via `options.dat`.
- Main menu revamp with orbiting menu world, layered title, Continue/New World flow, and Settings persistence.

### Fixed
- Restored saved chunks over spawn-preloaded chunks.
- Gated meshing while generation/snapshot application is pending.
- Deferred block-light flood from restored emitters to the main-thread drain.
- Persisted water-simulator edits so resting water survives reloads.

## [0.4.0] - 2026-05-16

### Added
- Incremental water spread at about 5.5 Hz for visible wavefront behavior.
- Sprite-sheet water flow animation with static source water.
- Directional water flow animation with travelling bands.
- Per-block water flow direction and UV rotation.
- Redesigned block textures with grass, bark, tree rings, cobble, and planks detail.
- 32 px tile size and detailed MC-style door art.

### Fixed
- Raycast passes through water and placement replaces water cells.
- Falling water columns no longer spread sideways mid-fall.
- Grid-line side faces between submerged water cells.
- Door and stair texture rotation.

### Changed
- Texture atlas now loads sprites from disk instead of generating all tiles procedurally.

## [0.3.0] - 2026-05-16

### Added
- Dual-mesh transparent water rendering path.
- Back-to-front chunk sort for the transparent water pass.
- Water rendering design spec and implementation plan.

### Fixed
- Unified `WATER` and `WATER_FLOW` transparent/cutout flags.
- Split chunk meshing into opaque and water mesh data.
- Fixed falling water gaps and shore z-fighting.
- Restored water side faces against solid blocks with small inset.
- Rendered solid block faces adjacent to water.
- Added null guard for water mesh rendering.

### Changed
- `ChunkLoader.Ready` carries `MeshData[]` for opaque and water meshes.

## [0.2.0] - 2026-05-15

### Added
- Full day/night cycle with sky color and ambient scaling.
- Textured block-break particles sampling block atlas UVs.
- Block-light vertex channel through the mesh pipeline.
- `emittedLight` support on block types.
- Torch block and procedural torch atlas tile.
- Chunk block-light storage and accessors.
- BFS flood-fill block light propagation.
- Chunk mesher sampling of actual block light.

### Fixed
- Sky clear color now applies immediately by moving `glClearColor` before `glClear`.

### Documentation
- Documented `TIME_SCALE`, thread-safety trade-offs, and setBlock ordering dependency.
- Added creative foundation design spec and implementation plan.

## [0.1.0] - 2026-05-15

### Added
- Initial tracked Mineclone codebase before the game-feel feature passes.
