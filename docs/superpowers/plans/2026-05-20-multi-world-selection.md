# Multi-World Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single hardcoded world with a scrollable world-select screen that lets the player create, play, and delete multiple worlds without restarting the app.

**Architecture:** World identity uses a timestamp-based folder ID (`world_yyyy-MM-dd_HH-mm-ss`); `level.dat` v4 adds a UTF display name before the seed; `Game` de-finalises world/mesher/loader, gains `startWorld(id)` / `createWorld()` / `unloadWorld()`, and a `inWorldSelect` sub-mode mirroring `inSettings`; `Hud` gains `drawWorldSelect` returning `WorldSelectAction`.

**Tech Stack:** Java 17, LWJGL 3, JOML, custom binary save format (gzip DataStream), GLFW.

---

## File Structure

| File | Change |
|---|---|
| `src/main/java/com/mineclone/save/SaveFormat.java` | `LEVEL_VERSION = 4`; add `newWorldId()` |
| `src/main/java/com/mineclone/save/LevelData.java` | Add `name` field; new named leaf constructor |
| `src/main/java/com/mineclone/save/SaveManager.java` | `WorldInfo`; `loadWorldInfo`; `listWorlds`; v4 read/write |
| `src/main/java/com/mineclone/game/Game.java` | De-finalise world fields; `startWorld` / `createWorld` / `unloadWorld`; world-select state machine |
| `src/main/java/com/mineclone/game/Hud.java` | `WorldSelectAction`; `drawWorldSelect`; updated `drawMainMenu` |

---

## Task 1: Bump save format version and add world-ID helper

**Files:**
- Modify: `src/main/java/com/mineclone/save/SaveFormat.java`

- [ ] **Step 1: Change LEVEL_VERSION to 4 and add `newWorldId()`**

  Replace lines 12–13 in [SaveFormat.java](src/main/java/com/mineclone/save/SaveFormat.java):

  ```java
  // FROM:
  public static final int LEVEL_VERSION = 3;

  // TO:
  public static final int LEVEL_VERSION = 4;
  ```

  Then add this static method before the closing `}` of the class:

  ```java
  /** Generates a filesystem-safe world folder ID based on current time. */
  public static String newWorldId() {
      java.time.LocalDateTime now = java.time.LocalDateTime.now();
      return "world_" + now.format(
          java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
  }
  ```

- [ ] **Step 2: Compile only — verify no errors**

  ```powershell
  $libs = Join-Path $PWD 'libs'
  $out  = Join-Path $PWD 'out'
  $jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
  $sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
  $srcList = Join-Path $out 'sources.txt'
  [IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
  javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
  ```

  Expected: exit code 0, no errors.

- [ ] **Step 3: Commit**

  ```powershell
  git add src/main/java/com/mineclone/save/SaveFormat.java
  git commit -m "feat(save): bump LEVEL_VERSION to 4; add newWorldId() helper"
  ```

---

## Task 2: Add `name` field to LevelData

**Files:**
- Modify: `src/main/java/com/mineclone/save/LevelData.java`

- [ ] **Step 1: Add `name` field and new leaf constructor**

  In [LevelData.java](src/main/java/com/mineclone/save/LevelData.java), add `public final String name;` as the **first** field (before `seed`), then redirect the existing 3-arg leaf constructor to a new named constructor:

  ```java
  // ADD as first field:
  public final String name;

  // REPLACE the existing leaf constructor (the one with BlockType[] inventory param):
  public LevelData(long seed, double px, double py, double pz,
                   double spawnX, double spawnY, double spawnZ,
                   float yaw, float pitch, float timeOfDay, int selectedSlot,
                   BlockType[] inventory) {
      this("", seed, px, py, pz, spawnX, spawnY, spawnZ,
           yaw, pitch, timeOfDay, selectedSlot, inventory);
  }

  // ADD new named leaf constructor after the one above:
  public LevelData(String name, long seed, double px, double py, double pz,
                   double spawnX, double spawnY, double spawnZ,
                   float yaw, float pitch, float timeOfDay, int selectedSlot,
                   BlockType[] inventory) {
      this.name = name != null ? name : "";
      this.seed = seed;
      this.px = px; this.py = py; this.pz = pz;
      this.spawnX = spawnX; this.spawnY = spawnY; this.spawnZ = spawnZ;
      this.yaw = yaw; this.pitch = pitch;
      this.timeOfDay = timeOfDay;
      this.selectedSlot = selectedSlot;
      this.inventory = normalizeInventory(inventory);
  }
  ```

  The two shorter constructors (the 8-arg and 11-arg ones) keep calling the old leaf signature — they now delegate through to the new named one via the redirected leaf. No changes needed to them.

