# Water Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render water as transparent, fix falling-water gaps, remove shore z-fighting, and unify water block flags.

**Architecture:** `ChunkMesher.buildData()` returns `MeshData[2]` (opaque + water). `ChunkLoader.Ready` carries `MeshData[]`. `Game` keeps two mesh maps and renders water in a back-to-front blended pass after the opaque pass.

**Tech Stack:** Java 17, OpenGL 3.3 (LWJGL), JOML. No test suite — verification is compile + visual run.

---

## File Map

| File | Change |
|---|---|
| `src/main/java/com/mineclone/world/BlockType.java` | Unify `WATER`/`WATER_FLOW` flags |
| `src/main/java/com/mineclone/world/ChunkMesher.java` | `buildData` → `MeshData[2]`; fix falling water; remove solid-side z-fight; simplify face cull |
| `src/main/java/com/mineclone/world/ChunkLoader.java` | `Ready.data` → `MeshData[]` |
| `src/main/java/com/mineclone/game/Game.java` | Two mesh maps; dual upload; back-to-front water pass with blending |

---

## Task 1: Unify WATER / WATER_FLOW flags in BlockType

**Files:**
- Modify: `src/main/java/com/mineclone/world/BlockType.java:11,20`

- [ ] **Step 1: Edit BlockType.java**

Change the `WATER_FLOW` line (currently line 20) so `cutout=false` stays but confirm `transparent=true` matches `WATER`. The goal is that both water types have identical `solid=false, transparent=true, cutout=false` — `WATER.cutout` was `true`, change it to `false` so both match:

```java
// line 11 — WATER
WATER(false, true, false, 8, 8, 8, 0.16f, 0.35f, 0.78f, 0),
// line 20 — WATER_FLOW (already transparent=true, cutout=false — no change needed here)
WATER_FLOW(false, true, false, 8, 8, 8, 0.16f, 0.35f, 0.78f, 0),
```

Only `WATER` changes: third constructor arg goes from `true` → `false`.

- [ ] **Step 2: Compile to verify**

```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Expected: no errors.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/mineclone/world/BlockType.java
git commit -m "fix: unify WATER and WATER_FLOW transparent/cutout flags"
```

---

## Task 2: ChunkMesher — fix water meshing bugs + split into MeshData[2]

**Files:**
- Modify: `src/main/java/com/mineclone/world/ChunkMesher.java`

This task is the core of the fix. Four changes in one file, committed together.

### 2a — Fix falling water topY

- [ ] **Step 1: Fix `waterCornerTopY` to treat water-below-water as full height**

In `waterCornerTopY` (line 137), when computing a neighbour's contribution, if the block **directly above** that neighbour is also water, use `1.0f` as its contribution. Replace the method body:

```java
private float waterCornerTopY(int wx, int wy, int wz) {
    int[] bxs = { wx - 1, wx, wx - 1, wx };
    int[] bzs = { wz - 1, wz - 1, wz, wz };
    float sum = 0f; int n = 0;
    for (int i = 0; i < 4; i++) {
        BlockType b = world.getBlock(bxs[i], wy, bzs[i]);
        if (b == BlockType.WATER || b == BlockType.WATER_FLOW) {
            BlockType above = world.getBlock(bxs[i], wy + 1, bzs[i]);
            boolean hasWaterAbove = above == BlockType.WATER || above == BlockType.WATER_FLOW;
            if (hasWaterAbove) {
                sum += 1.0f;
            } else {
                byte m = world.getBlockMeta(bxs[i], wy, bzs[i]);
                sum += waterLevelTopY(b, m);
            }
            n++;
        }
    }
    return n > 0 ? sum / n : 1.0f;
}
```

- [ ] **Step 2: Fix topY in `emitWaterBlock` to use 1.0 when water above**

In `emitWaterBlock` (around line 158), the `above` block is already computed at line 178. Move the `above` fetch before `topY` and use it:

Replace lines 158–159:
```java
// OLD:
byte meta = chunk.getMeta(x, y, z);
float topY = waterLevelTopY(b, meta);
```
with:
```java
byte meta = chunk.getMeta(x, y, z);
BlockType above = chunk.inBounds(x, y + 1, z)
        ? chunk.get(x, y + 1, z)
        : world.getBlock(baseX + x, y + 1, baseZ + z);
boolean waterAbove = above == BlockType.WATER || above == BlockType.WATER_FLOW;
float topY = waterAbove ? 1.0f : waterLevelTopY(b, meta);
```

