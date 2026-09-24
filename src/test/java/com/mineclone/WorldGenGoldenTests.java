package com.mineclone;

import com.mineclone.data.SectionCodec;
import com.mineclone.save.LevelData;
import com.mineclone.save.LevelLoad;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.Chunk;
import com.mineclone.world.GameMode;
import com.mineclone.world.GenProfile;
import com.mineclone.world.World;
import com.mineclone.world.gen.GenFeatures;
import com.mineclone.world.gen.GenPolicy;
import com.mineclone.world.gen.WorldGenSettings;
import com.mineclone.world.gen.WorldGenVersion;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GEN-01: the 1.0 generator is frozen. {@code fixtures/worldgen-v1.txt} holds
 * SHA-1 of blocks and meta for 120 chunks — three seeds, negative and far
 * coordinates, every biome and every structure kind — and was written by
 * {@code tools/MakeWorldGenGolden.java}, whose output is identical when run
 * against the v1.0.0-alpha tag. A mismatch means old worlds would change.
 */
final class WorldGenGoldenTests {
    static void runAll(TestMain.Runner r) {
        r.run("V1 generation reproduces the 1.0 golden hashes of 120 chunks", WorldGenGoldenTests::golden);
        r.run("a world's policy and a blank twin generate like the explicit V1 generator", WorldGenGoldenTests::policy);
        r.run("worldgen section round-trips every feature set and reads the M0 and absent forms", WorldGenGoldenTests::section);
        r.run("a level made by an unknown generator is refused and never rewritten", WorldGenGoldenTests::unknownGenerator);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private record Golden(long seed, int cx, int cz, String label, String sha1) {}

    private static List<Golden> fixture() throws Exception {
        List<Golden> result = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/fixtures/worldgen-v1.txt"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cells = line.split("\\|");
            result.add(new Golden(Long.parseLong(cells[0]), Integer.parseInt(cells[1]), Integer.parseInt(cells[2]),
                    cells[3], cells[4]));
        }
        return result;
    }

    static String hash(Chunk chunk) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(chunk.copyBlocks());
        sha.update(chunk.copyMeta());
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void golden() throws Exception {
        List<Golden> chunks = fixture();
        check(chunks.size() == 120, "golden holds " + chunks.size() + " chunks");
        for (String label : new String[] { "biome:OCEAN", "biome:ALPINE", "structure:RUIN", "structure:HUT",
                "structure:OBELISK", "structure:DUNGEON" })
            check(chunks.stream().filter(g -> g.label().equals(label)).count() == 3, "golden lost coverage of " + label);
        List<String> changed = new ArrayList<>();
        World world = null;
        for (Golden g : chunks) {
            if (world == null || world.seed != g.seed())
                world = new World(g.seed(), GenProfile.NORMAL, GenPolicy.fixed(WorldGenVersion.V1));
            String actual = hash(world.generateDetached(g.cx(), g.cz(), GenFeatures.V1));
            if (!actual.equals(g.sha1())) changed.add(g.seed() + " " + g.cx() + "," + g.cz() + " " + g.label());
        }
        check(changed.isEmpty(), changed.size() + " V1 chunks changed — old worlds would change under players: "
                + changed.subList(0, Math.min(8, changed.size())));
    }

