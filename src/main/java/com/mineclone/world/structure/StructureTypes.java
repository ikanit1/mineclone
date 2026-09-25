package com.mineclone.world.structure;

import java.util.List;

/**
 * The structure types of the 1.1 generator (GEN-03). They run only in chunks
 * whose features include {@code structures2}; that flag stays off until the
 * first type here ships, so a world never records a change it does not have.
 * The four 1.0 templates stay on their own path ({@code world.Structures}) and
 * keep the V1 golden hashes.
 */
public final class StructureTypes {
    /** Empty until the first multi-chunk structure is added. */
    public static final List<StructureType> V2 = List.of();

    private StructureTypes() {}
}
