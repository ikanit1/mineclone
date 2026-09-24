# SAVE-07 / SIM-02 preflight

Status: **analysis only, implementation pending**. Inspected after M0 work on
2026-09-24. This audit does not change source, save formats, simulation or benchmark
baselines. Recheck the listed integration points before starting M1.

## SAVE-07: state and format boundaries

Preserve host position precision (`LevelData` uses doubles; `PlayerData` currently
uses floats), yaw/pitch, selected slot, health/hunger, all 36 inventory slots and
their item components, pending stacks, and `SurvivalProgress`. A future immutable
`PlayerRecord` must also preserve equipment, effects, advancements, recipes and
unknown section bytes through item-only updates, container gestures, pickup/drop
checkpoints and asynchronous saves.

Concrete integration points:

- `save/LevelData.java`, `save/SaveManager.java`: current v11 `player` section is a
  fixed payload of six doubles plus yaw/pitch/selected/health/hunger. It is **not**
  a nested PlayerRecord; `inventory` and `pending` are separate level sections.
  Introduce an unambiguous format discriminator and retain a reader for this M0
  v11 layout. Reusing `player` with an indistinguishable payload would break worlds
  created by M0. Do not infer its layout by trying permissive parsers in sequence.
- `data/SectionCodec.java`: framing already bounds sections and rejects duplicate
  keys. Player-record decoding must distinguish critical inventory failure from
  an optional effects payload failure. Preserve uninterpreted bytes; do not turn a
  damaged checkpoint into a new empty player.
- `SaveManager.loadGuest/saveGuest`: existing guest files contain
  `MAGIC, version=1, PlayerData bytes` inside gzip. Read v1, write v2 with its own
  version/minimum-reader envelope and sections. Retain backup/write ordering and
  snapshot deep-copy behavior. Keep stable installation UUIDs; nickname changes
  must not create a different inventory.
- `game/Game.java`: unify `saveAll`, `startWorld`, `pendingPlayerItems` and the
  `NetContext.capturePlayerData/restorePlayerData` adapter. Capture must include
  cursor, crafting ingredients and queued drops without changing ownership.
  Restore first discards the old remote window **without returning its contents**,
  then applies the checkpoint and distributes pending overflow. Preserve movement,
  animation and chunk-streaming resets after applying the restored pose/vitals.
- `server/DedicatedServer.java`: `saveLevel` retains the loaded `ownerTemplate`.
  A dedicated server has no local player; retain the owner's complete record
  rather than capturing a fabricated empty participant.
- `net/PlayerData.java`, `net/InventorySync.java`, `net/NetContext.java`: preserve
  host authority on reconnect, menu ownership handoff, sequence/ack deduplication,
  inventory locks during transfers and recovery of queued drops. An item-only
  `withItems` replacement must retain every other record section.

`LevelData.spawn*` currently represents **world spawn**, including the existing
bedroll behavior. Guests do not yet have personal spawn. Keep world spawn separate
from the new per-player spawn section; server saves must not lose it.

The network format change is wider than `C_PLAYER/S_PLAYER`: `PlayerData` is also
embedded in `C_OPEN`, `C_DROP`, `C_PICKUP` and `S_PICKUP`. Switch every affected
envelope together under NET-02/protocol v8. A storage migration alone must not
silently change existing v7 packet bodies.

## SIM-02: participant boundaries

Add `sim/Participant`, `sim/Participants`, a local adapter and
`net/RemoteParticipant`. Expose the collection in `Game` and `DedicatedServer`:
single-player/host has local player plus guests; dedicated server has guests only;
client has an empty simulation collection.

- Update membership across every `Multiplayer` path: `onActorJoin`, lazy
  `player(actor)`, explicit leave, silence-timeout `removeIf`, reconnect and
  stop/clear. A transport placeholder must not become an active target before
  successful identity handling and a first accepted position.
- `RemotePlayer.position` is the rendered, interpolated position. Its private
  `to` vector holds the last accepted snapshot. Expose authoritative snapshot
  position for simulation rather than coupling targeting to interpolation speed.
- Derive alive state from health and `F_DEAD`; mode comes from `X_PLAYER_INFO`.
  Use existing `onHurt -> Multiplayer.hurtPlayer` for remote damage. Do not add a
  dedicated-server avatar: its current `playerPosition()` deliberately returns null.
- `nearest`/`within` must avoid per-query collections and vectors and use a stable
  tie order. Do not change the current AI or centre-of-simulation behavior during
  this adapter-only task; that belongs to SIM-03/SIM-07.
- Resolve the roadmap boundary conflict explicitly: AC-01 forbids imports from
  `game` in `sim`, while SIM-02 proposes `sim.LocalParticipant` wrapping
  `game.Player`. Recommended placement is `game.LocalParticipant`, implementing
  the neutral interface in `sim`. `ArmorView` and `DamageSource` are not present
  yet; introduce only neutral contracts needed by the adapter, without pulling
  presentation dependencies or future combat behavior into this step.

## Recommended bounded order

1. Add PlayerRecord, its codec and immutable snapshots; freeze migration fixtures
   for guest v1 and the current M0 level v11 layout before replacing either writer.
2. Add disk adapters and strict/read-only failure behavior while retaining v7
   networking. Preserve unknown sections and dedicated owner records.
3. Unify capture/restore and test pending ownership transitions.
4. Add Participants and lifecycle adapters without changing simulation behavior.
5. Switch all affected packet envelopes atomically with NET-02/protocol v8.

## Required regressions

- Every record section round-trips; unknown sections and item components survive
  item-only updates and repeated saves; mutation after queueing cannot change the
  saved snapshot.
- Guest v1 migrates to v2; M0 level v11 migrates without field/precision loss;
  damaged inventory refuses entry, while a damaged optional effects payload does
  not prevent reading inventory. Unsupported minimum-reader versions stay intact.
- Keep `DedicatedServerSaveTests`, `SaveMigrationTests`, `LevelFormatTests`,
  `InventoryNetworkTests` and `ContainerBreakNetworkTests` green.
- Exercise real TCP/LAN reconnect with changed nickname, open-container cursor,
  queued drops, pickup before acknowledgement, full inventory overflow and stale
  container gestures. Inventory/container/ground-item totals must remain constant.
- Participant lifecycle tests cover join/leave/timeout/reconnect, no server avatar,
  no client simulation, dead/creative target exclusion, stable nearest ties and
  authoritative position independent of render interpolation.

File paths above are relative to `src/main/java/com/mineclone`; tests are under
`src/test/java/com/mineclone`. This document records implementation guidance, not
evidence that SAVE-07 or SIM-02 is complete.
