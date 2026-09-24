import com.mineclone.net.RemotePlayer;
import com.mineclone.render.*;
import com.mineclone.world.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Real-GL review of the live animation, colour, equipment and depth passes. No game saves or network. */
public final class RenderPlayerMotion {
    static final int W = 280, H = 320;
    static final String[] LABELS = {"Idle / breathing", "12 Hz network / stop", "Sprint", "Backpedal",
            "Jump / landing", "Swim", "Creative flight", "Attack / held tool"};

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "out-test/previews/player-motion" : args[0]);
        Files.createDirectories(output);
        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(W, H, "Player motion review", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL window");
        glfwMakeContextCurrent(window); GL.createCapabilities();
        var renderer = new PlayerRenderer();
        var atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
        var shadow = new Shader(Shaders.SHADOW_MOB_VERTEX, Shaders.SHADOW_FRAGMENT);
        var floor = new Shader("#version 330 core\nlayout(location=0) in vec3 p; uniform mat4 pv; out vec3 w; void main(){w=p;gl_Position=pv*vec4(p,1);}",
                "#version 330 core\nin vec3 w;out vec4 c;void main(){float k=mod(floor(w.x*4)+floor(w.z*4),2);c=vec4(vec3(.17,.22,.25)+k*.025,1);}");
        int vao = glGenVertexArrays(), vbo = glGenBuffers();
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, new float[] {-4,-.002f,-4,4,-.002f,-4,4,-.002f,4,-4,-.002f,-4,4,-.002f,4,-4,-.002f,4}, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0); glEnableVertexAttribArray(0);
        var animations = new PlayerAnimation[8];
        for (int i = 0; i < 8; i++) {
            animations[i] = new PlayerAnimation();
            animations[i].reset(new Vector3f(), 0, true, false, false);
        }
        var remote = new RemotePlayer(2, "preview");
        remote.accept(0, 0, 0, 0, 0, RemotePlayer.F_ON_GROUND);
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        var held = ItemStack.of("iron_pickaxe");
        try (var stream = ImageIO.createImageOutputStream(output.resolve("player-motion.gif").toFile())) {
            writer.setOutput(stream); writer.prepareWriteSequence(null);
            for (int tick = 1; tick <= 300; tick++) {
                float t = tick / 60f;
                float jumpTime = (t - 1) % 2;
                float height = t < 1 ? 0 : Math.max(0, 5 * jumpTime - 10 * jumpTime * jumpTime);
                for (int i = 0; i < 8; i++) {
                    float distance = i == 2 ? t * 6.24f : i == 3 ? -t * 3 : 0;
                    if (i == 7 && tick % 48 == 1) animations[i].startSwing();
                    animations[i].update(1f / 60, new Vector3f(0, i == 4 ? height : 0, -distance),
                            0, i != 4 || height == 0, i == 5 && t > .4f, i == 6 && t > .4f, i == 2);
                }
                if (tick % 5 == 0) remote.accept(0, 0, -Math.min(t, 3.5f) * 4.8f, 0, 0, RemotePlayer.F_ON_GROUND);
                remote.update(1f / 60);
                if (tick % 3 != 0) continue;
                var sheet = new BufferedImage(W * 4, H * 2, BufferedImage.TYPE_INT_RGB);
                var g = sheet.createGraphics();
                for (int i = 0; i < 8; i++) {
                    var pose = i == 1 ? remote.animation.pose() : animations[i].pose();
                    float y = i == 4 ? height : i == 6 ? .20f : 0;
                    g.drawImage(render(renderer, atlas, shadow, floor, vao, pose, y, i == 7 ? held : null), i % 4 * W, i / 4 * H, null);
                    g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
                    g.drawString(LABELS[i], i % 4 * W + 10, i / 4 * H + 23);
                }
                g.dispose();
                if (tick == 75 || tick == 135 || tick == 270)
                    ImageIO.write(sheet, "png", output.resolve("poses-" + tick + ".png").toFile());
                var meta = writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(sheet), null);
                var root = (IIOMetadataNode) meta.getAsTree(meta.getNativeMetadataFormatName());
                var control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("delayTime", "5");
                if (tick == 3) {
                    var extensions = new IIOMetadataNode("ApplicationExtensions");
                    var loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE"); loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[] {1, 0, 0}); extensions.appendChild(loop); root.appendChild(extensions);
                }
                meta.setFromTree(meta.getNativeMetadataFormatName(), root);
                writer.writeToSequence(new javax.imageio.IIOImage(sheet, null, meta), null);
            }
            writer.endWriteSequence();
            System.out.println("PASS: 800 player colour and shadow renders, 100 animation frames, 8 states; no GL errors");
        } finally {
            writer.dispose(); renderer.destroy(); shadow.destroy(); floor.destroy(); atlas.destroy();
            glDeleteBuffers(vbo); glDeleteVertexArrays(vao); glfwDestroyWindow(window); glfwTerminate();
        }
    }

    static BufferedImage render(PlayerRenderer renderer, TextureAtlas atlas, Shader shadow, Shader floor,
                                 int vao, PlayerAnimation.Pose pose, float y, ItemStack held) {
        var position = new Vector3f(0, y, 0);
        var proj = new Matrix4f().perspective((float) Math.toRadians(43), (float) W / H, .05f, 100);
        var view = new Matrix4f().lookAt(0, 1.7f, -4.6f, 0, 1.05f, 0, 0, 1, 0);
        var pv = new Matrix4f(proj).mul(view);
        glViewport(0, 0, W, H); glEnable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
        glClear(GL_DEPTH_BUFFER_BIT);
        renderer.renderShadow(shadow, pv, position, .55f, .55f, 0, pose, atlas, held);
        var depth = BufferUtils.createFloatBuffer(W * H);
        glReadPixels(0, 0, W, H, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        int shadowPixels = 0;
        for (int i = 0; i < depth.capacity(); i++) if (depth.get(i) < .99999f) shadowPixels++;
        if (shadowPixels < 100) throw new IllegalStateException("Empty shadow pass");
        glClearColor(.105f, .15f, .19f, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_CULL_FACE);
        floor.bind(); floor.setMat4("pv", pv); glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES, 0, 6); floor.unbind();
        var lighting = SceneLighting.firstPerson(1, 1);
        lighting.camPos.set(0, 1.7f, -4.6f); lighting.fogStart = 50; lighting.fogEnd = 100; lighting.linearOut = 0;
        renderer.render(proj, view, position, .55f, .55f, 0, pose, lighting, 1, 0, atlas, held);
        var pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("OpenGL error");
        var image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < H; row++) for (int col = 0; col < W; col++) {
            int at = (row * W + col) * 4;
            image.setRGB(col, H - row - 1, (pixels.get(at) & 255) << 16 | (pixels.get(at + 1) & 255) << 8 | (pixels.get(at + 2) & 255));
        }
        return image;
    }
}
