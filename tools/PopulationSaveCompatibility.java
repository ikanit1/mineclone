import com.mineclone.core.BuildInfo;
import com.mineclone.data.ResourceId;
import com.mineclone.save.LevelData;
import com.mineclone.save.PlayerRecord;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.GameMode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * A current -> real legacy -> current ecology level-file compatibility gate.
 * Run the same Java source with each build's own classpath and assets as cwd:
 *
 * java -cp 'out-test;libs/*' tools/PopulationSaveCompatibility.java write <new-dir>
 * java -cp 'E:/mineclone/out;E:/mineclone/libs/*' <absolute-tool-path> rewrite <same-dir>
 * java -cp 'out-test;libs/*' tools/PopulationSaveCompatibility.java verify <same-dir>
 *
 * The legacy phase refuses any classpath containing EcologyCodec. All writes
 * belong to a newly created fixture directory, never to an existing user world.
 * Ecology classes are reached by reflection only in the current phases, so this
 * source also compiles under the legacy build that has no Living World package.
 */
public final class PopulationSaveCompatibility {
    private static final String WORLD = "compat-world", MARKER = "mineclone-population-compatibility-v1";
    private static final String CODEC = "com.mineclone.sim.ecology.EcologyCodec";
    private static final String CURRENT_NAME = "Current population fixture", LEGACY_NAME = "Rewritten by legacy build";
    private static final ResourceId COW = ResourceId.of("mineclone:cow"), UNKNOWN = ResourceId.of("future:aurochs");
    private static final byte[] REGION_TAIL = { 21, 34, 55 }, SECTION_TAIL = { 89, 44, 0, -1 };
    private static final long BODY_ID = 701;

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: PopulationSaveCompatibility.java write|rewrite|verify <fixture-directory>");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "write" -> write(root);
            case "rewrite" -> rewrite(root);
            case "verify" -> verify(root);
            default -> throw new IllegalArgumentException("Unknown phase: " + args[0]);
        }
    }

    private static void write(Path root) throws Exception {
        Class<?> codec = Class.forName(CODEC);
        if (Files.exists(root)) {
            if (!Files.isDirectory(root)) throw new IOException("Fixture path is not a directory: " + root);
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) throw new IOException("Refusing to replace an occupied fixture directory: " + root);
            }
        }
        byte[] ecology = specimen(codec);
        byte[] rules = ByteBuffer.allocate(15).putInt(GameMode.SURVIVAL.ordinal())
                .put(new byte[] { 7, 0, -1, 42, 99 }).putInt(0x4C575231).put((byte) 1).put((byte) 1).array();
        Files.createDirectories(root);
        Files.writeString(root.resolve("fixture.marker"), MARKER);
        Files.write(root.resolve("expected-ecology.bin"), ecology);
        Files.write(root.resolve("expected-rules.bin"), rules);
        SaveManager save = manager(root);
        try {
            Map<String, byte[]> sections = new LinkedHashMap<>();
            sections.put("ecology", ecology);
            sections.put("rules", rules);
            LevelData level = new LevelData(CURRENT_NAME, 20261002L, -503.5, 65, 777.5, 78.0f,
                    GameMode.SURVIVAL, 1L,
                    PlayerRecord.builder().pose(-503.5, 65, 777.5, 0.25f, -0.15f, 2).build(), sections);
            save.saveLevel(WORLD, level);
            save.flushAndAwait();
            assertSections(root, requireLevel(save), CURRENT_NAME);
            Files.writeString(root.resolve("current-writer.txt"), provenance());
            System.out.println("POPULATION_COMPAT_WRITE PASS ecology_sha256=" + sha256(ecology)
                    + " rules_sha256=" + sha256(rules) + " " + provenance());
        } finally { stopWriter(save); }
    }

    private static void rewrite(Path root) throws Exception {
        requireFixture(root);
        try {
            Class.forName(CODEC);
            throw new IllegalStateException("Legacy phase refuses a classpath with EcologyCodec; use the actual pre-ecology build");
        } catch (ClassNotFoundException expectedLegacy) { /* this is the compatibility boundary under test */ }
        if (Files.exists(root.resolve("legacy-rewrite.txt"))) throw new IOException("Legacy rewrite was already performed");
        SaveManager save = manager(root);
        try {
            LevelData original = requireLevel(save);
            assertSections(root, original, CURRENT_NAME);
            Path levelFile = root.resolve("saves").resolve(WORLD).resolve("level.dat");
            byte[] previousFile = Files.readAllBytes(levelFile);
            save.saveLevel(WORLD, original.withName(LEGACY_NAME));
            save.flushAndAwait();
            assertSections(root, requireLevel(save), LEGACY_NAME);
            check(!Arrays.equals(previousFile, Files.readAllBytes(levelFile)), "Legacy phase did not rewrite level.dat");
            Files.writeString(root.resolve("legacy-rewrite.txt"), provenance());
            System.out.println("POPULATION_COMPAT_LEGACY_REWRITE PASS ecology_sha256="
                    + sha256(original.extraSections.get("ecology")) + " " + provenance());
        } finally { stopWriter(save); }
    }

    private static void verify(Path root) throws Exception {
        requireFixture(root);
        check(Files.isRegularFile(root.resolve("legacy-rewrite.txt")), "The legacy rewrite phase has not run");
        Class<?> codec = Class.forName(CODEC);
        SaveManager save = manager(root);
        try {
            LevelData level = requireLevel(save);
            assertSections(root, level, LEGACY_NAME);
            byte[] bytes = level.extraSections.get("ecology");
            Object decoded = codec.getMethod("decode", byte[].class).invoke(null, bytes);
            Class<?> snapshotClass = decoded.getClass();
            byte[] encoded = (byte[]) codec.getMethod("encode", snapshotClass).invoke(null, decoded);
            check(Arrays.equals(bytes, encoded), "Current ecology decoder did not preserve the legacy-carried payload");
            List<?> regions = (List<?>) snapshotClass.getMethod("regions").invoke(decoded);
            check(regions.size() == 1, "Region was lost during compatibility round trip");
            Object region = regions.get(0);
            Class<?> regionClass = region.getClass();
            check((int) regionClass.getMethod("population", ResourceId.class).invoke(region, COW) == 9,
                    "Known cow population changed");
            check((int) regionClass.getMethod("population", ResourceId.class).invoke(region, UNKNOWN) == 7
                    && (int) regionClass.getMethod("capacity", ResourceId.class).invoke(region, UNKNOWN) == 19,
                    "Unknown species counts or capacity changed");
            check(Arrays.equals((byte[]) regionClass.getMethod("tail").invoke(region), REGION_TAIL)
                    && Arrays.equals((byte[]) snapshotClass.getMethod("tail").invoke(decoded), SECTION_TAIL),
                    "Unknown region or section tail changed");
            Map<?, ?> reservations = (Map<?, ?>) snapshotClass.getMethod("reservations").invoke(decoded);
            check(reservations.size() == 1 && reservations.containsKey(BODY_ID), "The saved body's reservation changed");
            byte[] rules = level.extraSections.get("rules");
            check(ByteBuffer.wrap(rules, rules.length - 6, 4).getInt() == 0x4C575231 && rules[rules.length - 1] == 1,
                    "The Living World switch changed in the legacy writer");
            Files.writeString(root.resolve("verification.txt"), "PASS\n" + provenance());
            System.out.println("POPULATION_COMPAT_VERIFY PASS known_species=1 unknown_species=1 reservations=1"
                    + " ecology_sha256=" + sha256(bytes) + " rules_sha256=" + sha256(rules) + " " + provenance());
        } finally { stopWriter(save); }
    }

    /** Encode a valid current section containing ownership and forward-compatible opaque records. */
    private static byte[] specimen(Class<?> codec) throws Exception {
        Class<?> regionClass = Class.forName("com.mineclone.sim.ecology.EcologyRegion");
        Object region = regionClass.getConstructor(int.class, int.class, long.class).newInstance(-2, 3, 12L);
        regionClass.getMethod("population", ResourceId.class, int.class).invoke(region, COW, 9);
        regionClass.getMethod("capacity", ResourceId.class, int.class).invoke(region, COW, 36);
        regionClass.getMethod("protectedCount", ResourceId.class, int.class).invoke(region, COW, 1);
        regionClass.getMethod("trend", ResourceId.class, int[].class).invoke(region, COW, new int[] { 1, 2, -1 });
        regionClass.getMethod("population", ResourceId.class, int.class).invoke(region, UNKNOWN, 7);
        regionClass.getMethod("capacity", ResourceId.class, int.class).invoke(region, UNKNOWN, 19);
        regionClass.getMethod("foodBonus", ResourceId.class, int.class).invoke(region, UNKNOWN, 3);
        regionClass.getMethod("trend", ResourceId.class, int[].class).invoke(region, UNKNOWN, new int[] { -2, 1, 3 });
        byte[] greenery = new byte[256];
        for (int i = 0; i < greenery.length; i++) greenery[i] = (byte) (i * 17);
        regionClass.getMethod("greenery", byte[].class).invoke(region, greenery);
        regionClass.getMethod("lairs", List.class).invoke(region, List.of(new byte[] { 7, 9, 11 }));
        regionClass.getMethod("scars", List.class).invoke(region, List.of(new byte[] { 5, 8, 13 }));
        regionClass.getMethod("tail", byte[].class).invoke(region, REGION_TAIL);
        Class<?> reservationClass = Class.forName(CODEC + "$Reservation");
        Object reservation = reservationClass.getConstructor(long.class, int.class, int.class, ResourceId.class)
                .newInstance(BODY_ID, -2, 3, COW);
        Class<?> snapshotClass = Class.forName(CODEC + "$Snapshot");
        Object snapshot = snapshotClass.getConstructor(List.class, Map.class, byte[].class)
                .newInstance(List.of(region), Map.of(BODY_ID, reservation), SECTION_TAIL);
        return (byte[]) codec.getMethod("encode", snapshotClass).invoke(null, snapshot);
    }

    private static SaveManager manager(Path root) { return new SaveManager(root.resolve("saves").toFile()); }

    private static LevelData requireLevel(SaveManager save) {
        LevelData level = save.loadLevel(WORLD);
        check(level != null, "Compatibility fixture level is unreadable");
        return level;
    }

    private static void assertSections(Path root, LevelData level, String name) throws IOException {
        check(level.name.equals(name), "Unexpected fixture name: " + level.name);
        check(level.gameMode == GameMode.SURVIVAL, "Game mode changed");
        check(Arrays.equals(level.extraSections.get("ecology"), Files.readAllBytes(root.resolve("expected-ecology.bin"))),
                "Ecology section changed byte-for-byte");
        check(Arrays.equals(level.extraSections.get("rules"), Files.readAllBytes(root.resolve("expected-rules.bin"))),
                "Rules section or unknown rules suffix changed byte-for-byte");
    }

    private static void requireFixture(Path root) throws IOException {
        check(Files.isRegularFile(root.resolve("fixture.marker"))
                && Files.readString(root.resolve("fixture.marker")).equals(MARKER), "Not a tool-owned compatibility fixture: " + root);
    }

    private static String provenance() {
        return "build=" + BuildInfo.VERSION + " level_version=" + SaveFormat.LEVEL_VERSION
                + " classes=" + SaveManager.class.getProtectionDomain().getCodeSource().getLocation();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void stopWriter(SaveManager save) throws Exception {
        save.flushAndAwait();
        var field = SaveManager.class.getDeclaredField("chunkWriter"); field.setAccessible(true);
        ((ExecutorService) field.get(save)).shutdown();
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
