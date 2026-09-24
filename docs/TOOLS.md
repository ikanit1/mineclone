# Developer tools

Inventory updated 2026-09-24. Paths below are relative to the repository root.
Compile current game/test classes first with `.\run-tests.ps1`; Java tools use those
classes and `libs/*`. GL tools need a usable desktop/OpenGL context even when their
window is hidden; audio smoke tools need OpenAL. Headless tests are separate.

## Invocation conventions

Each table row supplies a file to the corresponding command:

```powershell
# J: Java source launcher (most tools; replace FILE and append documented args)
java -cp 'out-test;libs/*' tools/FILE.java
# P: PowerShell helper
.\tools\FILE.ps1
# PY: Python utility (check its module dependencies / --help first)
python tools/FILE.py
# Compiled Game container smoke (package-private access requires same classloader)
javac --release 17 -encoding UTF-8 -d out-test -cp 'out-test;libs/*' tools/ContainerBreakSmoke.java
java '-Dmineclone.checkThreadOwnership=true' -cp 'out-test;libs/*' com.mineclone.ContainerBreakSmoke
# Native save/backup lifecycle; optional argument selects the artifact directory
java '-Dmineclone.checkThreadOwnership=true' -cp 'out-test;libs/*' tools/SaveLifecycleSmoke.java build/review-smoke/results-new
# Benchmark launcher aggregation checks need both tool classes compiled together
New-Item -ItemType Directory -Force build/bench-tools | Out-Null
javac --release 17 -encoding UTF-8 -d build/bench-tools -cp 'out-test;libs/*' tools/BenchScenes.java tools/BenchScenesTest.java
java -cp 'build/bench-tools;out-test;libs/*' BenchScenesTest
# Full 3 x 11 suite; see perf/README.md for short smoke and preparation-only options
java -cp 'out-test;libs/*' tools/BenchScenes.java
# No game classes needed for the source map
java tools/GenProjectMap.java
# Read-only save audit and two-image-set comparison
java -cp 'out-test;libs/*' tools/CheckSaves.java src/test/resources/fixtures/saves
java -cp 'out-test;libs/*' tools/ComparePreviews.java out-test/previews-baseline out-test/previews
```

Importers/generators **write asset files** at the listed destinations. Their existence
does not authorize replacing reviewed artwork during unrelated work. Prefer an
explicit candidate output when the tool supports it, review, then integrate.
PhotonSmoke contacts the external Photon service; it is not a CI prerequisite.
Quote Java `-Dname=value` arguments in PowerShell. `MakeSaveFixtures` is an
exception to the current-build convention: it requires the authenticated old
writer and its assets, as described in the [fixture reproduction guide](../src/test/resources/fixtures/saves/README.md).

## Current tools