- [ ] **Step 2: Compile — verify no errors**

  Run the compile command from Task 1 Step 2. Expected: exit 0.

- [ ] **Step 3: Commit**

  ```powershell
  git add src/main/java/com/mineclone/save/LevelData.java
  git commit -m "feat(save): add name field to LevelData with named constructor"
  ```

---

## Task 3: SaveManager — WorldInfo, listWorlds, v4 read/write

**Files:**
- Modify: `src/main/java/com/mineclone/save/SaveManager.java`

- [ ] **Step 1: Add `WorldInfo` nested class**

  Add this **inside** `SaveManager`, just after the opening class brace (before `savesRoot`):

  ```java
  public static final class WorldInfo {
      public final String id;
      public final String displayName;
      public final long seed;
      public final boolean corrupted;

      WorldInfo(String id, String displayName, long seed, boolean corrupted) {
          this.id = id;
          this.displayName = displayName;
          this.seed = seed;
          this.corrupted = corrupted;
      }
  }
  ```

- [ ] **Step 2: Add `loadWorldInfo(String id)` method**

  Add after `hasSave(String id)` (after line 53):

  ```java
  /**
   * Reads only MAGIC, VERSION, name (v4+), and seed from level.dat.
   * Returns a WorldInfo with {@code corrupted=true} on any error.
   */
  public WorldInfo loadWorldInfo(String id) {
      File f = levelFile(id);
      if (!f.isFile()) return new WorldInfo(id, "World", 0L, true);
      try (DataInputStream in = new DataInputStream(new GZIPInputStream(
              new BufferedInputStream(new FileInputStream(f))))) {
          if (in.readInt() != SaveFormat.MAGIC) return new WorldInfo(id, "World", 0L, true);
          int version = in.readInt();
          if (version < 1 || version > SaveFormat.LEVEL_VERSION)
              return new WorldInfo(id, "World", 0L, true);
          String name = (version >= 4) ? in.readUTF() : "";
          long seed = in.readLong();
          if (name.isEmpty()) name = "World";
          return new WorldInfo(id, name, seed, false);
      } catch (IOException e) {
          return new WorldInfo(id, "World", 0L, true);
      }
  }
  ```

- [ ] **Step 3: Add `listWorlds()` and private `trailingNumber` helper**

  Add after `loadWorldInfo`:

  ```java
  /**
   * Lists all worlds in saves/. Each subdirectory containing level.dat is a
   * world. Corrupted worlds are included with {@code corrupted=true}.
   * Sorted: valid worlds by display name numeric suffix ("World 2" before
   * "World 10"), corrupted worlds last.
   */
  public java.util.List<WorldInfo> listWorlds() {
      java.util.List<WorldInfo> list = new java.util.ArrayList<>();
      File[] dirs = savesRoot.listFiles(File::isDirectory);
      if (dirs == null) return list;
      for (File d : dirs) {
          if (!new File(d, SaveFormat.LEVEL_FILE).isFile()) continue;
          try {
              list.add(loadWorldInfo(d.getName()));
          } catch (Exception e) {
              list.add(new WorldInfo(d.getName(), "World", 0L, true));
          }
      }
      list.sort((a, b) -> {
          if (a.corrupted != b.corrupted) return a.corrupted ? 1 : -1;
          int na = trailingNumber(a.displayName);
          int nb = trailingNumber(b.displayName);
          if (na >= 0 && nb >= 0) return Integer.compare(na, nb);
          return a.displayName.compareToIgnoreCase(b.displayName);
      });
      return list;
  }

  private static int trailingNumber(String s) {
      int i = s.lastIndexOf(' ');
      if (i < 0) return -1;
      try { return Integer.parseInt(s.substring(i + 1)); }
      catch (NumberFormatException e) { return -1; }
  }
  ```

- [ ] **Step 4: Update `saveLevel` to write name (v4)**

  In `saveLevel`, after `o.writeInt(SaveFormat.LEVEL_VERSION);`, add:

  ```java
  o.writeUTF(d.name);   // v4: display name before seed
  ```

  The next line (`o.writeLong(d.seed);`) stays unchanged.

