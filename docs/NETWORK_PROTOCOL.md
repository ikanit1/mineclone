# Network protocol v8

The wire format between a host (the game that opened the world, or the
dedicated server) and its guests. `com.mineclone.net.NetProto` is the source;
`ProtocolTests` fails when the code table below and the constants there
disagree. Architecture: [architecture/network.md](architecture/network.md).

## Messages and packets

A transport message carries one or more packets back to back; a packet is a
one-byte code and its body. Bodies have no length prefix, so a reader that
meets an unknown code or runs out of bytes drops the rest of the message.
`NetChannel` gathers a network frame's packets (12 Hz) into one message per
addressee, reliable and unreliable apart, and sends early past 24 000 bytes.

Types: `u8`, `i16`, `i32`, `i64` big-endian; `f32` IEEE big-endian; `varInt`
seven bits a byte, low first; `str` = `varInt` length + UTF-8; `blockPos` packs
x, y, z into eight bytes.

## Versions

`NetProto.VERSION` travels in `C_HELLO`, in the LAN frame handshake, in the
LAN beacon and in Photon's application version, so builds of different
protocols neither list nor join each other's rooms. A mismatch that still
reaches a host by address is refused with both versions and the host's build
(`ConnectDiagnosis.versionMismatch`) — written by the host, so an older guest
can read it.

v8 is the 1.1 cycle's one change of version: it was raised at the first
incompatible body and stays until the release; later 1.1 changes to v8 bodies
come under the same number. Changed or new in v8 so far:

- `S_PLAYER_HURT` — `f32 amount, u8 kind (DamageType.id), u8 flags (1 a
  player's hit, 2 has an origin), [f32 x, y, z], f32 knockback, u8 mob type + 1
  (0 none), varInt participant + 1 (0 none)` (`PlayerHurt`). The guest applies
  it through its own damage path and is knocked away from a blow or a blast.
  v7 sent `f32 amount` alone.
- `S_GEN_MAP` (74, new) — `bytes gzip(chunk ledger)`: the chunks the host's
  world generates with another version than its own (GEN-02), in the ledger's
  own encoding (`GenMap`). Sent right before `S_WELCOME`, empty for a world that
  was never upgraded.
- `S_WELCOME` — `i64 seed, str name, i64 gameTime bits (double), i64
  worldTicks, u8 mode, f32 spawn x, y, z, bytes generator (WorldGenSettings, 16)`
  (`Welcome`). A guest used to generate the host's world with the 1.0 generator
  and a float time; it now builds `LedgerPolicy(host's settings, pinned chunks)`
  and the host's exact clock. A generator this build lacks ends the session
  with a message.
- `S_CHUNK_DELTA` — `i32 cx, cz, u8 generator version, bytes cells`: the version
  the delta was taken against. A guest whose chunk came from another version
  pins the host's and rebases the chunk on its generation before applying the
  delta.

- `C_USE_BLOCK` (70, new) — `blockPos, u8 face (0 none, 1..6: +X, -X, +Y, -Y,
  +Z, -Z), u8 hit x, y, z (the point in the block, in 255ths), varInt sequence`
  (BLK-03). A guest's right click on an interactive block: the host checks the
  guest is within reach, runs the block's behaviour (`BlockBehavior.use`) and
  sends back what changed as `S_BLOCK_SET`. The guest applies a door's swing at
  once without sending it as an edit; windows still open through `C_OPEN`, and
  a guest's sleep only moves its own respawn point. Before, a guest toggled a
  door by editing both halves itself (`C_BLOCK_EDIT`).

- `C_PLAYER`, `S_PLAYER` and every packet that carries a checkpoint
  (`C_OPEN`, `C_DROP`, `C_PICKUP`, `S_PICKUP`) — `bytes PlayerRecordCodec`: the
  whole `PlayerRecord` with its own format version (SAVE-07), no longer v7's
  inventory, pose, health, hunger and progress. The host takes the guest's own
  fields (`PlayerRecord.mergeClient`: pose, vitals, inventory, equipment, cursor
  stacks, progress, advancements, recipes) and keeps effects, the personal spawn
  and unknown sections. A record newer than this build is refused.

Planned for v8 (roadmap section 6/K): `C_USE_BLOCK (70)` (BLK-03) and the rest
of the table there.

