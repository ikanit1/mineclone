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
 * file layout. Level and chunk snapshots are written on one ordered background
 * queue after the session backup. Level reads await earlier writes for that
 * world; snapshot capture itself stays on the caller's thread.
 */
public final class SaveManager {

    public static final class WorldInfo {
        public final String id;
        public final String displayName;
        public final long seed;
        public final long lastPlayed;
        public final boolean corrupted;
        public final boolean tooNew;
        /** Режим игры; у повреждённого сейва — выживание, но он и не играется. */
        public final com.mineclone.world.GameMode mode;
        /** Игровое время — по нему список показывает номер суток. */
        public final float timeOfDay;
        /** Сколько мир занимает на диске, байты. */
        public final long sizeBytes;
        /** Есть ли у мира снимок-превью. */
        public final boolean hasIcon;

        WorldInfo(String id, String displayName, long seed, long lastPlayed, boolean corrupted, boolean tooNew,
                  com.mineclone.world.GameMode mode, float timeOfDay, long sizeBytes, boolean hasIcon) {
            this.id = id;
            this.displayName = displayName;
            this.seed = seed;
            this.lastPlayed = lastPlayed;
            this.corrupted = corrupted;
            this.tooNew = tooNew;
            this.mode = mode;
            this.timeOfDay = timeOfDay;
            this.sizeBytes = sizeBytes;
            this.hasIcon = hasIcon;
        }

        static WorldInfo corrupted(String id, long size) {
            return new WorldInfo(id, UNNAMED, 0L, 0L, true, false,
                    com.mineclone.world.GameMode.SURVIVAL, 0f, size, false);
        }

        static WorldInfo tooNew(String id, long size) {
            return new WorldInfo(id, UNNAMED, 0L, 0L, false, true,
                    com.mineclone.world.GameMode.SURVIVAL, 0f, size, false);
        }

        public boolean playable() { return !corrupted && !tooNew; }
    }

    /** Имя мира, у которого в level.dat пусто. */
    static final String UNNAMED = "Мир";

