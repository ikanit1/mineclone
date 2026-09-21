package com.mineclone.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор JSON для данных игры.
 *
 * <p>Свой, а не библиотечный: у проекта нет системы сборки, через которую
 * подтягивались бы зависимости, а нужен он ровно для одного — читать
 * рукописные файлы из {@code assets/data}.
 *
 * <p>Сверх строгого JSON две поблажки: комментарии {@code //} до конца строки
 * и висячая запятая перед {@code ]} и {@code }}. В этом проекте комментарии
 * пишутся везде, и данные не должны быть исключением; а висячая запятая — самая
 * частая ошибка при дописывании строки в конец списка.
 *
 * <p>Объект становится {@link LinkedHashMap} (порядок ключей важен: порядок
 * предметов в файле — порядок в креативе), массив — {@link ArrayList}, число —
 * {@link Double}, остальное — {@link String}, {@link Boolean} и {@code null}.
 */
public final class Json {

    private final String text;
    private final String source;
    private int pos;

    private Json(String text, String source) {
        this.text = text;
        this.source = source;
    }

    public static Object parse(String text, String source) {
        Json p = new Json(text, source);
        p.skipSpace();
        Object v = p.value();
        p.skipSpace();
        if (p.pos < p.text.length())
            throw p.error(p.pos, "unexpected text after the value");
        return v;
    }

    /** Разобрать текст, корень которого обязан быть объектом. */
    @SuppressWarnings("unchecked")
    public static JsonObject parseObject(String text, String source, String rootPath) {
        Object v = parse(text, source);
        if (!(v instanceof Map))
            throw new JsonException(source, 1, 1, "the root must be an object");
        return new JsonObject((Map<String, Object>) v, source, rootPath);
    }

    public static JsonObject parseObject(Path file, String source) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        // Блокнот Windows любит писать метку порядка байтов в начало UTF-8.
        if (!text.isEmpty() && text.charAt(0) == '﻿')
            text = text.substring(1);
        return parseObject(text, source, "");
    }

    // ------------------------------------------------------------------ разбор

    private Object value() {
        if (pos >= text.length())
            throw error(pos, "unexpected end of text");
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9'))
                    yield number();
                throw error(pos, "unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++;                                   // {
        skipSpace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            if (peek() == '}') {                 // висячая запятая
                pos++;
                return out;
            }
            if (peek() != '"')
                throw error(pos, "expected a quoted key");
            int keyAt = pos;
            String key = string();
            if (out.containsKey(key))
                throw error(keyAt, "duplicate key \"" + key + "\"");
            skipSpace();
            if (peek() != ':')
                throw error(pos, "expected ':' after the key");
            pos++;
            skipSpace();
            out.put(key, value());
            skipSpace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return out;
            }
            throw error(pos, "expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        pos++;                                   // [
        skipSpace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            if (peek() == ']') {                 // висячая запятая
                pos++;
                return out;
            }
            out.add(value());
            skipSpace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return out;
            }
            throw error(pos, "expected ',' or ']'");
        }
    }

    private String string() {
        int start = pos;
        pos++;                                   // "
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length())
                throw error(start, "unterminated string");
            char c = text.charAt(pos++);
            if (c == '"')
                return sb.toString();
            if (c == '\n')
                throw error(start, "unterminated string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length())
                throw error(start, "unterminated string");
            char e = text.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length())
                        throw error(pos - 2, "bad \\u escape");
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error(pos - 2, "bad \\u escape");
                    }
                    pos += 4;
                }
                default -> throw error(pos - 2, "unknown escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = pos;
        if (peek() == '-')
            pos++;
        digits(start);
        if (peek() == '.') {
            pos++;
            digits(start);
        }
        char c = peek();
        if (c == 'e' || c == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-')
                pos++;
            digits(start);
        }
        try {
            return Double.valueOf(text.substring(start, pos));
        } catch (NumberFormatException ex) {
            throw error(start, "bad number");
        }
    }

    private void digits(int numberStart) {
        int from = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos)))
            pos++;
        if (pos == from)
            throw error(numberStart, "bad number");
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos))
            throw error(pos, "unexpected word, expected " + word);
        int end = pos + word.length();
        if (end < text.length() && Character.isLetterOrDigit(text.charAt(end)))
            throw error(pos, "unexpected word, expected " + word);
        pos = end;
        return value;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    /** Пробелы и комментарии {@code //} до конца строки. */
    private void skipSpace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                while (pos < text.length() && text.charAt(pos) != '\n')
                    pos++;
            } else {
                return;
            }
        }
    }

    private JsonException error(int at, String message) {
        int line = 1, col = 1;
        for (int i = 0; i < at && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new JsonException(source, line, col, message);
    }
}
