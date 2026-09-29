# Design: living trails

`WorldSession` observes authoritative participant and animal positions. A
per-actor transient last position rejects long teleports and records a step when the
actor enters a new cell. `Trample` stores sorted exact pressure values in each
chunk; only the host thread mutates them. A quantized stage (0–5) is mirrored in
grass block metadata, which already survives chunk saves and travels through
`S_BLOCK_SET` and `S_CHUNK_DELTA`. No new network packet or protocol version is
needed. A stage update leaves the block type and terrain mesh untouched.

`TrailRenderer` reads a sparse stage index on each nearby chunk and draws all
visible surface cells with one instanced call. A path starts as dotted wear,
widens with pressure, and joins adjacent used cells. The shader cuts its uneven
edge with ordered 4×4 dithering to fit the game's 16-pixel material style; it
samples the authored dirt texture and the same light, fog and shadow inputs as
the terrain. Snow compression remains a real layer edit.

The optional `trample` chunk section has version 1, absolute day, count, then
sorted unsigned cell indices and nonzero pressure bytes. The codec validates
the bounds and order before publication. The save snapshot copies the section;
the write worker does not borrow the live map. Ordinary modified chunks remain
compatible with old saves, and older readers that support unknown sections can
preserve this optional section. On load, the pressure section restores visual
stages for older LW-07 saves that have not yet written metadata. Previously
converted dirt blocks remain dirt until their old pressure expires and the
existing grass-spread rule can reclaim them.

`BlockTicker` fades loaded chunks on their first tick of a new day and sends a
metadata update only when a visible stage changes. It offers legacy dirt an
immediate attempt at the existing grass-spread rule. The same
check on a step catches up an unloaded chunk before its next footfall. The
absolute game day includes sleep and does not require simulating every missed
frame.
