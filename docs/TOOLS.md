# Developer tools

Inventory updated 2026-10-02. Paths below are relative to the repository root.
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
| [RabbitRaidGameSmoke.java](../tools/RabbitRaidGameSmoke.java) | J | Actual Game: seven-day wolf release, reachable/fenced crops, gate, save/reopen and stone-breaking hearing in clear/storm weather | `out-test/rabbit-raid-game; disposable saves and native captures; optional output argument` |
| [BenchArmor.java](../tools/BenchArmor.java) | J | Eight-player armor CPU submission before/after under real GL; reports renderer and delta | `console; hardware budget still needs mp-host-3 baseline` |
| [RenderSurvivalPreview.java](../tools/RenderSurvivalPreview.java) | J | 78 real color captures, armor depth comparisons, baby models, sleeves and eating | `out-test/previews/survival` |
| [ImportBuildingFarmTextures.java](../tools/ImportBuildingFarmTextures.java) | J | Hash-checked source sheet → eight construction/farm/sapling tiles | `assets/textures/blocks` |
| [ImportFarmItemTextures.java](../tools/ImportFarmItemTextures.java) | J | Source sheet → sixteen farm/material icons | `assets/textures/blocks` |
| [ImportVegetationTextures.java](../tools/ImportVegetationTextures.java) | J | Source sheet → grass and flower tiles | `assets/textures/blocks` |
| [ImportCopperArmorTextures.java](../tools/ImportCopperArmorTextures.java) | J | Source sheet → four copper armor icons | `assets/textures/blocks` |
| [ImportArmorSheet.java](../tools/ImportArmorSheet.java) | J | Reviewed generated panels → four separate model sheets | `assets/mobs/armor` |
| [linux/run-tests.sh](../tools/linux/run-tests.sh) | bash | Java 17-bytecode compile and TestMain; needs JDK, libs and rg | `build/linux-test-classes; CLASSES/JAVA_HOME override` |
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
| [ImportCopperOreTexture.java](../tools/ImportCopperOreTexture.java) | J | Copper ore flecks over the exact shipped stone base (GEN-08) | `assets/textures/blocks/copper_ore.png` |
| [ImportRoadmapTextures.java](../tools/ImportRoadmapTextures.java) | J | Digest-checked import of armor, hoes and crop stages; binary alpha, 32px sprites (RND-09 part 2) | `optional output directory; default assets/textures/blocks` |
| [ExportTextureCatalog.java](../tools/ExportTextureCatalog.java) | J | Self-contained catalog of actual runtime PNGs and atlas indices | `optional output HTML; default docs/textures.html` |
| [RenderRoadmapTexturePreview.java](../tools/RenderRoadmapTexturePreview.java) | J + GL | Native atlas/UI preview of the 35 new tiles; checks GL errors and nonempty capture | `optional output PNG; default out-test/roadmap-textures/native.png` |
| [InventorySafetySmoke.java](../tools/InventorySafetySmoke.java) | J | Inventory/conservation gameplay smoke | `out-test/inventory-safety` |
| [make_trailer.py](../tools/make_trailer.py) | PY | Compose rendered frames with ffmpeg/Pillow | `out-test/trailer-clean.mp4; out-test/trailer-ru.mp4` |
| [MakeSaveFixtures.java](../tools/MakeSaveFixtures.java) | [old-writer recipe](../src/test/resources/fixtures/saves/README.md) | Authenticated alpha writer and explicitly synthetic historical save corpus; refuses current format writers | `required new/empty output directory; never replace the golden corpus` |
| [MakeGuestFixture.java](../tools/MakeGuestFixture.java) | [old-writer recipe](../src/test/resources/fixtures/saves/README.md) | `beta-guest`: guest checkpoint v1 written by the v1.0.1-alpha tag; refuses any other writer | `required new/empty output directory; never replace the golden corpus` |
| [MakeWorldGenGolden.java](../tools/MakeWorldGenGolden.java) | J | `fixtures/worldgen-v1.txt`: V1 generation hashes of 120 chunks; runs against current classes or the v1.0.0-alpha tag with identical output | `writes only the given file; never regenerate to pass a test` |
| [MakeTrailer.java](../tools/MakeTrailer.java) | J | Compose before/after image sequence from existing captures | `out-test/trailer` |
| [PhotonSmoke.java](../tools/PhotonSmoke.java) | J | Opt-in external Photon connection test; consumes cloud slots | `console; network traffic` |
| [PlayerCollisionSmoke.java](../tools/PlayerCollisionSmoke.java) | J | Native held-key wall movement and enclosed player/mob collision recovery | `console` |
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
| [WorldGenOpeningSmoke.java](../tools/WorldGenOpeningSmoke.java) | J | Production menu rendering/input at three sizes; decline, upgrade, error recovery | `out-test/worldgen-opening` |
| [WorldGenGameSmoke.java](../tools/WorldGenGameSmoke.java) | J | Real Game loop: decline V1, mouse upgrade, V1/V2 save/reopen | `out-test/worldgen-game` |

## Root entry scripts

