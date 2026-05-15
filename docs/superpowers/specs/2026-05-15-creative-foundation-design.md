# Creative Foundation — Design Spec
**Date:** 2026-05-15  
**Project:** Mineclone (Java / LWJGL / OpenGL 3.3)

## Scope

Adds the foundational blocks and systems for Creative mode:

1. Block metadata storage system
2. New blocks: Glass, Door (open/close), Stairs (directional)
3. Water physics: BFS spreading, 8 levels, swimming
4. Creative inventory menu (E key, fullscreen grid)

---

## 1. Block Metadata System

### Storage

`Chunk` gets a second parallel array `byte[] meta` (same index formula as `byte[] blocks`).  
Memory cost: 16 × 128 × 16 × 1 byte = 32 KB per chunk — acceptable.

Accessors added to `Chunk`:
```java
public byte getMeta(int x, int y, int z) { return meta[idx(x,y,z)]; }
public void setMeta(int x, int y, int z, byte val) { meta[idx(x,y,z)] = val; }
```

### Bit layout per block type

| Block | bits 1–0 | bit 2 | bits 3–0 |
|---|---|---|---|
| STAIRS | direction (0=N,1=S,2=E,3=W) | — | — |
| DOOR_CLOSED / DOOR_OPEN | facing (0=N,1=S,2=E,3=W) | 0=closed / 1=open | — |
| WATER_FLOW | — | — | level 1–7 |
| WATER (source) | — | — | 0 (implicit) |
| all others | 0 | 0 | 0 |

### World API changes

Overloaded methods in `World`:
```java
// existing — meta defaults to 0
public void setBlock(int wx, int wy, int wz, BlockType t)
// new — explicit meta
public void setBlock(int wx, int wy, int wz, BlockType t, byte meta)

// new reader
public byte getBlockMeta(int wx, int wy, int wz)
```

`ChunkLoader`, `ChunkMesher`, `Raycaster` gain read access to meta via `World.getBlockMeta()`.

---

## 2. New BlockType Entries

Five new enum constants appended to `BlockType`:

```
GLASS      (true,  false, true,  14, 14, 14,  0.70f, 0.90f, 0.90f, 0)
DOOR_CLOSED(true,  false, false, 15, 15, 15,  0.60f, 0.45f, 0.27f, 0)
DOOR_OPEN  (false, true,  false, 15, 15, 15,  0.60f, 0.45f, 0.27f, 0)
STAIRS     (true,  false, false, 11, 11, 11,  0.60f, 0.45f, 0.27f, 0)
WATER_FLOW (false, true,  false,  8,  8,  8,  0.16f, 0.35f, 0.78f, 0)
```

**Glass** — `solid=true, cutout=true`: full-cube collision, faces drawn when adjacent (same as Leaves). Texture is a semi-transparent tinted grid pattern.

**Door closed/open** — two enum values so existing `b.solid` checks work without change.

**Stairs** — `solid=true` so the basic solid-check path in `moveAxis` stays, but stair-specific AABB logic overrides it (see §5).

**Water\_flow** — same flags as WATER; level stored in meta.

### TextureAtlas additions

`TILE_NAMES` extended:
```java
"glass",   // 14
"door",    // 15
```

Procedural generators added to `generateTile(int index)`:
- `drawGlass(t)` — 16×16, alpha ~180, light-blue tint, 1px border grid lines.
- `drawDoor(t)` — 16×16, wood planks with a 2px frame and a door-knob dot at x=13, y=8.

---

## 3. Water Physics

### WaterSimulator (new class)

`world/WaterSimulator.java` — stateless, called from `Game` every 0.5 s real time.

```java
public static void tick(World world)
```

**Algorithm (single tick):**

