package com.mineclone.render;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Gives an item icon thickness without filling its transparent background. */
public final class ItemSpriteMesh {
    private ItemSpriteMesh() { }

    public record Geometry(float[] positions, float[] uvs, float[] light, int[] indices) {
        public Mesh upload() {
            return new Mesh(positions, uvs, light, new float[light.length], indices);
        }
    }

    /** Tool packs use different margins and opposite handle diagonals. */
    public static Geometry buildTool(BufferedImage sprite, int tile) {
        Geometry mesh = build(sprite, tile);
        int top = sprite.getHeight(), bottom = -1;
        for (int y = 0; y < sprite.getHeight(); y++) for (int x = 0; x < sprite.getWidth(); x++)
            if (opaque(sprite, x, y)) { top = Math.min(top, y); bottom = Math.max(bottom, y); }
        if (bottom < 0) return mesh;
        // Hold a little above the butt, inside the handle rather than at its tip.
        int row = bottom - Math.max(1, (bottom - top) / 8);
        float sum = 0;
        int count = 0;
        for (; row <= bottom; row++) {
            for (int x = 0; x < sprite.getWidth(); x++)
                if (opaque(sprite, x, row)) { sum += x + 0.5f; count++; }
            if (count > 0) break;
        }
        float gripX = 1.5f * (sum / count / sprite.getWidth() - 0.5f);
        float gripY = 1.5f * (0.5f - (row + 0.5f) / sprite.getHeight());
        float direction = gripX < 0f ? -1f : 1f;
        float scale = HeldToolTemplate.HEIGHT / (1.5f * (bottom - top + 1) / sprite.getHeight());
        for (int i = 0; i < mesh.positions.length; i += 3) {
            mesh.positions[i] = direction * (mesh.positions[i] - gripX) * scale + HeldToolTemplate.GRIP_X;
            mesh.positions[i + 1] = (mesh.positions[i + 1] - gripY) * scale + HeldToolTemplate.GRIP_Y;
        }
        if (direction < 0) {
            // Mirroring positions also reverses winding; retain outward faces.
            for (int i = 0; i < mesh.indices.length; i += 3) {
                int tmp = mesh.indices[i + 1];
                mesh.indices[i + 1] = mesh.indices[i + 2];
                mesh.indices[i + 2] = tmp;
            }
        }
        return mesh;
    }

    public static Geometry build(BufferedImage sprite, int tile) {
        int width = sprite.getWidth(), height = sprite.getHeight();
        List<Float> positions = new ArrayList<>(), uv = new ArrayList<>(), light = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        float tileX = (tile % TextureAtlas.TILES_PER_ROW) * TextureAtlas.TILE;
        float tileY = (tile / TextureAtlas.TILES_PER_ROW) * TextureAtlas.TILE;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (!opaque(sprite, x, y)) continue;
            float x0 = 1.5f * (x / (float) width - 0.5f), x1 = x0 + 1.5f / width;
            float y1 = 1.5f * (0.5f - y / (float) height), y0 = y1 - 1.5f / height;
            float z0 = -0.045f, z1 = 0.045f;
            // Sample the centre of this pixel on all its faces: no transparent
            // fringe on the edge and no entire icon squeezed onto a side wall.
            float u = (tileX + (x + 0.5f) * TextureAtlas.TILE / width) / TextureAtlas.ATLAS_SIZE;
            float v = (tileY + (y + 0.5f) * TextureAtlas.TILE / height) / TextureAtlas.ATLAS_SIZE;
            face(positions, uv, light, indices, u, v, 1f,
                    x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1);
            face(positions, uv, light, indices, u, v, 0.9f,
                    x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0);
            if (!opaque(sprite, x - 1, y)) face(positions, uv, light, indices, u, v, 0.8f,
                    x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0);
            if (!opaque(sprite, x + 1, y)) face(positions, uv, light, indices, u, v, 0.8f,
                    x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1);
            if (!opaque(sprite, x, y - 1)) face(positions, uv, light, indices, u, v, 1f,
                    x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0);
            if (!opaque(sprite, x, y + 1)) face(positions, uv, light, indices, u, v, 0.65f,
                    x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1);
        }
        return new Geometry(floats(positions), floats(uv), floats(light),
                indices.stream().mapToInt(Integer::intValue).toArray());
    }

    private static boolean opaque(BufferedImage sprite, int x, int y) {
        return x >= 0 && y >= 0 && x < sprite.getWidth() && y < sprite.getHeight()
                && (sprite.getRGB(x, y) >>> 24) >= 128;
    }

    private static void face(List<Float> positions, List<Float> uv, List<Float> light,
                             List<Integer> indices, float u, float v, float shade, float... xyz) {
        int base = positions.size() / 3;
        for (float value : xyz) positions.add(value);
        for (int i = 0; i < 4; i++) { uv.add(u); uv.add(v); light.add(shade); }
        for (int i : new int[] {0, 1, 2, 0, 2, 3}) indices.add(base + i);
    }

    private static float[] floats(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }
}
