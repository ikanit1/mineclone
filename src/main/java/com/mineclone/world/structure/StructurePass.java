package com.mineclone.world.structure;

import com.mineclone.world.Chunk;
import java.util.List;

/**
 * The fifth generation pass (GEN-03): every structure piece that reaches a
 * chunk is written into it, cut to the chunk. Runs on the generating worker,
 * on the detached chunk only; the starts come from the shared
 * {@link StructureIndex}.
 */
public final class StructurePass {
    private StructurePass() {}

    /** Places what reaches {@code chunk}; returns the writes that fell outside it (zero unless a piece is wrong). */
    public static int place(StructureIndex index, Chunk chunk) {
        if (index.types().isEmpty())
            return 0;
        ChunkWriter out = new ChunkWriter(chunk);
        BoundingBox area = out.box();
        List<StructureStart> starts = index.reaching(area);
        for (StructureStart s : starts)
            for (StructurePiece p : s.pieces()) {
                BoundingBox clip = p.box().intersection(area);
                if (clip != null)
                    p.place(out, clip);
            }
        return out.refused();
    }
}