- [ ] **Step 5: Update `loadLevel` to read name for v4 and use named constructor**

  In `loadLevel`, after reading `version`, add:

  ```java
  String name = (version >= 4) ? in.readUTF() : "";
  ```

  This goes **before** `long seed = in.readLong();`.

  Then at the `return` statement at the bottom of `loadLevel`, change from:

  ```java
  return new LevelData(seed, px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch, tod, slot, inventory);
  ```

  to:

  ```java
  return new LevelData(name, seed, px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch, tod, slot, inventory);
  ```

- [ ] **Step 6: Compile — verify no errors**

  Run the compile command from Task 1 Step 2. Expected: exit 0.

- [ ] **Step 7: Commit**

  ```powershell
  git add src/main/java/com/mineclone/save/SaveManager.java
  git commit -m "feat(save): add WorldInfo, listWorlds, loadWorldInfo; v4 level.dat read/write"
  ```

---

## Task 4: Game — de-finalise world fields and add lifecycle methods

This task covers the data-layer refactor: making world/mesher/loader mutable and adding the three lifecycle methods. No UI changes yet.

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java`

- [ ] **Step 1: Remove `final` from world/mesher/loader/worldId; remove field initialisers**

  Change the four declarations:

  ```java
  // FROM:
  private final World world;
  private final ChunkMesher mesher;
  private final ChunkLoader loader;
  ...
  private final String worldId = com.mineclone.save.SaveFormat.DEFAULT_WORLD_ID;

  // TO:
  private World world;
  private ChunkMesher mesher;
  private ChunkLoader loader;
  ...
  private String worldId;
  ```

- [ ] **Step 2: Remove `pendingLevel`, `showNewWorldConfirm`, `newWorldPending` fields**

  Delete these field declarations (they become unused):

  ```java
  private com.mineclone.save.LevelData pendingLevel;
  private boolean showNewWorldConfirm = false;
  private boolean newWorldPending = false;
  ```

- [ ] **Step 3: Add `worldDisplayName` and world-select state fields**

  Add after the `worldId` field declaration:

  ```java
  private String worldDisplayName = "";
  private boolean inWorldSelect = false;
  private String pendingDeleteId = null;
  private int worldSelectScroll = 0;
  private java.util.List<com.mineclone.save.SaveManager.WorldInfo> worldList =
          java.util.List.of();
  ```

- [ ] **Step 4: Remove world-init block from constructor**

  In the constructor, delete these lines (they use the now-removed `final` fields and `pendingLevel`):

  ```java
  com.mineclone.save.LevelData saved = save.loadLevel(worldId);
  this.world = new World(saved != null ? saved.seed : new java.util.Random().nextLong());
  this.mesher = new ChunkMesher(world);
  this.loader = new ChunkLoader(world, mesher, save, worldId);
  this.pendingLevel = saved;
  if (saved != null) {
      this.worldSpawn.set((float) saved.spawnX, (float) saved.spawnY, (float) saved.spawnZ);
  }
  ```

- [ ] **Step 5: Remove `startNewWorld()` method entirely**

  Delete the `startNewWorld()` method (the one that calls `save.deleteWorld`, writes a new seed, and sets `glfwSetWindowShouldClose`).

- [ ] **Step 6: Add `startWorld(String id)` method**

  Add after `beginLoadingToPlay()`:

  ```java
  private void startWorld(String id) {
      this.worldId = id;
      com.mineclone.save.LevelData lvl = save.loadLevel(id);
      long seed = (lvl != null) ? lvl.seed : new java.util.Random().nextLong();
      this.world = new World(seed);
      this.mesher = new ChunkMesher(world);
      this.loader = new ChunkLoader(world, mesher, save, id);

      // Preload spawn 3x3 so the player has ground under their feet immediately.
      for (int dx = -1; dx <= 1; dx++)
          for (int dz = -1; dz <= 1; dz++)
              loader.applySnapshot(world.getChunk(dx, dz));
      loader.drainLightFlood(9);

      // Default spawn: find solid surface at (8, ?, 8).
      int sx = 8, sz = 8;
      for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
          if (world.getBlock(sx, y, sz).solid) {
              player.position.set(sx + 0.5f, y + 1.1f, sz + 0.5f);
              break;
          }
      }
      worldSpawn.set(player.position.x, player.position.y, player.position.z);
      gameTime = (float) (Math.PI / 6.0);
      selectedSlot = 0;
      System.arraycopy(com.mineclone.save.LevelData.defaultInventory(), 0,
              inventory, 0, inventory.length);

      if (lvl != null) {
          worldDisplayName = lvl.name.isEmpty() ? "World" : lvl.name;
          worldSpawn.set((float) lvl.spawnX, (float) lvl.spawnY, (float) lvl.spawnZ);
          player.position.set((float) lvl.px, (float) lvl.py, (float) lvl.pz);
          player.camera.yaw = lvl.yaw;
          player.camera.pitch = lvl.pitch;
          gameTime = lvl.timeOfDay;
          selectedSlot = Math.floorMod(lvl.selectedSlot, 9);
          System.arraycopy(lvl.inventory, 0, inventory, 0,
                  Math.min(inventory.length, lvl.inventory.length));
      } else {
          worldDisplayName = "World";
      }

      lastHeldBlock = currentBlock();
      cursorItem = BlockType.AIR;
      player.flying = false;
      player.flySpeed = Player.FLY_SPEED;

      WaterSimulator.reset();
      beginLoadingToPlay();
  }
  ```

- [ ] **Step 7: Add `createWorld()` method**

  Add after `startWorld`:

  ```java
  private void createWorld() {
      String id = com.mineclone.save.SaveFormat.newWorldId();

      // Find lowest free N for display name "World N".
      java.util.List<com.mineclone.save.SaveManager.WorldInfo> existing = save.listWorlds();
      java.util.Set<Integer> usedNums = new java.util.HashSet<>();
      for (com.mineclone.save.SaveManager.WorldInfo wi : existing) {
          int n = trailingWorldN(wi.displayName);
          if (n > 0) usedNums.add(n);
      }
      int n = 1;
      while (usedNums.contains(n)) n++;
      String displayName = "World " + n;

      long seed = new java.util.Random().nextLong();
      Vector3f spawn = findDefaultSpawn(seed);
      com.mineclone.save.LevelData fresh = new com.mineclone.save.LevelData(
              displayName, seed,
              spawn.x, spawn.y, spawn.z,
              spawn.x, spawn.y, spawn.z,
              0f, 0f, (float) (Math.PI / 6.0), 0,
              com.mineclone.save.LevelData.defaultInventory());
      save.saveLevel(id, fresh);
      startWorld(id);
  }

  private static int trailingWorldN(String displayName) {
      if (!displayName.startsWith("World ")) return -1;
      try { return Integer.parseInt(displayName.substring(6)); }
      catch (NumberFormatException e) { return -1; }
  }
  ```

- [ ] **Step 8: Add `unloadWorld()` method**

  Add after `createWorld`:

  ```java
  /** Save, flush, stop loader threads, destroy GL meshes, null world refs.
   *  Must be called from the main (GL) thread only. */
  private void unloadWorld() {
      if (world == null) return;
      saveAll();
      save.flushAndAwait();
      loader.shutdown();
      for (Mesh m : chunkMeshes.values()) m.destroy();
      for (Mesh m : waterMeshes.values()) m.destroy();
      chunkMeshes.clear();
      waterMeshes.clear();
      WaterSimulator.reset();
      world = null;
      mesher = null;
      loader = null;
  }
  ```

- [ ] **Step 9: Update `saveAll()` to guard null world and carry display name**

  Replace the body of `saveAll()`:

  ```java
  private void saveAll() {
      if (world == null) return;
      com.mineclone.save.LevelData d = new com.mineclone.save.LevelData(
              worldDisplayName,
              world.seed,
              player.position.x, player.position.y, player.position.z,
              worldSpawn.x, worldSpawn.y, worldSpawn.z,
              player.camera.yaw, player.camera.pitch,
              gameTime, selectedSlot, inventory);
      save.saveLevel(worldId, d);
      for (com.mineclone.world.Chunk c : world.getLoadedChunks()) {
          saveChunkIfModified(c);
      }
  }
  ```

- [ ] **Step 10: Update the `run()` method**

  Remove the spawn-preload and pendingLevel-restore block that appears just before `input.grabCursor(false)` in `run()`:

  ```java
  // DELETE this entire block:
  for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++)
          loader.applySnapshot(world.getChunk(dx, dz));
  loader.drainLightFlood(9);

  int sx = 8, sz = 8;
  for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
      if (world.getBlock(sx, y, sz).solid) {
          player.position.set(sx + 0.5f, y + 1.1f, sz + 0.5f);
          break;
      }
  }

  if (pendingLevel != null) {
      player.position.set((float) pendingLevel.px,
                          (float) pendingLevel.py,
                          (float) pendingLevel.pz);
      player.camera.yaw = pendingLevel.yaw;
      player.camera.pitch = pendingLevel.pitch;
      gameTime = pendingLevel.timeOfDay;
      selectedSlot = Math.floorMod(pendingLevel.selectedSlot, 9);
      System.arraycopy(pendingLevel.inventory, 0, inventory, 0,
              Math.min(inventory.length, pendingLevel.inventory.length));
      lastHeldBlock = currentBlock();
  }
  ```

  Then replace the final save after the game loop:

  ```java
  // FROM:
  if (!newWorldPending)
      saveAll();
  save.flushAndAwait();

  // TO:
  if (world != null) {
      saveAll();
      save.flushAndAwait();
  }
  ```

- [ ] **Step 11: Compile — verify no errors**

  Run the compile command from Task 1 Step 2. Expected: exit 0.

  If `pendingLevel` or `newWorldPending` or `showNewWorldConfirm` references remain, delete them.

- [ ] **Step 12: Commit**

  ```powershell
  git add src/main/java/com/mineclone/game/Game.java
  git commit -m "feat(game): de-finalise world fields; add startWorld/createWorld/unloadWorld"
  ```

---

## Task 5: Game — world-select menu state machine

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java`

