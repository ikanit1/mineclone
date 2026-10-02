import com.mineclone.core.AppPaths;
import com.mineclone.core.Window;
import com.mineclone.render.*;
import com.mineclone.save.*;
import com.mineclone.ui.*;
import com.mineclone.world.gen.*;
import org.lwjgl.BufferUtils;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Arrays;
import static org.lwjgl.opengl.GL33.*;

/** Real menu rendering and button input for explicit old-world generation choices. */
public final class WorldGenOpeningSmoke {
    private static UiRenderer ui;
    private static MenuTheme theme;
    private static int width = 1280, height = 720;
    public static void main(String[] args) throws Exception {
        Path out = Path.of("out-test/worldgen-opening"); Files.createDirectories(out);
        Path root = Files.createTempDirectory(out, "saves-");
        SaveManager save = new SaveManager(root.toFile());
        for (String id : new String[] {"keep", "upgrade", "error"}) {
            Path source = Path.of("src/test/resources/fixtures/saves/alpha-small");
            try (var files = Files.walk(source)) {
                for (Path p : files.toList()) {
                    Path target = root.resolve(id).resolve(source.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target);
                }
            }
        }
        Window window = new Window("World generation opening smoke", width, height, false); window.init();
        TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
        Font font = new Font(AppPaths.path("assets/minecraft.ttf"), 22f);
        Font small = new Font(AppPaths.path("assets/minecraft.ttf"), 14f);
        ui = new UiRenderer(); ui.setAtlas(atlas.getTextureId()); ui.registerFonts(font, small);
        TextRenderer text = new TextRenderer(ui);
        theme = new MenuTheme(ui, text, font, small, atlas);
        try {
            byte[] oldBytes = Files.readAllBytes(root.resolve("keep/level.dat"));
            Screen keep = WorldGenUpgradeScreen.offer(save, MenuAction.play("keep")).screen;
            for (int w : new int[] {1280, 960, 640}) {
                width = w; height = w == 640 ? 480 : 720;
                frame(keep, UiInput.NONE); screenshot(out.resolve("offer-" + width + ".png"));
            }
            width = 1280; height = 720;
            MenuAction unchanged = click(keep, 800, 460);
            require(unchanged.is(MenuAction.Kind.PLAY_WORLD) && unchanged.worldgenChecked, "keep button");
            require(Arrays.equals(oldBytes, Files.readAllBytes(root.resolve("keep/level.dat"))), "keep wrote level");
            Screen upgrading = WorldGenUpgradeScreen.offer(save, MenuAction.play("upgrade")).screen;
            click(upgrading, 450, 460);
            require(upgrading.escape() == MenuAction.NONE, "Esc interrupted upgrade");
            save.flushAndAwait();
            require(frame(upgrading, UiInput.NONE).is(MenuAction.Kind.PLAY_WORLD), "upgrade button did not open world");
            require(WorldGenSettings.decode(save.loadLevel("upgrade").extraSections.get(WorldGenSettings.SAVE_SECTION))
                    .version() == WorldGenVersion.V2, "upgrade not durable");
            Files.write(root.resolve("error/backups"), new byte[] {1});
            Screen failing = WorldGenUpgradeScreen.offer(save, MenuAction.play("error")).screen;
            click(failing, 450, 460); save.flushAndAwait();
            require(frame(failing, UiInput.NONE) == MenuAction.NONE, "failed upgrade opened world");
            screenshot(out.resolve("failure.png"));
            require(click(failing, 800, 460).is(MenuAction.Kind.PLAY_WORLD), "failure blocked keeping old generator");
            System.out.println("PASS: production menu rendered at 1280/960/640; keep and upgrade buttons, failure recovery, Esc and durable saves; " + out);
        } finally {
            save.flushAndAwait(); ui.destroy(); text.destroy(); font.destroy(); small.destroy(); atlas.destroy(); window.destroy();
        }
    }
    private static MenuAction click(Screen screen, float x, float y) {
        frame(screen, UiInput.builder().at(x, y).mouseDown(true).mousePressed(true).build());
        return frame(screen, UiInput.builder().at(x, y).mouseReleased(true).build());
    }
    private static MenuAction frame(Screen screen, UiInput in) {
        glViewport(0, 0, width, height); glClearColor(.06f, .08f, .11f, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        ui.begin(width, height); theme.begin(width, height, in, 1, .016f);
        MenuAction action = screen.draw(theme); theme.end(); ui.end();
        require(glGetError() == GL_NO_ERROR, "menu GL error");
        return action;
    }
    private static void screenshot(Path file) throws Exception {
        var pixels = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = ((height - 1 - y) * width + x) * 4;
            image.setRGB(x, y, (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | pixels.get(i + 2) & 255);
        }
        ImageIO.write(image, "png", file.toFile());
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
