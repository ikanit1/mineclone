package com.mineclone.save;

import com.mineclone.world.Chunk;

/** Single source of truth for the on-disk save format. */
public final class SaveFormat {
    private SaveFormat() {}

    /** "MCLD" — first int of every save file. */
    public static final int MAGIC = 0x4D434C44;

    /**
     * Version 11 guards older readers with minReaderVersion and moves every
     * field into named sections. Future additive versions are readable when
     * their minimum reader is at most 11; unknown sections survive rewriting.
     */
    public static final int LEVEL_VERSION = 11;
    /**
     * Версия формата чанка. 2 — содержимое сундуков, 3 — состояние печей,
     * 4 — предметы на земле; 5 — адаптивный RLE блоков и метаданных;
     * 6 — стопка пишется id предмета и компонентами. Младшие версии читаются
     * как раньше: чего в них нет, того просто нет.
     */
    public static final int CHUNK_VERSION = 7;
    /**
     * Версия настроек. 5 — раскладка клавиш, 6 — привычки инвентаря
     * (расширенные подсказки, книга рецептов, сортировка); 7 — размеченный
     * хвост «имя, вид, значение» под настройки экрана, графики и игры.
     * Версии 1–6 читаются со значениями по умолчанию для того, чего в них нет.
     *
     * <p>Седьмая версия — последняя, которую придётся поднимать ради новой
     * настройки: хвост самоописывающийся, незнакомый ключ читатель пропускает
     * по виду значения, а пропавший берёт из умолчаний. Тот же приём, что у
     * секций level.dat v10.
     */
    public static final int OPTIONS_VERSION = 7;

    /**
     * A guest's {@code players/<uuid>.dat}. Version 1 is the protocol-v7
     * checkpoint as sent over the wire; version 2 adds a minimum reader and
     * holds a {@link PlayerRecord} — the same bytes as the host's player
     * section. Version 1 is read and rewritten as 2 by the next save.
     */
    public static final int GUEST_VERSION = 2;
    public static final int GUEST_V1 = 1;
    /** Bound on a version-1 payload before it is parsed. */
    public static final int GUEST_V1_MAX_BYTES = 262_144;

    /** Blocks per chunk = SIZE_X*SIZE_Y*SIZE_Z (16*128*16 = 32768). */
    public static final int CHUNK_VOLUME = Chunk.SIZE_X * Chunk.SIZE_Y * Chunk.SIZE_Z;

    public static final String SAVES_ROOT = "saves";
    public static final String DEFAULT_WORLD_ID = "world";
    public static final String LEVEL_FILE = "level.dat";
    public static final String CHUNKS_DIR = "chunks";
    /** Which generator made each chunk (GEN-02); lives beside the chunks it describes. */
    public static final String LEDGER_FILE = "ledger.dat";
    /** Снимок мира для списка миров, 256×144. */
    public static final String ICON_FILE = "icon.png";

    /** Global settings file (sibling of saves/, not inside any world dir). */
    public static final String OPTIONS_FILE = "options.dat";

    /** Fixed seed for the rotating main-menu backdrop world. Hand-picked
     *  to land the orbit center over varied surface terrain near sea level. */
    public static final long MENU_SEED = 0xC0FFEE13L;

    public static String chunkFileName(int cx, int cz) {
        return "c." + cx + "." + cz + ".dat";
    }

    /** Generates a filesystem-safe world folder ID based on current time. */
    public static String newWorldId() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return "world_" + now.format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
    }
}
