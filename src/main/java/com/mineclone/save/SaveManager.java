package com.mineclone.save;

import com.mineclone.core.AppPaths;
import com.mineclone.world.BlockType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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

    public static final class WorldInfo {
        public final String id;
        public final String displayName;
        public final long seed;
        public final long lastPlayed;
        public final boolean corrupted;

        WorldInfo(String id, String displayName, long seed, long lastPlayed, boolean corrupted) {
            this.id = id;
            this.displayName = displayName;
            this.seed = seed;
            this.lastPlayed = lastPlayed;
            this.corrupted = corrupted;
        }
    }

    private final File savesRoot;
    private final ExecutorService chunkWriter =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mineclone-save");
                t.setDaemon(true);
                return t;
            });

    public SaveManager() {
        this(AppPaths.file(SaveFormat.SAVES_ROOT));
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
    private File optionsFile() { return new File(savesRoot.getParentFile() != null
            ? savesRoot.getParentFile() : new File("."), SaveFormat.OPTIONS_FILE); }

    public boolean hasSave(String id) {
        return levelFile(id).isFile();
    }

    /**
     * Reads only MAGIC, VERSION, name (v4+), and seed from level.dat.
     * Returns a WorldInfo with {@code corrupted=true} on any error.
     */
    public WorldInfo loadWorldInfo(String id) {
        File f = levelFile(id);
        if (!f.isFile()) return new WorldInfo(id, "World", 0L, 0L, true);
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(f))))) {
            if (in.readInt() != SaveFormat.MAGIC) return new WorldInfo(id, "World", 0L, 0L, true);
            int version = in.readInt();
            if (version < 1 || version > SaveFormat.LEVEL_VERSION)
                return new WorldInfo(id, "World", 0L, 0L, true);
            String name = (version >= 4) ? in.readUTF() : "";
            long seed = in.readLong();
            long lastPlayed = (version >= 5) ? in.readLong() : 0L;
            if (name.isEmpty()) name = "World";
            return new WorldInfo(id, name, seed, lastPlayed, false);
        } catch (IOException e) {
            return new WorldInfo(id, "World", 0L, 0L, true);
        }
    }

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
                list.add(new WorldInfo(d.getName(), "World", 0L, 0L, true));
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

    // ---- level.dat ----

    public void saveLevel(String id, LevelData d) {
        try {
            writeGzipAtomic(levelFile(id), o -> {
                o.writeInt(SaveFormat.MAGIC);
                o.writeInt(SaveFormat.LEVEL_VERSION);
                o.writeUTF(d.name);
                o.writeLong(d.seed);
                o.writeLong(d.lastPlayed);
                o.writeFloat(d.health);
                o.writeDouble(d.px); o.writeDouble(d.py); o.writeDouble(d.pz);
                o.writeDouble(d.spawnX); o.writeDouble(d.spawnY); o.writeDouble(d.spawnZ);
                o.writeFloat(d.yaw); o.writeFloat(d.pitch);
                o.writeFloat(d.timeOfDay);
                o.writeInt(d.selectedSlot);
                o.writeInt(d.gameMode.ordinal());            // v6
                o.writeInt(d.inventory.length);
                for (com.mineclone.world.ItemStack s : d.inventory) {   // v6: type + count
                    if (s == null) {
                        o.writeByte(com.mineclone.world.BlockType.AIR.ordinal());
                        o.writeShort(0);
                    } else {
                        o.writeByte(s.type.ordinal());
                        o.writeShort(s.count);
                    }
                }
            });
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
            int version = in.readInt();
            if (version < 1 || version > SaveFormat.LEVEL_VERSION) return null;
            String name = (version >= 4) ? in.readUTF() : "";
            long seed = in.readLong();
            long lastPlayed = (version >= 5) ? in.readLong() : 0L;
            float health = version >= 7 ? in.readFloat() : 20f;
            double px = in.readDouble(), py = in.readDouble(), pz = in.readDouble();
            double spawnX = 8.5, spawnY = 80.0, spawnZ = 8.5;
            if (version >= 2) {
                spawnX = in.readDouble();
                spawnY = in.readDouble();
                spawnZ = in.readDouble();
            }
            float yaw = in.readFloat(), pitch = in.readFloat();
            float tod = in.readFloat();
            int slot = in.readInt();

            com.mineclone.world.GameMode gameMode =
                    (version >= 6) ? com.mineclone.world.GameMode.byOrdinalSafe(in.readInt())
                                   : com.mineclone.world.GameMode.CREATIVE;

            com.mineclone.world.ItemStack[] inventory = LevelData.emptyInventory();
            if (version >= 6) {
                int n = Math.max(0, Math.min(256, in.readInt()));
                com.mineclone.world.ItemStack[] tmp =
                        new com.mineclone.world.ItemStack[Math.max(com.mineclone.world.Inventory.SIZE, n)];
                for (int i = 0; i < n; i++) {
                    int blockId = in.readUnsignedByte();
                    int count = in.readShort();
                    if (count > 0 && blockId > 0 && blockId < BlockType.VALUES.length)
                        tmp[i] = new com.mineclone.world.ItemStack(BlockType.VALUES[blockId], count);
                }
                inventory = tmp;
            } else if (version >= 3) {
                // old format: BlockType[] with implicit count = 1
                int n = Math.max(0, Math.min(128, in.readInt()));
                com.mineclone.world.ItemStack[] tmp =
                        new com.mineclone.world.ItemStack[Math.max(com.mineclone.world.Inventory.SIZE, n)];
                for (int i = 0; i < n; i++) {
                    int blockId = in.readUnsignedByte();
                    if (blockId > 0 && blockId < BlockType.VALUES.length)
                        tmp[i] = new com.mineclone.world.ItemStack(BlockType.VALUES[blockId], 1);
                }
                inventory = tmp;
            }

            return new LevelData(name, seed, px, py, pz, spawnX, spawnY, spawnZ,
                    yaw, pitch, tod, slot, inventory, gameMode, lastPlayed, health);
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
        try {
            writeGzipAtomic(chunkFile(id, s.cx, s.cz), o -> {
                o.writeInt(SaveFormat.MAGIC);
                o.writeInt(SaveFormat.CHUNK_VERSION);
                o.write(s.blocks, 0, SaveFormat.CHUNK_VOLUME);
                o.write(s.meta, 0, SaveFormat.CHUNK_VOLUME);
            });
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

    /** Renames a world's display name in its level.dat without touching any chunk files. */
    public void renameWorld(String id, String newName) {
        LevelData d = loadLevel(id);
        if (d == null) return;
        saveLevel(id, new LevelData(newName, d.seed,
                d.px, d.py, d.pz, d.spawnX, d.spawnY, d.spawnZ,
                d.yaw, d.pitch, d.timeOfDay, d.selectedSlot,
                d.inventory, d.gameMode, d.lastPlayed, d.health));
    }

    // ---- options.dat (global, not per-world) ----

    /** @return loaded options, or {@link Options#defaults()} if absent/unreadable/incompatible. */
    public Options loadOptions() {
        File f = optionsFile();
        if (!f.isFile()) return Options.defaults();
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(f))))) {
            if (in.readInt() != SaveFormat.MAGIC) return Options.defaults();
            int version = in.readInt();
            if (version < 1 || version > SaveFormat.OPTIONS_VERSION) return Options.defaults();
            int rr = in.readInt();
            int fov = in.readInt();
            float br = in.readFloat();
            float vol = in.readFloat();
            if (version == 1) {
                return new Options(rr, fov, br, vol, 0, true, false, true, 1.0f, false, 1.0f, 1.0f, 0);
            }
            // v2+
            int maxFps = in.readInt();
            boolean vsync = in.readBoolean();
            boolean fullscreen = in.readBoolean();
            boolean viewBobbing = in.readBoolean();
            float sensitivity = in.readFloat();
            boolean invertY = in.readBoolean();
            float musicVol = in.readFloat();
            float effectsVol = in.readFloat();
            if (version == 2) {
                return new Options(rr, fov, br, vol, maxFps, vsync, fullscreen, viewBobbing,
                        sensitivity, invertY, musicVol, effectsVol, 0);
            }
            // v3
            int guiScale = in.readInt();
            return new Options(rr, fov, br, vol, maxFps, vsync, fullscreen, viewBobbing,
                    sensitivity, invertY, musicVol, effectsVol, guiScale);
        } catch (IOException e) {
            System.err.println("loadOptions failed: " + e.getMessage());
            return Options.defaults();
        }
    }

    public void saveOptions(Options o) {
        try {
            writeGzipAtomic(optionsFile(), out -> {
                out.writeInt(SaveFormat.MAGIC);
                out.writeInt(SaveFormat.OPTIONS_VERSION);
                out.writeInt(o.renderRadius);
                out.writeInt(o.fovDegrees);
                out.writeFloat(o.brightness);
                out.writeFloat(o.masterVolume);
                out.writeInt(o.maxFps);
                out.writeBoolean(o.vsync);
                out.writeBoolean(o.fullscreen);
                out.writeBoolean(o.viewBobbing);
                out.writeFloat(o.mouseSensitivity);
                out.writeBoolean(o.invertMouseY);
                out.writeFloat(o.musicVolume);
                out.writeFloat(o.effectsVolume);
                out.writeInt(o.guiScale);
            });
        } catch (IOException e) {
            System.err.println("saveOptions failed: " + e.getMessage());
        }
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        if (!f.delete() && f.exists())
            System.err.println("deleteWorld: failed to delete " + f.getPath());
    }

    /** Body that writes the gzip-compressed payload of a save file. */
    private interface Writer { void write(DataOutputStream o) throws IOException; }

    /**
     * Crash-safe write: stream the payload into a sibling {@code *.tmp} file,
     * then atomically rename it over {@code target}. A crash or power loss
     * mid-write leaves the old file intact instead of a half-written, corrupt
     * one. Falls back to a plain replace where the filesystem can't do an
     * atomic move (e.g. across volumes).
     */
    private static void writeGzipAtomic(File target, Writer body) throws IOException {
        File dir = target.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = File.createTempFile(target.getName() + "-", ".tmp", dir);
        try {
            try (DataOutputStream o = new DataOutputStream(new GZIPOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp))))) {
                body.write(o);
            }
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (tmp.exists() && !tmp.delete())
                tmp.deleteOnExit();
        }
    }

    /** Block until queued chunk writes finish. Safe to call multiple times. */
    public void flushAndAwait() {
        try {
            chunkWriter.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            System.err.println("flush save queue failed: " + e.getMessage());
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("flush save queue timed out");
        }
    }
}
