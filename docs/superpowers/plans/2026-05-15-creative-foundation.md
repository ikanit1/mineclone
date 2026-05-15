# Creative Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add block metadata, water physics (BFS + 8 levels + swimming), glass/door/stairs blocks, and a Creative inventory menu to Mineclone.

**Architecture:** A parallel `byte[] meta` array in `Chunk` stores per-block state (stair direction, door open/close, water level). `WaterSimulator` runs a BFS tick every 0.5 s in `Game`. Stairs get a custom `resolveStairs()` collision path in `Player`. The Creative menu is a new `CREATIVE_MENU` game state rendered by `Hud`.

**Tech Stack:** Java 17, LWJGL 3, JOML — no build tool, compile with PowerShell snippet in CLAUDE.md.

> **Note:** This project has no test suite. Each task's verification step is a compile check followed by a manual in-game test with specific pass criteria.

**Compile command (run from project root in PowerShell):**
```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

---

## Task 1: Block Metadata System

**Files:**
- Modify: `src/main/java/com/mineclone/world/Chunk.java`
- Modify: `src/main/java/com/mineclone/world/World.java`

- [ ] **Step 1.1 — Add `byte[] meta` to Chunk**

  In `Chunk.java`, add the meta array alongside the existing `blocks` array and add public accessors. The existing `idx(x,y,z)` static method is reused.

  ```java
  // After the existing blockLight array declaration (line ~17):
  private final byte[] meta = new byte[SIZE_X * SIZE_Y * SIZE_Z];

  // Add these two methods after setBlockLight():
  public byte getMeta(int x, int y, int z) {
      if (!inBounds(x, y, z)) return 0;
      return meta[idx(x, y, z)];
  }

  public void setMeta(int x, int y, int z, byte val) {
      if (!inBounds(x, y, z)) return;
      meta[idx(x, y, z)] = val;
  }
  ```

- [ ] **Step 1.2 — Add meta accessors to World**

  In `World.java`, add `getBlockMeta()` and an overloaded `setBlock()` that accepts meta. Place after the existing `setBlock(int,int,int,BlockType)` method.

  ```java
  public byte getBlockMeta(int wx, int wy, int wz) {
      if (wy < 0 || wy >= Chunk.SIZE_Y) return 0;
      int cx = Math.floorDiv(wx, Chunk.SIZE_X);
      int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
      Chunk c = getChunkIfExists(cx, cz);
      if (c == null) return 0;
      return c.getMeta(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z));
  }

  public void setBlock(int wx, int wy, int wz, BlockType t, byte meta) {
      setBlock(wx, wy, wz, t); // handles light propagation and dirty marking
      if (wy < 0 || wy >= Chunk.SIZE_Y) return;
      int cx = Math.floorDiv(wx, Chunk.SIZE_X);
      int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
      Chunk c = getChunkIfExists(cx, cz);
      if (c == null) return;
      c.setMeta(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z), meta);
  }
  ```

- [ ] **Step 1.3 — Compile and verify**

  Run the compile command above. Expected: `0 errors`. No runtime test needed — this task only adds new methods.

- [ ] **Step 1.4 — Commit**

  ```
  git add src/main/java/com/mineclone/world/Chunk.java
  git add src/main/java/com/mineclone/world/World.java
  git commit -m "feat: add per-block metadata array to Chunk and World"
  ```

---

## Task 2: New BlockTypes and Sounds

**Files:**
- Modify: `src/main/java/com/mineclone/world/BlockType.java`
- Modify: `src/main/java/com/mineclone/audio/Sounds.java`

- [ ] **Step 2.1 — Add five new BlockType constants**

  In `BlockType.java`, change the semicolon after `TORCH` to a comma and append:

  ```java
  // Change:
  TORCH  (false, true,  false, 12,12,12, 1.0f, 0.85f,0.40f,15);
  // To:
  TORCH       (false, true,  false, 12,12,12, 1.0f, 0.85f,0.40f,15),
  GLASS       (true,  false, true,  14,14,14, 0.70f,0.90f,0.90f, 0),
  DOOR_CLOSED (true,  false, false, 15,15,15, 0.60f,0.45f,0.27f, 0),
  DOOR_OPEN   (false, true,  false, 15,15,15, 0.60f,0.45f,0.27f, 0),
  STAIRS      (true,  false, false, 11,11,11, 0.60f,0.45f,0.27f, 0),
  WATER_FLOW  (false, true,  false,  8, 8, 8, 0.16f,0.35f,0.78f, 0);
  ```

- [ ] **Step 2.2 — Add sound methods to Sounds**

  In `Sounds.java`, update `materialOf()` and add two new public methods after `emptyList()`:

  ```java
  // Update materialOf() — add GLASS, WATER, WATER_FLOW, DOOR_CLOSED, DOOR_OPEN to existing switch:
  public Material materialOf(BlockType b) {
      if (b == null) return Material.NONE;
      return switch (b) {
          case GRASS, DIRT, LEAVES -> Material.GRASS;
          case STONE, COBBLE, BEDROCK -> Material.STONE;
          case SAND -> Material.SAND;
          case WOOD, PLANKS, TORCH -> Material.WOOD;
          case GLASS -> Material.STONE; // glass breaks like stone
          case STAIRS -> Material.WOOD;
          default -> Material.NONE;
      };
  }

  // Add after emptyList():
  public List<String> waterSplash() {
      return listMatching(ROOT + "/liquid", "splash");
  }

  public List<String> waterSwim() {
      return listMatching(ROOT + "/liquid", "swim");
  }

  public List<String> doorToggle() {
      // Uses random/door_open.ogg and random/door_close.ogg
      List<String> out = new ArrayList<>();
      File open  = new File(ROOT + "/random/door_open.ogg");
      File close = new File(ROOT + "/random/door_close.ogg");
      if (open.exists())  out.add(open.getAbsolutePath());
      if (close.exists()) out.add(close.getAbsolutePath());
      return out;
  }
  ```

  Note: `listMatching` is already `private static` in `Sounds` — call it via `Sounds.listMatching(...)` or duplicate the call pattern using `new File(ROOT + "/liquid")`. Since `listMatching` is private, use the same pattern as `uiClick()` for `doorToggle()` (shown above). For `waterSplash()` and `waterSwim()`, make `listMatching` package-private (`static List<String> listMatching`) instead of `private static`.

  Change `private static List<String> listMatching` to `static List<String> listMatching` in `Sounds.java`.

- [ ] **Step 2.3 — Compile and verify**

  Run compile. Expected: `0 errors`. Launch `.\run.ps1` to confirm the game opens without crash.

- [ ] **Step 2.4 — Commit**

  ```
  git add src/main/java/com/mineclone/world/BlockType.java
  git add src/main/java/com/mineclone/audio/Sounds.java
  git commit -m "feat: add GLASS, DOOR_CLOSED, DOOR_OPEN, STAIRS, WATER_FLOW block types and sounds"
  ```

---

## Task 3: Texture Atlas (Glass + Door Tiles)

**Files:**
- Modify: `src/main/java/com/mineclone/render/TextureAtlas.java`

- [ ] **Step 3.1 — Register tile names**

  In `TextureAtlas.java`, extend `TILE_NAMES` (append after `"particle"` at index 13):

  ```java
  public static final String[] TILE_NAMES = {
      "grass_top",   // 0
      "grass_side",  // 1
      "dirt",        // 2
      "stone",       // 3
      "sand",        // 4
      "log_side",    // 5
      "log_top",     // 6
      "leaves",      // 7
      "water",       // 8
      "bedrock",     // 9
      "cobblestone", // 10
      "planks",      // 11
      "torch",       // 12
      "particle",    // 13
      "glass",       // 14
      "door",        // 15
  };
  ```

- [ ] **Step 3.2 — Add procedural drawers**

  In `generateTile(int index)`, add two cases inside the switch:

  ```java
  case 14 -> drawGlass(t);
  case 15 -> drawDoor(t);
  ```

  Add the two drawer methods after `drawTorch()`:

  ```java
  private static void drawGlass(BufferedImage t) {
      // Semi-transparent blue tint with 1px opaque border grid
      int body   = (160 << 24) | (170 << 16) | (210 << 8) | 240;
      int border = (220 << 24) | (200 << 16) | (230 << 8) | 255;
      int center = (80 << 24)  | (140 << 16) | (190 << 8) | 220; // subtle cross highlight
      for (int y = 0; y < TILE; y++)
          for (int x = 0; x < TILE; x++) {
              boolean edge = x == 0 || x == TILE - 1 || y == 0 || y == TILE - 1;
              boolean cross = (x == TILE / 2 || y == TILE / 2);
              px(t, x, y, edge ? border : cross ? center : body);
          }
  }

  private static void drawDoor(BufferedImage t) {
      Random r = new Random(200);
      // Planks background
      for (int y = 0; y < TILE; y++)
          for (int x = 0; x < TILE; x++) {
              int base = (y % 4 == 0) ? 80 : 155;
              int v = jitter(r, base, 10);
              px(t, x, y, rgb(v, (int)(v * 0.75), (int)(v * 0.45)));
          }
      // Outer frame (2px border)
      for (int i = 0; i < TILE; i++) {
          int fc = rgb(55, 36, 18);
          px(t, i, 0, fc); px(t, i, 1, fc);
          px(t, i, TILE - 1, fc); px(t, i, TILE - 2, fc);
          px(t, 0, i, fc); px(t, 1, i, fc);
          px(t, TILE - 1, i, fc); px(t, TILE - 2, i, fc);
      }
      // Horizontal divider at mid height
      for (int x = 2; x < TILE - 2; x++) {
          int fc = rgb(55, 36, 18);
          px(t, x, TILE / 2, fc); px(t, x, TILE / 2 + 1, fc);
      }
      // Door knob
      px(t, 12, TILE / 2 - 2, rgb(200, 170, 40));
      px(t, 12, TILE / 2 - 1, rgb(200, 170, 40));
  }
  ```

- [ ] **Step 3.3 — Regenerate atlas and verify textures**

  ```powershell
  .\run.ps1 --regen-atlas
  ```

  Expected: game launches, new tiles 14 (glass) and 15 (door) appear in `assets/atlas.png`. Open `assets/atlas.png` in an image viewer to confirm the 16th and 17th tiles (row 0, columns 14 and 15) look like glass and a door.

- [ ] **Step 3.4 — Commit**

  ```
  git add src/main/java/com/mineclone/render/TextureAtlas.java assets/atlas.png
  git add assets/textures/blocks/glass.png assets/textures/blocks/door.png
  git commit -m "feat: add glass and door tiles to texture atlas (indices 14, 15)"
  ```

---

## Task 4: Water Simulator (BFS Tick)

**Files:**
- Create: `src/main/java/com/mineclone/world/WaterSimulator.java`
- Modify: `src/main/java/com/mineclone/game/Game.java`

- [ ] **Step 4.1 — Create WaterSimulator**

  Create `src/main/java/com/mineclone/world/WaterSimulator.java`:

  ```java
  package com.mineclone.world;

  import java.util.*;

  public final class WaterSimulator {

      private WaterSimulator() {}

      /**
       * One water simulation tick. Called every ~0.5 s from the game loop.
       * BFS from every WATER (source) block outward:
       *   - straight down preserves level (fills ravines at full density)
       *   - sideways increments level by 1 per step, max level 7
       * Any WATER_FLOW cell not reached by the BFS is cleared to AIR.
       */
      public static void tick(World world) {
          Set<Long> visited = new HashSet<>(4096);
          Deque<long[]> queue = new ArrayDeque<>(512);

          // Seed BFS from all WATER source blocks in loaded chunks
          for (Chunk chunk : world.getLoadedChunks()) {
              int bx = chunk.cx * Chunk.SIZE_X;
              int bz = chunk.cz * Chunk.SIZE_Z;
              for (int lx = 0; lx < Chunk.SIZE_X; lx++)
                  for (int y = 0; y < Chunk.SIZE_Y; y++)
                      for (int lz = 0; lz < Chunk.SIZE_Z; lz++)
                          if (chunk.get(lx, y, lz) == BlockType.WATER) {
                              long pk = pack(bx + lx, y, bz + lz);
                              if (visited.add(pk))
                                  queue.add(new long[]{bx + lx, y, bz + lz, 0});
                          }
          }

          // Collect changes to apply in bulk (avoid mutating while iterating)
          List<int[]> toSet = new ArrayList<>();

          while (!queue.isEmpty()) {
              long[] cur = queue.poll();
              int wx = (int) cur[0], wy = (int) cur[1], wz = (int) cur[2];
              int level = (int) cur[3]; // 0 = source, 1-7 = flow level

              // Try directly below — same level going down (no level increment)
              int bwy = wy - 1;
              if (bwy >= 0) {
                  BlockType below = world.getBlock(wx, bwy, wz);
                  if (below == BlockType.AIR) {
                      long pk = pack(wx, bwy, wz);
                      if (visited.add(pk)) {
                          toSet.add(new int[]{wx, bwy, wz, level == 0 ? 1 : level});
                          queue.add(new long[]{wx, bwy, wz, level});
                      }
                  } else if (below == BlockType.WATER || below == BlockType.WATER_FLOW) {
                      visited.add(pack(wx, bwy, wz));
                  }
              }

              // Try horizontal neighbours if level < 7
              if (level < 7) {
                  int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                  for (int[] d : sides) {
                      int nx = wx + d[0], nz = wz + d[1];
                      long pk = pack(nx, wy, nz);
                      if (visited.contains(pk)) continue;
                      BlockType nb = world.getBlock(nx, wy, nz);
                      if (nb == BlockType.AIR) {
                          int newLevel = level + 1;
                          visited.add(pk);
                          toSet.add(new int[]{nx, wy, nz, newLevel});
                          queue.add(new long[]{nx, wy, nz, newLevel});
                      } else if (nb == BlockType.WATER || nb == BlockType.WATER_FLOW) {
                          visited.add(pk);
                      }
                  }
              }
          }

          // Apply new WATER_FLOW blocks via direct chunk access (avoids per-block light propagation)
          for (int[] entry : toSet) {
              int wx = entry[0], wy = entry[1], wz = entry[2], lvl = entry[3];
              int cx = Math.floorDiv(wx, Chunk.SIZE_X);
              int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
              Chunk c = world.getChunkIfExists(cx, cz);
              if (c == null) continue;
              int lx = Math.floorMod(wx, Chunk.SIZE_X);
              int lz = Math.floorMod(wz, Chunk.SIZE_Z);
              c.set(lx, wy, lz, BlockType.WATER_FLOW);
              c.setMeta(lx, wy, lz, (byte) lvl);
          }

          // Remove stale WATER_FLOW cells not reached by BFS
          for (Chunk chunk : world.getLoadedChunks()) {
              int bx = chunk.cx * Chunk.SIZE_X;
              int bz = chunk.cz * Chunk.SIZE_Z;
              for (int lx = 0; lx < Chunk.SIZE_X; lx++)
                  for (int y = 0; y < Chunk.SIZE_Y; y++)
                      for (int lz = 0; lz < Chunk.SIZE_Z; lz++)
                          if (chunk.get(lx, y, lz) == BlockType.WATER_FLOW) {
                              long pk = pack(bx + lx, y, bz + lz);
                              if (!visited.contains(pk)) {
                                  chunk.set(lx, y, lz, BlockType.AIR);
                                  chunk.setMeta(lx, y, lz, (byte) 0);
                              }
                          }
          }
      }

      private static long pack(int wx, int wy, int wz) {
          // 22 bits x, 8 bits y, 22 bits z — safe for ±2M block radius
          return ((long)(wx & 0x3FFFFF) << 30) | ((long)(wy & 0xFF) << 22) | (long)(wz & 0x3FFFFF);
      }
  }
  ```

- [ ] **Step 4.2 — Wire tick into Game**

  In `Game.java`, add a timer field near the other timers (after `torchParticleTimer`):

  ```java
  private float waterTickTimer = 0.5f;
  ```

  In `updatePlaying(float dt)`, add after the torch particle block:

  ```java
  waterTickTimer -= dt;
  if (waterTickTimer <= 0f) {
      waterTickTimer = 0.5f;
      WaterSimulator.tick(world);
  }
  ```

  Add `import com.mineclone.world.WaterSimulator;` at the top of `Game.java`.

- [ ] **Step 4.3 — Verify: water flows**

  ```powershell
  .\run.ps1
  ```

  Start game. Place a WATER block (slot 7 = WATER by default) on top of a flat surface. Wait 1–2 seconds. Expected: water spreads outward in all directions, up to 7 blocks wide, flowing downward first if there's a ledge. Removing the source block causes the flow to disappear within 1 second.

  If WATER is not in the hotbar, add it temporarily to `Game.java`'s `hotbar` array for testing.

- [ ] **Step 4.4 — Commit**

  ```
  git add src/main/java/com/mineclone/world/WaterSimulator.java
  git add src/main/java/com/mineclone/game/Game.java
  git commit -m "feat: BFS water simulation tick, spreads up to 7 blocks from sources"
  ```

---

## Task 5: Variable-Height Water Rendering

**Files:**
- Modify: `src/main/java/com/mineclone/world/ChunkMesher.java`

- [ ] **Step 5.1 — Add emitWaterBlock method**

  In `ChunkMesher.java`, add the following method before `emitFace`. It manually emits up to 5 visible faces of a water block with a height proportional to the flow level.

  ```java
  /**
   * Emits water block geometry. Top face Y is reduced for WATER_FLOW levels 1-7.
   * Level 0 (WATER source) = full height 1.0. Level 7 = 1/8 height.
   * No AO for water — it's transparent and lit uniformly.
   */
  private void emitWaterBlock(Chunk chunk,
                               List<Float> pos, List<Float> uvs,
                               List<Float> light, List<Float> blockLightList,
                               List<Integer> idx,
                               int x, int y, int z, int baseX, int baseZ,
                               BlockType b) {
      byte meta = chunk.getMeta(x, y, z);
      int level = meta & 0x0F;
      float topY = (level == 0) ? 1.0f : (8 - level) / 8.0f;

      float[] uv = TextureAtlas.uv(b.sideTile);
      float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];

      float skyRaw = skyAt(chunk, x, y + 1, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
      float blRaw  = blockLightAt(chunk, x, y, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
      float lv     = Math.max(0.6f * skyRaw, blRaw);

      // Helper: emit one quad (4 vertices, 2 triangles)
      // corners: 4×{fx,fy,fz}, uvQuad: 4×{u,v}
      // (inlined here to avoid a separate method signature)

      // +Y top face — only if block above is not water (show surface)
      BlockType above = chunk.inBounds(x, y + 1, z)
              ? chunk.get(x, y + 1, z)
              : world.getBlock(baseX + x, y + 1, baseZ + z);
      if (above != BlockType.WATER && above != BlockType.WATER_FLOW) {
          float[][] corners  = {{x,y+topY,z+1},{x+1,y+topY,z+1},{x+1,y+topY,z},{x,y+topY,z}};
          float[][] uvCorner = {{u0,v1},{u1,v1},{u1,v0},{u0,v0}};
          addWaterQuad(pos, uvs, light, blockLightList, idx, corners, uvCorner, lv * 1.0f, blRaw);
      }

      // -Y bottom face — only if below is AIR (exposed bottom)
      BlockType below = (y > 0 && chunk.inBounds(x, y - 1, z))
              ? chunk.get(x, y - 1, z)
              : world.getBlock(baseX + x, y - 1, baseZ + z);
      if (below == BlockType.AIR) {
          float[][] corners  = {{x,y,z},{x+1,y,z},{x+1,y,z+1},{x,y,z+1}};
          float[][] uvCorner = {{u0,v0},{u1,v0},{u1,v1},{u0,v1}};
          addWaterQuad(pos, uvs, light, blockLightList, idx, corners, uvCorner, lv * 0.65f, blRaw);
      }

      // Side faces: only if neighbour is AIR (or non-water transparent)
      int[][] sideDirs = {{0,0,1},{0,0,-1},{1,0,0},{-1,0,0}};
      float[][][] sideCornersBase = {
          // +Z south
          {{x,y,z+1},{x+1,y,z+1},{x+1,y+topY,z+1},{x,y+topY,z+1}},
          // -Z north
          {{x+1,y,z},{x,y,z},{x,y+topY,z},{x+1,y+topY,z}},
          // +X east
          {{x+1,y,z+1},{x+1,y,z},{x+1,y+topY,z},{x+1,y+topY,z+1}},
          // -X west
          {{x,y,z},{x,y,z+1},{x,y+topY,z+1},{x,y+topY,z}},
      };
      float[] sideLights = {0.88f, 0.88f, 0.80f, 0.80f};
      float[][] uvSide   = {{u0,v1},{u1,v1},{u1,v0},{u0,v0}};

      for (int f = 0; f < 4; f++) {
          int nx = x + sideDirs[f][0], nz = z + sideDirs[f][2];
          BlockType nb = chunk.inBounds(nx, y, nz)
                  ? chunk.get(nx, y, nz)
                  : world.getBlock(baseX + nx, y, baseZ + nz);
          if (nb == BlockType.AIR || (nb.transparent && nb != BlockType.WATER && nb != BlockType.WATER_FLOW)) {
              // Clip side UV height to topY
              float vSide0 = v1 - (v1 - v0) * topY;
              float[][] uvS = {{u0,v1},{u1,v1},{u1,vSide0},{u0,vSide0}};
              addWaterQuad(pos, uvs, light, blockLightList, idx,
                      sideCornersBase[f], uvS, lv * sideLights[f], blRaw);
          }
      }
  }

  private void addWaterQuad(List<Float> pos, List<Float> uvs,
                             List<Float> light, List<Float> blockLightList,
                             List<Integer> idx,
                             float[][] corners, float[][] uvCorner,
                             float lv, float blVal) {
      int base = pos.size() / 3;
      for (int i = 0; i < 4; i++) {
          pos.add(corners[i][0]); pos.add(corners[i][1]); pos.add(corners[i][2]);
          uvs.add(uvCorner[i][0]); uvs.add(uvCorner[i][1]);
          light.add(lv);
          blockLightList.add(blVal);
      }
      idx.add(base); idx.add(base + 1); idx.add(base + 2);
      idx.add(base); idx.add(base + 2); idx.add(base + 3);
  }
  ```

- [ ] **Step 5.2 — Wire emitWaterBlock into buildData loop**

  In `buildData()`, just before the `for (int f = 0; f < 6; f++)` face loop, add water handling:

  ```java
  // After the TORCH special-case block (which does continue):
  if (b == BlockType.WATER || b == BlockType.WATER_FLOW) {
      emitWaterBlock(chunk, positions, uvs, light, blockLightList, indices,
              x, y, z, baseX, baseZ, b);
      continue;
  }
  ```

- [ ] **Step 5.3 — Verify: water levels render correctly**

  ```powershell
  .\run.ps1
  ```

  Place a WATER source on a flat surface. Expected: water surface is at full height for the source block, progressively shorter for each step away (7 blocks from source = 1/8 height). The transition is visually clear.

- [ ] **Step 5.4 — Commit**

  ```
  git add src/main/java/com/mineclone/world/ChunkMesher.java
  git commit -m "feat: variable-height water rendering based on flow level in block meta"
  ```

---

## Task 6: Swimming Physics

**Files:**
- Modify: `src/main/java/com/mineclone/game/Player.java`

- [ ] **Step 6.1 — Add swimming state and sound timer**

  In `Player.java`, add public fields (near the other public fields):

  ```java
  public boolean inWater = false;
  public float swimSoundTimer = 0f;
  ```

- [ ] **Step 6.2 — Add water detection helper**

  Add a private method in `Player.java`:

  ```java
  private boolean touchingWater(World world) {
      float hw = WIDTH / 2f;
      int x0 = (int) Math.floor(position.x - hw);
      int x1 = (int) Math.floor(position.x + hw - 0.01f);
      int y0 = (int) Math.floor(position.y + 0.1f); // check mid-body
      int y1 = (int) Math.floor(position.y + HEIGHT * 0.75f);
      int z0 = (int) Math.floor(position.z - hw);
      int z1 = (int) Math.floor(position.z + hw - 0.01f);
      for (int x = x0; x <= x1; x++)
          for (int y = y0; y <= y1; y++)
              for (int z = z0; z <= z1; z++) {
                  BlockType b = world.getBlock(x, y, z);
                  if (b == BlockType.WATER || b == BlockType.WATER_FLOW) return true;
              }
      return false;
  }
  ```

- [ ] **Step 6.3 — Apply water physics in update()**

  Replace the gravity/jump section of `Player.update()`. The current block is:

  ```java
  if (flying) {
      velocity.y = 0;
      if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))      velocity.y =  speed;
      if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)) velocity.y = -speed;
  } else {
      velocity.y += GRAVITY * dt;
      if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) && onGround) {
          velocity.y = JUMP_VELOCITY;
          onGround = false;
      }
  }
  ```

  Replace with:

  ```java
  inWater = !flying && touchingWater(world);

  if (flying) {
      velocity.y = 0;
      if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))      velocity.y =  speed;
      if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)) velocity.y = -speed;
  } else if (inWater) {
      // Water physics: buoyancy + paddle controls
      velocity.y += GRAVITY * 0.12f * dt;  // weak downward pull
      velocity.y = Math.max(velocity.y, -3f);
      velocity.x *= (float) Math.pow(0.75, dt * 10);
      velocity.z *= (float) Math.pow(0.75, dt * 10);
      if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))
          velocity.y = 3.5f;
      if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT))
          velocity.y = -3.5f;
      onGround = false;
      // Swim sound timer (decremented by caller via swimSoundTimer)
      swimSoundTimer -= dt;
  } else {
      velocity.y += GRAVITY * dt;
      if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) && onGround) {
          velocity.y = JUMP_VELOCITY;
          onGround = false;
      }
  }
  ```

- [ ] **Step 6.4 — Wire swim sounds in Game**

  In `Game.java`, in `updatePlaying(float dt)`, after the `player.update(dt, world, input)` call:

  ```java
  if (player.inWater && player.swimSoundTimer <= 0f) {
      player.swimSoundTimer = 0.8f;
      sound.playOneOf(sounds.waterSwim(), 0.4f, 0.9f + 0.2f * (float) Math.random());
  }
  ```

  Also add splash sound on water entry. Add a field in `Game.java`:

  ```java
  private boolean wasInWater = false;
  ```

  And in `updatePlaying(float dt)` after the swim sound block:

  ```java
  if (player.inWater && !wasInWater) {
      sound.playOneOf(sounds.waterSplash(), 0.8f, 0.9f + 0.1f * (float) Math.random());
  }
  wasInWater = player.inWater;
  ```

- [ ] **Step 6.5 — Verify: swimming**

  ```powershell
  .\run.ps1
  ```

  Walk into the ocean (or a placed WATER source block). Expected:
  - Player bobs in water (doesn't sink quickly)
  - SPACE key causes upward movement (swimming up)
  - SHIFT causes downward movement
  - Swim sounds play every ~0.8 s
  - Splash sound on entry
  - Player can exit water by swimming to surface and pressing SPACE

- [ ] **Step 6.6 — Commit**

  ```
  git add src/main/java/com/mineclone/game/Player.java
  git add src/main/java/com/mineclone/game/Game.java
  git commit -m "feat: swimming physics in water with buoyancy, paddle controls, and sounds"
  ```

---

## Task 7: Door Mechanics

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java`
- Modify: `src/main/java/com/mineclone/world/ChunkMesher.java`

