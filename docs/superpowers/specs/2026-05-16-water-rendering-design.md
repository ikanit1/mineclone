# Water Rendering — Design Spec
**Date:** 2026-05-16

## Problem

Current water rendering has four visible defects:

1. **Falling water gaps** — each `WATER_FLOW` cell has `topY = 7/8` even when the cell above is also water, creating a 1/8-block air slit visible from the side.
2. **No transparency** — `GL_BLEND` is disabled during chunk rendering, so the water tile's alpha (200/255) is ignored. Water appears as opaque dark blue.
3. **Z-fighting at shore** — water side faces are emitted flush against adjacent solid blocks (sand, dirt, grass), causing depth-buffer flickering.
4. **Inconsistent flags** — `WATER` is `transparent=true, cutout=true`; `WATER_FLOW` is `transparent=true, cutout=false`. The difference leaks into face-culling logic.

## Approach

**Dual-mesh chunks with a back-to-front transparent pass.**

Each chunk produces two meshes: an opaque mesh (existing behaviour) and a water mesh (new). The render loop draws all opaque meshes first, then all water meshes sorted far-to-near with alpha blending enabled.

Chosen over:
- *World-wide water buffer* — more complex cache invalidation.
- *Separate WaterRenderer with its own shader* — over-engineered for the current stack.

---

## Architecture

### MeshData / ChunkMesher

`ChunkMesher.buildData(Chunk)` changes return type from `MeshData` to `MeshData[]` of length 2:
- `[0]` — opaque geometry (unchanged logic)
- `[1]` — water geometry (all water quads extracted from `emitWaterBlock`)

`emitWaterBlock` is called from within `buildData` exactly as now; its output goes into the water lists instead of the shared lists.

### Game — mesh storage

```
Map<Long, Mesh> opaqueMeshes   // replaces chunkMeshes
Map<Long, Mesh> waterMeshes    // new
```

Both maps are keyed by the same `long` chunk key. On chunk (re)build: upload `data[0]` → `opaqueMeshes`, upload `data[1]` → `waterMeshes`. On chunk unload: remove from both maps.

### Game — render order

```
1. Opaque pass
   glDisable(GL_BLEND)
   glDepthMask(true)
   draw opaqueMeshes (existing loop)

2. Water pass
   glEnable(GL_BLEND)
   glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
   glDepthMask(false)
   sort waterMeshes keys: far → near (by chunk-centre distance to player)
   draw sorted waterMeshes
   glDepthMask(true)
   glDisable(GL_BLEND)
```

Same `chunkShader` is reused — it already outputs `tex.a` in `FragColor.a`.

---

## Fixes in ChunkMesher

### Fix 1 — Falling water gaps

Before computing `topY` in `emitWaterBlock`, check the block above:

```java
boolean waterAbove = above == BlockType.WATER || above == BlockType.WATER_FLOW;
float topY = waterAbove ? 1.0f : waterLevelTopY(b, meta);
```

In `waterCornerTopY`: when sampling a horizontal neighbour, if the block directly above that neighbour is also water, treat its contribution as `1.0f` rather than `waterLevelTopY`.

### Fix 2 — Z-fighting at shore

Remove the `else if (nb.solid)` branch in the side-face loop (currently `ChunkMesher.java:230`). Water faces against solid neighbours are invisible from outside and cause z-fighting — skip them entirely.

### Fix 3 — Flag unification

```java
// BlockType.java
WATER_FLOW(false, true, false, 8, 8, 8, ...)   // transparent=true (was already)
```

In side-face condition simplify:
```java
// before:
(nb.transparent && nb != BlockType.WATER && nb != BlockType.WATER_FLOW)
// after:
nb.transparent
```
(The `nb != WATER/WATER_FLOW` guard is no longer needed because those cases are handled by the explicit `nb == WATER || nb == WATER_FLOW` branch above it.)

### Fix 4 — Water tile alpha

`TextureAtlas.drawWater` already writes `alpha=200`. No change needed — blending will use it automatically once the pass is enabled.

---

## Non-goals

- Per-quad depth sorting (chunk-level sort is sufficient).
- Underwater caustics / reflections.
- Separate water shader.
- Water animation changes (existing UV-scroll stays as-is).

---

## Files changed

| File | Change |
|---|---|
| `ChunkMesher.java` | `buildData` returns `MeshData[2]`; fix topY for falling water; remove solid-neighbour side face; simplify face condition |
| `BlockType.java` | Unify `WATER`/`WATER_FLOW` transparent/cutout flags if needed |
| `Game.java` | Two mesh maps; dual upload on chunk ready; back-to-front water pass |
| `TextureAtlas.java` | No change |
| `Shaders.java` | No change |
