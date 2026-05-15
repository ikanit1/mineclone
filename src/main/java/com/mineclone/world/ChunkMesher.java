package com.mineclone.world;

import com.mineclone.render.Mesh;
import com.mineclone.render.MeshData;
import com.mineclone.render.TextureAtlas;

import java.util.ArrayList;
import java.util.List;

/** Face culling mesher with vertex Ambient Occlusion. */
public class ChunkMesher {

    private static final int[][] FACE_DIRS = {
            { 0, 0, 1 }, // +Z (south)
            { 0, 0, -1 }, // -Z (north)
            { 1, 0, 0 }, // +X (east)
            { -1, 0, 0 }, // -X (west)
            { 0, 1, 0 }, // +Y (top)
            { 0, -1, 0 }, // -Y (bottom)
    };

    private static final float[] FACE_LIGHT = {
            0.88f, 0.88f, 0.80f, 0.80f, 1.0f, 0.65f
    };

    /**
     * AO darkening per occlusion level (0..3). Softened for gentler corner shading.
     */
    private static final float[] AO_TABLE = { 1.0f, 0.86f, 0.74f, 0.62f };

    private final World world;

    public ChunkMesher(World world) {
        this.world = world;
    }

    /** Temporary shim — callers updated in Task 4. */
    public Mesh build(Chunk chunk) {
        return buildData(chunk)[0].upload();
    }