- [ ] **Step 7.1 — Door placement facing from camera**

  In `Game.java`, `handleInteraction()`, find the right-click block placement section:

  ```java
  if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
      int px = lastHit.x + lastHit.nx;
      int py = lastHit.y + lastHit.ny;
      int pz = lastHit.z + lastHit.nz;
      if (!playerOccupies(px, py, pz)) {
          sound.playOneOf(sounds.place(currentBlock()), 0.8f, 0.85f + 0.2f * (float) Math.random());
          world.setBlock(px, py, pz, currentBlock());
      }
  }
  ```

  Replace with:

  ```java
  if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
      BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
      // Toggle door open/close
      if (target == BlockType.DOOR_CLOSED || target == BlockType.DOOR_OPEN) {
          byte m = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
          BlockType next = (target == BlockType.DOOR_CLOSED) ? BlockType.DOOR_OPEN : BlockType.DOOR_CLOSED;
          world.setBlock(lastHit.x, lastHit.y, lastHit.z, next, m);
          sound.playOneOf(sounds.doorToggle(), 0.8f, 0.95f + 0.1f * (float) Math.random());
      } else {
          int px = lastHit.x + lastHit.nx;
          int py = lastHit.y + lastHit.ny;
          int pz = lastHit.z + lastHit.nz;
          if (!playerOccupies(px, py, pz)) {
              BlockType placing = currentBlock();
              byte meta = 0;
              if (placing == BlockType.DOOR_CLOSED || placing == BlockType.STAIRS) {
                  meta = facingFromCamera();
              }
              sound.playOneOf(sounds.place(placing), 0.8f, 0.85f + 0.2f * (float) Math.random());
              world.setBlock(px, py, pz, placing, meta);
          }
      }
  }
  ```

  Add the helper method `facingFromCamera()` in `Game.java`:

  ```java
  /** Derives 0-3 facing from camera forward vector. 0=+Z, 1=+X, 2=-Z, 3=-X. */
  private byte facingFromCamera() {
      Vector3f fwd = player.camera.forward();
      float absX = Math.abs(fwd.x), absZ = Math.abs(fwd.z);
      if (absZ >= absX) return (byte)(fwd.z > 0 ? 0 : 2);
      return (byte)(fwd.x > 0 ? 1 : 3);
  }
  ```

