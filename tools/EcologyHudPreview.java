import com.mineclone.core.AppPaths;
import com.mineclone.game.Hud;
import com.mineclone.render.Font;
import com.mineclone.render.TextRenderer;
import com.mineclone.render.TextureAtlas;
import com.mineclone.render.UiRenderer;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.ecology.*;
import com.mineclone.world.World;
import com.mineclone.world.entity.MobType;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Actual Hud/GL clipping previews using restored ecology diagnostics; separate from Game acceptance. */
public final class EcologyHudPreview {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "out-test/ecology-hud" : args[0]).toAbsolutePath();
        Files.createDirectories(output);
        Ecology ecology = fixture();
        Map<String, byte[]> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        ecology.writeSections(before);
        String[] observed = ecology.info(8.5f, 8.5f), unobserved = ecology.info(800, 800);
        ecology.writeSections(after);
        if (!before.keySet().equals(after.keySet()) || before.entrySet().stream()
                .anyMatch(entry -> !Arrays.equals(entry.getValue(), after.get(entry.getKey()))))
            throw new AssertionError("Diagnostic rendering changed saved state; rebuild current classes");
        Files.write(output.resolve("observed.txt"), List.of(observed));
        Files.write(output.resolve("unobserved.txt"), List.of(unobserved));
        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        try {
            for (int[] size : new int[][] {{1280,720}, {640,360}, {320,180}})
                render(output, size[0], size[1], observed, unobserved);
        } finally { glfwTerminate(); }
        System.out.println("ECOLOGY_HUD PASS frames=6 resolutions=3 noGlErrors=true output=" + output);
    }

    private static Ecology fixture() throws Exception {
        EcologyRegion region = new EcologyRegion(0, 0, 7);
        for (var definition : SpeciesTable.get().definitions()) {
            region.capacity(definition.id(), 42);
            region.population(definition.id(), 28);
            region.trend(definition.id(), new int[] {-2, 4, 1});
        }
        var nests = new ArrayList<byte[]>();
        for (int i = 0; i < 4; i++)
            nests.add(new Lair(16 + i * 48, 40, 16, 4, LairTable.get().byType(MobType.SPIDER)).encode());
        region.lairs(nests);
        byte[] scar = ByteBuffer.allocate(32).putInt(BurnScars.MAGIC).put((byte) 1)
                .putInt(1).putInt(1).putLong(7).put((byte) 1)
                .putShort((short) (0x8000 | (64 * 256 + 17))).putLong(0).array();
        region.scars(List.of(scar));
        WorldClock clock = new WorldClock(Math.PI * 2 * 7 + .5);
        Ecology ecology = new Ecology(new World(20261002L), clock, new EntityStore(), new Participants());
        ecology.restore(Map.of(Ecology.SAVE_SECTION,
                EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of()))),
                bytes -> { throw new AssertionError("Prepared diagnostic fixture must decode"); });
        return ecology;
    }

    private static void render(Path output, int width, int height, String[] observed, String[] unobserved) throws Exception {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(width, height, "Ecology HUD preview", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        TextureAtlas atlas = null;
        Font font = null, small = null;
        UiRenderer ui = null;
        try {
            atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
            font = new Font(AppPaths.path("assets/minecraft.ttf"), 22f);
            small = new Font(AppPaths.path("assets/minecraft.ttf"), 14f);
            ui = new UiRenderer();
            ui.setAtlas(atlas.getTextureId());
            ui.registerFonts(font, small);
            Hud hud = new Hud(font, small, new TextRenderer(ui), ui, atlas);
            for (int state = 0; state < 2; state++) {
                glViewport(0, 0, width, height);
                glDisable(GL_DEPTH_TEST);
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glClearColor(.25f, .36f, .43f, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                hud.drawEcology(width, height, state == 0 ? observed : unobserved);
                ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                int changed = 0, reference = 0;
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int p = (y * width + x) * 4;
                    int color = (pixels.get(p) & 255) << 16 | (pixels.get(p+1) & 255) << 8 | pixels.get(p+2) & 255;
                    if (p == 0) reference = color;
                    if (color != reference) changed++;
                    image.setRGB(x, height - y - 1, color);
                }
                if (changed < 100 || glGetError() != GL_NO_ERROR) throw new AssertionError("Invalid HUD frame");
                Path file = output.resolve("ecology-" + (state == 0 ? "known-" : "unknown-") + width + "x" + height + ".png");
                ImageIO.write(image, "png", file.toFile());
                System.out.println("ECOLOGY_HUD rendered " + file);
            }
        } finally {
            if (ui != null) ui.destroy();
            if (small != null) small.destroy();
            if (font != null) font.destroy();
            if (atlas != null) atlas.destroy();
            glfwDestroyWindow(window);
            GL.setCapabilities(null);
        }
    }
}
