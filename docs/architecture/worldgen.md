# World generation

Updated: 2026-10-01.

`world/World.generateDetached()` generates terrain -> caves -> ores -> vegetation,
then places the 1.0 structures and, where `structures2` is on, multi-chunk ones; rivers shape the height field during terrain generation.
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
  number, not the ordinal, is persisted. `LATEST` is V2 with copper ore (GEN-08).
  Existing worlds retain their recorded generator/features. Guests build the host's world
  with the host's generator (protocol v8, below).
- `GenFeatures` — one flag per 1.1 generation change (`copperOre`, `vegetation2`,
  `structures2`, `caves2`, `livestockAtGen`), each with a bit that never moves.
  A change reads its own flag inside `World.generateDetached(cx, cz, features)`;
  never branch on anything else, or V1 chunks drift. V1 has none; `GenFeatures.V2`
  is what this build implements — switch a flag on there in the commit that
  implements it, never earlier. Today only `copperOre` is enabled.
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
  and the player, including readable guests' checkpoints and personal spawn
  points, count as V1. A world that was never upgraded rebuilds empty. An
  unreadable guest retains its existing connection refusal and is reported;
  it does not prevent other players from opening the world during recovery.
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
  only `c.*.dat`. A guest saves no ledger: the world is the host's.
- **Guests (protocol v8).** The host sends `S_GEN_MAP` — the ledger's chunks on
  another version than the world's, `GenPolicy.pinned()` — and then `S_WELCOME`
  with its `WorldGenSettings`; the guest generates with `LedgerPolicy(host's
  settings, pinned chunks)`, never saved. Every `S_CHUNK_DELTA` names the version
  it was taken against (asking records the chunk in the host's ledger: the guest
  is about to see it); a guest whose chunk came from another version pins the
  host's (`ChunkLedger.pin`, guests only) and rebases the chunk on that
  generation before the delta. `NetworkTests` runs an upgraded host with pinned
  land and a host whose map forgets a chunk.

**Explicit opening choice (GEN-02, completed 2026-10-01).**
`WorldGenUpgradeScreen` offers an upgrade before opening a closed V1 world from
the world list, Continue or the multiplayer host selector. “Без обновления”
opens with the recorded generator; Esc returns to the menu without writing.
An old world may be offered the choice again on a later opening; it never
upgrades automatically. Starting a host from an already live world preserves
its generator: return to menus before upgrading it.

On the dedicated server, `upgrade-worldgen=false` is the default.
Setting it to `true` upgrades an existing older world before chunks or network
doors open. New worlds already use V2; a V2 world is never downgraded or
re-upgraded when this setting changes.

Both routes use `SaveManager.upgradeWorldGen` on the ordered background writer:
fresh original-byte backup → read all stored player anchors → pin old land →
atomic ledger write → atomic level write. Every recorded version survives;
the 12-chunk approximation also protects saved chunks, world spawn, the owner's
pose/personal spawn and every guest's pose/personal spawn. Pre-ledger travel
beyond those rings cannot be reconstructed. An unreadable guest or inaccessible
chunk directory refuses the upgrade, rather than treating unknown land as new.
Any backup/ledger/level error refuses the upgrade; the server exits with code 2,
and the menu reports the error. A failed level commit can leave extra V1 pins,
which are safe with the unchanged V1 level. It can never leave a V2 level
without its durable pins. Unknown level/player sections survive unchanged.

`WorldGenOpeningTests` upgrades the 1.0 fixture, compares seen terrain hashes,
checks remote guests, original backup bytes, server opt-in/autosave/reopen,
missing-ledger recovery, failed backup/ledger and (on POSIX) failed level
commit. `WorldGenOpeningSmoke` renders/clicks the production menu at three
resolutions; `WorldGenGameSmoke` runs the real Game through decline, mouse
upgrade and save/reopen. The Linux server check can use a disposable copied old
world to exercise mixed V1/V2 generation with two real clients.

**The V1 golden.** `src/test/resources/fixtures/worldgen-v1.txt` holds SHA-1 of
blocks and meta for 120 chunks — seeds 0, 20260922 and −77231; negative, far
(±30000) and scattered coordinates; the first chunk of every biome near spawn;
a chunk where each structure kind was actually placed. `tools/MakeWorldGenGolden.java`
wrote it using only APIs that existed at v1.0.0-alpha, and its output against
that tag's build is byte-identical: the current generator is the 1.0 generator.
`WorldGenGoldenTests` regenerates all 120 chunks on every run (~0.5 s); a one-line
change to a gravel lens fails all of them. Never regenerate the file to make the
test pass — put the change behind a feature flag instead.

## Multi-chunk structures (GEN-03)

`world/structure` builds structures of any size across chunks, deterministically,
whatever order the chunks are generated in (AC-11). It is the fifth pass of
`generateDetached`, after the 1.0 templates, and runs only in chunks whose
features include `structures2`. That flag is off in V1 and in V2, and
`StructureTypes.V2` is empty: the framework ships dormant until the first real
type, so no world records a change it does not have and the V1 golden stands.
The four 1.0 templates stay on their own path (`Structures`).