All changes are inside `drawUi()` and `updateMenu()`.

- [ ] **Step 1: Handle scroll in `updateMenu()`**

  In `updateMenu(float dt)`, add scroll accumulation for the world-select screen. Add at the end of the method body:

  ```java
  if (inWorldSelect) {
      int delta = (int) input.getScroll();
      if (delta != 0)
          worldSelectScroll = Math.max(0, worldSelectScroll - delta);
  }
  ```

- [ ] **Step 2: Replace the MENU case in `drawUi()` with the new flow**

  The existing MENU case handles `showNewWorldConfirm`, `inSettings`, and the `CONTINUE / NEW_WORLD / SETTINGS / QUIT` buttons.

  Replace the entire `case MENU -> { ... }` block with:

  ```java
  case MENU -> {
      boolean clicked = !swallowMouseUntilUp
              && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
      boolean down = !swallowMouseUntilUp
              && input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
      double mx = input.getCursorX(), my = input.getCursorY();

      if (inWorldSelect) {
          if (pendingDeleteId != null) {
              // Find display name for the confirm message.
              String dn = pendingDeleteId;
              for (com.mineclone.save.SaveManager.WorldInfo wi : worldList)
                  if (wi.id.equals(pendingDeleteId)) { dn = wi.displayName; break; }
              Hud.MenuAction da = hud.drawConfirm(w, h,
                      "Delete \"" + dn + "\"? This cannot be undone.",
                      "Delete", mx, my, clicked,
                      Hud.MenuAction.DELETE_WORLD_CONFIRM);
              if (da == Hud.MenuAction.DELETE_WORLD_CONFIRM) {
                  save.deleteWorld(pendingDeleteId);
                  pendingDeleteId = null;
                  worldList = save.listWorlds();
                  worldSelectScroll = 0;
              } else if (da == Hud.MenuAction.CANCEL
                      || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                  pendingDeleteId = null;
              }
          } else {
              int maxScroll = Math.max(0, worldList.size() - 5);
              worldSelectScroll = Math.max(0, Math.min(worldSelectScroll, maxScroll));
              Hud.WorldSelectAction wa = hud.drawWorldSelect(
                      w, h, mx, my, clicked, worldList, worldSelectScroll);
              if (wa.playId != null) {
                  sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                  inWorldSelect = false;
                  startWorld(wa.playId);
              } else if (wa.deleteId != null) {
                  sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                  pendingDeleteId = wa.deleteId;
                  swallowMouseUntilUp = true;
              } else if (wa.newWorld) {
                  sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                  inWorldSelect = false;
                  createWorld();
              } else if (wa.back || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                  sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                  inWorldSelect = false;
              }
          }
          break;
      }

      if (inSettings) {
          float[] sv = { renderRadius, fovDegrees, brightness, volume };
          Hud.MenuAction a = hud.drawSettings(w, h, mx, my, down, clicked, sv);
          renderRadius = Math.round(sv[0]);
          fovDegrees = Math.round(sv[1]);
          brightness = sv[2];
          if (sv[3] != volume) {
              volume = sv[3];
              sound.setMasterVolume(volume);
          }
          boolean escBack = input.keyPressed(GLFW.GLFW_KEY_ESCAPE);
          if (a == Hud.MenuAction.SETTINGS_BACK || escBack) {
              inSettings = false;
              save.saveOptions(new com.mineclone.save.Options(
                      renderRadius, fovDegrees, brightness, volume));
              sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
          }
          break;
      }

      Hud.MenuAction a = hud.drawMainMenu(w, h, mx, my, clicked);
      if (a != Hud.MenuAction.NONE)
          sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
      switch (a) {
          case SINGLEPLAYER -> {
              worldList = save.listWorlds();
              worldSelectScroll = 0;
              inWorldSelect = true;
              swallowMouseUntilUp = true;
          }
          case SETTINGS -> { inSettings = true; swallowMouseUntilUp = true; }
          case QUIT      -> GLFW.glfwSetWindowShouldClose(window.getHandle(), true);
          default -> { }
      }
  }
  ```

  Note: `drawMainMenu` signature changes in Task 6 (removes `hasSave` param). For now the compile will fail until Task 6 is done — that's expected.

