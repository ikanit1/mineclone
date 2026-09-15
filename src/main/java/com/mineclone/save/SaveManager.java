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
        long size = directorySize(worldDir(id));
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
        java.util.List<WorldInfo> list = new java.util.ArrayList<>();
        File[] dirs = savesRoot.listFiles(File::isDirectory);
        if (dirs == null) return list;
        for (File d : dirs) {
            if (!new File(d, SaveFormat.LEVEL_FILE).isFile()) continue;
            try {
                list.add(loadWorldInfo(d.getName()));
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

    private static long directorySize(File f) {
        if (f.isFile())
            return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null)
            for (File k : kids)
                total += directorySize(k);
        return total;
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
                d.inventory, d.gameMode, System.currentTimeMillis(), d.health, d.hunger));
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
    public void saveIconAsync(String id, int w, int h, int[] argb) {
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
                o.writeInt(d.inventory.length);
                // v8: слот стал размеченным — блок и инструмент больше не
                // различить по одному id, у инструмента ещё и износ.
                for (com.mineclone.world.ItemStack s : d.inventory)
                    writeStack(o, s);
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
            if (version >= 8) {
                int n = Math.max(0, Math.min(256, in.readInt()));
                com.mineclone.world.ItemStack[] tmp =
                        new com.mineclone.world.ItemStack[Math.max(com.mineclone.world.Inventory.SIZE, n)];
                for (int i = 0; i < n; i++)
                    tmp[i] = readStack(in);
                inventory = tmp;
            } else if (version >= 6) {
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
                    yaw, pitch, tod, slot, inventory, gameMode, lastPlayed, health, hunger);
        } catch (IOException e) {
            System.err.println("loadLevel failed: " + e.getMessage());
            return null;
        }
    }

    // ---- стопки предметов ----

    /**
     * Одна стопка в поток. Формат общий у инвентаря игрока и у сундуков:
     * разойдись они, любая правка предметов ломала бы ровно одно из двух
     * хранилищ, и заметить это можно было бы только открыв сундук.
     */
    static void writeStack(DataOutputStream o, com.mineclone.world.ItemStack s)
            throws IOException {
        if (s == null) {
            o.writeByte(SLOT_EMPTY);
        } else if (s.isTool()) {
            o.writeByte(SLOT_TOOL);
            o.writeByte(s.tool.ordinal());
            o.writeShort(Math.min(Short.MAX_VALUE, s.damage));
        } else if (s.isFood()) {
            o.writeByte(SLOT_FOOD);
            o.writeByte(s.food.ordinal());
            o.writeShort(s.count);
        } else {
            o.writeByte(SLOT_BLOCK);
            o.writeByte(s.type.ordinal());
            o.writeShort(s.count);
        }
    }

    /** Одна стопка из потока; null — пустой слот или неизвестный предмет. */
    static com.mineclone.world.ItemStack readStack(DataInputStream in) throws IOException {
        int kind = in.readUnsignedByte();
        if (kind == SLOT_TOOL) {
            com.mineclone.world.ToolType t =
                    com.mineclone.world.ToolType.byId(in.readUnsignedByte());
            int dmg = in.readShort();
            if (t == null)
                return null;
            com.mineclone.world.ItemStack st = new com.mineclone.world.ItemStack(t);
            st.damage = Math.max(0, dmg);
            return st;
        }
        if (kind == SLOT_FOOD) {
            com.mineclone.world.FoodType f =
                    com.mineclone.world.FoodType.byId(in.readUnsignedByte());
            int count = in.readShort();
            return (f != null && count > 0) ? new com.mineclone.world.ItemStack(f, count) : null;
        }
        if (kind == SLOT_BLOCK) {
            int blockId = in.readUnsignedByte();
            int count = in.readShort();
            if (count > 0 && blockId > 0 && blockId < BlockType.VALUES.length)
                return new com.mineclone.world.ItemStack(BlockType.VALUES[blockId], count);
        }
        return null;
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
                o.writeInt(s.chests.size());                                  // v2
                for (var e : s.chests.entrySet()) {
                    o.writeInt(e.getKey());
                    com.mineclone.world.ItemStack[] slots = e.getValue();
                    o.writeByte(slots.length);
                    for (com.mineclone.world.ItemStack st : slots)
                        writeStack(o, st);
                }
                o.writeInt(s.furnaces.size());                                // v3
                for (var e : s.furnaces.entrySet()) {
                    o.writeInt(e.getKey());
                    com.mineclone.world.Furnace f = e.getValue();
                    writeStack(o, f.input);
                    writeStack(o, f.fuel);
                    writeStack(o, f.output);
                    o.writeFloat(f.burnLeft);
                    o.writeFloat(f.burnMax);
                    o.writeFloat(f.cook);
                }
                o.writeInt(s.items.size());                                   // v4
                for (com.mineclone.world.DroppedItem d : s.items) {
                    writeStack(o, d.stack);
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
            in.readFully(blocks);
            in.readFully(meta);
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
                        slots[k] = readStack(in);
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
                    furnace.input = readStack(in);
                    furnace.fuel = readStack(in);
                    furnace.output = readStack(in);
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
                    com.mineclone.world.ItemStack st = readStack(in);
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
                d.inventory, d.gameMode, d.lastPlayed, d.health, d.hunger));
    }

    /** Метки слота инвентаря в формате уровня v8. */
    private static final int SLOT_EMPTY = 0;
    private static final int SLOT_BLOCK = 1;
    private static final int SLOT_TOOL = 2;
    private static final int SLOT_FOOD = 3;

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
            return new Options(rr, fov, br, vol, maxFps, vsync, fullscreen, viewBobbing,
                    sensitivity, invertY, musicVol, effectsVol, guiScale, shaderQuality, keys);
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
                out.writeInt(o.shaderQuality);
                java.util.Map<String, Integer> keys = o.keys.toMap();   // v5
                out.writeInt(keys.size());
                for (java.util.Map.Entry<String, Integer> e : keys.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeInt(e.getValue());
                }
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