`run.ps1` builds/runs; `run-tests.ps1` builds/runs headless checks;
`run-net-test.ps1` starts two real LAN game processes;
`run-server-test.ps1` starts a dedicated host plus game clients.
Use `.\run.ps1 -CompileOnly` before the LAN/server scripts, which consume `out`.
In a Linux container, `tools/linux/run-net-test.sh` and `tools/linux/run-server-test.sh`
are the same two checks under Xvfb, with natives from `tools/linux/fetch-natives.sh`;
they consume `out-test` and write to `out-test/net` and `out-test/server`.
See [TESTING](TESTING.md) for passing markers, filters and artifact paths, and
[benchmark methodology](perf/README.md) for suite options and provenance limits.

## Living population verification

`java -cp 'out;libs/*' tools/PopulationGameSmoke.java` drives the actual Game:
owner commands, finite herds, one real death and loot, ten days, F3+G input,
save/menu/reopen and identity checks. Screenshots/logs use `out-test/population-game`.
`run-net-test.ps1 -LivingWorld` enables the host rule and checks guest rejection;
`run-server-test.ps1 -LivingWorld` enables and advances through the server console.
Default and Photon paths retain their existing opt-in behaviour.

For LW-06/LW-09, `java -cp 'out;libs/*' tools/RabbitRaidGameSmoke.java` uses the
actual Game update/render path and temporary saves. It checks fields before and
after a gate opens, then reopening, and uses real stone-breaking input to compare
clear weather with the host's storm state. It does not measure the LW-12 hardware
budget or complete the other Living World stories. See the
[field-raids test plan](changes/field-raids/test-plan.md) and
[validation](changes/field-raids/validation.md) for the boundaries and results.

On Windows the combined `-Farm -LivingWorld` LAN/server scenarios also wait for
an ordinary hungry rabbit to consume a mature crop on the authority and require
guests to receive stage 0 while the protected crop stays at 7. They do not grant
guest Ecology authority. The dedicated script invokes
`java -cp 'out;libs/*' tools/RaidSaveAcceptance.java prepare|verify out-test/server/server.properties`:
preparation creates a fresh disposable real server save with two reserved animals
behind gates; verification reads the actual saved crop stages before the later
ten-day command can regrow them. It refuses paths outside this checkout's
`out-test` directory. Linux scripts retain their earlier farm scenario scope.

## Burn-scar acceptance

Burn-scar acceptance is documented in [its test plan](changes/burn-scars/test-plan.md).
`BurnScarsGameSmoke.java` exercises actual RMB ignition, production fire/ash
callbacks, seven owner-advanced days, ordinary natural growth/materialization
and menu/save/reopening. `run-net-test.ps1 -BurnScars` and
`run-server-test.ps1 -BurnScars` verify the same recovered terrain and positive
fresh animal ID on both real peers; dedicated mode reads the save before and
after stopping with `BurnScarsSaveAcceptance.java`.
`BurnScarsReaderCompatibility.java write|rewrite|verify` uses a preserved actual
pre-scar reader-9 classpath to prove opaque burn records, natural sapling meta,
animal identity and unknown suffixes survive real disk rewriting. Every tool
owns disposable fixtures under this checkout's ignored `out-test/` tree.

## Spider-lair acceptance and authoring

`tools/LairGameSmoke.java` runs the disposable native nest lifecycle with six actual
captures, ordinary heart-breaking input, three cleared nights and save/reopen.
`run-net-test.ps1 -Lairs` / `run-server-test.ps1 -Lairs` run matching LAN peers and
a NORMAL dedicated host with two real guests; `tools/LairSaveAcceptance.java`
prepares and verifies only fresh fixtures below this checkout's `out-test` directory.

`tools/LairReaderCompatibility.java write|rewrite|verify <out-test/fixture>` checks
an actual reader-8 classpath followed by the current reader-9 classpath. It preserves
typed/opaque lair records and suffixes through an older level rewrite, attempts a
blocked terrain write after `TooNew`, then verifies block 64, spider ID and cave hints.
The rewrite phase refuses a current classpath. Save old compiled classes before
building the newer source; it never edits an existing fixture or user save.
`tools/DrawSpiderNestTexture.java` is the offline original texture authoring source;
provenance is in `assets/textures/source/spider-nest.md`.

## Historical generators


[tools/legacy](../tools/legacy/README.md) preserves the moved root generators:
HeartSpriteGen.java, WaterParticleGen.java and WaterTextureGen.java. Read their
source before running; they predate current reviewed textures. The obsolete
SaveRoundTrip.java.txt is source history only, not a test entry point.

[tools/server-package](../tools/server-package/README.txt) contains serverZip
launcher/configuration templates. The compiled archive is written under
`build/distributions`, and the package smoke extracts to a unique ignored folder.

Farming/construction/armor native acceptance: `.\run-net-test.ps1 -Farm` and
`.\run-server-test.ps1 -Farm`. Linux equivalents use `NET_SCENARIO=farm` and
`CLASSES=<compiled current classes>` with `tools/linux/run-net-test.sh` or
`tools/linux/run-server-test.sh`. They use disposable worlds and real sockets.
