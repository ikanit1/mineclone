# Save opening and preservation

The current writer uses `level.dat` version 11 and chunks version 8 with minimum
chunk reader 8. Level versions 1–10 and chunk versions 1–7 remain readable.
Opening an older world schedules an original-byte `*-migration.zip` backup
before the first mutation or write.

## Optional ecology state (LW-02)

The bounded level `ecology` section stores regions, species ResourceIds, daily
trends, loss counters and reservations of existing animal ids. Limits are 4 MiB,
4096 regions, 16 species, 65536 reservations, 4 opaque lair records and 16 opaque
scar records per region. Unknown species and record tails survive rewriting.
The `livingWorld` flag appends an LWR1/version/boolean footer to `rules`, preserving
all earlier rule bytes. It starts off until LW-13.

Game and dedicated-server opening restore these sections before adopting chunk
animals; a reservation advances the world id counter before any new body can
claim an id. Saving writes ecology before the updated `entity_ids` snapshot.
Missing ecology initializes lazily. Newer layouts, rules or a combined species
table beyond the reader's bound disable ecology and preserve raw bytes.

Damaged bytes rebuild only after `preserveSectionEvidence` completes: it queues
the session backup, writes a unique evidence file and forces it to disk. Failure
leaves the section opaque and the feature disabled. Evidence files never replace
the original world. Body reservation accounting is described in
[simulation.md](simulation.md).

`ecology_fields` (LW-06) is an additional optional level section: FHLD magic,
version 1, region count, then signed region coordinates and sparse per-chunk
crop counts. There are at most 4096 regions and 256 chunk cells per region;
each count is 1–256, and zero cells are omitted. The estimated crop total yields
one rabbit food point per four cells, capped at 64 per region. Saturating a
chunk's stored count at 256 cannot reduce that ceiling. The largest supported
payload, including a preserved tail of at most 4096 bytes, stays below 3.2 MiB
and the outer 4 MiB section bound.

Loaded observations replace only their own chunk's last estimate. Saving and
`WorldSession.snapshotChunk` capture it before writable terrain is evicted;
unloaded fields keep their previous estimate across reopening. Actual plants
remain in blocks/meta and regrowth in `ticks`; the sparse crop index is rebuilt
from block data. Rabbit appetite and navigation have no new saved fields.

Missing field data starts with an empty observation table. A newer field format
disables Living World and preserves both original ecology sections and rule bytes.
Unknown version-1 tail bytes round-trip unchanged. Damaged data rebuilds from
loaded land only after the same durable evidence callback succeeds; if evidence
preservation fails, the raw field and ecology payloads remain opaque and the
feature stays disabled. This optional section does not change the level header
or chunk reader requirement. [Field-raids test plan](../changes/field-raids/test-plan.md)
separates these save checks from real gameplay acceptance.

## Opening a world

`SaveManager.readLevel(id)` returns a sealed `LevelLoad` result:

| Result | Game/server action | Disk action |
| --- | --- | --- |
| `Absent` | Create a new world | A subsequent explicit save creates the file |
| `Loaded(data)` | Restore the saved seed, player and state | None during reading |
| `Unreadable(reason)` | Refuse to open | Preserve the original path and bytes |
| `TooNew(what, version, supportedVersion)` | Refuse to open | Preserve the original path and bytes |

An existing directory or an inaccessible path is never treated as absent.
`WorldOpenPolicy.decide` is the common pure decision used by the graphical game
and dedicated server. The server returns code 2 before opening network doors when
the level cannot be read. The game displays an error and keeps the existing menu
or world. The world selectors and Continue button use `WorldInfo.playable()`;
future saves and damaged saves have distinct descriptions.

`loadLevel` remains a compatibility adapter for tools that accept a nullable
result. Opening a world must use the typed API, not the adapter. Failed level
reads also latch a write guard inside that `SaveManager`, so a fallback caller
cannot replace the level. A new manager is required after an external repair.

## Chunk recovery

`readChunk(id, cx, cz)` returns `Absent`, `Loaded`, `Unreadable`, or `TooNew` and
does not modify disk. The loader restores into a detached generated chunk before
publishing it through `World.publish` (SAVE-03).

For an unreadable chunk, `quarantineChunk` moves the regular source file to
`chunks/c.x.z.dat.corrupt-<epochMillis>`. The move never replaces an existing
evidence file; a colliding timestamp advances to the next free name. Only a
successful move removes the write guard. The generated replacement can then be
edited and saved normally. Quarantine evidence is not part of regular chunk
saving and is retained across reopening the world.

If quarantine fails, the source remains protected and the generated chunk is
marked read-only. A chunk from a future format is also read-only. Gameplay may
display generated terrain in that location, but this session never persists its
changes over the protected source. Both the game/server save loops and
`SaveManager.saveChunkAsync` enforce this boundary; even a caller that bypasses
`Chunk.isReadOnly()` cannot overwrite a failed-read file.

