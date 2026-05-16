package com.mineclone.game;

import com.mineclone.render.Camera;
import com.mineclone.render.Mesh;
import com.mineclone.render.Shader;
import com.mineclone.render.TextureAtlas;
import com.mineclone.save.SaveFormat;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.ChunkMesher;
import com.mineclone.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Self-contained mini-world used as the main-menu backdrop. Owns its own
 * World, ChunkLoader, mesh dictionary, and an orbit Camera. Does NOT touch
 * the play world — so loading saves/&lt;world&gt;/ and re-entering the menu
 * both leave this backdrop intact.
 *
 * Renders with the standard chunk shader at a fixed daylight=1.0 so the
 * backdrop always looks sunny regardless of in-world time.
 */
public final class MenuBackground {
    private static final int RADIUS = 3;             // chunks around orbit center
    private static final float ORBIT_RADIUS = 28f;   // world units
    private static final float ORBIT_SPEED  = 0.05f; // radians / sec
    private static final float CAM_OFFSET_Y = 14f;   // above sampled terrain
    private static final float CAM_PITCH    = 0.35f; // rad, looking down

    /** Orbit center in world coords. Placed mid-chunk (0,0) so 3-chunk radius covers it. */
    private static final float CENTER_X = Chunk.SIZE_X * 0.5f;
    private static final float CENTER_Z = Chunk.SIZE_Z * 0.5f;

    private final World world;
    private final ChunkMesher mesher;
    private final ChunkLoader loader;
    private final Map<Long, Mesh> meshes = new HashMap<>();
    private final Map<Long, Mesh> waterMeshes = new HashMap<>();
    private final Camera camera = new Camera();
    private float angle = 0f;

    public MenuBackground(com.mineclone.save.SaveManager save) {
        this.world = new World(SaveFormat.MENU_SEED);
        this.mesher = new ChunkMesher(world);
        // Sentinel world id so any rogue save-on-modified call cannot collide
        // with the player's "world" directory. The menu never edits blocks,
        // so this directory should never be created in practice.
        this.loader = new ChunkLoader(world, mesher, save, "__menu__");
    }

    /** Schedule generation + advance camera. Call once per frame in MENU state. */
    public void update(float dt) {
        angle += dt * ORBIT_SPEED;

        // Preload 3x3 around the orbit center if missing — synchronously so the
        // very first menu frame already shows terrain instead of empty sky.
        int pcx = (int) Math.floor(CENTER_X / Chunk.SIZE_X);
        int pcz = (int) Math.floor(CENTER_Z / Chunk.SIZE_Z);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (world.getChunkIfExists(pcx + dx, pcz + dz) == null)
                    world.getChunk(pcx + dx, pcz + dz);  // forces synchronous generation

        loader.ensureRadius(pcx, pcz, RADIUS);
        loader.drainLightFlood(2);
        for (ChunkLoader.Ready r : loader.drainReady(3)) {
            Mesh old = meshes.remove(r.key);
            if (old != null) old.destroy();
            Mesh oldW = waterMeshes.remove(r.key);
            if (oldW != null) oldW.destroy();
            if (!r.data[0].isEmpty()) meshes.put(r.key, r.data[0].upload());
            if (!r.data[1].isEmpty()) waterMeshes.put(r.key, r.data[1].upload());
        }

        // Camera: orbit around CENTER_X,CENTER_Z at terrain-top + offset.
        float terrainY = sampleTerrainTop();
        float cx = CENTER_X + (float) Math.cos(angle) * ORBIT_RADIUS;
        float cz = CENTER_Z + (float) Math.sin(angle) * ORBIT_RADIUS;
        camera.position.set(cx, terrainY + CAM_OFFSET_Y, cz);
        camera.yaw   = (float) Math.atan2(CENTER_X - cx, -(CENTER_Z - cz));  // aim at center
        camera.pitch = CAM_PITCH;
    }

    /** Highest solid block at the orbit center column. Falls back to SEA_LEVEL+8 if no chunk. */
    private float sampleTerrainTop() {
        int bx = (int) Math.floor(CENTER_X);
        int bz = (int) Math.floor(CENTER_Z);
        for (int y = Chunk.SIZE_Y - 1; y >= 0; y--) {
            if (world.getBlock(bx, y, bz).solid)
                return y + 1f;
        }
        return World.SEA_LEVEL + 8f;
    }

    /** Render opaque chunks of the menu world with the supplied shader. */
    public void render(Shader chunkShader, TextureAtlas atlas, float aspect, int fovDeg) {
        Matrix4f proj = camera.getProjection(aspect, fovDeg, 0.1f, 600f);
        Matrix4f view = camera.getView();

        chunkShader.bind();
        chunkShader.setMat4("uProjection", proj);
        chunkShader.setMat4("uView", view);
        chunkShader.setInt("uAtlas", 0);
        chunkShader.setVec3("uFogColor", new Vector3f(0.55f, 0.75f, 0.95f));
        chunkShader.setFloat("uFogStart", RADIUS * Chunk.SIZE_X * 0.5f);
        chunkShader.setFloat("uFogEnd",   RADIUS * Chunk.SIZE_X * 1.0f);
        chunkShader.setFloat("uAmbient",   0.22f);
        chunkShader.setFloat("uDaylight",  1.0f);
        chunkShader.setFloat("uBrightness", 1.0f);
        chunkShader.setFloat("uTime",      angle);  // any monotonic value drives water_flow animation
        atlas.bind(0);

        for (Map.Entry<Long, Mesh> e : meshes.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 32);
            int cz = (int) (k & 0xFFFFFFFFL);
            Matrix4f model = new Matrix4f().translate(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z);
            chunkShader.setMat4("uModel", model);
            e.getValue().render();
        }
        chunkShader.unbind();
        // Water pass skipped — menu backdrop rarely has water at orbit center.
    }

    public void destroy() {
        loader.shutdown();
        for (Mesh m : meshes.values()) m.destroy();
        meshes.clear();
        for (Mesh m : waterMeshes.values()) m.destroy();
        waterMeshes.clear();
    }
}
