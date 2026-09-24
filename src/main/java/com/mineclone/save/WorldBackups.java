package com.mineclone.save;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** ZIP snapshots and restore-to-copy; callers serialize this with world writes. */
public final class WorldBackups {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Duration MIGRATION_HOLD = Duration.ofDays(7);
    private final Path root;
    private final Clock clock;
    private volatile int retention = 5;

    public record Backup(Path path, long timestamp, String reason, long sizeBytes) {
        public String label() { return LABEL.format(Instant.ofEpochMilli(timestamp)); }
        public boolean migration() { return reason.equals("migration") || reason.startsWith("migration-"); }
    }

    public WorldBackups(Path root) { this(root, Clock.systemUTC()); }

    /** Clock seam for rotation and migration retention tests. */
    public WorldBackups(Path root, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
    }

    public void setRetention(int count) {
        if (count < 1 || count > 1000) throw new IllegalArgumentException("backups must be between 1 and 1000");
        retention = count;
    }

    private Path world(String id) {
        Path result = root.resolve(id).normalize();
        if (!result.getParent().equals(root)) throw new IllegalArgumentException("invalid world id");
        return result;
    }

    public List<Backup> list(String id) throws IOException {
        Path dir = world(id).resolve("backups");
        if (!Files.isDirectory(dir)) return List.of();
        List<Backup> result = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".zip")
                    && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = file.getFileName().toString();
                int marker = name.indexOf("migration");
                String reason = marker >= 0 ? name.substring(marker, name.length() - 4)
                        : name.endsWith("-session.zip") ? "session" : "manual";
                result.add(new Backup(file, Files.getLastModifiedTime(file).toMillis(), reason, Files.size(file)));
            }
        }
        result.sort(Comparator.comparingLong(Backup::timestamp).reversed()
                .thenComparing(b -> b.path().getFileName().toString(), Comparator.reverseOrder()));
        return List.copyOf(result);
    }

    public Backup snapshot(String id, String reason) throws IOException {
        if (reason == null || !reason.matches("[a-zA-Z0-9_-]{1,48}"))
            throw new IllegalArgumentException("invalid backup reason");
        Path source = world(id);
        if (!Files.isRegularFile(source.resolve(SaveFormat.LEVEL_FILE), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("world has no readable level.dat");
        Path directory = source.resolve("backups");
        Files.createDirectories(directory);
        Path tmp = Files.createTempFile(directory, ".snapshot-", ".tmp");
        try {
            List<Path> inputs = new ArrayList<>();
            inputs.add(source.resolve(SaveFormat.LEVEL_FILE));
            if (Files.isRegularFile(source.resolve(SaveFormat.ICON_FILE), LinkOption.NOFOLLOW_LINKS))
                inputs.add(source.resolve(SaveFormat.ICON_FILE));
            for (String subtree : List.of("players", "chunks")) {
                Path tree = source.resolve(subtree);
                if (!Files.exists(tree)) continue;
                if (!Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("backup source is not a directory: " + subtree);
                try (var paths = Files.walk(tree)) {
                    for (Path path : paths.sorted().toList()) {
                        if (Files.isSymbolicLink(path)) throw new IOException("backup source contains a symbolic link");
                        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                && !path.getFileName().toString().endsWith(".tmp")) inputs.add(path);
                    }
                }
            }
            try (var out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                // Saves are already gzip-compressed; a fast ZIP level avoids recompression stalls.
                out.setLevel(1);
                for (Path file : inputs) {
                    ZipEntry entry = new ZipEntry(source.relativize(file).toString().replace('\\', '/'));
                    entry.setTime(Files.getLastModifiedTime(file).toMillis());
                    out.putNextEntry(entry);
                    Files.copy(file, out);
                    out.closeEntry();
                }
            }
            String prefix = STAMP.format(clock.instant());
            Path target;
            int collision = 0;
            for (;;) {
                target = directory.resolve(prefix + (collision == 0 ? "" : "-" + collision) + "-" + reason + ".zip");
                try { Files.move(tmp, target); break; }
                catch (java.nio.file.FileAlreadyExistsException e) { collision++; }
            }
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(clock.instant()));
            Backup backup = new Backup(target, clock.millis(), reason, Files.size(target));
            rotate(id);
            return backup;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void rotate(String id) throws IOException {
        List<Backup> backups = list(id);
        long heldAfter = clock.millis() - MIGRATION_HOLD.toMillis();
        int kept = 0;
        for (Backup backup : backups) {
            if (backup.migration() && backup.timestamp() >= heldAfter) continue;
            if (++kept <= retention) continue;
            Files.delete(backup.path());
        }
    }

    /** Restore is staged and validated; the original world and archive remain untouched. */
    public String restore(String id, Backup backup) throws IOException {
        Path backupDir = world(id).resolve("backups");
        Path archive = backup.path().toAbsolutePath().normalize();
        if (!archive.getParent().equals(backupDir) || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("backup is not an archive from this world");
        Path stagingRoot = root.resolve(".restore-staging");
        Files.createDirectories(stagingRoot);
        Path stage = Files.createTempDirectory(stagingRoot, "world-");
        try {
            Set<String> seen = new HashSet<>();
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream file = Files.newInputStream(archive);
                 var in = new ZipInputStream(new BufferedInputStream(file))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.startsWith("/")
                            || java.util.Arrays.asList(name.split("/")).contains("..")
                            || !(name.equals(SaveFormat.LEVEL_FILE) || name.equals(SaveFormat.ICON_FILE)
                            || name.startsWith("chunks/") || name.startsWith("players/")))
                        throw new IOException("unsupported backup entry: " + name);
                    Path target = stage.resolve(name).normalize();
                    if (!target.startsWith(stage) || !seen.add(target.toString()) || seen.size() > 1_000_000)
                        throw new IOException("invalid or duplicate backup entry: " + name);
                    if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                    Files.createDirectories(target.getParent());
                    try (var out = new BufferedOutputStream(Files.newOutputStream(target,
                            java.nio.file.StandardOpenOption.CREATE_NEW))) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            total += read;
                            if (total > 16L * 1024 * 1024 * 1024) throw new IOException("backup expands beyond 16 GiB");
                            out.write(buffer, 0, read);
                        }
                    }
                    in.closeEntry();
                }
            }
            SaveManager validator = new SaveManager(stagingRoot.toFile());
            LevelLoad result = validator.readLevel(stage.getFileName().toString());
            if (!(result instanceof LevelLoad.Loaded loaded))
                throw new IOException("backup level cannot be opened: " + result);
            LevelData d = loaded.data();
            String label = (d.name.isBlank() ? SaveManager.UNNAMED : d.name) + " (бэкап " + backup.label() + ")";
            validator.writeRestoredLevel(stage.getFileName().toString(), d.withName(label));
            String base = SaveFormat.newWorldId() + "_backup";
            int suffix = 1;
            for (;;) {
                String restoredId = base + (suffix == 1 ? "" : "_" + suffix);
                Path destination = world(restoredId);
                try {
                    Files.move(stage, destination);
                    return restoredId;
                } catch (java.nio.file.FileAlreadyExistsException collision) { suffix++; }
            }
        } finally {
            if (Files.exists(stage)) {
                try (var paths = Files.walk(stage)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
}
