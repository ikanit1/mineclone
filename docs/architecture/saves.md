# Save opening and preservation

The current writer uses `level.dat` version 11 and chunks version 7. Level versions
1–10 and chunk versions 1–6 remain readable. Opening an older world schedules an
original-byte `*-migration.zip` backup before the first mutation or write.

## Opening a world

`SaveManager.readLevel(id)` returns a sealed `LevelLoad` result:

| Result | Game/server action | Disk action |
| --- | --- | --- |
| `Absent` | Create a new world | A subsequent explicit save creates the file |
| `Loaded(data)` | Restore the saved seed, player and state | None during reading |
| `Unreadable(reason)` | Refuse to open | Preserve the original path and bytes |
| `TooNew(version, supportedVersion)` | Refuse to open | Preserve the original path and bytes |

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
`WorldClock` state), `rules` (game-mode prefix), `worldgen` (generator version)
and opaque extra sections. Unknown sections survive rewriting. A version 12
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
never overwritten by that `SaveManager`. Protocol v7 carries only pose, health,
hunger, inventory, pending and progress; `PlayerRecord.mergeLegacy` applies a
guest's checkpoint to the stored record so equipment, effects, spawn and
unknown sections survive (`InventoryNetworkTests`). The record goes on the wire
itself with protocol v8 (NET-02).

`PlayerRecordTests` covers per-section round trips, immutability, damaged and
missing sections, too-new files, v1 migration with its backup, opaque sections
over five saves, host/guest byte identity and the recordless M0 level.

Chunk version 7 adds minimum reader 7, keeps RLE blocks/meta, then writes the
same section envelope with `chests`, `furnaces`, `items`, and opaque extras.
The extras travel through `ChunkSnapshot` and `Chunk` across load/unload cycles.

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
