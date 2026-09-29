# Design: living trails

`WorldSession` observes authoritative participant and animal positions. A
per-actor transient last position rejects long teleports and records a step when the
actor enters a new cell. `Trample` stores sorted pressure values in each chunk;
only the host thread mutates them. All visible terrain edits use `World.setBlock`
or `World.setSnowLevel`, so lighting, meshes, saves and network observation take
their normal paths.

The optional `trample` chunk section has version 1, absolute day, count, then
sorted unsigned cell indices and nonzero pressure bytes. The codec validates
the bounds and order before publication. The save snapshot copies the section;
the write worker does not borrow the live map. Ordinary modified chunks remain
compatible with old saves, and older readers that support unknown sections can
preserve this optional section.

`BlockTicker` fades loaded chunks on their first tick of a new day and offers
expired dirt one immediate attempt at the existing grass-spread rule. The same
check on a step catches up an unloaded chunk before its next footfall. The
absolute game day includes sleep and does not require simulating every missed
frame.
