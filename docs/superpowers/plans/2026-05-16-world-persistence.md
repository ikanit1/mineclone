# World Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the world (player-modified chunks), player state, and time of day to disk so a session survives a restart, using whole-chunk GZIP snapshots under a per-world directory.

**Architecture:** A self-contained `com.mineclone.save` package owns all disk I/O behind a clean API (`SaveManager` ↔ `LevelData`/`ChunkSnapshot`). `Chunk` gains a `modified` flag set only on post-generation edits and bulk snapshot/restore accessors. `ChunkLoader` applies a saved snapshot right after generation (delta-patching). `Game` builds the world from `level.dat` if a save exists, and flushes level + all loaded modified chunks on pause, on a periodic timer, and on quit.

**Tech Stack:** Java 17, LWJGL/GLFW/JOML (engine only — the save package is plain Java), `DataOutputStream` + `GZIPOutputStream`. No test framework exists in the project; the save package is verified by a headless round-trip `main` harness run with `java`, the integration by the manual game checklist in the spec.

**Spec:** `docs/superpowers/specs/2026-05-16-save-system-and-menu-revamp-design.md` (Sections 1–7, 11–13). Sections 8–10 (menu world, title/buttons, settings persistence) are **Plan 2**, not this plan.

**Deviations from spec (intentional, see Section 12 of spec + reality of codebase):**
- **No chunk eviction exists.** `ChunkLoader` never unloads chunks. Spec §6/§7 "flush dirty chunk on unload" has no unload path to hook. This plan implements `saveAll()` = level + **every loaded modified chunk**, which is correct and sufficient while nothing unloads. Eviction-flush is a documented follow-up; the per-chunk file format already supports it with no API change.
- **Continue is implicit in Plan 1.** Without the Plan 2 menu, `Game` auto-continues from `saves/world` when it exists, else creates a fresh world. The explicit "Continue / New World" buttons are Plan 2.
- **`Options` (settings persistence) is deferred to Plan 2** (YAGNI here — no settings UI change in this plan).

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/mineclone/save/SaveFormat.java` (create) | Magic/version constants + paths. One source of truth for the on-disk format. |
| `src/main/java/com/mineclone/save/LevelData.java` (create) | Immutable carrier for `level.dat` fields. |
| `src/main/java/com/mineclone/save/ChunkSnapshot.java` (create) | Immutable carrier for one chunk's `blocks`+`meta` arrays. |
| `src/main/java/com/mineclone/save/SaveManager.java` (create) | All disk I/O: level/chunk read/write/delete, background chunk-write executor. |
| `src/main/java/com/mineclone/world/Chunk.java` (modify) | Add `modified` flag + bulk snapshot/restore accessors. |
| `src/main/java/com/mineclone/world/World.java` (modify) | Mark the edited chunk `modified` in `setBlock`; expose loaded-chunk iteration (already exists). |
| `src/main/java/com/mineclone/world/ChunkLoader.java` (modify) | Hold a `SaveManager`+worldId; apply snapshot right after generation. |
| `src/main/java/com/mineclone/game/Game.java` (modify) | Own `SaveManager`; build world from `level.dat`; `saveAll()`; autosave on pause/periodic/quit. |
| `test/SaveRoundTrip.java` (create) | Headless round-trip harness (no OpenGL). Compiled+run with plain `java`. |

`SaveFormat`/`LevelData`/`ChunkSnapshot`/`SaveManager` have no engine dependencies and are unit-testable in isolation. `Chunk`/`World`/`ChunkLoader`/`Game` changes are minimal and follow existing patterns (private arrays + accessor methods, lazy `getChunk`, bg pools).

---

## Task 1: Save format constants

**Files:**
- Create: `src/main/java/com/mineclone/save/SaveFormat.java`

- [ ] **Step 1: Create the constants class**

```java
package com.mineclone.save;

import com.mineclone.world.Chunk;

/** Single source of truth for the on-disk save format. */
public final class SaveFormat {
    private SaveFormat() {}

    /** "MCLD" — first int of every save file. */
    public static final int MAGIC = 0x4D434C44;

    public static final int LEVEL_VERSION = 1;
    public static final int CHUNK_VERSION = 1;

    /** Blocks per chunk = SIZE_X*SIZE_Y*SIZE_Z (16*128*16 = 32768). */
    public static final int CHUNK_VOLUME = Chunk.SIZE_X * Chunk.SIZE_Y * Chunk.SIZE_Z;

