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
