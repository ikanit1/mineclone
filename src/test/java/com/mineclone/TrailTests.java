package com.mineclone;

import com.mineclone.save.SaveManager;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.ChunkLoad;
import com.mineclone.save.RunLengthCodec;
import com.mineclone.data.SectionCodec;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participant;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.Trample;
import com.mineclone.world.BlockTicker;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.GameMode;
import com.mineclone.world.World;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** LW-07: walking makes durable trails in the authoritative world. */
final class TrailTests {
    static void runAll(TestMain.Runner r) {
        r.run("twenty walks deepen a surface trail and ten quiet days erase it", TrailTests::walkAndRegrow);
        r.run("trails survive a real chunk save, unload and restore", TrailTests::saveAndRestore);
        r.run("a moving cow presses the same path as a participant", TrailTests::animal);
        r.run("visual trail stages avoid terrain remeshing on each step", TrailTests::visualStages);
        r.run("steps clear snow; snowfall returns after pressure fades", TrailTests::snow);
        r.run("trail section bounds and damaged bytes are rejected", TrailTests::bounds);
        r.run("a newer trail section leaves its saved chunk untouched", TrailTests::futureSectionProtected);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final class Walker implements Participant {
        final Vector3f at = new Vector3f(2.5f, 66f, 8.5f);
        @Override public int id() { return 1; }
        @Override public Vector3fc position() { return at; }
        @Override public Vector3fc eye() { return new Vector3f(at).add(0, 1.62f, 0); }
        @Override public GameMode mode() { return GameMode.SURVIVAL; }
        @Override public boolean alive() { return true; }
        @Override public ArmorView armor() { return ArmorView.NONE; }
        @Override public boolean local() { return false; }
        @Override public boolean damage(DamageSource source, float amount) { return true; }
    }

    private static World grassWorld() {
        World world = new World(42L);
        Chunk chunk = world.publish(new Chunk(0, 0));
        for (int x = 1; x <= 12; x++)
            for (int z = 7; z <= 9; z++) {
                world.setBlock(x, 65, z, BlockType.GRASS);
                world.setBlock(x, 66, z, BlockType.AIR);
                world.setBlock(x, 67, z, BlockType.AIR);
            }
        chunk.computeSkyLight();
        return world;
    }

    private static void walkAndRegrow() {
        World world = grassWorld();
        Walker walker = new Walker();
        Participants people = new Participants();
        people.put(walker);
        WorldSession session = new WorldSession(world, new WorldClock(0.5), new MobSpawner(42L),
                new EntityStore(), people, new WorldSession.Host() {
                    @Override public Vector3f mobFocus() { return walker.at; }
                    @Override public boolean spawnMobs() { return false; }
                }, WorldEvents.NONE);
        session.tickMobs(0.05f); // A player appearing in a cell does not press it.
        for (int trip = 0; trip < 10; trip++) {
            for (int x = 3; x <= 10; x++) {
                walker.at.x = x + 0.5f;
                session.tickMobs(0.05f);
            }
            for (int x = 9; x >= 2; x--) {
                walker.at.x = x + 0.5f;
                session.tickMobs(0.05f);
            }
        }
        for (int x = 3; x <= 9; x++) {
            check(world.getBlock(x, 65, 8) == BlockType.GRASS, "walking replaced the ground block at " + x);
            check(world.getBlockMeta(x, 65, 8) == 5, "walking did not deepen the surface at " + x);
        }
        check(world.getBlockMeta(5, 65, 7) == 0, "a neighbouring cell was trampled");
        BlockTicker ticker = new BlockTicker(1L);
        ticker.apply(world, 5, 65, 8);
        check(world.getBlockMeta(5, 65, 8) == 5, "fresh path faded immediately");
        world.setTrampleDay(10);
        Trample.advanceChunk(world, world.getChunkIfExists(0, 0), 10);
        for (int x = 3; x <= 9; x++)
            check(world.getBlock(x, 65, 8) == BlockType.GRASS && world.getBlockMeta(x, 65, 8) == 0,
                    "quiet path did not disappear at " + x);
    }

    private static void saveAndRestore() throws Exception {
        Path root = Files.createTempDirectory("mineclone-trails-");
        ChunkLoader loader = null;
        try {
            World world = grassWorld();
            for (int i = 0; i < 9; i++) Trample.step(world, 8.5f, 66f, 8.5f, 2);
            Chunk c = world.getChunkIfExists(0, 0);
            check(world.getBlock(8, 65, 8) == BlockType.GRASS && world.getBlockMeta(8, 65, 8) == 3,
                    "test trail did not form on grass");
            WorldSession session = new WorldSession(world, new WorldClock(0.5), new MobSpawner(1),
                    new EntityStore(), new Participants(), () -> new Vector3f(), WorldEvents.NONE);
            var snapshot = session.snapshotChunk(c);
            check(snapshot != null && snapshot.extra.containsKey(Trample.SECTION), "trail missed save snapshot");
            SaveManager save = new SaveManager(root.toFile());
            save.saveChunkAsync("trail", snapshot);
            save.flushAndAwait();
            World reopened = new World(42L);
            loader = new ChunkLoader(reopened, null, save, "trail");
            loader.setMeshing(false);
            Chunk loaded = loader.loadNow(0, 0);
            check(loaded.get(8, 65, 8) == BlockType.GRASS && loaded.getMeta(8, 65, 8) == 3,
                    "saved path stage vanished");
            check(Trample.pressureAt(reopened, 8, 65, 8) == 9, "trail count vanished on load");
            reopened.setTrampleDay(12);
            Trample.advanceChunk(reopened, loaded, 12);
            check(loaded.get(8, 65, 8) == BlockType.GRASS && loaded.getMeta(8, 65, 8) == 0,
                    "unloaded days did not erase the path");
        } finally {
            if (loader != null) loader.shutdown();
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void animal() {
        World world = grassWorld();
        EntityStore entities = new EntityStore();
        Mob cow = new Mob(MobType.COW, 2.5f, 66f, 8.5f, new java.util.Random(4));
        entities.mobs.add(cow);
        WorldSession session = new WorldSession(world, new WorldClock(0.5), new MobSpawner(42L),
                entities, new Participants(), new WorldSession.Host() {
                    @Override public Vector3f mobFocus() { return new Vector3f(2.5f, 66f, 8.5f); }
                    @Override public boolean spawnMobs() { return false; }
                }, WorldEvents.NONE);
        session.tickMobs(0.05f);
        for (int trip = 0; trip < 8; trip++) {
            for (int x = 3; x <= 10; x++) {
                cow.position.set(x + 0.5f, 66f, 8.5f);
                session.tickMobs(0.05f);
            }
            for (int x = 9; x >= 2; x--) {
                cow.position.set(x + 0.5f, 66f, 8.5f);
                session.tickMobs(0.05f);
            }
        }
        check(world.getBlock(5, 65, 8) == BlockType.GRASS && world.getBlockMeta(5, 65, 8) >= 3,
                "the cow did not leave a surface path");
    }

    private static void visualStages() {
        World world = grassWorld();
        Chunk chunk = world.getChunkIfExists(0, 0);
        check(chunk.clearDirtyIfCurrent(chunk.contentVersion()), "test chunk was not prepared");
        int[] packets = { 0 };
        world.setBlockObserver((x, y, z, old, now, meta) -> packets[0]++);
        for (int i = 0; i < 5; i++)
            Trample.step(world, 5.5f, 66f, 8.5f, 0);
        check(world.getBlock(5, 65, 8) == BlockType.GRASS, "visual wear replaced grass");
        check(world.getBlockMeta(5, 65, 8) == 2, "five steps did not reach stage two");
        check(chunk.trailVisuals().get(Chunk.idx(5, 65, 8)) == 2, "renderer lost the visual cell");
        check(packets[0] == 2, "every footfall sent a network update: " + packets[0]);
        check(!chunk.isDirty(), "visual wear rebuilt the whole terrain mesh");
        check(chunk.modified, "pressure-only changes were omitted from saves");
    }

    private static void snow() {
        World world = new World(33L);
        int x = 0, z = 0;
        outer: for (int cx = -12; cx <= 12; cx++)
            for (int cz = -12; cz <= 12; cz++) {
                int wx = cx * 16 + 8, wz = cz * 16 + 8;
                if (world.biomes.biomeAt(wx, wz).isCold()) { x = wx; z = wz; break outer; }
            }
        check(world.biomes.biomeAt(x, z).isCold(), "no cold test site");
        world.getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        world.setBlock(x, 65, z, BlockType.DIRT);
        world.setBlock(x, 66, z, BlockType.SNOW_LAYER, (byte) 7);
        for (int i = 0; i < 18; i++) Trample.step(world, x + 0.5f, 67f, z + 0.5f, 0);
        check(world.getBlock(x, 66, z) == BlockType.AIR, "walking did not clear snow");
        BlockTicker ticker = new BlockTicker(33L);
        ticker.apply(world, x, 66, z, false, true);
        check(world.getBlock(x, 66, z) == BlockType.AIR, "snow covered an active trail");
        world.setTrampleDay(10);
        Trample.advanceChunk(world, world.getChunkIfExists(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), 10);
        ticker.apply(world, x, 66, z, false, true);
        check(world.getBlock(x, 66, z) == BlockType.SNOW_LAYER, "snowfall did not cover an abandoned trail");
    }

    private static void bounds() throws Exception {
        Trample tracks = new Trample(1);
        for (int i = 0; i < Trample.MAX_ENTRIES; i++) check(tracks.press(i) == 1, "pressure lost at " + i);
        check(tracks.press(Trample.MAX_ENTRIES) == 0, "trail section exceeded its cap");
        check(Trample.decode(tracks.encode()).size() == Trample.MAX_ENTRIES, "bounded section did not round-trip");
        byte[] corrupt = tracks.encode();
        corrupt[0] = 2;
        try { Trample.decode(corrupt); throw new AssertionError("newer trail version accepted"); }
        catch (IOException expected) { /* The chunk reader protects this save. */ }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(1); out.writeLong(0); out.writeShort(2);
            out.writeShort(3); out.writeByte(1); out.writeShort(3); out.writeByte(1);
        }
        try { Trample.decode(bytes.toByteArray()); throw new AssertionError("duplicate trail cells accepted"); }
        catch (IOException expected) { /* Duplicates cannot hide in a saved chunk. */ }
    }

    private static void futureSectionProtected() throws Exception {
        Path root = Files.createTempDirectory("mineclone-future-trail-");
        ChunkLoader loader = null;
        try {
            Path chunk = root.resolve("trail/chunks/" + SaveFormat.chunkFileName(0, 0));
            Files.createDirectories(chunk.getParent());
            byte[] emptyCount = new byte[4];
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(chunk)))) {
                out.writeInt(SaveFormat.MAGIC);
                out.writeInt(SaveFormat.CHUNK_VERSION);
                out.writeInt(SaveFormat.CHUNK_VERSION);
                RunLengthCodec.write(out, new byte[SaveFormat.CHUNK_VOLUME]);
                RunLengthCodec.write(out, new byte[SaveFormat.CHUNK_VOLUME]);
                SectionCodec.write(out, Map.of("chests", emptyCount, "furnaces", emptyCount,
                        "items", emptyCount, Trample.SECTION, new byte[] { 2 }));
            }
            byte[] original = Files.readAllBytes(chunk);
            SaveManager save = new SaveManager(root.toFile());
            check(save.readChunk("trail", 0, 0) instanceof ChunkLoad.TooNew,
                    "newer section was accepted or treated as corruption");
            World world = new World(42L);
            loader = new ChunkLoader(world, null, save, "trail");
            loader.setMeshing(false);
            check(loader.loadNow(0, 0).isReadOnly(), "newer trail source was not protected");
            save.saveChunkAsync("trail", new com.mineclone.save.ChunkSnapshot(0, 0,
                    new byte[SaveFormat.CHUNK_VOLUME], new byte[SaveFormat.CHUNK_VOLUME],
                    Map.of(), Map.of(), java.util.List.of()));
            save.flushAndAwait();
            check(Arrays.equals(original, Files.readAllBytes(chunk)), "newer trail source was overwritten");
        } finally {
            if (loader != null) loader.shutdown();
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
