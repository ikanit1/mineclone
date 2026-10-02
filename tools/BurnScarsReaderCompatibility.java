import com.mineclone.save.*;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.world.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Actual pre-scar reader 9 preserves optional burn history, metadata and finite animal identity. */
public final class BurnScarsReaderCompatibility {
    private static final String WORLD = "burn-reader-fixture", MARKER = "mineclone-burn-reader-v1";
    private static final byte[] TAIL = { 21, 34, -1 }, OPAQUE = { 7, 9, 11 };
    private static final long BODY = 877;
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("write|rewrite|verify <fresh out-test fixture>");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        check(root.startsWith(Path.of("out-test").toAbsolutePath().normalize()), "only checkout out-test fixtures");
        if (args[0].equals("write")) {
            check(!Files.exists(root), "refusing to replace existing fixture");
            Files.createDirectories(root); Files.writeString(root.resolve("fixture.marker"), MARKER);
            byte[] scar = ByteBuffer.allocate(45).putInt(0x4255524e).put((byte) 1).putInt(0).putInt(0)
                    .putLong(7).put((byte) 2).putShort((short) (Chunk.idx(6, 64, 6) | 0x8000)).putLong(3)
                    .putShort((short) Chunk.idx(10, 64, 10)).putLong(5).put(TAIL).array();
            var region = new EcologyRegion(0, 0, 7); region.scars(List.of(scar, OPAQUE));
            byte[] ecology = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of(), TAIL));
            Files.write(root.resolve("expected-ecology.bin"), ecology);
            SaveManager save = manager(root);
            try {
                save.saveLevel(WORLD, new LevelData("Burn compatibility", 20261006, 6.5, 65, 6.5, 78f,
                        GameMode.CREATIVE, 1L, PlayerRecord.builder().pose(6.5, 65, 6.5, 0, 0, 0).build(),
                        Map.of("ecology", ecology, "future-level", OPAQUE)));
                byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME], meta = new byte[blocks.length];
                blocks[Chunk.idx(6, 64, 6)] = (byte) BlockType.GRASS.ordinal();
                blocks[Chunk.idx(6, 65, 6)] = (byte) BlockType.SAPLING.ordinal();
                meta[Chunk.idx(6, 65, 6)] = 4;
                blocks[Chunk.idx(10, 64, 10)] = (byte) BlockType.DIRT.ordinal();
                save.saveChunkAsync(WORLD, new ChunkSnapshot(0, 0, blocks, meta, null, null, null,
                        Map.of("future-chunk", OPAQUE), 0, new long[0],
                        List.of(new SavedMob("COW", BODY, 8.5f, 65, 6.5f, 0, 10, TAIL))));
                save.flushAndAwait();
                System.out.println("BURN_COMPAT_WRITE PASS " + provenance());
            } finally { stop(save); }
            return;
        }
        check(Files.readString(root.resolve("fixture.marker")).equals(MARKER), "not a tool-owned fixture");
        SaveManager save = manager(root);
        try {
            var result = save.readLevel(WORLD);
            check(result instanceof LevelLoad.Loaded, "level refused: " + result);
            LevelData level = ((LevelLoad.Loaded) result).data();
            byte[] bytes = level.extraSections.get("ecology");
            check(Arrays.equals(bytes, Files.readAllBytes(root.resolve("expected-ecology.bin"))), "opaque ecology changed");
            check(Arrays.equals(bytes, EcologyCodec.encode(EcologyCodec.decode(bytes))), "codec erased history");
            check(Arrays.equals(level.extraSections.get("future-level"), OPAQUE), "level tail lost");
            var chunkLoad = save.readChunk(WORLD, 0, 0);
            check(chunkLoad instanceof ChunkLoad.Loaded, "chunk refused: " + chunkLoad);
            ChunkSnapshot chunk = ((ChunkLoad.Loaded) chunkLoad).snapshot();
            check(Byte.toUnsignedInt(chunk.blocks[Chunk.idx(6, 65, 6)]) == BlockType.SAPLING.ordinal()
                    && chunk.meta[Chunk.idx(6, 65, 6)] == 4
                    && Byte.toUnsignedInt(chunk.blocks[Chunk.idx(10, 64, 10)]) == BlockType.DIRT.ordinal()
                    && chunk.mobs.size() == 1 && chunk.mobs.get(0).id() == BODY
                    && Arrays.equals(chunk.mobs.get(0).tail(), TAIL)
                    && Arrays.equals(chunk.extra.get("future-chunk"), OPAQUE), "terrain, metadata or animal changed");
            if (args[0].equals("rewrite")) {
                check(SaveFormat.CHUNK_VERSION == 9, "actual reader 9 required");
                try { Class.forName("com.mineclone.sim.ecology.BurnScars"); throw new AssertionError("new classes on old classpath"); }
                catch (ClassNotFoundException expected) { }
                save.saveLevel(WORLD, new LevelData("Rewritten by actual pre-scar reader 9", level.seed,
                        level.spawnX, level.spawnY, level.spawnZ, level.timeOfDay, level.gameMode, level.lastPlayed,
                        level.player, level.extraSections));
                save.saveChunkAsync(WORLD, chunk); save.flushAndAwait();
                Files.writeString(root.resolve("old-reader.txt"), provenance());
                System.out.println("BURN_COMPAT_REWRITE PASS opaqueHistory=true " + provenance());
            } else if (args[0].equals("verify")) {
                check(Files.exists(root.resolve("old-reader.txt"))
                                && level.name.equals("Rewritten by actual pre-scar reader 9")
                                && Files.readString(root.resolve("old-reader.txt")).contains("pre-burn-classes"),
                        "actual old-reader rewrite phase missing");
                var records = EcologyCodec.decode(bytes).regions().get(0).scars();
                Class<?> scarClass = Class.forName("com.mineclone.sim.ecology.BurnScars$Scar");
                Object scar = scarClass.getMethod("decode", byte[].class).invoke(null, records.get(0));
                List<?> sites = (List<?>) scarClass.getMethod("sites").invoke(scar);
                Class<?> siteClass = Class.forName("com.mineclone.sim.ecology.BurnScars$Site");
                check((int) scarClass.getMethod("cx").invoke(scar) == 0
                        && (int) scarClass.getMethod("cz").invoke(scar) == 0
                        && (long) scarClass.getMethod("lastProcessedDay").invoke(scar) == 7
                        && sites.size() == 2
                        && (int) siteClass.getMethod("index").invoke(sites.get(0)) == Chunk.idx(6, 64, 6)
                        && (long) siteClass.getMethod("burnDay").invoke(sites.get(0)) == 3
                        && (boolean) siteClass.getMethod("plantedOnce").invoke(sites.get(0))
                        && (int) siteClass.getMethod("index").invoke(sites.get(1)) == Chunk.idx(10, 64, 10)
                        && (long) siteClass.getMethod("burnDay").invoke(sites.get(1)) == 5
                        && !(boolean) siteClass.getMethod("plantedOnce").invoke(sites.get(1))
                        && Arrays.equals((byte[]) scarClass.getMethod("tail").invoke(scar), TAIL)
                        && Arrays.equals(records.get(1), OPAQUE), "known scar or opaque record lost");
                System.out.println("BURN_COMPAT_VERIFY PASS dates=true plantedFlag=true naturalMeta=true body877=true tails=true " + provenance());
            } else throw new IllegalArgumentException("unknown phase");
        } finally { stop(save); }
    }
    private static SaveManager manager(Path root) { return new SaveManager(root.resolve("saves").toFile()); }
    private static String provenance() { return "chunkReader=" + SaveFormat.CHUNK_VERSION
            + " classes=" + SaveManager.class.getProtectionDomain().getCodeSource().getLocation(); }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void stop(SaveManager save) throws Exception {
        save.flushAndAwait(); var field = SaveManager.class.getDeclaredField("chunkWriter"); field.setAccessible(true);
        ((ExecutorService) field.get(save)).shutdown();
    }
}
