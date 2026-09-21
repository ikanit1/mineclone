package com.mineclone.net.photon;

import com.mineclone.data.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON ровно в том виде, в каком его понимает Photon.
 *
 * <p>Читать умеет {@link Json} — он уже есть в проекте для {@code assets/data}
 * и отдаёт {@code Map}, {@code List}, {@code Double}, {@code String}. Писать
 * приходится здесь: игре это было не нужно ни разу.
 *
 * <p>Тонкость протокола: параметры операции едут не объектом, а плоским
 * массивом «ключ, значение, ключ, значение». Ключи — числа, поэтому объект с
 * числовыми ключами тут не пройдёт: у Photon это разные типы. Отсюда
 * {@link #vals(Map)} на чтении и запись списком на отправке.
 */
public final class PhotonJson {

    private PhotonJson() {
    }

    /** Записать значение: строка, число, логическое, список, карта или null. */
    public static void write(StringBuilder out, Object v) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof String s) {
            quote(out, s);
        } else if (v instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short
                || v instanceof Byte) {
            out.append(v);
        } else if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            // Целое пишется без дробной части: Photon сам решает, какой это
            // тип, и «4.0» в поле числа игроков он трактует иначе, чем «4».
            if (d == Math.rint(d) && !Double.isInfinite(d))
                out.append((long) d);
            else
                out.append(d);
        } else if (v instanceof int[] arr) {
            out.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0)
                    out.append(',');
                out.append(arr[i]);
            }
            out.append(']');
        } else if (v instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0)
                    out.append(',');
                write(out, list.get(i));
            }
            out.append(']');
        } else if (v instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first)
                    out.append(',');
                first = false;
                quote(out, String.valueOf(e.getKey()));
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else {
            quote(out, v.toString());
        }
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20)
                        out.append(String.format("\\u%04x", (int) c));
                    else
                        out.append(c);
                }
            }
        }
        out.append('"');
    }

    /** Разобрать сообщение сервера. Не объект — значит не наше дело, вернётся null. */
    public static Map<String, Object> parseMessage(String text) {
        Object v = Json.parse(text, "photon");
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet())
                out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return null;
    }

    /**
     * Плоский массив параметров в карту «код параметра — значение».
     *
     * <p>Нечётная длина — испорченное сообщение; лучше пустая карта, чем
     * съехавшие на единицу ключи, в которых адрес сервера окажется числом
     * игроков.
     */
    public static Map<Integer, Object> vals(Object raw) {
        Map<Integer, Object> out = new LinkedHashMap<>();
        if (!(raw instanceof List<?> list) || (list.size() & 1) != 0)
            return out;
        for (int i = 0; i + 1 < list.size(); i += 2) {
            Integer key = asInt(list.get(i));
            if (key != null)
                out.put(key, list.get(i + 1));
        }
        return out;
    }

    public static Integer asInt(Object v) {
        if (v instanceof Number n)
            return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static int intOr(Map<Integer, Object> vals, int key, int fallback) {
        Integer v = asInt(vals.get(key));
        return v == null ? fallback : v;
    }

    public static String strOr(Map<Integer, Object> vals, int key, String fallback) {
        Object v = vals.get(key);
        return v instanceof String s ? s : fallback;
    }

    /** Вложенный объект: свойства комнаты, свойства участника, список комнат. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> objectOr(Object raw) {
        if (raw instanceof Map<?, ?> m)
            return (Map<String, Object>) m;
        return Map.of();
    }

    public static List<Object> listOr(Object raw) {
        if (raw instanceof List<?> l)
            return new ArrayList<>(l);
        return List.of();
    }
}
