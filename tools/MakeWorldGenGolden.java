import com.mineclone.world.Biome;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.Structures;
import com.mineclone.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes src/test/resources/fixtures/worldgen-v1.txt: SHA-1 of blocks and meta
 * for 40 chunks of each of three seeds, chosen to cover negative coordinates,
 * far coordinates, every biome the seed shows near spawn and every structure
 * kind actually placed.
 *
 * <p>Uses only APIs that already existed at v1.0.0-alpha, so the same file can
 * be run against that tag's classes: identical output proves the current
 * generator is still the 1.0 generator. Chunk choice and hashing depend on the
 * generator alone, never on the order chunks are generated in.
 *
 *   java -cp "out-test;libs/*" tools/MakeWorldGenGolden.java <output file>
 */
public class MakeWorldGenGolden {
    static final long[] SEEDS = { 0L, 20260922L, -77231L };
    static final int PER_SEED = 40;
    static final String[] KINDS = { "RUIN", "HUT", "OBELISK", "DUNGEON" };

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Output file required");
        StringBuilder out = new StringBuilder();
        out.append("# World generation V1 golden: seed|cx|cz|label|sha1(blocks+meta).\n");
        out.append("# Written by tools/MakeWorldGenGolden.java; identical when run against v1.0.0-alpha.\n");
        out.append("# Never regenerate to make a test pass: a changed hash means old worlds changed.\n");
        for (long seed : SEEDS)
            for (var entry : choose(seed).entrySet()) {
                int[] at = entry.getValue();
                out.append(seed).append('|').append(at[0]).append('|').append(at[1]).append('|')
                        .append(entry.getKey()).append('|').append(hash(new World(seed), at[0], at[1])).append('\n');
            }
        Files.writeString(Path.of(args[0]), out, StandardCharsets.UTF_8);
        System.out.println("WORLDGEN_GOLDEN_WRITTEN chunks=" + SEEDS.length * PER_SEED + " -> " + args[0]);
    }

    /** Label -> chunk, in a fixed order; only generator output decides the picks. */
    static Map<String, int[]> choose(long seed) {
        Map<String, int[]> picks = new LinkedHashMap<>();
        int[][] fixed = { {0, 0}, {-1, -1}, {1, -1}, {-1, 1}, {2, 3}, {-7, -11}, {15, -16}, {-16, 15},
                {1000, -1000}, {-4096, 2048}, {30000, 30000} };
        for (int[] at : fixed) picks.put("fixed:" + at[0] + "," + at[1], at);
        World world = new World(seed);
        // Biomes: the first chunk centre of each, walking outward ring by ring.
        for (Biome biome : Biome.values()) {
            int[] found = ring(64, 4, (cx, cz) -> world.biomes.biomeAt(cx * 16 + 8, cz * 16 + 8) == biome);
            if (found != null) picks.putIfAbsent("biome:" + biome.name(), found);
        }
        // Structures: a candidate chunk that really received its building (every template has a torch).
        for (int kind = 0; kind < KINDS.length; kind++) {
            final int wanted = kind;
            int[] found = ring(24, 1, (rx, rz) -> {
                int[] candidate = candidate(rx, rz, seed, wanted);
                return candidate != null && contains(world, candidate[0], candidate[1], BlockType.TORCH);
            });
            if (found != null) picks.putIfAbsent("structure:" + KINDS[kind], candidate(found[0], found[1], seed, kind));
        }
        // Fill with scattered chunks from a fixed sequence.
        long state = seed ^ 0x57A7E5L;
        for (int i = 0; picks.size() < PER_SEED; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int cx = (int) ((state >>> 20) % 601) - 300, cz = (int) ((state >>> 40) % 601) - 300;
            picks.putIfAbsent("scatter:" + i, new int[] { cx, cz });
        }
        return picks;
    }

    /** The chunk of region (rx, rz) that holds a structure of this kind, or null. */
    static int[] candidate(int rx, int rz, long seed, int kind) {
        for (int cx = rx * Structures.REGION_CHUNKS; cx < (rx + 1) * Structures.REGION_CHUNKS; cx++)
            for (int cz = rz * Structures.REGION_CHUNKS; cz < (rz + 1) * Structures.REGION_CHUNKS; cz++)
                if (Structures.candidateKind(cx, cz, seed) == kind) return new int[] { cx, cz };
        return null;
    }

    interface Test { boolean at(int a, int b); }

    /** Squares of growing radius around the origin, in a fixed order. */
    static int[] ring(int radius, int step, Test test) {
        for (int r = 0; r <= radius; r++)
            for (int a = -r; a <= r; a++)
                for (int b = -r; b <= r; b++) {
                    if (Math.max(Math.abs(a), Math.abs(b)) != r) continue;
                    if (test.at(a * step, b * step)) return new int[] { a * step, b * step };
                }
        return null;
    }

    static boolean contains(World world, int cx, int cz, BlockType type) {
        Chunk chunk = world.getChunk(cx, cz);
        byte id = (byte) type.ordinal();
        for (byte b : chunk.copyBlocks()) if (b == id) return true;
        return false;
    }

    static String hash(World world, int cx, int cz) throws Exception {
        Chunk chunk = world.getChunk(cx, cz);
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(chunk.copyBlocks());
        sha.update(chunk.copyMeta());
        return HexFormat.of().formatHex(sha.digest());
    }

}