- [ ] **Step 7.2 — Door rendering in ChunkMesher**

  Add `emitDoor()` in `ChunkMesher.java` before `emitFace`:

  ```java
  /**
   * Door: a thin slab (3/16 thick) flush to one wall when closed,
   * rotated 90° when open. Facing: 0=+Z wall, 1=+X wall, 2=-Z wall, 3=-X wall.
   */
  private void emitDoor(Chunk chunk,
                         List<Float> pos, List<Float> uvs,
                         List<Float> light, List<Float> blockLightList,
                         List<Integer> idx,
                         int x, int y, int z, int baseX, int baseZ,
                         boolean open) {
      byte meta   = chunk.getMeta(x, y, z);
      int  facing = meta & 0x3;

      float skyRaw = skyAt(chunk, x, y + 1, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
      float blRaw  = blockLightAt(chunk, x, y, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
      float lv = Math.max(0.7f * skyRaw, blRaw);

      float T = 3f / 16f; // thickness

      // Closed bounding box per facing (in local block coords):
      // facing 0 = attached to +Z wall
      // facing 1 = attached to +X wall
      // facing 2 = attached to -Z wall
      // facing 3 = attached to -X wall
      float x0, x1, z0, z1;
      if (!open) {
          switch (facing) {
              case 0 -> { x0=0; x1=1; z0=1-T; z1=1; }
              case 1 -> { x0=1-T; x1=1; z0=0; z1=1; }
              case 2 -> { x0=0; x1=1; z0=0; z1=T; }
              default -> { x0=0; x1=T; z0=0; z1=1; }
          }
      } else {
          // Open: rotate 90° around the hinge, offset to the right side of the block
          switch (facing) {
              case 0 -> { x0=1-T; x1=1; z0=0; z1=1; }
              case 1 -> { x0=0; x1=1; z0=0; z1=T; }
              case 2 -> { x0=0; x1=T; z0=0; z1=1; }
              default -> { x0=0; x1=1; z0=1-T; z1=1; }
          }
      }

      float[] uv = TextureAtlas.uv(BlockType.DOOR_CLOSED.sideTile);
      float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
      float[][] uvQ = {{u0,v1},{u1,v1},{u1,v0},{u0,v0}};

      // Emit 6 box faces for the slab [x+x0..x+x1, y..y+1, z+z0..z+z1]
      float[][] faces = {
          // +Y top
          {x+x0,y+1,z+z1, x+x1,y+1,z+z1, x+x1,y+1,z+z0, x+x0,y+1,z+z0},
          // -Y bottom
          {x+x0,y,z+z0, x+x1,y,z+z0, x+x1,y,z+z1, x+x0,y,z+z1},
          // +Z
          {x+x0,y,z+z1, x+x1,y,z+z1, x+x1,y+1,z+z1, x+x0,y+1,z+z1},
          // -Z
          {x+x1,y,z+z0, x+x0,y,z+z0, x+x0,y+1,z+z0, x+x1,y+1,z+z0},
          // +X
          {x+x1,y,z+z1, x+x1,y,z+z0, x+x1,y+1,z+z0, x+x1,y+1,z+z1},
          // -X
          {x+x0,y,z+z0, x+x0,y,z+z1, x+x0,y+1,z+z1, x+x0,y+1,z+z0},
      };
      float[] faceLight = {1.0f, 0.65f, 0.88f, 0.88f, 0.80f, 0.80f};

      for (int f = 0; f < 6; f++) {
          float[] fc = faces[f];
          int base = pos.size() / 3;
          for (int i = 0; i < 4; i++) {
              pos.add(fc[i * 3]); pos.add(fc[i * 3 + 1]); pos.add(fc[i * 3 + 2]);
              uvs.add(uvQ[i][0]); uvs.add(uvQ[i][1]);
              light.add(lv * faceLight[f]);
              blockLightList.add(blRaw);
          }
          idx.add(base); idx.add(base + 1); idx.add(base + 2);
          idx.add(base); idx.add(base + 2); idx.add(base + 3);
      }
  }
  ```

  Wire it into `buildData()`. After the TORCH check and the new WATER check, add:

  ```java
  if (b == BlockType.DOOR_CLOSED || b == BlockType.DOOR_OPEN) {
      emitDoor(chunk, positions, uvs, light, blockLightList, indices,
               x, y, z, baseX, baseZ, b == BlockType.DOOR_OPEN);
      continue;
  }
  ```

