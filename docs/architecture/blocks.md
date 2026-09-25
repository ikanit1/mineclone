# Stable block IDs and properties

`BlockType.ordinal()` is the persisted and replicated block ID. Existing values
must never move or be removed; append new values at the end. The baseline has
**48** blocks, as measured from the tagged code, rather than the estimated 50 in
the roadmap. `fixtures/block-ordinals.txt` records that immutable prefix.

`BlockProps` centralizes cross/layer/gravity/soil/flammable flags, grip, movement
speed, preferred tool and required tool tier. Its exhaustive switch builds a
cached array indexed by ordinal. Adding a block requires an explicit properties
entry at compile time. `BlockType` methods delegate to that table; geometry,
hardness, textures and drops keep their current APIs pending later BLK tasks.

`BlockOrdinalTests` compares both the name/ordinal prefix and every baseline
property in `fixtures/block-properties-v1.txt`. The property fixture was captured
from compiled pre-refactor classes, so it detects accidental behavior changes
rather than restating the new switch.

## Shapes (BLK-02)

`world/shape/Shapes` gives every block a `BlockShape`: the boxes, in its own
cell, that a body collides with (`collision`) and that the aim hits and the
outline follows (`outline`), by meta. The switch is exhaustive, like
`BlockProps`. Player collision, mob and item physics (`EntityPhysics`), the
raycaster, the selection outline and the mesher's stairs, doors and layers
all read it; none of them names a block type any more.

| Shape | Blocks | Collision | Outline and aim |
|---|---|---|---|
| `FULL` | every solid cube | the cell | the cell |
| `EMPTY` | air, water, flowing water, lava | none | none — the aim passes through |
| `STAIRS` | stairs | slab (0..0.5) + step on the facing's half | the same two boxes |
| `DOOR_CLOSED` | closed door | the cell, as before | the 3/16 panel |
| `DOOR_OPEN` | open door | none | the panel swung to the side |
| `LAYER` | snow layer, bedroll | none | `(meta & 7) + 1` eighths high |
| `TORCH` | torch | none | the stick, tilted by its mount (meta 1..4) |
| `CROSS` | web, rope, chain, journal | none | the drawn planes, 10/16 high |
| `FLAME` | fire | none | the cell |

Collision keeps what `solid` always meant — a solid block stops a body — and
only says where inside the cell. A cube takes the old, box-free path, so cube
collision is bit for bit what it was (`CollisionParityTests`, 1000 random moves
for the player and 1000 steps for mobs and items against a transcription of the
pre-shape code; the session parity hash did not move). Stairs are real boxes:

- The old model decided which half of a stair was underfoot by the body's
  centre. Where that agreed with the body's footprint, the boxes give the same
  result in 1000 random moves; where it did not, the old model stood a player
  inside the step, let a head into a stair from below and threw a body walking
  on the slab back to the cell's far edge. Those are fixed on purpose and
  tested as such. Running into a stair now ends a sprint, as any block does.
- **No step-up.** The roadmap asks for an MC-style auto-step of up to 0.6
  blocks, but `PlayerPhysicsTests` holds the fix the user asked for in
  `99a10a4` — walking into a stair never lifts the player; a stair is taken by
  jumping — and BLK-02 must keep that test green. A mob, too, bumps into the slab
  and its AI jumps. Stepping would need that decision reversed first.
- `PathFinder` still treats a stair as a cube: without step-up a body jumps
  onto a stair either way, so a floor at half height changes no path yet. It
  belongs with the first slab (BLK-07).

The aim walks cells (DDA) and, in a shaped cell, meets its outline boxes: the
normal is the face of the box it entered, and `Raycaster.Hit.distance` is how
far along the ray (the aim at mobs compares it). Lava is no longer a target
(TD-50): a ray passes through it to the block beneath, as through water. Fire
stays a target so that it can still be put out by hand; clicking the block
under it instead waits for BLK-03's behaviours. A ray through an open doorway
reaches the block behind it.

