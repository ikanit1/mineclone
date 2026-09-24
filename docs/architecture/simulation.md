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