- [ ] **Step 3: Update PAUSED `MAIN_MENU` action to call `unloadWorld()`**

  In the `case PAUSED -> { ... }` block, find the `case MAIN_MENU ->` handler and replace it:

  ```java
  // FROM:
  case MAIN_MENU -> {
      saveAll();
      save.flushAndAwait();
      saveToastTimer = 1.6f;
      inSettings = false;
      state = State.MENU;
      input.grabCursor(false);
      swallowMouseUntilUp = true;
  }

  // TO:
  case MAIN_MENU -> {
      unloadWorld();
      saveToastTimer = 1.6f;
      inSettings = false;
      state = State.MENU;
      input.grabCursor(false);
      swallowMouseUntilUp = true;
  }
  ```

- [ ] **Step 4: Compile will fail (drawMainMenu signature mismatch) — that's expected**

  Do NOT commit yet. Proceed to Task 6 which fixes Hud.java, then compile both together.

---

## Task 6: Hud — WorldSelectAction, drawWorldSelect, updated main menu

**Files:**
- Modify: `src/main/java/com/mineclone/game/Hud.java`

- [ ] **Step 1: Add `SINGLEPLAYER` and `DELETE_WORLD_CONFIRM` to `MenuAction`**

  In the `MenuAction` enum, add two new values:

  ```java
  public enum MenuAction {
      NONE,
      SINGLEPLAYER,           // main menu: open world select screen
      CONTINUE,               // (legacy, no longer emitted)
      NEW_WORLD,              // (legacy, no longer emitted)
      NEW_WORLD_CONFIRM,      // (legacy, no longer emitted)
      DELETE_WORLD_CONFIRM,   // world select delete confirm: Yes
      CANCEL,
      SAVE,
      MAIN_MENU,
      RESUME, SETTINGS, SETTINGS_BACK, QUIT,
      RESPAWN
  }
  ```

