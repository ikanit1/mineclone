package com.mineclone;

import com.mineclone.render.MeshData;
import com.mineclone.render.OutlineAnimator;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkMesher;
import com.mineclone.world.World;
import com.mineclone.world.shape.BlockShape;
import com.mineclone.world.shape.Shapes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * BLK-02: one registry of block shapes. The boxes are checked against what the
 * mesher draws, so a shape that drifts from the picture fails here and not in
 * a player's hands.
 */
final class ShapeTests {
    static void runAll(TestMain.Runner r) {
        r.run("every block's shape agrees with its solidity, for every meta", ShapeTests::solidity);
        r.run("stairs are a slab and a step on the facing's half", ShapeTests::stairs);
        r.run("door panels are where the outline always drew them", ShapeTests::doors);
        r.run("layers rise by eighths and do not stop a body", ShapeTests::layers);
        r.run("a torch's box holds the stick the mesher tilts", ShapeTests::torch);
        r.run("the mesher draws exactly the shape's boxes", ShapeTests::meshIsTheShape);
        r.run("an outline follows the union's folds, not the boxes", ShapeTests::edges);
        r.run("the outline carries a shape's edges as it slides", ShapeTests::slidingEdges);
        r.run("no block type is special-cased in player or mob collision", ShapeTests::noStairsInPhysics);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final float[] UNIT = { 0, 0, 0, 1, 1, 1 };

    private static void solidity() {
        float[] boxes = BlockShape.buffer();
        for (BlockType type : BlockType.VALUES) {
            BlockShape shape = Shapes.of(type);
            check(shape != null, type + " has no shape");
            for (int m = 0; m < 16; m++) {
                byte meta = (byte) m;
                int c = shape.collision(meta, boxes);
                check(c <= BlockShape.MAX_BOXES, type + " writes " + c + " boxes");
                check((c > 0) == type.solid, type + " meta " + m + ": " + c + " collision boxes, solid=" + type.solid);
                inCell(boxes, c, type + " collision");
                check(shape.fullCube(meta) == (c == 1 && BlockShape.unit(boxes, 0)),
                        type + " meta " + m + " claims fullCube=" + shape.fullCube(meta));
                int o = shape.outline(meta, boxes);
                check(o <= BlockShape.MAX_BOXES, type + " outlines " + o + " boxes");
                inCell(boxes, o, type + " outline");
                boolean flows = type == BlockType.AIR || type == BlockType.WATER
                        || type == BlockType.WATER_FLOW || type == BlockType.LAVA;
                check((o == 0) == flows, type + " is " + (o == 0 ? "not " : "") + "a target");
            }
        }
    }

    private static void inCell(float[] boxes, int n, String what) {
        for (int i = 0; i < n; i++)
            for (int axis = 0; axis < 3; axis++) {
                float lo = boxes[i * 6 + axis], hi = boxes[i * 6 + 3 + axis];
                check(lo >= 0f && hi <= 1f && hi > lo, what + " box " + i + " axis " + axis + ": " + lo + ".." + hi);
            }
    }

    private static void stairs() {
        float[][] steps = {
                { 0, .5f, 0, 1, 1, .5f },    // facing 0: low Z
                { .5f, .5f, 0, 1, 1, 1 },    // facing 1: high X
                { 0, .5f, .5f, 1, 1, 1 },    // facing 2: high Z
                { 0, .5f, 0, .5f, 1, 1 } };  // facing 3: low X
        float[] boxes = BlockShape.buffer();
        for (int meta = 0; meta < 16; meta++) {
            int n = Shapes.STAIRS.collision((byte) meta, boxes);
            check(n == 2, "stairs have two boxes");
            box(boxes, 0, new float[] { 0, 0, 0, 1, .5f, 1 }, "slab, meta " + meta);
            box(boxes, 1, steps[meta & 3], "step, meta " + meta);
            float[] outline = BlockShape.buffer();
            check(Shapes.STAIRS.outline((byte) meta, outline) == 2
                    && java.util.Arrays.equals(boxes, outline), "stairs are aimed at as they collide");
            check(!Shapes.STAIRS.fullCube((byte) meta), "stairs are not a cube");
        }
    }

    private static void box(float[] boxes, int i, float[] expected, String what) {
        for (int k = 0; k < 6; k++)
            check(boxes[i * 6 + k] == expected[k], what + ": " + java.util.Arrays.toString(
                    java.util.Arrays.copyOfRange(boxes, i * 6, i * 6 + 6)));
    }

    /** The values the removed {@code BlockOutline.doorBox} returned. */
    private static void doors() {
        float t = 3f / 16f;
        float[][] closed = { { 0, 0, 1 - t, 1, 1, 1 }, { 0, 0, 0, t, 1, 1 }, { 0, 0, 0, 1, 1, t }, { 1 - t, 0, 0, 1, 1, 1 } };
        float[][] open = { { 1 - t, 0, 0, 1, 1, 1 }, { 0, 0, 1 - t, 1, 1, 1 }, { 0, 0, 0, t, 1, 1 }, { 0, 0, 0, 1, 1, t } };
        float[] boxes = BlockShape.buffer();
        for (int meta = 0; meta < 16; meta++) {
            check(Shapes.DOOR_CLOSED.outline((byte) meta, boxes) == 1, "one panel");
            box(boxes, 0, closed[meta & 3], "closed door, meta " + meta);
            check(Shapes.DOOR_OPEN.outline((byte) meta, boxes) == 1, "one panel");
            box(boxes, 0, open[meta & 3], "open door, meta " + meta);
            check(Shapes.DOOR_CLOSED.collision((byte) meta, boxes) == 1 && BlockShape.unit(boxes, 0),
                    "a closed door still fills its cell for a body");
            check(Shapes.DOOR_OPEN.collision((byte) meta, boxes) == 0, "an open door lets a body through");
        }
    }

    private static void layers() {
        float[] boxes = BlockShape.buffer();
        for (BlockType type : new BlockType[] { BlockType.SNOW_LAYER, BlockType.BEDROLL })
            for (int meta = 0; meta < 16; meta++) {
                check(Shapes.of(type).outline((byte) meta, boxes) == 1, "one box");
                box(boxes, 0, new float[] { 0, 0, 0, 1, ((meta & 7) + 1) / 8f, 1 }, type + " meta " + meta);
                check(Shapes.of(type).collision((byte) meta, boxes) == 0, type + " stops a body");
            }
    }

    private static void torch() {
        float[] boxes = BlockShape.buffer();
        float w = Shapes.TORCH_HALF_WIDTH, h = Shapes.CROSS_HEIGHT;
        // The mesher's bottom and top of the stick for each mount.
        float[][] ends = { { .5f, .5f, .5f, .5f }, { .08f, .5f, .34f, .5f }, { .92f, .5f, .66f, .5f },
                { .5f, .08f, .5f, .34f }, { .5f, .92f, .5f, .66f } };
        for (int mount = 0; mount < 5; mount++) {
            check(Shapes.TORCH.outline((byte) mount, boxes) == 1, "one box");
            float[] e = ends[mount];
            for (float[] p : new float[][] { { e[0] - w, 0, e[1] }, { e[0] + w, 0, e[1] }, { e[2] - w, h, e[3] },
                    { e[2] + w, h, e[3] }, { e[0], 0, e[1] - w }, { e[0], 0, e[1] + w }, { e[2], h, e[3] - w },
                    { e[2], h, e[3] + w } }) {
                float px = Math.max(0f, Math.min(1f, p[0])), pz = Math.max(0f, Math.min(1f, p[2]));
                check(px >= boxes[0] && px <= boxes[3] && p[1] >= boxes[1] && p[1] <= boxes[4]
                                && pz >= boxes[2] && pz <= boxes[5],
                        "mount " + mount + " draws outside its box at " + java.util.Arrays.toString(p));
            }
            float volume = (boxes[3] - boxes[0]) * (boxes[4] - boxes[1]) * (boxes[5] - boxes[2]);
            check(volume < 0.25f, "a torch's box is a stick, not a cell: " + volume);
        }
        check(Shapes.TORCH.collision((byte) 0, boxes) == 0, "a torch stops no one");
    }

    /**
     * Every vertex the mesher emits for a shaped block is a corner of the
     * shape's boxes, and every corner is drawn: the picture and the shape are
     * one geometry.
     */
    private static void meshIsTheShape() {
        List<Object[]> cases = new ArrayList<>();
        for (int m = 0; m < 4; m++) cases.add(new Object[] { BlockType.STAIRS, (byte) m });
        for (int m = 0; m < 8; m++) cases.add(new Object[] { BlockType.DOOR_CLOSED, (byte) m });
        for (int m = 0; m < 8; m++) cases.add(new Object[] { BlockType.DOOR_OPEN, (byte) m });
        for (int m = 0; m < 8; m++) cases.add(new Object[] { BlockType.SNOW_LAYER, (byte) m });
        World world = new World(5L);
        Chunk chunk = world.getChunk(0, 0);
        ChunkMesher mesher = new ChunkMesher(world);
        float[] boxes = BlockShape.buffer();
        int bx = 6, by = 70, bz = 6;
        for (Object[] c : cases) {
            BlockType type = (BlockType) c[0];
            byte meta = (byte) c[1];
            for (int x = 0; x < Chunk.SIZE_X; x++)
                for (int z = 0; z < Chunk.SIZE_Z; z++)
                    for (int y = 0; y < Chunk.SIZE_Y; y++)
                        chunk.set(x, y, z, BlockType.AIR);
            chunk.set(bx, by, bz, type);
            chunk.setMeta(bx, by, bz, meta);
            MeshData data = mesher.buildData(chunk)[0];
            int n = Shapes.of(type).outline(meta, boxes);
            List<float[]> corners = new ArrayList<>();
            for (int i = 0; i < n; i++)
                for (int k = 0; k < 8; k++)
                    corners.add(new float[] { boxes[i * 6 + ((k & 1) == 0 ? 0 : 3)],
                            boxes[i * 6 + 1 + ((k & 2) == 0 ? 0 : 3)], boxes[i * 6 + 2 + ((k & 4) == 0 ? 0 : 3)] });
            boolean[] drawn = new boolean[corners.size()];
            check(data.positions.length > 0, type + " meta " + meta + " drew nothing");
            for (int v = 0; v < data.positions.length; v += 3) {
                float lx = data.positions[v] - bx, ly = data.positions[v + 1] - by, lz = data.positions[v + 2] - bz;
                boolean corner = false;
                for (int k = 0; k < corners.size(); k++) {
                    float[] p = corners.get(k);
                    if (Math.abs(p[0] - lx) < 1e-5f && Math.abs(p[1] - ly) < 1e-5f && Math.abs(p[2] - lz) < 1e-5f) {
                        corner = true;
                        drawn[k] = true;
                    }
                }
                check(corner, type + " meta " + meta + " drew a vertex off its shape: " + lx + "," + ly + "," + lz);
            }
            for (int k = 0; k < drawn.length; k++)
                check(drawn[k], type + " meta " + meta + " left a corner of its shape undrawn: "
                        + java.util.Arrays.toString(corners.get(k)));
        }
    }

    private static void edges() {
        float[] boxes = BlockShape.buffer();
        float[] out = new float[Shapes.MAX_EDGES * 6];

        Shapes.FULL.outline((byte) 0, boxes);
        int cube = Shapes.edges(boxes, 1, out);
        float[] expected = new float[12 * 6];
        OutlineAnimator.boxEdges(UNIT, expected);
        sameEdges(out, cube, expected, 12, "cube");

        // A cube cut in two along a face is still the cube's twelve edges.
        BlockShape.box(boxes, 0, 0, 0, 0, 1, .5f, 1);
        BlockShape.box(boxes, 1, 0, .5f, 0, 1, 1, 1);
        sameEdges(out, Shapes.edges(boxes, 2, out), expected, 12, "a cube in two halves");

        // Two boxes apart are two outlines.
        BlockShape.box(boxes, 0, 0, 0, 0, .25f, .25f, .25f);
        BlockShape.box(boxes, 1, .5f, .5f, .5f, 1, 1, 1);
        check(Shapes.edges(boxes, 2, out) == 24, "two boxes apart");

        // A stair is an L extruded along one axis: two L's of six edges and six edges between them.
        for (int facing = 0; facing < 4; facing++) {
            int n = Shapes.edges(boxes, Shapes.STAIRS.outline((byte) facing, boxes), out);
            check(n == 18, "stair facing " + facing + " has " + n + " edges, not 18");
            float top = 0;
            for (int e = 0; e < n; e++) {
                // No edge lies on the back face's middle (where the slab meets the step flush)...
                boolean flatSeam = out[e * 6 + 1] == .5f && out[e * 6 + 4] == .5f
                        && onBack(facing, out, e);
                check(!flatSeam, "stair facing " + facing + " draws the seam across its back face");
                top = Math.max(top, out[e * 6 + 4]);
            }
            check(top == 1f, "the outline reaches the step's top");
        }
        // ...and the concave edge inside the L is there, for facing 0 at z = 0.5, y = 0.5.
        int n = Shapes.edges(boxes, Shapes.STAIRS.outline((byte) 0, boxes), out);
        check(hasEdge(out, n, new float[] { 0, .5f, .5f, 1, .5f, .5f }), "the inner corner of the L");
        check(hasEdge(out, n, new float[] { 0, 1, 0, 1, 1, 0 }), "the step's top back edge");
        check(!hasEdge(out, n, new float[] { 0, .5f, 0, 1, .5f, 0 }), "no seam where slab and step share the back");

        Shapes.DOOR_OPEN.outline((byte) 2, boxes);
        check(Shapes.edges(boxes, 1, out) == 12, "a panel is a box");
    }

    private static boolean onBack(int facing, float[] out, int e) {
        int o = e * 6;
        return switch (facing) {
            case 0 -> out[o + 2] == 0f && out[o + 5] == 0f;
            case 1 -> out[o] == 1f && out[o + 3] == 1f;
            case 2 -> out[o + 2] == 1f && out[o + 5] == 1f;
            default -> out[o] == 0f && out[o + 3] == 0f;
        };
    }

    private static boolean hasEdge(float[] out, int n, float[] edge) {
        for (int e = 0; e < n; e++) {
            boolean same = true;
            for (int k = 0; k < 6; k++)
                same &= out[e * 6 + k] == edge[k];
            if (same) return true;
        }
        return false;
    }

    private static void sameEdges(float[] out, int n, float[] expected, int m, String what) {
        check(n == m, what + ": " + n + " edges, not " + m);
        for (int e = 0; e < m; e++)
            check(hasEdge(out, n, java.util.Arrays.copyOfRange(expected, e * 6, e * 6 + 6)),
                    what + " misses edge " + e);
    }

    private static void slidingEdges() {
        float[] boxes = BlockShape.buffer();
        float[] local = new float[Shapes.MAX_EDGES * 6];
        int n = Shapes.edges(boxes, Shapes.STAIRS.outline((byte) 1, boxes), local);
        OutlineAnimator a = new OutlineAnimator();
        float[] at10 = shifted(local, n, 10, 64, 3);
        a.update(0.016f, new float[] { 10, 64, 3, 11, 65, 4 }, at10, n);
        float[] drawn = new float[Shapes.MAX_EDGES * 6];
        check(a.edges(drawn) == n, "the stair keeps its edges");
        for (int i = 0; i < n * 6; i++)
            check(Math.abs(drawn[i] - at10[i]) < 1e-5f, "a fresh outline sits on its target");
        // Aim at the next stair along X: the shape arrives at once, the place slides.
        float[] at11 = shifted(local, n, 11, 64, 3);
        a.update(0.016f, new float[] { 11, 64, 3, 12, 65, 4 }, at11, n);
        a.edges(drawn);
        float dx = drawn[0] - at10[0];
        check(dx > 0.1f && dx < 0.99f, "the outline slides rather than jumps: " + dx);
        for (int i = 0; i < 60; i++)
            a.update(0.016f, new float[] { 11, 64, 3, 12, 65, 4 }, at11, n);
        a.edges(drawn);
        for (int i = 0; i < n * 6; i++)
            check(Math.abs(drawn[i] - at11[i]) < 1e-3f, "and settles on the new stair");
        // Looking away fades it where it was.
        a.update(0.016f, null, null, 0);
        check(a.edges(drawn) == n && Math.abs(drawn[0] - at11[0]) < 1e-3f, "a fading outline stays put");
    }

    private static float[] shifted(float[] edges, int n, int x, int y, int z) {
        float[] out = edges.clone();
        int[] d = { x, y, z };
        for (int i = 0; i < n * 6; i++)
            out[i] += d[i % 3];
        return out;
    }

    /** Acceptance of BLK-02: collision reads shapes, not a list of special blocks. */
    private static void noStairsInPhysics() throws Exception {
        for (String file : new String[] { "src/main/java/com/mineclone/game/Player.java",
                "src/main/java/com/mineclone/world/entity/EntityPhysics.java" }) {
            String source = Files.readString(Path.of(file));
            check(!source.contains("STAIRS"), file + " still names STAIRS");
            check(!source.contains("resolveStairs"), file + " still resolves stairs by hand");
        }
    }
}
