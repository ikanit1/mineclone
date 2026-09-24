import com.mineclone.render.*;
import com.mineclone.world.*;
import com.mineclone.game.WeatherDrift;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Real game terrain/sky/precipitation shaders, deterministic weather fixtures, no saves. */
public final class RenderStormPreview {
    static final int W = 640, H = 360;
    static final Vector3f EYE = new Vector3f(.5f, 52.65f, 5.5f);
    static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(65), (float) W / H, .1f, 200);
    static final Matrix4f VIEW = new Matrix4f().lookAt(EYE, new Vector3f(.5f, 51.8f, -12), new Vector3f(0, 1, 0));
    record Stage(World world, Map<Long, Mesh> meshes) {
        void destroy() { meshes.values().forEach(Mesh::destroy); }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of("out-test/previews/storms"); Files.createDirectories(output);
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3); glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(W, H, "Weather verification", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL context");
        glfwMakeContextCurrent(window); GL.createCapabilities();
        var shader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
        var atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
        var precip = new PrecipitationRenderer();
        var sky = new SkyRenderer();
        Stage desert = stage(true), plains = stage(false);
        try {
            String[] labels = {"Clear desert", "Sandstorm / 12-block visibility", "Light rain", "Storm / dense fog and spray"};
            var sheet = new BufferedImage(W * 2, H * 2, BufferedImage.TYPE_INT_RGB);
            var g = sheet.createGraphics();
            for (int i = 0; i < 4; i++) {
                BufferedImage shot = render(i < 2 ? desert : plains, shader, atlas, precip, sky,
                        i == 1 ? 1 : 0, i == 2 ? .4f : i == 3 ? 1 : 0, i == 1 || i == 3 ? 1 : 0, 8);
                ImageIO.write(shot, "png", output.resolve("weather-" + i + ".png").toFile());
                g.drawImage(shot, i % 2 * W, i / 2 * H, null);
                g.setColor(new Color(0, 0, 0, 170)); g.fillRect(i % 2 * W, i / 2 * H, W, 30);
                g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
                g.drawString(labels[i], i % 2 * W + 10, i / 2 * H + 21);
            }
            g.dispose(); ImageIO.write(sheet, "png", output.resolve("comparison.png").toFile());
            var writer = ImageIO.getImageWritersByFormatName("gif").next();
            try (var stream = ImageIO.createImageOutputStream(output.resolve("sandstorm.gif").toFile())) {
                writer.setOutput(stream); writer.prepareWriteSequence(null);
                for (int frame = 0; frame < 60; frame++) {
                    BufferedImage shot = render(desert, shader, atlas, precip, sky, 1, 0, 1, 8 + frame * .05f);
                    var meta = writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(shot), null);
                    var root = (IIOMetadataNode) meta.getAsTree(meta.getNativeMetadataFormatName());
                    ((IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0)).setAttribute("delayTime", "5");
                    if (frame == 0) {
                        var extensions = new IIOMetadataNode("ApplicationExtensions");
                        var loop = new IIOMetadataNode("ApplicationExtension");
                        loop.setAttribute("applicationID", "NETSCAPE"); loop.setAttribute("authenticationCode", "2.0");
                        loop.setUserObject(new byte[] {1, 0, 0}); extensions.appendChild(loop); root.appendChild(extensions);
                    }
                    meta.setFromTree(meta.getNativeMetadataFormatName(), root);
                    writer.writeToSequence(new javax.imageio.IIOImage(shot, null, meta), null);
                }
                writer.endWriteSequence();
            } finally { writer.dispose(); }
            // Inspect the actual dust GPU output independently from terrain and fog.
            precip.invalidate(); precip.updateField(desert.world, EYE);
            int outdoors = dustPixels(precip);
            if (outdoors < 100) throw new AssertionError("Dust pass is invisible: " + outdoors);
            for (Chunk c : desert.world.getLoadedChunks()) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                c.set(x, 76, z, BlockType.STONE);
            precip.invalidate(); precip.updateField(desert.world, EYE);
            if (dustPixels(precip) != 0) throw new AssertionError("Dust leaked below roof");
            System.out.println("PASS: 64 real-GL weather frames, visible dust grains/clouds, roof occlusion, no GL errors");
        } finally {
            desert.destroy(); plains.destroy(); shader.destroy(); atlas.destroy(); precip.destroy(); sky.destroy();
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    static Stage stage(boolean sand) {
        World w = new World(99);
        for (int cx = -2; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) {
            Chunk c = w.getChunk(cx, cz);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < Chunk.SIZE_Y; y++)
                c.set(x, y, z, y < 50 ? BlockType.STONE : y == 50 ? (sand ? BlockType.SAND : BlockType.GRASS) : BlockType.AIR);
        }
        for (int[] p : new int[][] {{-3,0,3},{3,-4,4},{-3,-11,4},{4,-20,5},{-4,-30,5}})
            for (int y = 51; y < 51 + p[2]; y++) {
                Chunk c = w.getChunk(Math.floorDiv(p[0], 16), Math.floorDiv(p[1], 16));
                c.set(Math.floorMod(p[0], 16), y, Math.floorMod(p[1], 16), sand ? BlockType.CACTUS : BlockType.WOOD);
            }
        for (Chunk c : w.getLoadedChunks()) c.computeSkyLight();
        Map<Long, Mesh> meshes = new LinkedHashMap<>();
        var mesher = new ChunkMesher(w);
        for (Chunk c : w.getLoadedChunks()) {
            MeshData[] data = mesher.buildData(c);
            if (!data[0].isEmpty()) meshes.put(World.key(c.cx, c.cz), data[0].upload());
        }
        return new Stage(w, meshes);
    }

    static BufferedImage render(Stage stage, Shader shader, TextureAtlas atlas, PrecipitationRenderer precip,
                                 SkyRenderer sky, float dust, float rain, float storm, float time) {
        float clouds = storm > 0 ? 1 : rain > 0 ? .78f : 0;
        float vis = Weather.visibility(rain, storm, false, dust);
        var palette = new SkyPalette(); palette.compute(1.57f, 1, clouds, storm, 1, 0);
        Vector3f haze = SkyPalette.linear(new Vector3f(.4f, .44f, .50f))
                .lerp(SkyPalette.linear(new Vector3f(.66f, .46f, .24f)), dust);
        float hazeMix = Math.min(.98f, (1 - vis) * 1.15f);
        Vector3f fog = new Vector3f(palette.horizon).lerp(haze, Math.min(1, hazeMix * 1.1f));
        var light = new SceneLighting();
        light.camPos.set(EYE); light.lightDir.set(palette.lightDir); light.lightColor.set(palette.lightCol);
        light.skyLight.set(palette.skyAmb); light.groundLight.set(palette.groundAmb); light.fogColor.set(fog);
        light.fogEnd = Weather.fogEnd(100, vis, storm, dust); light.fogStart = light.fogEnd * (.10f + .42f * vis);
        light.linearOut = 0; light.time = time;
        glViewport(0, 0, W, H); glEnable(GL_DEPTH_TEST); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        var dome = new SkyRenderer.Dome(); dome.zenith.set(palette.zenith); dome.horizon.set(palette.horizon);
        dome.ground.set(palette.ground); dome.sunGlow.set(palette.sunGlow); dome.lightDir.set(palette.lightDir);
        dome.cloudiness = clouds; dome.time = time; dome.haze.set(fog); dome.hazeMix = hazeMix; dome.linearOut = 0;
        sky.renderDome(new Matrix4f(PROJ).mul(VIEW).invert(), dome);
        glEnable(GL_DEPTH_TEST); glDepthMask(true); glEnable(GL_CULL_FACE);
        shader.bind(); light.apply(shader); shader.setMat4("uProjection", PROJ); shader.setMat4("uView", VIEW);
        shader.setInt("uAtlas", 0); shader.setFloat("uWindSway", 0); atlas.bind(0);
        for (var e : stage.meshes.entrySet()) {
            shader.setMat4("uModel", new Matrix4f().translation((int) (e.getKey() >> 32) * 16, 0, (int) (long) e.getKey() * 16));
            e.getValue().render();
        }
        shader.unbind();
        precip.invalidate(); precip.updateField(stage.world, EYE);
        var drift = drift(time);
        Vector3f flake = PrecipitationRenderer.flakeColor(palette.skyAmb, palette.lightCol, fog);
        precip.render(PROJ, VIEW, EYE, time, 4.5f, 1.4f, drift, 0, rain, storm, dust,
                flake, PrecipitationRenderer.dropColor(flake), new Vector3f(haze).mul(1.5f), 0);
        return pixels();
    }

    static WeatherDrift drift(float t) {
        var d = new WeatherDrift();
        for (int i = 0; i < Math.round(t * 60); i++) d.advance(1f / 60, 4.5f, 1.4f, 1);
        return d;
    }
    static int dustPixels(PrecipitationRenderer precip) {
        glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        precip.render(PROJ, VIEW, EYE, 8, 4.5f, 1.4f, drift(8), 0, 0, 1, 1,
                new Vector3f(1), new Vector3f(1), new Vector3f(1), 0);
        BufferedImage image = pixels(); int changed = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((image.getRGB(x, y) & 0xffffff) != 0) changed++;
        return changed;
    }
    static BufferedImage pixels() {
        var pixels = BufferUtils.createByteBuffer(W * H * 4); glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("OpenGL error");
        var image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int at = (y * W + x) * 4;
            image.setRGB(x, H - y - 1, (pixels.get(at) & 255) << 16 | (pixels.get(at + 1) & 255) << 8 | (pixels.get(at + 2) & 255));
        }
        return image;
    }
}
