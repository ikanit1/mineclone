package com.mineclone.world.shape;

import com.mineclone.world.BlockType;
import com.mineclone.world.World;

/**
 * The shape of every block (BLK-02): one registry that collision, the aim, the
 * outline and the mesher read instead of five places that knew stairs apart.
 *
 * <p>Collision keeps what {@link BlockType#solid} always meant — a solid block
 * stops a body, a non-solid one does not — and only says <em>where</em> inside
 * the cell it stops it. Stairs therefore stop you with their slab and their
 * step, not with a whole cube; a closed door still fills its cell, as it did,
 * while the aim and the outline see its panel. What the aim sees is what the
 * mesher draws, with one exception: nothing that flows is a target (TD-50).
 */
public final class Shapes {
    /** Thickness of a door panel. */
    public static final float DOOR_THICKNESS = 3f / 16f;
    /** Top of the slab under a stair's step. */
    public static final float STAIR_SLAB = 0.5f;
    /** Height of a cross-drawn block other than fire. */
    public static final float CROSS_HEIGHT = 10f / 16f;
    /** Half the width of a torch's stick. */
    public static final float TORCH_HALF_WIDTH = 2.5f / 16f;
    /** The most edges the outline of any shape has. */
    public static final int MAX_EDGES = 12 * BlockShape.MAX_BOXES * 3;

    private Shapes() {}

    /** Air and fluids: a body passes, the aim goes through. */
    public static final BlockShape EMPTY = new BlockShape() {
        @Override public int collision(byte meta, float[] out) { return 0; }
        @Override public int outline(byte meta, float[] out) { return 0; }
    };

    /** A cube. */
    public static final BlockShape FULL = new BlockShape() {
        @Override public int collision(byte meta, float[] out) { return BlockShape.box(out, 0, 0, 0, 0, 1, 1, 1); }
        @Override public int outline(byte meta, float[] out) { return BlockShape.box(out, 0, 0, 0, 0, 1, 1, 1); }
        @Override public boolean fullCube(byte meta) { return true; }
    };

    /** Two crossed planes the height of a torch: web, rope, chain, journal. */
    public static final BlockShape CROSS = cross(CROSS_HEIGHT);

    /** Fire fills its cell; it stays a target, so it can still be put out by hand. */
    public static final BlockShape FLAME = cross(1f);

    /** A torch on the floor or leaning from a wall (meta 1..4), as the mesher tilts it. */
    public static final BlockShape TORCH = new BlockShape() {
        @Override public int collision(byte meta, float[] out) { return 0; }

        @Override
        public int outline(byte meta, float[] out) {
            int mount = meta & 0x7;
            float bx = .5f, bz = .5f, tx = .5f, tz = .5f;
            if (mount == 1) { bx = .08f; tx = .34f; }
            else if (mount == 2) { bx = .92f; tx = .66f; }
            else if (mount == 3) { bz = .08f; tz = .34f; }
            else if (mount == 4) { bz = .92f; tz = .66f; }
            float w = TORCH_HALF_WIDTH;
            return BlockShape.box(out, 0,
                    Math.max(0f, Math.min(bx, tx) - w), 0f, Math.max(0f, Math.min(bz, tz) - w),
                    Math.min(1f, Math.max(bx, tx) + w), CROSS_HEIGHT, Math.min(1f, Math.max(bz, tz) + w));
        }
    };

    /**
     * Snow and the bedroll: {@code (meta & 7) + 1} eighths high. Walked through,
     * as they always were — a solid layer would be a full-block step for the
     * body, and the snow's depth is a slowdown, not a wall.
     */
    public static final BlockShape LAYER = new BlockShape() {
        @Override public int collision(byte meta, float[] out) { return 0; }

        @Override
        public int outline(byte meta, float[] out) {
            return BlockShape.box(out, 0, 0f, 0f, 0f, 1f, ((meta & 0x7) + 1) / 8f, 1f);
        }
    };

    /**
     * A slab across the cell and a step on half of it. Facing 0 puts the step
     * at low Z, 1 at high X, 2 at high Z, 3 at low X.
     */
    public static final BlockShape STAIRS = new BlockShape() {
        @Override public int collision(byte meta, float[] out) { return boxes(meta, out); }
        @Override public int outline(byte meta, float[] out) { return boxes(meta, out); }

        private int boxes(byte meta, float[] out) {
            int n = BlockShape.box(out, 0, 0f, 0f, 0f, 1f, STAIR_SLAB, 1f);
            float x0 = 0f, z0 = 0f, x1 = 1f, z1 = 1f;
            switch (meta & 0x3) {
                case 0 -> z1 = 0.5f;
                case 1 -> x0 = 0.5f;
                case 2 -> z0 = 0.5f;
                default -> x1 = 0.5f;
            }
            return BlockShape.box(out, n, x0, STAIR_SLAB, z0, x1, 1f, z1);
        }
    };

