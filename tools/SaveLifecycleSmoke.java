import com.mineclone.core.Input;
import com.mineclone.core.Window;
import com.mineclone.game.Game;
import com.mineclone.render.GpuTimers;
import com.mineclone.save.LevelData;
import com.mineclone.save.SaveManager;
import com.mineclone.sim.WorldClock;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.ScreenStack;
import com.mineclone.ui.WorldBackupsScreen;
import com.mineclone.ui.WorldBackupProgressScreen;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Hidden native Game loop; real rendering, save/load, refusal and backup UI input. */
public class SaveLifecycleSmoke {
    private static Object get(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args);
    }
    private static Object call(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }
    private static void check(boolean okay, String why) { if (!okay) throw new AssertionError(why); }
    private static void action(Game game, MenuAction action) throws Exception {
        call(game, "handleMenuAction", new Class<?>[] { MenuAction.class }, action);
    }

    private static final class ReviewWindow extends Window {
        Game game;
        Throwable failure;
        Path output, saves;
        byte[] expectedClock, corruptBytes = { 6, 5, 4, 3 }, opaque = { 88, 12, -1, 42 };
        int frame, stage, stageFrame;
        String reopened;
        boolean sawProgress;
        long started = System.nanoTime();
        ReviewWindow(Path output, Path saves, byte[] expectedClock) {
            super("Mineclone save lifecycle review", 960, 540, false);
            this.output = output; this.saves = saves; this.expectedClock = expectedClock;
        }
        private void next(int value) { stage = value; stageFrame = frame; }
        @Override public void update() {
            try {
                frame++;
                check(System.nanoTime() - started < 150_000_000_000L, "native lifecycle timeout at stage " + stage);
                int glError = glGetError();
                check(glError == GL_NO_ERROR, "OpenGL error 0x" + Integer.toHexString(glError) + " during stage " + stage);
                tick();
            } catch (Throwable error) {
                failure = error;
                error.printStackTrace();
                glfwSetWindowShouldClose(getHandle(), true);
            }
            super.update();
        }
        private void tick() throws Exception {
            if (game == null) return;
            SaveManager save = (SaveManager) get(game, "save");
            String state = get(game, "state").toString();
            ScreenStack menus = (ScreenStack) get(game, "menus");
            if (stage == 0 && frame > 3) {
                boolean accepted = (boolean) call(game, "startWorld", new Class<?>[] { String.class }, "corrupt");
                check(!accepted && get(game, "world") == null, "corrupt world opened");
                check(Arrays.equals(corruptBytes, Files.readAllBytes(saves.resolve("corrupt/level.dat"))), "Game refusal changed source");
                next(1);
            } else if (stage == 1 && frame - stageFrame > 8) {
                shot("corrupt-refusal");
                action(game, MenuAction.of(MenuAction.Kind.MAIN_MENU));
                var writerField = SaveManager.class.getDeclaredField("chunkWriter"); writerField.setAccessible(true);
                var writer = (java.util.concurrent.ExecutorService) writerField.get(save);
                writer.submit(() -> { try { Thread.sleep(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
                check((boolean) call(game, "startWorld", new Class<?>[] { String.class }, "review"), "valid open refused");
                next(2);
            } else if (stage == 2) {
                if (!sawProgress && menus.top() instanceof WorldBackupProgressScreen && frame - stageFrame > 2) {
                    sawProgress = true; shot("backup-progress");
                }
                if (get(game, "world") != null) {
                    check(sawProgress, "backup did not keep rendering progress frames");
                    check(Arrays.equals(expectedClock, ((WorldClock) get(game, "worldClock")).encode()), "clock changed during load");
                    check(save.listBackups("review").size() == 1, "opening did not create exactly one backup");
                    next(3);
                }
            } else if (stage == 3 && state.equals("PLAYING") && frame - stageFrame > 15) {
                shot("world-before-reopen");
                WorldClock clock = (WorldClock) get(game, "worldClock");
                clock.advance(3.333);
                expectedClock = clock.encode();
                action(game, MenuAction.of(MenuAction.Kind.MAIN_MENU));
                LevelData saved = save.loadLevel("review");
                check(Arrays.equals(expectedClock, saved.extraSections.get(WorldClock.SAVE_SECTION)), "save-close lost precise clock");
                check(Arrays.equals(opaque, saved.extraSections.get("future:review")), "save-close lost unknown section");
                call(game, "startWorld", new Class<?>[] { String.class }, "review");
                next(4);
            } else if (stage == 4 && get(game, "world") != null) {
                check(Arrays.equals(expectedClock, ((WorldClock) get(game, "worldClock")).encode()), "reopen lost clock fraction/ticks");
                check(((Inventory) get(game, "inventory")).get(0).count == 17, "reopen lost owner inventory");
                check(save.listBackups("review").size() == 2, "reopening backup count");
                next(5);
            } else if (stage == 5 && state.equals("PLAYING") && frame - stageFrame > 15) {
                action(game, MenuAction.of(MenuAction.Kind.MAIN_MENU));
                menus.push(new WorldBackupsScreen(save, "review"));
                next(6);
            } else if (stage == 6 && frame - stageFrame > 15) {
                shot("backups-ui");
                Input input = (Input) get(game, "input");
                input.overrideCursor(400, 464);
                input.injectMouseButton(GLFW_MOUSE_BUTTON_LEFT, true);
                next(7);
            } else if (stage == 7) {
                ((Input) get(game, "input")).injectMouseButton(GLFW_MOUSE_BUTTON_LEFT, false);
                next(8);
            } else if (stage == 8 && get(game, "worldId") != null) {
                reopened = (String) get(game, "worldId");
                if (reopened.equals("review")) return;
                check(save.loadLevel(reopened).name.contains("(бэкап "), "UI did not restore a named copy");
                check(Arrays.equals(opaque, save.loadLevel(reopened).extraSections.get("future:review")), "UI restore lost unknown section");
                next(9);
            } else if (stage == 9 && state.equals("PLAYING") && frame - stageFrame > 15) {
                shot("restored-copy");
                GpuTimers gpu = (GpuTimers) get(game, "gpuTimers");
                check(gpu.sampleCount() > 10, "GPU queries did not complete");
                System.out.println("SAVE_LIFECYCLE_SMOKE PASS frames=" + frame + " gpuSamples=" + gpu.sampleCount()
                        + " restored=" + reopened + " clockUnknownRoundTrip=true corruptUnchanged=true backupUiRestore=true");
                glfwSetWindowShouldClose(getHandle(), true);
                next(10);
            }
        }
        private void shot(String name) throws Exception {
            int w = getWidth(), h = getHeight();
            java.nio.ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
            try {
                glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                var image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int p = (y * w + x) * 4;
                    image.setRGB(x, h - 1 - y, ((pixels.get(p) & 255) << 16)
                            | ((pixels.get(p + 1) & 255) << 8) | (pixels.get(p + 2) & 255));
                }
                javax.imageio.ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
            } finally { org.lwjgl.system.MemoryUtil.memFree(pixels); }
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/review-smoke/results").toAbsolutePath();
        Files.createDirectories(output);
        Path saves = Files.createTempDirectory(output, "saves-");
        System.setProperty("mineclone.savesDir", saves.toString());
        System.setProperty("mineclone.checkThreadOwnership", "true");
        SaveManager setup = new SaveManager(saves.toFile());
        WorldClock clock = new WorldClock(100.125); clock.advance(0.037);
        ItemStack[] inventory = LevelData.emptyInventory(); inventory[0] = ItemStack.of("diamond", 17);
        setup.saveLevel("review", new LevelData("Native save review", 4242, 8.5, 85, 8.5,
                8.5, 85, 8.5, 0, 0, clock.gameTimeFloat(), 0, inventory, GameMode.CREATIVE,
                12345, 13, 14, null, Map.of(WorldClock.SAVE_SECTION, clock.encode(), "future:review", new byte[] { 88, 12, -1, 42 })));
        setup.flushAndAwait();
        Files.createDirectories(saves.resolve("corrupt"));
        Files.write(saves.resolve("corrupt/level.dat"), new byte[] { 6, 5, 4, 3 });
        ReviewWindow window = new ReviewWindow(output, saves, clock.encode());
        window.init();
        org.lwjgl.opengl.GLDebugMessageCallback debug = null;
        if (org.lwjgl.opengl.GL.getCapabilities().OpenGL43) {
            debug = org.lwjgl.opengl.GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
                if (type == org.lwjgl.opengl.GL43.GL_DEBUG_TYPE_ERROR) {
                    System.err.println("GL_SMOKE_ERROR id=" + id + " "
                            + org.lwjgl.opengl.GLDebugMessageCallback.getMessage(length, message));
                    new Exception("GL_SMOKE_STACK").printStackTrace();
                }
            });
            org.lwjgl.opengl.GL43.glDebugMessageCallback(debug, 0);
            glEnable(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT);
            glEnable(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        }
        try {
            window.game = new Game(window, false);
            ((Input) get(window.game, "input")).setGrabAllowed(false);
            set(window.game, "renderRadius", 2);
            set(window.game, "guiScale", 1);
            window.game.run();
            if (window.failure != null) throw new AssertionError("native save lifecycle failed", window.failure);
            check(window.stage == 10, "native lifecycle did not complete");
        } finally {
            if (debug != null) { org.lwjgl.opengl.GL43.glDebugMessageCallback(null, 0); debug.free(); }
            window.destroy();
        }
    }
}
