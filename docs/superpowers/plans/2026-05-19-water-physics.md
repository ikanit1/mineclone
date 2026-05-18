# Water Physics (MC-style) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make water physics match Minecraft rules — waterfall counter reset and infinite source creation.

**Architecture:** Two surgical edits to `WaterSimulator.java`. Change 1 sets `newLevel = 0` on vertical fall so any waterfall resets the distance counter and spreads 7 blocks at the bottom. Change 2 adds a `toSource` pass at the end of each tick that upgrades `WATER_FLOW` cells with ≥2 horizontal `WATER`-source neighbours into full `WATER` sources.

**Tech Stack:** Java 17, LWJGL/OpenGL (no test framework — this project has no test suite; verification is manual in-game).

---

## File Structure

| File | Change |
|------|--------|
| `src/main/java/com/mineclone/world/WaterSimulator.java` | Modify `trySpread` (1 line) + modify `tick` + add `countSourceNeighbors` helper |

---

### Task 1: Reset distance counter on vertical fall

**Files:**
- Modify: `src/main/java/com/mineclone/world/WaterSimulator.java` — method `trySpread`, around line 204

- [ ] **Step 1: Locate the line to change**

Open `src/main/java/com/mineclone/world/WaterSimulator.java`.  
Find the block inside `trySpread` that reads:

```java
if (canFall) {
    long pk = pack(wx, wy - 1, wz);
    // Preserve level on fall — a source (level 0) creates a source-equivalent
    // column, so the bottom-of-fall acts like a fresh source and pools up to
    // 7 cells horizontally (matches Minecraft).
    int newLevel = myLevel;
    Integer prev = toAdd.get(pk);
    if (prev == null || newLevel < prev) toAdd.put(pk, newLevel);
}
```

- [ ] **Step 2: Apply the change**

Replace that entire block with:

```java
if (canFall) {
    long pk = pack(wx, wy - 1, wz);
    // Reset level to 0 on fall so the bottom of any waterfall spreads
    // 7 blocks sideways regardless of how far the source is horizontally.
    int newLevel = 0;
    Integer prev = toAdd.get(pk);
    if (prev == null || newLevel < prev) toAdd.put(pk, newLevel);
}
```

- [ ] **Step 3: Compile**

```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Expected: zero errors.

- [ ] **Step 4: Verify in-game**

Run `.\run.ps1`. Find a cliff (or dig one). Place a water source at the top. Observe:
- The waterfall column is a clean vertical stream (no sideways arms mid-fall). ✓
- At the bottom, water spreads horizontally. Count the blocks: should reach **7 blocks** from the landing point in each direction. ✓
- Remove the source — the pool drains back. ✓

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/mineclone/world/WaterSimulator.java
git commit -m "fix(water): reset distance counter to 0 on vertical fall

Any waterfall now spreads 7 blocks at its base regardless of
the horizontal distance from the source, matching MC behaviour."
```

---

### Task 2: Infinite source creation

**Files:**
- Modify: `src/main/java/com/mineclone/world/WaterSimulator.java` — method `tick` + new private helper

- [ ] **Step 1: Add `countSourceNeighbors` helper at the bottom of the class**

Add this method before the closing `}` of the class (after `unpackZ`):

```java
private static int countSourceNeighbors(World world, int wx, int wy, int wz, int[][] sides) {
    int n = 0;
    for (int[] d : sides)
        if (world.getBlock(wx + d[0], wy, wz + d[1]) == BlockType.WATER) n++;
    return n;
}
```

- [ ] **Step 2: Add `toSource` collection in `tick`**

In the `tick` method, find the declarations of `toRemove` and `toAdd`:

```java
Set<Long> toRemove = new HashSet<>(256);
Map<Long, Integer> toAdd = new HashMap<>(256);
```

Add `toSource` immediately after:

```java
Set<Long> toRemove = new HashSet<>(256);
Map<Long, Integer> toAdd = new HashMap<>(256);
Set<Long> toSource = new HashSet<>(64);
```

- [ ] **Step 3: Add infinite-source check in the scan loop**

In the scan loop, find the block that processes each water cell:

```java
BlockType b = chunk.get(lx, y, lz);
if (b != BlockType.WATER && b != BlockType.WATER_FLOW) continue;

int wx = bx + lx, wy = y, wz = bz + lz;
int myLevel = (b == BlockType.WATER) ? 0 : (chunk.getMeta(lx, y, lz) & 0xF);

// Flow cells without support are scheduled for removal.
if (b == BlockType.WATER_FLOW && !hasSupport(world, wx, wy, wz, myLevel, sides)) {
```

Insert the infinite-source check **before** the `hasSupport` check:

