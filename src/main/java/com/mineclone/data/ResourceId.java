package com.mineclone.data;

import java.util.regex.Pattern;

/**
 * Идентификатор контента: {@code mineclone:iron_pickaxe}.
 *
 * <p>Пространство имён берётся из папки данных ({@code assets/data/<ns>/}),
 * поэтому контент мода не пересекается с игрой, а поиск по {@code @mineclone}
 * работает без отдельного поля. Только строчная латиница, цифры и подчёркивание
 * — id пишется в сейв и в команды, а там регистр и пробелы только мешают.
 */
public record ResourceId(String namespace, String path) implements Comparable<ResourceId> {

    public static final String DEFAULT_NAMESPACE = "mineclone";

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./]+");

    public ResourceId {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches())
            throw new IllegalArgumentException("bad namespace: " + namespace);
        if (path == null || !PATH.matcher(path).matches())
            throw new IllegalArgumentException("bad path: " + path);
    }

    /** {@code stone} → {@code <defaultNamespace>:stone}; {@code mod:x} — как есть. */
    public static ResourceId parse(String s, String defaultNamespace) {
        if (s == null)
            throw new IllegalArgumentException("null id");
        int colon = s.indexOf(':');
        if (colon < 0)
            return new ResourceId(defaultNamespace, s);
        if (s.indexOf(':', colon + 1) >= 0)
            throw new IllegalArgumentException("more than one ':' in " + s);
        return new ResourceId(s.substring(0, colon), s.substring(colon + 1));
    }

    public static ResourceId of(String s) {
        return parse(s, DEFAULT_NAMESPACE);
    }

    @Override
    public int compareTo(ResourceId o) {
        int c = namespace.compareTo(o.namespace);
        return c != 0 ? c : path.compareTo(o.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