    public static final String SAVES_ROOT = "saves";
    public static final String DEFAULT_WORLD_ID = "world";
    public static final String LEVEL_FILE = "level.dat";
    public static final String CHUNKS_DIR = "chunks";

    public static String chunkFileName(int cx, int cz) {
        return "c." + cx + "." + cz + ".dat";
    }
}
```

- [ ] **Step 2: Compile to verify it builds**

Run (PowerShell, from repo root):
```powershell
$libs = Join-Path $PWD 'libs'; $out = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"; "javac exit=$LASTEXITCODE"
```
Expected: `javac exit=0`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mineclone/save/SaveFormat.java
git commit -m "feat(save): on-disk format constants"
```

---

## Task 2: Data carriers (LevelData, ChunkSnapshot)

**Files:**
- Create: `src/main/java/com/mineclone/save/LevelData.java`
- Create: `src/main/java/com/mineclone/save/ChunkSnapshot.java`

- [ ] **Step 1: Create LevelData**

```java
package com.mineclone.save;

/** Everything stored in level.dat. Immutable. */
public final class LevelData {
    public final long seed;
    public final double px, py, pz;
    public final float yaw, pitch;
    public final float timeOfDay;
    public final int selectedSlot;

    public LevelData(long seed, double px, double py, double pz,
                     float yaw, float pitch, float timeOfDay, int selectedSlot) {
        this.seed = seed;
        this.px = px; this.py = py; this.pz = pz;
        this.yaw = yaw; this.pitch = pitch;
        this.timeOfDay = timeOfDay;
        this.selectedSlot = selectedSlot;
    }
}
```

- [ ] **Step 2: Create ChunkSnapshot**

```java
package com.mineclone.save;

/** One chunk's full block + meta arrays (length = SaveFormat.CHUNK_VOLUME). */
public final class ChunkSnapshot {
    public final int cx, cz;
    public final byte[] blocks;
    public final byte[] meta;

    public ChunkSnapshot(int cx, int cz, byte[] blocks, byte[] meta) {
        this.cx = cx; this.cz = cz;
        this.blocks = blocks; this.meta = meta;
    }
}
```

- [ ] **Step 3: Compile (same command as Task 1 Step 2)**

Expected: `javac exit=0`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mineclone/save/LevelData.java src/main/java/com/mineclone/save/ChunkSnapshot.java
git commit -m "feat(save): LevelData and ChunkSnapshot carriers"
```

---

## Task 3: SaveManager (disk I/O)

**Files:**
- Create: `src/main/java/com/mineclone/save/SaveManager.java`

- [ ] **Step 1: Create SaveManager with level + chunk + delete + executor**

```java
package com.mineclone.save;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * All save-file I/O. The engine depends only on this API and never touches
 * file layout. Chunk writes go through a single background thread so the game
 * loop never blocks on disk; level writes are tiny and synchronous.
 */
