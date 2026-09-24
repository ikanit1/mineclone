# Network baseline, protocol v7 (NET-01)

Measured 2026-09-24 on commit `a9af3d1` plus NET-01, before any protocol v8
change: these are the numbers NET-03 is judged against.

## How

`java -cp "out;libs/*" tools/NetBaseline.java` (Windows) or with `:` on Linux.
The tool runs the real `Multiplayer` of a host and its guests over the
loopback transport, driven by simulated time at 60 frames a second: 10 s of
warmup (entry, handshake), then 60 s measured from `NetStats` totals. The host
holds the scenario's mobs and ground items; guests walk circles around it.
Chunk deltas are not requested — they are a one-time burst at entry, not
steady traffic.

- **Payload** — packet bytes as the game writes them (code byte included).
- **Photon wire** — the same messages as Photon carries them: base64 inside
  the event operation JSON inside its `~m~` frame, computed exactly by
  `PhotonTransport.wireBytes` for every delivery. TLS, TCP and IP are not
  counted; a direct connection adds 13 bytes a message instead.
- **Per guest** — what the host delivered, divided by the guests: a packet for
  everyone counts once per recipient, because every guest downloads it.
- **Room messages** — Photon's count summed over all peers: each message sent
  plus each delivery.

The GL scene `mp-host-3` (PERF-01, `BenchPeers`) measures a real game host with
three loopback guests; it needs a window and was not run for this baseline.

## Results

| Scenario | Download per guest, payload | Photon wire | Delivered from each guest | Room messages |
|---|---|---|---|---|
| idle: 1 guest, no mobs, no items | 0.42 KB/s | 1.30 KB/s | 0.82 KB/s | 66 /s |
| typical: 3 guests, 30 mobs, 50 items | **37.92 KB/s** | **51.33 KB/s** | 1.36 KB/s | 240 /s |
| item peak: 3 guests, 30 mobs, 480 items | 104.93 KB/s | 140.68 KB/s | 1.36 KB/s | 240 /s |

Per packet code, typical scenario, per guest:

| Code | KB/s | Packets/s | Bytes/packet |
|---|---|---|---|
| `S_MOBS` (30) | 30.00 | 12.0 | 2 560 (85 per mob) |
| `S_ITEMS` (32) | 7.63 | 4.0 | 1 952 (39 per item) |
| `X_PLAYER_STATE` (10) | 0.26 | 12.0 | 22 |
| `S_PROJECTILES` (35) | 0.02 | 12.0 | 2 |
| `X_PLAYER_INFO` (11) | 0.01 | 0.5 | 11 |
| `X_PLAYER_EQUIPMENT` (15) | < 0.01 | 0.5 | 10 |
| `S_TIME` (24) | < 0.01 | 0.2 | 5 |

At the item peak `S_ITEMS` alone is 74.63 KB/s (19 106 bytes, four times a
second). What each guest sends: `X_PLAYER_STATE` 22 bytes at 12 Hz to everyone,
`C_PLAYER` (the checkpoint) 141 bytes at 4 Hz, `X_PLAYER_INFO` and
`X_PLAYER_EQUIPMENT` every two seconds.

## Against the budget (roadmap section 12)

- The code estimate held for mobs: 85 bytes a mob, 30 KB/s per guest at 30.
- Items cost more than estimated: 39 bytes an item, not ~30 — 7.6 KB/s at 50
  items (estimate 6) and 74.6 KB/s at 480 (estimate 58).
- Typical total per guest 37.9 KB/s payload is inside the 35–45 KB/s estimate
  and 1.5× the v8 target (≤ 25 KB/s); on Photon's wire it is 51.3 KB/s.
- Room messages: 240 a second for four players, against an estimate of 192
  (48 sent + 144 delivered). Reliable and unreliable packets travel in separate
  messages, and so does every addressee: a guest's checkpoint to the host is a
  message of its own four times a second, beside its broadcast state.

## Findings for NET-03

- **Photon's envelope dominates small traffic.** The idle guest downloads
  0.42 KB/s of payload and 1.30 KB/s on the wire: about 60 bytes of JSON and
  framing around every message, twice per network frame.
- **The checkpoint is resent unchanged.** A guest sends its whole checkpoint to
  the host four times a second whether or not anything changed: 141 bytes with
  an empty inventory, 854 bytes with 36 filled slots — 3.3 KB/s of upload per
  guest in real play, about 4.6 KB/s on Photon's wire.
- `S_ITEMS` reliably resends the whole list; it is the only packet that grows
  without bound with ordinary play (a broken chest, a creeper crater).

## Watching it live

- F3, network line: `out … in … KB/s room … msg/s` over the last whole second.
- `-Dmineclone.netStats=<file.csv>`: a row per packet code and second
  (`second,dir,code,name,packets,bytes`) and a `messages` row per direction
  with deliveries and wire bytes. A second session in the same process writes
  `<file.csv>.2`.
