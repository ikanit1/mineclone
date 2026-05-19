package com.mineclone.render;

import com.mineclone.world.BlockType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

public final class HeldItemRenderer {
    private final Shader blockShader;
    private final Shader solidShader;
    private final Mesh armMesh;
    private final Map<BlockType, Mesh> blockMeshes = new EnumMap<>(BlockType.class);

    public HeldItemRenderer() {
        this.blockShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
        this.solidShader = new Shader(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT);
        this.armMesh = createSolidBox();
    }

    public void render(TextureAtlas atlas, BlockType held, float aspect,
            float fovDegrees, float equipProgress, float swingProgress,
            float walkDistance, boolean underwater,
            float daylight, float brightness, float skyFrac, float blockFrac) {
        float equip = clamp01(equipProgress);
        float swing = 1f - clamp01(swingProgress); // 0 → 1 over the swing
        // MC swing curve: short windup, sharp impact, slower follow-through.
        // sin(s·π)^1.5 gives a sharper peak than plain sin and matches MC's feel.
        float swingArc  = (float) Math.pow(Math.sin(swing * Math.PI), 1.5);
        float swingYaw  = (float) -Math.sin(swing * (float) Math.PI * 2.0); // left→right horizontal arc
        float swingTilt = (float) Math.sin(swing * (float) Math.PI * 0.5);  // impact dip toward target

        // Lissajous figure-8 view-bob (MC-authentic):
        //   X = cos(t)   · Ax  → one side-to-side cycle
        //   Y = sin(2·t) · Ay  → two up-down cycles per one left-right (the "×2" is the secret)
        float bobPhase = walkDistance * (float) Math.PI;
        float bobX = (float) Math.cos(bobPhase) * 0.022f;
        float bobY = (float) Math.sin(bobPhase * 2f) * 0.016f;

        // Single source of truth for held-item lighting — mirrors the chunk shader exactly.
        // effectiveLight = max(sky * daylight, blockLight) incorporates both sources.
        // lightTint  — used directly for solid-color arm (solidShader has no lighting model).
        // ambient / effectiveLight — passed as uniforms to blockShader so face-direction
        //   multipliers (baked into vLight) still apply per-face, giving AO-like shading.
        float ambient       = 0.04f + 0.18f * daylight;
        float effectiveLight = Math.max(skyFrac * daylight, blockFrac);
        float lightTint     = (float) Math.pow(Math.max(ambient, effectiveLight), 0.75) * brightness;

        // Equip ease-out: fast at start, soft landing. Cubic.
        float eq = 1f - (1f - equip) * (1f - equip) * (1f - equip);
        float equipDrop  = (1f - eq) * 0.62f;          // vertical drop while bringing up
        float equipTilt  = (1f - eq) * (float) Math.toRadians(60f); // forward pitch while equipping

        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(Math.max(55f, Math.min(95f, fovDegrees))),
                aspect, 0.05f, 20f);
        Matrix4f view = new Matrix4f();

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);

        boolean emptyHand = (held == null || held == BlockType.AIR);
        if (emptyHand) {
            // Show arm only when nothing is held.
            solidShader.bind();
            solidShader.setMat4("uProjection", projection);
            solidShader.setMat4("uView", view);

            Matrix4f armRoot = new Matrix4f()
                    .translate(0.88f + bobX + swingArc * 0.18f,
                            -1.12f - equipDrop + bobY + swingTilt * 0.04f,
                            -0.98f - swingArc * 0.06f)
                    .rotateZ((float) Math.toRadians(-8f - swingArc * 22f))
                    .rotateY((float) Math.toRadians(6f + swingYaw * 14f))
                    .rotateX((float) Math.toRadians(-12f + swingArc * 18f) + equipTilt);

            float sleeveR = (underwater ? 0.30f : 0.40f) * lightTint;
            float sleeveG = (underwater ? 0.42f : 0.50f) * lightTint;
            float sleeveB = (underwater ? 0.62f : 0.76f) * lightTint;
            solidShader.setVec4("uColor", sleeveR, sleeveG, sleeveB, 1f);
            solidShader.setMat4("uModel", new Matrix4f(armRoot)
                    .translate(0f, 0.65f, 0f)
                    .scale(0.33f, 0.22f, 0.33f));
            armMesh.render();

            float skinR = (underwater ? 0.60f : 0.95f) * lightTint;
            float skinG = (underwater ? 0.64f : 0.70f) * lightTint;
            float skinB = (underwater ? 0.60f : 0.51f) * lightTint;
            solidShader.setVec4("uColor", skinR, skinG, skinB, 1f);
            solidShader.setMat4("uModel", new Matrix4f(armRoot)
                    .translate(0f, 0.32f, 0f)
                    .scale(0.28f, 0.62f, 0.28f));
            armMesh.render();
            solidShader.unbind();
        }

        if (held != null && held != BlockType.AIR) {
            Mesh mesh = blockMeshes.computeIfAbsent(held, HeldItemRenderer::createItemMesh);
            // Torch is its own light source — boost minimum brightness so it never goes dark.
            float itemLight = (held == BlockType.TORCH)
                    ? Math.max(effectiveLight, 0.75f) : effectiveLight;
            blockShader.bind();
            blockShader.setMat4("uProjection", projection);
            blockShader.setMat4("uView", view);
            blockShader.setInt("uAtlas", 0);
            blockShader.setVec3("uFogColor", new Vector3f(0, 0, 0));
            blockShader.setFloat("uFogStart", 100f);
            blockShader.setFloat("uFogEnd", 120f);
            blockShader.setFloat("uAmbient", ambient);
            blockShader.setFloat("uDaylight", itemLight);
            blockShader.setFloat("uBrightness", brightness);
            blockShader.setFloat("uTime", 0f);
            atlas.bind(0);
            Matrix4f itemModel = new Matrix4f()
                    .translate(0.52f + bobX + swingArc * 0.14f,
                            -0.48f - equipDrop * 0.70f + bobY - swingTilt * 0.12f,
                            -1.00f - swingArc * 0.10f)
                    .rotateX((float) Math.toRadians(-23f - swingArc * 28f) + equipTilt * 0.6f)
                    .rotateY((float) Math.toRadians(42f + swingYaw * 22f))
                    .rotateZ((float) Math.toRadians(8f - swingArc * 14f))
                    .scale(0.34f);
            blockShader.setMat4("uModel", itemModel);
            mesh.render();
            blockShader.unbind();
        }

        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
    }

    /** Dispatches to the correct mesh builder for each block type. */
    private static Mesh createItemMesh(BlockType block) {
        if (block == BlockType.TORCH) return createTorchMesh();
        return createBlockMesh(block);
    }

    /** Cross (+) mesh matching ChunkMesher.emitCross but in local [-0.5,+0.5] space. */
    private static Mesh createTorchMesh() {
        List<Float> pos = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Float> light = new ArrayList<>();
        List<Float> blockLight = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        float[] uv = TextureAtlas.uv(BlockType.TORCH.sideTile);
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float h = 10f / 16f; // same proportion as world torch
        // planes centred at origin: x/z in [-0.5, 0.5], y in [-h/2, h/2]
        float y0 = -h * 0.5f, y1 = h * 0.5f;
        float[][][] planes = {
            { {-0.5f, y0, 0f}, { 0.5f, y0, 0f}, { 0.5f, y1, 0f}, {-0.5f, y1, 0f} }, // A front
            { { 0.5f, y0, 0f}, {-0.5f, y0, 0f}, {-0.5f, y1, 0f}, { 0.5f, y1, 0f} }, // A back
            { { 0f, y0, -0.5f}, { 0f, y0,  0.5f}, { 0f, y1,  0.5f}, { 0f, y1, -0.5f} }, // B front
            { { 0f, y0,  0.5f}, { 0f, y0, -0.5f}, { 0f, y1, -0.5f}, { 0f, y1,  0.5f} }, // B back
        };
        float[][] uvQ = { {u0, v1}, {u1, v1}, {u1, v0}, {u0, v0} };
        for (float[][] quad : planes) {
            int base = pos.size() / 3;
            for (int i = 0; i < 4; i++) {
                pos.add(quad[i][0]); pos.add(quad[i][1]); pos.add(quad[i][2]);
                uvs.add(uvQ[i][0]); uvs.add(uvQ[i][1]);
                light.add(1.0f); blockLight.add(0f);
            }
            indices.add(base); indices.add(base + 1); indices.add(base + 2);
            indices.add(base); indices.add(base + 2); indices.add(base + 3);
        }
        return new Mesh(toFloatArray(pos), toFloatArray(uvs), toFloatArray(light),
                toFloatArray(blockLight), toIntArray(indices));
    }

    private static Mesh createBlockMesh(BlockType block) {
        List<Float> pos = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Float> light = new ArrayList<>();
        List<Float> blockLight = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        emitBox(pos, uvs, light, blockLight, indices,
                block.sideTile, block.topTile, block.bottomTile);
        return new Mesh(toFloatArray(pos), toFloatArray(uvs), toFloatArray(light),
                toFloatArray(blockLight), toIntArray(indices));
    }

    private static Mesh createSolidBox() {
        List<Float> pos = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Float> light = new ArrayList<>();
        List<Float> blockLight = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        emitBox(pos, uvs, light, blockLight, indices, 0, 0, 0);
        return new Mesh(toFloatArray(pos), toFloatArray(uvs), toFloatArray(light),
                toFloatArray(blockLight), toIntArray(indices));
    }

    private static void emitBox(List<Float> pos, List<Float> uvs,
            List<Float> light, List<Float> blockLight, List<Integer> indices,
            int sideTile, int topTile, int bottomTile) {
        float a = -0.5f, b = 0.5f;
        float[][][] faces = {
                { { a, a, b }, { b, a, b }, { b, b, b }, { a, b, b } },
                { { b, a, a }, { a, a, a }, { a, b, a }, { b, b, a } },
                { { b, a, b }, { b, a, a }, { b, b, a }, { b, b, b } },
                { { a, a, a }, { a, a, b }, { a, b, b }, { a, b, a } },
                { { a, b, b }, { b, b, b }, { b, b, a }, { a, b, a } },
                { { a, a, a }, { b, a, a }, { b, a, b }, { a, a, b } }
        };
        int[] tiles = { sideTile, sideTile, sideTile, sideTile, topTile, bottomTile };
        float[] faceLight = { 0.88f, 0.88f, 0.80f, 0.80f, 1.0f, 0.65f };
        for (int f = 0; f < faces.length; f++) {
            float[] uv = TextureAtlas.uv(Math.max(0, tiles[f]));
            float[][] uvQ = {
                    { uv[0], uv[3] }, { uv[2], uv[3] }, { uv[2], uv[1] }, { uv[0], uv[1] }
            };
            int base = pos.size() / 3;
            for (int i = 0; i < 4; i++) {
                pos.add(faces[f][i][0]);
                pos.add(faces[f][i][1]);
                pos.add(faces[f][i][2]);
                uvs.add(uvQ[i][0]);
                uvs.add(uvQ[i][1]);
                light.add(faceLight[f]);
                blockLight.add(0f);
            }
            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
            indices.add(base);
            indices.add(base + 2);
            indices.add(base + 3);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
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

    public void destroy() {
        armMesh.destroy();
        for (Mesh mesh : blockMeshes.values())
            mesh.destroy();
        blockMeshes.clear();
        blockShader.destroy();
        solidShader.destroy();
    }
}
