import com.mineclone.save.*;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Actual reader-8 rewrite of optional lair bytes and refusal to overwrite reader-9 terrain. */
public final class LairReaderCompatibility {
    private static final String WORLD = "lair-reader-fixture", MARKER = "mineclone-lair-reader-compatibility-v1";
    private static final byte[] TAIL = { 21, 34, -1 }, OPAQUE = { 7, 9, 11 };
    private static final long BODY = 701;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("write|rewrite|verify <fresh out-test fixture>");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        if (!root.startsWith(Path.of("out-test").toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Only this checkout's out-test fixtures are allowed");
        if (args[0].equals("write")) write(root);
        else {
            check(Files.readString(root.resolve("fixture.marker")).equals(MARKER), "not a tool-owned fixture");
            if (args[0].equals("rewrite")) rewrite(root);
            else if (args[0].equals("verify")) verify(root);
            else throw new IllegalArgumentException("unknown phase");
        }
    }

    private static void write(Path root) throws Exception {
        if (Files.exists(root)) throw new IllegalArgumentException("refusing to replace an existing fixture");
        Class<?> lairClass = Class.forName("com.mineclone.sim.ecology.Lair");
        Class<?> tableClass = Class.forName("com.mineclone.sim.ecology.LairTable");
        Object table = tableClass.getMethod("get").invoke(null);
        Object definition = tableClass.getMethod("byType", com.mineclone.world.entity.MobType.class)
                .invoke(table, com.mineclone.world.entity.MobType.SPIDER);
        Object lair = lairClass.getConstructor(int.class, int.class, int.class, long.class, definition.getClass())
                .newInstance(6, 65, 6, 0L, definition);
        check((boolean) lairClass.getMethod("claim", long.class).invoke(lair, BODY), "claim failed");
        byte[] lairBytes = append((byte[]) lairClass.getMethod("encode").invoke(lair), TAIL);
        var region = new EcologyRegion(0, 0, 0);
        region.lairs(List.of(lairBytes, OPAQUE));
        byte[] ecology = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of(), TAIL));
        Class<?> spotsClass = Class.forName("com.mineclone.sim.ecology.LairSpots");
        Object spots = spotsClass.getConstructor(int[].class).newInstance((Object) new int[] { Chunk.idx(10, 65, 10) });
        byte[] spotBytes = append((byte[]) spotsClass.getMethod("encode").invoke(spots), TAIL);
        Files.createDirectories(root);
        Files.writeString(root.resolve("fixture.marker"), MARKER);
        Files.write(root.resolve("expected-ecology.bin"), ecology);
        Files.write(root.resolve("expected-spots.bin"), spotBytes);
        SaveManager save = manager(root);
        try {
            save.saveLevel(WORLD, new LevelData("Lair compatibility", 20261002, 6.5, 65, 6.5, 78f,
                    GameMode.CREATIVE, 1L, PlayerRecord.builder().pose(6.5, 65, 6.5, 0, 0, 0).build(), Map.of("ecology", ecology)));
            byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME];
            blocks[Chunk.idx(6, 65, 6)] = (byte) BlockType.valueOf("SPIDER_NEST").ordinal();
            var spider = new SavedMob("SPIDER", BODY, 8.5f, 65, 6.5f, 0, 8, TAIL);
            save.saveChunkAsync(WORLD, new ChunkSnapshot(0, 0, blocks, new byte[blocks.length],
                    null, null, null, Map.of("lair_spots", spotBytes), 0, new long[0], List.of(spider)));
            save.flushAndAwait();
            Files.write(root.resolve("expected-chunk.bin"), Files.readAllBytes(chunkFile(root)));
            System.out.println("LAIR_COMPAT_WRITE PASS " + provenance());
        } finally { stop(save); }
    }

    private static void rewrite(Path root) throws Exception {
        check(SaveFormat.CHUNK_VERSION == 8, "rewrite requires actual pre-lair reader 8");
        try { Class.forName("com.mineclone.sim.ecology.Lair"); throw new AssertionError("new lair classes on old classpath"); }
        catch (ClassNotFoundException expected) { }
        SaveManager save = manager(root);
        try {
            LevelData level = level(save);
            byte[] bytes = level.extraSections.get("ecology");
            check(Arrays.equals(bytes, EcologyCodec.encode(EcologyCodec.decode(bytes))), "old ecology codec changed opaque lair bytes");
            save.saveLevel(WORLD, new LevelData("Rewritten by actual reader 8", level.seed, level.spawnX, level.spawnY,
                    level.spawnZ, level.timeOfDay, level.gameMode, level.lastPlayed, level.player, level.extraSections));
            ChunkLoad load = save.readChunk(WORLD, 0, 0);
            check(load instanceof ChunkLoad.TooNew, "reader 8 accepted block 64: " + load);
            save.saveChunkAsync(WORLD, new ChunkSnapshot(0, 0, new byte[SaveFormat.CHUNK_VOLUME], new byte[SaveFormat.CHUNK_VOLUME]));
            save.flushAndAwait();
            check(Arrays.equals(Files.readAllBytes(root.resolve("expected-chunk.bin")), Files.readAllBytes(chunkFile(root))),
                    "reader 8 overwrote protected terrain");
            check(Arrays.equals(bytes, level(save).extraSections.get("ecology")), "old writer changed optional ecology");
            Files.writeString(root.resolve("old-reader.txt"), provenance());
            System.out.println("LAIR_COMPAT_REWRITE PASS opaqueLairs=true chunkTooNew=true attemptedWriteBlocked=true " + provenance());
        } finally { stop(save); }
    }

    private static void verify(Path root) throws Exception {
        check(Files.exists(root.resolve("old-reader.txt")), "old-reader phase missing");
        SaveManager save = manager(root);
        try {
            LevelData level = level(save);
            byte[] ecology = level.extraSections.get("ecology");
            check(level.name.equals("Rewritten by actual reader 8"), "old writer did not run");
            check(Arrays.equals(ecology, Files.readAllBytes(root.resolve("expected-ecology.bin"))), "ecology bytes lost");
            List<byte[]> records = EcologyCodec.decode(ecology).regions().get(0).lairs();
            Class<?> lairClass = Class.forName("com.mineclone.sim.ecology.Lair");
            Object lair = lairClass.getMethod("decode", byte[].class).invoke(null, records.get(0));
            check((int) lairClass.getMethod("population").invoke(lair) == 6
                    && ((Set<?>) lairClass.getMethod("bodyIds").invoke(lair)).contains(BODY)
                    && Arrays.equals((byte[]) lairClass.getMethod("tail").invoke(lair), TAIL)
                    && Arrays.equals(records.get(1), OPAQUE), "lair state or unknown records lost");
            ChunkLoad load = save.readChunk(WORLD, 0, 0);
            check(load instanceof ChunkLoad.Loaded, "current reader failed: " + load);
            ChunkSnapshot chunk = ((ChunkLoad.Loaded) load).snapshot();
            check(Byte.toUnsignedInt(chunk.blocks[Chunk.idx(6, 65, 6)]) == BlockType.valueOf("SPIDER_NEST").ordinal()
                    && chunk.mobs.size() == 1 && chunk.mobs.get(0).id() == BODY && chunk.mobs.get(0).type().equals("SPIDER")
                    && Arrays.equals(chunk.extra.get("lair_spots"), Files.readAllBytes(root.resolve("expected-spots.bin"))),
                    "heart, spider ID or cave hints changed");
            System.out.println("LAIR_COMPAT_VERIFY PASS heart64=true population6=true body701=true opaqueTails=true " + provenance());
        } finally { stop(save); }
    }

    private static byte[] append(byte[] bytes, byte[] tail) {
        byte[] result = Arrays.copyOf(bytes, bytes.length + tail.length);
        System.arraycopy(tail, 0, result, bytes.length, tail.length); return result;
    }
    private static SaveManager manager(Path root) { return new SaveManager(root.resolve("saves").toFile()); }
    private static Path chunkFile(Path root) { return root.resolve("saves").resolve(WORLD).resolve("chunks/c.0.0.dat"); }
    private static LevelData level(SaveManager save) {
        var result = save.readLevel(WORLD);
        check(result instanceof LevelLoad.Loaded, "level refused: " + result); return ((LevelLoad.Loaded) result).data();
    }
    private static String provenance() { return "chunkReader=" + SaveFormat.CHUNK_VERSION
            + " classes=" + SaveManager.class.getProtectionDomain().getCodeSource().getLocation(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void stop(SaveManager save) throws Exception {
        save.flushAndAwait(); var field = SaveManager.class.getDeclaredField("chunkWriter"); field.setAccessible(true);
        ((ExecutorService) field.get(save)).shutdown();
    }
}
