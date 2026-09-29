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
its chunk. Six crossings turn exposed grass into dirt. Snow thins and is kept
off a used trail. Pressure fades by two each elapsed game day; at zero, exposed
dirt can regrow and snowfall can cover the route. The host and dedicated server
own these changes, which use existing block sync.

No new block id, item, generator version or network message is introduced.