    /** CPU-only mesh build; safe to call from background threads. */
    public MeshData[] buildData(Chunk chunk) {
        List<Float> positions = new ArrayList<>(4096);
        List<Float> uvs = new ArrayList<>(2048);
        List<Float> light = new ArrayList<>(1024);
        List<Float> blockLightList = new ArrayList<>(1024);
        List<Integer> indices = new ArrayList<>(4096);

        List<Float> wPositions   = new ArrayList<>(1024);
        List<Float> wUvs         = new ArrayList<>(512);
        List<Float> wLight       = new ArrayList<>(256);
        List<Float> wBlockLight  = new ArrayList<>(256);
        List<Integer> wIndices   = new ArrayList<>(1024);

        int baseX = chunk.cx * Chunk.SIZE_X;
        int baseZ = chunk.cz * Chunk.SIZE_Z;

        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int y = 0; y < Chunk.SIZE_Y; y++) {
                for (int z = 0; z < Chunk.SIZE_Z; z++) {
                    BlockType b = chunk.get(x, y, z);
                    if (b == BlockType.AIR)
                        continue;

                    if (b == BlockType.TORCH) {
                        emitCross(chunk, positions, uvs, light, blockLightList, indices, x, y, z, baseX, baseZ, b);
                        continue;
                    }

                    if (b == BlockType.WATER || b == BlockType.WATER_FLOW) {
                        emitWaterBlock(chunk, wPositions, wUvs, wLight, wBlockLight, wIndices,
                                x, y, z, baseX, baseZ, b);
                        continue;
                    }

                    if (b == BlockType.DOOR_CLOSED || b == BlockType.DOOR_OPEN) {
                        emitDoor(chunk, positions, uvs, light, blockLightList, indices, x, y, z, baseX, baseZ, b);
                        continue;
                    }

                    if (b == BlockType.STAIRS) {
                        emitStairs(chunk, positions, uvs, light, blockLightList, indices, x, y, z, baseX, baseZ, b);
                        continue;
                    }

                    for (int f = 0; f < 6; f++) {
                        int[] d = FACE_DIRS[f];
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        BlockType neighbor;
                        if (ny < 0 || ny >= Chunk.SIZE_Y) {
                            neighbor = BlockType.AIR;
                        } else if (chunk.inBounds(nx, ny, nz)) {
                            neighbor = chunk.get(nx, ny, nz);
                        } else {
                            neighbor = world.getBlock(baseX + nx, ny, baseZ + nz);
                        }

                        if (shouldDrawFace(b, neighbor)) {
                            int tile;
                            if (f == 4)
                                tile = b.topTile;
                            else if (f == 5)
                                tile = b.bottomTile;
                            else
                                tile = b.sideTile;
                            emitFace(chunk, positions, uvs, light, blockLightList, indices,
                                    x, y, z, baseX, baseZ, f, tile, FACE_LIGHT[f]);
                        }
                    }
                }
            }
        }

        float[] pa = toFloatArray(positions);
        float[] ua = toFloatArray(uvs);
        float[] la = toFloatArray(light);
        float[] bla = toFloatArray(blockLightList);
        int[] ia = toIntArray(indices);
        MeshData opaque = new MeshData(pa, ua, la, bla, ia);
        MeshData water = new MeshData(
                toFloatArray(wPositions), toFloatArray(wUvs),
                toFloatArray(wLight), toFloatArray(wBlockLight),
                toIntArray(wIndices));
        return new MeshData[]{ opaque, water };
    }

    private static boolean shouldDrawFace(BlockType self, BlockType neighbor) {
        if (neighbor == BlockType.AIR)
            return true;
        if (neighbor == BlockType.WATER || neighbor == BlockType.WATER_FLOW)
            return false;
        if (neighbor.transparent || neighbor.cutout)
            return neighbor != self;
        return false;
    }

    private float waterLevelTopY(BlockType b, byte meta) {
        if (b == BlockType.WATER) return 1.0f;
        if (b == BlockType.WATER_FLOW) {
            int lev = meta & 0x0F;
            return (lev == 0) ? 1.0f : (8 - lev) / 8.0f;
        }
        return 0f;
    }

    /** Average height of the 4 water blocks sharing the corner at world (wx, wy, wz). */
    private float waterCornerTopY(int wx, int wy, int wz) {
        int[] bxs = { wx - 1, wx, wx - 1, wx };
        int[] bzs = { wz - 1, wz - 1, wz, wz };
        float sum = 0f; int n = 0;
        for (int i = 0; i < 4; i++) {
            BlockType b = world.getBlock(bxs[i], wy, bzs[i]);
            if (b == BlockType.WATER || b == BlockType.WATER_FLOW) {
                BlockType above = world.getBlock(bxs[i], wy + 1, bzs[i]);
                boolean hasWaterAbove = above == BlockType.WATER || above == BlockType.WATER_FLOW;
                if (hasWaterAbove) {
                    sum += 1.0f;
                } else {
                    byte m = world.getBlockMeta(bxs[i], wy, bzs[i]);
                    sum += waterLevelTopY(b, m);
                }
                n++;
            }
        }
        return n > 0 ? sum / n : 1.0f;
    }

    private void emitWaterBlock(Chunk chunk,
            List<Float> pos, List<Float> uvs,
            List<Float> light, List<Float> blockLightList,
            List<Integer> idx,
            int x, int y, int z, int baseX, int baseZ,
            BlockType b) {
        byte meta = chunk.getMeta(x, y, z);
        BlockType above = chunk.inBounds(x, y + 1, z)
                ? chunk.get(x, y + 1, z)
                : world.getBlock(baseX + x, y + 1, baseZ + z);
        boolean waterAbove = above == BlockType.WATER || above == BlockType.WATER_FLOW;
        float topY = waterAbove ? 1.0f : waterLevelTopY(b, meta);

        float[] uv = TextureAtlas.uv(b.sideTile);
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];

        float skyRaw = skyAt(chunk, x, y + 1, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
        float blRaw = blockLightAt(chunk, x, y, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
        float waterBl = blRaw + 10.0f; // water flag for shader animation
        float lv = Math.max(0.6f * skyRaw, blRaw);

        // Pre-compute all four corner heights once; shared by top face AND side faces
        // so that the side face top edges exactly match the top face edges (no gaps).
        int wx = baseX + x, wz = baseZ + z;
        float h00 = waterCornerTopY(wx,     y, wz    );
        float h10 = waterCornerTopY(wx + 1, y, wz    );
        float h11 = waterCornerTopY(wx + 1, y, wz + 1);
        float h01 = waterCornerTopY(wx,     y, wz + 1);

        // +Y top face with per-corner height interpolation
        if (!waterAbove) {
            float[][] corners = {
                { x,     y + h01, z + 1 },
                { x + 1, y + h11, z + 1 },
                { x + 1, y + h10, z     },
                { x,     y + h00, z     }
            };
            float[][] uvCorner = { { u0, v1 }, { u1, v1 }, { u1, v0 }, { u0, v0 } };
            addWaterQuad(pos, uvs, light, blockLightList, idx, corners, uvCorner, lv, waterBl);
        }

        // -Y bottom face
        BlockType below = (y > 0 && chunk.inBounds(x, y - 1, z))
                ? chunk.get(x, y - 1, z)
                : world.getBlock(baseX + x, y - 1, baseZ + z);
        if (below == BlockType.AIR) {
            float[][] corners = { { x, y, z }, { x + 1, y, z }, { x + 1, y, z + 1 }, { x, y, z + 1 } };
            float[][] uvCorner = { { u0, v0 }, { u1, v0 }, { u1, v1 }, { u0, v1 } };
            addWaterQuad(pos, uvs, light, blockLightList, idx, corners, uvCorner, lv * 0.65f, waterBl);
        }

        // Side faces.
        // Each direction maps to the two TOP corner heights of its edge (vertex2, vertex3).
        // Using the same corner heights as the top face eliminates seams/gaps.
        int[][] sideDirs = { { 0, 0, 1 }, { 0, 0, -1 }, { 1, 0, 0 }, { -1, 0, 0 } };
        float[] sideLights = { 0.88f, 0.88f, 0.80f, 0.80f };
        float[][] faceCornerH = {
            { h11, h01 }, // +Z: v2=(x+1,z+1)=h11, v3=(x,z+1)=h01
            { h00, h10 }, // -Z: v2=(x,z)=h00,     v3=(x+1,z)=h10
            { h10, h11 }, // +X: v2=(x+1,z)=h10,   v3=(x+1,z+1)=h11
            { h01, h00 }, // -X: v2=(x,z+1)=h01,   v3=(x,z)=h00
        };

        for (int f = 0; f < 4; f++) {
            int nx = x + sideDirs[f][0], nz = z + sideDirs[f][2];
            BlockType nb = chunk.inBounds(nx, y, nz)
                    ? chunk.get(nx, y, nz)
                    : world.getBlock(baseX + nx, y, baseZ + nz);

            float faceBot = 0f;
            if (nb == BlockType.AIR || (nb.transparent && nb != BlockType.WATER && nb != BlockType.WATER_FLOW)) {
                // render full face from 0 to corner heights
            } else if (nb == BlockType.WATER || nb == BlockType.WATER_FLOW) {
                byte nbm = nb == BlockType.WATER_FLOW
                        ? (chunk.inBounds(nx, y, nz) ? chunk.getMeta(nx, y, nz) : world.getBlockMeta(baseX + nx, y, baseZ + nz))
                        : 0;
                float nbTopY = waterLevelTopY(nb, nbm);
                if (nbTopY >= topY) continue; // neighbor is as tall or taller, skip
                faceBot = nbTopY;
            } else {
                continue;
            }

            float hA = Math.max(faceCornerH[f][0], faceBot);
            float hB = Math.max(faceCornerH[f][1], faceBot);
            if (hA <= faceBot && hB <= faceBot) continue; // degenerate face

            float[][] sideCorners;
            if (f == 0) // +Z
                sideCorners = new float[][] { { x, y + faceBot, z + 1 }, { x + 1, y + faceBot, z + 1 }, { x + 1, y + hA, z + 1 }, { x, y + hB, z + 1 } };
            else if (f == 1) // -Z
                sideCorners = new float[][] { { x + 1, y + faceBot, z }, { x, y + faceBot, z }, { x, y + hA, z }, { x + 1, y + hB, z } };
            else if (f == 2) // +X
                sideCorners = new float[][] { { x + 1, y + faceBot, z + 1 }, { x + 1, y + faceBot, z }, { x + 1, y + hA, z }, { x + 1, y + hB, z + 1 } };
            else // -X
                sideCorners = new float[][] { { x, y + faceBot, z }, { x, y + faceBot, z + 1 }, { x, y + hA, z + 1 }, { x, y + hB, z } };

            float vB  = v0 + (v1 - v0) * faceBot;
            float vTA = v0 + (v1 - v0) * hA;
            float vTB = v0 + (v1 - v0) * hB;
            float[][] uvS = { { u0, vB }, { u1, vB }, { u1, vTA }, { u0, vTB } };
            addWaterQuad(pos, uvs, light, blockLightList, idx, sideCorners, uvS, lv * sideLights[f], waterBl);
        }
    }

    private void addWaterQuad(List<Float> pos, List<Float> uvs,
            List<Float> light, List<Float> blockLightList,
            List<Integer> idx,
            float[][] corners, float[][] uvCorner,
            float lv, float blVal) {
        int base = pos.size() / 3;
        for (int i = 0; i < 4; i++) {
            pos.add(corners[i][0]);
            pos.add(corners[i][1]);
            pos.add(corners[i][2]);
            uvs.add(uvCorner[i][0]);
            uvs.add(uvCorner[i][1]);
            light.add(lv);
            blockLightList.add(blVal);
        }
        idx.add(base);
        idx.add(base + 1);
        idx.add(base + 2);
        idx.add(base);
        idx.add(base + 2);
        idx.add(base + 3);
    }

    private void emitDoor(Chunk chunk, List<Float> pos, List<Float> uvs, List<Float> light, List<Float> bl,
            List<Integer> idx,
            int x, int y, int z, int baseX, int baseZ, BlockType b) {
        byte meta = chunk.getMeta(x, y, z);
        int facing = meta & 0x3;
        boolean open = (b == BlockType.DOOR_OPEN);

        // Standard closed door is on the north (-Z) edge.
        // open=true rotates 90° inward.
        float th = 3f / 16f; // thickness
        float x0 = 0, y0 = 0, z0 = 0, x1 = 1, y1 = 1, z1 = 1;

        if (facing == 0) { // South facing (+Z)
            if (open) { x0 = 1 - th; }   // swing to +X edge, full Z
            else      { z0 = 1 - th; }   // slab on +Z face
        } else if (facing == 1) { // West facing (-X)
            if (open) { z0 = 1 - th; }   // swing to +Z edge, full X
            else      { x1 = 0 + th; }   // slab on -X face
        } else if (facing == 2) { // North facing (-Z)
            if (open) { x1 = 0 + th; }   // swing to -X edge, full Z
            else      { z1 = 0 + th; }   // slab on -Z face
        } else if (facing == 3) { // East facing (+X)
            if (open) { z1 = 0 + th; }   // swing to -Z edge, full X
            else      { x0 = 1 - th; }   // slab on +X face
        }

        boolean isTopHalf = (meta & 0x4) != 0;
        int tile = isTopHalf ? 16 : b.sideTile; // 16 = door_top, 15 = door_bottom
        emitBox(chunk, pos, uvs, light, bl, idx, x, y, z, baseX, baseZ, tile, x0, y0, z0, x1, y1, z1);
    }

    private void emitStairs(Chunk chunk, List<Float> pos, List<Float> uvs, List<Float> light, List<Float> bl,
            List<Integer> idx,
            int x, int y, int z, int baseX, int baseZ, BlockType b) {
        byte meta = chunk.getMeta(x, y, z);
        int facing = meta & 0x3;

        // Bottom slab is always present
        emitBox(chunk, pos, uvs, light, bl, idx, x, y, z, baseX, baseZ, b.sideTile, 0, 0, 0, 1, 0.5f, 1);

        // Top step depends on facing
        float x0 = 0, z0 = 0, x1 = 1, z1 = 1;
        if (facing == 0)      z1 = 0.5f; // step at -Z half  (matches Player: relZ < 0.5)
        else if (facing == 1) x0 = 0.5f; // step at +X half
        else if (facing == 2) z0 = 0.5f; // step at +Z half  (matches Player: relZ >= 0.5)
        else if (facing == 3) x1 = 0.5f; // step at -X half

        // Skip bottom face (index 5, -Y at y=0.5) — it is interior and would render dark
        emitBox(chunk, pos, uvs, light, bl, idx, x, y, z, baseX, baseZ, b.sideTile, x0, 0.5f, z0, x1, 1f, z1, 1 << 5);
    }

    private void emitBox(Chunk chunk, List<Float> pos, List<Float> uvs, List<Float> light, List<Float> bl,
            List<Integer> idx,
            int x, int y, int z, int baseX, int baseZ, int tileIndex,
            float x0, float y0, float z0, float x1, float y1, float z1) {
        emitBox(chunk, pos, uvs, light, bl, idx, x, y, z, baseX, baseZ, tileIndex, x0, y0, z0, x1, y1, z1, 0);
    }

    private void emitBox(Chunk chunk, List<Float> pos, List<Float> uvs, List<Float> light, List<Float> bl,
            List<Integer> idx,
            int x, int y, int z, int baseX, int baseZ, int tileIndex,
            float x0, float y0, float z0, float x1, float y1, float z1, int skipFaces) {
        float[] uv = TextureAtlas.uv(tileIndex);
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];

        float skyRaw = skyAt(chunk, x, y + 1, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
        float blRaw = blockLightAt(chunk, x, y, z, baseX, baseZ) / (float) Chunk.MAX_LIGHT;
        float lv = Math.max(0.6f * skyRaw, blRaw);

        float[][][] faces = {
                { { x1, y0, z1 }, { x1, y1, z1 }, { x0, y1, z1 }, { x0, y0, z1 } }, // +Z
                { { x0, y0, z0 }, { x0, y1, z0 }, { x1, y1, z0 }, { x1, y0, z0 } }, // -Z
                { { x1, y0, z0 }, { x1, y1, z0 }, { x1, y1, z1 }, { x1, y0, z1 } }, // +X
                { { x0, y0, z1 }, { x0, y1, z1 }, { x0, y1, z0 }, { x0, y0, z0 } }, // -X
                { { x0, y1, z1 }, { x1, y1, z1 }, { x1, y1, z0 }, { x0, y1, z0 } }, // +Y
                { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 }, { x0, y0, z1 } } // -Y
        };
        float[] faceLight = { 0.88f, 0.88f, 0.80f, 0.80f, 1.0f, 0.65f };
        float[][] uvQ = { { u0, v1 }, { u1, v1 }, { u1, v0 }, { u0, v0 } };

        for (int f = 0; f < 6; f++) {
            if ((skipFaces & (1 << f)) != 0)
                continue;
            float[][] quad = faces[f];
            if (quad[0][0] == quad[2][0] && quad[0][1] == quad[2][1] && quad[0][2] == quad[2][2])
                continue; // degenerate flat face
            int base = pos.size() / 3;
            for (int i = 0; i < 4; i++) {
                pos.add(x + quad[i][0]);
                pos.add(y + quad[i][1]);
                pos.add(z + quad[i][2]);
                uvs.add(uvQ[i][0]);
                uvs.add(uvQ[i][1]);
                light.add(lv * faceLight[f]);
                bl.add(blRaw * faceLight[f]);
            }
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
        }
    }

    private void emitFace(Chunk chunk,
            List<Float> pos, List<Float> uvs, List<Float> light, List<Float> blockLightList, List<Integer> idx,
            int x, int y, int z, int baseX, int baseZ,
            int face, int tileIndex, float lightVal) {
        float[] uv = TextureAtlas.uv(tileIndex);
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        int base = pos.size() / 3;

        int[][] quad;
        switch (face) {
            case 0:
                quad = new int[][] { { 0, 0, 1 }, { 1, 0, 1 }, { 1, 1, 1 }, { 0, 1, 1 } };
                break; // +Z
            case 1:
                quad = new int[][] { { 1, 0, 0 }, { 0, 0, 0 }, { 0, 1, 0 }, { 1, 1, 0 } };
                break; // -Z
            case 2:
                quad = new int[][] { { 1, 0, 1 }, { 1, 0, 0 }, { 1, 1, 0 }, { 1, 1, 1 } };
                break; // +X
            case 3:
                quad = new int[][] { { 0, 0, 0 }, { 0, 0, 1 }, { 0, 1, 1 }, { 0, 1, 0 } };
                break; // -X
            case 4:
                quad = new int[][] { { 0, 1, 1 }, { 1, 1, 1 }, { 1, 1, 0 }, { 0, 1, 0 } };
                break; // +Y top
            default:
                quad = new int[][] { { 0, 0, 0 }, { 1, 0, 0 }, { 1, 0, 1 }, { 0, 0, 1 } };
                break; // -Y bottom
        }

        float[][] uvQuad = { { u0, v1 }, { u1, v1 }, { u1, v0 }, { u0, v0 } };

        int[] dN = FACE_DIRS[face];
        int nAxis = (dN[0] != 0) ? 0 : (dN[1] != 0) ? 1 : 2;
        int nSign = dN[nAxis];
        int ta = (nAxis == 0) ? 1 : 0;
        int tb = (nAxis == 2) ? 1 : 2;

        float[] ao = new float[4];
        float[] blVerts = new float[4];
        for (int i = 0; i < 4; i++) {
            int[] corner = quad[i];
            int sa = corner[ta] * 2 - 1;
            int sb = corner[tb] * 2 - 1;

            int[] off = { 0, 0, 0 };
            off[nAxis] = nSign;

            int s1x = off[0], s1y = off[1], s1z = off[2];
            int s2x = off[0], s2y = off[1], s2z = off[2];
            int cox = off[0], coy = off[1], coz = off[2];

            switch (ta) {
                case 0 -> {
                    s1x += sa;
                    cox += sa;
                }
                case 1 -> {
                    s1y += sa;
                    coy += sa;
                }
                case 2 -> {
                    s1z += sa;
                    coz += sa;
                }
            }
            switch (tb) {
                case 0 -> {
                    s2x += sb;
                    cox += sb;
                }
                case 1 -> {
                    s2y += sb;
                    coy += sb;
                }
                case 2 -> {
                    s2z += sb;
                    coz += sb;
                }
            }

            boolean side1 = isOpaqueWorld(chunk, x + s1x, y + s1y, z + s1z, baseX, baseZ);
            boolean side2 = isOpaqueWorld(chunk, x + s2x, y + s2y, z + s2z, baseX, baseZ);
            boolean cor = isOpaqueWorld(chunk, x + cox, y + coy, z + coz, baseX, baseZ);

            int aoLevel = (side1 && side2) ? 3 : (b(side1) + b(side2) + b(cor));
            float aoFactor = AO_TABLE[aoLevel];

            // Per-vertex sky light (averaged across non-opaque neighbours).
            int skySum = skyAt(chunk, x + off[0], y + off[1], z + off[2], baseX, baseZ);
            int skyCnt = 1;
            if (!side1) {
                skySum += skyAt(chunk, x + s1x, y + s1y, z + s1z, baseX, baseZ);
                skyCnt++;
            }
            if (!side2) {
                skySum += skyAt(chunk, x + s2x, y + s2y, z + s2z, baseX, baseZ);
                skyCnt++;
            }
            if (!cor && !(side1 && side2)) {
                skySum += skyAt(chunk, x + cox, y + coy, z + coz, baseX, baseZ);
                skyCnt++;
            }
            float sky = (skySum / (float) skyCnt) / (float) Chunk.MAX_LIGHT;
            ao[i] = aoFactor * lightVal * sky;

            // Per-vertex block light (same neighbour sampling, AO also applied).
            int blSum = blockLightAt(chunk, x + off[0], y + off[1], z + off[2], baseX, baseZ);
            int blCnt = 1;
            if (!side1) {
                blSum += blockLightAt(chunk, x + s1x, y + s1y, z + s1z, baseX, baseZ);
                blCnt++;
            }
            if (!side2) {
                blSum += blockLightAt(chunk, x + s2x, y + s2y, z + s2z, baseX, baseZ);
                blCnt++;
            }
            if (!cor && !(side1 && side2)) {
                blSum += blockLightAt(chunk, x + cox, y + coy, z + coz, baseX, baseZ);
                blCnt++;
            }
            blVerts[i] = aoFactor * (blSum / (float) blCnt) / (float) Chunk.MAX_LIGHT;
        }

        for (int i = 0; i < 4; i++) {
            float fx = x + quad[i][0], fy = y + quad[i][1], fz = z + quad[i][2];
            pos.add(fx);
            pos.add(fy);
            pos.add(fz);
            uvs.add(uvQuad[i][0]);
            uvs.add(uvQuad[i][1]);
            light.add(ao[i]);
            blockLightList.add(blVerts[i]);
        }

        // Anti-anisotropy flip: pick diagonal on whichever attribute is dominant.
        float aoSum = ao[0] + ao[1] + ao[2] + ao[3];
        boolean flip = aoSum > 0f
                ? (ao[0] + ao[2]) < (ao[1] + ao[3])
                : (blVerts[0] + blVerts[2]) < (blVerts[1] + blVerts[3]);
        if (flip) {
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 3);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base + 3);
        } else {
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
        }
    }

    private int blockLightAt(Chunk chunk, int lx, int ly, int lz, int baseX, int baseZ) {
        if (ly < 0 || ly >= Chunk.SIZE_Y)
            return 0;
        if (chunk.inBounds(lx, ly, lz))
            return chunk.getBlockLight(lx, ly, lz);
        return world.getBlockLightWorld(baseX + lx, ly, baseZ + lz);
    }

    private int skyAt(Chunk chunk, int lx, int ly, int lz, int baseX, int baseZ) {
        if (ly < 0)
            return 0;
        if (ly >= Chunk.SIZE_Y)
            return Chunk.MAX_LIGHT;
        if (chunk.inBounds(lx, ly, lz))
            return chunk.getSkyLight(lx, ly, lz);
        return world.getSkyLight(baseX + lx, ly, baseZ + lz);
    }

    private boolean isOpaqueWorld(Chunk chunk, int lx, int ly, int lz, int baseX, int baseZ) {
        if (ly < 0 || ly >= Chunk.SIZE_Y)
            return false;
        BlockType bt;
        if (chunk.inBounds(lx, ly, lz))
            bt = chunk.get(lx, ly, lz);
        else
            bt = world.getBlock(baseX + lx, ly, baseZ + lz);
        return bt != BlockType.AIR && !bt.transparent && !bt.cutout;
    }

    /**
     * Plus-shaped (+) torch model: two perpendicular vertical planes through
     * the block centre, 10/16 tall, each rendered double-sided.
     * Plane A sits at z=0.5 (visible from north/south).
     * Plane B sits at x=0.5 (visible from east/west).
     */
    private void emitCross(Chunk chunk,
            List<Float> pos, List<Float> uvs, List<Float> light, List<Float> blockLightList,
            List<Integer> idx,
            int x, int y, int z, int baseX, int baseZ, BlockType b) {
        float[] uv = TextureAtlas.uv(b.sideTile);
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float h = 10f / 16f; // torch height in block units

        int skyRaw = skyAt(chunk, x, y + 1, z, baseX, baseZ);
        float skyFrac = skyRaw / (float) Chunk.MAX_LIGHT;
        int blRaw = blockLightAt(chunk, x, y, z, baseX, baseZ);
        float blVal = blRaw / (float) Chunk.MAX_LIGHT;
        float lv = Math.max(skyFrac, blVal);

        // Each row: {wx,wy,wz} for the 4 corners BL,BR,TR,TL
        // UV mapping: U spans tile left→right, V spans tile bottom→top
        float[][][] planes = {
                // Plane A front (normal +Z): x=0→1, y=0→h, z=0.5
                { { 0f, 0f, .5f }, { 1f, 0f, .5f }, { 1f, h, .5f }, { 0f, h, .5f } },
                // Plane A back (normal -Z)
                { { 1f, 0f, .5f }, { 0f, 0f, .5f }, { 0f, h, .5f }, { 1f, h, .5f } },
                // Plane B front (normal +X): x=0.5, z=0→1, y=0→h
                { { .5f, 0f, 1f }, { .5f, 0f, 0f }, { .5f, h, 0f }, { .5f, h, 1f } },
                // Plane B back (normal -X)
                { { .5f, 0f, 0f }, { .5f, 0f, 1f }, { .5f, h, 1f }, { .5f, h, 0f } },
        };
        float[][] uvQ = { { u0, v1 }, { u1, v1 }, { u1, v0 }, { u0, v0 } };

        for (float[][] quad : planes) {
            int base = pos.size() / 3;
            for (int i = 0; i < 4; i++) {
                pos.add(x + quad[i][0]);
                pos.add(y + quad[i][1]);
                pos.add(z + quad[i][2]);
                uvs.add(uvQ[i][0]);
                uvs.add(uvQ[i][1]);
                light.add(lv);
                blockLightList.add(blVal);
            }
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
        }
    }

    private static int b(boolean v) {
        return v ? 1 : 0;
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++)
            arr[i] = list.get(i);
        return arr;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++)
            arr[i] = list.get(i);
        return arr;
    }
}
