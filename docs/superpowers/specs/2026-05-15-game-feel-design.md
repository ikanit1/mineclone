# Game Feel: Particles, Day/Night Cycle, Torchlight

**Date:** 2026-05-15  
**Approach:** B (light scaling uniform)

---

## Overview

Three independent features implemented in order of increasing complexity:

1. **Particles with texture** — block-break particles sample the block's atlas UV
2. **Full day/night cycle** — sky color, fog, and ambient light follow game time; meshes scale via `uDaylight` uniform
3. **TORCH block + flood-fill block light** — new block type emits light 15, BFS propagates through chunks, combined with daylight in shader

---

## Feature 1: Particles with Texture

### What changes
- `ParticleSystem.java` — particle struct gains `u0, v0, u1, v1`; VBO layout changes from `{x,y}` to `{x,y,u,v}` (interleaved)
- `Shaders.java` — `PARTICLE_VERTEX` adds `layout(location=1) in vec2 aUv`, passes `vUv` to fragment; `PARTICLE_FRAGMENT` samples `uAtlas`

### UV computation
Atlas is 256×256, 16 tiles × 16px. Tile step = `1/16 = 0.0625`.  
For a block with `sideTile = T`:
```
tileU = (T % 16) * 0.0625
tileV = (T / 16) * 0.0625
```
Each particle picks a random 4×4px sub-region within that tile (UV span = `4/256 = 0.015625`).  
`u0 = tileU + rand * (0.0625 - 0.015625)`, `u1 = u0 + 0.015625` (same for v).

### Invariants
- AIR has `sideTile = -1`; callers must never emit particles for AIR (already guaranteed in `Game.handleInteraction`)
- BEDROCK is also excluded by the existing `!= BEDROCK` guard

---

## Feature 2: Day/Night Cycle

### Game time
`Game.java` gains:
```java
private static final float TIME_SCALE = 0.005f; // 1 real second ≈ 18 game seconds
private float gameTime = (float)(Math.PI * 0.5); // start at noon
```
Each playing tick: `gameTime += dt * TIME_SCALE`.  
`daylight = clamp(sin(gameTime), 0, 1)` — smooth 0→1→0 cycle.

### Sky color palette (3 keyframes)
| `daylight` | Sky RGB | uAmbient |
|---|---|---|
| 1.0 (noon) | `(0.55, 0.75, 0.95)` | 0.22 |
| 0.3 (dusk/dawn) | `(0.85, 0.45, 0.20)` | 0.10 |
| 0.0 (night) | `(0.02, 0.03, 0.08)` | 0.04 |

Interpolation: linear lerp between keyframes based on `daylight`.  
`glClearColor` and `uFogColor` receive the same sky color each frame.

### Shader changes (`CHUNK_FRAGMENT`)
New uniform: `uniform float uDaylight;`  
New varying (from vertex): `out float vBlockLight;` / `in float vBlockLight;`  

```glsl
float combined = max(vLight * uDaylight, vBlockLight);
float shaped   = pow(max(uAmbient, combined), 0.75);
vec3  lit      = tex.rgb * shaped;
```

Night-time: `vLight * 0` → only `vBlockLight` (torch) illuminates. `uAmbient` floor also scales down to 0.04 so caves are genuinely dark.

---

## Feature 3: TORCH Block + Flood-fill Block Light

### BlockType additions
```java
public final int emittedLight; // 0 for all existing, 15 for TORCH
```
New entry:
```java
TORCH(false, true, false, 12, 12, 12,  1.0f,0.85f,0.40f,  15)
```
`solid=false, transparent=true` — players can walk through torches (simplest valid behavior without non-full-block geometry). Added to hotbar in `Game`.

### Chunk.blockLight
```java
public final byte[] blockLight = new byte[SIZE_X * SIZE_Y * SIZE_Z];
```
Values 0–15. Index formula same as existing block data array.  
Getter/setter: `getBlockLight(x,y,z)` / `setBlockLight(x,y,z,val)`.

### World flood-fill

**Place light source** (`emittedLight > 0`):
```
BFS queue starting at (x,y,z) with value=emittedLight
For each neighbor: if neighborLight < currentLight-1 and neighbor is not solid-opaque:
    set neighborLight = currentLight-1, enqueue
Mark all touched chunks dirty
```

**Remove light source**:
```
1. BFS-collect all positions reachable from origin where blockLight > 0 (flood-fill boundary)
2. Zero out blockLight at all collected positions
3. Find all light-emitting blocks within the collected set's bounding box + 1 block margin
4. Re-run floodFillAdd() from each such source
5. Mark touched chunks dirty
```
Step 3 uses a simple AABB scan of the cleared volume to find remaining sources — bounded to at most 33×33×33 blocks, fast enough for interactive play.

BFS is bounded to 15 steps (light value 15 → 0), touching at most a 31×31×31 volume.  
Light propagates through blocks where `!block.solid || block.transparent || block.cutout` (i.e., AIR, WATER, LEAVES, TORCH itself). Stops at fully solid non-transparent blocks (STONE, DIRT, etc.).

### MeshData + ChunkMesher
`MeshData` gains `float[] blockLightData` parallel to existing vertex arrays, or interleaved as 4th float per vertex.

`ChunkMesher.buildData()` reads `chunk.getBlockLight(x,y,z)` (and neighbors for interpolation) at each face, writes `blockLight / 15f` into `aBlockLight`.

Simple per-face approach (no interpolation): all 4 vertices of a face get the same `blockLight` value from the block itself. Good enough for v1.

### Vertex attribute
```glsl
layout(location = 3) in float aBlockLight;
out float vBlockLight;
```
`aBlockLight` passed through vertex shader into `vBlockLight`, used in fragment as described in Feature 2.

### TextureAtlas tile 12
Tile 12 is a simple procedurally generated torch pattern: dark background with a bright orange/yellow center column (4px wide). Generated in `TextureAtlas.generateTile()` alongside existing tiles.

---

## File Change Summary

| File | Change |
|---|---|
| `ParticleSystem.java` | UV fields in P, interleaved VBO, uAtlas uniform |
| `Shaders.java` | PARTICLE_*, CHUNK_VERTEX (aBlockLight attr), CHUNK_FRAGMENT (uDaylight, vBlockLight) |
| `Game.java` | gameTime, daylight(), skyColor(), glClearColor, uDaylight/uAmbient each frame |
| `BlockType.java` | emittedLight field, TORCH entry |
| `Chunk.java` | blockLight array, getBlockLight/setBlockLight |
| `World.java` | floodFillAdd(), floodFillRemove(), called from setBlock() |
| `MeshData.java` | blockLightData array (or 4th interleaved float) |
| `ChunkMesher.java` | read blockLight, write aBlockLight per face |
| `TextureAtlas.java` | tile 12 procedural torch texture |

---

## Out of Scope

- Non-full-block torch geometry (wall-mounted, floor-mounted with offset)
- Skybox with sun/moon sprites
- Mob spawning at night
- Multiple light levels per chunk face (smooth lighting for block light)
