package com.mineclone.render;

import com.mineclone.item.Item;
import org.joml.Matrix4f;

/** Three tool silhouettes, material variants, and one first-person display pose. */
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

    /**
     * То же, но с явным тайлом: у лука их три — по стадии натяжения.
     *
     * Предмет без инструментальной части (лук) экструдируется как есть:
     * подбирать ему шаблон не по чему, да и незачем — своя иконка у него уже
     * нужной формы.
     */
    public static ItemSpriteMesh.Geometry mesh(Item item, int iconTile) {
        if (item.tool == null)
            return ItemSpriteMesh.buildTool(TextureAtlas.sprite(iconTile), iconTile);
        String kind = item.tool.toolClass().dataName();
        int template = tile("iron_" + kind);
        if (template < 0)
            return ItemSpriteMesh.buildTool(TextureAtlas.sprite(iconTile), iconTile);
        var pixels = TextureAtlas.sprite(template);
        var geometry = ItemSpriteMesh.buildTool(pixels, template);
        String name = item.id.path();
        int separator = name.indexOf('_');
        String material = separator < 0 ? name : name.substring(0, separator);
        int target = switch (material) {
            case "gold" -> tile("tool_gold");
            case "copper" -> tile("tool_copper");
            default -> iconTile;
        };
        boolean metalPalette = material.equals("gold") || material.equals("copper");
        float tileSpan = 1f / TextureAtlas.TILES_PER_ROW;
        float sourceX = template % TextureAtlas.TILES_PER_ROW * tileSpan;
        float sourceY = template / TextureAtlas.TILES_PER_ROW * tileSpan;
        float targetX = target % TextureAtlas.TILES_PER_ROW * tileSpan;
        float targetY = target / TextureAtlas.TILES_PER_ROW * tileSpan;
        for (int i = 0; i < geometry.uvs().length; i += 2) {
            float u = geometry.uvs()[i] - sourceX, v = geometry.uvs()[i + 1] - sourceY;
            int x = Math.min(pixels.getWidth() - 1, Math.round(u / tileSpan * pixels.getWidth() - 0.5f));
            int y = Math.min(pixels.getHeight() - 1, Math.round(v / tileSpan * pixels.getHeight() - 0.5f));
            int rgb = pixels.getRGB(x, y);
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            // Iron's grey pixels form the head; the brown wooden handle stays
            // on the template. Gold and copper share that same silhouette.
            boolean head = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 15;
            if (!metalPalette || head) {
                geometry.uvs()[i] = targetX + u;
                geometry.uvs()[i + 1] = targetY + v;
                if (metalPalette)
                    geometry.light()[i / 2] *= Math.max(0.10f, (r + g + b) / 765f);
            }
        }
        return geometry;
    }

    private static int tile(String name) {
        for (int i = 0; i < TextureAtlas.TILE_NAMES.length; i++)
            if (TextureAtlas.TILE_NAMES[i].equals(name)) return i;
        return -1;
    }
    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static float mix(float a, float b, float k) { return a + (b - a) * k; }
}
