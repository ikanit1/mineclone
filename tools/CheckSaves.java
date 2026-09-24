import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.SaveManager;
import com.mineclone.save.LevelLoad;
import com.mineclone.save.ChunkLoad;
import com.mineclone.save.PlayerRecord;
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
 * Prints one line per world - world, chunks, players, stacks, missing, failures -
 * and exits 1 when anything failed to load. Guest checkpoints in players/ are
 * read too: a guest file the game cannot open locks that guest out. Writes nothing.
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
        int totalChunks = 0, totalPlayers = 0, totalStacks = 0, totalMissing = 0, totalFailures = 0,
                totalQuarantine = 0, totalSections = 0;
        System.out.printf("%-34s %8s %8s %8s %8s %9s %10s %8s%n",
                "world", "chunks", "players", "stacks", "missing", "failures", "quarantine", "sections");

        for (File dir : worlds) {
            String id = dir.getName();
            int chunks = 0, players = 0, stacks = 0, missing = 0, failures = 0, sections = 0;

            LevelLoad levelRead = save.readLevel(id);
            if (!(levelRead instanceof LevelLoad.Loaded loaded)) {
                failures++;
                System.err.println(id + ": " + levelRead);
            } else {
                LevelData level = loaded.data();
                int[] counted = count(level.player);
                stacks += counted[0];
                missing += counted[1];
            }

            File[] guests = new File(dir, "players").listFiles((d, name) -> name.endsWith(".dat"));
            if (guests != null) {
                Arrays.sort(guests, (a, b) -> a.getName().compareTo(b.getName()));
                for (File guest : guests) {
                    String guestId = guest.getName().substring(0, guest.getName().length() - 4);
                    try {
                        PlayerRecord record = save.loadGuestRecord(id, guestId);
                        int[] counted = count(record);
                        stacks += counted[0];
                        missing += counted[1];
                        players++;
                    } catch (RuntimeException e) {
                        failures++;
                        System.err.println(id + "/players/" + guest.getName() + ": " + e.getMessage()
                                + (e.getCause() == null ? "" : " (" + e.getCause() + ")"));
                    }
                }
            }

            File chunkDir = new File(dir, "chunks");
            File[] evidence = chunkDir.listFiles(
                    (d, name) -> name.matches("c\\.-?\\d+\\.-?\\d+\\.dat\\.corrupt-\\d+"));
            int quarantined = evidence == null ? 0 : evidence.length;
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
                    ChunkLoad chunkRead = save.readChunk(id, xz[0], xz[1]);
                    if (!(chunkRead instanceof ChunkLoad.Loaded chunkLoaded)) {
                        failures++;
                        System.err.println(id + "/" + f.getName() + ": " + chunkRead);
                        continue;
                    }
                    ChunkSnapshot c = chunkLoaded.snapshot();
                    chunks++;
                    // Three decoded container/item sections plus opaque future data.
                    sections += 3 + c.extra.size();
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

            System.out.printf("%-34s %8d %8d %8d %8d %9d %10d %8d%n",
                    id, chunks, players, stacks, missing, failures, quarantined, sections);
            totalChunks += chunks;
            totalPlayers += players;
            totalStacks += stacks;
            totalMissing += missing;
            totalFailures += failures;
            totalQuarantine += quarantined;
            totalSections += sections;
        }

        System.out.printf("%-34s %8d %8d %8d %8d %9d %10d %8d%n",
                "TOTAL (" + worlds.length + " worlds)",
                totalChunks, totalPlayers, totalStacks, totalMissing, totalFailures, totalQuarantine, totalSections);
        System.out.println(totalFailures == 0 ? "RESULT: OK" : "RESULT: FAIL");
        System.exit(totalFailures == 0 ? 0 : 1);
    }

    /** Occupied stacks and unresolved items across a player's inventory, equipment and pending stacks. */
    static int[] count(PlayerRecord player) {
        int stacks = 0, missing = 0;
        for (ItemStack[] slots : new ItemStack[][] { player.inventory(), player.equipment(), player.pending() })
            for (ItemStack s : slots) {
                if (s == null)
                    continue;
                stacks++;
                if (s.item.missing)
                    missing++;
            }
        return new int[] { stacks, missing };
    }

    /** "c.-3.11.dat" -> {-3, 11}; null when the name is not a chunk file. */
    static int[] parse(String name) {
        if (!name.startsWith("c.") || !name.endsWith(".dat") || name.length() < 9)
            return null;
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