The outline is drawn along `Shapes.edges`: the edges of the union of the boxes,
where its surface folds (a stair's L: 18 edges, no seam across its back). The
animator slides the box around the shape and carries the edges with it.
Torches, snow, fire, webs and open doors are outlined now; before, only solid
blocks were.

`ShapeTests` compares every meta of every block with its solidity, checks
stairs, door panels (the values the removed `BlockOutline.doorBox` returned),
layers and torches, and meshes stairs, doors and layers to require that every
drawn vertex is a corner of the shape and every corner is drawn.
`RaycastShapeTests` compares 1000 rays through random cubes with the old walk
and aims at slab tops, a step from the front and from behind, lava, an open
doorway, a torch and snow.

## Behaviours (BLK-03)

`world/behavior/Behaviors` gives every block a `BlockBehavior` — what it does,
as `Shapes` says what it is made of: `canPlaceAt`, `placementMeta`, `onPlaced`,
`interactive` and `use` (→ `UseResult`: pass, consumed, open a menu, sleep),
`needsSupport`/`canSurvive`/`collapse`, `onRemoved`, and `scheduledTick`
(see [scheduled ticks](#scheduled-ticks-blk-04)). The switch is exhaustive. Nothing in it knows the screen
or the network; host, server and tests run the same code.

| Behaviour | Blocks | Rules |
|---|---|---|
| chest, furnace | `CHEST`, `FURNACE` | open their window; **spill on any removal** |
| crafting table | `CRAFTING_TABLE` | opens the 3×3 grid |
| bedroll | `BEDROLL` | placed half a block high; used to sleep |
| door | `DOOR_CLOSED`, `DOOR_OPEN` | needs air above and something solid below; placed, opened and removed as two halves |
| torch | `TORCH` | stands on a floor or leans from a wall (mount 1..4), never hangs from a ceiling; falls as an item without its support |
| stairs | `STAIRS` | the step faces away from the placer |
| snow layer | `SNOW_LAYER` | lies on something solid, goes when that is dug out |
| default | everything else | none |

**Removal.** `World.setBlock` calls the leaving block's `onRemoved` before it
writes the new one, whoever changes the cell: the pickaxe, a guest's edit, an
explosion, `/fill`. A chest and a furnace spill there — and clear their slots,
because an open window holds the same array. The drops go to the world's
`DropSink`, which a `WorldSession` installs (`World.simulate`); a guest's
mirror has none, so the host alone spills. `Game` and `DedicatedServer` no
longer spill containers themselves (TD-05, closed systemically).

**Support.** A change queues the cell and its six neighbours in
`NeighbourUpdates` — only those whose block can fall (`needsSupport`), since
water alone moves hundreds of cells a tick. `WorldSimulation.update` works the
queue last in the world's tick, `NeighbourUpdates.BUDGET` (256) cells at a time;
what cannot stay collapses and drops its own loot table, rolled as bare hands
in survival — a fallen torch is a torch in any mode. A check whose support lies
in an unloaded chunk waits for the next change instead. A door's two halves
hold each other up: the first to go drops the door, the one that follows drops
nothing. Only the host queues: a guest sees the result as block sets.

**Clicks.** `game/InteractionController` (extracted from `Game.handleInteraction`)
turns input into calls on behaviours: dig, place (`canPlaceAt` →
`placementMeta` → `setBlock` → `onPlaced`), use, pipette. A right click on an
interactive block is a use, repeated only by a fresh press — a held button
repeats placement, and Shift places against the block instead. A thin snow
layer (the first eighth) gives way to a placed block, as in Minecraft, so a
torch can still be set on snowy ground. A guest predicts a door's swing and
sends `C_USE_BLOCK` (see [network](network.md)); a guest's sleep moves only its
own respawn point, since the night is the host's clock.

`BehaviorTests` (12): a chest spills whatever removes it (air, stone, water)
and the open window's view is cleared, a furnace too, an explosion through a
real session, a door placed/opened/removed whole with one drop, a torch on a
floor and on each wall, snow, a mirror queues nothing, the budget over 512
torches, the world tick works the queue only when simulating, placement
facings transcribed from the removed `Game` methods, the interactive set, and
the acceptance scan. `NetworkTests`: a guest's `C_USE_BLOCK` opens the host's
door whole; stone, a truncated packet and a guest out of reach change nothing.
`ContainerBreakNetworkTests` (TD-05) is green without the old special case.

## Scheduled ticks (BLK-04)

Random ticks reach a cell about once in forty minutes; a block that needs its
time — a crop's stage, a sapling — asks instead:
`World.scheduleTick(x, y, z, kind, delay)` wakes the block `kind` in that cell
`delay` world ticks later (at least one), through
`BlockBehavior.scheduledTick(world, x, y, z, meta, lateBy)`. Asking again for
the same block in the same cell moves its time rather than adding a tick. The
tick comes only if that block still stands there: a crop dug up and replaced by
dirt does not wake the dirt. No block asks yet; crops and saplings will.

- **Storage.** Each chunk has a `ScheduledTicks`, created on the first ask: a
  binary heap of `long`s — due world tick (40 bits), kind (8 bits, the block's
  ordinal) and the cell's index (15 bits) — so ticks come by due time and,
  among equal times, always in the same order. A chunk holds at most
  `ScheduledTicks.MAX_ENTRIES` (32768).
- **Running.** `WorldSession.tickScheduled()` runs after the random ticks each
  frame, only where the world is simulated (the host, single player, the
  dedicated server; a guest's mirror schedules nothing). It works every world
  tick since the last run in turn — a block asking every N ticks ticks once per
  N even when a frame spans several — `TICK_BUDGET` (512) per world tick, the
  rest one tick late. A stall longer than `TickScheduler.MAX_STEPS` (40 world
  ticks) runs the overdue ticks late instead of replaying the whole gap. A chunk
  runs its ticks only while the four chunks beside it are loaded.
- **Late ticks.** `lateBy` is how long past its time a tick runs — a frame's
  stall, a chunk that was unloaded, a game left closed — capped at
  `WorldSession.MAX_TICK_LATENESS`, two world days (≈ 50 266 ticks): a crop left
  for a week catches up two days' worth.
- **Saving.** The chunk section `ticks` ([saves](saves.md#section-formats-and-write-ordering))
  keeps dues relative to the world tick they were written at. A restored chunk
  hands its ticks to the simulation when it is published; the time since the
  save counts (the ticks run late), unless the level's clock is behind the save
  (a level restored from an older backup) — then the dues shift back, so a tick
  never waits longer than it was asked to. A kind this build does not know is
  held and written back untouched.

`ScheduledTickTests` (10): heap order and ties, replacing an ask, the budget and
a handler's new ask, the bound, held kinds and the clock-behind shift, the
section's round trip and six malformed payloads, the world's per-tick run (a
long frame, a stall, a replaced block, a missing neighbour, an ask for no delay),
512 a world tick across chunks, and the acceptance: **a counter asking every 20
ticks ticks exactly ten times in ten intervals across a save and a fresh game**,
half an hour and three hours away (capped at two days), a clock behind the save,
and an edit before the first run keeping the restored origin.