- [ ] **Step 7.3 — Verify: door opens and closes**

  ```powershell
  .\run.ps1
  ```

  Add `BlockType.DOOR_CLOSED` to the hotbar in `Game.java` temporarily. Place a door (right-click on ground). Expected:
  - Door appears as a thin slab against a wall
  - Right-clicking the door toggles it open/closed (door rotates 90°)
  - Door sound plays on toggle
  - Closed door blocks passage; open door does not

- [ ] **Step 7.4 — Commit**

  ```
  git add src/main/java/com/mineclone/game/Game.java
  git add src/main/java/com/mineclone/world/ChunkMesher.java
  git commit -m "feat: door placement with camera-facing meta, open/close toggle, door rendering"
  ```

---

## Task 8: Stairs Collision

**Files:**
- Modify: `src/main/java/com/mineclone/game/Player.java`

- [ ] **Step 8.1 — Add resolveStairs helper**

  Add this private method in `Player.java`:

  ```java
  /**
   * Resolves collision between the player and a stair block.
   * Stair facing (meta bits 1-0): 0=step toward -Z, 1=step toward +X,
   *                                2=step toward +Z, 3=step toward -X.
   * Bottom slab [y, y+0.5] is always solid.
   * Top step occupies the "back" half of the block at [y+0.5, y+1].
   */
  private void resolveStairs(World world, int bx, int by, int bz,
                              float dx, float dy, float dz, float hw) {
      byte meta   = world.getBlockMeta(bx, by, bz);
      int  facing = meta & 0x3;

      float relX = position.x - bx;
      float relZ = position.z - bz;

      // Determine if the player's XZ centre is over the raised step zone
      boolean overStep = switch (facing) {
          case 0 -> relZ < 0.5f;   // step at -Z half
          case 1 -> relX >= 0.5f;  // step at +X half
          case 2 -> relZ >= 0.5f;  // step at +Z half
          default -> relX < 0.5f;  // step at -X half (facing=3)
      };

      float topSurface = overStep ? by + 1.0f : by + 0.5f;

      if (dy < 0) {
          // Falling: land on whichever surface is under the player's foot
          if (position.y >= topSurface - 1e-3f) {
              position.y = topSurface + 1e-4f;
              velocity.y = 0;
              onGround   = true;
          }
      } else if (dy > 0) {
          // Rising into step ceiling
          if (overStep && position.y + HEIGHT >= by + 1.0f - 1e-3f
                       && position.y + HEIGHT <= by + 1.0f + HEIGHT) {
              position.y = by + 1.0f - HEIGHT - 1e-4f;
              velocity.y = 0;
          }
      } else {
          // Horizontal: check if player overlaps the solid zone
          if (position.y < topSurface && position.y + HEIGHT > by) {
              float stepDelta = topSurface - position.y;
              if (stepDelta <= 0.55f) {
                  // Auto-step up onto the surface
                  position.y = topSurface + 1e-4f;
                  onGround   = true;
              } else {
                  // Too high to step over — push back
                  if (dx > 0) { position.x = bx - hw - 1e-4f; velocity.x = 0; }
                  else if (dx < 0) { position.x = bx + 1 + hw + 1e-4f; velocity.x = 0; }
                  if (dz > 0) { position.z = bz - hw - 1e-4f; velocity.z = 0; }
                  else if (dz < 0) { position.z = bz + 1 + hw + 1e-4f; velocity.z = 0; }
              }
          }
      }
  }
  ```