`WorldWarning` records are queued safely by worker threads and drained by world
id on the consumer thread. The messages distinguish successful quarantine,
future format and failed quarantine. Repeated reads do not flood the toast queue.

Reads, quarantine and background writes share the manager's monitor so a queued
write cannot interleave with preservation. Queue bookkeeping uses a separate lock
so a long backup never blocks `saveLevel` merely by holding the I/O monitor.
The raw input stream is registered
separately in try-with-resources: a malformed gzip header may throw while its
wrapper is being constructed, and leaving the raw stream open would prevent
Windows from renaming the source. Reading to EOF validates the gzip trailer and
CRC, including files whose full payload was readable before a damaged trailer.
Invalid collection counts, duplicate level sections and oversized sections are
rejected rather than silently truncated or allocated without bounds.

## Verification

`SaveSafetyTests` uses temporary save roots and the real `DedicatedServer.run`
refusal path. It checks missing files, future versions, directory paths, corrupt
gzip headers and trailers, untouched source bytes, quarantine and warning
delivery, replacement round trips, failed quarantine, and writes that bypass
the gameplay guard. The server refusal tests disable transports as an additional
boundary; world validation itself occurs before transport setup.

`tools/CheckSaves.java` stays read-only. It prints typed read failures and a
`quarantine` column counting retained evidence files. Existing quarantines are
reported separately from current unreadable files; evidence alone does not make
the current replacement fail validation.

## Backups and restore

`beginWorldSession(id)` queues one snapshot per explicit opening on the same
executor as chunk, guest and level saves. Missing worlds need no snapshot.
Standalone writes lazily establish that gate as well. A failed backup prevents
all dependent writes and quarantine operations. `backupWorld(id, "manual")` is
ordered behind already queued saves and supports the server `/backup` command.

Snapshots include the level, icon, players, every persisted chunk and ledger,
and retained corrupt-file evidence. They exclude temporary files and the backups
directory itself. The default rotation keeps five regular archives plus migration
archives younger than seven days; `server.properties` can set `backups`.

Restore extracts into an unpublished staging directory, rejects traversal,
duplicate paths, invalid levels and excessive expansion, then publishes a new
world directory. The original world and archive are unchanged. All restored
chunk/player/ledger bytes match the archive. The level retains gameplay data
and receives a display name with the backup date. The world selector's
“Бэкапы…” screen lists copies and opens the restored world after completion.

Explicit generator upgrades use the same ordered writer and a fresh pre-upgrade
snapshot. `upgradeWorldGen` is for closed worlds: it reads all stored player
anchors, writes the pinned ledger atomically, then writes the level with new
generator settings. Errors complete the returned future exceptionally; they
never publish new settings to the caller. The level remains on V1 after a failed
commit, and any already-written V1 pins remain safe. See [worldgen](worldgen.md).

`tools/WorldBackupBenchmark.java` measured 200 MiB of deterministic, incompressible
temporary input at **5.428 seconds**, with a 209,804,472-byte ZIP on this machine.
The game therefore renders a progress screen while the session backup future is
pending; restoration also runs asynchronously.

## Section formats and write ordering

The level header is `MAGIC, version, minReaderVersion, writtenBy UTF`, followed by
`SectionCodec` entries: VarInt count, UTF name, VarInt payload length, payload.
The codec bounds count at 64, each payload at 4 MiB, and combined payloads at
64 MiB. Duplicate names, invalid counts and oversize lengths fail closed.

Version 11 stores `world` (name/seed/lastPlayed), `player_format` (a marker),
`player` (the host's `PlayerRecord`), `world_spawn`, `clock` (the precise
`WorldClock` state), `rules` (game-mode prefix), `worldgen` (generator version,
recorded 1.1 features and last upgrade; see [worldgen.md](worldgen.md) — a generator
this build lacks refuses the world as too new) and opaque extra sections. Unknown sections survive rewriting. A version 12
file with minimum reader 11 is accepted; minimum reader 12 is refused. A 1.0
reader rejects version 11 before reading any new fields.

The development build of M0 wrote v11 without the marker: a fixed `player`
payload (pose, spawn, slot, health, hunger) beside `inventory`, `pending` and
`mineclone:survival_progress` sections. The reader tells the two apart by the
marker, never by trying one parser and then the other, and such a level is
backed up as a migration before its first rewrite.

## Player records (SAVE-07)

`save.PlayerRecord` is one player's checkpoint in the same shape everywhere: the
host's `player` level section, a guest's `players/<uuid>.dat`, and — through the
`net.PlayerData` adapter — protocol v7. `Game.capturePlayer` is the single
place that gathers it. The record is immutable and copies stacks in and out.

