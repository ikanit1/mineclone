# Sprint + Mining Progress — Design Spec
_2026-05-21_

## Overview

Two gameplay-feel features implemented together because they touch the same interaction loop (player movement + block interaction) and share no conflicting state.

---

## 1. Sprint

### Behaviour
- **Activation (two methods, both always active):**
  - Hold **Left Ctrl** while moving forward (W held) → immediate sprint
  - **Double-tap W** within 0.25 s → activate sprint (sprint remains active only while W is still held, not a persistent toggle)
- **Speed:** `SPRINT_SPEED = WALK_SPEED × 1.3` ≈ 6.24 m/s (ground only)
- **Deactivation:** W released; horizontal wall collision (`velocity.x` or `velocity.z` zeroed by AABB resolver); entering water; entering fly mode
- **FOV boost:** +10° added to `targetFov` while sprinting, same smooth lerp used for underwater FOV (`1 - exp(-dt * 8)`)
- **Not applicable** in water or fly mode — those have their own speed systems

### State (Player.java)
| Field | Type | Purpose |
|---|---|---|
| `isSprinting` | `boolean` | Current sprint state |
| `lastWPressTime` | `float` | World-time of last W keyPressed event, for double-tap detection |
| `SPRINT_SPEED` | `static final float` | `WALK_SPEED * 1.3f` |
| `DOUBLE_TAP_WINDOW` | `static final float` | `0.25f` seconds |

### Implementation notes
- `checkSprintActivation(input, dt)` called at top of `update()` before movement, increments `lastWPressTime` accumulator on `keyPressed(W)`.
- Sprint cancelled inside `moveAxis()` when `velocity.x` or `velocity.z` is zeroed by a collision while `dx != 0` or `dz != 0` (existing wall-stop code already zeroes these — just add `isSprinting = false` there).
- `isSprinting` exposed as public field; Game reads it to adjust `targetFov`.

---

## 2. Mining Progress

### Behaviour
- **Hold LMB** on a breakable block → progress accumulates at rate `1 / hardness` per second
- **Progress resets** when: LMB released, player looks at a different block, player moves beyond ray range
- **Dig sound** plays every **0.4 s** while breaking (replaces the single sound-on-break)
- **Break** fires the existing break logic (setBlock AIR, particles, door cleanup) when `breakProgress >= 1.0`
- BEDROCK: `hardness = Float.MAX_VALUE` → never breaks (same guard as before)

### Hardness values (seconds to break bare-handed)
| Block | Hardness |
|---|---|
| TORCH | 0.05 |
| LEAVES | 0.2 |
| SAND, DIRT | 0.5 |
| GRASS | 0.6 |
| GLASS | 0.3 |
| PLANKS, STAIRS, DOOR | 1.5 |
| WOOD (log) | 2.0 |
| COBBLE, STONE | 7.5 |
| BEDROCK | Float.MAX_VALUE |
| WATER, WATER_FLOW, AIR | 0 (not breakable) |

### State (Game.java)
| Field | Type | Purpose |
|---|---|---|
| `breakX/Y/Z` | `int` | Block currently being broken |
| `breakProgress` | `float` | 0..1 progress |
| `breakDigTimer` | `float` | Accumulator for dig-sound ticks |
| `NO_BREAK` | `int` constant | Sentinel value (-1) for breakX when not breaking |

### BlockType changes
- Add `hardness` float as last constructor parameter to every enum value
- Existing constructor gains one parameter; no other consumers break

### Crack overlay visual

**Atlas:** Add 10 crack tiles at slots **16–25** in `TextureAtlas`, generated procedurally in a new `drawCrackTile(Graphics2D, int stage)` method. Stage 0 = 1–2 faint lines; stage 9 = dense overlapping cracks. Simple approach: each stage adds ~3 random line segments using a seeded `Random(stage)` so tiles are deterministic.

**Renderer:** New class `BlockBreakOverlay` (≈60 lines):
- VAO/VBO for 24 vertices (6 faces × 4 vertices), updated each frame when breaking
- Uses existing `CHUNK` shader with atlas uniform (crack UV computed from tile index)
- `glEnable(GL_POLYGON_OFFSET_FILL)` + `glPolygonOffset(-1, -1)` before draw, restored after — prevents z-fighting with chunk surface
- Crack tiles generated with crack pixels at alpha ~0.55, background at alpha 0.0; `glEnable(GL_BLEND)` + `GL_SRC_ALPHA / GL_ONE_MINUS_SRC_ALPHA` before draw (same as water pass) — CHUNK shader passes texture alpha through to FragColor
- Called from `Game.render()` after BlockOutline pass

### handleInteraction() changes
- Replace `mousePressed(LEFT)` with `mouseDown(LEFT)` for the break path
- Add accumulation logic: same block → increment; different block or no hit → reset
- Keep `mousePressed(RIGHT)` for place/interact (unchanged)
- Extract `executeBlockBreak()` helper with existing break code

---

## Files changed

| File | Change |
|---|---|
| `Player.java` | Sprint state, speed, FOV field, `checkSprintActivation()` |
| `Game.java` | targetFov sprint boost; break state fields; new handleInteraction logic; BlockBreakOverlay lifecycle |
| `BlockType.java` | Add `hardness` float field + values |
| `TextureAtlas.java` | Generate crack tiles 16–25 |
| `BlockBreakOverlay.java` | New renderer class |
| `Shaders.java` | No changes needed (CHUNK shader handles UV + alpha already) |

---

## Out of scope
- Tool/weapon multipliers (hardness unchanged by held item)
- Mining fatigue / haste effects
- Crack texture animation (static per-stage tiles only)
- Hunger drain on sprint