- [ ] **Step 8.2 — Integrate resolveStairs into moveAxis**

  In `moveAxis`, inside the block loop, replace the `if (!b.solid) continue;` check with:

  ```java
  if (!b.solid) continue;
  if (b == BlockType.STAIRS) {
      resolveStairs(world, x, y, z, dx, dy, dz, hw);
      minX = position.x - hw; maxX = position.x + hw;
      minY = position.y;      maxY = position.y + HEIGHT;
      minZ = position.z - hw; maxZ = position.z + hw;
      continue;
  }
  // ... existing solid block resolution unchanged below
  ```

- [ ] **Step 8.3 — Fix ground check for stair surfaces**

  At the end of `moveAxis`, the existing ground check looks for solid blocks directly below:

  ```java
  if (dy == 0 && !onGround) {
      int yb = (int) Math.floor(position.y - 1e-3);
      ...
      if (world.getBlock(xi, yb, zi).solid) { onGround = true; break outer; }
  }
  ```

  Extend it to also detect stair surfaces:

  ```java
  if (dy == 0 && !onGround) {
      int hw2 = (int)(WIDTH / 2f * 1000); // not used, keep existing logic
      float py = position.y;
      int yb = (int) Math.floor(py - 1e-3);
      int xa = (int) Math.floor(position.x - hw + 1e-3);
      int xb = (int) Math.floor(position.x + hw - 1e-3);
      int za = (int) Math.floor(position.z - hw + 1e-3);
      int zb = (int) Math.floor(position.z + hw - 1e-3);
      outer:
      for (int xi = xa; xi <= xb; xi++)
          for (int zi = za; zi <= zb; zi++) {
              BlockType bl = world.getBlock(xi, yb, zi);
              if (bl == BlockType.STAIRS) {
                  byte m = world.getBlockMeta(xi, yb, zi);
                  int f2 = m & 0x3;
                  float rx = position.x - xi, rz = position.z - zi;
                  boolean step = switch (f2) {
                      case 0 -> rz < 0.5f; case 1 -> rx >= 0.5f;
                      case 2 -> rz >= 0.5f; default -> rx < 0.5f;
                  };
                  float top = step ? yb + 1.0f : yb + 0.5f;
                  if (Math.abs(py - top) < 0.1f) { onGround = true; break outer; }
              } else if (bl.solid) {
                  onGround = true; break outer;
              }
          }
  }
  ```

