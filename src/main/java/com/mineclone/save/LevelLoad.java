package com.mineclone.save;

/** A missing file is the only read result that permits creating a new world. */
public sealed interface LevelLoad {
    record Absent() implements LevelLoad {}
    record Loaded(LevelData data) implements LevelLoad {}
    record Unreadable(String reason) implements LevelLoad {}
    /**
     * Written by a newer build: {@code what} names what is too new — the level
     * format, or the generator its unedited land depends on (its version, or
     * the bits of the 1.1 changes it records).
     */
    record TooNew(String what, int version, int supportedVersion) implements LevelLoad {
        public TooNew(int version, int supportedVersion) { this("level version", version, supportedVersion); }
    }
}
