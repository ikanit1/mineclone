# World clock

`sim.WorldClock` owns absolute game time as a double, monotonic simulation ticks
at 20 Hz, and the fractional tick remainder. Game and DedicatedServer advance the
same class. HUD, moon phase and sleep use its conversion functions; time commands
change the display/day phase without rewinding simulation ticks.

The `clock` level section stores a section version, double game time, long ticks,
and double remainder. Restoring v11 preserves all four exactly. Legacy saves
derive initial ticks from their float time; that migration cannot recover
precision that the original file did not contain. Saving a world on a dedicated
server preserves both this clock and the single-player owner's checkpoint.

Existing shader and protocol-v7 APIs still receive a float view at their boundary.
They do not own or accumulate authoritative time. This task does not introduce
the fixed-step simulation loop scheduled for SIM-06.

`WorldClockTests` checks client/server step equivalence, midnight/day changes,
commands, sleep, fractional tick round trips and invalid input. Native
`SaveLifecycleSmoke` checks the actual Game save/close/reopen path with precision
and opaque extra sections.

## Stable entity identity (2 October 2026)

Clock section version 2 appends the world-owned `long` entity counter (36-byte
payload). Version 1's 28-byte clock remains readable. Restored entities advance
the counter through `observeEntityId`; a birth reserves one ID and network varlong
encoding preserves it. Time commands never rewind this counter.

`logicalDayTicks(day)` anchors replay-created natural saplings and nest silk to
their logical calendar day at the current phase. It subtracts the exact cycle
duration and fractional cursor from the integer tick counter, preserving large
long values without conversion to double. Current/future days use the current
cursor; days before the simulation origin clamp to zero. Existing relative
scheduling, stored clock version and time-command semantics are unchanged.
