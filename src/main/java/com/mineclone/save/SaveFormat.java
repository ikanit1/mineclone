package com.mineclone.save;

import com.mineclone.world.Chunk;

/** Single source of truth for the on-disk save format. */
public final class SaveFormat {
    private SaveFormat() {}

    /** "MCLD" — first int of every save file. */
    public static final int MAGIC = 0x4D434C44;

    public static final int LEVEL_VERSION = 3;
    public static final int CHUNK_VERSION = 1;
    public static final int OPTIONS_VERSION = 1;

    /** Blocks per chunk = SIZE_X*SIZE_Y*SIZE_Z (16*128*16 = 32768). */
    public static final int CHUNK_VOLUME = Chunk.SIZE_X * Chunk.SIZE_Y * Chunk.SIZE_Z;

    public static final String SAVES_ROOT = "saves";
    public static final String DEFAULT_WORLD_ID = "world";
    public static final String LEVEL_FILE = "level.dat";
    public static final String CHUNKS_DIR = "chunks";

    /** Global settings file (sibling of saves/, not inside any world dir). */
    public static final String OPTIONS_FILE = "options.dat";

    /** Fixed seed for the rotating main-menu backdrop world. Hand-picked
     *  to land the orbit center over varied surface terrain near sea level. */
    public static final long MENU_SEED = 0xC0FFEE13L;

    public static String chunkFileName(int cx, int cz) {
        return "c." + cx + "." + cz + ".dat";
    }
}
