# Changelog

This project does not currently have git tags, so the versions below are reconstructed from the commit history by feature milestones. Dates are taken from the commits.

## [Unreleased] - 0.9.0 candidate

### Added
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
- This version is still uncommitted in the working tree.
- The repository also contains untracked build/runtime artifacts such as `bin/`, generated `.class` files, Gradle wrapper files, and helper texture generator files. They should be reviewed before including them in a release.

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