```java
BlockType b = chunk.get(lx, y, lz);
if (b != BlockType.WATER && b != BlockType.WATER_FLOW) continue;

int wx = bx + lx, wy = y, wz = bz + lz;
int myLevel = (b == BlockType.WATER) ? 0 : (chunk.getMeta(lx, y, lz) & 0xF);

// Existing WATER_FLOW with 2+ source neighbours becomes a source.
if (b == BlockType.WATER_FLOW && countSourceNeighbors(world, wx, wy, wz, sides) >= 2) {
    toSource.add(pack(wx, wy, wz));
    continue;
}

// Flow cells without support are scheduled for removal.
if (b == BlockType.WATER_FLOW && !hasSupport(world, wx, wy, wz, myLevel, sides)) {
```

- [ ] **Step 4: Add infinite-source check in the `toAdd` apply loop**

Find the apply-additions block:

```java
for (Map.Entry<Long, Integer> e : toAdd.entrySet()) {
    long pk = e.getKey();
    if (toRemove.contains(pk)) continue;
    int wx = unpackX(pk), wy = unpackY(pk), wz = unpackZ(pk);
    int newLevel = e.getValue();
    BlockType current = world.getBlock(wx, wy, wz);
    if (current == BlockType.AIR) {
        setBlockSafe(world, wx, wy, wz, BlockType.WATER_FLOW, (byte) newLevel);
    } else if (current == BlockType.WATER_FLOW) {
        int curLevel = world.getBlockMeta(wx, wy, wz) & 0xF;
        if (newLevel < curLevel)
            setBlockSafe(world, wx, wy, wz, BlockType.WATER_FLOW, (byte) newLevel);
    }
    // else: WATER source, solid, or any other block — leave it alone
}
```

Replace it with:

```java
for (Map.Entry<Long, Integer> e : toAdd.entrySet()) {
    long pk = e.getKey();
    if (toRemove.contains(pk)) continue;
    int wx = unpackX(pk), wy = unpackY(pk), wz = unpackZ(pk);
    int newLevel = e.getValue();
    BlockType current = world.getBlock(wx, wy, wz);
    if (current == BlockType.AIR) {
        // Newly filled cell: upgrade to source if it has 2+ source neighbours.
        if (countSourceNeighbors(world, wx, wy, wz, sides) >= 2) {
            toSource.add(pk);
        } else {
            setBlockSafe(world, wx, wy, wz, BlockType.WATER_FLOW, (byte) newLevel);
        }
    } else if (current == BlockType.WATER_FLOW) {
        int curLevel = world.getBlockMeta(wx, wy, wz) & 0xF;
        if (newLevel < curLevel) {
            // Strengthened flow: check for source upgrade before writing.
            if (countSourceNeighbors(world, wx, wy, wz, sides) >= 2) {
                toSource.add(pk);
            } else {
                setBlockSafe(world, wx, wy, wz, BlockType.WATER_FLOW, (byte) newLevel);
            }
        }
    }
    // else: WATER source, solid, or any other block — leave it alone
}
```

- [ ] **Step 5: Apply `toSource` after `toAdd`**

Find the end of the `tick` method, after the `toAdd` apply loop and before the closing `}`. The method ends with the `toAdd` loop. Add `toSource` application immediately after:

```java
// Upgrade eligible WATER_FLOW cells to full sources (infinite-source rule).
for (long pk : toSource) {
    int wx = unpackX(pk), wy = unpackY(pk), wz = unpackZ(pk);
    setBlockSafe(world, wx, wy, wz, BlockType.WATER, (byte) 0);
}
```

- [ ] **Step 6: Compile**

```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Expected: zero errors.

- [ ] **Step 7: Verify in-game — 2×2 pool**

Run `.\run.ps1`. On a flat surface, place two water sources in opposite corners of a 2×2 square (diagonal):

```
S . 
. S
```

Observe over a few seconds:
- The two empty cells fill with `WATER_FLOW`. ✓
- Shortly after, all four cells become `WATER` sources (the flowing cells upgrade). ✓
- Scoop water from any corner with a bucket — the cell refills immediately. ✓ (infinite pool)

- [ ] **Step 8: Verify in-game — 1×3 channel**

Place sources at the two ends of a 1×3 channel:

```
S . S
```

Observe:
- The middle cell becomes `WATER` source. ✓
- Scooping the middle cell refills it. ✓
- Scooping either end cell removes it permanently. ✓

- [ ] **Step 9: Verify no unintended spreading**

Place a single source on flat ground. Count that it spreads exactly 7 blocks in each cardinal direction and no further. Removing the source should cause all flow to drain. ✓

- [ ] **Step 10: Commit**

```powershell
git add src/main/java/com/mineclone/world/WaterSimulator.java
git commit -m "feat(water): infinite source creation

WATER_FLOW cells with 2+ horizontal WATER-source neighbours are
upgraded to WATER sources each tick, enabling classic MC infinite
2x2 pools and 1x3 channels."
```
