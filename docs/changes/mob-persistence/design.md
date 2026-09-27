# Design: mob-persistence

## Decisions

1. **The wire keeps `int` ids; no protocol bump.** The MOB-02 card asked for `varlong`.
   `S_MOBS` already writes the id as a `VarInt`, and the Living World slice promises no
   `NetProto.VERSION` change. Ids therefore stay in the positive `int` range: the counter
   is a `long` in memory and on disk, and after `Integer.MAX_VALUE` it wraps to 1,
   skipping ids that are live. Two billion spawns in one world is not a practical case;
   the wrap only keeps the invariant true.

2. **The counter gets its own level section `entity_ids`**, not a field in `clock` as the
   card suggested: a section's layout is fixed by its name (see `ChunkSectionCodec.TICKS`),
   so a new field would need a new name anyway. Payload: one `VarLong`, the next id.
   Older builds keep it as an unknown section.

3. **Ids are handed out in one place.** `EntityStore.assignIds()` gives an id to every mob
   whose id is still 0. The session calls it at the start of its mob tick, and it runs
   again before a chunk is snapshotted and before the host captures `S_MOBS`, so every
   path that adds a mob (spawner, `/summon`-like commands, the autopilot, tests) is
   covered without each of them knowing about ids.

4. **`entities` section layout** (fixed by its name):
   `VarInt count (≤ 256)`, then per record `VarInt length (≤ 1024)` followed by
   `UTF type, VarLong id, f32 x, f32 y, f32 z, f32 yaw, f32 health` and any bytes a newer
   build appended. The length prefix lets future fields be appended compatibly; the
   appended tail is kept and written back. A record of an unknown type stays with its
   chunk as a pending record: it never enters the world and is written back unchanged.

5. **Restored mobs enter like restored items.** A loaded chunk keeps its records as
   pending; when the chunk becomes live, `WorldSession.adoptChunkItems` (the listener the
   game and the server already call) also adopts its mobs. A record whose id is already
   live is dropped (a crash between two chunk writes), and adoption moves the counter
   past every adopted id.

6. **Eviction removes mobs after the write.** `Game.evictDistantChunks` already writes the
   doomed chunks and then drops their items in one pass; it drops their mobs in the same
   pass. Persistent ones are in the write; hostile ones simply go, as `despawnFar` would
   have removed them anyway.

7. **Write triggers.** Like `Chunk.savedItems`, a chunk remembers how many mobs its last
   write held (`savedMobs`), so a chunk a herd has left is written again without them.

## Risks

- A dedicated server never evicts chunks, and `despawnFar` now spares animals, so a server
  keeps every animal of every chunk it loaded. The spawner's caps bound the count per
  area; LW-03 (materialization) folds distant animals back into regional counts.
- A crash between two chunk writes can leave one mob in two files; decision 5 keeps
  exactly one of them.
