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
  to generate, old worlds can be upgraded and the welcome packet names the host's
  generator: a guest still builds the host's world as V1
  (`Game.startRemoteWorld`), and a test fails if `LATEST` moves first.
- `GenFeatures` — one flag per 1.1 generation change (`copperOre`, `vegetation2`,
  `structures2`, `caves2`, `livestockAtGen`), each with a bit that never moves.
  A change reads its own flag inside `World.generateDetached(cx, cz, features)`;
  never branch on anything else, or V1 chunks drift. V1 has none; `GenFeatures.V2`
  is what this build implements — switch a flag on there in the commit that
  implements it, never earlier. Today it is empty.
- `GenPolicy` — which version and features a chunk is generated with; a `World`
  asks it per chunk from worker threads. The game and the dedicated server use
  the ledger-backed policy (below); guests, the menu and tests use fixed ones.
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

## Chunk ledger (GEN-02)

`world/gen/ChunkLedger` records the version each chunk was first generated with.
The game and the server generate through `WorldGenSettings.policy(ledger)`: a
chunk the ledger knows keeps its version (with that version's full feature set);
a new one gets the world's version and is recorded. A chunk missing from the
ledger is the world's own version — a world that was never upgraded needs no
entries for the land it made before the ledger existed: all of it is V1.

- **File** `chunks/ledger.dat`, gzip: `int MAGIC ("MCLG"), int FORMAT (1), VarInt
  count`, the keys sorted — the first zigzagged, then the differences — as
  VarLongs (`data/VarLong`), then the versions as runs `VarInt length, VarInt id`.
  About 1–2 bytes a chunk before gzip. Unsorted or repeated keys, an unknown
  version, a run past the end or trailing bytes refuse the file whole.
- **One file, not the roadmap's `ledger.log`.** Every save that recorded something
  (`encodeIfDirty`) rewrites the whole file atomically, queued behind the session
  backup like chunks; a read sees queued bytes at once. 100 000 chunks is about
  200 KB before gzip, less than the chunk writes of the same autosave, and a single
  file has no replay or compaction to get wrong.
- **Damage.** `SaveManager.openLedger` always returns a ledger: the ledger is
  derived data, and the world opens. A damaged file is moved aside as
  `ledger.dat.corrupt-<millis>` after the session backup; if the backup or the
  move fails it stays in place and that `SaveManager` never writes over it. A
  world that failed its level read check never gets a ledger written either.
- **Rebuild.** A missing or damaged ledger of a world that was ever upgraded
  (`upgradedAt > 0`) is rebuilt conservatively by `WorldGenUpgrade.pinSeen`: every
  saved chunk and `SEEN_RADIUS` (12) chunks around it, and as much around spawn
  and the player (the owner's checkpoint on a server), count as V1. A world that
  was never upgraded rebuilds empty.
- **Upgrade.** `WorldGenUpgrade.upgrade(from, target, ledger, saved chunks, anchors,
  now)` pins the same seen land to the old version and returns the new settings.
  `ChunkLedgerTests` upgrades the 1.0 fixture `alpha-small` and compares chunks 0
  and 12 away from every saved chunk with the V1 generator by hash; land 200
  chunks away gets V2.
- **Seams.** `WorldGenUpgrade.seams` — recorded chunks whose side neighbour has
  another version: the rim of the pinned land. `tools/SeamReport.java [saves]
  <world> [--list]` prints them, read-only.
- **One chunk, one feature set.** Everything a chunk's generation reads, neighbour
  crowns reproduced across its border included, follows that chunk's own
  features. Never ask the policy about a neighbour: a pinned chunk would change
  when its neighbour moves to V2. The price is crowns that do not match along a
  seam.
- Backups and world copies carry the ledger with `chunks/`; `CheckSaves` reads
  only `c.*.dat`. A guest has no ledger: the world is the host's.

**Not yet** — each waits for V2 to change land; until then it would only record a
version, and `WorldGenGoldenTests` fails if `LATEST` moves past V1 first:

- the world-list dialog "update generation for new land?" and `upgrade-worldgen`
  in `server.properties`;
- the network part: `S_WELCOME` naming the host's generator, `S_GEN_MAP (74)` with
  the pinned keys, a version byte in `S_CHUNK_DELTA` (the guest regenerates on a
  mismatch);
- guests' checkpoints (`players/`) as anchors of a server's upgrade.

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
