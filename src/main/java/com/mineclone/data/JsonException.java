package com.mineclone.data;

/**
 * Ошибка в данных: где и что.
 *
 * <p>Сообщение начинается с места — {@code items/tools.json:12:7:} для ошибки
 * разбора или {@code items/tools.json: iron_pickaxe.tool.level:} для ошибки
 * значения. Сломанный JSON должен находиться по первой строке лога, а не
 * поиском по всему каталогу данных.
 */
public final class JsonException extends RuntimeException {
    /** Имя файла или иного источника текста. */
    public final String source;
    /** Строка и столбец с единицы; 0 — ошибка значения, а не разбора. */
    public final int line, column;

    public JsonException(String source, int line, int column, String message) {
        super(source + ":" + line + ":" + column + ": " + message);
        this.source = source;
        this.line = line;
        this.column = column;
    }

    public JsonException(String source, String path, String message) {
        super(source + (path.isEmpty() ? "" : ": " + path) + ": " + message);
        this.source = source;
        this.line = 0;
        this.column = 0;
    }
}
