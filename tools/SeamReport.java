import com.mineclone.save.LedgerLoad;
import com.mineclone.save.LevelLoad;
import com.mineclone.save.SaveManager;
import com.mineclone.world.gen.ChunkLedger;
import com.mineclone.world.gen.WorldGenSettings;
import com.mineclone.world.gen.WorldGenUpgrade;
import com.mineclone.world.gen.WorldGenVersion;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Where the generators meet in a world (GEN-02): every recorded chunk whose side
 * neighbour generates with another version (WorldGenUpgrade.seams). After an
 * upgrade these ring the land pinned to the old generator, where terrain may not
 * line up.
 *
 *   java -cp "out;libs/*" tools/SeamReport.java [saves-dir] <world> [--list]
 *
 * Prints the world's generator, how many chunks the ledger holds per version
 * and the number of seam chunks; --list adds one line per seam chunk. Exits 1
 * when the level or the ledger cannot be read. Writes nothing.
 */
public class SeamReport {
    public static void main(String[] args) {
        List<String> plain = new ArrayList<>();
        boolean list = false;
        for (String arg : args) {
            if (arg.equals("--list")) list = true;
            else plain.add(arg);
        }
        if (plain.isEmpty() || plain.size() > 2) {
            System.out.println("usage: SeamReport [saves-dir] <world> [--list]");
            System.exit(2);
        }
        File root = new File(plain.size() == 2 ? plain.get(0) : "saves");
        String id = plain.get(plain.size() - 1);
        SaveManager save = new SaveManager(root);

        LevelLoad level = save.readLevel(id);
        if (!(level instanceof LevelLoad.Loaded loaded)) {
            System.out.println(id + ": level " + level);
            System.exit(1);
            return;
        }
        WorldGenSettings generator;
        try {
            generator = WorldGenSettings.decode(loaded.data().extraSections.get(WorldGenSettings.SAVE_SECTION));
        } catch (IllegalArgumentException e) {
            System.out.println(id + ": generator " + e.getMessage());
            System.exit(1);
            return;
        }
        LedgerLoad read = save.readLedger(id);
        if (read instanceof LedgerLoad.Unreadable unreadable) {
            System.out.println(id + ": ledger unreadable (" + unreadable.reason() + ")");
            System.exit(1);
            return;
        }
        ChunkLedger ledger = read instanceof LedgerLoad.Loaded found ? found.ledger() : new ChunkLedger();

        Map<WorldGenVersion, Integer> perVersion = new EnumMap<>(WorldGenVersion.class);
        for (WorldGenVersion version : ledger.snapshot().values()) perVersion.merge(version, 1, Integer::sum);
        List<Long> seams = WorldGenUpgrade.seams(ledger, generator.version());

        System.out.println("world " + id + ": generator " + generator.version() + " " + generator.features()
                + (generator.upgradedAt() > 0 ? ", upgraded at " + generator.upgradedAt() : ", never upgraded"));
        System.out.println("ledger " + (read instanceof LedgerLoad.Absent ? "absent" : ledger.size() + " chunks")
                + (perVersion.isEmpty() ? "" : " " + perVersion));
        System.out.println("seam chunks " + seams.size());
        if (list)
            for (long key : seams)
                System.out.println("  " + (int) (key >> 32) + "," + (int) key + " "
                        + ledger.versionAt(key, generator.version()));
    }
}