- [ ] **Step 2: Add `WorldSelectAction` class inside `Hud`**

  Add this static nested class after `InventoryAction`:

  ```java
  public static final class WorldSelectAction {
      public final String playId;    // non-null: user clicked a world row
      public final String deleteId;  // non-null: user clicked Delete on a row
      public final boolean newWorld;
      public final boolean back;

      private WorldSelectAction(String playId, String deleteId,
                                boolean newWorld, boolean back) {
          this.playId   = playId;
          this.deleteId = deleteId;
          this.newWorld = newWorld;
          this.back     = back;
      }

      public static WorldSelectAction none()            { return new WorldSelectAction(null, null, false, false); }
      public static WorldSelectAction play(String id)   { return new WorldSelectAction(id,   null, false, false); }
      public static WorldSelectAction delete(String id) { return new WorldSelectAction(null, id,   false, false); }
      public static WorldSelectAction newWorld()        { return new WorldSelectAction(null, null, true,  false); }
      public static WorldSelectAction back()            { return new WorldSelectAction(null, null, false, true);  }
  }
  ```

- [ ] **Step 3: Update `drawMainMenu` — remove `hasSave` param, use Singleplayer/Settings/Quit**

  Replace the entire `drawMainMenu` method:

  ```java
  public MenuAction drawMainMenu(int screenW, int screenH,
                                 double mx, double my, boolean clicked) {
      drawTitle(screenW, screenH, "MINECLONE", 80f);
      String[]     labels = { "Singleplayer", "Settings", "Quit" };
      MenuAction[] acts   = { MenuAction.SINGLEPLAYER, MenuAction.SETTINGS, MenuAction.QUIT };
      boolean[]    enabled = { true, true, true };
      return stoneMenu(screenW, screenH, labels, acts, enabled,
              mx, my, clicked, /*dimWorld=*/false, /*titleArt=*/true);
  }
  ```

