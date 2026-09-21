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
        /** Режим игры; у повреждённого сейва — выживание, но он и не играется. */
        public final com.mineclone.world.GameMode mode;
        /** Игровое время — по нему список показывает номер суток. */
        public final float timeOfDay;
        /** Сколько мир занимает на диске, байты. */
        public final long sizeBytes;
        /** Есть ли у мира снимок-превью. */
        public final boolean hasIcon;

        WorldInfo(String id, String displayName, long seed, long lastPlayed, boolean corrupted,
                  com.mineclone.world.GameMode mode, float timeOfDay, long sizeBytes, boolean hasIcon) {
            this.id = id;
            this.displayName = displayName;
            this.seed = seed;
            this.lastPlayed = lastPlayed;
            this.corrupted = corrupted;
            this.mode = mode;
            this.timeOfDay = timeOfDay;
            this.sizeBytes = sizeBytes;
            this.hasIcon = hasIcon;
        }

        static WorldInfo corrupted(String id, long size) {
            return new WorldInfo(id, UNNAMED, 0L, 0L, true,
                    com.mineclone.world.GameMode.SURVIVAL, 0f, size, false);
        }
    }

    /** Имя мира, у которого в level.dat пусто. */
    static final String UNNAMED = "Мир";

    private final File savesRoot;
    private final ExecutorService chunkWriter =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mineclone-save");
                t.setDaemon(true);
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
    }

    private File worldDir(String id) { return new File(savesRoot, id); }
    private File levelFile(String id) { return new File(worldDir(id), SaveFormat.LEVEL_FILE); }
    private File chunksDir(String id) { return new File(worldDir(id), SaveFormat.CHUNKS_DIR); }
    private File chunkFile(String id, int cx, int cz) {
        return new File(chunksDir(id), SaveFormat.chunkFileName(cx, cz));
    }
    private File iconFile(String id) { return new File(worldDir(id), SaveFormat.ICON_FILE); }
    private File optionsFile() { return new File(savesRoot.getParentFile() != null
            ? savesRoot.getParentFile() : new File("."), SaveFormat.OPTIONS_FILE); }

    public boolean hasSave(String id) {
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
        LevelData d = loadLevel(id);
        if (d == null)
            return WorldInfo.corrupted(id, size);
        String name = d.name.isEmpty() ? UNNAMED : d.name;
        return new WorldInfo(id, name, d.seed, d.lastPlayed, false, d.gameMode, d.timeOfDay,
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
        java.util.List<WorldInfo> list = new java.util.ArrayList<>();
        File[] dirs = savesRoot.listFiles(File::isDirectory);
        if (dirs == null) return list;
        for (File d : dirs) {
            if (!new File(d, SaveFormat.LEVEL_FILE).isFile()) continue;
            try {
                list.add(loadWorldInfo(d.getName(), withSizes));
            } catch (Exception e) {
                list.add(WorldInfo.corrupted(d.getName(), 0L));
            }
        }
        list.sort((a, b) -> {
            if (a.corrupted != b.corrupted) return a.corrupted ? 1 : -1;
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
        if (!worldDir(base).exists())
            return base;
        int n = 2;
        while (worldDir(base + "_" + n).exists())
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
                d.px, d.py, d.pz, d.spawnX, d.spawnY, d.spawnZ,
                d.yaw, d.pitch, d.timeOfDay, d.selectedSlot,
                d.inventory, d.gameMode, System.currentTimeMillis(), d.health, d.hunger,
                d.pending, d.extraSections));
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
        chunkWriter.submit(() -> {
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
        try {
            writeGzipAtomic(levelFile(id), o -> {
                o.writeInt(SaveFormat.MAGIC);
                o.writeInt(SaveFormat.LEVEL_VERSION);
                o.writeUTF(d.name);
                o.writeLong(d.seed);
                o.writeLong(d.lastPlayed);
                o.writeFloat(d.health);
                o.writeFloat(d.hunger);                      // v9
                o.writeDouble(d.px); o.writeDouble(d.py); o.writeDouble(d.pz);
                o.writeDouble(d.spawnX); o.writeDouble(d.spawnY); o.writeDouble(d.spawnZ);
                o.writeFloat(d.yaw); o.writeFloat(d.pitch);
                o.writeFloat(d.timeOfDay);
                o.writeInt(d.selectedSlot);
                o.writeInt(d.gameMode.ordinal());            // v6
                // v10: дальше идут размеченные секции. Новая секция больше не
                // поднимает версию файла, а незнакомая переживает запись.
                writeSections(o, d);
            });
        } catch (IOException e) {
            System.err.println("saveLevel failed: " + e.getMessage());
        }
    }

    /** Имя секции инвентаря игрока. */
    private static final String SECTION_INVENTORY = "inventory";
    /** Секция стопок, которым некуда лечь: курсор открытого окна. */
    private static final String SECTION_PENDING = "pending";

    private static void writeSections(DataOutputStream o, LevelData d) throws IOException {
        java.util.LinkedHashMap<String, byte[]> sections = new java.util.LinkedHashMap<>();
        sections.put(SECTION_INVENTORY, stacksToBytes(d.inventory));
        if (d.pending.length > 0)
            sections.put(SECTION_PENDING, stacksToBytes(d.pending));
        // Чужие секции идут последними и ровно теми байтами, что пришли.
        for (var e : d.extraSections.entrySet())
            sections.putIfAbsent(e.getKey(), e.getValue());
        com.mineclone.data.VarInt.write(o, sections.size());
        for (var e : sections.entrySet()) {
            o.writeUTF(e.getKey());
            com.mineclone.data.VarInt.write(o, e.getValue().length);
            o.write(e.getValue());
        }
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
            int n = Math.max(0, Math.min(max, com.mineclone.data.VarInt.read(in)));
            com.mineclone.world.ItemStack[] out = new com.mineclone.world.ItemStack[n];
            for (int i = 0; i < n; i++)
                out[i] = ItemStackCodec.read(in);
            return out;
        }
    }

    /** @return loaded level, or null if absent/unreadable/incompatible. */
    public LevelData loadLevel(String id) {
        if (nameless(id)) return null;
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
                int count = Math.max(0, Math.min(256, com.mineclone.data.VarInt.read(in)));
                for (int i = 0; i < count; i++) {
                    String key = in.readUTF();
                    int length = Math.max(0, com.mineclone.data.VarInt.read(in));
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
                int n = Math.max(0, Math.min(256, in.readInt()));
                com.mineclone.world.ItemStack[] tmp =
                        new com.mineclone.world.ItemStack[Math.max(com.mineclone.world.Inventory.SIZE, n)];
                for (int i = 0; i < n; i++)
                    tmp[i] = ItemStackCodec.readLegacy(in);
                inventory = tmp;
            } else if (version >= 6) {
                int n = Math.max(0, Math.min(256, in.readInt()));
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
                int n = Math.max(0, Math.min(128, in.readInt()));
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

            return new LevelData(name, seed, px, py, pz, spawnX, spawnY, spawnZ,
                    yaw, pitch, tod, slot, inventory, gameMode, lastPlayed, health, hunger,
                    pending, extra);
        } catch (IOException e) {
            System.err.println("loadLevel failed: " + e.getMessage());
            return null;
        }
    }

    // ---- chunk snapshots ----

    /** Queues a chunk write on the background thread. Arrays must not be mutated after the call. */
    public void saveChunkAsync(String id, ChunkSnapshot s) {
        if (nameless(id)) return;
        chunkWriter.submit(() -> saveChunkBlocking(id, s));
    }

    void saveChunkBlocking(String id, ChunkSnapshot s) {
        try {
            writeGzipAtomic(chunkFile(id, s.cx, s.cz), o -> {
                o.writeInt(SaveFormat.MAGIC);
                o.writeInt(SaveFormat.CHUNK_VERSION);
                RunLengthCodec.write(o, s.blocks);
                RunLengthCodec.write(o, s.meta);
                o.writeInt(s.chests.size());                                  // v2
                for (var e : s.chests.entrySet()) {
                    o.writeInt(e.getKey());
                    com.mineclone.world.ItemStack[] slots = e.getValue();
                    o.writeByte(slots.length);
                    for (com.mineclone.world.ItemStack st : slots)
                        ItemStackCodec.write(o, st);
                }
                o.writeInt(s.furnaces.size());                                // v3
                for (var e : s.furnaces.entrySet()) {
                    o.writeInt(e.getKey());
                    com.mineclone.world.Furnace f = e.getValue();
                    ItemStackCodec.write(o, f.input);
                    ItemStackCodec.write(o, f.fuel);
                    ItemStackCodec.write(o, f.output);
                    o.writeFloat(f.burnLeft);
                    o.writeFloat(f.burnMax);
                    o.writeFloat(f.cook);
                }
                o.writeInt(s.items.size());                                   // v4
                for (com.mineclone.world.DroppedItem d : s.items) {
                    ItemStackCodec.write(o, d.stack);
                    o.writeFloat(d.x);
                    o.writeFloat(d.y);
                    o.writeFloat(d.z);
                    o.writeFloat(d.age);
                }
            });
        } catch (IOException e) {
            System.err.println("saveChunk failed: " + e.getMessage());
        }
    }

    /** @return snapshot, or null if absent/unreadable/incompatible. */
    public ChunkSnapshot loadChunk(String id, int cx, int cz) {
        if (nameless(id)) return null;
        File f = chunkFile(id, cx, cz);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(f))))) {
            if (in.readInt() != SaveFormat.MAGIC) return null;
            int version = in.readInt();
            // Первая версия читается как раньше: сундуков в ней просто нет.
            // Отказаться от неё значило бы выкинуть все правки во всех уже
            // сохранённых мирах.
            if (version < 1 || version > SaveFormat.CHUNK_VERSION) return null;
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
                int n = Math.max(0, Math.min(4096, in.readInt()));
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
                int n = Math.max(0, Math.min(4096, in.readInt()));
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
                int n = Math.max(0, Math.min(4096, in.readInt()));
                for (int i = 0; i < n; i++) {
                    com.mineclone.world.ItemStack st = readChunkStack(in, version);
                    float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
                    float age = in.readFloat();
                    if (st != null)
                        items.add(new com.mineclone.world.DroppedItem(st, x, y, z, age));
                }
            }
            return new ChunkSnapshot(cx, cz, blocks, meta, chests, furnaces, items);
        } catch (IOException e) {
            System.err.println("loadChunk failed: " + e.getMessage());
            return null;
        }
    }

    /** Стопка из чанка: с шестой версии — id предмета, раньше — размеченный слот. */
    private static com.mineclone.world.ItemStack readChunkStack(DataInputStream in, int version)
            throws IOException {
        return version >= 6 ? ItemStackCodec.read(in) : ItemStackCodec.readLegacy(in);
    }

    public void deleteWorld(String id) {
        deleteRecursive(worldDir(id));
    }

    /**
     * Renames a world's display name in its level.dat without touching any chunk files.
     * Сытость передаётся явно: конструктор без неё ставит полную, и переименование
     * молча кормило игрока.
     */
    public void renameWorld(String id, String newName) {
        LevelData d = loadLevel(id);
        if (d == null) return;
        saveLevel(id, new LevelData(newName, d.seed,
                d.px, d.py, d.pz, d.spawnX, d.spawnY, d.spawnZ,
                d.yaw, d.pitch, d.timeOfDay, d.selectedSlot,
                d.inventory, d.gameMode, d.lastPlayed, d.health, d.hunger,
                d.pending, d.extraSections));
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