    private static void policy() throws Exception {
        Golden g = fixture().get(0);
        String expected = g.sha1();
        World legacy = new World(g.seed());
        check(hash(legacy.generateDetached(g.cx(), g.cz())).equals(expected), "default world is not V1");
        World twin = legacy.blankTwin();
        check(twin.seed == legacy.seed && twin.genPolicy() == legacy.genPolicy()
                && twin.getChunkIfExists(g.cx(), g.cz()) == null, "twin shares generator but not chunks");
        check(hash(twin.getChunk(g.cx(), g.cz())).equals(expected), "twin generates differently");
        int[] asked = {0};
        World counted = new World(g.seed(), GenProfile.NORMAL, (cx, cz) -> { asked[0]++; return WorldGenVersion.V1; });
        counted.generateDetached(3, -4);
        check(asked[0] == 1, "the policy is not consulted per chunk");
        check(GenFeatures.of(WorldGenVersion.V1).equals(GenFeatures.V1) && GenFeatures.V1.bits() == 0,
                "V1 has no 1.1 changes");
        check(GenFeatures.V1.within(GenFeatures.V2), "V2 lost something V1 generates");
        check(WorldGenVersion.byId(1) == WorldGenVersion.V1 && WorldGenVersion.byId(2) == WorldGenVersion.V2
                && WorldGenVersion.byId(0) == null && WorldGenVersion.byId(3) == null, "stable version ids");
        var fresh = WorldGenSettings.forNewWorld();
        check(fresh.version() == WorldGenVersion.LATEST && fresh.features().equals(GenFeatures.of(WorldGenVersion.LATEST)),
                "new worlds use the latest generator with everything it implements");
        // A guest builds the host's world from the seed with V1 (Game.startRemoteWorld) and
        // the welcome packet does not name a generator yet: a V2 host would send wrong deltas.
        check(WorldGenVersion.LATEST == WorldGenVersion.V1, "new worlds moved past V1 before guests can learn the"
                + " host's generator: carry the worldgen settings in S_WELCOME (GEN-02), then update this check");
        // The recorded features, not the build's own set, decide what a world's land gets
        // (the two can only differ once V2 implements a change).
        var recorded = new WorldGenSettings(WorldGenVersion.V2, GenFeatures.V1, 0).policy();
        check(recorded.versionAt(-9, 4) == WorldGenVersion.V2 && recorded.featuresAt(-9, 4).equals(GenFeatures.V1),
                "a world's policy ignores the features it recorded");
        check(new GenFeatures(true, false, false, false, false).bits() == 1
                && new GenFeatures(false, false, false, false, true).bits() == 1 << 4, "persisted bits moved");
    }

    private static void section() {
        var allFlags = GenFeatures.fromBits((1 << GenFeatures.KNOWN_FLAGS) - 1);
        for (int bits = 0; bits < 1 << GenFeatures.KNOWN_FLAGS; bits++) {
            var features = GenFeatures.fromBits(bits);
            check(features.bits() == bits, "feature bits do not round-trip: " + bits);
            // The record refuses a flag its version lacks; V2 grows as 1.1 lands, so only
            // flags it already implements are exercised here.
            if (!features.within(GenFeatures.V2)) continue;
            var settings = new WorldGenSettings(WorldGenVersion.V2, features, 1_727_000_000_000L + bits);
            byte[] bytes = settings.encode();
            check(bytes.length == 16 && WorldGenSettings.decode(bytes).equals(settings), "round trip " + settings);
        }
        check(allFlags.bits() == 0b11111, "all known flags");
        check(WorldGenSettings.decode(null).equals(WorldGenSettings.LEGACY)
                && WorldGenSettings.LEGACY.version() == WorldGenVersion.V1
                && WorldGenSettings.LEGACY.features().equals(GenFeatures.V1), "absent section is plain V1");
        byte[] m0 = ByteBuffer.allocate(Integer.BYTES).putInt(1).array();
        check(WorldGenSettings.decode(m0).equals(WorldGenSettings.LEGACY) && WorldGenSettings.storedFeatures(m0) == 0,
                "the M0 four-byte form");
        byte[][] bad = {
                new byte[3], new byte[12], new byte[17],
                ByteBuffer.allocate(4).putInt(99).array(),
                ByteBuffer.allocate(16).putInt(99).putInt(0).putLong(0).array(),
                ByteBuffer.allocate(16).putInt(1).putInt(1 << GenFeatures.KNOWN_FLAGS).putLong(0).array(),
                ByteBuffer.allocate(16).putInt(1).putInt(1).putLong(0).array(),   // V1 is frozen: no 1.1 changes
                ByteBuffer.allocate(16).putInt(1).putInt(0).putLong(-5).array(),
        };
        for (byte[] section : bad) {
            boolean refused = false;
            try { WorldGenSettings.decode(section); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "malformed or unknown worldgen section accepted: " + HexFormat.of().formatHex(section));
        }
        check(WorldGenSettings.storedVersion(ByteBuffer.allocate(4).putInt(99).array()) == 99, "stored version");
        check(WorldGenSettings.storedFeatures(ByteBuffer.allocate(16).putInt(2).putInt(1 << 9).putLong(0).array())
                == 1 << 9, "stored features");
    }

