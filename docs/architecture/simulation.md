# Shared simulation

Updated: 2026-10-02. Paths in backticks are relative to the repository root.

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
number and type, origin, knockback, limb, the `PLAYER` flag) and `ArmorView`,
which stays `NONE` until equipment slots exist (CMB-01). Every damage path in
the game speaks it since SURV-01 — see [combat-mobs.md](combat-mobs.md#damage-surv-01).

Their consumers: mobs choose targets among participants (SIM-07), and block
ticks, furnaces, spawning and despawning run around every participant (SIM-05).

## Finite wildlife (LW-01…03; LW-10 diagnostics)

`WorldSession.ecology()` owns the opt-in Living World state for the host and
dedicated server. Guests mirror ordinary mob snapshots. `Participant.viewDirection`
reads the local camera or last accepted remote angles; an unknown direction fails
closed when testing spawn visibility.

`EcologyModel` copies regional snapshots and applies deterministic daily births,
abstract wolf predation, and simultaneous migration between cardinal neighbours.
Regions are 256×256 blocks and start lazily at 70% of climate capacity. Loaded
terrain refreshes their greenery estimate without generating new chunks. Catch-up
uses at most the final 60 elapsed days and never rewinds a newer regional day.

Population includes live and saved bodies. Reservations by world mob id protect
those bodies from abstract deaths and migrations. Materialization consumes only
unreserved animals, on loaded terrain at least 32 blocks from every living player
and outside their views. A session death releases one reservation and removes one
animal, with player/predator/fire/other counters. Existing bodies are retained when
an older world first enables the rule. Actual breeding calls `Ecology.born` after
assigning the child its shared clock id, adding exactly one animal and reservation.
Saving and returning a child retain its age and breeding state.

Every two seconds, animals over 128 blocks from every living participant park in
writable loaded chunk records with their identity, health and opaque tail intact.
Returning participants adopt them once; disabling the rule wakes parked records.
Read-only or full chunks keep the body live. With the rule off, the legacy spawn
path and recorded simulation trajectory remain unchanged.

Owners use `/ecology on|off|info|lair|advance <1–60>`; F3+G selects diagnostics.
Burn-scar behaviour and the LW-12 performance budget remain later work.
See [population evidence](../changes/living-populations/validation.md).

## Finite spider lairs and founding (LW-04/05)

`Ecology.lairs()` owns `LairRuntime` in the same `WorldSession` used by the game
host and dedicated server. The opt-in rule controls adoption, founding, growth,
emission and home routes. Loaded writable hearts are adopted from the chunk's
sparse nest index. The default data definition starts with six spiders, capacity
eight, growth one per day, at most four emitted bodies and a 24-block territory.
An emitted spider represents an existing member and receives a persistent ID;
it never adds another member to the total.

`tickMobs` runs lair residency before mob brains. At night, an alive loaded heart
can emit onto a clear, fully supported spider footprint. Emission shares the
ordinary hostile limits with undead: eight within 128 blocks of a participant
and a global limit scaled by participant count, up to four times eight. At dawn,
the brain walks a surviving spider towards the heart; only a body physically
near home retires into the lair, releasing its reservation without death or
loot. Bodies outside the territory also receive a home route. A session death
consumes one reserved member once, including a death immediately before saving.

Breaking the heart marks the lair destroyed. Growth, home returns and new
emission stop; wandering bodies retain their IDs and finite accounting until
death or normal saved residency. Distant emitted bodies park and re-adopt through
the existing persistent-entity path. With Living World off, lair routes are
cleared, lair simulation and web growth stop, and the original mixed spawner runs.
Peaceful retirement releases a living body representation without a population
loss. A body killed before switching difficulty is accounted as a death before
peaceful removal, so its member cannot be restored by the change.
Heart checks also run before each lair tick, before saving and before
`/ecology advance` moves the clock. Destruction immediately before either
boundary keeps the actual destruction day and cannot delay reseeding on reload.

The ordinary daily step and `/ecology advance` run the same lair growth and
founding sequence, bounded to the final 60 elapsed days. Discovery uses the
logical replay day, so a newly observed placed heart has the same history in
ordinary play and advancement. `LairSpots` discovers up to 16 saved anchors per
loaded chunk: a supported 2×2 cavity, an opaque roof and zero sky/block light.
`Colonizer` rechecks candidates, excludes nearby participants and bases, other
lair territories and line of sight from recent participant paths. Those path
samples are transient, bounded at 256 and retained for three days.

A newly observed or founded heart schedules its first silk callback one world
day after its logical birth tick. Daily replay uses `World.scheduleTickAt`,
because the block dispatcher may still be at the tick before the clock advance.
A heart founded on the target replay day waits its full first day; one founded
on an earlier replay day may already be due and uses normal bounded late-tick
growth. The existing chunk `ticks` section preserves that due time on save and
reload.

A region with no known lair history can found its initial nest after loaded
habitat discovery. Otherwise a region without a live nest uses deterministic
daily opportunities; after destruction, the delay is three to ten days and the
old territory remains excluded. Reopening destroyed history never skips this
delay. Eligible loaded terrain is required; the runtime does not generate a
remote cave to satisfy a deadline. Web growth stays in scheduled block ticks.
Typed records, unsupported definitions and reserved IDs follow the protected
save policy in [saves](saves.md#typed-spider-lairs-lw-0405).

`LairTests`, `LairRuntimeTests`, `ColonizerTests` and `NestBlockTests` cover model,
shared-session and block persistence boundaries. Render/input and multiplayer
acceptance are tracked separately in
[spider-lairs validation](../changes/spider-lairs/validation.md).

## Fields and rain (LW-06, LW-09)

The same opt-in rule now connects fields to the regional rabbit population and
physical raids. `Chunk` keeps a sparse index of all wheat, carrot and potato cells,
at every growth stage. `Ecology` observes loaded writable chunks before daily
steps and saving; `WorldSession.snapshotChunk` also observes a chunk before
eviction. `FieldHabitat` retains its last observed count while terrain is absent,
so unloading a field does not immediately remove its food. A new observation of
removed crops withdraws that chunk's contribution. No unseen terrain is generated
for an estimate.

Each four observed crop cells add one rabbit food point, up to 64 per region.
Stored counts saturate at 256 per chunk, which already suffices for the regional
ceiling. The food bonus enters `EcologyModel.effectiveCapacity` before the same
daily births, predation and migration used by ordinary play and owner advancement.
Wolf predation therefore limits rabbit pressure while fields support more rabbits.
The F3+G and `/ecology info` output includes the field estimate and food bonus.

Only the authoritative session sets `Mob.cropRaidsEnabled`. Rabbits walk to a
reachable maturing plant and reset its stage; guests see the ordinary block-meta
change. Crops and regrowth ticks persist in their usual chunk data; appetite and
navigation are transient. [Combat and mobs](combat-mobs.md#field-raids-lw-06)
describes barriers, search bounds and fear/temptation priority.

The session also adjusts noise radius for rain at the source through
`WeatherHearing`. Game supplies its actual `Atmosphere.global`; the dedicated
host uses deterministic seed/clock sampling through the default host seam.
Roof, arid-biome, snow and missing-source-column exclusions apply before the
existing `Mob.hearNoise` call. Both behaviours leave the disabled-rule path
unchanged and require no protocol extension. Acceptance evidence is tracked in
[the field-raids change](../changes/field-raids/validation.md); this is not the
complete LW-11 story set or the LW-12 performance gate.

## Ground trails (LW-07)

The host's `WorldSession` samples a participant or persistent animal only when it
enters a new ground cell. A jump over the ground or a teleport longer than three
blocks does not stamp all intervening cells. After repeated crossings, `Trample`
deepens a five-stage soil-wear pattern on grass without changing its block type;
snow layers are physically pressed down. Guests receive the quantized stage in
ordinary block-metadata updates; the guest's simulation does not create trails. The same session
runs on the dedicated server. The position bookkeeping is transient; the pressure
belongs to the chunk and is saved there.

`BlockTicker` asks loaded chunks to fade pressure once per elapsed absolute game
day, including days while they were unloaded. The rendered stage recedes only
when it crosses a visual threshold, so most footsteps and day ticks avoid a
network update and never rebuild the terrain mesh. While pressure remains,
snowfall cannot cover the path. On expiration, legacy dirt paths get an
immediate chance at the existing grass-spread rule; snowfall can cover it again.
The world clock's absolute day includes sleep, while
setting time backwards never reverses a fade.

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
| `Host.mobFocus` — the body, an empty world | its own player | the spawn point |
| `Host.focusIsBody` | yes: mobs are pushed out of the player | no: the focus is only a point |
| `Host.participantStruck` | knockback, an elite's poison or frost, the hurt sound | nothing |
| `Host.projectileTargets` | its own player and the guests | the guests |
| `Host.itemCollector` | its own player picks up items and its arrows | none: guests ask with `C_ITEM_PICK` |
| `Host.blasted` | a local player is thrown back | nothing |

`WorldEvents` only reads; the session calls it in simulation order.
Collisions handle each pair once, by the earlier mob in the list
(`MobSpatialGrid.order`), without the per-frame identity map the game used to
allocate.

The server gained one behaviour by sharing the loop: its mobs are now pushed
apart like the host's instead of standing inside one another.

## Around everyone (SIM-05)

`WorldSession.centres()` is where the world is alive this tick: every
participant in id order, or the focus while no one is here (a dedicated server
keeps the water at its spawn flowing). `WorldSimulation.update` ticks random
blocks (`BlockTicker`) and furnaces over the union of the squares around the
centres — each chunk once, in the order a single centre used, so a lone
player's world ticks exactly as before and two players in one place do not
double anything. The spawner rings every centre: its own cap counts the mobs
within `MobSpawner.LOCAL_RANGE` (128) of it, the shared cap grows with the
players up to `MAX_CAP_SCALE` (4) times; a mob despawns only beyond
`DESPAWN_RADIUS` from the nearest centre. The server's block ticks get the
weather front's precipitation (`Weather.sample` at `WorldClock.frontSeconds()`,
what the game's `Atmosphere` samples), so snow settles and rain puts fires out
there too; `DedicatedServer.centre()` is gone.

`WorldAroundEveryoneTests`: a furnace by a second player 300 blocks away
smelts (and one by no one does not); two players in one chunk tick it exactly
as one does; mobs spawn around both of two distant players and one beside the
second is not despawned for being far from the first; the server's
precipitation is the front's.

## Targets (SIM-07)

Each tick `sim.TargetSelector` picks every mob's target among the participants
that are alive and outside creative mode, and the mob runs its whole brain
against that target: chase, fuse, aim, sight, torch fear. A new target is the
nearest one the mob can see, else the nearest. It is kept — a pack does not swap
victims every step — unless someone in view is 30 % nearer (`SWITCH_MARGIN`), or
it has been out of sight for `LOST_TIME` (3 s) while someone else is in view.
Whoever hit the mob comes first for `Mob.REVENGE_TIME` (20 s): that is how a
wolf pays back. The memory (`targetId`, `targetUnseen`, `revengeId`,
`revengeTimer`) lives on the mob; the selector is in `sim` because it reads
participants.

With a single candidate the selector returns it, seen or not: range, darkness
and the sight cone stay the mob's own business, so a lone player's world is
exactly as before (the parity hash did not move). With no candidate a mob is
not hostile and looks at the nearest participant, or at the focus in an empty
world.

A mob's blow goes to its target's `Participant.damage` with a `MELEE` source
(the mob's type and position); the host's own player then gets knockback and
the elite's effect through `Host.participantStruck`, a guest the damage over
`S_PLAYER_HURT` — knockback for guests waits for protocol v8 (NET-02). Mobs hear
every participant: a guest's broken or placed block (`remoteBlockAction`) is a
`WorldSession.noise` like the host's own.

`TargetSelectionTests` cover the nearest visible choice, creative and dead
players, a target lost behind a wall, the 30 % margin, the grudge and the lone
candidate; `ServerSimulationTests` a zombie striking a guest on the server
(it struck no one there before) and a guest's broken block heard by a zombie.

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
- **Saving** (`snapshotChunk`): blocks, containers, the items lying in the
  chunk and the animals standing in it — live ones and restored ones not yet
  adopted — in one snapshot, or null when nothing changed. The game and the
  server call the same method.

## Mobs stay where they were (MOB-01, MOB-02)

A mob whose temper is not `HOSTILE` is persistent (`Mob.persistent()`): it is
written with the chunk it stands in (chunk section `entities`, see
[saves](saves.md)), `MobSpawner.despawnFar` never removes it, and it leaves the
world only with its chunk. The game's `evictDistantChunks` writes the doomed
chunks and then calls `WorldSession.releaseEntitiesIn`, which drops their items
and mobs in one pass — animals are in the write, hostiles simply go. A loaded
chunk keeps its mob records as pending; `adoptChunkItems` — already the door for
restored items — brings them into the world when the chunk is live. The
dedicated server never evicts, so its animals stay while their chunks do;
LW-03 will fold distant animals back into regional counts.

Every mob carries a world id (`Mob.id`): `EntityStore.assignIds` gives one to
every mob that has none, from a counter saved in the level section
`entity_ids`. The session calls it at the start of its mob tick and before a
chunk snapshot, and the host's network context before `S_MOBS`, so no path
that adds a mob (spawner, command, autopilot, test) needs to know about ids.
`EntityStore.adopt` lets a saved mob in with its id and moves the counter past
it; a record whose id is already live — one mob written into two chunks by a
crash between their writes — stays out. `S_MOBS` carries the world id in its
existing `VarInt`, so ids stay positive `int`s (the counter wraps and skips
live ids past `Integer.MAX_VALUE`) and the protocol is unchanged; a guest keeps
the same mob across the host's unload and reload, and `C_MOB_HIT` names the mob
by it.

`MobPersistenceTests` (MP-1…MP-9, ID-1…ID-4) and `NetworkTests` (ID-5, ID-6)
trace the scenarios of `docs/changes/mob-persistence`; the single-player
autopilot walks 600 blocks away and back and reopens the world, and checks the
herd by the spawn comes back with the same ids both times.

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

## Entity lifecycle (2 October 2026)

WorldSession adopts restored persistent entities once per chunk, assigns stable
clock IDs, updates shared environmental hazards/air/effects, and saves entity and
item lifetimes before forgetting a chunk. Breeding pairs are evaluated before
mob brains and appended births cannot invalidate iteration. Peaceful removes
hostiles immediately. Lightning is a typed radius event from the game's Storm.
DedicatedServer uses the same lifecycle without importing client/render/audio
classes. `EntityLifecycleTests` includes server stop/reopen with the same herd ID.