```
Step A — BFS from all WATER sources in loaded chunks:
  queue ← all WATER blocks (level 0) in loaded chunks
  for each cell dequeued at level L:
    below = (wx, wy-1, wz)
    if below is AIR → setBlock(below, WATER_FLOW, level=min(L+1,7)); enqueue(below, L)
                      (flowing down does NOT increment level — straight-down fill stays full)
    else for each of 4 horizontal neighbors:
      if neighbor is AIR AND L < 7 → setBlock(neighbor, WATER_FLOW, level=L+1); enqueue(neighbor, L+1)

Step B — remove stale WATER_FLOW:
  for each WATER_FLOW block in loaded chunks:
    if not visited by Step A BFS → setBlock(AIR)
```

Visited set is a `HashSet<Long>` of packed world coords, built during Step A.

Tick is skipped for chunks not in the `chunkMeshes` map (not yet rendered) to avoid off-screen flooding lag.

### Variable-height water rendering

`ChunkMesher` detects WATER / WATER_FLOW and reads level from meta:

```java
float topY = (level == 0) ? 1.0f : (8 - level) / 8f;
// top face: emit at local y + topY instead of y + 1
// side faces: clip UV and vertex Y to topY
```

Adjacent water blocks suppress the shared top face as today (neighbor is not AIR).  
A water block next to a lower-level water block still draws the top face (different heights).

### Swimming physics (`Player.update`)

After `moveAxis` calls, check if any block overlapping the player AABB is WATER or WATER_FLOW:

```java
boolean inWater = /* any block in player AABB is WATER/WATER_FLOW */;
if (inWater) {
    velocity.y = Math.max(velocity.y, GRAVITY * 0.15f * dt); // buoyancy
    velocity.x *= 0.7f;
    velocity.z *= 0.7f;
    if (input.keyDown(SPACE))      velocity.y =  3f;
    if (input.keyDown(LEFT_SHIFT)) velocity.y = -3f;
    onGround = false;
}
```

Water sounds: on entering water → play `assets/sounds/water/splash_*.ogg`; while swimming every 0.8 s → play `assets/sounds/water/swim_*.ogg`. `Sounds.java` maps WATER / WATER_FLOW to step sounds from `assets/sounds/water/`.

---

## 4. Door

### Placement

Right-click with DOOR_CLOSED selected → `handleInteraction()` reads `lastHit.nx, lastHit.nz` to compute facing (4 directions), sets `meta` accordingly. Calls `world.setBlock(px,py,pz, DOOR_CLOSED, facingMeta)`.

### Interaction

In `handleInteraction()`, before the "place block" branch, check:

```java
BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
if (target == DOOR_CLOSED || target == DOOR_OPEN) {
    // toggle, preserve facing bits, flip bit 2
    byte m = world.getBlockMeta(...);
    BlockType next = (target == DOOR_CLOSED) ? DOOR_OPEN : DOOR_CLOSED;
    world.setBlock(lastHit.x, lastHit.y, lastHit.z, next, m);
    sound.playOneOf(sounds.door(), ...);
    return; // don't fall through to placement
}
```

`Sounds.door()` returns OGG list from `assets/sounds/door/`.

### Rendering

`ChunkMesher` special-cases DOOR_CLOSED and DOOR_OPEN via `emitDoor()`:

- **DOOR_CLOSED:** thin slab (thickness = 3/16) flush against the wall indicated by facing. Emits 6 faces clipped to that thin volume.
- **DOOR_OPEN:** same slab rotated 90° around the hinge edge (perpendicular to the wall), offset to the side.

Facing bits from meta determine which axis/side.

### Collision

DOOR_CLOSED: `solid=true`, full-block AABB (slight inaccuracy — acceptable simplification).  
DOOR_OPEN: `solid=false`, no collision.

---

## 5. Stairs

### Placement

Direction determined from camera yaw at the time of placement:
```java
int facing = Math.floorMod((int)Math.round(yawDegrees / 90.0), 4);
// 0=N (+Z), 1=E (+X), 2=S (-Z), 3=W (-X)
```
Meta byte = facing (bits 1–0).

### Collision (`Player.moveAxis`)

