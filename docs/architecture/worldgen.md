# World generation

Updated: 2026-09-24.

`world/World.generateDetached()` generates terrain -> caves -> ores -> vegetation,
then places structures; rivers shape the height field during terrain generation.
Saved modifications are applied before the chunk is published. See
[world invariants](world.md) and [publication](chunk-publication.md).

`BiomeProvider` samples three climate noises and smooths height parameters.
Neighbor tree crowns are reproduced from the same seed and coordinates without
writing into another worker's chunk. Caves protect the seabed; ore veins stay
inside their height bands and replace stone only.

`Structures` has four templates: ruins, hut, obelisk and DUNGEON. It chooses at
most one candidate per 10 x 10 chunk region, with a 3/4 region chance and a
minimum candidate gap of seven chunks. This replaces the old independent
1/70, 1/90 and 1/110 placement claims. Surface sites reserve trees before foliage;
placement itself comes after vegetation.

## Generator versions (GEN-01)

Only edited chunks are saved; everything else is regenerated from the seed each
time it loads. So a change to generation silently rewrites every unedited chunk
of every old world, and a guest generating with another version receives wrong
deltas. Generation is therefore versioned (`world/gen`):

- `WorldGenVersion` — V1 is the 1.0 generator; V2 collects what 1.1 adds. The
  number, not the ordinal, is persisted. `LATEST` stays V1 until V2 has something
  to generate, the chunk ledger (GEN-02) keeps explored land on V1 and the welcome
  packet names the host's generator: a guest still builds the host's world as V1
  (`Game.startRemoteWorld`), and a test fails if `LATEST` moves first.
- `GenFeatures` — one flag per 1.1 generation change (`copperOre`, `vegetation2`,
  `structures2`, `caves2`, `livestockAtGen`), each with a bit that never moves.
  A change reads its own flag inside `World.generateDetached(cx, cz, features)`;
  never branch on anything else, or V1 chunks drift. V1 has none; `GenFeatures.V2`
  is what this build implements — switch a flag on there in the commit that
  implements it, never earlier. Today it is empty.
- `GenPolicy` — which version and features a chunk is generated with; a `World`
  asks it per chunk from worker threads. Today every world uses a fixed policy.
  `World.blankTwin()` gives the network delta baseline the same seed, profile and
  policy as the live world.
- `WorldGenSettings` — the level's `worldgen` section: `int version, int feature
  bits, long upgradedAt`. The world records the features it was created with and
  keeps generating with them, so a change implemented by a later build never
  reaches land an earlier build already showed. A level without the section
  predates versions and is V1; the M0 four-byte form still reads. New worlds record
  `WorldGenSettings.forNewWorld()` with their first save — the game (also a world
  opened without a level file) and the dedicated server alike.

A level this build cannot generate is refused and never rewritten, because
generating its unedited land with another generator would change it:

| Stored | Result |
| --- | --- |
| Unknown version | `LevelLoad.TooNew("world generator version", …)` |
| A V2 change this build does not implement | `TooNew("world generator features", stored bits, implemented bits)` |
| V1 with any change, bad length, negative `upgradedAt` | `Unreadable` — no build writes it |

The layout is fixed: new generator data belongs in a section of its own, or an
older build would report a newer world as damaged instead of too new.

`GenProfile` (NORMAL/FLAT) is unrelated: a debug profile for benchmarks only.

**The V1 golden.** `src/test/resources/fixtures/worldgen-v1.txt` holds SHA-1 of
blocks and meta for 120 chunks — seeds 0, 20260922 and −77231; negative, far
(±30000) and scattered coordinates; the first chunk of every biome near spawn;
a chunk where each structure kind was actually placed. `tools/MakeWorldGenGolden.java`
wrote it using only APIs that existed at v1.0.0-alpha, and its output against
that tag's build is byte-identical: the current generator is the 1.0 generator.
`WorldGenGoldenTests` regenerates all 120 chunks on every run (~0.5 s); a one-line
change to a gravel lens fails all of them. Never regenerate the file to make the
test pass — put the change behind a feature flag instead.

Checks: `run-tests.ps1 -Only worldgen,core,chunk`. `WorldGenerationTests` covers
sparse structure spacing, biome textures, seams and falling-block conservation;
CoreTests retains deterministic generation, caves, ores and river invariants.
Knobs: [TUNING.md](../TUNING.md), `Biome`, `BiomeProvider`, `Caves`, `Rivers`,
`Structures.REGION_CHUNKS`, `Structures.MIN_CHUNK_GAP`.
