# Change: living-trails (LW-07)

Status: implemented under the approved Living World slice in
[ROADMAP_1_1.md](../../ROADMAP_1_1.md). Prioritized on 2026-09-30 because the
player should see the world remember their actions before more architecture work.

## Why

The existing short-lived footprint decal and local snow compression disappear
without a lasting trace. Repeated travel should shape the terrain, survive a
restart and then recover when that route is abandoned.

## What changes

Players and persistent animals crossing a ground cell add bounded pressure to
its chunk. Grass remains grass: five visible wear stages gradually reveal an
irregular soil trail over the surface. Connected footprints join into a path
without full-block edges. Snow still thins and stays off a used trail. Pressure
fades by two each elapsed game day; the visible path recedes and snowfall can
cover the route again. The host and dedicated server own these changes. Existing
block metadata updates carry the visual stages to guests.

No new block id, item, generator version or network message is introduced.
