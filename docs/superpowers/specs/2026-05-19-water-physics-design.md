# Water Physics — MC-style Design

**Date:** 2026-05-19  
**Scope:** `WaterSimulator.java` only — two targeted changes.  
**Out of scope:** smart flow (pathfinding), waterlogging, bubble columns (soul sand / magma).

---

## Problem

Current `WaterSimulator` has two deviations from Minecraft water rules:

1. **Fall does not reset the distance counter.**  
   A level-3 flow falling off a cliff lands at level 3 and spreads only 4 blocks sideways. MC rule: any waterfall resets to level 0 at the bottom, spreading 7 blocks.

2. **No infinite source creation.**  
   In MC, an empty cell with ≥2 horizontal source-block (WATER) neighbours automatically becomes a source, enabling infinite 2×2 pools.

---

## Change 1 — Counter reset on fall

**File:** `src/main/java/com/mineclone/world/WaterSimulator.java`  
**Method:** `trySpread`

```java
// Before
int newLevel = myLevel;

// After
int newLevel = 0;
```

**Invariants preserved:**
- Mid-column cells (water above AND can still fall) have `hasWaterAbove && canFall → return` guard, so they never sprout sideways arms regardless of their level.
- Bottom-of-fall cells (water above, solid below) spread sideways as level 1 → 7 blocks. ✓
- Support check: `above == WATER || WATER_FLOW → hasSupport = true` — level-0 flow cells in a column are never orphaned. ✓

---

## Change 2 — Infinite source creation

**File:** `src/main/java/com/mineclone/world/WaterSimulator.java`  
**Method:** `tick`

### New collection

```java
Set<Long> toSource = new HashSet<>();
```

### Conversion condition

A cell becomes a WATER source when:
- It is currently `WATER_FLOW`, **and**
- It has ≥2 horizontal neighbours that are `WATER` (source, not `WATER_FLOW`)

### Where the check runs

1. **Scan loop** — for each existing `WATER_FLOW` cell: if `countSourceNeighbors(...) >= 2` → add to `toSource`, skip normal decay/spread for this cell.
2. **Apply `toAdd` loop** — before writing a new `WATER_FLOW` cell: if `countSourceNeighbors(...) >= 2` → add to `toSource` instead.

### Apply order

```
toRemove → toAdd → toSource
```

`toSource` runs last so it can upgrade cells that were just placed by `toAdd` in the same tick.

### Helper method

```java
private static int countSourceNeighbors(World world, int wx, int wy, int wz, int[][] sides) {
    int n = 0;
    for (int[] d : sides)
        if (world.getBlock(wx + d[0], wy, wz + d[1]) == BlockType.WATER) n++;
    return n;
}
```

### Example: 2×2 infinite pool

1. Player places two WATER sources in opposite corners of a 2-wide channel.
2. Next tick: each source spreads level-1 flow into the gap cells.
3. Each gap cell now has 2 WATER neighbours → `toSource` → becomes WATER.
4. Result: 4 source blocks. Scooping any one refills from its two neighbours. ✓

---

## Files changed

| File | Change |
|------|--------|
| `WaterSimulator.java` | 1-line fix in `trySpread` + infinite-source logic in `tick` + `countSourceNeighbors` helper |

No changes to `BlockType`, `Chunk`, `World`, `Game`, or shaders.
