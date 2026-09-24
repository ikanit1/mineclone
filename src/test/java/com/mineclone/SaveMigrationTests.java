package com.mineclone;

import com.mineclone.data.Json;
import com.mineclone.data.JsonObject;
import com.mineclone.save.*;
import com.mineclone.world.*;
import java.io.DataInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Immutable old-writer goldens; upgrades happen only in temporary copies. */
public final class SaveMigrationTests {
    private static final Path ROOT = Path.of("src/test/resources/fixtures/saves");
    private static final String[] WORLDS = {"alpha-small", "alpha-chests", "alpha-creative",
            "legacy-level-v6-chunk-v4", "legacy-level-v8-chunk-v4", "legacy-level-v9-chunk-v5"};

    public static void runAll(TestMain.Runner runner) {
        runner.run("legacy fixture corpus is pinned and under two megabytes", SaveMigrationTests::corpusIntegrity);
        for (String id : WORLDS) runner.run("migrate golden save " + id, () -> migrate(id));
    }

    public static void main(String[] args) throws Exception {
        corpusIntegrity();
        for (String id : WORLDS) migrate(id);
        System.out.println("SAVE_MIGRATION PASS: 6 historical worlds read and migrated; source hashes unchanged");
    }

    private static void corpusIntegrity() throws Exception {
        long bytes;
        try (var files = Files.walk(ROOT)) {
            bytes = files.filter(Files::isRegularFile).mapToLong(file -> {
                try { return Files.size(file); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }).sum();
        }
        check(bytes > 0 && bytes <= 2 * 1024 * 1024, "fixture corpus exceeds 2 MiB: " + bytes);
        for (String id : WORLDS) {
            JsonObject manifest = manifest(id);
            check(manifest.string("writerTag").equals("v1.0.0-alpha"), "unexpected golden writer tag");
            check(manifest.string("writerCommit").equals("448347596c1c3d944df9720de17b7e7b5c99a655"),
                    "golden provenance changed");
            if (id.startsWith("alpha-")) check(manifest.string("kind").equals("tagged-writer"), "alpha fixture is synthetic");
            verifyFileHashes(ROOT.resolve(id), manifest);
            check(version(ROOT.resolve(id).resolve("level.dat")) == manifest.integer("levelVersion"), "wrong old level version");
            for (int i = 0; i < manifest.array("chunks").size(); i++) {
                JsonObject chunk = manifest.element("chunks", i);
                Path file = ROOT.resolve(id).resolve("chunks/c." + chunk.integer("x") + "." + chunk.integer("z") + ".dat");
                check(version(file) == manifest.integer("chunkVersion"), "wrong old chunk version");
            }
        }
    }

    private static void migrate(String id) throws Exception {
        JsonObject expected = manifest(id);
        Path source = ROOT.resolve(id);
        verifyFileHashes(source, expected);
        Path temporary = Files.createTempDirectory("mineclone-migration-");
        SaveManager save = new SaveManager(temporary.toFile());
        try {
            copyTree(source, temporary.resolve(id));
            LevelData level = loadedLevel(save, id);
            assertLevel(level, expected);
            List<ChunkSnapshot> snapshots = new ArrayList<>();
            for (int i = 0; i < expected.array("chunks").size(); i++) {
                JsonObject chunk = expected.element("chunks", i);
                ChunkSnapshot snapshot = loadedChunk(save, id, chunk.integer("x"), chunk.integer("z"));
                assertChunk(snapshot, chunk);
                snapshots.add(snapshot);
            }
            // Upgrading copied historical data must retain all known and opaque fields.
            save.saveLevel(id, level);
            for (ChunkSnapshot snapshot : snapshots) save.saveChunkAsync(id, snapshot);
            save.flushAndAwait();
            check(version(temporary.resolve(id).resolve("level.dat")) == SaveFormat.LEVEL_VERSION,
                    "level was not upgraded to current version");
            SaveManager reopened = new SaveManager(temporary.toFile());
            assertLevel(loadedLevel(reopened, id), expected);
            for (int i = 0; i < expected.array("chunks").size(); i++) {
                JsonObject chunk = expected.element("chunks", i);
                int cx = chunk.integer("x"), cz = chunk.integer("z");
                check(version(temporary.resolve(id).resolve("chunks/c." + cx + "." + cz + ".dat")) == SaveFormat.CHUNK_VERSION,
                        "chunk was not upgraded to current version");
                assertChunk(loadedChunk(reopened, id, cx, cz), chunk);
            }
            reopened.flushAndAwait();
        } finally {
            save.flushAndAwait();
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            verifyFileHashes(source, expected);
        }
    }

    private static JsonObject manifest(String id) throws Exception {
        Path file = ROOT.resolve(id).resolve("fixture.json");
        return Json.parseObject(file, file.toString());
    }

    private static LevelData loadedLevel(SaveManager save, String id) {
        LevelLoad result = save.readLevel(id);
        if (result instanceof LevelLoad.Loaded loaded) return loaded.data();
        throw new AssertionError(id + ": " + result);
    }
    private static ChunkSnapshot loadedChunk(SaveManager save, String id, int cx, int cz) {
        ChunkLoad result = save.readChunk(id, cx, cz);
        if (result instanceof ChunkLoad.Loaded loaded) return loaded.snapshot();
        throw new AssertionError(id + " chunk " + cx + "," + cz + ": " + result);
    }

    private static void assertLevel(LevelData level, JsonObject expected) {
        check(level.name.equals(expected.string("name")), "level name changed");
        check(level.seed == (long) number(expected, "seed"), "seed changed");
        check(level.lastPlayed == (long) number(expected, "lastPlayed"), "last played timestamp changed");
        check(level.gameMode.name().equals(expected.string("mode")), "game mode changed");
        near(level.health, number(expected, "health"), "health");
        near(level.hunger, number(expected, "hunger"), "hunger");
        near(level.timeOfDay, number(expected, "gameTime"), "day/moon time");
        near(level.yaw, number(expected, "yaw"), "yaw"); near(level.pitch, number(expected, "pitch"), "pitch");
        check(level.selectedSlot == expected.integer("selectedSlot"), "selected slot changed");
        assertVector(new double[]{level.px, level.py, level.pz}, expected.array("position"), "position");
        assertVector(new double[]{level.spawnX, level.spawnY, level.spawnZ}, expected.array("spawn"), "spawn");
        assertSlots(level.inventory, expected, "inventory");
        assertSlots(level.pending, expected, "pending");
        JsonObject extra = expected.object("extraSections");
        for (String key : extra.keys()) {
            byte[] bytes = level.extraSections.get(key);
            check(bytes != null && HexFormat.of().formatHex(bytes).equals(extra.string(key)), "opaque section lost: " + key);
        }
    }

    private static void assertChunk(ChunkSnapshot chunk, JsonObject expected) throws Exception {
        check(chunk.cx == expected.integer("x") && chunk.cz == expected.integer("z"), "chunk coordinates changed");
        check(digest(chunk.blocks).equals(expected.string("blocksSha256")), "block array changed during migration");
        check(digest(chunk.meta).equals(expected.string("metaSha256")), "metadata array changed during migration");
        JsonObject chests = expected.object("chests");
        check(chunk.chests.size() == chests.keys().size(), "chest count changed");
        for (String key : chests.keys()) {
            ItemStack[] slots = chunk.chests.get(Integer.parseInt(key));
            check(slots != null, "chest missing");
            assertSlots(slots, chests, key);
        }
        JsonObject furnaces = expected.object("furnaces");
        check(chunk.furnaces.size() == furnaces.keys().size(), "furnace count changed");
        for (String key : furnaces.keys()) {
            Furnace furnace = chunk.furnaces.get(Integer.parseInt(key));
            check(furnace != null, "furnace missing");
            JsonObject reference = furnaces.object(key);
            assertStack(furnace.input, reference.object("input"));
            assertStack(furnace.fuel, reference.object("fuel"));
            assertStack(furnace.output, reference.object("output"));
            near(furnace.cook, number(reference, "cook"), "furnace cook");
            near(furnace.burnLeft, number(reference, "burnLeft"), "furnace burn left");
            near(furnace.burnMax, number(reference, "burnMax"), "furnace burn max");
        }
        check(chunk.items.size() == expected.array("items").size(), "dropped item count changed");
        for (int i = 0; i < chunk.items.size(); i++) {
            DroppedItem item = chunk.items.get(i);
            JsonObject reference = expected.element("items", i);
            assertStack(item.stack, reference.object("stack"));
            near(item.x, number(reference, "x"), "dropped x"); near(item.y, number(reference, "y"), "dropped y");
            near(item.z, number(reference, "z"), "dropped z"); near(item.age, number(reference, "age"), "dropped age");
        }
    }

    private static void assertSlots(ItemStack[] slots, JsonObject expected, String key) {
        long actual = Arrays.stream(slots).filter(Objects::nonNull).count();
        check(actual == expected.array(key).size(), "number of stacks changed: " + key);
        for (int i = 0; i < expected.array(key).size(); i++) {
            JsonObject stack = expected.element(key, i);
            int slot = stack.integer("slot");
            check(slot >= 0 && slot < slots.length, "slot missing: " + slot);
            assertStack(slots[slot], stack);
        }
    }

    private static void assertStack(ItemStack stack, JsonObject expected) {
        check(stack != null && !stack.item.missing, "stack missing or unresolved");
        check(stack.item.id.toString().equals(expected.string("id")), "item id changed");
        check(stack.count == expected.integer("count"), "stack count changed");
        check(stack.damage() == expected.integer("damage"), "tool wear changed");
    }

    private static void verifyFileHashes(Path source, JsonObject expected) throws Exception {
        JsonObject files = expected.object("files");
        for (String name : files.keys()) {
            Path file = source.resolve(name).normalize();
            check(file.startsWith(source.normalize()), "fixture manifest escapes its world");
            check(digest(Files.readAllBytes(file)).equals(files.string(name)), "immutable fixture changed: " + file);
        }
    }
    private static int version(Path file) throws Exception {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            check(in.readInt() == 0x4D434C44, "bad save magic");
            return in.readInt();
        }
    }
    private static void copyTree(Path source, Path destination) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path target = destination.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else Files.copy(file, target);
            }
        }
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static double number(JsonObject object, String key) { return ((Number) object.raw(key)).doubleValue(); }
    private static void assertVector(double[] actual, List<Object> expected, String name) {
        for (int i = 0; i < actual.length; i++) near(actual[i], ((Number) expected.get(i)).doubleValue(), name);
    }
    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-6, message + " differs: " + actual + " != " + expected);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
