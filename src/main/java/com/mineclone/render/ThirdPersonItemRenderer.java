package com.mineclone.render;

import com.mineclone.world.ItemStack;
import com.mineclone.item.Item;
import org.joml.Matrix4f;
import java.util.HashMap;
import java.util.Map;
import static org.lwjgl.opengl.GL11.*;

/** World-space held items: shared tool silhouettes, real depth, lighting and shadows. */
public final class ThirdPersonItemRenderer {
    private static final String VERTEX = """
            #version 330 core
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec2 aUv;
            layout(location=2) in float aLight;
            uniform mat4 uProjection, uView, uModel;
            uniform float uSkyVis, uBlockVis;
            centroid out vec2 vUv;
            out vec3 vRepeat, vWorld;
            out float vLight, vBlockLight, vFogDist;
            void main() {
                vec4 world=uModel*vec4(aPos,1);
                vec4 view=uView*world;
                gl_Position=uProjection*view;
                vUv=aUv; vRepeat=vec3(0); vWorld=world.xyz;
                vLight=aLight*uSkyVis; vBlockLight=aLight*uBlockVis;
                vFogDist=length(view.xyz);
            }
            """;
    private final Shader shader = new Shader(VERTEX, Shaders.CHUNK_FRAGMENT);
    private final Shader shadow = new Shader(Shaders.SHADOW_VERTEX, Shaders.SHADOW_FRAGMENT);
    private final Map<Item, Mesh> meshes = new HashMap<>();

    private Mesh mesh(ItemStack held) {
        return meshes.computeIfAbsent(held.item, item -> {
            if (item.tool != null) return HeldToolTemplate.mesh(item).upload();
            if (item.block != null) return HeldItemRenderer.createItemMesh(item.block);
            int tile = held.iconTile();
            return ItemSpriteMesh.build(TextureAtlas.sprite(tile), tile).upload();
        });
    }

    /** The mesh grip is mapped to the animated palm, never to a fixed world offset. */
    public static Matrix4f itemPose(Matrix4f palm, ItemStack held) {
        Matrix4f pose = new Matrix4f(palm);
        if (held.tool() != null) {
            return pose.rotateX(-0.65f).rotateY(0.9f).rotateZ(-(float)Math.PI/4)
                    .scale(0.60f).translate(-HeldToolTemplate.GRIP_X, -HeldToolTemplate.GRIP_Y, 0);
        }
        if (held.block() == com.mineclone.world.BlockType.TORCH)
            return pose.rotateX(-0.3f).scale(0.65f).translate(0, 0.25f, 0);
        if (held.block() != null) return pose.translate(0, 0, -0.16f).rotateY(0.35f).scale(0.30f);
        return pose.rotateX(-0.35f).rotateY(0.65f).scale(0.40f);
    }

    public void render(TextureAtlas atlas, ItemStack held, Matrix4f palm,
                       Matrix4f proj, Matrix4f view, SceneLighting lighting, float sky, float block) {
        if (held == null || held.count <= 0 || atlas == null) return;
        boolean culling = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_CULL_FACE);
        shader.bind(); lighting.apply(shader);
        shader.setMat4("uProjection",proj); shader.setMat4("uView",view);
        shader.setMat4("uModel",itemPose(palm,held));
        shader.setFloat("uSkyVis",sky); shader.setFloat("uBlockVis",block);
        shader.setInt("uAtlas",0); atlas.bind(0);
        mesh(held).render(); shader.unbind(); if (culling) glEnable(GL_CULL_FACE);
    }

    public void renderShadow(TextureAtlas atlas, ItemStack held, Matrix4f palm, Matrix4f lightSpace) {
        if (held == null || held.count <= 0 || atlas == null) return;
        boolean culling = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_CULL_FACE);
        shadow.bind(); shadow.setMat4("uLightSpace",lightSpace);
        shadow.setMat4("uModel",itemPose(palm,held)); shadow.setInt("uAtlas",0);
        shadow.setFloat("uTime",0); shadow.setFloat("uWindSway",0); atlas.bind(0);
        mesh(held).render(); shadow.unbind(); if (culling) glEnable(GL_CULL_FACE);
    }

    public void destroy() {
        meshes.values().forEach(Mesh::destroy); meshes.clear(); shader.destroy(); shadow.destroy();
    }
}