- [ ] **Step 8.4 — Add `import` for BlockType in Player.java**

  Ensure `import com.mineclone.world.BlockType;` is at the top of `Player.java`.

- [ ] **Step 8.5 — Compile and verify**

  ```powershell
  .\run.ps1
  ```

  Compile must succeed with 0 errors.

- [ ] **Step 8.6 — Commit**

  ```
  git add src/main/java/com/mineclone/game/Player.java
  git commit -m "feat: stair AABB collision with auto-step and half-block landing"
  ```

---

## Task 9: Stairs Rendering

**Files:**
- Modify: `src/main/java/com/mineclone/world/ChunkMesher.java`

- [ ] **Step 9.1 — Add emitStairs method**

  Add before `emitFace` in `ChunkMesher.java`:

  ```java
  /**
   * Stairs: a bottom slab (full XZ, Y 0→0.5) plus a step on the "back" half
   * (XZ half depending on facing, Y 0.5→1.0).
   * Facing bits: 0=step at -Z half, 1=step at +X half, 2=step at +Z half, 3=step at -X half.
   */
  private void emitStairs(Chunk chunk,
                           List<Float> pos, List<Float> uvs,
                           List<Float> light, List<Float> blockLightList,
                           List<Integer> idx,
                           int x, int y, int z, int baseX, int baseZ) {
      byte meta   = chunk.getMeta(x, y, z);
      int  facing = meta & 0x3;

      float skyRaw = skyAt(chunk, x, y + 1, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
      float blRaw  = blockLightAt(chunk, x, y, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
      float lv = Math.max(0.7f * skyRaw, blRaw);

      int tile = BlockType.STAIRS.sideTile; // planks tile
      float[] uv = TextureAtlas.uv(tile);
      float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
      float um = (u0 + u1) / 2f, vm = (v0 + v1) / 2f; // UV midpoint for half faces

      // Step XZ bounds based on facing
      float sx0, sx1, sz0, sz1;  // step horizontal extent
      switch (facing) {
          case 0 -> { sx0=0; sx1=1; sz0=0; sz1=0.5f; }  // step at -Z half
          case 1 -> { sx0=0.5f; sx1=1; sz0=0; sz1=1; }  // step at +X half
          case 2 -> { sx0=0; sx1=1; sz0=0.5f; sz1=1; }  // step at +Z half
          default -> { sx0=0; sx1=0.5f; sz0=0; sz1=1; } // step at -X half
      }
      // Slab XZ = full (0..1), open part = complement of step
      float ox0 = 1-sx1, ox1 = 1-sx0, oz0 = 1-sz1, oz1 = 1-sz0;
      // "Open" XZ: the non-step half at top Y
      // (used for the top face of the slab half not covered by the step)
      // For facing=0: open is z=0.5..1.0
      // etc. — computed as complement
      switch (facing) {
          case 0 -> { ox0=0; ox1=1; oz0=0.5f; oz1=1; }
          case 1 -> { ox0=0; ox1=0.5f; oz0=0; oz1=1; }
          case 2 -> { ox0=0; ox1=1; oz0=0; oz1=0.5f; }
          default -> { ox0=0.5f; ox1=1; oz0=0; oz1=1; }
      }

      // Emit bottom slab (6 faces, 0..1 XZ, 0..0.5 Y)
      emitBox(pos, uvs, light, blockLightList, idx,
              x, y, z, 0, 0, 0, 1, 0.5f, 1, u0, v0, u1, v1, lv, blRaw);

      // Emit top step (6 faces, step XZ, 0.5..1.0 Y)
      emitBox(pos, uvs, light, blockLightList, idx,
              x, y, z, sx0, 0.5f, sz0, sx1, 1.0f, sz1, u0, v0, u1, v1, lv, blRaw);
  }

  /**
   * Emits all 6 faces of an axis-aligned box [bx0..bx1] x [by0..by1] x [bz0..bz1]
   * in local block space. Coordinates are offsets within the block cell (0..1 range).
   */
  private void emitBox(List<Float> pos, List<Float> uvs,
                        List<Float> light, List<Float> blockLightList, List<Integer> idx,
                        int x, int y, int z,
                        float bx0, float by0, float bz0,
                        float bx1, float by1, float bz1,
                        float u0, float v0, float u1, float v1,
                        float lv, float blVal) {
      float[][] uvQ = {{u0,v1},{u1,v1},{u1,v0},{u0,v0}};
      float[][][] faces = {
          // +Y top
          {{x+bx0,y+by1,z+bz1},{x+bx1,y+by1,z+bz1},{x+bx1,y+by1,z+bz0},{x+bx0,y+by1,z+bz0}},
          // -Y bottom
          {{x+bx0,y+by0,z+bz0},{x+bx1,y+by0,z+bz0},{x+bx1,y+by0,z+bz1},{x+bx0,y+by0,z+bz1}},
          // +Z south
          {{x+bx0,y+by0,z+bz1},{x+bx1,y+by0,z+bz1},{x+bx1,y+by1,z+bz1},{x+bx0,y+by1,z+bz1}},
          // -Z north
          {{x+bx1,y+by0,z+bz0},{x+bx0,y+by0,z+bz0},{x+bx0,y+by1,z+bz0},{x+bx1,y+by1,z+bz0}},
          // +X east
          {{x+bx1,y+by0,z+bz1},{x+bx1,y+by0,z+bz0},{x+bx1,y+by1,z+bz0},{x+bx1,y+by1,z+bz1}},
          // -X west
          {{x+bx0,y+by0,z+bz0},{x+bx0,y+by0,z+bz1},{x+bx0,y+by1,z+bz1},{x+bx0,y+by1,z+bz0}},
      };
      float[] faceLight = {1.0f, 0.65f, 0.88f, 0.88f, 0.80f, 0.80f};

      for (int f = 0; f < 6; f++) {
          float[][] fc = faces[f];
          int base = pos.size() / 3;
          for (int i = 0; i < 4; i++) {
              pos.add(fc[i][0]); pos.add(fc[i][1]); pos.add(fc[i][2]);
              uvs.add(uvQ[i][0]); uvs.add(uvQ[i][1]);
              light.add(lv * faceLight[f]);
              blockLightList.add(blVal);
          }
          idx.add(base); idx.add(base + 1); idx.add(base + 2);
          idx.add(base); idx.add(base + 2); idx.add(base + 3);
      }
  }
  ```

