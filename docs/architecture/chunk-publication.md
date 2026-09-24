# Chunk publication and ownership

Saved worlds load chunks through `ChunkLoader.loadNow(cx, cz)`. Background generation
calls the same method. Startup, server startup and respawn therefore share one path:

1. Look up an already published chunk and return it without restoring it again.
2. Generate a worker-private candidate with `World.generateDetached`.
3. Read the typed chunk result. Restore blocks, metadata, containers and pending
   items only on the detached candidate. Quarantine unreadable files before allowing
   replacement; a too-new file or failed quarantine marks the candidate read-only.
4. Recompute local sky light after restoration and clear `modified` while private.
5. Register the pending light barrier, then publish with `World.publish`.

`World.publish` uses `ConcurrentHashMap.putIfAbsent`. The returned object is the
winner; another candidate can never replace an existing player's edits. Insertion
establishes visibility of all preceding snapshot writes. Loader locks serialize
competing synchronous/background loads of the same coordinate, including quarantine.
Different coordinates use 64 lock stripes, so collisions may serialize some loads.

The generation worker performs no block/container writes after publication. The
world's creating thread owns published blocks, chests, furnaces and pending items.
Container getters return live mutable values, so callers must retain this ownership
when using returned stacks or furnaces. Save snapshots make deep copies on the world
thread; the writer only serializes those copies. `Chunk.modified` is volatile.

Eviction can enqueue a save and reload that coordinate before the writer reaches
it. `SaveManager` therefore retains the newest detached checkpoint per chunk until
that exact write succeeds. Reads validate the disk file's corruption/version guards
first, then return a deep copy of the newest pending checkpoint. They never wait
on the writer while holding the save monitor. An older write cannot clear a newer
pending edit; a failed write retains its checkpoint for reload and replacement by a
later save. Retention protects the running session, not process termination after
an unresolved disk failure. Protected disk evidence always takes priority.

The main-thread `drainLightFlood` handles cross-chunk block light, lava activation
and neighbour mesh invalidation. A light barrier observed before its chunk is
published remains queued, preventing a lost flood. Falling-block scans are queued
only for the winning restored chunk and processed by the simulation thread.

`World.getChunk` is a pristine-generation convenience for menu worlds, network delta
baselines and isolated tests. It must not load a saved world. Saved-world calls and
published container accesses from another thread fail when Java assertions or
`-Dmineclone.checkThreadOwnership=true` are enabled. Chunk ownership checks capture
that setting when the chunk is created. These checks expose accidental worker access
without adding locks around mutable gameplay containers.

`ChunkPublicationTests` covers a latch after real terrain generation and a second
latch after restoration but before insertion, the first publication winner,
repeat-load preservation, ownership checks and the actual network delta serializer.
Its stress case runs 20 rounds of 300 gzip saves with four loading workers while the
main thread edits blocks/metadata/chests/furnaces and queues disk writes. Every round
compares both live chunks and re-read saves against the prior snapshot plus edits.
The stress terrain is deliberately empty; the production generator is exercised by
the deterministic latch case and the generation suite. Run via the `chunk` category
or directly with `com.mineclone.ChunkPublicationTests`.

`QueuedChunkSaveTests` additionally blocks the writer to reproduce eviction/reload,
checks mutation isolation and out-of-date completion, forces a real codec write
failure followed by a corrected save, and verifies that pending data cannot mask
unreadable or too-new disk files. It runs in the `save` category.