- [ ] **Step 4: Add `drawWorldSelect` method**

  Add after `drawMainMenu`:

  ```java
  /**
   * World selection screen. Renders a scrollable list of worlds with a
   * Delete button per row, plus New World and Back at the bottom.
   *
   * @param scrollOffset  first visible row index (caller clamps to valid range)
   * @return action taken this frame, or {@link WorldSelectAction#none()}
   */
  public WorldSelectAction drawWorldSelect(int sw, int sh,
          double mx, double my, boolean clicked,
          java.util.List<com.mineclone.save.SaveManager.WorldInfo> worlds,
          int scrollOffset) {

      final float ROW_H   = 64f;
      final float ROW_PAD = 6f;
      final float LIST_X  = sw / 2f - 310f;
      final float LIST_W  = 620f;
      final float LIST_TOP = 140f;
      final float LIST_BOT = sh - 110f;
      final int   VIS_ROWS = Math.max(1, (int) ((LIST_BOT - LIST_TOP) / (ROW_H + ROW_PAD)));
      final float DEL_W = 72f, DEL_H = 30f;

      WorldSelectAction result = WorldSelectAction.none();

      // ── quads ─────────────────────────────────────────────────────────────
      ui.begin(sw, sh);
      // dim panel behind the list
      ui.quad(LIST_X - 10f, LIST_TOP - 10f,
              LIST_W + 20f, LIST_BOT - LIST_TOP + 20f,
              0f, 0f, 0f, 0.45f);

      int end = Math.min(worlds.size(), scrollOffset + VIS_ROWS);
      for (int i = scrollOffset; i < end; i++) {
          com.mineclone.save.SaveManager.WorldInfo wi = worlds.get(i);
          float ry  = LIST_TOP + (i - scrollOffset) * (ROW_H + ROW_PAD);
          float rowW = LIST_W - DEL_W - 10f;

          // Row background
          boolean rowHov = !wi.corrupted && hov(mx, my, LIST_X, ry, rowW, ROW_H);
          float rc = rowHov ? 0.30f : 0.17f;
          ui.quad(LIST_X, ry, rowW, ROW_H, rc, rc, rc + 0.04f, 0.88f);

          // Delete button
          float delX = LIST_X + LIST_W - DEL_W;
          float delY = ry + (ROW_H - DEL_H) / 2f;
          boolean delHov = hov(mx, my, delX, delY, DEL_W, DEL_H);
          float dk = delHov ? 0.60f : 0.34f;
          ui.quad(delX, delY, DEL_W, DEL_H, dk, 0.12f, 0.12f, 0.92f);
          ui.quad(delX, delY, DEL_W, 2f, dk + 0.18f, 0.30f, 0.30f, 0.70f);

          if (clicked && rowHov) result = WorldSelectAction.play(wi.id);
          if (clicked && delHov) result = WorldSelectAction.delete(wi.id);
      }

      // Bottom buttons
      float bw = 210f, bh = 46f, gap = 18f;
      float botY = sh - 88f;
      float newX  = sw / 2f - bw - gap / 2f;
      float backX = sw / 2f + gap / 2f;
      boolean newHov  = stoneButton(newX,  botY, bw, bh, "New World", sw, sh, mx, my, true, true);
      boolean backHov = stoneButton(backX, botY, bw, bh, "Back",      sw, sh, mx, my, true, true);
      if (clicked && newHov)  result = WorldSelectAction.newWorld();
      if (clicked && backHov) result = WorldSelectAction.back();

      ui.end();

      // ── text overlays ─────────────────────────────────────────────────────
      drawTitle(sw, sh, "Select World", 42f);

      for (int i = scrollOffset; i < end; i++) {
          com.mineclone.save.SaveManager.WorldInfo wi = worlds.get(i);
          float ry = LIST_TOP + (i - scrollOffset) * (ROW_H + ROW_PAD);
          float lhOff = font.getPixelHeight() * 0.34f;

          if (wi.corrupted) {
              text.drawShadowed(font, "Corrupted save",
                      LIST_X + 12f, ry + ROW_H / 2f + lhOff,
                      sw, sh, 0.8f, 0.3f, 0.3f);
          } else {
              text.drawShadowed(font, wi.displayName,
                      LIST_X + 12f, ry + 22f, sw, sh, 1f, 1f, 1f);
              text.drawShadowed(font, "Seed: " + wi.seed,
                      LIST_X + 12f, ry + 46f, sw, sh, 0.65f, 0.65f, 0.65f);
          }

          // Delete label
          float delX = LIST_X + LIST_W - DEL_W;
          float delY = ry + (ROW_H - DEL_H) / 2f;
          String dl = "Delete";
          float dlW = font.textWidth(dl);
          text.drawShadowed(font, dl,
                  delX + (DEL_W - dlW) / 2f, delY + DEL_H / 2f + lhOff,
                  sw, sh, 1f, 0.75f, 0.75f);
      }

      if (worlds.isEmpty()) {
          String msg = "No worlds yet. Click New World to start!";
          float mw = font.textWidth(msg);
          text.drawShadowed(font, msg, sw / 2f - mw / 2f, LIST_TOP + 50f,
                  sw, sh, 0.7f, 0.7f, 0.7f);
      }

      // Scroll hint when list overflows
      if (worlds.size() > VIS_ROWS) {
          String hint = "Scroll to see more (" + worlds.size() + " worlds)";
          float hw = font.textWidth(hint);
          text.drawShadowed(font, hint, sw / 2f - hw / 2f, LIST_BOT + 6f,
                  sw, sh, 0.5f, 0.5f, 0.5f);
      }

      return result;
  }
  ```

