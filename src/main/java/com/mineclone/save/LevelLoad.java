package com.mineclone.save;

/** A missing file is the only read result that permits creating a new world. */
public sealed interface LevelLoad {
    record Absent() implements LevelLoad {}
    record Loaded(LevelData data) implements LevelLoad {}
    record Unreadable(String reason) implements LevelLoad {}
    record TooNew(int version, int supportedVersion) implements LevelLoad {}
}