    /** A closed door fills its cell for a body, but is aimed at and outlined by its panel. */
    public static final BlockShape DOOR_CLOSED = new BlockShape() {
        @Override public int collision(byte meta, float[] out) { return FULL.collision(meta, out); }
        @Override public int outline(byte meta, float[] out) { return doorPanel(meta, false, out); }
        @Override public boolean fullCube(byte meta) { return true; }
    };

    /** An open door lets a body through; its panel, swung to the side, is still a target. */
    public static final BlockShape DOOR_OPEN = new BlockShape() {
        @Override public int collision(byte meta, float[] out) { return 0; }
        @Override public int outline(byte meta, float[] out) { return doorPanel(meta, true, out); }
    };

    private static final BlockShape[] TABLE = new BlockShape[BlockType.VALUES.length];

    static {
        for (BlockType type : BlockType.VALUES)
            TABLE[type.ordinal()] = define(type);
    }

    public static BlockShape of(BlockType type) {
        return TABLE[type.ordinal()];
    }

    /** Exhaustive: a new block does not compile until its shape is decided here. */
    private static BlockShape define(BlockType type) {
        return switch (type) {
            case AIR, WATER, WATER_FLOW, LAVA -> EMPTY;
            case TORCH -> TORCH;
            case FIRE -> FLAME;
            case WEB, ROPE, CHAIN, JOURNAL -> CROSS;
            case SNOW_LAYER, BEDROLL -> LAYER;
            case STAIRS -> STAIRS;
            case DOOR_CLOSED -> DOOR_CLOSED;
            case DOOR_OPEN -> DOOR_OPEN;
            case GRASS, DIRT, STONE, SAND, WOOD, LEAVES, BEDROCK, COBBLE, PLANKS, GLASS,
                    SNOWY_GRASS, CACTUS, COAL_ORE, IRON_ORE, GOLD_ORE, DIAMOND_ORE, CHEST,
                    FURNACE, ICE, MUD, ASH, MOSSY_COBBLE, OBSIDIAN, THIN_ICE, CRAFTING_TABLE,
                    PODZOL, PEAT, DRY_GRASS, RED_SAND, TERRACOTTA, LIMESTONE, BASALT, GRAVEL -> FULL;
        };
    }

    /** Collision boxes of the block at a world position, in its own cell. */
    public static int collision(World world, int x, int y, int z, float[] out) {
        BlockShape shape = TABLE[world.getBlock(x, y, z).ordinal()];
        return shape.collision(meta(world, shape, x, y, z), out);
    }

    /** Aim and outline boxes of the block at a world position, in its own cell. */
    public static int outline(World world, int x, int y, int z, float[] out) {
        BlockShape shape = TABLE[world.getBlock(x, y, z).ordinal()];
        return shape.outline(meta(world, shape, x, y, z), out);
    }

    /** The meta a shape needs; cubes and emptiness do not look at it. */
    public static byte meta(World world, BlockShape shape, int x, int y, int z) {
        return shape == FULL || shape == EMPTY ? 0 : world.getBlockMeta(x, y, z);
    }

    private static BlockShape cross(float height) {
        return new BlockShape() {
            @Override public int collision(byte meta, float[] out) { return 0; }
            @Override public int outline(byte meta, float[] out) { return BlockShape.box(out, 0, 0f, 0f, 0f, 1f, height, 1f); }
        };
    }

    private static int doorPanel(byte meta, boolean open, float[] out) {
        float th = DOOR_THICKNESS;
        float x0 = 0f, z0 = 0f, x1 = 1f, z1 = 1f;
        switch (meta & 0x3) {
            case 0 -> { if (open) x0 = 1f - th; else z0 = 1f - th; }
            case 1 -> { if (open) z0 = 1f - th; else x1 = th; }
            case 2 -> { if (open) x1 = th; else z1 = th; }
            default -> { if (open) z1 = th; else x0 = 1f - th; }
        }
        return BlockShape.box(out, 0, x0, 0f, z0, x1, 1f, z1);
    }

    // --- outline edges ----------------------------------------------------

    private static final float PROBE = 1e-3f;
    /** Where other boxes cut an edge; per thread, since the outline asks every frame. */
    private static final ThreadLocal<float[]> CUTS =
            ThreadLocal.withInitial(() -> new float[2 * BlockShape.MAX_BOXES + 2]);