## Codes

`S_` host to guests, `C_` guest to host, `X_` both ways. Retired codes stay
reserved and are never reused.

| Code | Constant | Direction | Delivery | Body and notes |
|---|---|---|---|---|
| 1 | `C_HELLO` | guest → host | reliable | `varInt version, str name, str identity` |
| 2 | `S_WELCOME` | host → guest | reliable | **v8**: `Welcome` — seed, name, precise clock, mode, spawn, generator |
| 3 | `S_REJECT` | host → guest | reliable | `str reason`; ends the session |
| 10 | `X_PLAYER_STATE` | both | unreliable, 12 Hz | `f32 x, y, z, yaw, pitch, u8 flags` — 22 bytes with the code |
| 11 | `X_PLAYER_INFO` | both | reliable, 0.5 Hz | `str name, u8 mode, f32 health` |
| 12 | `X_PLAYER_LIFE` | both | — | read (`u8`) but not sent; kept for the death message (SURV-07) |
| 13 | `X_PLAYER_SWING` | both | reliable | no body |
| 14 | `X_BLOCK_ACTION` | both | reliable | a player's own edit, for its sound |
| 15 | `X_PLAYER_EQUIPMENT` | both | reliable | `i64 sequence, str item id` |
| 20 | `S_BLOCK_SET` | host → guests | reliable | a block became this |
| 21 | `C_BLOCK_EDIT` | guest → host | reliable | a request to place or break |
| 22 | `C_CHUNK_REQUEST` | guest → host | reliable | a chunk's delta, please |
| 23 | `S_CHUNK_DELTA` | host → guest | reliable | **v8**: `i32 cx, cz, u8 generator version, bytes cells` differing from fresh generation |
| 24 | `S_TIME` | host → guests | reliable, every 5 s | time of day |
| 30 | `S_MOBS` | host → guests | unreliable, 12 Hz | every mob, `MobSnapshot`: 85 bytes a mob |
| 32 | `S_ITEMS` | host → guests | reliable, 4 Hz | every item on the ground |
| 34 | `C_MOB_HIT` | guest → host | reliable | `varInt mob, f32 damage, knockback, from x, z` |
| 35 | `S_PROJECTILES` | host → guests | unreliable, 12 Hz | arrows in flight and stuck |
| 36 | `C_SHOOT` | guest → host | reliable | a shot for the host to fire |
| 37 | `S_PLAYER_HURT` | host → guest | reliable | **v8**: `PlayerHurt`, see above |
| 40 | `C_CONTAINER_OPEN` | — | — | retired in v7 |
| 41 | `S_CONTAINER` | — | — | retired in v7 |
| 42 | `C_CONTAINER_COMMIT` | — | — | retired in v7 |
| 50 | `X_CHAT` | both | reliable | a chat line |
| 51 | `C_ITEM_PICK` | — | — | retired in v7; read and ignored |
| 52 | `S_GIVE` | host → guest | — | read, no longer sent |
| 60 | `C_PLAYER` | guest → host | reliable, 4 Hz | **v8**: the guest's checkpoint, the whole record |
| 61 | `S_PLAYER` | host → guest | reliable | **v8**: the stored record on entry |
| 62 | `C_OPEN` | guest → host | reliable | open a container menu |
| 63 | `C_ACTION` | guest → host | reliable | one gesture in an open menu |
| 64 | `C_CLOSE` | guest → host | reliable | close the menu |
| 65 | `S_MENU` | host → guest | reliable | the menu as the host sees it |
| 66 | `C_DROP` | guest → host | reliable | drop, with a checkpoint |
| 67 | `S_DROP_ACK` | host → guest | reliable | the drop landed |
| 68 | `C_PICKUP` | guest → host | reliable | pick up, with a checkpoint |
| 69 | `S_PICKUP` | host → guest | reliable | what was picked up |
| 70 | `C_USE_BLOCK` | guest → host | reliable | **v8, new**: use a block; the host runs its behaviour |
| 74 | `S_GEN_MAP` | host → guest | reliable | **v8, new**: `GenMap`, right before `S_WELCOME` |

Traffic by code, measured: [perf/net-baseline.md](perf/net-baseline.md).