- **`StructureType`**: an id, `spacing` and `separation` in chunks, a `salt`, a
  biome predicate, a height mode (`SURFACE` — the terrain at the start's centre
  — or an underground band) and `maxRadiusChunks`, how far its pieces may reach
  from the start chunk (at most 16). Each `spacing` x `spacing` region holds at
  most one start, at an offset below `spacing - separation`, so starts in
  neighbouring regions are at least `separation` chunks apart.
- **`StructureStart.create(type, seed, rx, rz, terrain)`** is a pure function: the
  region's chunk, its height, then the type's `Assembler` with its own
  `SplittableRandom(seed ^ salt ^ rx·A ^ rz·B)` and the terrain (`World.terrainHeight`,
  the biome map — both pure). Pieces that overlap, or reach past the radius or out
  of the world, are a bug in the type and throw; an empty assembly or a start in
  the wrong biome means no start.
- **`StructurePiece`**: a `BoundingBox` and `place(writer, clip)`. Every chunk it
  crosses places it once, cut to that chunk, so it decides each block from its
  position (`StructurePiece.noise`), never from a random stream that advances as
  it writes.
- **`StructureIndex`** caches starts (LRU, `CACHE_SIZE` 512) for all generation
  threads, finds the starts reaching a box, and answers `at(x, y, z)` — type,
  start and piece — for the spawner, advancements and F3 (`World.structureAt`,
  which also checks the chunk's features).
- **`StructurePass`** writes the pieces reaching a chunk through a `ChunkWriter`,
  which refuses and counts any write outside that chunk.

`StructureTests` (8): boxes; starts inside their regions and `separation` apart
over 50 x 50 regions; starts equal from two indexes asked in opposite orders on
four threads, the cache within its bound and evicted starts recomputed alike;
overlapping, far-reaching and sky-high pieces refused, no start in the wrong
biome or for an empty assembly; the writer's bounds; **a 40 x 40 x 20 hall over
nine chunks, generated in row, reverse and shuffled order and on four threads,
identical every time and equal block for block to one placement into a buffer**,
with no write outside a chunk — once starting mid-region and once on a region's
first chunk, so that it reaches chunks of the region before; the index inside, beside and above the hall and
not in a chunk without the flag; the pass inactive without `structures2`, and
the shipped registry empty.

## Generated loot chests (GEN-04)

`StructurePiece.chest(writer, clip, x, y, z, tableId)` checks ownership by the
piece and writes only when the position lies inside its current cut.
`StructurePass` supplies the index's world seed to `ChunkWriter`; chest contents
come from `LootRegistry.fillChest`. Generation creates the CHEST block and its
27 slots on the detached chunk, without setting `modified`. Repeated placement
does not refill an existing chest, including one with empty contents.

Untouched loot is regenerated; a player's container edit marks the chunk
modified through the existing authoritative container path. `ChunkLoader`
restores the saved chest map after generation and before publication, replacing
generated contents even when the saved chest is empty or destroyed. No save
format or protocol change is required. `StructureLootTests` covers independent
generation on four workers, negative chunk borders, the pass's seed propagation,
overflow without partial writes, and actual save/load for changed, empty and
destroyed chests. Natural placement remains dormant until GEN-05 enables
`structures2`; the V1 templates are unchanged.

## Copper ore (GEN-08)

V2 appends six copper vein attempts per chunk, sizes 6–10, at y20–70, replacing
stone only. The random stream of all four V1 ores stays unchanged. Existing
gravel lenses take priority over copper, so this feature does not suppress
gravel or alter the V1 pass's output elsewhere. Block ID 48 and atlas tile 139
are appended; hardness is 10 and required pickaxe level is 1, following the
numeric requirement in the roadmap (the current data calls wooden level 1 and
stone level 2). Copper ore belongs to `ores` and smelts into a copper ingot in
eight seconds. `CopperOreTests` covers bands, determinism, unchanged non-copper
cells, block/item save round trips, the shared stone texture base and the
generated ore → mining → furnace → copper pickaxe crafting chain. All 120 V1
golden hashes still match.

Checks: `run-tests.ps1 -Only worldgen,core,chunk`. `WorldGenerationTests` covers
sparse structure spacing, biome textures, seams and falling-block conservation;
CoreTests retains deterministic generation, caves, ores and river invariants.
Knobs: [TUNING.md](../TUNING.md), `Biome`, `BiomeProvider`, `Caves`, `Rivers`,
`Structures.REGION_CHUNKS`, `Structures.MIN_CHUNK_GAP`.

## Vegetation and initial herds (2 October 2026)

New V2 worlds enable `vegetation2` and `livestockAtGen` as well as copper. Features
are stored per world, so older V2/V1 chunks keep their recorded flags. Terrain,
ore and V1 tree goldens remain unchanged across 120 fixture chunks. `TreeShapes`
shares the exact old tree placements with sapling growth; species metadata is set
only with the new vegetation flag. Vegetation is deterministic, biome-specific,
soil-supported and added after trees. Grass drops wheat seeds with 12.5% probability;
cold snowfall can replace vegetation with a layer. Herd persistence is in the
session/chunk section, separate from terrain generation.
