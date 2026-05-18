# Underwater Effects Design

**Date:** 2026-05-19  
**Status:** Approved

## Overview

Two underwater visual improvements:
1. Water surface visible from below (poluprozrachny rippling sheet looking up)
2. Smooth FOV reduction when camera is fully submerged

## Feature 1 — Water Surface Visible From Below

### Problem

`GL_CULL_FACE` is enabled globally in `Window.java` (`glCullFace(GL_BACK)`). Water top faces are wound CCW from above, so they are back-faces from below and are currently culled — the player looking up underwater sees no water surface at all.

### Solution

Disable backface culling for the duration of the transparent (water) pass in `Game.java`.

**File:** `src/main/java/com/mineclone/game/Game.java`, transparent water pass (~line 892).

```java
// --- Transparent (water) pass ---
glEnable(GL_BLEND);
glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
glDepthMask(false);
glDisable(GL_CULL_FACE);          // render water from both sides
// ... existing water render loop ...
glDepthMask(true);
glDisable(GL_BLEND);
glEnable(GL_CULL_FACE);           // restore global state
// --- End water pass ---
```

### Trade-offs

- Water side faces also render from both sides. Double-blending inside water is acceptable because underwater fog (fogStart=3, fogEnd=12) hides distant geometry.
- No mesher changes, no shader changes.

## Feature 2 — Smooth FOV Reduction Underwater

### Problem

No underwater FOV effect exists. The camera uses a fixed `fovDegrees` at all times.

### Solution

Add a `currentFov` field that smoothly interpolates toward a target each frame.

**File:** `src/main/java/com/mineclone/game/Game.java`

**New field:**
```java
private float currentFov;  // initialized to fovDegrees on load
```

**Initialization** (constructor / load path, after `fovDegrees` is set):
```java
this.currentFov = fovDegrees;
```

**Per-frame update** in `render()`, before `getProjection()`:
```java
float targetFov = player.eyeInWater ? fovDegrees * 0.85f : fovDegrees;
currentFov += (targetFov - currentFov) * (1f - (float) Math.exp(-dt * 8f));
Matrix4f proj = player.camera.getProjection(window.getAspect(), currentFov, 0.1f, 600f);
```

### Parameters

| Parameter | Value | Reasoning |
|-----------|-------|-----------|
| FOV scale underwater | `0.85f` | ~15% reduction; at default 70° gives ~60°, similar to MC feel |
| Interpolation speed | `8f` | ~0.3 s transition, feels snappy but not instant |

### Invariants

- `currentFov` is only used for projection; `fovDegrees` remains the user-configured value.
- Changing FOV in settings takes effect immediately on the next frame (via interpolation convergence).
- `eyeInWater` is already computed in `Player.update()` before `render()` is called.

## Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/mineclone/game/Game.java` | Add `currentFov` field + init; update water pass culling; update projection call |

No other files need modification.