- [ ] **Step 9.2 — Wire emitStairs into buildData**

  After the DOOR check in `buildData()`, add:

  ```java
  if (b == BlockType.STAIRS) {
      emitStairs(chunk, positions, uvs, light, blockLightList, indices,
                 x, y, z, baseX, baseZ);
      continue;
  }
  ```

- [ ] **Step 9.3 — Verify: stairs render and walk correctly**

  ```powershell
  .\run.ps1
  ```

  Add `BlockType.STAIRS` to hotbar. Place a stair block. Expected:
  - Stair has a visible lower slab and an upper step
  - Walking into the low side auto-steps the player up
  - Walking over the step side lands at full block height
  - The stair uses the planks texture

- [ ] **Step 9.4 — Commit**

  ```
  git add src/main/java/com/mineclone/world/ChunkMesher.java
  git commit -m "feat: directional stair rendering using emitBox helper (slab + step geometry)"
  ```

---

## Task 10: Creative Inventory Menu

**Files:**
- Modify: `src/main/java/com/mineclone/game/Hud.java`
- Modify: `src/main/java/com/mineclone/game/Game.java`

- [ ] **Step 10.1 — Add CREATIVE_MENU state to Game**

  In `Game.java`, in the `State` enum, add the new state:

  ```java
  private enum State { MENU, PLAYING, PAUSED, CREATIVE_MENU }
  ```

  In `updatePlaying(float dt)`, add E-key handling before the ESC check:

  ```java
  if (input.keyPressed(GLFW.GLFW_KEY_E)) {
      state = State.CREATIVE_MENU;
      input.grabCursor(false);
      return;
  }
  ```

  In `updateMenu` / the main switch, add a CREATIVE\_MENU case — it should do nothing (world frozen):

  ```java
  case CREATIVE_MENU -> {} // world paused; input handled in drawUi
  ```

  In `drawUi()`, inside the `switch (state)` add a CREATIVE\_MENU case:

  ```java
  case CREATIVE_MENU -> {
      // Draw game behind (already rendered)
      hud.drawHotbar(w, h, hotbar, selectedSlot);
      boolean clicked = input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
      double mx = input.getCursorX(), my = input.getCursorY();
      BlockType picked = hud.drawCreativeMenu(w, h, mx, my, clicked, hotbar, selectedSlot);
      if (picked != null) {
          hotbar[selectedSlot] = picked;
          sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
      }
      if (input.keyPressed(GLFW.GLFW_KEY_E) || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
          state = State.PLAYING;
          input.grabCursor(true);
      }
  }
  ```

  Also make sure chunk streaming continues during CREATIVE_MENU. In the main game loop switch, add:

  ```java
  case CREATIVE_MENU -> {
      ensureChunksLoaded();
      updateDirtyMeshes();
  }
  ```

  (Replace the empty `case CREATIVE_MENU -> {}` in `updateMenu` section above with this.)