Then remove the duplicate `above` fetch at the original line 178 (now ~line 183) since `above` is already declared. The if-condition at that line becomes:

```java
if (!waterAbove) {
```

### 2b — Remove z-fighting against solid neighbours

- [ ] **Step 3: Remove the `else if (nb.solid)` branch in side-face loop**

In `emitWaterBlock`, inside the `for (int f = 0; f < 4; f++)` loop, find:

```java
} else if (nb.solid) {
    // render water face to cover the visible gap at the shore
} else {
    continue;
}
```

Replace with just:

```java
} else {
    continue;
}
```

(The empty `else if (nb.solid)` block fell through to emit the face. By removing it, water faces against solid blocks are skipped entirely.)

### 2c — Simplify transparent-neighbour condition

- [ ] **Step 4: Simplify side-face transparent check**

In the same side-face loop, the first condition currently reads:

```java
if (nb == BlockType.AIR || (nb.transparent && nb != BlockType.WATER && nb != BlockType.WATER_FLOW)) {
```

Now that WATER and WATER_FLOW are both `transparent=true`, the exclusion is still correct (they're handled by the `else if (nb == WATER || nb == WATER_FLOW)` branch). But make intent explicit:

```java
if (nb == BlockType.AIR || (nb.transparent && nb != BlockType.WATER && nb != BlockType.WATER_FLOW)) {
    // render full face — neighbour is air or a non-water transparent block (glass, leaves)
```

No code change needed here — the existing logic is already correct after the flag fix. Leave as-is.

### 2d — Split buildData into MeshData[2]

- [ ] **Step 5: Add separate water lists and return MeshData[2]**

In `buildData(Chunk chunk)` (line 43), add six new water lists alongside the existing six opaque lists:

```java
public MeshData[] buildData(Chunk chunk) {
    List<Float> positions    = new ArrayList<>(4096);
    List<Float> uvs          = new ArrayList<>(2048);
    List<Float> light        = new ArrayList<>(1024);
    List<Float> blockLightList = new ArrayList<>(1024);
    List<Integer> indices    = new ArrayList<>(4096);

    List<Float> wPositions   = new ArrayList<>(1024);
    List<Float> wUvs         = new ArrayList<>(512);
    List<Float> wLight       = new ArrayList<>(256);
    List<Float> wBlockLight  = new ArrayList<>(256);
    List<Integer> wIndices   = new ArrayList<>(1024);
    // ...
```

Pass the water lists into `emitWaterBlock` instead of the opaque lists. Update the `emitWaterBlock` signature:

```java
private void emitWaterBlock(Chunk chunk,
        List<Float> pos, List<Float> uvs,
        List<Float> light, List<Float> blockLightList,
        List<Integer> idx,
        int x, int y, int z, int baseX, int baseZ,
        BlockType b) {
```

Change the call site (around line 66):

```java
if (b == BlockType.WATER || b == BlockType.WATER_FLOW) {
    emitWaterBlock(chunk, wPositions, wUvs, wLight, wBlockLight, wIndices,
            x, y, z, baseX, baseZ, b);
    continue;
}
```

At the end of `buildData`, return both:

```java
MeshData opaque = new MeshData(
        toFloatArray(positions), toFloatArray(uvs),
        toFloatArray(light), toFloatArray(blockLightList),
        toIntArray(indices));
MeshData water = new MeshData(
        toFloatArray(wPositions), toFloatArray(wUvs),
        toFloatArray(wLight), toFloatArray(wBlockLight),
        toIntArray(wIndices));
return new MeshData[]{ opaque, water };
```

- [ ] **Step 6: Fix the `build()` convenience method**

Line 38–40 currently:

```java
public Mesh build(Chunk chunk) {
    return buildData(chunk).upload();
}
```

Replace with (uploads only opaque — `build()` is used in the sync dirty path which we will update in Task 4):

```java
public MeshData[] buildData(Chunk chunk) { ... }  // already returning MeshData[]

// build() is removed — callers will be updated in Task 4
```

Actually keep `build()` temporarily pointing to opaque only so it compiles during this task:

```java
/** Temporary — returns only opaque mesh; callers updated in Task 4. */
public Mesh build(Chunk chunk) {
    return buildData(chunk)[0].upload();
}
```

- [ ] **Step 7: Compile**

```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Expected: no errors.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/mineclone/world/ChunkMesher.java
git commit -m "fix: split buildData into opaque+water MeshData[2], fix falling water gaps and shore z-fight"
```

---

## Task 3: ChunkLoader — carry MeshData[] instead of MeshData

**Files:**
- Modify: `src/main/java/com/mineclone/world/ChunkLoader.java:24-28,87-88`

- [ ] **Step 1: Update `Ready` record**

Lines 24–27:

```java
// OLD:
public static final class Ready {
    public final long key;
    public final MeshData data;
    Ready(long key, MeshData data) { this.key = key; this.data = data; }
}
```

```java
// NEW:
public static final class Ready {
    public final long key;
    public final MeshData[] data;  // [0]=opaque, [1]=water
    Ready(long key, MeshData[] data) { this.key = key; this.data = data; }
}
```

- [ ] **Step 2: Update `submitMesh` to pass `MeshData[]`**

Line 87:

```java
// OLD:
MeshData data = mesher.buildData(c);
ready.offer(new Ready(key, data));
```

```java
// NEW:
MeshData[] data = mesher.buildData(c);
ready.offer(new Ready(key, data));
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

Expected: `Game.java` will now fail to compile (uses `r.data.upload()`) — that is expected and fixed in Task 4.

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/mineclone/world/ChunkLoader.java
git commit -m "refactor: ChunkLoader.Ready carries MeshData[] for dual opaque/water meshes"
```

---

## Task 4: Game — dual mesh maps + blended water pass

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java`

- [ ] **Step 1: Add `waterMeshes` map next to `chunkMeshes`**

Around line 71 find:
```java
private final Map<Long, Mesh> chunkMeshes = new HashMap<>();
```

Add below it:
```java
private final Map<Long, Mesh> waterMeshes = new HashMap<>();
```

- [ ] **Step 2: Update `ensureChunksLoaded` — upload both meshes**

Lines 301–306:

```java
// OLD:
for (ChunkLoader.Ready r : loader.drainReady(3)) {
    Mesh old = chunkMeshes.remove(r.key);
    if (old != null) old.destroy();
    chunkMeshes.put(r.key, r.data.upload());
}
```

```java
// NEW:
for (ChunkLoader.Ready r : loader.drainReady(3)) {
    Mesh old = chunkMeshes.remove(r.key);
    if (old != null) old.destroy();
    Mesh oldW = waterMeshes.remove(r.key);
    if (oldW != null) oldW.destroy();
    if (!r.data[0].isEmpty()) chunkMeshes.put(r.key, r.data[0].upload());
    if (!r.data[1].isEmpty()) waterMeshes.put(r.key, r.data[1].upload());
}
```

- [ ] **Step 3: Update `updateDirtyMeshes` — rebuild both meshes synchronously**

Lines 494–508:

```java
// OLD:
private void updateDirtyMeshes() {
    int rebuilt = 0;
    for (Chunk c : world.getLoadedChunks()) {
        if (!c.dirty) continue;
        long key = World.key(c.cx, c.cz);
        Mesh old = chunkMeshes.remove(key);
        if (old != null) old.destroy();
        chunkMeshes.put(key, mesher.build(c));
        loader.markMeshed(key);
        c.dirty = false;
        if (++rebuilt >= 4) break;
    }
}
```

```java
// NEW:
private void updateDirtyMeshes() {
    int rebuilt = 0;
    for (Chunk c : world.getLoadedChunks()) {
        if (!c.dirty) continue;
        long key = World.key(c.cx, c.cz);
        Mesh old = chunkMeshes.remove(key);
        if (old != null) old.destroy();
        Mesh oldW = waterMeshes.remove(key);
        if (oldW != null) oldW.destroy();
        MeshData[] data = mesher.buildData(c);
        if (!data[0].isEmpty()) chunkMeshes.put(key, data[0].upload());
        if (!data[1].isEmpty()) waterMeshes.put(key, data[1].upload());
        loader.markMeshed(key);
        c.dirty = false;
        if (++rebuilt >= 4) break;
    }
}
```

- [ ] **Step 4: Remove now-unused `build()` shim from ChunkMesher**

Open `ChunkMesher.java` and delete the `build()` method entirely (it was the shim from Task 2 Step 6):

```java
// DELETE this method:
public Mesh build(Chunk chunk) {
    return buildData(chunk)[0].upload();
}
```

Also remove the unused import `import com.mineclone.render.Mesh;` in `ChunkMesher.java` if it was there.

- [ ] **Step 5: Update `cleanup()` — destroy water meshes**

Lines 692–697:

```java
// OLD:
for (Mesh m : chunkMeshes.values())
    m.destroy();
chunkMeshes.clear();
```

```java
// NEW:
for (Mesh m : chunkMeshes.values()) m.destroy();
chunkMeshes.clear();
for (Mesh m : waterMeshes.values()) m.destroy();
waterMeshes.clear();
```

- [ ] **Step 6: Add the blended water render pass after the opaque pass**

In `render()`, the opaque chunk loop ends around line 560 with `chunkShader.unbind()`. After it, add the water pass. The `chunkShader` uniform state (projection, view, fog, ambient, daylight, brightness, time, atlas) is still valid — just re-bind the shader and draw:

Find this block (lines 560–562):
```java
        chunkShader.unbind();
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
```

After `chunkShader.unbind()` and before `glPolygonMode` restore, insert:

```java
        // --- Transparent (water) pass ---
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        chunkShader.bind();
        chunkShader.setMat4("uProjection", proj);
        chunkShader.setMat4("uView", view);
        chunkShader.setInt("uAtlas", 0);
        chunkShader.setVec3("uFogColor", fogColor);
        chunkShader.setFloat("uFogStart", fogStart);
        chunkShader.setFloat("uFogEnd", fogEnd);
        chunkShader.setFloat("uAmbient", 0.04f + 0.18f * daylight);
        chunkShader.setFloat("uDaylight", daylight);
        chunkShader.setFloat("uBrightness", brightness);
        chunkShader.setFloat("uTime", totalTime);
        atlas.bind(0);

        // Sort water chunks back-to-front
        List<Long> waterKeys = new ArrayList<>(waterMeshes.keySet());
        waterKeys.sort((ka, kb) -> {
            int cxa = (int)(ka >> 32), cza = (int)(ka & 0xFFFFFFFFL);
            int cxb = (int)(kb >> 32), czb = (int)(kb & 0xFFFFFFFFL);
            float dxa = cxa * Chunk.SIZE_X + Chunk.SIZE_X * 0.5f - player.position.x;
            float dza = cza * Chunk.SIZE_Z + Chunk.SIZE_Z * 0.5f - player.position.z;
            float dxb = cxb * Chunk.SIZE_X + Chunk.SIZE_X * 0.5f - player.position.x;
            float dzb = czb * Chunk.SIZE_Z + Chunk.SIZE_Z * 0.5f - player.position.z;
            float distA = dxa * dxa + dza * dza;
            float distB = dxb * dxb + dzb * dzb;
            return Float.compare(distB, distA); // far first
        });
        for (Long k : waterKeys) {
            int cx = (int)(k >> 32), cz = (int)(k & 0xFFFFFFFFL);
            if (Math.abs(cx - pcx) > renderRadius || Math.abs(cz - pcz) > renderRadius)
                continue;
            Matrix4f model = new Matrix4f().translate(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z);
            chunkShader.setMat4("uModel", model);
            waterMeshes.get(k).render();
        }
        chunkShader.unbind();
        glDepthMask(true);
        glDisable(GL_BLEND);
        // --- End water pass ---
```

Make sure `java.util.ArrayList` is already imported (it should be).

- [ ] **Step 7: Compile**

```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Expected: clean build, no errors.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/mineclone/game/Game.java src/main/java/com/mineclone/world/ChunkMesher.java
git commit -m "feat: dual-mesh transparent water pass with back-to-front chunk sort"
```

---

## Task 5: Visual verification

- [ ] **Step 1: Run the game**

```powershell
.\run.ps1
```

- [ ] **Step 2: Check falling water** — place water on a high ledge with open air below. The falling column should show a continuous texture with no 1/8-block gaps.

- [ ] **Step 3: Check shore** — stand at water edge next to grass/sand. No z-fighting flicker on the side face.

- [ ] **Step 4: Check transparency** — look straight down into a lake. Sand/dirt on the bottom should be visible through the water surface.

- [ ] **Step 5: Check underwater fog** — jump in water. The blue fog overlay should still work.

- [ ] **Step 6: Commit if clean**

```powershell
git add -A
git commit -m "fix: water rendering — transparent, no gaps, no shore z-fight"
```
