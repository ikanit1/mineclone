package com.mineclone.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Объект JSON с типизированным доступом и путём для сообщений об ошибках.
 *
 * <p>Каждый объект знает, откуда он: файл и путь от корня
 * ({@code iron_pickaxe.tool}). Поэтому ошибка значения говорит не «ожидалось
 * число», а {@code items/tools.json: iron_pickaxe.tool.level: expected an integer}.
 */
public final class JsonObject {

    private final Map<String, Object> map;
    private final String source;
    private final String path;

    public JsonObject(Map<String, Object> map, String source, String path) {
        this.map = map;
        this.source = source;
        this.path = path == null ? "" : path;
    }

    public String source() {
        return source;
    }

    public String path() {
        return path;
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(map.keySet());
    }

    public Object raw(String key) {
        return map.get(key);
    }

    // ---------------------------------------------------------------- строки

    public String string(String key) {
        Object v = required(key);
        if (!(v instanceof String s))
            throw error(key, "expected a string");
        return s;
    }

    public String string(String key, String def) {
        return has(key) && map.get(key) != null ? string(key) : def;
    }

    // ----------------------------------------------------------------- числа

    public int integer(String key) {
        Object v = required(key);
        if (!(v instanceof Double d) || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE)
            throw error(key, "expected an integer");
        return (int) (double) d;
    }

    public int integer(String key, int def) {
        return has(key) && map.get(key) != null ? integer(key) : def;
    }

    public float number(String key) {
        Object v = required(key);
        if (!(v instanceof Double d))
            throw error(key, "expected a number");
        return (float) (double) d;
    }

    public float number(String key, float def) {
        return has(key) && map.get(key) != null ? number(key) : def;
    }

    public boolean bool(String key, boolean def) {
        if (!has(key) || map.get(key) == null)
            return def;
        if (!(map.get(key) instanceof Boolean b))
            throw error(key, "expected true or false");
        return b;
    }

    // --------------------------------------------------------------- вложенное

    @SuppressWarnings("unchecked")
    public JsonObject object(String key) {
        Object v = required(key);
        if (!(v instanceof Map))
            throw error(key, "expected an object");
        return new JsonObject((Map<String, Object>) v, source, child(key));
    }

    public JsonObject objectOrNull(String key) {
        return has(key) && map.get(key) != null ? object(key) : null;
    }

    @SuppressWarnings("unchecked")
    public List<Object> array(String key) {
        Object v = required(key);
        if (!(v instanceof List))
            throw error(key, "expected an array");
        return (List<Object>) v;
    }

    public List<String> strings(String key) {
        List<Object> raw = array(key);
        List<String> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof String s))
                throw error(key + "[" + i + "]", "expected a string");
            out.add(s);
        }
        return out;
    }

    public List<String> strings(String key, List<String> def) {
        return has(key) && map.get(key) != null ? strings(key) : def;
    }

    /** Объект-элемент массива: для списков вроде {@code "categories": [{...}]}. */
    @SuppressWarnings("unchecked")
    public JsonObject element(String key, int index) {
        List<Object> raw = array(key);
        Object v = raw.get(index);
        if (!(v instanceof Map))
            throw error(key + "[" + index + "]", "expected an object");
        return new JsonObject((Map<String, Object>) v, source, child(key + "[" + index + "]"));
    }

    /** Любой ключ вне перечня — ошибка: опечатка в данных должна падать, а не молчать. */
    public void allowOnly(String... keys) {
        outer:
        for (String k : map.keySet()) {
            for (String allowed : keys)
                if (allowed.equals(k))
                    continue outer;
            throw error(k, "unknown property");
        }
    }

    public JsonException error(String key, String message) {
        return new JsonException(source, child(key), message);
    }

    private Object required(String key) {
        if (!map.containsKey(key) || map.get(key) == null)
            throw error(key, "missing");
        return map.get(key);
    }

    /** An empty key names this object itself: a file-level error has no key to blame. */
    private String child(String key) {
        if (key.isEmpty()) return path;
        return path.isEmpty() ? key : path + "." + key;
    }
}