- [ ] **Step 10.2 — Implement drawCreativeMenu in Hud**

  Add the following method to `Hud.java`:

  ```java
  /**
   * Fullscreen creative inventory grid.
   * Returns the BlockType the user clicked, or null if none.
   * Excluded from the grid: AIR, WATER_FLOW, DOOR_OPEN (internal/implicit types).
   */
  public BlockType drawCreativeMenu(int sw, int sh,
                                    double mx, double my, boolean clicked,
                                    BlockType[] hotbar, int selectedSlot) {
      // Blocks to show (skip internal types)
      java.util.List<BlockType> items = new java.util.ArrayList<>();
      for (BlockType b : BlockType.VALUES) {
          if (b == BlockType.AIR || b == BlockType.WATER_FLOW || b == BlockType.DOOR_OPEN) continue;
          items.add(b);
      }

      final int COLS    = 6;
      final float SLOT  = 52f, GAP = 6f;
      final float gridW = COLS * SLOT + (COLS - 1) * GAP;
      int   rows  = (items.size() + COLS - 1) / COLS;
      float gridH = rows * SLOT + (rows - 1) * GAP;

      float panelW = gridW + 40f;
      float panelH = gridH + 120f;
      float panelX = sw / 2f - panelW / 2f;
      float panelY = sh / 2f - panelH / 2f;
      float gridX  = panelX + 20f;
      float gridY  = panelY + 70f;

      ui.begin(sw, sh);
      // Dim overlay
      ui.quad(0, 0, sw, sh, 0f, 0f, 0f, 0.7f);
      // Panel background
      ui.quad(panelX, panelY, panelW, panelH, 0.10f, 0.10f, 0.13f, 0.96f);
      ui.quad(panelX, panelY, panelW, 2f, 0.3f, 0.3f, 0.35f, 1f); // top edge

      BlockType result = null;

      for (int i = 0; i < items.size(); i++) {
          BlockType b = items.get(i);
          int col = i % COLS, row = i / COLS;
          float sx = gridX + col * (SLOT + GAP);
          float sy = gridY + row * (SLOT + GAP);
          boolean hover = mx >= sx && mx <= sx + SLOT && my >= sy && my <= sy + SLOT;

          // Slot background
          ui.quad(sx, sy, SLOT, SLOT, hover ? 0.30f : 0.18f, hover ? 0.32f : 0.18f, hover ? 0.38f : 0.22f, 0.9f);

          // Block icon
          int tile = (b == BlockType.GRASS) ? b.topTile : b.sideTile;
          if (tile >= 0) {
              float[] uvs = TextureAtlas.uv(tile);
              float inset = 7f;
              ui.texQuad(sx + inset, sy + inset, SLOT - 2 * inset, SLOT - 2 * inset,
                         atlas.getTextureId(), uvs[0], uvs[1], uvs[2], uvs[3], 1f, 1f, 1f, 1f);
          }

          if (hover && clicked) result = b;
      }

      // Hotbar strip at bottom of panel
      float hbY = panelY + panelH - 72f;
      float hbSlot = 44f, hbGap = 4f;
      float hbW = hotbar.length * hbSlot + (hotbar.length - 1) * hbGap;
      float hbX = sw / 2f - hbW / 2f;
      ui.quad(hbX - 8f, hbY - 4f, hbW + 16f, hbSlot + 8f, 0f, 0f, 0f, 0.5f);
      for (int i = 0; i < hotbar.length; i++) {
          float sx = hbX + i * (hbSlot + hbGap);
          boolean sel = i == selectedSlot;
          ui.quad(sx, hbY, hbSlot, hbSlot, sel ? 0.35f : 0.18f, sel ? 0.37f : 0.18f, sel ? 0.45f : 0.22f, 0.9f);
          if (hotbar[i] != null && hotbar[i] != BlockType.AIR) {
              int tile = (hotbar[i] == BlockType.GRASS) ? hotbar[i].topTile : hotbar[i].sideTile;
              float[] uvs = TextureAtlas.uv(tile);
              float inset = 6f;
              ui.texQuad(sx + inset, hbY + inset, hbSlot - 2 * inset, hbSlot - 2 * inset,
                         atlas.getTextureId(), uvs[0], uvs[1], uvs[2], uvs[3], 1f, 1f, 1f, 1f);
          }
          if (sel) {
              float t = 2f;
              ui.quad(sx - t, hbY - t, hbSlot + 2*t, t, 1f,1f,1f,0.9f);
              ui.quad(sx - t, hbY + hbSlot, hbSlot + 2*t, t, 1f,1f,1f,0.9f);
              ui.quad(sx - t, hbY, t, hbSlot, 1f,1f,1f,0.9f);
              ui.quad(sx + hbSlot, hbY, t, hbSlot, 1f,1f,1f,0.9f);
          }
      }
      ui.end();

      // Text: title
      String title = "CREATIVE INVENTORY";
      float tw = font.textWidth(title);
      text.drawShadowed(font, title, sw / 2f - tw / 2f, panelY + 30f, sw, sh, 1f, 0.95f, 0.55f);

      // Tooltip on hover
      for (int i = 0; i < items.size(); i++) {
          int col = i % COLS, row = i / COLS;
          float sx = gridX + col * (SLOT + GAP);
          float sy = gridY + row * (SLOT + GAP);
          if (mx >= sx && mx <= sx + SLOT && my >= sy && my <= sy + SLOT) {
              String name = items.get(i).name().replace('_', ' ');
              text.drawShadowed(font, name, (float) mx + 10f, (float) my - 4f, sw, sh, 1f, 1f, 0.8f);
              break;
          }
      }

      return result;
  }
  ```

- [ ] **Step 10.3 — Verify: Creative menu**

  ```powershell
  .\run.ps1
  ```

  Start game. Press `E`. Expected:
  - Fullscreen dark overlay with grid of all block types (excluding AIR, WATER_FLOW, DOOR_OPEN)
  - Hovering shows block name as tooltip
  - Clicking a block places it in the selected hotbar slot
  - Pressing `E` or `ESC` returns to game
  - World does not tick while menu is open (water doesn't spread, no movement)
  - Hotbar strip at bottom shows current state

- [ ] **Step 10.4 — Commit**

  ```
  git add src/main/java/com/mineclone/game/Hud.java
  git add src/main/java/com/mineclone/game/Game.java
  git commit -m "feat: Creative inventory menu (E key) — fullscreen block grid, click to assign hotbar"
  ```

---

## Task 11: Add New Blocks to Default Hotbar

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java`

- [ ] **Step 11.1 — Update default hotbar**

  In `Game.java`, find the `hotbar` field initialization:

  ```java
  private final BlockType[] hotbar = {
          BlockType.DIRT, BlockType.GRASS, BlockType.STONE, BlockType.COBBLE,
          BlockType.SAND, BlockType.WOOD, BlockType.PLANKS, BlockType.LEAVES, BlockType.TORCH
  };
  ```

  Replace with (adding GLASS, DOOR\_CLOSED, STAIRS; keeping other slots useful):

  ```java
  private final BlockType[] hotbar = {
          BlockType.STONE, BlockType.DIRT, BlockType.GRASS, BlockType.PLANKS,
          BlockType.GLASS, BlockType.DOOR_CLOSED, BlockType.STAIRS, BlockType.TORCH, BlockType.WATER
  };
  ```

- [ ] **Step 11.2 — Final integration verify**

  ```powershell
  .\run.ps1
  ```

  Full end-to-end checklist:
  - [ ] Keys 5, 6, 7 select Glass, Door, Stairs in hotbar
  - [ ] Glass placed: transparent, solid (player can't walk through), renders with grid pattern
  - [ ] Door placed: thin slab visible; right-click opens (rotates 90°); right-click again closes; sound plays
  - [ ] Stairs placed: step geometry visible; walking into low side steps player up; landing on step puts player at y+1.0; landing on slab puts player at y+0.5
  - [ ] Water (slot 9): placing starts spreading BFS; removing source drains it
  - [ ] Swimming: walking into water → swimming physics active
  - [ ] E key: creative menu opens; blocks selectable; world paused; hotbar updates

- [ ] **Step 11.3 — Final commit**

  ```
  git add src/main/java/com/mineclone/game/Game.java
  git commit -m "feat: update default hotbar to include glass, door, stairs, water"
  ```

---

## Self-Review Checklist

**Spec coverage:**

| Spec section | Task |
|---|---|
| Block metadata (byte[] meta) | Task 1 |
| Glass block | Task 2 + 3 |
| Door block (open/close) | Task 2 + 7 |
| Stairs (directional, meta) | Task 2 + 8 + 9 |
| WATER_FLOW block | Task 2 |
| Texture atlas tiles 14-15 | Task 3 |
| BFS water simulation | Task 4 |
| 8-level water rendering | Task 5 |
| Swimming physics | Task 6 |
| Water sounds | Task 2 (Sounds) + Task 6 |
| Door sounds | Task 2 (Sounds) + Task 7 |
| Creative menu (E key, fullscreen, hotbar assign) | Task 10 |
| Chunk streaming during creative menu | Task 10 (Step 10.1) |

**No gaps found.**

**Type consistency check:**
- `WaterSimulator.tick(World)` → matches call in `Game.updatePlaying`
- `Chunk.getMeta / setMeta` → matches calls in `ChunkMesher`, `Player`, `WaterSimulator`
- `World.getBlockMeta / setBlock(x,y,z,t,meta)` → matches calls in `Game`, `Player`
- `Sounds.waterSplash() / waterSwim() / doorToggle()` → matches calls in `Game`
- `Hud.drawCreativeMenu(int,int,double,double,boolean,BlockType[],int)` → matches call in `Game.drawUi`
- `facingFromCamera()` → used in both door placement and stair placement (Step 7.1)
- `resolveStairs(World,int,int,int,float,float,float,float)` → called from `moveAxis` in Step 8.2

**No placeholder issues found. All steps have concrete code.**
