import com.mineclone.item.Components;
import com.mineclone.item.LegacyItems;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Run against the compiled v1.0.0-alpha tag, with that worktree as the cwd.
 * Refuses newer writers and an occupied output directory; fixtures are immutable.
 * See src/test/resources/fixtures/saves/README.md for the exact reproduction command.
 */
public class MakeSaveFixtures {
    private static final String TAG = "v1.0.0-alpha";
    private static final String COMMIT = "448347596c1c3d944df9720de17b7e7b5c99a655";
    private static final long LAST_PLAYED = 1_725_000_000_000L;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Output saves directory required");
        if (SaveFormat.LEVEL_VERSION != 10 || SaveFormat.CHUNK_VERSION != 6)
            throw new IllegalStateException("Compile/run this generator against " + TAG + ", never the current writer");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(root)) try (var files = Files.list(root)) {
            if (files.findAny().isPresent()) throw new IOException("Refusing to replace existing fixtures: " + root);
        }
        Files.createDirectories(root);
        SaveManager save = new SaveManager(root.toFile());
        makeTagged(save, root, "alpha-small", 81001, 5, false, false);
        makeTagged(save, root, "alpha-chests", 81002, 1, true, false);
        makeTagged(save, root, "alpha-creative", 81003, 1, false, true);
        makeHistorical(root, 6, 4);
        makeHistorical(root, 8, 4);
        makeHistorical(root, 9, 5);
        System.out.println("SAVE_FIXTURES_WRITTEN tag=" + TAG + " commit=" + COMMIT + " worlds=6 root=" + root);
    }

    private static void makeTagged(SaveManager save, Path root, String id, long seed,
                                   int chunkCount, boolean containers, boolean creative) throws Exception {
        ItemStack[] inventory = creative ? LevelData.creativeInventory() : LevelData.emptyInventory();
        if (!creative) {
            inventory[0] = new ItemStack(BlockType.STONE, 32);
            inventory[4] = tool("diamond_pickaxe", 37);
        }
        ItemStack[] pending = { new ItemStack(BlockType.GLASS, 3) };
        LevelData level = new LevelData(id, seed, -8.5, 84.25, 17.5,
                8.5, 81.0, 8.5, 0.25f, -0.5f, 18.75f, 4,
                inventory, creative ? GameMode.CREATIVE : GameMode.SURVIVAL, LAST_PLAYED,
                14.5f, 11.25f, pending, Map.of("fixture:opaque", new byte[]{7, 0, -1, 42}));
        save.saveLevel(id, level);
        World world = new World(seed);
        List<ChunkSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            Chunk chunk = world.getChunk(i - 2, i % 2);
            chunk.set(3, 120, 4, BlockType.GLASS);
            chunk.setMeta(3, 120, 4, (byte) (i + 1));
            if (containers) populateContainers(chunk);
            ChunkSnapshot snapshot = snapshot(chunk);
            snapshots.add(snapshot);
            save.saveChunkAsync(id, snapshot);
        }
        save.flushAndAwait();
        writeManifest(root.resolve(id), level, snapshots, 10, 6, "tagged-writer");
    }

    private static void populateContainers(Chunk chunk) {
        chunk.set(1, 80, 1, BlockType.CHEST);
        chunk.setMeta(1, 80, 1, (byte) 2);
        chunk.set(2, 80, 1, BlockType.FURNACE);
        chunk.createChest(1, 80, 1)[0] = new ItemStack(BlockType.STONE, 17);
        chunk.createChest(1, 80, 1)[8] = tool("iron_pickaxe", 9);
        Furnace furnace = chunk.createFurnace(2, 80, 1);
        furnace.input = new ItemStack(BlockType.SAND, 7);
        furnace.fuel = new ItemStack(BlockType.WOOD, 3);
        furnace.output = new ItemStack(BlockType.GLASS, 2);
        furnace.cook = 2.75f;
        furnace.burnLeft = 4.5f;
        furnace.burnMax = 12f;
        chunk.setPendingItems(List.of(new DroppedItem(tool("stone_pickaxe", 11),
                chunk.cx * 16 + 4.5f, 81.25f, chunk.cz * 16 + 5.5f, 19.75f)));
    }

    private static ItemStack tool(String id, int wear) {
        ItemStack stack = ItemStack.of(id, 1);
        stack.set(Components.DAMAGE, wear);
        return stack;
    }

    private static ChunkSnapshot snapshot(Chunk chunk) {
        return new ChunkSnapshot(chunk.cx, chunk.cz, chunk.copyBlocks(), chunk.copyMeta(),
                chunk.copyChests(), chunk.copyFurnaces(), chunk.copyPendingItems());
    }

    /** Historical layouts are handwritten, independently of the active writer/reader. */
    private static void makeHistorical(Path root, int levelVersion, int chunkVersion) throws Exception {
        String id = "legacy-level-v" + levelVersion + "-chunk-v" + chunkVersion;
        Path worldDir = root.resolve(id);
        Files.createDirectories(worldDir.resolve("chunks"));
        ItemStack[] inventory = LevelData.emptyInventory();
        inventory[0] = new ItemStack(BlockType.STONE, 23);
        if (levelVersion >= 8) inventory[1] = tool("iron_pickaxe", 19);
        LevelData expected = new LevelData(id, 82000 + levelVersion,
                1.25, 72.5, -3.75, 8.5, 80, 8.5, 0.75f, -0.25f, 8.5f, 1,
                inventory, GameMode.SURVIVAL, LAST_PLAYED,
                levelVersion >= 7 ? 12.5f : 20f, levelVersion >= 9 ? 9.25f : 20f,
                null, null);
        try (DataOutputStream out = gzip(worldDir.resolve("level.dat"))) {
            out.writeInt(0x4D434C44); out.writeInt(levelVersion);
            out.writeUTF(id); out.writeLong(expected.seed); out.writeLong(LAST_PLAYED);
            if (levelVersion >= 7) out.writeFloat(expected.health);
            if (levelVersion >= 9) out.writeFloat(expected.hunger);
            out.writeDouble(expected.px); out.writeDouble(expected.py); out.writeDouble(expected.pz);
            out.writeDouble(expected.spawnX); out.writeDouble(expected.spawnY); out.writeDouble(expected.spawnZ);
            out.writeFloat(expected.yaw); out.writeFloat(expected.pitch); out.writeFloat(expected.timeOfDay);
            out.writeInt(expected.selectedSlot); out.writeInt(expected.gameMode.ordinal());
            out.writeInt(inventory.length);
            for (ItemStack stack : inventory) {
                if (levelVersion >= 8) writeLegacyStack(out, stack);
                else {
                    out.writeByte(stack == null ? 0 : stack.block().ordinal());
                    out.writeShort(stack == null ? 0 : stack.count);
                }
            }
        }
        Chunk chunk = new Chunk(-1, 2);
        populateContainers(chunk);
        ChunkSnapshot snapshot = snapshot(chunk);
        try (DataOutputStream out = gzip(worldDir.resolve("chunks/c.-1.2.dat"))) {
            out.writeInt(0x4D434C44); out.writeInt(chunkVersion);
            if (chunkVersion == 4) { out.write(snapshot.blocks); out.write(snapshot.meta); }
            else { writeRuns(out, snapshot.blocks); writeRuns(out, snapshot.meta); }
            out.writeInt(snapshot.chests.size());
            for (var entry : snapshot.chests.entrySet()) {
                out.writeInt(entry.getKey()); out.writeByte(entry.getValue().length);
                for (ItemStack stack : entry.getValue()) writeLegacyStack(out, stack);
            }
            out.writeInt(snapshot.furnaces.size());
            for (var entry : snapshot.furnaces.entrySet()) {
                out.writeInt(entry.getKey()); Furnace furnace = entry.getValue();
                writeLegacyStack(out, furnace.input); writeLegacyStack(out, furnace.fuel); writeLegacyStack(out, furnace.output);
                out.writeFloat(furnace.burnLeft); out.writeFloat(furnace.burnMax); out.writeFloat(furnace.cook);
            }
            out.writeInt(snapshot.items.size());
            for (DroppedItem item : snapshot.items) {
                writeLegacyStack(out, item.stack);
                out.writeFloat(item.x); out.writeFloat(item.y); out.writeFloat(item.z); out.writeFloat(item.age);
            }
        }
        writeManifest(worldDir, expected, List.of(snapshot), levelVersion, chunkVersion, "synthetic-historical-layout");
    }

    private static DataOutputStream gzip(Path file) throws IOException {
        return new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)));
    }

    private static void writeRuns(DataOutputStream out, byte[] values) throws IOException {
        out.writeBoolean(true);
        for (int start = 0; start < values.length;) {
            int end = start + 1;
            while (end < values.length && values[end] == values[start] && end - start < 65535) end++;
            out.writeShort(end - start); out.writeByte(values[start]); start = end;
        }
    }

    private static void writeLegacyStack(DataOutputStream out, ItemStack stack) throws IOException {
        if (stack == null) { out.writeByte(0); return; }
        int tool = LegacyItems.toolIndex(stack.item);
        if (tool >= 0) { out.writeByte(2); out.writeByte(tool); out.writeShort(stack.damage()); return; }
        out.writeByte(1); out.writeByte(stack.block().ordinal()); out.writeShort(stack.count);
    }

    private static Map<String, Object> stack(ItemStack stack) {
        return Map.of("id", stack.item.id.toString(), "count", stack.count, "damage", stack.damage());
    }
    private static List<Object> stacks(ItemStack[] slots) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) if (slots[i] != null) {
            Map<String, Object> value = new TreeMap<>(stack(slots[i])); value.put("slot", i); out.add(value);
        }
        return out;
    }

    private static void writeManifest(Path dir, LevelData level, List<ChunkSnapshot> chunks,
                                      int levelVersion, int chunkVersion, String kind) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("writerTag", TAG); manifest.put("writerCommit", COMMIT); manifest.put("kind", kind);
        manifest.put("levelVersion", levelVersion); manifest.put("chunkVersion", chunkVersion);
        manifest.put("name", level.name); manifest.put("seed", level.seed);
        manifest.put("health", level.health); manifest.put("hunger", level.hunger);
        manifest.put("mode", level.gameMode.name()); manifest.put("lastPlayed", level.lastPlayed);
        manifest.put("position", List.of(level.px, level.py, level.pz));
        manifest.put("spawn", List.of(level.spawnX, level.spawnY, level.spawnZ));
        manifest.put("yaw", level.yaw); manifest.put("pitch", level.pitch);
        manifest.put("gameTime", level.timeOfDay); manifest.put("selectedSlot", level.selectedSlot);
        manifest.put("inventory", stacks(level.inventory)); manifest.put("pending", stacks(level.pending));
        Map<String, Object> extra = new TreeMap<>();
        level.extraSections.forEach((key, bytes) -> extra.put(key, HexFormat.of().formatHex(bytes)));
        manifest.put("extraSections", extra);
        List<Object> expectedChunks = new ArrayList<>();
        for (ChunkSnapshot chunk : chunks) {
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("x", chunk.cx); expected.put("z", chunk.cz);
            expected.put("blocksSha256", digest(chunk.blocks)); expected.put("metaSha256", digest(chunk.meta));
            Map<String, Object> chests = new TreeMap<>();
            chunk.chests.forEach((key, slots) -> chests.put(key.toString(), stacks(slots)));
            expected.put("chests", chests);
            Map<String, Object> furnaces = new TreeMap<>();
            chunk.furnaces.forEach((key, furnace) -> furnaces.put(key.toString(), Map.of(
                    "input", stack(furnace.input), "fuel", stack(furnace.fuel), "output", stack(furnace.output),
                    "cook", furnace.cook, "burnLeft", furnace.burnLeft, "burnMax", furnace.burnMax)));
            expected.put("furnaces", furnaces);
            List<Object> items = new ArrayList<>();
            for (DroppedItem item : chunk.items) items.add(Map.of("stack", stack(item.stack),
                    "x", item.x, "y", item.y, "z", item.z, "age", item.age));
            expected.put("items", items); expectedChunks.add(expected);
        }
        manifest.put("chunks", expectedChunks);
        Map<String, Object> hashes = new TreeMap<>();
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                hashes.put(dir.relativize(file).toString().replace('\\', '/'), digest(Files.readAllBytes(file)));
        }
        manifest.put("files", hashes);
        Files.writeString(dir.resolve("fixture.json"), json(manifest, 0) + "\n", StandardCharsets.UTF_8);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String json(Object value, int indent) {
        if (value instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (var entry : map.entrySet()) entries.add(" ".repeat(indent + 2)
                    + json(entry.getKey().toString(), 0) + ": " + json(entry.getValue(), indent + 2));
            return "{\n" + String.join(",\n", entries) + "\n" + " ".repeat(indent) + "}";
        }
        if (value instanceof List<?> list) {
            List<String> entries = new ArrayList<>();
            for (Object entry : list) entries.add(json(entry, indent));
            return "[" + String.join(", ", entries) + "]";
        }
        return value.toString();
    }
}
