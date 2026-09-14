import com.mineclone.render.*;
import com.mineclone.world.*;
import com.mineclone.world.entity.*;
import org.joml.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Real OpenGL snapshots, independent of saved worlds and interactive input. */
public class RenderMobPreview {
    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(1280, 960, "Render verification", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        MobRenderer renderer = new MobRenderer();
        HeldItemRenderer hand = new HeldItemRenderer();
        TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
        World world = new World(1L);
        var mobs = new ArrayList<Mob>();
        int index = 0;
        for (MobType type : MobType.values()) {
            Mob m = new Mob(type, (index++ - 2) * 1.5f, 0, 0, new java.util.Random(4));
            m.yaw = -0.35f;
            m.onGround = true;
            m.walkAmount = 1f;
            mobs.add(m);
        }
        Files.createDirectories(Path.of("out-test/previews"));
        for (int width : new int[] {1280, 720}) {
            int height = width == 1280 ? 720 : 960;
            BufferedImage previous = null;
            for (int frame = 0; frame < 6; frame++) {
                glViewport(0, 0, width, height);
                glClearColor(0.40f, 0.61f, 0.72f, 1f);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glEnable(GL_DEPTH_TEST);
                for (Mob m : mobs) {
                    m.yaw = frame < 3 ? -0.35f : (frame - 2) * 1.5708f;
                    m.animationTime = frame * 0.22f;
                    m.walkedDistance = frame * 0.22f;
                    m.onGround = m.type != MobType.CHICKEN;
                    m.attackSwing = frame == 1 ? Mob.ATTACK_SWING_TIME * 0.5f : 0f;
                }
                float distance = width == 1280 ? -7f : -13f;
                renderer.render(new Matrix4f().perspective((float) java.lang.Math.toRadians(50),
                        (float) width / height, 0.05f, 100f),
                        new Matrix4f().lookAt(0, 2.5f, distance, 0, 0.7f, 0, 0, 1, 0),
                        mobs, world, 1, 1, 1, new Vector3f(0.4f, 0.61f, 0.72f), 50, 100);
                hand.render(atlas, frame == 2 ? BlockType.GRASS : null, (float) width / height,
                        70, 1, frame == 1 ? 0.65f : 0, 0, false, false, 1, 1, 1, 0);
                var pixels = BufferUtils.createByteBuffer(width * height * 4);
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++) {
                        int p = (y * width + x) * 4;
                        image.setRGB(x, height - y - 1, 0xff000000 | (pixels.get(p) & 255) << 16
                                | (pixels.get(p + 1) & 255) << 8 | (pixels.get(p + 2) & 255));
                    }
                int visible = 0, changed = 0;
                int background = image.getRGB(0, 0);
                for (int y = 0; y < height * 0.6f; y++)
                    for (int x = 0; x < width; x++) {
                        if (image.getRGB(x, y) != background) visible++;
                        if (previous != null && image.getRGB(x, y) != previous.getRGB(x, y)) changed++;
                    }
                if (visible < 1000 || previous != null && changed < 100)
                    throw new IllegalStateException("Blank or static mob render");
                previous = image;
                ImageIO.write(image, "png", Path.of("out-test/previews/mobs-" + width + "-" + frame + ".png").toFile());
                if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("OpenGL error");
            }
        }
        hand.destroy();
        renderer.destroy();
        glfwDestroyWindow(window);
        glfwTerminate();
        System.out.println("Rendered 12 OpenGL frames including side and rear views; no GL errors.");
    }
}
