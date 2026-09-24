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

Checks: `run-tests.ps1 -Only worldgen,core,chunk`. `WorldGenerationTests` covers
sparse structure spacing, biome textures, seams and falling-block conservation;
CoreTests retains deterministic generation, caves, ores and river invariants.
Knobs: [TUNING.md](../TUNING.md), `Biome`, `BiomeProvider`, `Caves`, `Rivers`,
`Structures.REGION_CHUNKS`, `Structures.MIN_CHUNK_GAP`.
