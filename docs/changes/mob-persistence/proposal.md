# Change: mob-persistence (MOB-01, MOB-02)

Status: approved through the Living World plan — milestone M1.5, EPIC P in
[ROADMAP_1_1.md](../../ROADMAP_1_1.md), owner decision of 2026-09-27. Roadmap cards:
MOB-01 «Сохранение мобов в чанке», MOB-02 «Стабильные id сущностей».

## Why

Mobs are never saved. `MobSpawner.despawnFar` removes every mob farther than 72 blocks
from the nearest participant, and nothing of a mob is written with its chunk, so a herd
left behind is gone and a killed cow is simply replaced by the spawner. Living World
needs animals that stay where they were — its populations turn into bodies and back
(LW-03), and a death has to last — and it needs one identity per mob across unload and
reload, for the network and to catch a mob written twice.

## What Changes

- Every mob whose temper is not `HOSTILE` is persistent: it is written with the chunk
  it stands in (new chunk section `entities`), leaves the world when that chunk
  unloads and returns when it loads.
- `despawnFar` never removes a persistent mob.
- Every mob carries a world-unique id from a counter saved in the level (new level
  section `entity_ids`); the id survives save and reload.
- `S_MOBS` carries that id instead of a per-session number, so a guest keeps the same
  mob across the host's unload and reload; a guest's hit names the mob by it.

## Capabilities

### New Capabilities

- `mob-persistence`: which mobs are saved, where, and how they come back.
- `stable-entity-ids`: allocation, persistence and network use of mob ids.

## Out of Scope

Babies, taming, names and breeding state (MOB-03, MOB-04); hostile mobs and lair spiders
(lairs are LW-04); regional populations (LW-01, LW-03); any change to the network
format or `NetProto.VERSION`; chunk eviction on the dedicated server (it keeps every
chunk it loaded, as today).

## Impact

- Code: `sim/EntityStore`, `sim/WorldSession`, `world/entity/Mob`,
  `world/entity/MobSpawner`, `world/Chunk`, `world/ChunkLoader`, `save/ChunkSnapshot`,
  `save/ChunkSectionCodec`, `game/Game` (eviction, level open and save),
  `server/DedicatedServer` (level open and save), `net/Multiplayer` (mob ids).
- Saves: chunk section `entities` (old chunks simply have none); level section
  `entity_ids` (old worlds start the counter at 1). Both are additive; older builds keep
  them as unknown sections.
- Network: no format or version change.
- Design notes: [design.md](design.md).
