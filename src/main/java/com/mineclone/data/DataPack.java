package com.mineclone.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Каталог данных игры: {@code assets/data/<namespace>/<вид>/**.json}.
 *
 * <p>Порядок файлов стабильный — по пространству имён, затем по пути. От него
 * зависит порядок предметов в креативе, и он не должен меняться от того,
 * в каком порядке файловая система отдала каталог. Реестр рецептов после
 * загрузки отдельно сортирует записи по явному {@code order}, затем по id.
 */
public final class DataPack {

    /** Разобранный файл: из какого пространства, путь внутри вида и содержимое. */
    public record Entry(String namespace, String relPath, JsonObject json) {}

    private final Path root;

    public DataPack(Path root) {
        this.root = root;
    }

    public Path rootDir() {
        return root;
    }

    /** Все файлы вида ({@code items}, {@code tags/items}) во всех пространствах имён. */
    public List<Entry> files(String kind) {
        List<Entry> out = new ArrayList<>();
        for (String ns : namespaces()) {
            Path dir = root.resolve(ns).resolve(kind);
            if (!Files.isDirectory(dir))
                continue;
            List<Path> found;
            try (Stream<Path> walk = Files.walk(dir)) {
                found = walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> slashes(dir.relativize(p))))
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (Path p : found) {
                String rel = slashes(dir.relativize(p));
                out.add(new Entry(ns, rel, read(p, ns + "/" + kind + "/" + rel)));
            }
        }
        return out;
    }

    /** Файл верхнего уровня пространства ({@code categories.json}) или null. */
    public JsonObject root(String namespace, String fileName) {
        Path p = root.resolve(namespace).resolve(fileName);
        if (!Files.isRegularFile(p))
            return null;
        return read(p, namespace + "/" + fileName);
    }

    public List<String> namespaces() {
        if (!Files.isDirectory(root))
            return List.of();
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonObject read(Path p, String source) {
        try {
            return Json.parseObject(p, source);
        } catch (IOException e) {
            throw new JsonException(source, 0, 0, "cannot read: " + e.getMessage());
        }
    }

    private static String slashes(Path rel) {
        return rel.toString().replace('\\', '/');
    }
}