- [ ] **Step 5: Compile both Game.java and Hud.java together — verify no errors**

  Run the compile command from Task 1 Step 2. Expected: exit 0.

  Common issues to check:
  - `drawMainMenu` now takes 5 params (no `hasSave`): the call in `drawUi()` must match
  - `DELETE_WORLD_CONFIRM` referenced in Game.java must exist in `MenuAction`
  - `WorldSelectAction` fields used in Game.java match what was defined

- [ ] **Step 6: Commit**

  ```powershell
  git add src/main/java/com/mineclone/game/Game.java
  git add src/main/java/com/mineclone/game/Hud.java
  git commit -m "feat(ui): world select screen; Singleplayer/Settings/Quit main menu"
  ```

---

## Task 7: Manual smoke test

No automated tests exist. Run the game and verify each of the following.

- [ ] **Step 1: Launch**

  ```powershell
  .\run.ps1
  ```

  Expected: main menu shows **Singleplayer**, **Settings**, **Quit** (no Continue or New World).

- [ ] **Step 2: Create first world**

  Click **Singleplayer** → world select screen appears (empty: "No worlds yet…").
  Click **New World** → loading screen → game starts.
  The world folder `saves/world_<timestamp>/` exists on disk.

- [ ] **Step 3: Return to menu and create second world**

  Press ESC → **Main Menu** → back to world select (1 world listed as "World 1").
  Click **New World** → loading → second world starts.
  World select now shows "World 1" and "World 2".

- [ ] **Step 4: Switch worlds**

  Return to menu → click **World 1** → game starts in the first world.
  Player position from the first world is restored.

- [ ] **Step 5: Delete a world**

  Return to menu → world select → click **Delete** on World 2 → confirm dialog appears.
  Click **Delete** → world 2 disappears from the list.

- [ ] **Step 6: Legacy `saves/world/` interop (if it exists)**

  If a `saves/world/` folder exists from a pre-v4 session, it should appear in the world select list as "World" (no number).
  Clicking it should load the old world. After the first save, its `level.dat` upgrades to v4 silently.

- [ ] **Step 7: Settings from main menu**

  Click **Settings** from main menu → sliders work → **Done** returns to main menu.

---

## Task 8: Commit plan document

- [ ] **Step 1: Commit this plan**

  ```powershell
  git add docs/superpowers/plans/2026-05-20-multi-world-selection.md
  git commit -m "docs: add multi-world selection implementation plan"
  ```