public final class SaveManager {
    private final File savesRoot;
    private final ExecutorService chunkWriter =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mineclone-save");
                t.setDaemon(true);
                return t;
            });

    public SaveManager() {
        this(new File(SaveFormat.SAVES_ROOT));
    }

    /** Test seam: point the manager at an arbitrary saves root. */
    public SaveManager(File savesRoot) {
        this.savesRoot = savesRoot;
    }

    private File worldDir(String id) { return new File(savesRoot, id); }
    private File levelFile(String id) { return new File(worldDir(id), SaveFormat.LEVEL_FILE); }
    private File chunksDir(String id) { return new File(worldDir(id), SaveFormat.CHUNKS_DIR); }
    private File chunkFile(String id, int cx, int cz) {
        return new File(chunksDir(id), SaveFormat.chunkFileName(cx, cz));
    }

    public boolean hasSave(String id) {
        return levelFile(id).isFile();
    }

    // ---- level.dat ----

    public void saveLevel(String id, LevelData d) {
        File f = levelFile(id);
        f.getParentFile().mkdirs();
        try (DataOutputStream o = new DataOutputStream(new GZIPOutputStream(
                new BufferedOutputStream(new FileOutputStream(f))))) {
            o.writeInt(SaveFormat.MAGIC);
            o.writeInt(SaveFormat.LEVEL_VERSION);
            o.writeLong(d.seed);
            o.writeDouble(d.px); o.writeDouble(d.py); o.writeDouble(d.pz);
            o.writeFloat(d.yaw); o.writeFloat(d.pitch);
            o.writeFloat(d.timeOfDay);
            o.writeInt(d.selectedSlot);
        } catch (IOException e) {
            System.err.println("saveLevel failed: " + e.getMessage());
        }
    }

    /** @return loaded level, or null if absent/unreadable/incompatible. */
    public LevelData loadLevel(String id) {
        File f = levelFile(id);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(f))))) {
            if (in.readInt() != SaveFormat.MAGIC) return null;
            if (in.readInt() != SaveFormat.LEVEL_VERSION) return null;
            long seed = in.readLong();
            double px = in.readDouble(), py = in.readDouble(), pz = in.readDouble();
            float yaw = in.readFloat(), pitch = in.readFloat();
            float tod = in.readFloat();
            int slot = in.readInt();
            return new LevelData(seed, px, py, pz, yaw, pitch, tod, slot);
        } catch (IOException e) {
            System.err.println("loadLevel failed: " + e.getMessage());
            return null;
        }
    }

    // ---- chunk snapshots ----

    /** Queues a chunk write on the background thread. Arrays must not be mutated after the call. */
    public void saveChunkAsync(String id, ChunkSnapshot s) {
        chunkWriter.submit(() -> saveChunkBlocking(id, s));
    }

    void saveChunkBlocking(String id, ChunkSnapshot s) {
        File f = chunkFile(id, s.cx, s.cz);
        f.getParentFile().mkdirs();
        try (DataOutputStream o = new DataOutputStream(new GZIPOutputStream(
                new BufferedOutputStream(new FileOutputStream(f))))) {
            o.writeInt(SaveFormat.MAGIC);
            o.writeInt(SaveFormat.CHUNK_VERSION);
            o.write(s.blocks, 0, SaveFormat.CHUNK_VOLUME);
            o.write(s.meta, 0, SaveFormat.CHUNK_VOLUME);
        } catch (IOException e) {
            System.err.println("saveChunk failed: " + e.getMessage());
        }
    }

    /** @return snapshot, or null if absent/unreadable/incompatible. */
    public ChunkSnapshot loadChunk(String id, int cx, int cz) {
        File f = chunkFile(id, cx, cz);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(f))))) {
            if (in.readInt() != SaveFormat.MAGIC) return null;
            if (in.readInt() != SaveFormat.CHUNK_VERSION) return null;
            byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME];
            byte[] meta = new byte[SaveFormat.CHUNK_VOLUME];
            in.readFully(blocks);
            in.readFully(meta);
            return new ChunkSnapshot(cx, cz, blocks, meta);
        } catch (IOException e) {
            System.err.println("loadChunk failed: " + e.getMessage());
            return null;
        }
    }

    public void deleteWorld(String id) {
        deleteRecursive(worldDir(id));
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    /** Block until queued chunk writes finish (call before process exit). */
    public void flushAndAwait() {
        chunkWriter.submit(() -> {});
        chunkWriter.shutdown();
        try {
            chunkWriter.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: Compile (same command as Task 1 Step 2)**

Expected: `javac exit=0`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mineclone/save/SaveManager.java
git commit -m "feat(save): SaveManager disk I/O (level, chunks, delete, async writer)"
```

---

## Task 4: Headless round-trip test

**Files:**
- Create: `test/SaveRoundTrip.java`

This is the project's verification substitute (no JUnit). It exercises the entire save package without OpenGL: write level + a chunk, read them back, assert equality, then delete.

> **Plan correction (applied during execution, commit 0f9330c):** The harness must be declared `package com.mineclone.save;` (white-box), not the default package, because `SaveManager.saveChunkBlocking` is package-private and Java forbids cross-package access to it. Consequently the run command uses the fully-qualified class name. Also: this harness body contains exactly **14** `check(...)` calls, so the correct passing output is `SaveRoundTrip OK (14 checks)` — the earlier "15" was a miscount.

- [ ] **Step 1: Create the harness**

```java
package com.mineclone.save;

import java.io.File;
import java.util.Random;

public class SaveRoundTrip {
    static int checks = 0;
    static void check(boolean cond, String what) {
        checks++;
        if (!cond) { System.err.println("FAIL: " + what); System.exit(1); }
    }

    public static void main(String[] args) {
        File tmp = new File("saves_test_tmp");
        SaveManager sm = new SaveManager(tmp);
        String id = "rt";

        check(!sm.hasSave(id), "no save before write");

        LevelData lvl = new LevelData(123456789L, 1.5, 90.25, -3.75,
                0.7f, -0.2f, 1.234f, 4);
        sm.saveLevel(id, lvl);
        check(sm.hasSave(id), "hasSave after saveLevel");

        LevelData back = sm.loadLevel(id);
        check(back != null, "loadLevel non-null");
        check(back.seed == 123456789L, "seed");
        check(back.px == 1.5 && back.py == 90.25 && back.pz == -3.75, "position");
        check(back.yaw == 0.7f && back.pitch == -0.2f, "orientation");
        check(back.timeOfDay == 1.234f, "timeOfDay");
        check(back.selectedSlot == 4, "selectedSlot");

        byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME];
        byte[] meta = new byte[SaveFormat.CHUNK_VOLUME];
        new Random(42).nextBytes(blocks);
        new Random(43).nextBytes(meta);
        sm.saveChunkBlocking(id, new ChunkSnapshot(-5, 12, blocks, meta));

        ChunkSnapshot cs = sm.loadChunk(id, -5, 12);
        check(cs != null, "loadChunk non-null");
        check(cs.cx == -5 && cs.cz == 12, "chunk coords");
        check(java.util.Arrays.equals(cs.blocks, blocks), "blocks round-trip");
        check(java.util.Arrays.equals(cs.meta, meta), "meta round-trip");

        check(sm.loadChunk(id, 99, 99) == null, "absent chunk -> null");

        sm.deleteWorld(id);
        check(!sm.hasSave(id), "deleteWorld removed save");
        new File(tmp, "").delete();
        tmp.delete();

        System.out.println("SaveRoundTrip OK (" + checks + " checks)");
    }
}
```

- [ ] **Step 2: Compile the harness against the built classes**

Run (PowerShell):
```powershell
javac -encoding UTF-8 -d out -cp out test\SaveRoundTrip.java; "javac exit=$LASTEXITCODE"
```
Expected: `javac exit=0` (Task 3 must be compiled into `out` first).

- [ ] **Step 3: Run it — verify all checks pass**

Run (PowerShell):
```powershell
java -cp out com.mineclone.save.SaveRoundTrip
```
Expected stdout: `SaveRoundTrip OK (14 checks)` and process exit 0. Any `FAIL: ...` line means stop and fix the offending Task 1–3 code before continuing.

- [ ] **Step 4: Commit**

```bash
git add test/SaveRoundTrip.java
git commit -m "test(save): headless round-trip harness for the save package"
```

---

## Task 5: Chunk — modified flag + snapshot/restore accessors

**Files:**
- Modify: `src/main/java/com/mineclone/world/Chunk.java`

- [ ] **Step 1: Add the `modified` field**

In `Chunk.java`, immediately after the existing line `public boolean dirty = true;` (line 21), add:

```java
    /**
     * True once a block changed AFTER initial generation (player/command/water
     * edits go through World.setBlock, which sets this). Distinct from
     * {@link #dirty}, which is the mesh-rebuild flag. Drives whole-chunk save.
     */
    public boolean modified = false;
```

- [ ] **Step 2: Add bulk snapshot/restore accessors**

In `Chunk.java`, add these methods just before the final closing brace (after `transparent(...)`, after line 162):

```java
    /** Defensive copy of the raw block array (length SIZE_X*SIZE_Y*SIZE_Z). */
    public byte[] copyBlocks() {
        return blocks.clone();
    }

    /** Defensive copy of the raw meta array. */
    public byte[] copyMeta() {
        return meta.clone();
    }

    /**
     * Overwrite this chunk's blocks+meta from a saved snapshot. Does NOT
     * touch lighting or flags — the caller recomputes sky light, sets
     * {@code dirty} for remeshing, and leaves {@code modified=false} (a
     * freshly-restored chunk matches disk).
     */
    public void restore(byte[] srcBlocks, byte[] srcMeta) {
        System.arraycopy(srcBlocks, 0, blocks, 0, blocks.length);
        System.arraycopy(srcMeta, 0, meta, 0, meta.length);
    }
```

- [ ] **Step 3: Compile (Task 1 Step 2 command)**

Expected: `javac exit=0`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mineclone/world/Chunk.java
git commit -m "feat(world): Chunk.modified flag + snapshot/restore accessors"
```

---

## Task 6: World — mark chunk modified on edits

**Files:**
- Modify: `src/main/java/com/mineclone/world/World.java:224-256`

`setBlock(int,int,int,BlockType)` is the player/command/water-sim edit path (generation uses `Chunk.set` directly and never calls this). The `(…,byte meta)` overload delegates to it, so one hook covers both.

- [ ] **Step 1: Set `modified` on the edited chunk**

In `World.java`, in `public void setBlock(int wx, int wy, int wz, BlockType t)`, find the existing line (line 233):

```java
        BlockType old = c.get(lx, wy, lz);
        c.set(lx, wy, lz, t);
```

Change it to:

```java
        BlockType old = c.get(lx, wy, lz);
        c.set(lx, wy, lz, t);
        c.modified = true;
```

- [ ] **Step 2: Compile (Task 1 Step 2 command)**

Expected: `javac exit=0`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mineclone/world/World.java
git commit -m "feat(world): flag chunk modified on post-generation edits"
```

---

## Task 7: ChunkLoader — apply saved snapshot after generation

**Files:**
- Modify: `src/main/java/com/mineclone/world/ChunkLoader.java`

Delta-patching: after a chunk is generated on the gen thread, if a snapshot exists on disk, restore it over the generated terrain and recompute chunk-local sky light (same bg-safe call `generate()` already makes). Block light from restored emitters is handled on the main thread in Task 8 via the `dirty` remesh + existing flood paths; restoring blocks+meta+sky light is correct and matches the engine's existing "light self-corrects on dirty" trade-off.

- [ ] **Step 1: Add SaveManager + worldId fields and constructor params**

In `ChunkLoader.java`, change the fields block (lines 30–33) from:

```java
    private final World world;
    private final ChunkMesher mesher;
    private final ExecutorService genPool;
    private final ExecutorService meshPool;
```

to:

```java
    private final World world;
    private final ChunkMesher mesher;
    private final ExecutorService genPool;
    private final ExecutorService meshPool;
    private final com.mineclone.save.SaveManager save;
    private final String worldId;
```

- [ ] **Step 2: Update the constructor**

Change the constructor signature + body (lines 41–46) from:

```java
    public ChunkLoader(World world, ChunkMesher mesher) {
        this.world = world;
        this.mesher = mesher;
        this.genPool  = Executors.newFixedThreadPool(2, daemon("mineclone-gen"));
        this.meshPool = Executors.newFixedThreadPool(2, daemon("mineclone-mesh"));
    }
```

to:

```java
    public ChunkLoader(World world, ChunkMesher mesher,
                       com.mineclone.save.SaveManager save, String worldId) {
        this.world = world;
        this.mesher = mesher;
        this.save = save;
        this.worldId = worldId;
        this.genPool  = Executors.newFixedThreadPool(2, daemon("mineclone-gen"));
        this.meshPool = Executors.newFixedThreadPool(2, daemon("mineclone-mesh"));
    }
```

- [ ] **Step 3: Apply the snapshot in `submitGen`**

Change `submitGen` (lines 70–79) from:

```java
    private void submitGen(int cx, int cz, long key) {
        if (!pendingGen.add(key)) return;
        genPool.submit(() -> {
            try {
                world.getChunk(cx, cz);
            } finally {
                pendingGen.remove(key);
            }
        });
    }
```

to:

```java
    private void submitGen(int cx, int cz, long key) {
        if (!pendingGen.add(key)) return;
        genPool.submit(() -> {
            try {
                Chunk c = world.getChunk(cx, cz);
                com.mineclone.save.ChunkSnapshot snap = save.loadChunk(worldId, cx, cz);
                if (snap != null) {
                    c.restore(snap.blocks, snap.meta);
                    c.computeSkyLight();
                    c.dirty = true;
                    c.modified = false;
                }
            } finally {
                pendingGen.remove(key);
            }
        });
    }
```

- [ ] **Step 4: Compile (Task 1 Step 2 command)**

Expected: `javac exit=1` with errors at the `new ChunkLoader(world, mesher)` call site in `Game.java` (signature changed). That is expected and fixed in Task 8. Do NOT commit yet — proceed to Task 8 so the build is green again.

---

## Task 8: Game — wire SaveManager, load/create world, saveAll, autosave

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java` (fields ~40–80, constructor 82–93, `run()` 99–143 + loop end ~752, plus a periodic-save tick in the PLAYING update and a pause-transition hook)

- [ ] **Step 1: Add SaveManager + bookkeeping fields**

In `Game.java`, in the fields block (after `private int selectedSlot = 0;`, line 76), add:

```java
    private final com.mineclone.save.SaveManager save = new com.mineclone.save.SaveManager();
    private final String worldId = com.mineclone.save.SaveFormat.DEFAULT_WORLD_ID;
    private static final float AUTOSAVE_INTERVAL = 120f; // seconds
    private float autosaveTimer = AUTOSAVE_INTERVAL;
```

- [ ] **Step 2: Build the world from the save (or fresh) in the constructor**

In `Game.java` constructor, replace lines 85–87:

```java
        this.world = new World(1337L);
        this.mesher = new ChunkMesher(world);
        this.loader = new ChunkLoader(world, mesher);
```

with:

```java
        com.mineclone.save.LevelData saved = save.loadLevel(worldId);
        this.world = new World(saved != null ? saved.seed : new java.util.Random().nextLong());
        this.mesher = new ChunkMesher(world);
        this.loader = new ChunkLoader(world, mesher, save, worldId);
        this.pendingLevel = saved; // applied to player/time in run() after spawn setup
```

- [ ] **Step 3: Add the pendingLevel field**

In `Game.java` fields block (next to the fields from Step 1), add:

```java
    private com.mineclone.save.LevelData pendingLevel;
```

- [ ] **Step 4: Apply saved player/time after spawn setup in `run()`**

In `Game.java` `run()`, the spawn block ends at line 123 (`break;` inside the `for (int y = ... )` that sets `player.position`). Immediately AFTER that `for` loop's closing brace (line 123 region, before `// Start in menu` at line 125), add:

```java
        if (pendingLevel != null) {
            player.position.set((float) pendingLevel.px,
                                (float) pendingLevel.py,
                                (float) pendingLevel.pz);
            player.camera.yaw = pendingLevel.yaw;
            player.camera.pitch = pendingLevel.pitch;
            gameTime = pendingLevel.timeOfDay;
            selectedSlot = Math.floorMod(pendingLevel.selectedSlot, hotbar.length);
        }
```

- [ ] **Step 5: Add `saveAll()`**

In `Game.java`, add this method next to `currentBlock()` (after line 97):

```java
    /** Flush level.dat + every loaded chunk whose blocks changed since gen. */
    private void saveAll() {
        com.mineclone.save.LevelData d = new com.mineclone.save.LevelData(
                world.seed,
                player.position.x, player.position.y, player.position.z,
                player.camera.yaw, player.camera.pitch,
                gameTime, selectedSlot);
        save.saveLevel(worldId, d);
        for (com.mineclone.world.Chunk c : world.getLoadedChunks()) {
            if (c.modified) {
                save.saveChunkAsync(worldId,
                        new com.mineclone.save.ChunkSnapshot(c.cx, c.cz,
                                c.copyBlocks(), c.copyMeta()));
                c.modified = false;
            }
        }
    }
```

- [ ] **Step 6: Periodic autosave while PLAYING**

In `Game.java` `updatePlaying(dt)` — locate the existing time advance line `gameTime += dt * TIME_SCALE;` (line 188). Immediately after it add:

```java
        autosaveTimer -= dt;
        if (autosaveTimer <= 0f) {
            autosaveTimer = AUTOSAVE_INTERVAL;
            saveAll();
        }
```

- [ ] **Step 7: Autosave on entering PAUSED**

In `Game.java`, find every transition that sets `state = State.PAUSED;` (line ~196). Replace each occurrence of the bare:

```java
                state = State.PAUSED;
```

with:

```java
                state = State.PAUSED;
                saveAll();
```

(There is one such assignment in the pause-key handler around line 196. Apply to that line.)

- [ ] **Step 8: Autosave on quit, after the game loop**

In `Game.java`, after the `while (!window.shouldClose())` loop body ends and before `loader.shutdown();` (line 752), add:

```java
        saveAll();
        save.flushAndAwait();
```

- [ ] **Step 9: Compile the whole project (Task 1 Step 2 command)**

Expected: `javac exit=0` (Task 7's call-site error is now resolved).

- [ ] **Step 10: Commit Task 7 + Task 8 together (they form one green build)**

```bash
git add src/main/java/com/mineclone/world/ChunkLoader.java src/main/java/com/mineclone/game/Game.java
git commit -m "feat(save): load/create world from level.dat, snapshot delta-patch, autosave on pause/timer/quit"
```

---

## Task 9: Manual integration verification

No automated harness can drive GLFW/OpenGL; this is the spec §13 checklist.

- [ ] **Step 1: Clean any stale save**

Run (PowerShell): `Remove-Item -Recurse -Force saves -ErrorAction SilentlyContinue; "clean"`

- [ ] **Step 2: First run — build a cross-chunk structure, then quit**

Run: `.\run.ps1`
In game: walk away from spawn, place a recognizable line of blocks crossing at least one chunk border (≥16 blocks), place one TORCH, place one WATER source and let it spread, note your position. Close the window (X) — this triggers `saveAll()` + `flushAndAwait()`.
Expected: `saves/world/level.dat` and one or more `saves/world/chunks/c.*.dat` now exist (`Get-ChildItem -Recurse saves`).

- [ ] **Step 3: Second run — verify restoration**

Run: `.\run.ps1`
Expected: spawn at the same position/orientation, same time-of-day; the block line is intact across the chunk border; the torch still emits light; the water is in the same resting shape with **no re-flood**, including across the chunk border.

- [ ] **Step 4: Edit-after-load persists**

In the second run: break part of the structure, wait >2 minutes (periodic autosave) OR open pause (autosave on pause), then quit. Run `.\run.ps1` a third time.
Expected: the break is persisted.

- [ ] **Step 5: Record result**

If all of Steps 2–4 hold, the persistence subsystem is verified. If water re-floods on load, or blocks/position are lost, stop and debug before declaring done (see spec §6: restored water must not be re-enqueued into the simulator — confirm `World.setBlock` is the only `modified` setter and `restore()` bypasses it).

---

## Self-Review

**Spec coverage (spec §§1–7,11–13):**
- §3 whole-chunk snapshot, GZIP, per-world dir, default id `world` → Tasks 1,3.
- §4 file layout + magic/version → Tasks 1,3.
- §5 `com.mineclone.save` API (`SaveManager`,`LevelData`,`ChunkSnapshot`; `Options` deferred to Plan 2 per stated deviation) → Tasks 1–3.
- §6 `populated`/dirty tracking → realized as single `modified` flag (Task 5/6) set only via `World.setBlock` post-gen; generation uses `Chunk.set` so no `populated` flag is needed (simpler, equivalent). Snapshot apply + sky-light recompute → Task 7. Light recomputed not persisted → Task 7 (`computeSkyLight`); block-light via existing dirty/flood path. Water resting (not re-flowed) → guaranteed because `restore()` writes arrays directly and never calls `World.setBlock`, so the simulator is never fed restored cells (Task 7 + verified Task 9 Step 3).
- §7 saveAll on pause/periodic/quit → Task 8 Steps 5–8. Unload-flush → documented deviation (no unload path exists); saveAll covers all loaded modified chunks.
- §11 isolation: all I/O in `com.mineclone.save`; engine touches only the API → file structure honored.
- §13 verification → Task 4 (headless) + Task 9 (manual checklist).

**Placeholder scan:** none — every code step shows complete code; every run step shows the exact command and expected output.

**Type consistency:** `SaveManager` methods (`hasSave`,`loadLevel`,`saveLevel`,`saveChunkAsync`,`saveChunkBlocking`,`loadChunk`,`deleteWorld`,`flushAndAwait`) are used with identical signatures in Tasks 4,7,8. `ChunkSnapshot(int,int,byte[],byte[])` and fields `cx,cz,blocks,meta` consistent across Tasks 2,3,4,7,8. `LevelData` ctor arg order `(seed,px,py,pz,yaw,pitch,timeOfDay,selectedSlot)` identical in Tasks 2,3,4,8. `Chunk.copyBlocks/copyMeta/restore/modified` defined Task 5, used Tasks 7,8. `ChunkLoader` new ctor `(World,ChunkMesher,SaveManager,String)` defined Task 7, called Task 8 Step 2.

**Known follow-ups (out of scope, not gaps):** chunk eviction + flush-on-unload; Plan 2 (menu world, title, stone buttons, Continue/New World screens, `Options` settings persistence).