    private final File savesRoot;
    private final WorldBackups backups;
    private final Object queueLock = new Object();
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<WorldBackups.Backup>> sessions =
            new java.util.HashMap<>();
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<Void>> pendingLevels =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Eviction may be followed by reload before the ordered writer catches up.
    // Keep the newest detached checkpoint visible until its own write succeeds.
    private final java.util.Map<java.nio.file.Path, ChunkSnapshot> pendingChunks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.nio.file.Path, java.util.concurrent.CompletableFuture<Void>> pendingGuests =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<java.nio.file.Path> checkedGuests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<java.nio.file.Path> protectedGuests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> legacyPlayerLevels = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile Thread writerThread;
    private final java.util.concurrent.atomic.LongAdder completedWrites = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder writtenBytes = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder writeNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder failedWrites = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.AtomicLong lastWriteNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong maxWriteNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.Map<String, Integer> levelVersions = new java.util.HashMap<>();
    // A failed read latches protection for this manager's lifetime. Repair must
    // reopen the world, so an old generation worker cannot overwrite a repair.
    private final java.util.Set<String> protectedLevels = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> checkedLevels = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<java.nio.file.Path> protectedChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<java.nio.file.Path> checkedChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentLinkedQueue<WorldWarning> warnings =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService chunkWriter =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mineclone-save");
                t.setDaemon(true);
                writerThread = t;
                return t;
            });

    public SaveManager() {
        this(defaultRoot());
    }

    /**
     * Каталог сейвов. {@code -Dmineclone.savesDir} уводит в сторону и миры, и
     * options.dat рядом с ними: автопилот не должен трогать миры игрока.
     */
    private static File defaultRoot() {
        String dir = System.getProperty("mineclone.savesDir");
        return dir != null && !dir.isBlank() ? new File(dir) : AppPaths.file(SaveFormat.SAVES_ROOT);
    }

    /** Test seam: point the manager at an arbitrary saves root. */
    public SaveManager(File savesRoot) {
        this.savesRoot = savesRoot;
        this.backups = new WorldBackups(savesRoot.toPath());
    }

    public void setBackupRetention(int count) { backups.setRetention(count); }

    /** Atomic gzip I/O only; queue wait, capture and ZIP backups are measured separately. */
    public record WriteMetrics(long completedWrites, long compressedBytes, long totalNanos,
                               long lastNanos, long maxNanos, long failedWrites) {}

    public WriteMetrics writeMetrics() {
        return new WriteMetrics(completedWrites.sum(), writtenBytes.sum(), writeNanos.sum(),
                lastWriteNanos.get(), maxWriteNanos.get(), failedWrites.sum());
    }

    /** Begin each explicit world opening once, before chunk recovery or any writes. */
    public java.util.concurrent.CompletableFuture<WorldBackups.Backup> beginWorldSession(String id) {
        return beginWorldSession(id, "session");
    }

    public java.util.concurrent.CompletableFuture<WorldBackups.Backup> beginWorldSession(String id, String reason) {
        synchronized (queueLock) {
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                synchronized (this) {
                    LevelLoad load = readLevelChecked(id);
                    if (load instanceof LevelLoad.Absent) return null;
                    if (!(load instanceof LevelLoad.Loaded))
                        throw new java.util.concurrent.CompletionException(new IOException("cannot back up unreadable world: " + load));
                    try {
                        return backups.snapshot(id, levelVersions.getOrDefault(id, SaveFormat.LEVEL_VERSION)
                                < SaveFormat.LEVEL_VERSION || legacyPlayerLevels.contains(id) ? "migration" : reason);
                    } catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
                }
            }, chunkWriter);
            sessions.put(id, future);
            return future;
        }
    }

    private java.util.concurrent.CompletableFuture<WorldBackups.Backup> sessionBackup(String id) {
        synchronized (queueLock) {
            var future = sessions.get(id);
            return future != null ? future : beginWorldSession(id);
        }
    }

    /** Queued with saves: all earlier queued writes finish before this snapshot starts. */
    public java.util.concurrent.CompletableFuture<WorldBackups.Backup> backupWorld(String id, String reason) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                try { return backups.snapshot(id, reason); }
                catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
            }
        }, chunkWriter);
    }

    public java.util.List<WorldBackups.Backup> listBackups(String id) throws IOException { return backups.list(id); }

    public java.util.concurrent.CompletableFuture<String> restoreBackup(String id, WorldBackups.Backup backup) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                try { return backups.restore(id, backup); }
                catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
            }
        }, chunkWriter);
    }

    private static void awaitBackup(java.util.concurrent.CompletableFuture<WorldBackups.Backup> future) throws IOException {
        try { future.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("backup interrupted", e); }
        catch (java.util.concurrent.ExecutionException e) { throw new IOException("backup failed; refusing write", e.getCause()); }
    }

    private String playerIdentity;

    public synchronized String playerId() {
        if (playerIdentity != null) return playerIdentity;
        File file = new File(optionsFile().getParentFile(), "player-id.txt");
        try {
            if (!file.isFile()) {
                Files.createDirectories(file.toPath().toAbsolutePath().getParent());
                try {
                    Files.writeString(file.toPath(), java.util.UUID.randomUUID().toString(),
                            java.nio.file.StandardOpenOption.CREATE_NEW);
                } catch (java.nio.file.FileAlreadyExistsException ignored) { }
            }
            playerIdentity = java.util.UUID.fromString(Files.readString(file.toPath()).trim()).toString();
            return playerIdentity;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot read persistent player identity: " + file, e);
        }
    }

    private File guestFile(String world, String id) {
        String safeId = java.util.UUID.fromString(id).toString();
        return new File(new File(worldDir(world), "players"), safeId + ".dat");
    }

    public com.mineclone.net.PlayerData loadGuest(String world, String id) {
        PlayerRecord record = loadGuestRecord(world, id);
        return record == null ? null : new com.mineclone.net.PlayerData(record);
    }

    public void saveGuest(String world, String id, com.mineclone.net.PlayerData data) {
        saveGuestRecord(world, id, data.record());
    }

    /**
     * A guest's checkpoint, or null if this world has never seen the guest.
     *
     * @throws IllegalStateException if the file exists but cannot be read; the
     *         file is then protected and this manager will never overwrite it
     */
    public PlayerRecord loadGuestRecord(String world, String id) {
        File file = guestFile(world, id);
        if (Thread.currentThread() != writerThread) {
            var pending = pendingGuests.get(file.toPath());
            if (pending != null) {
                try { pending.join(); }
                catch (java.util.concurrent.CompletionException ignored) { /* inspect the preserved file */ }
            }
        }
        synchronized (this) {
            try { return readGuestRecord(file); }
            catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Cannot load player " + id, error);
            }
        }
    }

    /**
     * Guest file: {@code MAGIC, version}. Version 1 holds a protocol-v7
     * checkpoint; version 2 adds {@code minReader} and the record sections.
     */
    private PlayerRecord readGuestRecord(File file) throws IOException {
        var path = file.toPath();
        checkedGuests.add(path);
        if (Files.notExists(path)) return null;
        try (var raw = new FileInputStream(file); var in = new DataInputStream(new GZIPInputStream(raw))) {
            if (in.readInt() != SaveFormat.MAGIC) throw new IOException("invalid player file magic");
            int version = in.readInt();
            PlayerRecord result;
            if (version == SaveFormat.GUEST_V1) {
                byte[] bytes = in.readNBytes(SaveFormat.GUEST_V1_MAX_BYTES + 1);
                if (bytes.length > SaveFormat.GUEST_V1_MAX_BYTES) throw new IOException("player checkpoint too large");
                var buffer = com.mineclone.net.PacketBuf.reading(bytes);
                var data = com.mineclone.net.PlayerData.read(buffer);
                if (data == null || buffer.hasMore()) throw new IOException("invalid legacy player checkpoint");
                result = data.record();
            } else if (version >= SaveFormat.GUEST_VERSION) {
                int minReader = in.readInt();
                if (minReader < 1 || minReader > version) throw new IOException("invalid player minimum reader");
                if (minReader > SaveFormat.GUEST_VERSION)
                    throw new IOException("player file requires reader " + minReader);
                result = PlayerRecordCodec.read(in);
            } else {
                throw new IOException("invalid player file version " + version);
            }
            requireEnd(in);
            for (String warning : result.warnings())
                System.err.println("player " + path.getFileName() + ": " + warning);
            return result;
        } catch (IOException | RuntimeException failure) {
            protectedGuests.add(path);
            throw failure;
        }
    }

    /**
     * Queue a guest's checkpoint behind the session backup. The first write for
     * a file reads it first; a file that failed to read is never replaced.
     */
    public void saveGuestRecord(String world, String id, PlayerRecord record) {
        File file = guestFile(world, id);
        var path = file.toPath();
        final byte[] bytes;
        try { bytes = PlayerRecordCodec.encode(record); }
        catch (IOException failure) { throw new IllegalArgumentException("Cannot encode player", failure); }
        synchronized (queueLock) {
            var backup = sessionBackup(world);
            var write = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    awaitBackup(backup);
                    synchronized (this) {
                        if (!checkedGuests.contains(path)) readGuestRecord(file);
                        if (protectedGuests.contains(path)) throw new IOException("player failed its read check");
                        writeGzipAtomic(file, out -> {
                            out.writeInt(SaveFormat.MAGIC);
                            out.writeInt(SaveFormat.GUEST_VERSION);
                            out.writeInt(SaveFormat.GUEST_VERSION); // minimum reader
                            out.write(bytes);
                        });
                    }
                } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            }, chunkWriter);
            pendingGuests.put(path, write);
            write.whenComplete((ignored, failure) -> {
                // Only a completed write leaves the barrier; a newer queued one stays.
                pendingGuests.remove(path, write);
                if (failure != null) System.err.println("saveGuest refused: " + failure.getCause());
            });
        }
    }

    private File worldDir(String id) { return new File(savesRoot, id); }
    private File levelFile(String id) { return new File(worldDir(id), SaveFormat.LEVEL_FILE); }
    private File chunksDir(String id) { return new File(worldDir(id), SaveFormat.CHUNKS_DIR); }
    private File chunkFile(String id, int cx, int cz) {
        return new File(chunksDir(id), SaveFormat.chunkFileName(cx, cz));
    }
    private File iconFile(String id) { return new File(worldDir(id), SaveFormat.ICON_FILE); }
    private File ledgerFile(String id) { return new File(chunksDir(id), SaveFormat.LEDGER_FILE); }
    private File optionsFile() { return new File(savesRoot.getParentFile() != null
            ? savesRoot.getParentFile() : new File("."), SaveFormat.OPTIONS_FILE); }

    public boolean hasSave(String id) {
        awaitLevelWrite(id);
        return levelFile(id).isFile();
    }

    /**
     * Сводка мира для списка: имя, сид, режим, сутки, размер, превью.
     * Нечитаемый level.dat даёт {@code corrupted=true}, а не исключение —
     * один битый сейв не должен прятать остальные миры.
     */
    public WorldInfo loadWorldInfo(String id) {
        return loadWorldInfo(id, true);
    }

    private WorldInfo loadWorldInfo(String id, boolean withSize) {
        long size = withSize ? directorySize(worldDir(id)) : 0L;
        LevelLoad result = readLevel(id);
        if (result instanceof LevelLoad.TooNew)
            return WorldInfo.tooNew(id, size);
        if (!(result instanceof LevelLoad.Loaded loaded))
            return WorldInfo.corrupted(id, size);
        LevelData d = loaded.data();
        String name = d.name.isEmpty() ? UNNAMED : d.name;
        return new WorldInfo(id, name, d.seed, d.lastPlayed, false, false, d.gameMode, d.timeOfDay,
                size, iconFile(id).isFile());
    }

    /**
     * Все миры в saves/: каталог с level.dat — мир. Повреждённые тоже в
     * списке, последними. Остальные — от последнего сыгранного: мир, в
     * который играли вчера, нужен чаще, чем «Мир 2» по алфавиту.
     */
    public java.util.List<WorldInfo> listWorlds() {
        return listWorlds(true);
    }

    /**
     * @param withSizes считать ли размер на диске. Это обход всех файлов мира:
     *                  титулу и экрану создания размер не нужен, и платить за
     *                  него кадром при каждом открытии экрана незачем.
     */
    public java.util.List<WorldInfo> listWorlds(boolean withSizes) {
        // A caller may create a world and immediately refresh its menu.
        for (String id : pendingLevels.keySet()) awaitLevelWrite(id);
        java.util.List<WorldInfo> list = new java.util.ArrayList<>();
        File[] dirs = savesRoot.listFiles(File::isDirectory);
        if (dirs == null) return list;
        for (File d : dirs) {
            if (Files.notExists(new File(d, SaveFormat.LEVEL_FILE).toPath())) continue;
            try {
                list.add(loadWorldInfo(d.getName(), withSizes));
            } catch (Exception e) {
                list.add(WorldInfo.corrupted(d.getName(), 0L));
            }
        }
        list.sort((a, b) -> {
            if (a.playable() != b.playable()) return a.playable() ? -1 : 1;
            if (a.lastPlayed != b.lastPlayed) return Long.compare(b.lastPlayed, a.lastPlayed);
            return a.displayName.compareToIgnoreCase(b.displayName);
        });
        return list;
    }

    /** Сколько мир занимает на диске, байты. Обход всех файлов — не для кадра. */
    public long worldSize(String id) {
        return directorySize(worldDir(id));
    }

    /**
     * Размер каталога. Через {@code walkFileTree}, а не {@code File.length()} на
     * каждый файл: на Windows атрибуты приходят вместе с листингом каталога, и
     * тысяча чанков считается без тысячи отдельных запросов к файловой системе.
     */
    private static long directorySize(File dir) {
        if (!dir.exists())
            return 0L;
        long[] total = { 0L };
        try {
            Files.walkFileTree(dir.toPath(), new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path file, IOException e) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return total[0];
        }
        return total[0];
    }

    /** Свободный id каталога: base, а если занят — base_2, base_3… */
    public String uniqueWorldId(String base) {
        if (!pendingLevels.containsKey(base) && !worldDir(base).exists())
            return base;
        int n = 2;
        while (pendingLevels.containsKey(base + "_" + n) || worldDir(base + "_" + n).exists())
            n++;
        return base + "_" + n;
    }

    /**
     * Копия мира целиком: level.dat, чанки, превью. Имя получает пометку, а
     * время последнего входа — текущее, чтобы копия встала в списке первой,
     * там, где её и ищут после нажатия.
     *
     * @return id копии или null, если исходник не читается
     */
    public String duplicateWorld(String id) {
        LevelData d = loadLevel(id);
        if (d == null)
            return null;
        flushAndAwait();   // запись чанков из очереди обязана успеть в копию
        String copy = uniqueWorldId(SaveFormat.newWorldId());
        try {
            copyRecursive(worldDir(id), worldDir(copy));
        } catch (IOException e) {
            System.err.println("duplicateWorld failed: " + e.getMessage());
            deleteRecursive(worldDir(copy));
            return null;
        }
        saveLevel(copy, new LevelData(d.name + " (копия)", d.seed,
                d.spawnX, d.spawnY, d.spawnZ, d.timeOfDay, d.gameMode,
                System.currentTimeMillis(), d.player, d.extraSections));
        return copy;
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs())
                throw new IOException("cannot create " + dst);
            File[] kids = src.listFiles();
            if (kids != null)
                for (File k : kids)
                    copyRecursive(k, new File(dst, k.getName()));
        } else if (!src.getName().endsWith(".tmp")) {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- превью мира ----

    /**
     * Записать превью в фоне, в том же потоке, что и чанки. Массив
     * копируется: вызывающий волен переиспользовать свой.
     */
    /**
     * Мир без имени — это чужой мир, открытый по сети.
     *
     * <p>У участника своего сохранения нет и быть не должно: {@code worldId} у
     * него пустой, и всё, что идёт через него, обязано тихо ничего не делать, а
     * не падать на построении пути к файлу.
     */
    private static boolean nameless(String id) {
        return id == null || id.isEmpty();
    }

    public void saveIconAsync(String id, int w, int h, int[] argb) {
        if (nameless(id)) return;
        int[] px = argb.clone();
        var backup = sessionBackup(id);
        chunkWriter.submit(() -> {
            try { awaitBackup(backup); }
            catch (IOException e) { System.err.println("saveIcon refused: " + e.getMessage()); return; }
            File dir = worldDir(id);
            if (!dir.isDirectory())
                return;   // мир успели удалить, пока кадр ждал очереди
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, px, 0, w);
            try {
                File tmp = File.createTempFile("icon-", ".tmp", dir);
                try {
                    javax.imageio.ImageIO.write(img, "png", tmp);
                    Files.move(tmp.toPath(), iconFile(id).toPath(), StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    if (tmp.exists() && !tmp.delete())
                        tmp.deleteOnExit();
                }
            } catch (IOException e) {
                System.err.println("saveIcon failed: " + e.getMessage());
            }
        });
    }

    /** Превью мира или null, если его нет или файл не читается. */
    public java.awt.image.BufferedImage loadIcon(String id) {
        if (nameless(id)) return null;
        File f = iconFile(id);
        if (!f.isFile())
            return null;
        try {
            return javax.imageio.ImageIO.read(f);
        } catch (IOException e) {
            return null;
        }
    }

    // ---- level.dat ----

    public void saveLevel(String id, LevelData d) {
        if (nameless(id)) return;
        final byte[] payload;
        try {
            // Serialize while caller-owned inventory/components still belong to
            // this tick. The worker receives bytes, never mutable gameplay state.
            payload = sectionBytes(out -> writeLevelPayload(out, d));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("saveLevel serialization failed: " + e.getMessage());
            return;
        }
        synchronized (queueLock) {
            var backup = sessionBackup(id);
            var write = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    awaitBackup(backup);
                    synchronized (this) { writeLevelBytes(id, payload); }
                } catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
            }, chunkWriter);
            pendingLevels.put(id, write);
            write.whenComplete((ignored, failure) -> {
                if (failure != null) System.err.println("saveLevel refused: " + failure.getCause());
            });
        }
    }

    private void awaitLevelWrite(String id) {
        // Backup preparation/restoration is already ordered on this executor;
        // waiting for a later queued save from that worker would deadlock.
        if (Thread.currentThread() == writerThread) return;
        var pending = pendingLevels.get(id);
        if (pending != null) {
            try { pending.join(); }
            catch (java.util.concurrent.CompletionException ignored) { /* inspect preserved disk state */ }
        }
    }

    /** Staged restore has no previous world to back up; failures must abort publication. */
    synchronized void writeRestoredLevel(String id, LevelData d) throws IOException {
        writeLevelBytes(id, sectionBytes(out -> writeLevelPayload(out, d)));
    }

    private void writeLevelBytes(String id, byte[] payload) throws IOException {
        if (!checkedLevels.contains(id)) readLevelChecked(id);
        if (protectedLevels.contains(id)) {
            throw new IOException("world '" + id + "' failed its read check");
        }
        writeGzipAtomic(levelFile(id), o -> o.write(payload));
        levelVersions.put(id, SaveFormat.LEVEL_VERSION);
        legacyPlayerLevels.remove(id);
    }

    /** Имя секции инвентаря игрока. */
    private static final String SECTION_INVENTORY = "inventory";
    /** Секция стопок, которым некуда лечь: курсор открытого окна. */
    private static final String SECTION_PENDING = "pending";

    private static void writeLevelPayload(DataOutputStream o, LevelData d) throws IOException {
        o.writeInt(SaveFormat.MAGIC);
        o.writeInt(SaveFormat.LEVEL_VERSION);
        o.writeInt(SaveFormat.LEVEL_VERSION); // minReaderVersion
        o.writeUTF(com.mineclone.core.BuildInfo.VERSION);
        java.util.LinkedHashMap<String, byte[]> sections = new java.util.LinkedHashMap<>();
        sections.put("world", sectionBytes(out -> {
            out.writeUTF(d.name); out.writeLong(d.seed); out.writeLong(d.lastPlayed);
        }));
        sections.put(PlayerRecordCodec.LEVEL_MARKER, sectionBytes(out -> out.writeInt(PlayerRecordCodec.MAGIC)));
        sections.put("player", PlayerRecordCodec.encode(d.player));
        sections.put("world_spawn",sectionBytes(out->{out.writeDouble(d.spawnX);out.writeDouble(d.spawnY);out.writeDouble(d.spawnZ);}));
        sections.put("clock", com.mineclone.sim.WorldClock.fromSaved(d.timeOfDay, d.extraSections.get("clock")).encode());
        // Rules/worldgen evolve independently; preserve their full section payloads.
        byte[] rules = d.extraSections.getOrDefault("rules", new byte[Integer.BYTES]).clone();
        if (rules.length < Integer.BYTES) throw new IOException("invalid rules section");
        java.nio.ByteBuffer.wrap(rules).putInt(d.gameMode.ordinal());
        sections.put("rules", rules);
        // A level without the section predates generator versions: its land is V1.
        sections.put(com.mineclone.world.gen.WorldGenSettings.SAVE_SECTION, d.extraSections.getOrDefault(
                com.mineclone.world.gen.WorldGenSettings.SAVE_SECTION, com.mineclone.world.gen.WorldGenSettings.LEGACY.encode()));
        // Чужие секции идут последними и ровно теми байтами, что пришли.
        for (var e : d.extraSections.entrySet())
            if(!e.getKey().equals(PlayerRecord.LEGACY_PROGRESS_SECTION)
                    && !e.getKey().equals(SECTION_INVENTORY) && !e.getKey().equals(SECTION_PENDING))
                sections.putIfAbsent(e.getKey(), e.getValue());
        com.mineclone.data.SectionCodec.write(o, sections);
    }

    private static byte[] sectionBytes(Writer writer) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { writer.write(out); }
        return bytes.toByteArray();
    }

    private static byte[] stacksToBytes(com.mineclone.world.ItemStack[] stacks)
            throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(buf)) {
            com.mineclone.data.VarInt.write(o, stacks.length);
            for (com.mineclone.world.ItemStack s : stacks)
                ItemStackCodec.write(o, s);
        }
        return buf.toByteArray();
    }

    private static com.mineclone.world.ItemStack[] stacksFromBytes(byte[] bytes, int max)
            throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            int n = bounded(com.mineclone.data.VarInt.read(in), max, "stack count");
            com.mineclone.world.ItemStack[] out = new com.mineclone.world.ItemStack[n];
            for (int i = 0; i < n; i++)
                out[i] = ItemStackCodec.read(in);
            requireEnd(in);
            return out;
        }
    }

    /** Compatibility adapter. Opening a world must use {@link #readLevel}. */
    public LevelData loadLevel(String id) {
        LevelLoad result = readLevel(id);
        return result instanceof LevelLoad.Loaded loaded ? loaded.data() : null;
    }

    /** Inspection only: no directory creation, quarantine, or disk writes. */
    public LevelLoad readLevel(String id) {
        awaitLevelWrite(id);
        synchronized (this) { return readLevelChecked(id); }
    }

    private LevelLoad readLevelChecked(String id) {
        if (nameless(id)) return new LevelLoad.Absent();
        LevelLoad result = readLevelFile(id);
        checkedLevels.add(id);
        if (result instanceof LevelLoad.Unreadable || result instanceof LevelLoad.TooNew)
            protectedLevels.add(id);
        return result;
    }

    private LevelLoad readLevelFile(String id) {
        File f = levelFile(id);
        if (Files.notExists(f.toPath())) return new LevelLoad.Absent();
        try (var source = new BufferedInputStream(new FileInputStream(f));
             DataInputStream in = new DataInputStream(new GZIPInputStream(source))) {
            if (in.readInt() != SaveFormat.MAGIC) throw new IOException("invalid level magic");
            int version = in.readInt();
            if (version < 1) throw new IOException("invalid level version " + version);
            if (version >= 11) {
                int minReader = in.readInt();
                if (minReader > SaveFormat.LEVEL_VERSION)
                    return new LevelLoad.TooNew(version, SaveFormat.LEVEL_VERSION);
                if (minReader < 1) throw new IOException("invalid minimum reader version " + minReader);
                in.readUTF(); // writtenBy, informational; never execute or interpret it.
                var sections = com.mineclone.data.SectionCodec.read(in);
                LevelData data = readLevelSections(sections);
                requireEnd(in);
                LevelLoad.TooNew generatorTooNew = generatorTooNew(
                        sections.get(com.mineclone.world.gen.WorldGenSettings.SAVE_SECTION));
                if (generatorTooNew != null) return generatorTooNew;
                if (!sections.containsKey(PlayerRecordCodec.LEVEL_MARKER)) legacyPlayerLevels.add(id);
                levelVersions.put(id, version);
                return new LevelLoad.Loaded(data);
            }
            String name = (version >= 4) ? in.readUTF() : "";
            long seed = in.readLong();
            long lastPlayed = (version >= 5) ? in.readLong() : 0L;
            float health = version >= 7 ? in.readFloat() : 20f;
            float hunger = version >= 9 ? in.readFloat() : 20f;
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
            com.mineclone.world.ItemStack[] pending = new com.mineclone.world.ItemStack[0];
            java.util.LinkedHashMap<String, byte[]> extra = new java.util.LinkedHashMap<>();
            if (version >= 10) {
                int count = bounded(com.mineclone.data.VarInt.read(in), 256, "section count");
                int totalBytes = 0;
                java.util.Set<String> sectionNames = new java.util.HashSet<>();
                for (int i = 0; i < count; i++) {
                    String key = in.readUTF();
                    if (!sectionNames.add(key)) throw new IOException("duplicate section " + key);
                    int length = bounded(com.mineclone.data.VarInt.read(in), 16 * 1024 * 1024, "section bytes");
                    totalBytes += length;
                    if (totalBytes > 64 * 1024 * 1024) throw new IOException("level sections too large");
                    byte[] bytes = new byte[length];
                    in.readFully(bytes);
                    switch (key) {
                        case SECTION_INVENTORY -> inventory = stacksFromBytes(bytes, 256);
                        case SECTION_PENDING -> pending = stacksFromBytes(bytes, 256);
                        // Секция незнакома — значит её записала другая сборка.
                        // Выбросить её означало бы потерять чужие данные при
                        // первом же сохранении из этой.
                        default -> extra.put(key, bytes);
                    }
                }
            } else if (version >= 8) {
                int n = bounded(in.readInt(), 256, "inventory count");
                com.mineclone.world.ItemStack[] tmp =
                        new com.mineclone.world.ItemStack[Math.max(com.mineclone.world.Inventory.SIZE, n)];
                for (int i = 0; i < n; i++)
                    tmp[i] = ItemStackCodec.readLegacy(in);
                inventory = tmp;
            } else if (version >= 6) {
                int n = bounded(in.readInt(), 256, "inventory count");
                com.mineclone.world.ItemStack[] tmp =
                        new com.mineclone.world.ItemStack[Math.max(com.mineclone.world.Inventory.SIZE, n)];
                for (int i = 0; i < n; i++) {
                    int blockId = in.readUnsignedByte();
                    int count = in.readShort();
                    com.mineclone.item.Item item = com.mineclone.item.LegacyItems.block(blockId);
                    if (count > 0 && item != null)
                        tmp[i] = new com.mineclone.world.ItemStack(item, count);
                }
                inventory = tmp;
            } else if (version >= 3) {
                // old format: BlockType[] with implicit count = 1
                int n = bounded(in.readInt(), 128, "inventory count");
                com.mineclone.world.ItemStack[] tmp =
                        new com.mineclone.world.ItemStack[Math.max(com.mineclone.world.Inventory.SIZE, n)];
                for (int i = 0; i < n; i++) {
                    int blockId = in.readUnsignedByte();
                    com.mineclone.item.Item item = com.mineclone.item.LegacyItems.block(blockId);
                    if (item != null)
                        tmp[i] = new com.mineclone.world.ItemStack(item, 1);
                }
                inventory = tmp;
            }

            requireEnd(in);
            com.mineclone.sim.WorldClock.fromSaved(tod, extra.get("clock"));
            LevelLoad.TooNew generatorTooNew = generatorTooNew(
                    extra.get(com.mineclone.world.gen.WorldGenSettings.SAVE_SECTION));
            if (generatorTooNew != null) return generatorTooNew;
            levelVersions.put(id, version);
            return new LevelLoad.Loaded(new LevelData(name, seed, px, py, pz, spawnX, spawnY, spawnZ,
                    yaw, pitch, tod, slot, inventory, gameMode, lastPlayed, health, hunger,
                    pending, extra));
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return new LevelLoad.Unreadable(readReason(e));
        }
    }

    // ---- chunk snapshots ----

    /** Queues a detached chunk write; subsequent reads see this checkpoint even before disk catches up. */
    public void saveChunkAsync(String id, ChunkSnapshot s) {
        if (nameless(id)) return;
        ChunkSnapshot snapshot = copyChunkSnapshot(s);
        java.nio.file.Path target = chunkFile(id, s.cx, s.cz).toPath();
        synchronized (queueLock) {
            var backup = sessionBackup(id);
            pendingChunks.put(target, snapshot);
            chunkWriter.submit(() -> {
                try {
                    awaitBackup(backup);
                    if (saveChunkBlocking(id, snapshot))
                        // Completing an older write cannot hide a later queued edit.
                        pendingChunks.remove(target, snapshot);
                } catch (IOException e) { System.err.println("saveChunk refused: " + e.getMessage()); }
            });
        }
    }

    private static ChunkSnapshot copyChunkSnapshot(ChunkSnapshot source) {
        var chests = new java.util.LinkedHashMap<Integer, com.mineclone.world.ItemStack[]>();
        source.chests.forEach((key, slots) -> {
            var copy = new com.mineclone.world.ItemStack[slots.length];
            for (int i = 0; i < slots.length; i++) copy[i] = slots[i] == null ? null : slots[i].copy();
            chests.put(key, copy);
        });
        var furnaces = new java.util.LinkedHashMap<Integer, com.mineclone.world.Furnace>();
        source.furnaces.forEach((key, original) -> {
            var copy = new com.mineclone.world.Furnace();
            copy.input = original.input == null ? null : original.input.copy();
            copy.fuel = original.fuel == null ? null : original.fuel.copy();
            copy.output = original.output == null ? null : original.output.copy();
            copy.burnLeft = original.burnLeft; copy.burnMax = original.burnMax; copy.cook = original.cook;
            furnaces.put(key, copy);
        });
        var items = new java.util.ArrayList<com.mineclone.world.DroppedItem>();
        for (var item : source.items)
            items.add(new com.mineclone.world.DroppedItem(item.stack.copy(), item.x, item.y, item.z, item.age));
        return new ChunkSnapshot(source.cx, source.cz, source.blocks.clone(), source.meta.clone(),
                chests, furnaces, items, source.extra);
    }

    synchronized boolean saveChunkBlocking(String id, ChunkSnapshot s) {
        java.nio.file.Path target = chunkFile(id, s.cx, s.cz).toPath();
        if (!checkedChunks.contains(target)) readChunk(id, s.cx, s.cz);
        if (protectedChunks.contains(target) || protectedLevels.contains(id)) {
            System.err.println("saveChunk refused: protected chunk " + id + " " + s.cx + "," + s.cz);
            return false;
        }
        try {
            writeGzipAtomic(chunkFile(id, s.cx, s.cz), o -> {
                o.writeInt(SaveFormat.MAGIC);
                o.writeInt(SaveFormat.CHUNK_VERSION);
                o.writeInt(SaveFormat.CHUNK_VERSION); // minReaderVersion
                ChunkSectionCodec.write(o, s);
            });
            return true;
        } catch (IOException e) {
            System.err.println("saveChunk failed: " + e.getMessage());
            return false;
        }
    }

    /** Compatibility adapter; the streaming loader must inspect {@link #readChunk}. */
    public ChunkSnapshot loadChunk(String id, int cx, int cz) {
        ChunkLoad result = readChunk(id, cx, cz);
        return result instanceof ChunkLoad.Loaded loaded ? loaded.snapshot() : null;
    }

    private static DataInputStream requiredSection(java.util.Map<String, byte[]> sections, String name) throws IOException {
        byte[] bytes = sections.get(name);
        if (bytes == null) throw new IOException("missing level section " + name);
        return new DataInputStream(new java.io.ByteArrayInputStream(bytes));
    }

    private static LevelData readLevelSections(java.util.Map<String, byte[]> sections) throws IOException {
        String name;
        long seed, lastPlayed;
        try (var world = requiredSection(sections, "world")) {
            name = world.readUTF(); seed = world.readLong(); lastPlayed = world.readLong();
            requireEnd(world);
        }
        double px, py, pz, spawnX, spawnY, spawnZ;
        float yaw, pitch, health, hunger;
        int slot; PlayerRecord record=null;
        if(sections.containsKey(PlayerRecordCodec.LEVEL_MARKER)) {
            try(var marker=requiredSection(sections,PlayerRecordCodec.LEVEL_MARKER)) {
                if(marker.readInt()!=PlayerRecordCodec.MAGIC)throw new IOException("unsupported level player format");
                requireEnd(marker);
            }
            byte[] bytes=sections.get("player");if(bytes==null)throw new IOException("missing level player section");
            record=PlayerRecordCodec.decode(bytes);
            var pose=record.pose();var vitals=record.vitals();
            px=pose.x();py=pose.y();pz=pose.z();yaw=pose.yaw();pitch=pose.pitch();slot=pose.selected();
            health=vitals.health();hunger=vitals.hunger();
            try(var spawn=requiredSection(sections,"world_spawn")) {
                spawnX=spawn.readDouble();spawnY=spawn.readDouble();spawnZ=spawn.readDouble();requireEnd(spawn);
            }
        } else {
            // M0 v11 has an exact fixed payload. Never guess based on whether another parser succeeds.
            try (var player = requiredSection(sections, "player")) {
                px = player.readDouble(); py = player.readDouble(); pz = player.readDouble();
                spawnX = player.readDouble(); spawnY = player.readDouble(); spawnZ = player.readDouble();
                yaw = player.readFloat(); pitch = player.readFloat(); slot = player.readInt();
                health = player.readFloat(); hunger = player.readFloat();
                requireEnd(player);
            }
        }
        for (double value : new double[] { px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch, health, hunger })
            if (!Double.isFinite(value)) throw new IOException("non-finite player field");
        int mode;
        try (var rules = requiredSection(sections, "rules")) {
            mode = rules.readInt();
            // Future rule fields are opaque and survive through extraSections.
        }
        if (mode < 0 || mode >= com.mineclone.world.GameMode.values().length) throw new IOException("invalid game mode");
        byte[] clockBytes = sections.get("clock");
        if (clockBytes == null) throw new IOException("missing level section clock");
        var clock = com.mineclone.sim.WorldClock.fromSaved(0, clockBytes);
        java.util.Map<String, byte[]> extra = new java.util.LinkedHashMap<>(sections);
        extra.remove("world"); extra.remove("player"); extra.remove(SECTION_INVENTORY); extra.remove(SECTION_PENDING);
        extra.remove(PlayerRecordCodec.LEVEL_MARKER);extra.remove("world_spawn");
        if(record!=null) {
            extra.remove(PlayerRecord.LEGACY_PROGRESS_SECTION);
            return new LevelData(name,seed,spawnX,spawnY,spawnZ,clock.gameTimeFloat(),
                    com.mineclone.world.GameMode.values()[mode],lastPlayed,record,extra);
        }
        byte[] invBytes = sections.get(SECTION_INVENTORY);
        if (invBytes == null) throw new IOException("missing level section inventory");
        var inventory = stacksFromBytes(invBytes, 256);
        var pending = sections.containsKey(SECTION_PENDING) ? stacksFromBytes(sections.get(SECTION_PENDING), 256)
                : new com.mineclone.world.ItemStack[0];
        return new LevelData(name, seed, px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch,
                clock.gameTimeFloat(), slot, inventory, com.mineclone.world.GameMode.values()[mode], lastPlayed,
                health, hunger, pending, extra);
    }

    public synchronized ChunkLoad readChunk(String id, int cx, int cz) {
        if (nameless(id)) return new ChunkLoad.Absent();
        ChunkLoad result = readChunkFile(id, cx, cz);
        java.nio.file.Path path = chunkFile(id, cx, cz).toPath();
        checkedChunks.add(path);
        if (result instanceof ChunkLoad.Unreadable || result instanceof ChunkLoad.TooNew)
            protectedChunks.add(path);
        if (result instanceof ChunkLoad.TooNew newer)
            warn(new WorldWarning(id, cx, cz, WorldWarning.Kind.TOO_NEW,
                    "chunk version " + newer.version(), null));
        // Inspect disk first: a queued checkpoint must never disguise corruption
        // or a newer file and bypass the existing read-only/quarantine policy.
        ChunkSnapshot pending = pendingChunks.get(path);
        if (pending != null && !protectedChunks.contains(path) && !protectedLevels.contains(id))
            return new ChunkLoad.Loaded(copyChunkSnapshot(pending));
        return result;
    }

    private ChunkLoad readChunkFile(String id, int cx, int cz) {
        File f = chunkFile(id, cx, cz);
        if (Files.notExists(f.toPath())) return new ChunkLoad.Absent();
        try (var source = new BufferedInputStream(new FileInputStream(f));
             DataInputStream in = new DataInputStream(new GZIPInputStream(source))) {
            if (in.readInt() != SaveFormat.MAGIC) throw new IOException("invalid chunk magic");
            int version = in.readInt();
            // Первая версия читается как раньше: сундуков в ней просто нет.
            // Отказаться от неё значило бы выкинуть все правки во всех уже
            // сохранённых мирах.
            if (version < 1) throw new IOException("invalid chunk version " + version);
            if (version >= 7) {
                int minReader = in.readInt();
                if (minReader > SaveFormat.CHUNK_VERSION)
                    return new ChunkLoad.TooNew(version, SaveFormat.CHUNK_VERSION);
                if (minReader < 1) throw new IOException("invalid minimum chunk reader " + minReader);
                ChunkSnapshot snapshot = ChunkSectionCodec.read(in, cx, cz);
                requireEnd(in);
                return new ChunkLoad.Loaded(snapshot);
            }
            byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME];
            byte[] meta = new byte[SaveFormat.CHUNK_VOLUME];
            if (version >= 5) {
                RunLengthCodec.read(in, blocks);
                RunLengthCodec.read(in, meta);
            } else {
                in.readFully(blocks);
                in.readFully(meta);
            }
            java.util.Map<Integer, com.mineclone.world.ItemStack[]> chests =
                    new java.util.HashMap<>();
            if (version >= 2) {
                int n = bounded(in.readInt(), 4096, "chest count");
                for (int i = 0; i < n; i++) {
                    int key = in.readInt();
                    int len = in.readUnsignedByte();
                    com.mineclone.world.ItemStack[] slots =
                            new com.mineclone.world.ItemStack[len];
                    for (int k = 0; k < len; k++)
                        slots[k] = version >= 6 ? ItemStackCodec.read(in)
                                                : ItemStackCodec.readLegacy(in);
                    if (key >= 0 && key < SaveFormat.CHUNK_VOLUME)
                        chests.put(key, slots);
                }
            }
            java.util.Map<Integer, com.mineclone.world.Furnace> furnaces =
                    new java.util.HashMap<>();
            if (version >= 3) {
                int n = bounded(in.readInt(), 4096, "furnace count");
                for (int i = 0; i < n; i++) {
                    int key = in.readInt();
                    com.mineclone.world.Furnace furnace = new com.mineclone.world.Furnace();
                    furnace.input = readChunkStack(in, version);
                    furnace.fuel = readChunkStack(in, version);
                    furnace.output = readChunkStack(in, version);
                    furnace.burnLeft = in.readFloat();
                    furnace.burnMax = in.readFloat();
                    furnace.cook = in.readFloat();
                    if (key >= 0 && key < SaveFormat.CHUNK_VOLUME)
                        furnaces.put(key, furnace);
                }
            }
            java.util.List<com.mineclone.world.DroppedItem> items = new java.util.ArrayList<>();
            if (version >= 4) {
                int n = bounded(in.readInt(), 4096, "dropped item count");
                for (int i = 0; i < n; i++) {
                    com.mineclone.world.ItemStack st = readChunkStack(in, version);
                    float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
                    float age = in.readFloat();
                    if (st != null)
                        items.add(new com.mineclone.world.DroppedItem(st, x, y, z, age));
                }
            }
            requireEnd(in);
            return new ChunkLoad.Loaded(new ChunkSnapshot(cx, cz, blocks, meta, chests, furnaces, items));
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return new ChunkLoad.Unreadable(readReason(e));
        }
    }

    /**
     * Unedited land is regenerated on every load: opening a world whose generator
     * this build does not have would silently rewrite it. Null when the build has
     * it; a malformed section throws, so the level reads as damaged here rather
     * than failing later in whatever opens it.
     */
    private static LevelLoad.TooNew generatorTooNew(byte[] worldgen) {
        int stored = com.mineclone.world.gen.WorldGenSettings.storedVersion(worldgen);
        var version = com.mineclone.world.gen.WorldGenVersion.byId(stored);
        if (version == null) {
            var versions = com.mineclone.world.gen.WorldGenVersion.values();
            return new LevelLoad.TooNew("world generator version", stored, versions[versions.length - 1].id());
        }
        // A V2 change this build does not implement yet came from a newer build.
        // V1 never has any: such a section is contradictory, and decode refuses it.
        int features = com.mineclone.world.gen.WorldGenSettings.storedFeatures(worldgen);
        int supported = com.mineclone.world.gen.GenFeatures.of(version).bits();
        if (version != com.mineclone.world.gen.WorldGenVersion.V1 && (features & ~supported) != 0)
            return new LevelLoad.TooNew("world generator features", features, supported);
        com.mineclone.world.gen.WorldGenSettings.decode(worldgen);
        return null;
    }

    private static String readReason(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static int bounded(int value, int max, String field) throws IOException {
        if (value < 0 || value > max) throw new IOException("invalid " + field + ": " + value);
        return value;
    }

    private static void requireEnd(DataInputStream in) throws IOException {
        // Reading to EOF validates the gzip CRC/trailer as well as the payload.
        if (in.read() != -1) throw new IOException("unexpected trailing save data");
    }

    // ---- chunk ledger (GEN-02) ----

    /** Ledger bytes queued but not yet on disk: a read sees the newest. */
    private final java.util.Map<String, byte[]> pendingLedgers = new java.util.concurrent.ConcurrentHashMap<>();
    /** Worlds whose damaged ledger could not be moved aside: it stays unwritten this session. */
    private final java.util.Set<String> protectedLedgers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** No real ledger comes near this; more is damage, not a world. */
    private static final int MAX_LEDGER_BYTES = 64 * 1024 * 1024;

    /** The keys of every chunk the world saved: an upgrade pins the land around them. */
    public java.util.List<Long> savedChunkKeys(String id) {
        java.util.List<Long> keys = new java.util.ArrayList<>();
        if (nameless(id)) return keys;
        String[] names = chunksDir(id).list();
        if (names == null) return keys;
        for (String name : names) {
            if (!name.startsWith("c.") || !name.endsWith(".dat")) continue;
            String[] parts = name.substring(2, name.length() - 4).split("\\.");
            if (parts.length != 2) continue;
            try {
                keys.add(com.mineclone.world.World.key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            } catch (NumberFormatException notAChunk) { }
        }
        java.util.Collections.sort(keys);
        return keys;
    }

    /** Inspection only: a damaged ledger stays where it is until {@link #openLedger} moves it aside. */
    public synchronized LedgerLoad readLedger(String id) {
        if (nameless(id)) return new LedgerLoad.Absent();
        try {
            byte[] pending = pendingLedgers.get(id);
            if (pending != null) return new LedgerLoad.Loaded(com.mineclone.world.gen.ChunkLedger.decode(pending));
            File f = ledgerFile(id);
            if (Files.notExists(f.toPath())) return new LedgerLoad.Absent();
            byte[] bytes;
            try (var in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                bytes = in.readNBytes(MAX_LEDGER_BYTES + 1);
            }
            if (bytes.length > MAX_LEDGER_BYTES) throw new IOException("chunk ledger too large");
            return new LedgerLoad.Loaded(com.mineclone.world.gen.ChunkLedger.decode(bytes));
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return new LedgerLoad.Unreadable(readReason(e));
        }
    }

    /**
     * The world's ledger, ready to generate with; a world always opens. A
     * damaged file is moved aside as evidence ({@code ledger.dat.corrupt-<millis>})
     * after the session backup; when either fails, the file stays where it is
     * and this session never writes over it. A missing or damaged ledger of a
     * world that was ever upgraded is rebuilt conservatively — saved chunks, the
     * land around them and around the {@code anchors} (spawn, players) count as
     * V1 — because unedited land it no longer knows about would otherwise
     * regenerate with the new generator.
     */
    public com.mineclone.world.gen.ChunkLedger openLedger(String id, com.mineclone.world.gen.WorldGenSettings generator,
                                                          float[][] anchors) {
        LedgerLoad read = readLedger(id);
        if (read instanceof LedgerLoad.Loaded loaded) return loaded.ledger();
        if (read instanceof LedgerLoad.Unreadable unreadable) {
            try {
                moveLedgerAside(id);
                System.err.println("chunk ledger of " + id + " unreadable (" + unreadable.reason() + "); rebuilt");
            } catch (IOException e) {
                protectedLedgers.add(id);
                System.err.println("chunk ledger of " + id + " unreadable (" + unreadable.reason()
                        + ") and kept in place (" + e.getMessage() + "); rebuilt for this session only");
            }
        }
        var ledger = new com.mineclone.world.gen.ChunkLedger();
        if (generator.upgradedAt() > 0)
            com.mineclone.world.gen.WorldGenUpgrade.pinSeen(ledger, com.mineclone.world.gen.WorldGenVersion.V1,
                    savedChunkKeys(id), anchors);
        return ledger;
    }

    private void moveLedgerAside(String id) throws IOException {
        awaitBackup(sessionBackup(id));
        synchronized (this) {
            java.nio.file.Path source = ledgerFile(id).toPath();
            long stamp = System.currentTimeMillis();
            for (;;) {
                java.nio.file.Path evidence = source.resolveSibling(source.getFileName() + ".corrupt-" + stamp++);
                try {
                    Files.move(source, evidence);
                    return;
                } catch (java.nio.file.FileAlreadyExistsException collision) { }
            }
        }
    }

    /** Queues the ledger's bytes behind the session backup; reads see them at once. */
    public void saveLedgerAsync(String id, byte[] encoded) {
        if (nameless(id) || encoded == null) return;
        byte[] copy = encoded.clone();
        synchronized (queueLock) {
            var backup = sessionBackup(id);
            pendingLedgers.put(id, copy);
            chunkWriter.submit(() -> {
                try {
                    awaitBackup(backup);
                    synchronized (SaveManager.this) {
                        if (protectedLevels.contains(id))
                            throw new IOException("world '" + id + "' failed its read check");
                        if (protectedLedgers.contains(id))
                            throw new IOException("the damaged chunk ledger of '" + id + "' is kept in place");
                        writeGzipAtomic(ledgerFile(id), o -> o.write(copy));
                    }
                    pendingLedgers.remove(id, copy);
                } catch (IOException e) {
                    System.err.println("saveLedger refused: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Preserve the original bytes before allowing regenerated data to save.
     * A failed move leaves protection latched; the caller must use read-only mode.
     */
    public java.nio.file.Path quarantineChunk(String id, int cx, int cz) throws IOException {
        awaitBackup(sessionBackup(id));
        synchronized (this) { return quarantineChunkBlocking(id, cx, cz); }
    }

    private java.nio.file.Path quarantineChunkBlocking(String id, int cx, int cz) throws IOException {
        java.nio.file.Path source = chunkFile(id, cx, cz).toPath();
        protectedChunks.add(source);
        try {
            if (!(readChunkFile(id, cx, cz) instanceof ChunkLoad.Unreadable))
                throw new IOException("chunk is not unreadable; refusing quarantine");
            if (!Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IOException("source is not a regular chunk file");
            long stamp = System.currentTimeMillis();
            java.nio.file.Path evidence;
            for (;;) {
                evidence = source.resolveSibling(source.getFileName() + ".corrupt-" + stamp++);
                try {
                    // No REPLACE_EXISTING: an existing quarantine copy is never lost.
                    Files.move(source, evidence);
                    break;
                } catch (java.nio.file.FileAlreadyExistsException collision) { }
            }
            protectedChunks.remove(source);
            checkedChunks.add(source);
            warn(new WorldWarning(id, cx, cz, WorldWarning.Kind.QUARANTINED, "unreadable chunk", evidence));
            return evidence;
        } catch (IOException | SecurityException e) {
            warn(new WorldWarning(id, cx, cz, WorldWarning.Kind.QUARANTINE_FAILED, readReason(e), null));
            if (e instanceof IOException io) throw io;
            throw new IOException("quarantine denied", e);
        }
    }

    private void warn(WorldWarning warning) {
        String key = warning.worldId() + ":" + warning.cx() + ":" + warning.cz() + ":" + warning.kind();
        if (warned.add(key)) warnings.add(warning);
    }

    public java.util.List<WorldWarning> drainWorldWarnings(String id) {
        java.util.List<WorldWarning> result = new java.util.ArrayList<>();
        for (WorldWarning warning : warnings) {
            if (warning.worldId().equals(id) && warnings.remove(warning)) result.add(warning);
        }
        return result;
    }

    /** Стопка из чанка: с шестой версии — id предмета, раньше — размеченный слот. */
    private static com.mineclone.world.ItemStack readChunkStack(DataInputStream in, int version)
            throws IOException {
        return version >= 6 ? ItemStackCodec.read(in) : ItemStackCodec.readLegacy(in);
    }

    public void deleteWorld(String id) {
        flushAndAwait();
        deleteRecursive(worldDir(id));
        synchronized (queueLock) { sessions.remove(id); pendingLevels.remove(id); }
        pendingLedgers.remove(id);
        protectedLedgers.remove(id);
        java.nio.file.Path removed = chunksDir(id).toPath();
        pendingChunks.keySet().removeIf(path -> path.startsWith(removed));
    }

    /**
     * Renames a world's display name in its level.dat without touching any chunk files.
     * Сытость передаётся явно: конструктор без неё ставит полную, и переименование
     * молча кормило игрока.
     */
    public void renameWorld(String id, String newName) {
        LevelData d = loadLevel(id);
        if (d == null) return;
        saveLevel(id, d.withName(newName));
    }

    /** Метки слота инвентаря в формате уровня v8. */
    private static final int SLOT_EMPTY = 0;
    private static final int SLOT_BLOCK = 1;
    private static final int SLOT_TOOL = 2;
    private static final int SLOT_FOOD = 3;

    // ---- options.dat (global, not per-world) ----

    /**
     * Размеченный хвост настроек: «имя, вид, значение».
     *
     * <p>Новая настройка не поднимает версию файла, а незнакомую читатель
     * пропускает по виду значения — ровно так же, как секции level.dat.
     * Раньше каждое поле было позицией в потоке, и добавить одно значило
     * написать ещё одну ветку чтения для каждой прошлой версии.
     */
    private static final byte KIND_BOOL = 0, KIND_INT = 1, KIND_FLOAT = 2, KIND_STRING = 3;

    private static void putBool(DataOutputStream out, String key, boolean v) throws IOException {
        out.writeUTF(key); out.writeByte(KIND_BOOL); out.writeBoolean(v);
    }

    private static void putInt(DataOutputStream out, String key, int v) throws IOException {
        out.writeUTF(key); out.writeByte(KIND_INT); out.writeInt(v);
    }

    private static void putString(DataOutputStream out, String key, String v) throws IOException {
        out.writeUTF(key); out.writeByte(KIND_STRING); out.writeUTF(v == null ? "" : v);
    }

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
                return new Options(rr, fov, br, vol, 0, true, false, true, 1.0f, false, 1.0f, 1.0f, 0, 1);
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
                        sensitivity, invertY, musicVol, effectsVol, 0, 1);
            }
            // v3+
            int guiScale = in.readInt();
            if (version == 3) {
                return new Options(rr, fov, br, vol, maxFps, vsync, fullscreen, viewBobbing,
                        sensitivity, invertY, musicVol, effectsVol, guiScale, 1);
            }
            // v4
            int shaderQuality = in.readInt();
            if (version == 4) {
                return new Options(rr, fov, br, vol, maxFps, vsync, fullscreen, viewBobbing,
                        sensitivity, invertY, musicVol, effectsVol, guiScale, shaderQuality);
            }
            // v5: раскладка клавиш парами «имя действия — код клавиши»
            int n = Math.max(0, Math.min(256, in.readInt()));
            java.util.Map<String, Integer> saved = new java.util.LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                String action = in.readUTF();
                saved.put(action, in.readInt());
            }
            com.mineclone.core.KeyBindings keys = new com.mineclone.core.KeyBindings();
            keys.load(saved);
            if (version == 5) {
                return new Options(rr, fov, br, vol, maxFps, vsync, fullscreen, viewBobbing,
                        sensitivity, invertY, musicVol, effectsVol, guiScale, shaderQuality, keys);
            }
            // v6: привычки инвентаря
            boolean advancedTooltips = in.readBoolean();
            boolean recipeBookOpen = in.readBoolean();
            boolean recipeBookCraftable = in.readBoolean();
            String recipeBookCategory = in.readUTF();
            int sortMode = in.readInt();
            if (version == 6) {
                return new Options(rr, fov, br, vol, maxFps, vsync, fullscreen, viewBobbing,
                        sensitivity, invertY, musicVol, effectsVol, guiScale, shaderQuality, keys,
                        advancedTooltips, recipeBookOpen, recipeBookCraftable,
                        recipeBookCategory, sortMode);
            }
            // v7: размеченный хвост
            java.util.Map<String, Object> extra = readTagged(in);
            Options.Video vd = Options.Video.defaults();
            Options.Graphics gr = Options.Graphics.defaults();
            Options.Gameplay gp = Options.Gameplay.defaults();
            vd = new Options.Video(
                    intOr(extra, "video.windowMode", vd.windowMode()),
                    intOr(extra, "video.resolution", vd.resolutionIndex()),
                    intOr(extra, "video.renderScale", vd.renderScale()),
                    intOr(extra, "video.antialiasing", vd.antialiasing()));
            gr = new Options.Graphics(
                    intOr(extra, "gfx.shadows", gr.shadows()),
                    boolOr(extra, "gfx.bloom", gr.bloom()),
                    boolOr(extra, "gfx.godRays", gr.godRays()),
                    boolOr(extra, "gfx.volumetricFog", gr.volumetricFog()),
                    boolOr(extra, "gfx.waterReflections", gr.waterReflections()),
                    intOr(extra, "gfx.particles", gr.particles()),
                    intOr(extra, "gfx.weather", gr.weather()),
                    intOr(extra, "gfx.entityDistance", gr.entityDistance()),
                    boolOr(extra, "gfx.occlusion", gr.occlusion()),
                    boolOr(extra, "gfx.chunkLod", gr.chunkLod()));
            gp = new Options.Gameplay(
                    boolOr(extra, "game.cameraShake", gp.cameraShake()),
                    boolOr(extra, "game.screenEffects", gp.screenEffects()),
                    boolOr(extra, "game.contextHints", gp.contextHints()),
                    intOr(extra, "game.fpsDisplay", gp.fpsDisplay()));
            com.mineclone.net.NetSettings defNet = com.mineclone.net.NetSettings.defaults();
            com.mineclone.net.NetSettings net = new com.mineclone.net.NetSettings(
                    intOr(extra, "net.transport", defNet.transport()),
                    stringOr(extra, "net.nickname", defNet.nickname()),
                    stringOr(extra, "net.appId", defNet.appId()),
                    stringOr(extra, "net.region", defNet.region()),
                    stringOr(extra, "net.room", defNet.room()),
                    stringOr(extra, "net.address", defNet.address()),
                    intOr(extra, "net.port", defNet.port()));
            return new Options(rr, fov, br, vol, maxFps, vsync, fullscreen, viewBobbing,
                    sensitivity, invertY, musicVol, effectsVol, guiScale, shaderQuality, keys,
                    advancedTooltips, recipeBookOpen, recipeBookCraftable,
                    recipeBookCategory, sortMode, vd, gr, gp, net);
        } catch (IOException e) {
            System.err.println("loadOptions failed: " + e.getMessage());
            return Options.defaults();
        }
    }

    /** Читает хвост до конца, пропуская незнакомые ключи по виду значения. */
    private static java.util.Map<String, Object> readTagged(DataInputStream in) throws IOException {
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        int count = in.readInt();
        if (count < 0 || count > 4096)
            return out;
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            byte kind = in.readByte();
            switch (kind) {
                case KIND_BOOL -> out.put(key, in.readBoolean());
                case KIND_INT -> out.put(key, in.readInt());
                case KIND_FLOAT -> out.put(key, in.readFloat());
                case KIND_STRING -> out.put(key, in.readUTF());
                // Вид неизвестен — дальше по потоку идти вслепую нельзя:
                // отдаём то, что успели прочитать, остальное возьмётся из
                // умолчаний.
                default -> { return out; }
            }
        }
        return out;
    }

    private static int intOr(java.util.Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        return v instanceof Integer i ? i : fallback;
    }

    private static boolean boolOr(java.util.Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    private static String stringOr(java.util.Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v instanceof String s ? s : fallback;
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
                out.writeInt(o.shaderQuality);
                java.util.Map<String, Integer> keys = o.keys.toMap();   // v5
                out.writeInt(keys.size());
                for (java.util.Map.Entry<String, Integer> e : keys.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeInt(e.getValue());
                }
                out.writeBoolean(o.advancedTooltips);                   // v6
                out.writeBoolean(o.recipeBookOpen);
                out.writeBoolean(o.recipeBookCraftable);
                out.writeUTF(o.recipeBookCategory);
                out.writeInt(o.sortMode);
                out.writeInt(25);                                       // v7
                putInt(out, "video.windowMode", o.video.windowMode());
                putInt(out, "video.resolution", o.video.resolutionIndex());
                putInt(out, "video.renderScale", o.video.renderScale());
                putInt(out, "video.antialiasing", o.video.antialiasing());
                putInt(out, "gfx.shadows", o.graphics.shadows());
                putBool(out, "gfx.bloom", o.graphics.bloom());
                putBool(out, "gfx.godRays", o.graphics.godRays());
                putBool(out, "gfx.volumetricFog", o.graphics.volumetricFog());
                putBool(out, "gfx.waterReflections", o.graphics.waterReflections());
                putInt(out, "gfx.particles", o.graphics.particles());
                putInt(out, "gfx.weather", o.graphics.weather());
                putInt(out, "gfx.entityDistance", o.graphics.entityDistance());
                putBool(out, "gfx.occlusion", o.graphics.occlusion());
                putBool(out, "gfx.chunkLod", o.graphics.chunkLod());
                putBool(out, "game.cameraShake", o.gameplay.cameraShake());
                putBool(out, "game.screenEffects", o.gameplay.screenEffects());
                putBool(out, "game.contextHints", o.gameplay.contextHints());
                putInt(out, "game.fpsDisplay", o.gameplay.fpsDisplay());
                putInt(out, "net.transport", o.net.transport());
                putString(out, "net.nickname", o.net.nickname());
                putString(out, "net.appId", o.net.appId());
                putString(out, "net.region", o.net.region());
                putString(out, "net.room", o.net.room());
                putString(out, "net.address", o.net.address());
                putInt(out, "net.port", o.net.port());
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
    private void writeGzipAtomic(File target, Writer body) throws IOException {
        long start = System.nanoTime();
        try {
            writeGzipAtomicFile(target, body);
            completedWrites.increment();
            writtenBytes.add(target.length());
        } catch (IOException e) {
            failedWrites.increment();
            throw e;
        } finally {
            long elapsed = System.nanoTime() - start;
            writeNanos.add(elapsed);
            lastWriteNanos.set(elapsed);
            maxWriteNanos.accumulateAndGet(elapsed, Math::max);
        }
    }

    private static void writeGzipAtomicFile(File target, Writer body) throws IOException {
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
        if (Thread.currentThread() == writerThread) return;
        try {
            // Large session backups can legitimately exceed ten seconds. Returning
            // early would let exit/delete discard still-queued world writes.
            chunkWriter.submit(() -> {}).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            System.err.println("flush save queue failed: " + e.getMessage());
        }
    }
}
