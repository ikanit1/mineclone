import com.mineclone.core.Input;
import com.mineclone.core.Window;
import com.mineclone.game.Game;
import com.mineclone.save.SaveManager;
import com.mineclone.ui.*;
import com.mineclone.world.World;
import com.mineclone.world.gen.*;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Hidden real Game loop: decline, upgrade through mouse input, save and reopen an old world. */
public final class WorldGenGameSmoke {
    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static void action(Game game, MenuAction a) throws Exception {
        var m = Game.class.getDeclaredMethod("handleMenuAction", MenuAction.class); m.setAccessible(true); m.invoke(game, a);
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static final class SmokeWindow extends Window {
        Game game;
        Throwable failure;
        int stage, frame, lastFrame;
        final Path out;
        final long started = System.nanoTime();
        SmokeWindow(Path out) { super("World generation Game smoke", 960, 540, false); this.out = out; }
        void next(int s) { stage = s; lastFrame = frame; }
        @Override public void update() {
            try {
                frame++;
                require(System.nanoTime() - started < 240_000_000_000L, "timeout at stage " + stage);
                require(glGetError() == GL_NO_ERROR, "Game GL error at stage " + stage);
                tick();
            } catch (Throwable error) { failure = error; error.printStackTrace(); glfwSetWindowShouldClose(getHandle(), true); }
            super.update();
        }
        void tick() throws Exception {
            if (game == null) return;
            ScreenStack menus = (ScreenStack) field(game, "menus");
            String state = field(game, "state").toString();
            World world = (World) field(game, "world");
            if (stage == 0 && frame > 3) {
                action(game, MenuAction.play("w"));
                require(menus.top() instanceof WorldGenUpgradeScreen && world == null, "Game bypassed generation offer");
                next(1);
            } else if (stage == 1 && frame - lastFrame > 8) {
                shot("game-offer");
                action(game, ((WorldGenUpgradeScreen) menus.top()).keepCurrent()); next(2);
            } else if (stage == 2 && state.equals("PLAYING")) {
                require(world.genPolicy().versionAt(200, -200) == WorldGenVersion.V1, "decline changed generator");
                action(game, MenuAction.of(MenuAction.Kind.MAIN_MENU)); next(3);
            } else if (stage == 3 && frame - lastFrame > 8) {
                action(game, MenuAction.play("w"));
                require(menus.top() instanceof WorldGenUpgradeScreen, "old world was upgraded automatically"); next(4);
            } else if (stage == 4 && frame - lastFrame > 8) {
                Input input = (Input) field(game, "input"); input.overrideCursor(285, 370);
                input.injectMouseButton(GLFW_MOUSE_BUTTON_LEFT, true); next(5);
            } else if (stage == 5) {
                ((Input) field(game, "input")).injectMouseButton(GLFW_MOUSE_BUTTON_LEFT, false); next(6);
            } else if (stage == 6 && state.equals("PLAYING") && world != null) {
                require(world.genPolicy().versionAt(0, 0) == WorldGenVersion.V1, "upgrade changed spawn land");
                require(world.genPolicy().versionAt(200, -200) == WorldGenVersion.V1, "upgrade changed previously recorded distant land");
                require(world.genPolicy().versionAt(300, -300) == WorldGenVersion.V2, "upgrade button not applied in Game");
                shot("game-upgraded"); action(game, MenuAction.of(MenuAction.Kind.MAIN_MENU)); next(7);
            } else if (stage == 7 && frame - lastFrame > 8) {
                action(game, MenuAction.play("w"));
                require(!(menus.top() instanceof WorldGenUpgradeScreen), "reopened V2 world asked again"); next(8);
            } else if (stage == 8 && state.equals("PLAYING") && world != null) {
                require(world.genPolicy().versionAt(0, 0) == WorldGenVersion.V1
                        && world.genPolicy().versionAt(200, -200) == WorldGenVersion.V1
                        && world.genPolicy().versionAt(300, -300) == WorldGenVersion.V2, "save/reopen lost versions");
                System.out.println("WORLDGEN_GAME_SMOKE PASS decline=V1 upgradeMouse=V2 spawn=V1 saveReopen=mixed frames=" + frame);
                glfwSetWindowShouldClose(getHandle(), true); next(9);
            }
        }
        void shot(String name) throws Exception {
            int w = getWidth(), h = getHeight(); var px = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
            var image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                image.setRGB(x, h - 1 - y, (px.get(i) & 255) << 16 | (px.get(i + 1) & 255) << 8 | px.get(i + 2) & 255);
            }
            javax.imageio.ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
        }
    }
    public static void main(String[] args) throws Exception {
        Path out = Path.of("out-test/worldgen-game").toAbsolutePath(); Files.createDirectories(out);
        Path root = Files.createTempDirectory(out, "saves-");
        Path source = Path.of("src/test/resources/fixtures/saves/alpha-small");
        try (var files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path target = root.resolve("w").resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target);
            }
        }
        System.setProperty("mineclone.savesDir", root.toString());
        System.setProperty("mineclone.checkThreadOwnership", "true");
        SmokeWindow window = new SmokeWindow(out); window.init();
        try {
            window.game = new Game(window, false);
            ((Input) field(window.game, "input")).setGrabAllowed(false);
            set(window.game, "renderRadius", 2); set(window.game, "guiScale", 1);
            window.game.run();
            if (window.failure != null) throw new AssertionError("Game worldgen smoke failed", window.failure);
            require(window.stage == 9, "Game smoke incomplete");
        } finally { window.destroy(); }
    }
}
