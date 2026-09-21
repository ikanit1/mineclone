import com.mineclone.core.AppPaths;
import com.mineclone.game.Hud;
import com.mineclone.render.*;
import com.mineclone.world.*;

import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Cost of the inventory windows: milliseconds and draw calls per frame.
 *
 * Same method as BenchShaders: a hidden 1280x720 window, glFinish after every
 * frame so the GPU time is inside the measurement, and warm-up frames are thrown
 * away (the driver compiles the pipeline on the first ones).
 *
 * Run from the repository root:
 *   java -cp "out;libs/*" tools/BenchUi.java
 */
public class BenchUi {

    private static final int W = 1280, H = 720;
    private static final int WARMUP = 60, FRAMES = 300;

    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(W, H, "UI bench", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL window");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        GL.createCapabilities();

        TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
        Font font = new Font(AppPaths.path("assets/minecraft.ttf"), 22f);
        Font small = new Font(AppPaths.path("assets/minecraft.ttf"), 14f);
        UiRenderer ui = new UiRenderer();
        TextRenderer text = new TextRenderer(ui);
        ui.setAtlas(atlas.getTextureId());
        ui.registerFonts(font, small);
        com.mineclone.ui.MenuTheme theme =
                new com.mineclone.ui.MenuTheme(ui, text, font, small, atlas);

        Inventory inv = sampleInventory();
        ItemStack[] chest = new ItemStack[Chunk.CHEST_SLOTS];
        for (int i = 0; i < chest.length; i += 2)
            chest[i] = new ItemStack(BlockType.COBBLE, 1 + i * 2);

        com.mineclone.ui.container.PreviewContext ctx = new com.mineclone.ui.container.PreviewContext(inv);
        var inventoryScreen = new com.mineclone.ui.container.InventoryScreen(ctx);
        var chestScreen = new com.mineclone.ui.container.ChestScreen(ctx, chest, null, null);
        com.mineclone.ui.container.PreviewContext creativeCtx = new com.mineclone.ui.container.PreviewContext(inv);
        creativeCtx.mode = com.mineclone.world.GameMode.CREATIVE;
        var creativeScreen = new com.mineclone.ui.container.CreativeScreen(creativeCtx);

        System.out.printf("%-10s %10s %12s%n", "window", "ms/frame", "draws/frame");
        bench("inventory", window, () -> frame(theme, inventoryScreen));
        bench("chest", window, () -> frame(theme, chestScreen));
        bench("creative", window, () -> frame(theme, creativeScreen));

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    /** One window frame with no input: the same path the game takes. */
    private static void frame(com.mineclone.ui.MenuTheme theme,
            com.mineclone.ui.container.ContainerScreen screen) {
        theme.begin(W, H, com.mineclone.ui.UiInput.builder().at(640f, 360f).build(), 0f, 1f / 60f);
        theme.beginScreen(1f, 0f, true);
        screen.draw(theme);
        theme.endScreen();
        theme.end();
    }

    private static Inventory sampleInventory() {
        Inventory inv = new Inventory();
        BlockType[] blocks = { BlockType.COBBLE, BlockType.PLANKS, BlockType.DIRT, BlockType.GLASS,
                BlockType.TORCH, BlockType.WOOD, BlockType.SAND, BlockType.COAL_ORE, BlockType.IRON_ORE,
                BlockType.STONE, BlockType.LEAVES, BlockType.CHEST, BlockType.FURNACE, BlockType.STAIRS,
                BlockType.GRASS, BlockType.ICE, BlockType.MUD, BlockType.OBSIDIAN, BlockType.GOLD_ORE,
                BlockType.DIAMOND_ORE };
        for (int i = 0; i < blocks.length; i++)
            inv.set(i * 36 / blocks.length, new ItemStack(blocks[i], 5 + i * 3));
        return inv;
    }

    private static void bench(String name, long window, Runnable draw) {
        glViewport(0, 0, W, H);
        long total = 0;
        int draws = 0;
        for (int i = 0; i < WARMUP + FRAMES; i++) {
            glClearColor(0.42f, 0.56f, 0.70f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            UiRenderer.resetDrawCalls();
            long t0 = System.nanoTime();
            draw.run();
            glFinish();
            long dt = System.nanoTime() - t0;
            if (i >= WARMUP) {
                total += dt;
                draws = UiRenderer.drawCalls();
            }
            glfwPollEvents();
        }
        System.out.printf("%-10s %10.3f %12d%n", name, total / 1e6 / FRAMES, draws);
    }
}
