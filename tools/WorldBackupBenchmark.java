import com.mineclone.save.LevelData;
import com.mineclone.save.SaveManager;
import com.mineclone.save.WorldBackups;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

/** Standalone 200 MiB backup measurement; uses disposable generated data only. */
public class WorldBackupBenchmark {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mineclone-backup-benchmark-");
        try {
            SaveManager save = new SaveManager(root.toFile());
            save.saveLevel("bench", new LevelData(123, 8, 80, 8, 0, 0, 0, 0));
            Path chunks = Files.createDirectories(root.resolve("bench/chunks"));
            byte[] block = new byte[1024 * 1024];
            Random random = new Random(12345);
            for (int i = 0; i < 200; i++) {
                random.nextBytes(block);
                Files.write(chunks.resolve("ledger." + i), block);
            }
            long started = System.nanoTime();
            WorldBackups.Backup result = save.beginWorldSession("bench").get();
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            System.out.printf(java.util.Locale.ROOT, "BACKUP_BENCHMARK sourceMiB=200 archiveBytes=%d seconds=%.3f%n", result.sizeBytes(), seconds);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