    /**
     * The edges of the union of {@code count} boxes: where its surface folds,
     * not where two boxes meet on a flat face. A stair is two boxes and
     * eighteen edges; drawing both boxes would add six lines across its faces.
     *
     * <p>Each box edge is cut wherever another box starts or ends along it, and
     * a piece is kept when the four quarters around its middle are not all
     * inside, all outside, or split into two halves by a flat face. Pieces on
     * one line are then joined, so a ribbon is never drawn twice at a joint.
     *
     * @param out six floats an edge: {@code x0, y0, z0, x1, y1, z1}, the first
     *            end lower along the edge's axis; room for {@link #MAX_EDGES}
     * @return edges written
     */
    public static int edges(float[] boxes, int count, float[] out) {
        if (count > BlockShape.MAX_BOXES)
            throw new IllegalArgumentException("more than " + BlockShape.MAX_BOXES + " boxes");
        int n = 0;
        float[] cuts = CUTS.get();
        for (int b = 0; b < count; b++) {
            int o = b * BlockShape.STRIDE;
            for (int axis = 0; axis < 3; axis++) {
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                float lo = boxes[o + axis], hi = boxes[o + 3 + axis];
                int c = 0;
                cuts[c++] = lo;
                cuts[c++] = hi;
                for (int k = 0; k < count; k++)
                    for (int side = 0; side < 2; side++) {
                        float t = boxes[k * BlockShape.STRIDE + side * 3 + axis];
                        if (t > lo && t < hi) cuts[c++] = t;
                    }
                java.util.Arrays.sort(cuts, 0, c);
                for (int corner = 0; corner < 4; corner++) {
                    float pu = boxes[o + ((corner & 1) == 0 ? 0 : 3) + u];
                    float pv = boxes[o + ((corner & 2) == 0 ? 0 : 3) + v];
                    for (int i = 0; i + 1 < c; i++) {
                        float t0 = cuts[i], t1 = cuts[i + 1];
                        if (t1 - t0 <= PROBE) continue;
                        if (!fold(boxes, count, axis, (t0 + t1) * 0.5f, u, pu, v, pv)) continue;
                        n = addSegment(out, n, axis, u, pu, v, pv, t0, t1);
                    }
                }
            }
        }
        return n;
    }

    /** Whether the union's surface bends along the line through this point. */
    private static boolean fold(float[] boxes, int count, int axis, float t, int u, float pu, int v, float pv) {
        boolean a = inside(boxes, count, axis, t, u, pu - PROBE, v, pv - PROBE);
        boolean b = inside(boxes, count, axis, t, u, pu + PROBE, v, pv - PROBE);
        boolean c = inside(boxes, count, axis, t, u, pu - PROBE, v, pv + PROBE);
        boolean d = inside(boxes, count, axis, t, u, pu + PROBE, v, pv + PROBE);
        int filled = (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0);
        if (filled == 1 || filled == 3) return true;
        return filled == 2 && a == d;       // two diagonal quarters: the edge where two boxes touch
    }

    private static boolean inside(float[] boxes, int count, int axis, float t, int u, float pu, int v, float pv) {
        float x = axis == 0 ? t : u == 0 ? pu : pv;
        float y = axis == 1 ? t : u == 1 ? pu : pv;
        float z = axis == 2 ? t : u == 2 ? pu : pv;
        for (int k = 0; k < count; k++) {
            int o = k * BlockShape.STRIDE;
            if (x > boxes[o] && x < boxes[o + 3] && y > boxes[o + 1] && y < boxes[o + 4]
                    && z > boxes[o + 2] && z < boxes[o + 5])
                return true;
        }
        return false;
    }

    /** Adds a piece of an edge, joining it to a piece already on the same line. */
    private static int addSegment(float[] out, int n, int axis, int u, float pu, int v, float pv,
                                  float t0, float t1) {
        for (int e = 0; e < n; e++) {
            int o = e * 6;
            if (out[o + u] != pu || out[o + v] != pv || out[o + 3 + u] != pu || out[o + 3 + v] != pv
                    || out[o + axis] == out[o + 3 + axis])
                continue;
            float lo = out[o + axis], hi = out[o + 3 + axis];
            if (t0 > hi || t1 < lo) continue;
            out[o + axis] = Math.min(lo, t0);
            out[o + 3 + axis] = Math.max(hi, t1);
            return mergeAll(out, n, e, axis, u, pu, v, pv);
        }
        if (n == MAX_EDGES)
            throw new IllegalStateException("shape outline has more than " + MAX_EDGES + " edges");
        int o = n * 6;
        out[o + axis] = t0;
        out[o + u] = pu;
        out[o + v] = pv;
        out[o + 3 + axis] = t1;
        out[o + 3 + u] = pu;
        out[o + 3 + v] = pv;
        return n + 1;
    }

    /** After an edge grew, it may now reach another piece on its line; fold that one in. */
    private static int mergeAll(float[] out, int n, int grown, int axis, int u, float pu, int v, float pv) {
        int g = grown * 6;
        for (int e = 0; e < n; e++) {
            if (e == grown) continue;
            int o = e * 6;
            if (out[o + u] != pu || out[o + v] != pv || out[o + 3 + u] != pu || out[o + 3 + v] != pv
                    || out[o + axis] == out[o + 3 + axis])
                continue;
            if (out[o + axis] > out[g + 3 + axis] || out[o + 3 + axis] < out[g + axis]) continue;
            out[g + axis] = Math.min(out[g + axis], out[o + axis]);
            out[g + 3 + axis] = Math.max(out[g + 3 + axis], out[o + 3 + axis]);
            // Remove e by moving the last edge into its place.
            int last = (n - 1) * 6;
            System.arraycopy(out, last, out, o, 6);
            n--;
            if (grown == n) grown = e;      // the grown edge was the one just moved
            return mergeAll(out, n, grown, axis, u, pu, v, pv);
        }
        return n;
    }
}
