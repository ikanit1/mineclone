import com.mineclone.core.AppPaths;
import com.mineclone.game.Hud;
import com.mineclone.render.*;
import com.mineclone.world.*;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Снимки интерфейса без запуска игры.
 *
 * HUD меняется чаще всего и правится вслепую: хотбар, сердца, шкала сытости,
 * полоска прочности, полка крафта. Этот инструмент рисует их в реальном GL на
 * контрастном фоне, чтобы правку можно было посмотреть, а не только собрать.
 *
 * Запуск из корня репозитория:
 *   java -cp "out;libs/*" tools/RenderHudPreview.java
 */
public class RenderHudPreview {

    private static final int W = 1280, H = 720;

    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(W, H, "HUD preview", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
        Font font = new Font(AppPaths.path("assets/minecraft.ttf"), 22f);
        Font small = new Font(AppPaths.path("assets/minecraft.ttf"), 14f);
        UiRenderer ui = new UiRenderer();
        TextRenderer text = new TextRenderer(ui);
        ui.setAtlas(atlas.getTextureId());
        ui.registerFonts(font, small);
        menuUi = ui;
        Hud hud = new Hud(font, small, text, ui, atlas);

        Inventory inv = new Inventory();
        inv.set(0, ItemStack.of("iron_pickaxe"));
        inv.get(0).setDamage((int) (inv.get(0).item.durability * 0.45f));
        inv.set(1, ItemStack.of("wooden_axe"));
        inv.get(1).setDamage((int) (inv.get(1).item.durability * 0.85f));
        inv.set(2, ItemStack.of("beef", 5));
        inv.set(3, new ItemStack(BlockType.COBBLE, 64));
        inv.set(4, new ItemStack(BlockType.TORCH, 12));
        inv.set(5, new ItemStack(BlockType.DIAMOND_ORE, 3));
        inv.set(6, new ItemStack(BlockType.PLANKS, 30));
        inv.set(9, new ItemStack(BlockType.IRON_ORE, 8));
        inv.set(10, ItemStack.of("chicken", 2));
        inv.set(11, new ItemStack(BlockType.WOOD, 16));

        Files.createDirectories(Path.of("out-test/previews"));

        // Кадр 1: игровой HUD поверх «неба».
        shot("hud-play", () -> {
            hud.drawCompass(W, H, (float) Math.toRadians(-38), (float) (Math.PI / 6.0));
            hud.drawHotbar(W, H, inv, 3, 0.25f);
            hud.drawHearts(W, H, 13f, 17f);
            hud.drawHunger(W, H, 11f);
            hud.drawVersionLabel(W, H);
        });

        // Кадры компаса: рассвет, полдень, закат, ночь. Лента одна и та же,
        // меняются светило и цвет полосы неба — по этим четырём кадрам видно,
        // читается ли переход.
        float[] times = { 0.10f, (float) (Math.PI / 2.0), (float) (Math.PI * 0.95),
                (float) (Math.PI * 1.5) };
        float[] yaws = { 0f, 1.1f, 2.6f, 4.4f };
        String[] names = { "dawn", "noon", "dusk", "night" };
        for (int i = 0; i < times.length; i++) {
            final int k = i;
            shot("hud-compass-" + names[k], () -> hud.drawCompass(W, H, yaws[k], times[k]));
        }

        // Кадр 2: экран инвентаря с полкой крафта.
        shot("hud-inventory", () ->
                hud.drawInventory(W, H, 0, 0, false, false, inv, 3, null));

        // Кадр: экран сундука — его слоты сверху, инвентарь игрока снизу.
        ItemStack[] chest = new ItemStack[com.mineclone.world.Chunk.CHEST_SLOTS];
        chest[0] = new ItemStack(BlockType.COBBLE, 64);
        chest[1] = new ItemStack(BlockType.COAL_ORE, 23);
        chest[2] = ItemStack.of("diamond_pickaxe");
        chest[4] = ItemStack.of("mutton", 3);
        chest[9] = new ItemStack(BlockType.PLANKS, 48);
        chest[10] = new ItemStack(BlockType.GLASS, 7);
        chest[17] = new ItemStack(BlockType.TORCH, 31);
        chest[26] = new ItemStack(BlockType.DIAMOND_ORE, 2);
        shot("hud-chest", () ->
                hud.drawChest(W, H, 0, 0, false, false, chest, inv, 3, null));

        // Кадр: печь на середине работы — пламя горит, стрелка заполнена.
        var furnace = new com.mineclone.world.Furnace();
        furnace.input = ItemStack.of("beef", 6);
        furnace.fuel = new ItemStack(BlockType.COAL_ORE, 12);
        furnace.output = ItemStack.of("cooked_beef", 3);
        furnace.burnMax = com.mineclone.world.Smelting.COOK_TIME * 8f;
        furnace.burnLeft = furnace.burnMax * 0.62f;
        furnace.cook = com.mineclone.world.Smelting.COOK_TIME * 0.45f;
        shot("hud-furnace", () ->
                hud.drawFurnace(W, H, 0, 0, false, false, furnace, inv, 3, null));

        // Кадр 3: творческое меню — блоки, инструменты и еда в одной сетке.
        shot("hud-creative", () ->
                hud.drawCreativeMenu(W, H, 0, 0, false, inv, 3));

        // Кадр: стекло поверх пёстрого «мира», подсказка у прицела на
        // кириллице, дуги звука и повёрнутый кубик в выбранном слоте. Пёстрые
        // полосы нужны, чтобы размытие под стеклом было видно глазом.
        Backdrop backdrop = new Backdrop();
        com.mineclone.game.SoundIndicators cues = new com.mineclone.game.SoundIndicators();
        cues.add(0f, 0f, 0f, 9f, 2f, 0.9f, true);
        cues.add(0f, 0f, 0f, -3f, 8f, 0.6f, false);
        shot("hud-glass", () -> {
            ui.begin(W, H);
            for (int i = 0; i < 24; i++) {
                float t = i / 24f;
                ui.quad(i * W / 24f, 0, W / 24f + 1f, H,
                        0.5f + 0.5f * (float) Math.sin(t * 6.28f), 0.5f + 0.5f * (float) Math.sin(t * 6.28f + 2.1f),
                        0.5f + 0.5f * (float) Math.sin(t * 6.28f + 4.2f), 1f);
            }
            ui.end();
            backdrop.capture(W, H);
            ui.setBackdrop(backdrop.texture());
            hud.setTime(0.6f);
            hud.drawCompass(W, H, (float) Math.toRadians(-38), (float) (Math.PI / 6.0));
            hud.drawHotbar(W, H, inv, 3, 1f);
            hud.drawHearts(W, H, 13f, 17f);
            hud.drawHunger(W, H, 11f);
            // Строку берём из скомпилированного кода игры: сам этот файл лаунчер
            // читает в кодировке системы, и кириллица в литерале портится.
            hud.drawHint(W, H, com.mineclone.game.ContextHint.forTarget(BlockType.CHEST, null, true, false), 1f);
            hud.drawSoundCues(W, H, cues, 0f);
            ui.setBackdrop(0);
        });
        // Кадр: витрина темы меню — все виджеты в покое, под курсором, нажатыми
        // и выключенными, поверх того же пёстрого фона со стеклом. Подписи —
        // юникод-экранами: литерал кириллицы лаунчер испортил бы.
        com.mineclone.ui.MenuTheme theme = new com.mineclone.ui.MenuTheme(ui, text, font, small, atlas);
        com.mineclone.ui.TextField field = new com.mineclone.ui.TextField("Seed 1234", 32,
                com.mineclone.ui.TextField.ANY);
        shot("menu-theme", () -> {
            stripes(ui);
            backdrop.capture(W, H);
            ui.setBackdrop(backdrop.texture());
            // Курсор над второй кнопкой: видно наведение.
            com.mineclone.ui.UiInput hover = com.mineclone.ui.UiInput.builder().at(640f, 250f).build();
            theme.begin(W, H, hover, 1.2f, 1f);
            theme.focus("field");
            theme.dim(0.25f);
            theme.panel(80, 60, 520, 600);
            float y = theme.header("\u0412\u0438\u0442\u0440\u0438\u043d\u0430 \u0442\u0435\u043c\u044b", 104, 76, 472);
            theme.button("b1", 104, y + 20, 472, 44, "\u041e\u0434\u0438\u043d\u043e\u0447\u043d\u0430\u044f \u0438\u0433\u0440\u0430", com.mineclone.ui.MenuTheme.Style.PRIMARY, true);
            theme.button("b2", 104, y + 74, 472, 44, "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 (\u043d\u0430\u0432\u0435\u0434\u0435\u043d\u0438\u0435)");
            theme.button("b3", 104, y + 128, 230, 44, "\u0423\u0434\u0430\u043b\u0438\u0442\u044c", com.mineclone.ui.MenuTheme.Style.DANGER, true);
            theme.button("b4", 346, y + 128, 230, 44, "\u0412\u044b\u043a\u043b\u044e\u0447\u0435\u043d\u0430", com.mineclone.ui.MenuTheme.Style.NORMAL, false);
            theme.slider("s1", 104, y + 190, 472, 38, "\u0414\u0430\u043b\u044c\u043d\u043e\u0441\u0442\u044c \u043f\u0440\u043e\u0440\u0438\u0441\u043e\u0432\u043a\u0438", "8 \u0447\u0430\u043d\u043a\u043e\u0432", 0.42f);
            theme.toggle("t1", 104, y + 238, 472, 38, "\u0412\u0435\u0440\u0442\u0438\u043a\u0430\u043b\u044c\u043d\u0430\u044f \u0441\u0438\u043d\u0445\u0440\u043e\u043d\u0438\u0437\u0430\u0446\u0438\u044f", true);
            theme.toggle("t2", 104, y + 286, 472, 38, "\u041f\u043e\u043a\u0430\u0447\u0438\u0432\u0430\u043d\u0438\u0435 \u043a\u0430\u043c\u0435\u0440\u044b", false);
            theme.segmented("g1", 104, y + 334, 472, 38, new String[] { "\u0412\u044b\u0436\u0438\u0432\u0430\u043d\u0438\u0435", "\u0422\u0432\u043e\u0440\u0447\u0435\u0441\u0442\u0432\u043e" }, 0);
            theme.textField("field", 104, y + 382, 472, 38, field, "\u041f\u0443\u0441\u0442\u043e \u2014 \u0441\u043b\u0443\u0447\u0430\u0439\u043d\u044b\u0439");
            theme.progressBar(104, y + 440, 472, 18, 0.63f);
            theme.spinner(124, y + 490, 10f, com.mineclone.ui.MenuTheme.ACCENT);
            theme.check(150, y + 482, 18f, com.mineclone.ui.MenuTheme.GOOD, 1f);
            theme.smallText("\u041c\u0435\u043b\u043a\u0438\u0439 \u043a\u0435\u0433\u043b\u044c: \u043f\u043e\u0434\u043f\u0438\u0441\u0438 \u0438 \u0441\u0447\u0451\u0442\u0447\u0438\u043a\u0438", 180, y + 496, com.mineclone.ui.MenuTheme.TEXT_DIM, 1f);

            theme.panel(660, 60, 540, 600);
            float y2 = theme.header("\u0421\u043f\u0438\u0441\u043e\u043a \u043c\u0438\u0440\u043e\u0432", 684, 76, 492);
            theme.row("r1", 684, y2 + 20, 492, 84, true);
            theme.text("\u041c\u0438\u0440 1", 820, y2 + 52, com.mineclone.ui.MenuTheme.TEXT, 1f);
            theme.smallText("\u0412\u044b\u0436\u0438\u0432\u0430\u043d\u0438\u0435 \u00b7 \u0414\u0435\u043d\u044c 12 \u00b7 3,4 \u041c\u0411", 820, y2 + 76, com.mineclone.ui.MenuTheme.TEXT_DIM, 1f);
            theme.row("r2", 684, y2 + 112, 492, 84, false);
            theme.text("\u041e\u0441\u0442\u0440\u043e\u0432", 820, y2 + 144, com.mineclone.ui.MenuTheme.TEXT, 1f);
            theme.smallText("\u0422\u0432\u043e\u0440\u0447\u0435\u0441\u0442\u0432\u043e \u00b7 \u0414\u0435\u043d\u044c 3 \u00b7 812 \u041a\u0411", 820, y2 + 168, com.mineclone.ui.MenuTheme.TEXT_DIM, 1f);
            theme.logo("MINECLONE", 930, y2 + 250, 7f);
            theme.end();
            ui.setBackdrop(0);
        });

        // ---- экраны меню ----
        // Миры для списка — настоящие сейвы во временной папке, с превью:
        // так снимок проходит тот же путь чтения, что и игра.
        Path previewRoot = Files.createTempDirectory("mineclone-menu-preview-");
        com.mineclone.save.SaveManager save = new com.mineclone.save.SaveManager(previewRoot.resolve("saves").toFile());
        long now = System.currentTimeMillis();
        sampleWorld(save, "preview_a", "\u041e\u0441\u0442\u0440\u043e\u0432", 12345L, com.mineclone.world.GameMode.SURVIVAL,
                now - 3_600_000L, (float) (Math.PI * 2 * 11 + 0.8), 0);
        sampleWorld(save, "preview_b", "\u0417\u0438\u043c\u043d\u0438\u0439 \u043b\u0430\u0433\u0435\u0440\u044c", -8811L, com.mineclone.world.GameMode.CREATIVE,
                now - 26 * 3_600_000L, 2.5f, 1);
        sampleWorld(save, "preview_c", "\u041c\u0438\u0440 1", 42L, com.mineclone.world.GameMode.SURVIVAL,
                now - 9L * 86_400_000L, 0.6f, -1);
        Path broken = previewRoot.resolve("saves").resolve("preview_broken");
        Files.createDirectories(broken);
        Files.write(broken.resolve("level.dat"), new byte[] { 1, 2, 3 });
        save.flushAndAwait();
        com.mineclone.save.SaveManager emptySave =
                new com.mineclone.save.SaveManager(previewRoot.resolve("empty").toFile());

        com.mineclone.ui.SettingsModel settings = new com.mineclone.ui.SettingsModel(new com.mineclone.core.KeyBindings());
        settings.renderRadius = 8;
        settings.maxFps = 144;

        menuShot("menu-title", backdrop, theme, new com.mineclone.ui.TitleScreen(save, settings, 0),
                at(640f, 398f), 0);

        com.mineclone.ui.WorldSelectScreen worlds = new com.mineclone.ui.WorldSelectScreen(save, settings);
        worlds.select("preview_b");
        Thread.sleep(300);   // размеры миров считаются в фоне
        menuShot("menu-worlds", backdrop, theme, worlds, at(500f, 170f), 1);
        worlds.requestDelete();
        menuShot("menu-worlds-delete", backdrop, theme, worlds, at(470f, 430f), 1);
        worlds.escape();
        worlds.select("preview_a");
        worlds.requestRename(theme);
        menuShot("menu-worlds-rename", backdrop, theme, worlds,
                com.mineclone.ui.UiInput.builder().at(0f, 0f).typed(" 2").build(), 1);
        worlds.closed();
        menuShot("menu-worlds-empty", backdrop, theme, new com.mineclone.ui.WorldSelectScreen(emptySave, settings),
                at(640f, 420f), 1);

        com.mineclone.ui.WorldCreateScreen create = new com.mineclone.ui.WorldCreateScreen(save, settings);
        menuFrame(backdrop, theme, create, at(0f, 0f), 0.1f);
        menuFrame(backdrop, theme, create, click(500f, 274f), 0.2f);
        menuFrame(backdrop, theme, create, com.mineclone.ui.UiInput.builder().at(500f, 274f).typed("mineclone").build(), 0.3f);
        menuFrame(backdrop, theme, create, click(800f, 356f), 0.4f);
        menuShot("menu-create", backdrop, theme, create, at(480f, 594f), 2);

        settings.keys.set(com.mineclone.core.KeyBindings.Action.JUMP, org.lwjgl.glfw.GLFW.GLFW_KEY_W);
        menuShot("menu-settings-graphics", backdrop, theme, new com.mineclone.ui.SettingsScreen(settings, 0),
                at(640f, 214f), 1);
        menuShot("menu-settings-controls", backdrop, theme, new com.mineclone.ui.SettingsScreen(settings, 1),
                at(640f, 312f), 1);
        menuShot("menu-settings-audio", backdrop, theme, new com.mineclone.ui.SettingsScreen(settings, 2),
                at(0f, 0f), 1);
        com.mineclone.ui.KeybindScreen keys = new com.mineclone.ui.KeybindScreen(settings);
        keys.startCapture(com.mineclone.core.KeyBindings.Action.DROP);
        menuShot("menu-keys", backdrop, theme, keys, at(0f, 0f), 1);

        com.mineclone.ui.LoadingScreen loading = new com.mineclone.ui.LoadingScreen("\u041e\u0441\u0442\u0440\u043e\u0432");
        loading.update(com.mineclone.world.LoadStage.GENERATING, 0.45f, 0f, 0f);
        menuShot("menu-loading-generating", backdrop, theme, loading, at(0f, 0f), 3);
        loading.update(com.mineclone.world.LoadStage.LIGHTING, 1f, 0.62f, 0f);
        menuShot("menu-loading-lighting", backdrop, theme, loading, at(0f, 0f), 3);
        loading.update(com.mineclone.world.LoadStage.BUILDING, 1f, 1f, 0.30f);
        menuShot("menu-loading-building", backdrop, theme, loading, at(0f, 0f), 3);

        com.mineclone.ui.PauseScreen pause = new com.mineclone.ui.PauseScreen("\u041e\u0441\u0442\u0440\u043e\u0432", settings);
        menuFrame(backdrop, theme, pause, click(640f, 326f), 0.5f);
        menuShot("menu-pause", backdrop, theme, pause, at(640f, 270f), 4);
        menuShot("menu-death", backdrop, theme, new com.mineclone.ui.DeathScreen(), at(640f, 336f), 4);
        backdrop.destroy();

        text.destroy();
        ui.destroy();
        font.destroy();
        small.destroy();
        atlas.destroy();
        glfwDestroyWindow(window);
        glfwTerminate();
        System.out.println("OK: " + shots + " frames in out-test/previews/");
    }

