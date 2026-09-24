package com.mineclone.save;

/** Typed chunk read; inspection never renames or replaces the source file. */
public sealed interface ChunkLoad {
    record Absent() implements ChunkLoad {}
    record Loaded(ChunkSnapshot snapshot) implements ChunkLoad {}
    record Unreadable(String reason) implements ChunkLoad {}
    record TooNew(int version, int supportedVersion) implements ChunkLoad {}
}
