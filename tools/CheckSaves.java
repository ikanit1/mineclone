import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.SaveManager;
import com.mineclone.world.Furnace;
import com.mineclone.world.ItemStack;

import java.io.File;
import java.util.Arrays;

/**
 * Read-only sweep over every saved world.
 *
 * Written for the move to the registry-backed stack format: level.dat v10 and
 * chunk v6 must open every world produced by earlier builds, and a missing
 * item means an id that used to exist no longer resolves - a silent way to
 * empty someone's chest.
 *
 *   java -cp "out;libs/*" tools/CheckSaves.java [saves-dir]
 *
 * Prints one line per world - world, chunks, stacks, missing, failures - and
 * exits 1 when anything failed to load. Writes nothing.
 */
public class CheckSaves {

    public static void main(String[] args) {
        File root = new File(args.length > 0 ? args[0] : "saves");
        if (!root.isDirectory()) {
            System.out.println("no saves directory: " + root.getPath());
            System.exit(2);
        }
        File[] worlds = root.listFiles(File::isDirectory);
        if (worlds == null)
            worlds = new File[0];
        Arrays.sort(worlds, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        SaveManager save = new SaveManager(root);
        int totalChunks = 0, totalStacks = 0, totalMissing = 0, totalFailures = 0;
        System.out.printf("%-34s %8s %8s %8s %9s%n",
                "world", "chunks", "stacks", "missing", "failures");

        for (File dir : worlds) {
            String id = dir.getName();
            int chunks = 0, stacks = 0, missing = 0, failures = 0;

            LevelData level = save.loadLevel(id);
            if (level == null) {
                failures++;
            } else {
                for (ItemStack s : level.inventory) {
                    if (s == null)
                        continue;
                    stacks++;
                    if (s.item.missing)
                        missing++;
                }
                for (ItemStack s : level.pending) {
                    if (s == null)
                        continue;
                    stacks++;
                    if (s.item.missing)
                        missing++;
                }
            }

            File chunkDir = new File(dir, "chunks");
            File[] files = chunkDir.listFiles(
                    (d, name) -> name.startsWith("c.") && name.endsWith(".dat"));
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                for (File f : files) {
                    int[] xz = parse(f.getName());
                    if (xz == null) {
                        failures++;
                        continue;
                    }
                    ChunkSnapshot c = save.loadChunk(id, xz[0], xz[1]);
                    if (c == null) {
                        failures++;
                        continue;
                    }
                    chunks++;
                    for (ItemStack[] chest : c.chests.values())
                        for (ItemStack s : chest) {
                            if (s == null)
                                continue;
                            stacks++;
                            if (s.item.missing)
                                missing++;
                        }
                    for (Furnace furnace : c.furnaces.values())
                        for (ItemStack s : new ItemStack[] {
                                furnace.input, furnace.fuel, furnace.output }) {
                            if (s == null)
                                continue;
                            stacks++;
                            if (s.item.missing)
                                missing++;
                        }
                    for (var d : c.items) {
                        stacks++;
                        if (d.stack.item.missing)
                            missing++;
                    }
                }
            }

            System.out.printf("%-34s %8d %8d %8d %9d%n", id, chunks, stacks, missing, failures);
            totalChunks += chunks;
            totalStacks += stacks;
            totalMissing += missing;
            totalFailures += failures;
        }

        System.out.printf("%-34s %8d %8d %8d %9d%n",
                "TOTAL (" + worlds.length + " worlds)",
                totalChunks, totalStacks, totalMissing, totalFailures);
        System.out.println(totalFailures == 0 ? "RESULT: OK" : "RESULT: FAIL");
        System.exit(totalFailures == 0 ? 0 : 1);
    }

    /** "c.-3.11.dat" -> {-3, 11}; null when the name is not a chunk file. */
    static int[] parse(String name) {
        String body = name.substring(2, name.length() - 4);
        int dot = body.indexOf('.', body.charAt(0) == '-' ? 1 : 0);
        if (dot < 0)
            return null;
        try {
            return new int[] {
                    Integer.parseInt(body.substring(0, dot)),
                    Integer.parseInt(body.substring(dot + 1)) };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
