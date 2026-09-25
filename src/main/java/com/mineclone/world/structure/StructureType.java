package com.mineclone.world.structure;

import com.mineclone.world.Biome;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A kind of structure and where it may start (GEN-03, AC-11).
 *
 * <p>The world is cut into regions of {@code spacing} x {@code spacing}
 * chunks; each region has at most one start, in a chunk chosen from the
 * seed, the region and {@code salt} so that two starts in neighbouring
 * regions are at least {@code separation} chunks apart. The start's centre
 * must be in a biome {@code biomes} accepts. Everything the
 * {@link Assembler} builds must lie within {@code maxRadiusChunks} of the start
 * chunk: that is how far a generating chunk looks for starts that reach it.
 *
 * @param id              stable name, for the index, F3 and advancements
 * @param maxRadiusChunks how many chunks a structure reaches from its start chunk
 */
public record StructureType(String id, int spacing, int separation, long salt, Predicate<Biome> biomes,
                            HeightMode height, int maxRadiusChunks, Assembler assembler) {
    public StructureType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(biomes, "biomes");
        Objects.requireNonNull(height, "height");
        Objects.requireNonNull(assembler, "assembler");
        if (spacing < 1 || separation < 0 || separation >= spacing)
            throw new IllegalArgumentException("spacing " + spacing + " must exceed separation " + separation);
        if (maxRadiusChunks < 0 || maxRadiusChunks > 16)
            throw new IllegalArgumentException("radius " + maxRadiusChunks + " out of 0..16 chunks");
    }

    /** Which height a start gets: the terrain at its centre, or a band underground. */
    public record HeightMode(boolean surface, int minY, int maxY) {
        public static final HeightMode SURFACE = new HeightMode(true, 0, 0);

        public HeightMode {
            if (!surface && (minY < 1 || maxY < minY))
                throw new IllegalArgumentException("underground band " + minY + ".." + maxY);
        }

        public static HeightMode underground(int minY, int maxY) {
            return new HeightMode(false, minY, maxY);
        }
    }

    /** Builds a structure's pieces for one start; a pure function of its context. */
    @FunctionalInterface
    public interface Assembler {
        /** The pieces, none sharing a block; empty when the site does not suit. */
        List<StructurePiece> assemble(StructureStart.Context ctx);
    }
}
