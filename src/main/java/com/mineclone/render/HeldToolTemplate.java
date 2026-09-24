package com.mineclone.render;

import com.mineclone.item.Item;
import org.joml.Matrix4f;

/** One first-person pose; equipment geometry comes from its actual inventory sprite. */
public final class HeldToolTemplate {
    public static final float GRIP_X = 0.47f, GRIP_Y = -0.47f;
    public static final float HEIGHT = 1.20f;
    private HeldToolTemplate() { }

    public static Matrix4f pose(float equip, float swing, float distance, boolean bobbing,
                                float inspect, float spin) {
        float k = clamp(inspect);
        k = k * k * (3f - 2f * k);
        float phase = 1f - clamp(swing);
        float arc = (float) Math.sin(Math.sqrt(phase) * Math.PI) * (1f - k);
        float sweep = -(float) Math.sin(phase * Math.PI * 2) * (1f - k);
        float drop = (float) Math.pow(1f - clamp(equip), 3) * 1.20f;
        float bobX = bobbing ? (float) Math.cos(distance * Math.PI) * 0.022f : 0f;
        float bobY = bobbing ? (float) Math.sin(distance * Math.PI * 2) * 0.016f : 0f;
        return new Matrix4f()
                .translate(mix(0.56f, 0.08f, k) + bobX - arc * 0.28f,
                        mix(-0.64f, -0.38f, k) + bobY - drop + arc * 0.10f,
                        mix(-1.0f, -1.25f, k) - arc * 0.15f)
                .rotateY(mix((float) Math.toRadians(-15f + sweep * 16f), spin, k))
                .rotateZ(mix((float) Math.toRadians(-62f + arc * 42f), -0.55f, k))
                .rotateX((float) Math.toRadians(-6f - arc * 42f))
                .scale(mix(0.65f, 0.58f, k))
                .translate(-GRIP_X, -GRIP_Y, 0f);
    }

    public static ItemSpriteMesh.Geometry mesh(Item item) {
        return mesh(item, item.iconTile);
    }

    /** Explicit tile supports the bow's draw stages as well as normal tools. */
    public static ItemSpriteMesh.Geometry mesh(Item item, int iconTile) {
        // Palette variants are authored in the PNGs. Substituting the iron
        // silhouette or swatch UVs here made held tools disagree with their
        // inventory/drop icons and could sample transparent pixels.
        return ItemSpriteMesh.buildTool(TextureAtlas.sprite(iconTile), iconTile);
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static float mix(float a, float b, float k) { return a + (b - a) * k; }
}