    /** Пёстрые полосы — чтобы размытие под стеклом было видно глазом. */
    private static void stripes(UiRenderer ui) {
        ui.begin(W, H);
        for (int i = 0; i < 24; i++) {
            float t = i / 24f;
            ui.quad(i * W / 24f, 0, W / 24f + 1f, H,
                    0.5f + 0.5f * (float) Math.sin(t * 6.28f), 0.5f + 0.5f * (float) Math.sin(t * 6.28f + 2.1f),
                    0.5f + 0.5f * (float) Math.sin(t * 6.28f + 4.2f), 1f);
        }
        ui.end();
    }


    private static com.mineclone.ui.UiInput at(float x, float y) {
        return com.mineclone.ui.UiInput.builder().at(x, y).build();
    }

    private static com.mineclone.ui.UiInput click(float x, float y) {
        return com.mineclone.ui.UiInput.builder().at(x, y).click().build();
    }

    /**
     * Мир для списка: level.dat, чанк и превью-«пейзаж». variant < 0 — без
     * превью, так видно заглушку.
     */
    private static void sampleWorld(com.mineclone.save.SaveManager save, String id, String name, long seed,
                                    com.mineclone.world.GameMode mode, long lastPlayed, float tod, int variant) {
        save.saveLevel(id, new com.mineclone.save.LevelData(name, seed, 8, 70, 8, 8, 70, 8, 0f, 0f, tod, 0,
                com.mineclone.save.LevelData.emptyInventory(), mode, lastPlayed, 20f, 20f));
        byte[] blocks = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        new java.util.Random(seed).nextBytes(blocks);
        save.saveChunkAsync(id, new com.mineclone.save.ChunkSnapshot(0, 0, blocks,
                new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME]));
        if (variant < 0)
            return;
        int w = 256, h = 144;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double hill = 70 + 18 * Math.sin(x * 0.045 + variant * 2.0) + 9 * Math.sin(x * 0.13 + variant);
                int r, g, b;
                if (y < hill) {
                    double k = y / hill;
                    r = (int) (variant == 1 ? 150 + 60 * k : 90 + 70 * k);
                    g = (int) (variant == 1 ? 170 + 50 * k : 150 + 60 * k);
                    b = (int) (variant == 1 ? 200 + 40 * k : 230 - 20 * k);
                } else if (y < hill + 6) {
                    r = variant == 1 ? 235 : 70; g = variant == 1 ? 240 : 150; b = variant == 1 ? 245 : 55;
                } else {
                    r = variant == 1 ? 120 : 110; g = variant == 1 ? 110 : 80; b = variant == 1 ? 100 : 50;
                }
                px[y * w + x] = r << 16 | g << 8 | b;
            }
        save.saveIconAsync(id, w, h, px);
    }

    /** Условный пейзаж под меню: небо, холмы, земля — стекло видно на нём честно. */
    private static void scenery(UiRenderer ui) {
        ui.begin(W, H);
        for (int i = 0; i < 18; i++) {
            float k = i / 18f;
            ui.quad(0, i * H * 0.55f / 18f, W, H * 0.55f / 18f + 1f,
                    0.36f + 0.30f * k, 0.55f + 0.25f * k, 0.86f + 0.08f * k, 1f);
        }
        for (int x = 0; x < W; x += 16) {
            float hill = H * 0.46f + 40f * (float) Math.sin(x * 0.006) + 18f * (float) Math.sin(x * 0.021);
            ui.quad(x, hill, 16f, H - hill, 0.22f, 0.42f, 0.20f, 1f);
            ui.quad(x, hill + 60f + 10f * (float) Math.sin(x * 0.03), 16f, H, 0.34f, 0.25f, 0.16f, 1f);
        }
        ui.end();
    }

    private static UiRenderer menuUi;

    /** Кадр экрана меню поверх пейзажа со стеклом; dt = 1 — пружины доезжают сразу. */
    private static void menuFrame(Backdrop backdrop, com.mineclone.ui.MenuTheme theme, com.mineclone.ui.Screen screen,
                                  com.mineclone.ui.UiInput in, float time) {
        glViewport(0, 0, W, H);
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        scenery(menuUi);
        backdrop.capture(W, H);
        menuUi.setBackdrop(backdrop.texture());
        theme.begin(W, H, in, time, 1f);
        theme.beginScreen(1f, 0f, true);
        screen.draw(theme);
        theme.endScreen();
        theme.end();
        menuUi.setBackdrop(0);
    }

    private static void menuShot(String name, Backdrop backdrop, com.mineclone.ui.MenuTheme theme,
                                 com.mineclone.ui.Screen screen, com.mineclone.ui.UiInput in, float time)
            throws Exception {
        shot(name, () -> menuFrame(backdrop, theme, screen, in, time));
    }

    /** Предыдущий кадр — новый обязан от него отличаться. */
    private static int[] previous;

    private static int shots;

    private static void shot(String name, Runnable draw) throws Exception {
        shots++;
        glViewport(0, 0, W, H);
        // Небесно-синий фон: на чёрном не видно тёмных рамок слотов, на белом —
        // светлых. Синий показывает и то, и другое.
        glClearColor(0.42f, 0.56f, 0.70f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        draw.run();
        var pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int p = (y * W + x) * 4;
                img.setRGB(x, H - y - 1, 0xff000000 | (pixels.get(p) & 255) << 16
                        | (pixels.get(p + 1) & 255) << 8 | (pixels.get(p + 2) & 255));
            }
        ImageIO.write(img, "png", Path.of("out-test/previews/" + name + ".png").toFile());
        // Однотонный кадр — значит, ничего не нарисовалось; совпавший с прошлым —
        // значит, рисуется не то, что просили.
        int[] argb = img.getRGB(0, 0, W, H, null, 0, W);
        boolean uniform = true;
        for (int i = 1; i < argb.length && uniform; i++)
            uniform = argb[i] == argb[0];
        if (uniform)
            throw new IllegalStateException(name + ": frame is a single colour");
        if (previous != null && java.util.Arrays.equals(previous, argb))
            throw new IllegalStateException(name + ": frame is identical to the previous one");
        previous = argb;
        int err = glGetError();
        if (err != GL_NO_ERROR)
            throw new IllegalStateException("OpenGL error " + err + " in " + name);
        System.out.println("rendered " + name);
    }
}
