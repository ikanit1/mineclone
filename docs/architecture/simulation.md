# Shared simulation

Updated: 2026-09-24. Paths in backticks are relative to the repository root.

The `sim` package holds what a single-player host and the dedicated server must
simulate identically. It never references `render`, `audio`, `game` or LWJGL;
`ParticipantTests` reads the package sources and fails on such a reference.
Time lives in `sim.WorldClock` ([clock.md](clock.md)).

## Participants (SIM-02)

`sim.Participant` is how the simulation sees a player: id, feet and eye position,
game mode, alive, armor and `damage(DamageSource, amount)`. It knows nothing
about the network. Two adapters exist:

| Adapter | Package | Position | Damage |
| --- | --- | --- | --- |
| `LocalParticipant` | `game` | the `Player`'s own vector | `Player.takeAttackDamage` for attacks, `takeDamage` for environmental kinds |
| `RemoteParticipant` | `net` | the last accepted snapshot, not the eased drawing position | forwarded as `S_PLAYER_HURT`; the guest applies its own invulnerability window |

`Multiplayer.participants()` is the live list, rebuilt after every session event:

- host: its own player (registered by `Game` with `setLocalParticipant`) and every
  guest that completed the `C_HELLO` handshake and has reported a position;
- dedicated server: the accepted guests only — it registers no local player;
- offline single player: the local player alone;
- client: empty — a guest mirrors the host's world and simulates nothing.

An actor that never completed the handshake never enters, whatever it sends.
A guest silent for `SILENCE_LIMIT` leaves the list and returns with its next
state while its handshake identity stands; only a transport leave ends that
identity. This keeps a guest whose game stalled (a slow load, a long hitch) a
target and keeps its checkpoints stored.

`Participants` is ordered by id. `nearest` and `forEachWithin` walk it by index
and allocate nothing; ties resolve to the smaller id, so the choice does not
depend on join order. `HOSTILE_TARGETS` accepts the living outside creative
mode. A remote guest whose mode has not been announced reads as creative and is
therefore never a target by accident.

`world.damage` holds the vocabulary the participants speak: `DamageType`
(attack kinds respect the invulnerability window, environmental kinds bypass it;
drowning, starvation and poison bypass armor), `DamageSource` (kind, attacker
number and type, origin, knockback) and `ArmorView`, which stays `NONE` until
equipment slots exist (CMB-01). Converting the existing damage paths to these
types is SURV-01.

Consumers arrive with the next tasks: mobs choose targets among participants
(SIM-07), block ticks, furnaces and spawning run around every participant
(SIM-05). `DedicatedServer.centre()` still selects the first player until then.

## World session: mobs (SIM-03)

`sim.WorldSession` is the simulation of one open world; the game's host and the
dedicated server both run it. Step one holds the mobs:
`tickMobs(dt)` senses herds and prey every `SENSE_INTERVAL`, updates group
tactics, ticks each mob, lands wolf bites, retires the dead, pushes mobs apart
and out of the focus, despawns and spawns — in the order `Game.updateMobs` ran
them. The mobs live in `sim.EntityStore`, which `NetContext.mobs()` exposes; on a
guest the store holds the host's snapshots and no session exists.

| Seam | Game (host) | Dedicated server |
| --- | --- | --- |
| `WorldEvents` — what to show | `game.EntityPresentation`: voices, splashes, rage, footprints, fire, deaths, blasts, sound cues | `WorldEvents.NONE` |
| `Host.mobFocus` | its own player | `centre()`: the first guest, else the spawn point |
| `Host.hostileMobs` | survival mode | `!creative` in `server.properties` |
| `Host.focusIsBody` | yes: mobs are pushed out of the player | no: the focus is only a point |
| `Host.mobStruck` | player damage and knockback | nothing yet (SIM-07) |
| `Host.projectileTargets` | its own player and the guests | the guests |
| `Host.itemCollector` | its own player picks up items and its arrows | none: guests ask with `C_ITEM_PICK` |
| `Host.blasted` | a local player is thrown back | nothing |

`WorldEvents` only reads; the session calls it in simulation order. `Host` is
temporary by design: blows go to `Participant.damage` of a chosen target with
SIM-07, and the single focus gives way to all participants with SIM-05.
Collisions handle each pair once, by the earlier mob in the list
(`MobSpatialGrid.order`), without the per-frame identity map the game used to
allocate.

The server gained one behaviour by sharing the loop: its mobs are now pushed
apart like the host's instead of standing inside one another.

## World session: items, arrows, blasts, drops, saving (SIM-04)

`EntityStore` also holds the items lying about and the arrows; `NetContext`
exposes them. The session ticks both for the host and the server:

- **Items** fall, float, merge every `ITEM_MERGE_INTERVAL` and expire; past
  `MAX_ITEMS` the oldest goes. `addItem` / `dropStack` are the one door for
  loose stacks on the host. Items restored with a chunk enter through
  `adoptChunkItems`: the game calls it when the chunk's mesh arrives, the server
  from `ChunkLoader.setChunkLiveListener` once the chunk's light is in.
  `adoptFallingDrops` takes what landing sand broke.
- **Arrows** fly and strike mobs and the targets the host names. Mob arrows now
  exist on the server too: skeletons shoot there instead of closing in.
- **Blasts** (`explode`): the blocks the centre can see go (`Explosion`), every
  participant in range gets `Participant.damage` with an `EXPLOSION` source —
  guests included, over `S_PLAYER_HURT` — every other mob is hurt, then
  `WorldEvents.explosion` shows it.
- **Drops** (`dropLoot`): an eaten mob drops nothing, nor does a mob killed by a
  creative participant; everything else rolls its loot table, natural deaths
  included, whatever mode the host plays in. `Mob.killer` is the participant
  number of the lethal hit: the host's melee (`hurtLimb(..., attacker)`), a
  guest's `C_MOB_HIT` (`Mob.hurtBy`) and an arrow's owner
  (`Hittable.participantId`) pass it. A guest reads as creative until it
  announces its mode, so its kills in the first two seconds drop nothing.
- **Saving** (`snapshotChunk`): blocks, containers and the items lying in the
  chunk — live ones and restored ones not yet adopted — in one snapshot, or null
  when nothing changed. The game and the server call the same method.

`ServerSimulationTests` drives the real server tick: an item on the ground
(which used to throw out of the tick), items saved in a chunk surviving a server
session (the server used to erase them on its first save), a guest's arrow that
flies and lands (it used to hang where it was shot), a creeper that blows a
crater and hurts the guest (it used to die silently), a guest's kill that drops
beef on the server and at the guest, and a creative guest's kill that drops
nothing. The parity hash was re-recorded before this move with a transcription
of `Game.detonate` (`a8e5fd7`) and the session reproduces it.

`SessionParityTests` pins the loop. Its scenario — seed 20260922 at night, nine
placed mobs (two cows standing in each other, a pig in the participant's way), a
participant on a fixed path striking the nearest mob every two seconds, natural
spawning, 600 ticks of 50 ms — is hashed tick by tick, with every block the run
changes. The expected hash was recorded by a transcription of `Game.updateMobs`
before the move (commit `79318ba`); pushing a pair twice, never pushing mobs out
of the participant, or changing the sense interval fails it. Wolf kills are not
reached by the scenario. The same run with a recording `WorldEvents` gives the
same hash, and the dedicated server's source contains no mob rules of its own.
