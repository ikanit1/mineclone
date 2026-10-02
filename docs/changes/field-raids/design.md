# Authority, habitat observations and physical raids

`WorldSession` sets `Mob.cropRaidsEnabled` from its supported Living World state
before authoritative mob updates. The guest mirrors ordinary mob snapshots and
block-meta changes. `Wildlife` adds the raid branch after player/wolf fear and
existing flight, and before random peaceful wandering. Held-food temptation
cancels the crop route. `Mob.update` also cancels before the sleeping branch,
which otherwise skips `Wildlife`. The disabled rule takes the previous rabbit
and noise paths without new random draws or changed default spawn behaviour.

The session's sensing pass calls `Wildlife.sense(mobs, ecology.enabled())`.
With the rule on, threat distances, wolf prey selection and pack anger include
vertical separation. A hostile twenty blocks under a field cannot cause nearby
surface rabbits to remain in flight. Wolf bite range also includes height through
the host-set transient mob flag: prey four blocks below can still be sensed, but
is outside the 1.4-block bite radius. This is a distance rule, not a new sight or
combat ray. The legacy sensing entry point and disabled bite calculation preserve
their prior horizontal arithmetic. No saved or network fields are added.

`CropRaids` is transient per-rabbit memory created on first hunger. Searches are
staggered by identity and position, run at 1.5-second intervals, and inspect at
most 192 sparse crop cells in the nine surrounding loaded chunks. Only wheat,
carrots and potatoes at stage 4–7 within 16 blocks are candidates. At most two
bounded `PathFinder` calls are attempted per search. Unreachable targets are
temporarily rejected; no chunk is generated and read-only targets are excluded.
The first attempt selects the nearest eligible plant within the inspected cells.
If necessary, the second explores the first eligible plant in rotating scan order,
or the next one when it duplicates the nearest target. Advancing the cursor past
this exploration target also works when the entire sparse table fits in one scan.
This avoids repeatedly choosing only a fenced cluster after its rejection entries
expire, including clusters larger than the 32-entry rejection cache. The cell and
path budgets stay unchanged.

The helper uses actual path waypoints and the 15/16-block farmland surface.
Footprint checks and a 0.18-block waypoint reach keep corners centred through a
one-cell gap. Active crop paths suppress generic wall-hop/sidestep recovery;
normal rabbit hopping and legitimate route steps still use entity physics. An
unreachable plant never becomes a direct path through a wall or closed gate.
Eating requires horizontal distance at most 0.7 blocks, close vertical position,
a clear body and a mouth line checked against actual collision shapes. The line
also sees tall fences in the cell below, and open gates have no collision panel.

The meal changes only the plant's meta to stage 0 through `World.setBlock`, then
calls its placement behaviour to schedule normal regrowth. There is no removal
callback or harvest loot. Satiety lasts 45–90 seconds. Appetite and search memory
are not added to saved animal records; returning bodies use the usual constructor
appetite delay. The crop stage and its scheduled ticks already have persistence.

# Field food across absence

`Chunk` derives an O(1) sparse crop index from block data. Planting, type changes,
removal and restoration maintain it. All stages count as field habitat.
`Ecology.refreshFields` observes loaded writable chunks before daily updates and
saving; `WorldSession.snapshotChunk` records the final observation before eviction.
Each observation replaces that chunk's count. Other chunks retain their previous
counts while absent, avoiding a food collapse caused solely by streaming.

`FieldHabitat` stores at most 4096 regions of 256 chunk cells. Each chunk's count
saturates at 256; every four observed crops add one rabbit food point, up to 64
per region. This ceiling makes larger per-chunk counts equivalent. The bonus is
added by `EcologyModel.effectiveCapacity` before the existing daily births,
predation and migration. Physical animals remain protected by the existing
reservation ledger; habitat observations do not create bodies or reservations.

The optional level `ecology_fields` section is FHLD/version 1 with signed region
coordinates, sparse nonzero counts and a preserved tail of at most 4096 bytes.
The supported maximal payload stays below 3.2 MiB and the outer 4 MiB section
bound. Missing data starts an empty observation table. Newer data disables Living
World while preserving the raw field and ecology sections and the original rules.
Corruption is rebuilt from loaded terrain only after durable evidence preservation
succeeds; failure leaves the original bytes opaque and disables the feature.

# Rain at the sound source

`WorldSession.noise` uses `WeatherHearing` only with Living World enabled.
`Host.hearingWeather` supplies the graphical game's `Atmosphere.global`, including
forced weather and the current blend. Its default uses `Weather.sample(seed,
clock.frontSeconds())`, shared by the dedicated server.

The helper inspects only an already loaded source column. A solid block above
the source, an arid biome, a snowing elevation/biome, or missing source terrain
prevents rain masking. Otherwise radius is multiplied by
`max(0.25, 1 - rain * (0.35 + 0.40 * storm))`: full rain/thunder changes 16 to 4.
Existing `Mob.hearNoise` handles the result. No weather state, audio volume,
snapshot format or network protocol changes are required.
