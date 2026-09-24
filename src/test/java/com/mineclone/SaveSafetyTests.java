package com.mineclone;

import com.mineclone.save.ChunkLoad;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.LevelLoad;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.save.WorldOpenPolicy;
import com.mineclone.save.WorldWarning;
import com.mineclone.server.DedicatedServer;
import com.mineclone.server.ServerConfig;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.World;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.GZIPOutputStream;

/** Fail-closed reads and preservation checks run against isolated real files. */
final class SaveSafetyTests {
    static void runAll(TestMain.Runner r) {
        r.run("missing level alone permits creation; reads write nothing", SaveSafetyTests::missingLevel);
        r.run("damaged level refuses server start and preserves every world byte", SaveSafetyTests::corruptServer);
        r.run("future level refuses server start and lists separately from damage", SaveSafetyTests::futureLevel);
        r.run("a level directory is unreadable, never absent", SaveSafetyTests::levelDirectory);
        r.run("gzip CRC damage in a complete level refuses overwrite", SaveSafetyTests::levelChecksum);
        r.run("corrupt chunk is quarantined before regenerated save and survives reopening", SaveSafetyTests::corruptChunk);
        r.run("future chunk stays read-only after edits and queued saves", SaveSafetyTests::futureChunk);
        r.run("failed quarantine preserves source and makes generated chunk read-only", SaveSafetyTests::failedQuarantine);
        r.run("direct writes cannot bypass unreadable chunk protection", SaveSafetyTests::directWrites);
        r.run("chunk checksum damage is quarantined with original trailer bytes", SaveSafetyTests::chunkChecksum);
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static LevelData level() { return new LevelData(12345L, 8.5, 80, 8.5, 0, 0, 0, 0); }

    private static void missingLevel() throws Exception {
        try (Fixture f = new Fixture()) {
            LevelLoad result = f.save.readLevel("missing");
            var decision = WorldOpenPolicy.decide(result);
            check(result instanceof LevelLoad.Absent, "missing file must be Absent");
            check(decision.allowed() && decision.createNew() && decision.exitCode() == 0, "creation policy");
            check(!Files.exists(f.root.resolve("missing")), "read created a world directory");
            f.save.saveLevel("missing", level());
            var loaded = f.save.readLevel("missing");
            check(loaded instanceof LevelLoad.Loaded, "new level did not round-trip");
            check(!WorldOpenPolicy.decide(loaded).createNew(), "loaded world must keep seed");
        }
    }

    private static void corruptServer() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] evidence = { 1, 2, 3, 4, 5 };
            Files.write(f.levelPath(), evidence);
            Path chunk = f.chunkPath();
            Files.write(chunk, new byte[] { 7, 8, 9 });
            var decision = WorldOpenPolicy.decide(f.save.readLevel("world"));
            check(!decision.allowed() && !decision.createNew() && decision.exitCode() == 2, "failed closed policy");
            check(f.server().run() == 2, "damaged server start must return 2 before opening sockets");
            f.save.saveLevel("world", level());
            f.save.flushAndAwait();
            check(Arrays.equals(evidence, Files.readAllBytes(f.levelPath())), "level overwritten");
            check(Arrays.equals(new byte[] { 7, 8, 9 }, Files.readAllBytes(chunk)), "chunk changed on refusal");
            try (var files = Files.list(chunk.getParent())) {
                check(files.count() == 1, "server start quarantined chunks before refusing level");
            }
        }
    }

    private static void futureLevel() throws Exception {
        try (Fixture f = new Fixture()) {
            header(f.levelPath(), 99);
            byte[] evidence = Files.readAllBytes(f.levelPath());
            LevelLoad result = f.save.readLevel("world");
            check(result instanceof LevelLoad.TooNew newer && newer.version() == 99, "future version must stay distinct");
            check(f.server().run() == 2, "future server start must return 2");
            f.save.saveLevel("world", level());
            check(Arrays.equals(evidence, Files.readAllBytes(f.levelPath())), "future level overwritten");
            var info = f.save.loadWorldInfo("world");
            check(info.tooNew && !info.corrupted && !info.playable(), "future world list status");
            Files.createDirectories(f.root.resolve("broken"));
            Files.write(f.root.resolve("broken/level.dat"), new byte[] { 1 });
            var broken = f.save.loadWorldInfo("broken");
            check(broken.corrupted && !broken.tooNew && !broken.playable(), "corrupt world list status");
            check(f.save.listWorlds(false).size() == 2, "unreadable worlds hidden from list");
        }
    }

    private static void levelDirectory() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.createDirectories(f.levelPath());
            check(f.save.readLevel("world") instanceof LevelLoad.Unreadable, "existing non-file is not absence");
            f.save.saveLevel("world", level());
            check(Files.isDirectory(f.levelPath()), "save replaced unreadable path");
            check(f.save.listWorlds(false).get(0).corrupted, "directory level not listed as damaged");
        }
    }

    private static void levelChecksum() throws Exception {
        try (Fixture f = new Fixture()) {
            f.save.saveLevel("world", level());
            f.save.flushAndAwait();
            byte[] evidence = damageChecksum(f.levelPath());
            check(f.save.readLevel("world") instanceof LevelLoad.Unreadable, "gzip trailer was not checked");
            f.save.saveLevel("world", level());
            check(Arrays.equals(evidence, Files.readAllBytes(f.levelPath())), "CRC-damaged level replaced");
        }
    }

    private static void corruptChunk() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] damaged = { 4, 3, 2, 1 };
            Files.write(f.chunkPath(), damaged);
            check(f.save.readChunk("world", 0, 0) instanceof ChunkLoad.Unreadable, "corrupt read status");
            check(Files.exists(f.chunkPath()), "inspection modified disk");
            try (LoadedWorld loaded = new LoadedWorld(f)) {
                Chunk c = loaded.loader.loadNow(0, 0);
                check(!c.isReadOnly(), "successfully quarantined generated chunk should save");
                check(c.get(0, 0, 0) != BlockType.AIR, "chunk was not regenerated");
                var warnings = f.save.drainWorldWarnings("world");
                check(warnings.size() == 1 && warnings.get(0).kind() == WorldWarning.Kind.QUARANTINED, "missing quarantine warning");
                Path evidence = warnings.get(0).evidence();
                check(Arrays.equals(damaged, Files.readAllBytes(evidence)), "quarantine lost source bytes");
                loaded.world.setBlock(1, 100, 1, BlockType.GLASS);
                saveAll(f.save, loaded.world);
                check(Arrays.equals(damaged, Files.readAllBytes(evidence)), "saveAll replaced evidence");
                check(f.save.readChunk("world", 0, 0) instanceof ChunkLoad.Loaded, "replacement chunk not saved");
                SaveManager reopened = new SaveManager(f.root.toFile());
                var snap = (ChunkLoad.Loaded) reopened.readChunk("world", 0, 0);
                check(snap.snapshot().blocks[Chunk.idx(1, 100, 1)] == (byte) BlockType.GLASS.ordinal(), "regenerated edit lost");
            }
        }
    }

    private static void futureChunk() throws Exception {
        try (Fixture f = new Fixture()) {
            header(f.chunkPath(), 99);
            byte[] evidence = Files.readAllBytes(f.chunkPath());
            try (LoadedWorld loaded = new LoadedWorld(f)) {
                Chunk c = loaded.loader.loadNow(0, 0);
                check(c.isReadOnly(), "future chunk not marked read-only");
                loaded.world.setBlock(1, 100, 1, BlockType.GLASS);
                saveAll(f.save, loaded.world);
                // Even a caller that ignores isReadOnly cannot replace this file.
                f.save.saveChunkAsync("world", snapshot(c));
                f.save.flushAndAwait();
                check(Arrays.equals(evidence, Files.readAllBytes(f.chunkPath())), "future bytes changed");
                var warnings = f.save.drainWorldWarnings("world");
                check(warnings.size() == 1 && warnings.get(0).kind() == WorldWarning.Kind.TOO_NEW, "future warning missing or repeated");
                check(f.save.readChunk("world", 0, 0) instanceof ChunkLoad.TooNew, "future status lost");
                check(f.save.drainWorldWarnings("world").isEmpty(), "same warning repeated every read");
            }
        }
    }

    private static void failedQuarantine() throws Exception {
        try (Fixture f = new Fixture()) {
            // This fails deterministically on every OS, unlike ACL/readonly-bit tricks.
            Files.createDirectories(f.chunkPath());
            Path child = f.chunkPath().resolve("original-evidence");
            byte[] evidence = { 11, 22, 33 };
            Files.write(child, evidence);
            try (LoadedWorld loaded = new LoadedWorld(f)) {
                Chunk c = loaded.loader.loadNow(0, 0);
                check(c.isReadOnly(), "failed quarantine must protect generated chunk");
                loaded.world.setBlock(1, 100, 1, BlockType.GLASS);
                saveAll(f.save, loaded.world);
                f.save.saveChunkAsync("world", snapshot(c));
                f.save.flushAndAwait();
                check(Files.isDirectory(f.chunkPath()) && Arrays.equals(evidence, Files.readAllBytes(child)), "failed quarantine modified source");
                check(f.save.drainWorldWarnings("world").get(0).kind() == WorldWarning.Kind.QUARANTINE_FAILED, "failed warning missing");
            }
        }
    }

    private static void directWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] evidence = { 71, 72, 73 };
            Files.write(f.chunkPath(), evidence);
            f.save.saveChunkAsync("world", snapshot(new Chunk(0, 0)));
            f.save.flushAndAwait();
            check(Arrays.equals(evidence, Files.readAllBytes(f.chunkPath())), "unchecked write replaced unreadable chunk");
            Files.write(f.levelPath(), evidence);
            SaveManager direct = new SaveManager(f.root.toFile());
            direct.saveLevel("world", level());
            direct.flushAndAwait();
            check(Arrays.equals(evidence, Files.readAllBytes(f.levelPath())), "unchecked write replaced unreadable level");
        }
    }

    private static void chunkChecksum() throws Exception {
        try (Fixture f = new Fixture()) {
            f.save.saveChunkAsync("world", snapshot(new Chunk(0, 0)));
            f.save.flushAndAwait();
            byte[] evidence = damageChecksum(f.chunkPath());
            check(f.save.readChunk("world", 0, 0) instanceof ChunkLoad.Unreadable, "chunk gzip checksum was not checked");
            Path quarantined = f.save.quarantineChunk("world", 0, 0);
            check(Arrays.equals(evidence, Files.readAllBytes(quarantined)), "checksum evidence changed");
        }
    }

    private static void saveAll(SaveManager save, World world) {
        for (Chunk c : world.getLoadedChunks()) {
            if (c.modified && !c.isReadOnly()) save.saveChunkAsync("world", snapshot(c));
        }
        save.flushAndAwait();
    }

    private static ChunkSnapshot snapshot(Chunk c) {
        return new ChunkSnapshot(c.cx, c.cz, c.copyBlocks(), c.copyMeta());
    }

    private static byte[] damageChecksum(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 8] ^= 0x41;
        Files.write(file, bytes);
        return bytes;
    }

    private static void header(Path file, int version) throws Exception {
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(SaveFormat.MAGIC);
            out.writeInt(version);
            out.writeInt(version); // minimum reader version for modern level/chunk headers
        }
    }

    private static final class LoadedWorld implements AutoCloseable {
        final World world = new World(12345L);
        final ChunkLoader loader;
        LoadedWorld(Fixture f) {
            loader = new ChunkLoader(world, null, f.save, "world");
            loader.setMeshing(false);
        }
        @Override public void close() { loader.shutdown(); }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("mineclone-save-safety-");
        final SaveManager save = new SaveManager(root.toFile());
        Fixture() throws Exception { Files.createDirectories(root.resolve("world/chunks")); }
        Path levelPath() { return root.resolve("world/level.dat"); }
        Path chunkPath() { return root.resolve("world/chunks/c.0.0.dat"); }
        DedicatedServer server() throws Exception {
            Path config = root.resolve("server.properties");
            Files.writeString(config, "saves-dir=" + root.toString().replace('\\', '/')
                    + "\nworld=world\ndirect=false\nphoton=false\nupnp=false\n");
            return new DedicatedServer(ServerConfig.load(config.toFile()));
        }
        @Override public void close() throws Exception {
            save.flushAndAwait();
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