`PlayerRecordCodec` writes named sections: `format` (magic, version 2, minimum
reader), `pose` (double position, yaw, pitch, slot), `vitals` (health, hunger,
saturation, exhaustion, air in 20 Hz ticks up to 300), `inventory` (exactly 36),
`equipment` (5: head, chest, legs, feet, offhand), `pending` (cursor/grid stacks),
`effects` (by name), `spawn` (personal, optional), `advancements`, `recipes`,
`progress` (opaque `SurvivalProgress` bytes) and unknown sections verbatim.
`format`, `pose`, `vitals` and `inventory` are required. Each section reads to
its exact end. Item sections fail closed; a damaged `effects` section alone is
kept byte for byte, reported in `warnings()` and the items still load.
Records from before the fields existed get saturation `min(5, hunger)`, zero
exhaustion and full air.

Guest files: version 1 is `MAGIC, 1, <protocol-v7 checkpoint>`; version 2 is
`MAGIC, 2, minReader, <record>`. Version 1 is read and rewritten as 2 by the next
save, which is queued behind the session backup. A guest file that fails to
read — damaged, or requiring a newer reader — refuses the guest's join and is
never overwritten by that `SaveManager`. Since protocol v8 (NET-02) the record
itself goes on the wire (`PlayerData.write`); the v7 layout survives only as
`PlayerData.readV7` for version-1 files. The host applies a guest's checkpoint
with `PlayerRecord.mergeClient`: the guest's pose, vitals, items, equipment,
progress, advancements and recipes; the host keeps effects, the personal spawn
and sections the guest's build may not know (`InventoryNetworkTests`).

`PlayerRecordTests` covers per-section round trips, immutability, damaged and
missing sections, too-new files, v1 migration with its backup, opaque sections
over five saves, host/guest byte identity and the recordless M0 level.

Chunk version 7 introduced the minimum-reader header and section envelope.
Version 8 keeps the same RLE blocks/meta and envelope with `chests`, `furnaces`,
`items`, and opaque extras, but writes minimum reader 8. This gate protects
the canonical `entities` format from both incompatible version-7 branch codecs:
older readers return `TooNew` before interpreting the body, never quarantine it
as corruption, and never overwrite its terrain. Every newly written chunk gets
this header, including one containing only an empty generation spawn receipt.
Historical version-7 files still load; future additive envelopes remain readable
when their minimum reader is at most 8.
The extras travel through `ChunkSnapshot` and `Chunk` across load/unload cycles.

`trample` (LW-07, optional) stores version 1, the last simulated absolute game
day, and at most 4096 sorted `(cell index, pressure byte)` entries. It is absent
for untouched chunks. A damaged section fails the chunk read; its source is
preserved under the normal quarantine policy. A section version from a newer
build makes the chunk read-only instead. Pressure decays only on the host's
simulation thread after publication; the save writer receives a copied byte
array, and a pressure-only change still marks the chunk for saving. Grass meta
mirrors only visual stage 0–5, so existing block updates and chunk deltas show
the same path to guests; the section remains the exact host pressure. Loading
an older LW-07 chunk with pressure and zeroed grass meta reconstructs its
visual stage before publication. Older dirt-block paths are preserved and
regrow under the original rule when their pressure expires.

