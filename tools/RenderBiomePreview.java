import com.mineclone.core.Window;
import com.mineclone.render.*;
import com.mineclone.world.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

/** Actual generated terrain and falling-block renderer, in a hidden GL context.
 * Run after tests: java -cp "out-test;libs/*" tools/RenderBiomePreview.java
 */
public final class RenderBiomePreview {
    private static final int W = 800, H = 500;
    public static void main(String[] args) throws Exception {
        Window window = new Window("Biome regression previews", W, H, false);
        window.init();
        try {
            TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
            Shader shader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
            FallingBlockRenderer falling = new FallingBlockRenderer();
            Files.createDirectories(Path.of("out-test/previews"));
            for (Biome biome : new Biome[]{Biome.TAIGA, Biome.SWAMP, Biome.SAVANNA,
                    Biome.BADLANDS, Biome.ALPINE, Biome.VOLCANIC}) {
                World world = new World(7381);
                int[] point = find(world, biome);
                int cx = Math.floorDiv(point[0], 16), cz = Math.floorDiv(point[1], 16);
                int height = world.terrainHeight(point[0], point[1]);
                for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) world.getChunk(cx + dx, cz + dz);
                List<Mesh> meshes = new ArrayList<>(); List<Chunk> chunks = new ArrayList<>();
                ChunkMesher mesher = new ChunkMesher(world);
                for (Chunk chunk : world.getLoadedChunks()) {
                    MeshData[] data = mesher.buildData(chunk);
                    for (MeshData d : data) if (!d.isEmpty()) { meshes.add(d.upload()); chunks.add(chunk); }
                }
                SceneLighting light = new SceneLighting();
                light.linearOut = 0; light.fogStart = 70; light.fogEnd = 120;
                light.lightDir.set(-0.5f, 1, 0.4f).normalize();
                light.camPos.set(point[0] + 30, height + 30, point[1] + 38);
                Matrix4f projection = new Matrix4f().perspective(1.05f, W / (float) H, 0.1f, 250);
                Matrix4f view = new Matrix4f().lookAt(light.camPos.x, light.camPos.y, light.camPos.z,
                        point[0], height + 1, point[1], 0, 1, 0);
                glViewport(0, 0, W, H); glClearColor(0.64f, 0.78f, 0.9f, 1);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                atlas.bind(0); shader.bind(); light.apply(shader);
                shader.setInt("uAtlas", 0); shader.setInt("uBlockArray", TextureAtlas.ARRAY_UNIT);
                shader.setMat4("uProjection", projection); shader.setMat4("uView", view);
                for (int i = 0; i < meshes.size(); i++) {
                    Chunk c = chunks.get(i);
                    shader.setMat4("uModel", new Matrix4f().translation(c.cx * 16, 0, c.cz * 16));
                    meshes.get(i).render();
                }
                shader.unbind();
                screenshot(biome.name().toLowerCase(Locale.ROOT));
                // Exercise real animated cubes with the new materials and collision sweeps.
                world.setBlock(point[0], Math.min(125, height + 15), point[1], BlockType.RED_SAND);
                for (int frame = 0; frame < 120; frame++) {
                    world.falling.update(1f / 60);
                    falling.render(projection, view, world, atlas, light, 1);
                    int error = glGetError();
                    if (error != GL_NO_ERROR) throw new AssertionError("GL error " + error + " in " + biome);
                }
                for (Mesh mesh : meshes) mesh.destroy();
                System.out.println(biome + " seed=7381 x=" + point[0] + " z=" + point[1] + " height=" + height);
            }
            falling.destroy(); shader.destroy(); atlas.destroy();
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("GL cleanup error");
            System.out.println("Six biome previews and falling-block GL checks passed");
        } finally { window.destroy(); }
    }
    private static int[] find(World w, Biome biome) {
        for (int x = -4000; x <= 4000; x += 32) for (int z = -4000; z <= 4000; z += 32) {
            if (w.biomes.biomeAt(x, z) != biome) continue;
            if (w.biomes.biomeAt(x - 24, z) != biome || w.biomes.biomeAt(x + 24, z) != biome
                    || w.biomes.biomeAt(x, z - 24) != biome || w.biomes.biomeAt(x, z + 24) != biome) continue;
            if (w.terrainHeight(x, z) >= World.SEA_LEVEL + (biome == Biome.SWAMP ? 0 : 3)) return new int[]{x, z};
        }
        throw new AssertionError("No sizable " + biome);
    }
    private static void screenshot(String name) throws Exception {
        glFinish();
        var bytes = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = ((H - y - 1) * W + x) * 4;
            image.setRGB(x, y, ((bytes.get(i) & 255) << 16) | ((bytes.get(i + 1) & 255) << 8) | (bytes.get(i + 2) & 255));
        }
        ImageIO.write(image, "png", Path.of("out-test/previews/biome-" + name + ".png").toFile());
    }
}