| File | Run | Purpose | Output / effects |
|---|---|---|---|
| [AnalyzeMusic.java](../tools/AnalyzeMusic.java) | J | Read-only MP3 loudness/catalog analysis | `console` |
| [BenchChunkPipeline.java](../tools/BenchChunkPipeline.java) | J | Chunk generation/mesh frame cost | `console` |
| [BenchPathfinder.java](../tools/BenchPathfinder.java) | J | Mob navigation cost | `console` |
| [BenchScenes.java](../tools/BenchScenes.java) | J | Sequential 11-scene native suite, source/resource snapshot, provenance and repeatability gate | `out-test/bench/runs/<timestamp>; --output selects a new directory` |
| [BenchScenesTest.java](../tools/BenchScenesTest.java) | compiled pair above | Headless median/missing-metric/repeatability checks | `console; no GL or benchmark scenes` |
| [BenchShaders.java](../tools/BenchShaders.java) | J | Real GL shadow/HDR/post benchmark | `console` |
| [BenchUi.java](../tools/BenchUi.java) | J | Real GL inventory draw cost | `console` |
| [BenchWater.java](../tools/BenchWater.java) | J | Water tick benchmark | `console` |
| [build-common.ps1](../tools/build-common.ps1) | via run.ps1/run-tests.ps1 | Dot-sourced build helper, not a standalone command | `libs; out*/generated/com/mineclone/core/BuildInfo.java` |
| [CheckSaves.java](../tools/CheckSaves.java) | J | Read-only level/chunk/guest-record/item audit; pass a saves directory | `console; no save writes` |
| [ComparePreviews.java](../tools/ComparePreviews.java) | J | Pixel comparison; pass baseline and candidate directories | `console; reads image folders` |
| [ContainerBreakSmoke.java](../tools/ContainerBreakSmoke.java) | compiled command above | Actual Game host container destruction and guest item delivery | `out-test/container-break-smoke` |
| [CreativeGameSmoke.java](../tools/CreativeGameSmoke.java) | J | Real Game mouse interaction in temporary creative world | `out-test/creative-game` |
| [CreativeSmoke.java](../tools/CreativeSmoke.java) | J | Native input/physics and creative UI | `out-test/previews/creative` |
| [DrawPixelParticles.java](../tools/DrawPixelParticles.java) | J | Overwrite authored particle masks from fixed pixel patterns | `assets/textures/blocks/pixel_*.png` |
| [GenBiomeSprites.java](../tools/GenBiomeSprites.java) | J | Legacy procedural biome texture generation | `assets/textures/blocks` |
| [GenBlockTextures.java](../tools/GenBlockTextures.java) | J | Legacy full procedural texture generation | `assets/textures/blocks; assets/atlas.png; docs/textures.html` |
| [GenProjectMap.java](../tools/GenProjectMap.java) | J | Parse current Java declarations/Javadoc and regenerate class map | `docs/PROJECT_MAP.md` |
| [import_equipment_pack.py](../tools/import_equipment_pack.py) | PY | Import reviewed silhouettes/materials; requires Pillow | `--output directory; default assets/textures/blocks` |
| [ImportBiomeTextures.java](../tools/ImportBiomeTextures.java) | J | Import reviewed source sheet into biome tiles | `assets/textures/blocks` |
| [ImportMobAtlas.java](../tools/ImportMobAtlas.java) | J | Import reviewed mob sheet into renderer UV layout | `assets/mobs/*.png; atlas.png` |
| [ImportPlayerSkin.java](../tools/ImportPlayerSkin.java) | J | Import reviewed player sheet; optional source argument | `assets/mobs/player.png; pre-import backup` |
| [ImportTerrainAtlas.java](../tools/ImportTerrainAtlas.java) | J | Import terrain sheet with old-tile backup | `assets/textures/blocks; assets/textures/pre-higgsfield; assets/atlas.png` |
| [ImportToolTextures.java](../tools/ImportToolTextures.java) | J | Import tool sheet into native atlas sprites | `assets/textures/blocks` |
| [InventorySafetySmoke.java](../tools/InventorySafetySmoke.java) | J | Inventory/conservation gameplay smoke | `out-test/inventory-safety` |
| [make_trailer.py](../tools/make_trailer.py) | PY | Compose rendered frames with ffmpeg/Pillow | `out-test/trailer-clean.mp4; out-test/trailer-ru.mp4` |
| [MakeSaveFixtures.java](../tools/MakeSaveFixtures.java) | [old-writer recipe](../src/test/resources/fixtures/saves/README.md) | Authenticated alpha writer and explicitly synthetic historical save corpus; refuses current format writers | `required new/empty output directory; never replace the golden corpus` |
| [MakeGuestFixture.java](../tools/MakeGuestFixture.java) | [old-writer recipe](../src/test/resources/fixtures/saves/README.md) | `beta-guest`: guest checkpoint v1 written by the v1.0.1-alpha tag; refuses any other writer | `required new/empty output directory; never replace the golden corpus` |
| [MakeTrailer.java](../tools/MakeTrailer.java) | J | Compose before/after image sequence from existing captures | `out-test/trailer` |
| [PhotonSmoke.java](../tools/PhotonSmoke.java) | J | Opt-in external Photon connection test; consumes cloud slots | `console; network traffic` |
| [PlayerCollisionSmoke.java](../tools/PlayerCollisionSmoke.java) | J | Native held-key player movement/collision | `console` |
| [RainAudioSmoke.java](../tools/RainAudioSmoke.java) | J | Real Vorbis/OpenAL rain loop | `console; audible playback` |
| [RenderAtmospherePreview.java](../tools/RenderAtmospherePreview.java) | J | Real weather/aurora/moon GL captures | `out-test/previews/atmo-*.png` |
| [RenderBiomePreview.java](../tools/RenderBiomePreview.java) | J | Generated biome terrain/falling-block captures | `out-test/previews/biome-*.png` |
| [RenderEquipmentReview.java](../tools/RenderEquipmentReview.java) | J | Chunk/first-person/drop/inventory item review | `out-test/texture-review` |
| [RenderHandPreview.java](../tools/RenderHandPreview.java) | J | Real first-person item/hand poses | `out-test/previews` |
| [RenderHeldPlayerPreview.java](../tools/RenderHeldPlayerPreview.java) | J | Third-person equipment and shadow passes | `out-test/previews/third-person-items` |
| [RenderHudPreview.java](../tools/RenderHudPreview.java) | J | Real GL HUD layout captures | `out-test/previews` |
| [RenderMobAnimations.java](../tools/RenderMobAnimations.java) | J | All registered mob animation review | `out-test/previews/mob-animations` |
| [RenderMobPreview.java](../tools/RenderMobPreview.java) | J | Mob GL snapshots | `out-test/previews` |
| [RenderPlayerHandedness.java](../tools/RenderPlayerHandedness.java) | J | Front/back/side attack and held-item orientation | `out-test/handedness/after` |
| [RenderPlayerMotion.java](../tools/RenderPlayerMotion.java) | J | Live player animation/color/depth passes | `out-test/previews/player-motion` |
| [RenderPlayerPreview.java](../tools/RenderPlayerPreview.java) | J | Player GL snapshots | `out-test/player-preview` |
| [RenderShaderPreview.java](../tools/RenderShaderPreview.java) | J | Generated world shadow/HDR/post captures | `out-test/previews/shader-*.png` |
| [RenderStormPreview.java](../tools/RenderStormPreview.java) | J | Deterministic real terrain/sky/storm shaders | `out-test/previews/storms` |
| [RenderTrailer.java](../tools/RenderTrailer.java) | J | Rendered cinematic fly-through frames | `out-test/trailer` |
| [RenderVersionShot.java](../tools/RenderVersionShot.java) | J | Current version weather/sky comparison captures | `out-test/version-shot/now-*.png` |
| [SaveLifecycleSmoke.java](../tools/SaveLifecycleSmoke.java) | J; command above | Actual Game corrupt refusal, precise clock/opaque-section save/reopen, rendered backup progress and restore input, GL error checks | `build/review-smoke/results by default; optional artifact directory; temporary saves retained there` |
| [StormAudioSmoke.java](../tools/StormAudioSmoke.java) | J | Real OpenAL wind/storm lifecycle | `console; audible playback` |
| [TestRunnerCli.ps1](../tools/TestRunnerCli.ps1) | P | Filter/list/env/invalid-input/failure-exit acceptance | `out-test/runner-cli` |
| [TestServerPackage.ps1](../tools/TestServerPackage.ps1) | P | Extract serverZip; launch .bat, TCP ready, /stop and saved level | `out-test/server-package-*` |
| [WorldBackupBenchmark.java](../tools/WorldBackupBenchmark.java) | J | 200 MiB backup measurement on disposable generated data | `temporary directory + console` |

## Root entry scripts

`run.ps1` builds/runs; `run-tests.ps1` builds/runs headless checks;
`run-net-test.ps1` starts two real LAN game processes;
`run-server-test.ps1` starts a dedicated host plus game clients.
Use `.\run.ps1 -CompileOnly` before the LAN/server scripts, which consume `out`.
See [TESTING](TESTING.md) for passing markers, filters and artifact paths, and
[benchmark methodology](perf/README.md) for suite options and provenance limits.

## Historical generators

[tools/legacy](../tools/legacy/README.md) preserves the moved root generators:
HeartSpriteGen.java, WaterParticleGen.java and WaterTextureGen.java. Read their
source before running; they predate current reviewed textures. The obsolete
SaveRoundTrip.java.txt is source history only, not a test entry point.

[tools/server-package](../tools/server-package/README.txt) contains serverZip
launcher/configuration templates. The compiled archive is written under
`build/distributions`, and the package smoke extracts to a unique ignored folder.