`ticks` (BLK-04, optional — written only when a block asked for a tick) is
`VarLong writtenAt, VarInt count`, then per tick `VarInt index, u8 kind,
VarLong zigzag(due - writtenAt)`: dues relative to the world tick of the write,
so a tick twenty ticks ahead costs two bytes in a world of any age. A chunk
without it has no ticks, which is every chunk before BLK-04; no migration. The
reader refuses (quarantine, as for any section) a count above 32768, a cell
outside the chunk, a duplicate cell and kind, a due before tick zero or trailing
bytes. A kind the build does not know is kept and written back. An incompatible
layout would take a new section name; `ticks` is never reinterpreted. What the
dues mean after a load — lateness, the two-day cap, a clock behind the save — is
in [blocks](blocks.md#scheduled-ticks-blk-04).

`entities` (MOB-01/04, optional) uses canonical schema 2:
`i32 0xD94D4F42, u8 version, VarInt count` (≤ 1024), then per mob
`VarInt length` (≤ 2048) and that many bytes:
`UTF type, VarLong id, f32 x, y, z, yaw, health, age, boolean persistent,
f32 loveCooldown, love`, followed by an opaque record tail. The original
headerless MOB-01 records (≤ 256, record length ≤ 1024) and the generation
branch's `u8 version1, i32 count` layout migrate on read. The tail is kept on
the mob and written back; an unknown type stays with its chunk as a pending
record, never enters the world and is written back unchanged. Invalid bounds,
duplicate IDs, non-finite values or trailing section bytes fail the read.
A future entity schema returns `TooNew` and keeps the source read-only.
An explicit empty `entities` section records that the generated herd was
already adopted, so dead or departed animals are not generated again. A chunk
without the section has no saved mobs. Only persistent mobs are written; a
chunk that held mobs at its last write is written again, so a herd that walked
away leaves no copy behind, and every chunk with animals is rewritten at each
save, as chunks with lying items already were.

The legacy level section `entity_ids` (MOB-02) holds the next mob id as one
`VarLong`. It is bridged into the shared `clock` schema-2 allocator before
ecology reservations or saved bodies are adopted. IDs are positive `long`s;
protocol 12 `S_MOBS` carries them as `VarLong`. Both counters are captured after
new bodies receive IDs. Details: [simulation](simulation.md#mobs-stay-where-they-were-mob-01-mob-02).

`chunks/ledger.dat` (GEN-02) records which generator made each chunk; its format
and meaning are in [worldgen.md](worldgen.md#chunk-ledger-gen-02). For saving it
behaves like a chunk: `saveLedgerAsync` queues the whole file behind the session
backup, a read sees the queued bytes, a world that failed its read check never
gets it written. Unlike a chunk it never stops a world from opening:
`openLedger` moves a damaged file aside as `ledger.dat.corrupt-<millis>` — or,
when the backup or the move fails, leaves it in place and latches a write guard
for that `SaveManager` — and rebuilds the ledger. `savedChunkKeys` lists only
`c.<x>.<z>.dat`, so the ledger is never taken for a chunk.

`saveLevel` serializes a complete immutable byte snapshot on the caller, then
queues all filesystem operations behind the session backup. It does not wait
for the writer. `readLevel`, the nullable adapter and world-list refresh wait for
the relevant pending level writes, preserving read-after-write behavior.
Executor-internal reads bypass that barrier to avoid waiting on a later task in
their own queue. `uniqueWorldId` reserves queued worlds before their directories
exist. `flushAndAwait` remains required before shutdown or inspecting raw files.

Chunk writes capture detached copies and retain the latest checkpoint per file
until its write succeeds. `readChunk` validates disk protection first, then returns
an independent copy of that pending checkpoint; eviction/reload cannot restore an
older disk version while the queue is busy. An older completion cannot clear a
newer pending checkpoint, and failed writes retain the latest state in memory for
a corrected subsequent save. This does not make an unresolved write failure durable
across process exit. `QueuedChunkSaveTests` covers writer backlogs, mutation
isolation, completion ordering, write failure/retry and protected disk evidence.

`LevelFormatTests` holds the writer behind a deterministic latch, verifies that
`saveLevel` returns while it is blocked, mutates the original inventory and
section arrays, then confirms the queued bytes and read barrier are correct.
It also checks additive future versions, minimum-reader refusal, original-byte
migration archives, clock validation and section bounds.

Fsync durability, region storage and automatic data repair remain separate
roadmap tasks.

## New optional sections (2 October 2026)

`mineclone:rules` in the level is a strict versioned payload. Absent rules migrate to NORMAL
with inventory retention and commands enabled, preserving legacy behaviour.
`clock` section version 2 appends the last allocated stable entity ID; version 1 remains
readable. `entity_ids` bridges the earlier next-id counter into that clock before
adoption/materialization/breeding, so both branches share one long allocator.
An older build supporting only clock version 1 may refuse world initialization
even though the outer level remains version 11. The chunk-8 gate protects terrain
when an older reader encounters it; it does not guarantee that the complete world
can be opened or played in an older build.
Canonical `entities` version 2 stores persistent mob IDs, type, position, yaw,
health, age and breeding clocks in bounded length-delimited records. Both the
original headerless records and the generation branch's byte-version-1 layout
migrate on read; unknown record tails stay intact. Validate a complete batch before publishing.
Future schemas/type names are kept opaque through the existing chunk-section
protection and backup mechanism.

Death inventory, equipment and pending stacks become host-owned ground items at
600-second lifetime. Ordinary items retain 300 seconds. Optional chunk section
`item_lifetimes` supplements legacy `items` without rewriting its layout; count,
finite values and limits are validated before publication. Snapshot copies retain
lifetime. Player equipment preserves opaque future tail cells; known effect names
accept both legacy bare names and `mineclone:` IDs, unknown effects round-trip.

The save queue's detached snapshot copy also carries lifetime; the five-cycle
loader regression uses a ten-minute item to cover this boundary. A death while
an equipment/chest menu is open captures that host-owned cursor/ingredients
directly instead of returning them to a full inventory and spilling a duplicate.