`moveAxis` currently treats all `b.solid` blocks as `[0,1]³` AABBs. Stairs override this:

```java
if (b == BlockType.STAIRS) {
    int dir = world.getBlockMeta(x,y,z) & 0x3;
    resolveStairs(dx, dy, dz, x, y, z, dir);
    continue;
}
```

`resolveStairs` checks two AABB zones:
- **Bottom slab** `[x, x+1] × [y, y+0.5] × [z, z+1]` — always solid.
- **Top step** — a `[0.5, 1.0]` half along the non-facing axis, at `[y+0.5, y+1]`.

For `dy < 0` (falling): land at `y + 0.5` if player is over the bottom slab but NOT over the top step; land at `y + 1.0` if over the top step.  
For horizontal movement: if player foot Y ≥ `y + 0.5` and player is moving into the step side → normal solid push-back. If foot Y < `y + 0.5` → only bottom slab blocks.

### Rendering (`ChunkMesher.emitStairs`)

Bottom slab: 6 faces, height 0 → 0.5.  
Top step: 5 faces (no bottom), occupying the half of the block opposite to the facing direction, height 0.5 → 1.0.

Face culling: a stairs face is culled if the adjacent cell is a solid non-transparent block occupying the same zone (not implemented for stair-stair adjacency — acceptable for now).

---

## 6. Creative Inventory Menu

### State machine change (`Game`)

New state added:

```
MENU → PLAYING ↔ PAUSED
              ↕
         CREATIVE_MENU
```

In `PLAYING`: pressing `E` → `state = CREATIVE_MENU`, `input.grabCursor(false)`.  
In `CREATIVE_MENU`: pressing `E` or `ESC` → `state = PLAYING`, `input.grabCursor(true)`. World update (physics, water ticks, chunk loading) is **paused** while menu is open.

### Hud.drawCreativeMenu

New method signature:
```java
public BlockType drawCreativeMenu(int sw, int sh, double mx, double my,
                                  boolean clicked, BlockType[] hotbar, int selectedSlot)
```

Returns the `BlockType` the user clicked (or `null`).

**Layout:**
- Full-screen dark overlay (alpha 0.75)
- Centered panel 540×420 px
- Title "CREATIVE INVENTORY" at top
- Block grid: 6 columns, rows as needed, 52×52 px slots with 4 px gap
- Excluded from grid: `AIR`, `WATER_FLOW`, `DOOR_OPEN` (internal/implicit blocks)
- Hover: white border + block name tooltip below cursor
- Hotbar strip at bottom of panel showing current hotbar with selected slot highlighted

**On click:**
```java
// in Game.drawUi, CREATIVE_MENU case:
BlockType picked = hud.drawCreativeMenu(...);
if (picked != null) {
    hotbar[selectedSlot] = picked;
    sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f);
}
```

---

## Files Changed / Created

| File | Change |
|---|---|
| `world/Chunk.java` | + `byte[] meta`, accessors |
| `world/World.java` | + `setBlock(..., meta)`, `getBlockMeta()` |
| `world/BlockType.java` | + GLASS, DOOR_CLOSED, DOOR_OPEN, STAIRS, WATER_FLOW |
| `world/WaterSimulator.java` | **new** — BFS water tick |
| `world/ChunkMesher.java` | + emitStairs, emitDoor, variable-height water, stairs collision helper |
| `game/Player.java` | + stair AABB, swimming physics |
| `game/Game.java` | + CREATIVE_MENU state, water tick timer, door interaction, stair placement |
| `game/Hud.java` | + drawCreativeMenu |
| `render/TextureAtlas.java` | + tiles 14–15, drawGlass, drawDoor |
| `audio/Sounds.java` | + door(), water step/swim sounds |

---

## Out of Scope

- Multi-block doors (2-tall)
- Stair-to-stair face culling
- Water rendering below the player's camera (underwater fog/tint)
- Waterlogged blocks (water inside stairs/doors)
- Stone stairs, brick stairs (same system, different texture — add later)