    private static void unknownGenerator() throws Exception {
        Path root = Files.createTempDirectory("mineclone-worldgen-");
        try {
            SaveManager save = new SaveManager(root.toFile());
            var stored = new WorldGenSettings(WorldGenVersion.V2, GenFeatures.V2, 7);
            LevelData level = new LevelData("Future land", 5, 1, 70, 1, 8.5, 80, 8.5, 0, 0, 1, 0,
                    LevelData.emptyInventory(), GameMode.SURVIVAL, 1, 20, 20, null,
                    Map.of(WorldGenSettings.SAVE_SECTION, stored.encode()));
            save.saveLevel("world", level);
            save.flushAndAwait();
            LevelData read = ((LevelLoad.Loaded) save.readLevel("world")).data();
            check(WorldGenSettings.decode(read.extraSections.get(WorldGenSettings.SAVE_SECTION)).equals(stored),
                    "known generator settings did not survive the level");
            Path file = root.resolve("world").resolve(SaveFormat.LEVEL_FILE);
            // An unknown version; a 1.1 change this build does not implement yet (the
            // lowest one it lacks — past the named flags once all of them are done);
            // and V1 with a change, which no build writes.
            int missing = Integer.lowestOneBit(~GenFeatures.V2.bits());
            Object[][] cases = {
                    { ByteBuffer.allocate(16).putInt(99).putInt(0).putLong(0).array(), "world generator version", 99 },
                    { ByteBuffer.allocate(16).putInt(2).putInt(missing).putLong(0).array(), "world generator features", missing },
                    { ByteBuffer.allocate(16).putInt(1).putInt(1).putLong(0).array(), null, 0 },
            };
            for (Object[] c : cases) {
                rewriteGenerator(file, (byte[]) c[0]);
                byte[] original = Files.readAllBytes(file);
                SaveManager fresh = new SaveManager(root.toFile());
                LevelLoad result = fresh.readLevel("world");
                if (c[1] == null)
                    check(result instanceof LevelLoad.Unreadable, "contradictory generator accepted: " + result);
                else
                    check(result instanceof LevelLoad.TooNew newer && newer.what().equals(c[1])
                            && newer.version() == (int) c[2], "unknown generator accepted: " + result);
                var info = fresh.loadWorldInfo("world");
                check(!info.playable() && info.tooNew == (c[1] != null),
                        "world list offers a world this build would regenerate wrongly: " + result);
                fresh.saveLevel("world", level);
                fresh.flushAndAwait();
                check(Arrays.equals(original, Files.readAllBytes(file)), "refused level was overwritten: " + result);
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void rewriteGenerator(Path file, byte[] section) throws Exception {
        Map<String, byte[]> sections;
        int magic, level, minimum;
        String writer;
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            magic = in.readInt(); level = in.readInt(); minimum = in.readInt(); writer = in.readUTF();
            sections = new java.util.LinkedHashMap<>(SectionCodec.read(in));
        }
        sections.put(WorldGenSettings.SAVE_SECTION, section);
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(magic); out.writeInt(level); out.writeInt(minimum); out.writeUTF(writer);
            SectionCodec.write(out, sections);
        }
    }
}
