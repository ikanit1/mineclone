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
        SkyRenderer sky = new SkyRenderer();
        boolean skyOnly = args.length > 0 && args[0].equals("sky");
        HeldItemRenderer hand = new HeldItemRenderer();
        TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
        World world = new World(1L);
        var mobs = new ArrayList<Mob>();
        MobType[] types = MobType.values();
        for (int index = 0; index < types.length; index++) {
            // Ряд по центру кадра: новые виды дописываются в конец перечисления
            // и иначе уезжали бы за правый край.
            Mob m = new Mob(types[index], (index - (types.length - 1) * 0.5f) * 1.4f, 0, 0,
                    new java.util.Random(4));
            m.yaw = -0.35f;
            m.onGround = true;
            m.walkAmount = 1f;
            mobs.add(m);
        }
        Files.createDirectories(Path.of("out-test/previews"));
        int frames = 0;
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
                    m.onGround = m.type != MobType.CHICKEN && m.type != MobType.BIRD;
                    m.attackSwing = frame == 1 ? Mob.ATTACK_SWING_TIME * 0.5f : 0f;
                }
                if (skyOnly) {
                    float daylight = frame % 2 == 0 ? 1f : 0f;
                    glClearColor(daylight > 0f ? 0.48f : 0.025f,
                            daylight > 0f ? 0.70f : 0.035f, daylight > 0f ? 1f : 0.065f, 1f);
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    sky.render(new Matrix4f().perspective((float) java.lang.Math.toRadians(70),
                                    (float) width / height, 0.05f, 3000f),
                            new Matrix4f().lookAt(0, 70, 0, 0, 71, -1, 0, 1, 0),
                            new Vector3f(0, 70, 0), daylight > 0f ? 0.7f : 3.84f,
                            daylight, frame * 20f, 0f);
                }
                float distance = width == 1280 ? -8f : -15f;
                if (!skyOnly) {
                // Свет как у игры, но без HDR-поста: шейдер тонемапит сам
                // (uLinearOut = 0), иначе снимок ушёл бы в линейное пространство.
                SceneLighting lighting = lighting(distance);
                renderer.render(new Matrix4f().perspective((float) java.lang.Math.toRadians(50),
                        (float) width / height, 0.05f, 100f),
                        new Matrix4f().lookAt(0, 2.5f, distance, 0, 0.7f, 0, 0, 1, 0),
                        mobs, world, 1f, lighting);
                // Третий кадр — рука с инструментом: поза у него своя, и
                // ломалась она молча, пока превью не собиралось.
                hand.render(atlas, frame == 2 ? BlockType.GRASS : null,
                        frame == 3 ? com.mineclone.world.ToolType.IRON_PICKAXE : null,
                        (float) width / height, 70f, 1f,
                        frame == 1 ? 0.65f : (frame == 3 ? 0.35f : 0f), 0f,
                        false, false, 1f, 1f, 1f, 0f, 0f);
                }
                BufferedImage image = grab(width, height);
                check(image, previous, height * 0.6f, "Blank or static mob render");
                previous = image;
                ImageIO.write(image, "png", Path.of("out-test/previews/" + (skyOnly ? "sky-" : "mobs-") + width + "-" + frame + ".png").toFile());
                if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("OpenGL error");
                frames++;
            }
        }
        if (!skyOnly)
            frames += renderWildlife(renderer, world);
        hand.destroy();
        sky.destroy();
        renderer.destroy();
        glfwDestroyWindow(window);
        glfwTerminate();
        System.out.println("Rendered " + frames + " OpenGL frames including side, rear and close-up views; no GL errors.");
    }

    /**
     * Крупный план зверья и поз, которые в общем ряду не разглядеть: кролик,
     * волк и птица мелкие, а ярость и падение трупа — это состояния, а не виды.
     */
    private static int renderWildlife(MobRenderer renderer, World world) throws Exception {
        int width = 1280, height = 720;
        Mob rabbit = new Mob(MobType.RABBIT, -1.3f, 0, 0, new java.util.Random(5));
        Mob wolf = new Mob(MobType.WOLF, 0f, 0, 0.2f, new java.util.Random(6));
        Mob bird = new Mob(MobType.BIRD, 1.2f, 0, 0, new java.util.Random(7));
        List<Mob> wild = List.of(rabbit, wolf, bird);
        Mob angry = new Mob(MobType.ZOMBIE, -1.2f, 0, 0, new java.util.Random(8));
        Mob corpse = new Mob(MobType.ZOMBIE, 1.3f, 0, 0, new java.util.Random(9));
        List<Mob> states = List.of(angry, corpse);
        BufferedImage previous = null;
        for (int frame = 0; frame < 4; frame++) {
            glViewport(0, 0, width, height);
            glClearColor(0.40f, 0.61f, 0.72f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glEnable(GL_DEPTH_TEST);
            float yaw = frame == 3 ? 2.6f : -0.55f;
            for (Mob m : wild) {
                m.yaw = yaw;
                m.onGround = !(frame >= 1 && m.type == MobType.BIRD);
                m.walkAmount = frame == 0 ? 0f : 1f;
                m.animationTime = 0.3f + frame * 0.37f;
                m.walkedDistance = 0.2f + frame * 0.26f;
                m.position.y = m.type == MobType.BIRD && !m.onGround ? 0.6f : 0f;
            }
            List<Mob> shown = frame == 2 ? states : wild;
            float camY = frame == 2 ? 1.6f : 0.9f, lookY = frame == 2 ? 0.8f : 0.3f;
            float distance = frame == 2 ? -5.2f : -3.6f;
            if (frame == 2) {
                angry.yaw = -0.4f;
                angry.enraged = true;
                // sin(t * 9) = 1: пик пульса, иначе кадр мог попасть в нейтральную фазу.
                angry.animationTime = (float) (java.lang.Math.PI / 2 / 9);
                corpse.yaw = 0.3f;
                corpse.dead = true;
                corpse.deathTimer = Mob.DEATH_TIME * 0.4f;
                corpse.topple = 1.2f;
                corpse.deathAxisX = 0f;
                corpse.deathAxisZ = 1f;
            }
            renderer.render(new Matrix4f().perspective((float) java.lang.Math.toRadians(50),
                    (float) width / height, 0.05f, 100f),
                    new Matrix4f().lookAt(0, camY, distance, 0, lookY, 0, 0, 1, 0),
                    shown, world, 1f, lighting(distance));
            BufferedImage image = grab(width, height);
            check(image, previous, height, "Blank or static wildlife render");
            previous = image;
            ImageIO.write(image, "png", Path.of("out-test/previews/wild-" + frame + ".png").toFile());
            if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("OpenGL error");
        }
        return 4;
    }

    private static SceneLighting lighting(float distance) {
        SceneLighting lighting = SceneLighting.firstPerson(1f, 1f);
        lighting.camPos.set(0, 2.5f, distance);
        lighting.fogColor.set(0.4f, 0.61f, 0.72f);
        lighting.fogStart = 50f;
        lighting.fogEnd = 100f;
        lighting.linearOut = 0f;
        return lighting;
    }

    private static BufferedImage grab(int width, int height) {
        var pixels = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int p = (y * width + x) * 4;
                image.setRGB(x, height - y - 1, 0xff000000 | (pixels.get(p) & 255) << 16
                        | (pixels.get(p + 1) & 255) << 8 | (pixels.get(p + 2) & 255));
            }
        return image;
    }

    private static void check(BufferedImage image, BufferedImage previous, float rows, String error) {
        int visible = 0, changed = 0;
        int background = image.getRGB(0, 0);
        for (int y = 0; y < (int) rows; y++)
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != background) visible++;
                if (previous != null && image.getRGB(x, y) != previous.getRGB(x, y)) changed++;
            }
        if (visible < 1000 || previous != null && changed < 100)
            throw new IllegalStateException(error);
    }
}
