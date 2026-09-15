package com.mineclone.game;

import com.mineclone.render.Font;
import com.mineclone.render.TextRenderer;
import com.mineclone.render.TextureAtlas;
import com.mineclone.render.UiRenderer;
import com.mineclone.world.BlockType;
import org.joml.Vector3f;

/**
 * Игровой интерфейс: F3, хотбар, сердца, сытость, компас, инвентарь, сундук,
 * печь, творческое меню. Меню, пауза и загрузка живут в {@code com.mineclone.ui}.
 */
public class Hud {

    private final Font font;
    private final Font small;
    private final TextRenderer text;
    private final UiRenderer ui;
    private final TextureAtlas atlas;

    /** Outcome of an inventory click: which slot and which button, or none. */
    public static final class SlotClick {
        public final int slot;      // -1 = not a slot
        public final boolean right;
        public final boolean trash; // clicked the trash box
        /** Индекс рецепта в полке крафта, или -1. */
        public final int recipe;
        /**
         * Клик пришёлся в контейнер, а не в инвентарь игрока.
         *
         * Отдельный признак, а не смещение индекса на сотню: смещение
         * пришлось бы помнить в каждом месте, где слот читают, и первая же
         * забытая проверка молча положила бы предмет не в тот ящик.
         */
        public final boolean container;
        private SlotClick(int slot, boolean right, boolean trash, int recipe, boolean container) {
            this.slot = slot; this.right = right; this.trash = trash;
            this.recipe = recipe; this.container = container;
        }
        public static SlotClick none()  { return new SlotClick(-1, false, false, -1, false); }
        public static SlotClick at(int slot, boolean right) { return new SlotClick(slot, right, false, -1, false); }
        public static SlotClick trash() { return new SlotClick(-1, false, true, -1, false); }
        public static SlotClick recipe(int index) { return new SlotClick(-1, false, false, index, false); }
        public static SlotClick inContainer(int slot, boolean right) {
            return new SlotClick(slot, right, false, -1, true);
        }
    }

    /**
     * @param small мелкий кегль того же шрифта: счётчики стопок, часы,
     *              подпись версии. Один кегль на весь интерфейс не работает —
     *              цифра «64» основным шрифтом закрывала полслота.
     */
    public Hud(Font font, Font small, TextRenderer text, UiRenderer ui, TextureAtlas atlas) {
        this.font = font;
        this.small = small != null ? small : font;
        this.text = text;
        this.ui = ui;
        this.atlas = atlas;
    }

    // ---------------- F3 debug overlay ----------------

    public void drawDebug(int screenW, int screenH, int fps, Vector3f pos,
            int chunkX, int chunkZ, int loadedChunks, int drawnChunks,
            BlockType target, byte targetMeta, boolean wireframe, int skyLight, int blockLight,
            String biome, int mobCount,
            com.mineclone.game.FrameProfiler profiler, int chunkQueue) {
        float lineH = font.getPixelHeight() + 2;
        float y = lineH;
        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        long total = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        String targetStr = target == null ? "—" : target.name() + "  meta=" + (targetMeta & 0xFF);
        String[] lines = {
                "Mineclone  -  F3 debug  " + (wireframe ? "[WIREFRAME]" : ""),
                "FPS: " + fps,
                String.format("XYZ: %.2f / %.2f / %.2f", pos.x, pos.y, pos.z),
                "Chunk: " + chunkX + " , " + chunkZ + "   Loaded: " + loadedChunks + "  Drawn: " + drawnChunks,
                "Biome: " + biome,
                "Target: " + targetStr,
                "Light  sky=" + skyLight + "  block=" + blockLight,
                "Mobs: " + mobCount,
                "Memory: " + used + " MB / " + total + " MB",
                // Средний FPS скрывает рывок: шестьдесят кадров с провалом
                // посередине и ровные шестьдесят читаются в счётчике одинаково.
                String.format("Frame: %.1f ms   worst %.1f ms", profiler.totalMillis(), profiler.worstMillis()),
                profiler.breakdown(),
                "Chunk queue: " + chunkQueue,
        };
        for (String s : lines) {
            text.drawShadowed(font, s, 8, y, screenW, screenH, 1f, 1f, 1f);
            y += lineH;
        }
    }

    // ---------------- Water overlay ----------------

    public void drawWaterOverlay(int screenW, int screenH) {
        ui.begin(screenW, screenH);
        // Full-screen tint to mask x-ray through water geometry
        ui.quad(0, 0, screenW, screenH, 0.04f, 0.14f, 0.55f, 0.62f);
        // Slightly lighter center — gives subtle "underwater depth" feel
        float cx = screenW * 0.2f, cy = screenH * 0.2f;
        ui.quad(cx, cy, screenW - 2 * cx, screenH - 2 * cy, 0.08f, 0.22f, 0.65f, 0.12f);
        ui.end();
    }

    // ---------------- Console bar ----------------

    public void drawConsole(int screenW, int screenH, String input) {
        float barH = font.getPixelHeight() + 12;
        float y = screenH - barH;
        ui.begin(screenW, screenH);
        ui.quad(0, y, screenW, barH, 0f, 0f, 0f, 0.72f);
        ui.end();
        text.drawShadowed(font, "> " + input + "|", 8, y + 6, screenW, screenH, 1f, 1f, 1f);
    }

    // ---------------- version label ----------------

    /**
     * Метка версии. Намеренно тихая: одна строка, полупрозрачная, в углу.
     * Крупная золотая вывеска на пол-экрана забирала внимание у игры и лезла
     * в каждый скриншот.
     */
    public void drawVersionLabel(int screenW, int screenH) {
        String label = com.mineclone.ui.TitleScreen.VERSION;
        float padding = 8;
        float w = small.textWidth(label);
        text.draw(small, label, screenW - w - padding, small.getPixelHeight() + 4f,
                screenW, screenH, 0.85f, 0.85f, 0.88f, 0.40f);
    }

    // ---------------- hotbar ----------------

    /**
     * @param selectAnim 0..1 — сколько прошло с переключения слота; на этом
     *                   едет пружина выделения
     */
    public void drawHotbar(int screenW, int screenH, com.mineclone.world.Inventory hotbar,
            int selected, float selectAnim) {
        int n = Math.min(9, hotbar.size());
        float slot = 52, pad = 4;
        float totalW = n * slot + (n - 1) * pad;
        float x0 = screenW / 2f - totalW / 2f;
        float y0 = screenH - slot - 16;

        ui.begin(screenW, screenH);
        // Стеклянная панель: размытый мир под ней, тёмная полупрозрачная
        // подложка, светлая грань сверху и тёмная снизу. Без размытого кадра
        // (пост не собрался) остаются одни три слоя — тот же язык, но плоский.
        ui.glass(x0 - 7, y0 - 7, totalW + 14, slot + 14);
        ui.quad(x0 - 7, y0 - 7, totalW + 14, slot + 14, 0.05f, 0.06f, 0.09f, ui.hasBackdrop() ? 0.42f : 0.55f);
        ui.quad(x0 - 7, y0 - 7, totalW + 14, 1.5f, 1f, 1f, 1f, 0.22f);
        ui.quad(x0 - 7, y0 + slot + 5.5f, totalW + 14, 1.5f, 0f, 0f, 0f, 0.35f);
        for (int i = 0; i < n; i++) {
            float x = x0 + i * (slot + pad);
            ui.quad(x, y0, slot, slot, 0.16f, 0.18f, 0.23f, 0.42f);
            ui.quad(x, y0, slot, 1f, 1f, 1f, 1f, 0.10f);
            com.mineclone.world.ItemStack stack = hotbar.get(i);
            if (stack != null && (stack.isTool() || stack.isFood() || stack.type != BlockType.AIR)) {
                float inset = 6;
                drawItemIcon(stack, x + inset, y0 + inset, slot - 2 * inset, 1f, i == selected);
            }
            if (i == selected) {
                // Затухающая пружина: рамка выскакивает и садится обратно.
                float grow = selectSpring(selectAnim) * 6f;
                float t = 3;
                float bx = x - grow, by = y0 - grow;
                float bs = slot + 2 * grow;
                ui.quad(bx - t, by - t, bs + 2 * t, t, 1f, 1f, 1f, 0.95f);
                ui.quad(bx - t, by + bs, bs + 2 * t, t, 1f, 1f, 1f, 0.95f);
                ui.quad(bx - t, by, t, bs, 1f, 1f, 1f, 0.95f);
                ui.quad(bx + bs, by, t, bs, 1f, 1f, 1f, 0.95f);
            }
        }
        ui.end();

        for (int i = 0; i < n; i++) {
            float x = x0 + i * (slot + pad);
            com.mineclone.world.ItemStack s = hotbar.get(i);
            if (s != null && s.count > 1)
                drawCount(screenW, screenH, s.count, x, y0, slot);
        }
    }

    public void drawHeldItem(int screenW, int screenH, BlockType held,
            float equipProgress, float swingProgress, float walkDistance, boolean underwater) {
        float swing = Math.max(0f, Math.min(1f, swingProgress));
        float equip = Math.max(0f, Math.min(1f, equipProgress));
        float bob = (float) Math.sin(walkDistance * 5.2f) * 4f;
        float swingArc = (float) Math.sin((1f - swing) * Math.PI);

        float armW = Math.max(70f, screenW * 0.070f);
        float armH = Math.max(150f, screenH * 0.245f);
        float armX = screenW - armW - 38f + swingArc * 46f;
        float armY = screenH - armH + (1f - equip) * 70f + bob + swingArc * 20f;

        ui.begin(screenW, screenH);
        ui.quad(armX + 8f, armY + 16f, armW, armH, 0.34f, 0.20f, 0.14f, 0.95f);
        ui.quad(armX, armY, armW, armH - 14f, 0.74f, 0.51f, 0.35f, 1f);
        ui.quad(armX, armY, armW, 8f, 0.92f, 0.70f, 0.50f, 1f);
        ui.quad(armX + armW - 10f, armY + 10f, 10f, armH - 24f, 0.50f, 0.31f, 0.22f, 1f);
        ui.quad(armX + 8f, armY + armH - 34f, armW - 16f, 26f, 0.54f, 0.34f, 0.23f, 1f);
        if (underwater)
            ui.quad(armX, armY, armW, armH, 0.08f, 0.22f, 0.65f, 0.20f);

        if (held != null && held != BlockType.AIR) {
            int tile = held == BlockType.GRASS ? held.topTile : held.sideTile;
            float[] uv = TextureAtlas.uv(tile);
            float size = Math.max(58f, Math.min(86f, screenH * 0.105f));
            float ix = armX - size * 0.50f - swingArc * 22f;
            float iy = armY + armH * 0.16f + (1f - equip) * 20f - swingArc * 24f;
            int tid = atlas.getTextureId();
            ui.quad(ix - 5f, iy + 7f, size + 10f, size + 10f, 0f, 0f, 0f, 0.30f);
            ui.texQuad(ix + size * 0.18f, iy + size * 0.24f, size, size, tid,
                    uv[0], uv[1], uv[2], uv[3], 0.50f, 0.50f, 0.50f, 1f);
            ui.texQuad(ix, iy, size, size, tid,
                    uv[0], uv[1], uv[2], uv[3], 1f, 1f, 1f, 1f);
            ui.quad(ix, iy, size, 3f, 1f, 1f, 1f, 0.25f);
            ui.quad(ix + size - 3f, iy, 3f, size, 0f, 0f, 0f, 0.22f);
        }
        ui.end();
    }

    // ---------------- health hearts ----------------

    private static final int HUNGER_FULL_TILE = 73;
    private static final int HUNGER_EMPTY_TILE = 74;
    private static final int HEART_EMPTY_TILE = 34;
    private static final int HEART_FULL_TILE = 35;
    private static final int HEART_HALF_TILE = 36;

    /**
     * Затухающая пружина 0..1 -> 0. Ровно то, что нужно выделению слота:
     * резкий выброс и мягкая посадка.
     */
    public static float selectSpring(float t) {
        if (t <= 0f || t >= 1f)
            return 0f;
        return (float) (Math.sin(t * Math.PI * 1.6) * Math.exp(-t * 4.0));
    }

    /**
     * @param ghost здоровье, которое «догоняет» текущее: полоса потери
     *              оседает медленнее, чем само здоровье падает
     */
    public void drawHearts(int screenW, int screenH, float health, float ghost) {
        // Mirror hotbar geometry so hearts sit flush above its left edge
        float slot = 52f, pad = 4f;
        float totalW = 9 * slot + 8 * pad; // 9 hotbar slots
        float hotbarX = screenW / 2f - totalW / 2f;
        float hotbarY = screenH - slot - 16f;

        float hs = 14f; // heart sprite display size (px)
        float gap = 2f; // gap between hearts
        float x0 = hotbarX;
        float y0 = hotbarY - 6f - hs - 5f; // just above hotbar backing quad

        float[] uvE = TextureAtlas.uv(HEART_EMPTY_TILE);
        float[] uvF = TextureAtlas.uv(HEART_FULL_TILE);
        float[] uvH = TextureAtlas.uv(HEART_HALF_TILE);
        int tid = atlas.getTextureId();

        ui.begin(screenW, screenH);
        for (int i = 0; i < 10; i++) {
            float x = x0 + i * (hs + gap);
            // Empty container behind every slot
            ui.texQuad(x, y0, hs, hs, tid, uvE[0], uvE[1], uvE[2], uvE[3], 1f, 1f, 1f, 1f);
            // Тень потери: тёмно-красное сердце там, где здоровье уже
            // ушло, но полоса ещё не осела. Без неё удар незаметен, если
            // смотреть на мир, а не на интерфейс.
            float gh = ghost - i * 2f;
            if (gh >= 1f)
                ui.texQuad(x, y0, hs, hs, tid, uvF[0], uvF[1], uvF[2], uvF[3],
                        0.45f, 0.06f, 0.06f, 1f);
            float hp = health - i * 2f;
            if (hp >= 2f) {
                ui.texQuad(x, y0, hs, hs, tid, uvF[0], uvF[1], uvF[2], uvF[3], 1f, 1f, 1f, 1f);
            } else if (hp >= 1f) {
                ui.texQuad(x, y0, hs, hs, tid, uvH[0], uvH[1], uvH[2], uvH[3], 1f, 1f, 1f, 1f);
            }
        }
        ui.end();
    }

    /**
     * Шкала сытости — зеркально сердцам, у правого края хотбара.
     *
     * Ровно та же геометрия, что у сердец, но отсчёт справа налево: так две
     * шкалы читаются как одна пара, а не как два разных элемента.
     */
    public void drawHunger(int screenW, int screenH, float hunger) {
        float slot = 52f, pad = 4f;
        float totalW = 9 * slot + 8 * pad;
        float hotbarX = screenW / 2f - totalW / 2f;
        float hotbarY = screenH - slot - 16f;

        float hs = 14f, gap = 2f;
        float y0 = hotbarY - 6f - hs - 5f;
        float right = hotbarX + totalW;

        float[] uvF = TextureAtlas.uv(HUNGER_FULL_TILE);
        float[] uvE = TextureAtlas.uv(HUNGER_EMPTY_TILE);
        int tid = atlas.getTextureId();

        ui.begin(screenW, screenH);
        for (int i = 0; i < 10; i++) {
            float x = right - (i + 1) * (hs + gap) + gap;
            ui.texQuad(x, y0, hs, hs, tid, uvE[0], uvE[1], uvE[2], uvE[3], 1f, 1f, 1f, 1f);
            float h = hunger - i * 2f;
            if (h >= 2f)
                ui.texQuad(x, y0, hs, hs, tid, uvF[0], uvF[1], uvF[2], uvF[3], 1f, 1f, 1f, 1f);
            else if (h >= 1f)
                // Половинка: рисуем целый значок, обрезая правую половину
                // вторым проходом пустого поверх.
                ui.texQuad(x, y0, hs / 2f, hs, tid, uvF[0], uvF[1],
                        (uvF[0] + uvF[2]) / 2f, uvF[3], 1f, 1f, 1f, 1f);
        }
        ui.end();
    }

    // ---------------- подсказка у прицела ----------------

    /**
     * Подсказка «клавиша — действие» под прицелом.
     *
     * Клавиша нарисована кнопкой, а не словом в скобках: глаз ищет её форму,
     * а не читает текст. Плашка стеклянная и маленькая — она подсказывает, а
     * не перекрывает то, на что игрок смотрит. Выплывает снизу вверх на
     * несколько пикселей вместе с появлением.
     */
    public void drawHint(int screenW, int screenH, ContextHint.Hint hint, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        float ease = a * a * (3f - 2f * a);
        float keyW = small.textWidth(hint.key()) + 12f;
        float actW = small.textWidth(hint.action());
        float h = 22f, gap = 7f, pad = 6f;
        float totalW = pad + keyW + gap + actW + pad + 2f;
        float x = screenW / 2f - totalW / 2f;
        float y = screenH / 2f + 30f + (1f - ease) * 6f;

        ui.begin(screenW, screenH);
        ui.quad(x, y, totalW, h, 0.05f, 0.06f, 0.09f, 0.62f * ease);
        ui.quad(x, y, totalW, 1f, 1f, 1f, 1f, 0.18f * ease);
        ui.quad(x, y + h - 1f, totalW, 1f, 0f, 0f, 0f, 0.35f * ease);
        // Кнопка: светлая плитка с тёмной нижней гранью — как клавиша.
        float kx = x + pad, ky = y + 3f, kh = h - 6f;
        ui.quad(kx, ky, keyW, kh, 0.86f, 0.89f, 0.94f, 0.92f * ease);
        ui.quad(kx, ky + kh - 2f, keyW, 2f, 0.45f, 0.48f, 0.55f, 0.92f * ease);
        ui.end();

        float baseline = y + h / 2f + small.getPixelHeight() * 0.36f;
        text.draw(small, hint.key(), kx + 6f, baseline, screenW, screenH, 0.08f, 0.09f, 0.12f, ease);
        text.draw(small, hint.action(), kx + keyW + gap, baseline, screenW, screenH,
                0.93f, 0.95f, 0.98f, ease);
    }

    // ---------------- индикаторы звука ----------------

    /** Сегментов в одной дуге: меньше — видны изломы, больше — лишние квады. */
    private static final int CUE_SEGMENTS = 10;
    /** Угловая ширина дуги, градусы. */
    private static final float CUE_SPAN = 26f;

    /**
     * Дуги по кругу вокруг прицела в сторону громких звуков.
     *
     * Круг, а не рамка экрана: на широком мониторе угол «справа-сзади» на
     * рамке съезжает в дальний угол, и глаз не успевает его найти. На
     * окружности направление читается как стрелка компаса. Радиус взят
     * близко к краю, чтобы дуга не лезла в центр кадра, где идёт бой.
     */
    public void drawSoundCues(int screenW, int screenH, SoundIndicators cues, float yaw) {
        if (cues.cues().isEmpty())
            return;
        float cx = screenW / 2f, cy = screenH / 2f;
        float radius = Math.min(screenW, screenH) * 0.36f;
        ui.begin(screenW, screenH);
        for (SoundIndicators.Cue c : cues.cues()) {
            float a = c.alpha();
            if (a <= 0.01f)
                continue;
            float bearing = SoundIndicators.bearing(yaw, c.dirX, c.dirZ);
            float r = c.danger ? 1f : 0.82f, g = c.danger ? 0.42f : 0.88f, b = c.danger ? 0.22f : 1f;
            // Мягкое свечение под дугой и сама дуга потоньше поверх.
            drawArc(cx, cy, radius - 3f, radius + 9f, bearing, CUE_SPAN * 1.15f, r, g, b, 0.16f * a);
            drawArc(cx, cy, radius, radius + 5f, bearing, CUE_SPAN, r, g, b, 0.72f * a);
        }
        ui.end();
    }

    /** Дуга из четырёхугольников: 0° — верх экрана, по часовой стрелке. */
    private void drawArc(float cx, float cy, float inner, float outer, float centerDeg, float spanDeg,
                         float r, float g, float b, float alpha) {
        for (int i = 0; i < CUE_SEGMENTS; i++) {
            float t0 = i / (float) CUE_SEGMENTS, t1 = (i + 1) / (float) CUE_SEGMENTS;
            // Края дуги тают: обрубленный конец выглядит как деталь рамки, а не как сигнал.
            float edge = (float) Math.sin(Math.PI * (t0 + t1) * 0.5);
            double a0 = Math.toRadians(centerDeg + (t0 - 0.5f) * spanDeg);
            double a1 = Math.toRadians(centerDeg + (t1 - 0.5f) * spanDeg);
            float s0 = (float) Math.sin(a0), c0 = (float) Math.cos(a0);
            float s1 = (float) Math.sin(a1), c1 = (float) Math.cos(a1);
            ui.quad4(new float[] {
                    cx + s0 * outer, cy - c0 * outer,
                    cx + s1 * outer, cy - c1 * outer,
                    cx + s1 * inner, cy - c1 * inner,
                    cx + s0 * inner, cy - c0 * inner
            }, r, g, b, alpha * edge);
        }
    }

    // ---------------- компас и время суток ----------------

    private static final float COMPASS_W = 320f;
    private static final float COMPASS_H = 30f;
    /** Отступ всего блока от верха экрана. */
    private static final float COMPASS_TOP = 8f;
    /** Высота дуги неба над лентой. */
    private static final float COMPASS_ARC = 26f;
    /** Масштаб ленты: половина ширины приходится на 90°, итого обзор ±90°. */
    private static final float COMPASS_DEG_PX = (COMPASS_W / 2f) / 90f;

    /**
     * Курс в градусах по часовой стрелке от севера.
     *
     * Солнце в этом мире встаёт в −Z и садится в +Z ({@link
     * com.mineclone.render.SunLight#sunDirection}), значит восток — это −Z,
     * а камера при yaw = 0 смотрит ровно туда. Отсюда сдвиг на 90°: север
     * оказывается слева от восхода, как и положено.
     */
    public static float heading(float yaw) {
        float deg = (float) Math.toDegrees(yaw) + 90f;
        deg %= 360f;
        return deg < 0f ? deg + 360f : deg;
    }

    /** Кратчайшая разница углов, −180..180. */
    public static float angleDelta(float target, float from) {
        float d = (target - from) % 360f;
        if (d > 180f)
            d -= 360f;
        if (d < -180f)
            d += 360f;
        return d;
    }

    /**
     * Игровые часы. {@code gameTime} в радианах, pi/2 — полдень, 0 — восход.
     * Тот же расчёт, что показывает команда {@code /time}: если бы формулы
     * разъехались, компас и консоль спорили бы о времени.
     */
    public static String clockText(float gameTime) {
        float cycle = (float) (Math.PI * 2.0);
        float t = gameTime % cycle;
        if (t < 0f)
            t += cycle;
        float hours = ((t + (float) (Math.PI / 2.0)) / cycle) * 24f;
        int totalMinutes = Math.floorMod(Math.round(hours * 60f), 24 * 60);
        return String.format("%02d:%02d", totalMinutes / 60, totalMinutes % 60);
    }

    /**
     * Лента компаса и дуга светила наверху экрана.
     *
     * Верхний край ленты работает горизонтом: светило выходит прямо из неё и
     * идёт по дуге до противоположного края. Поэтому одним взглядом видно и
     * куда смотришь, и сколько осталось до темноты — а это две вещи, ради
     * которых в такой игре вообще оборачиваются.
     */
    public void drawCompass(int screenW, int screenH, float yaw, float gameTime) {
        float x0 = screenW / 2f - COMPASS_W / 2f;
        float cx = screenW / 2f;
        float ribbonY = COMPASS_TOP + COMPASS_ARC;
        float horizon = ribbonY;              // верх ленты = линия горизонта

        float cycle = (float) (Math.PI * 2.0);
        float t = gameTime % cycle;
        if (t < 0f)
            t += cycle;
        boolean day = Math.sin(t) >= 0.0;
        float elev = (float) Math.abs(Math.sin(t));        // высота светила 0..1
        float u = (float) ((t % Math.PI) / Math.PI);       // путь восход→закат

        // Цвет неба в полосе: рассвет и закат тёплые, полдень синий, ночь тёмная.
        float sr, sg, sb;
        if (day) {
            float k = smooth01(elev / 0.35f);
            sr = lerp(0.82f, 0.36f, k);
            sg = lerp(0.46f, 0.60f, k);
            sb = lerp(0.24f, 0.86f, k);
        } else {
            sr = 0.09f; sg = 0.12f; sb = 0.25f;
        }

        ui.begin(screenW, screenH);

        // Небо — три полосы с затухающей прозрачностью вместо градиента:
        // uColor в UI-шейдере один на четырёхугольник.
        float band = COMPASS_ARC / 3f;
        for (int i = 0; i < 3; i++)
            ui.quad(x0, horizon - band * (i + 1), COMPASS_W, band,
                    sr, sg, sb, 0.26f - i * 0.08f);

        // Дуга: путь светила от края до края.
        int steps = 44;
        for (int i = 0; i <= steps; i++) {
            float ui_ = i / (float) steps;
            float ax = x0 + ui_ * COMPASS_W;
            float ay = horizon - COMPASS_ARC * (float) Math.sin(Math.PI * ui_);
            ui.quad(ax - 1f, ay - 1f, 2f, 2f,
                    day ? 0.98f : 0.70f, day ? 0.94f : 0.76f, day ? 0.78f : 0.98f,
                    day ? 0.22f : 0.18f);
        }

        // Само светило — там же, где дуга: sin(pi*u) и есть его высота.
        float lx = x0 + u * COMPASS_W;
        float ly = horizon - COMPASS_ARC * elev;
        if (day) {
            ui.quad(lx - 7f, ly - 7f, 14f, 14f, 1f, 0.80f, 0.36f, 0.22f);
            ui.quad(lx - 4f, ly - 4f, 8f, 8f, 1f, 0.93f, 0.64f, 1f);
            // Четыре луча: без них жёлтый квадрат читается как «предмет».
            ui.quad(lx - 1f, ly - 10f, 2f, 4f, 1f, 0.90f, 0.55f, 0.75f);
            ui.quad(lx - 1f, ly + 6f, 2f, 4f, 1f, 0.90f, 0.55f, 0.75f);
            ui.quad(lx - 10f, ly - 1f, 4f, 2f, 1f, 0.90f, 0.55f, 0.75f);
            ui.quad(lx + 6f, ly - 1f, 4f, 2f, 1f, 0.90f, 0.55f, 0.75f);
        } else {
            ui.quad(lx - 8f, ly - 8f, 16f, 16f, 0.58f, 0.70f, 1f, 0.13f);
            ui.quad(lx - 5.5f, ly - 5.5f, 11f, 11f, 0.70f, 0.80f, 1f, 0.26f);
            ui.quad(lx - 4f, ly - 4f, 8f, 8f, 0.88f, 0.92f, 1f, 0.95f);
        }

        // Стекло ленты — тот же приём, что у хотбара.
        ui.glass(x0, ribbonY, COMPASS_W, COMPASS_H);
        ui.quad(x0, ribbonY, COMPASS_W, COMPASS_H, 0.05f, 0.06f, 0.09f, ui.hasBackdrop() ? 0.42f : 0.55f);
        ui.quad(x0, ribbonY, COMPASS_W, 1.5f, 1f, 1f, 1f, 0.22f);
        ui.quad(x0, ribbonY + COMPASS_H - 1.5f, COMPASS_W, 1.5f, 0f, 0f, 0f, 0.35f);

        // Риски: каждые 15° мелкая, каждые 45° средняя.
        float head = heading(yaw);
        float tickBase = ribbonY + COMPASS_H - 2f;
        for (int deg = 0; deg < 360; deg += 15) {
            float d = angleDelta(deg, head);
            if (Math.abs(d) > 92f)
                continue;
            float x = cx + d * COMPASS_DEG_PX;
            float fade = clamp01((COMPASS_W / 2f - Math.abs(x - cx)) / 45f);
            if (deg % 90 == 0)
                continue;                      // у сторон света своя риска ниже
            boolean mid = deg % 45 == 0;
            float len = mid ? 9f : 5f;
            ui.quad(x - 1f, tickBase - len, 2f, len,
                    0.88f, 0.91f, 0.96f, (mid ? 0.45f : 0.24f) * fade);
        }

        // Куда смотришь: треугольник над лентой и своя риска снизу. Сплошная
        // линия во всю высоту перечёркивала бы букву, если сторона света
        // оказывалась ровно по курсу.
        ui.quad4(new float[] {
                cx - 5f, horizon - 8f,
                cx + 5f, horizon - 8f,
                cx,      horizon - 0.5f,
                cx,      horizon - 0.5f
        }, 0.98f, 0.99f, 1f, 0.9f);
        ui.quad(cx - 1f, tickBase - 9f, 2f, 9f, 0.98f, 0.99f, 1f, 0.9f);
        ui.end();

        // Стороны света буквами. Север тёплый — как стрелка настоящего компаса.
        String[] names = { "N", "E", "S", "W" };
        float baseline = ribbonY + COMPASS_H - 11f;
        for (int i = 0; i < 4; i++) {
            float d = angleDelta(i * 90, head);
            if (Math.abs(d) > 92f)
                continue;
            float x = cx + d * COMPASS_DEG_PX;
            float fade = clamp01((COMPASS_W / 2f - Math.abs(x - cx)) / 45f);
            float w = font.textWidth(names[i]);
            boolean north = i == 0;
            text.draw(font, names[i], x - w / 2f, baseline, screenW, screenH,
                    north ? 1f : 0.88f, north ? 0.70f : 0.91f, north ? 0.38f : 0.96f,
                    fade);
        }

        // Часы — в свободном верхнем углу полосы неба, у правого края ленты.
        String clock = clockText(gameTime);
        float cw = small.textWidth(clock);
        text.draw(small, clock, x0 + COMPASS_W - cw, COMPASS_TOP + 12f,
                screenW, screenH, 0.92f, 0.94f, 0.98f, 0.55f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }

    private static float smooth01(float v) {
        float k = clamp01(v);
        return k * k * (3f - 2f * k);
    }

    private static boolean hov(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public SlotClick drawInventory(int w, int h, double mx, double my,
            boolean clicked, boolean rightClicked,
            com.mineclone.world.Inventory inv, int selectedSlot,
            com.mineclone.world.ItemStack cursor) {
        float slot = 42f, gap = 5f;
        float panelW = 9 * slot + 8 * gap + 52f;
        float panelH = 360f + CRAFT_ROW_H;
        float panelX = w / 2f - panelW / 2f;
        float panelY = h / 2f - panelH / 2f;
        float invX = panelX + 22f;
        float titleY = panelY + 34f;
        float mainLabelY = panelY + 60f;
        float mainY = panelY + 78f;
        float hotbarLabelY = panelY + 250f;
        float hotbarY = panelY + 268f;
        float trashX = panelX + panelW - 70f;
        float trashY = panelY + 18f;

        SlotClick action = SlotClick.none();
        com.mineclone.world.ItemStack hovered = null;
        float hoverX = 0, hoverY = 0;

        ui.begin(w, h);
        // Мир за окном и так размыт глубиной резкости — затемнение мягче,
        // а панель прозрачнее: сквозь неё видно матовое стекло.
        ui.quad(0, 0, w, h, 0f, 0f, 0f, ui.hasBackdrop() ? 0.30f : 0.65f);
        // Тот же стеклянный язык, что у хотбара: тёмная полупрозрачная
        // подложка и тонкие рёбра. Бежевая плитка, доставшаяся от первых
        // версий, выглядела из другой игры.
        ui.glass(panelX, panelY, panelW, panelH);
        ui.quad(panelX, panelY, panelW, panelH, 0.06f, 0.07f, 0.10f, ui.hasBackdrop() ? 0.62f : 0.90f);
        ui.quad(panelX, panelY, panelW, 1.5f, 1f, 1f, 1f, 0.22f);
        ui.quad(panelX, panelY + panelH - 1.5f, panelW, 1.5f, 0f, 0f, 0f, 0.50f);
        ui.quad(panelX, panelY, 1.5f, panelH, 1f, 1f, 1f, 0.10f);
        ui.quad(panelX + panelW - 1.5f, panelY, 1.5f, panelH, 0f, 0f, 0f, 0.35f);
        ui.quad(trashX, trashY, 44f, 44f, 0.34f, 0.12f, 0.14f, 0.92f);
        ui.quad(trashX, trashY, 44f, 1f, 1f, 1f, 1f, 0.18f);
        ui.quad(trashX + 10f, trashY + 12f, 24f, 4f, 0.95f, 0.95f, 0.95f, 0.85f);
        ui.quad(trashX + 13f, trashY + 18f, 18f, 16f, 0.80f, 0.80f, 0.80f, 0.85f);

        // main 27 slots (indices 9..35)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = 9 + row * 9 + col;
                float x = invX + col * (slot + gap);
                float y = mainY + row * (slot + gap);
                boolean hov = hov(mx, my, x, y, slot, slot);
                drawSlotBack(x, y, slot, hov);
                com.mineclone.world.ItemStack s = inv.get(slotIndex);
                if (s != null) {
                    drawItemIcon(s, x + 6f, y + 6f, slot - 12f, 1f, hov);
                }
                if (hov) {
                    hovered = s; hoverX = x; hoverY = y;
                    if (clicked)           action = SlotClick.at(slotIndex, false);
                    else if (rightClicked) action = SlotClick.at(slotIndex, true);
                }
            }
        }

        // hotbar 9 slots (indices 0..8)
        for (int col = 0; col < 9; col++) {
            float x = invX + col * (slot + gap);
            float y = hotbarY;
            boolean hov = hov(mx, my, x, y, slot, slot);
            drawSlotBack(x, y, slot, hov);
            com.mineclone.world.ItemStack s = inv.get(col);
            if (s != null) {
                drawItemIcon(s, x + 6f, y + 6f, slot - 12f, 1f, hov);
            }
            if (col == selectedSlot) {
                ui.quad(x - 3f, y - 3f, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
                ui.quad(x - 3f, y + slot, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
                ui.quad(x - 3f, y, 3f, slot, 1f, 1f, 1f, 0.95f);
                ui.quad(x + slot, y, 3f, slot, 1f, 1f, 1f, 0.95f);
            }
            if (hov) {
                hovered = s; hoverX = x; hoverY = y;
                if (clicked)           action = SlotClick.at(col, false);
                else if (rightClicked) action = SlotClick.at(col, true);
            }
        }

        // ---- полка крафта --------------------------------------------------
        // Не сетка 2x2, а полка готовых рецептов: сетка требует ещё одного
        // набора слотов, своей логики курсора и понятия «форма», а полка
        // решает ту же задачу — дать собрать то, на что хватает материала.
        java.util.List<com.mineclone.world.Recipes.Recipe> craftable =
                com.mineclone.world.Recipes.available(inv);
        float craftY = panelY + panelH - CRAFT_ROW_H + 24f;
        for (int i = 0; i < craftable.size() && i < CRAFT_MAX; i++) {
            float x = invX + (i % 9) * (slot + gap);
            float y = craftY + (i / 9) * (slot + gap);
            boolean hov = hov(mx, my, x, y, slot, slot);
            drawSlotBack(x, y, slot, hov);
            com.mineclone.world.ItemStack out =
                    com.mineclone.world.Recipes.result(craftable.get(i));
            drawItemIcon(out, x + 6f, y + 6f, slot - 12f, 1f, hov);
            if (hov) {
                hovered = out;
                hoverX = x;
                hoverY = y;
                if (clicked)
                    action = SlotClick.recipe(i);
            }
        }

        boolean trashHover = hov(mx, my, trashX, trashY, 44f, 44f);
        if ((clicked || rightClicked) && trashHover)
            action = SlotClick.trash();

        if (cursor != null) {
            drawItemIcon(cursor, (float) mx - 18f, (float) my - 18f, 36f, 1f);
        }
        ui.end();

        text.drawShadowed(font, "Inventory", panelX + 22f, titleY, w, h, 1f, 0.94f, 0.82f);
        text.draw(small, "Storage", invX, mainLabelY, w, h, 0.72f, 0.77f, 0.86f, 1f);
        text.draw(small, "Hotbar", invX, hotbarLabelY, w, h, 0.72f, 0.77f, 0.86f, 1f);
        text.draw(small, craftable.isEmpty() ? "Crafting — nothing available yet" : "Crafting",
                invX, craftY - 8f, w, h, 0.72f, 0.77f, 0.86f, 1f);

        if (hovered != null) {
            String name = hovered.isTool() ? hovered.displayName() : displayName(hovered.type);
            float twd = font.textWidth(name);
            float tx = Math.min(w - twd - 12f, Math.max(8f, hoverX + 4f));
            text.drawShadowed(font, name, tx, hoverY - 8f, w, h, 1f, 1f, 1f);
        }

        // draw stack counts (after ui.end so text renders on top)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = 9 + row * 9 + col;
                float x = invX + col * (slot + gap);
                float y = mainY + row * (slot + gap);
                com.mineclone.world.ItemStack s = inv.get(slotIndex);
                if (s != null) drawCount(w, h, s.count, x + 6f, y + 6f, slot - 12f);
            }
        }
        for (int col = 0; col < 9; col++) {
            float x = invX + col * (slot + gap);
            float y = hotbarY;
            com.mineclone.world.ItemStack s = inv.get(col);
            if (s != null) drawCount(w, h, s.count, x + 6f, y + 6f, slot - 12f);
        }
        if (cursor != null && cursor.count > 1)
            drawCount(w, h, cursor.count, (float) mx - 18f, (float) my - 18f, 36f);

        return action;
    }

    // ---------------- сундук ----------------

    /** Сколько слотов сундука в ряду. */
    private static final int CHEST_COLS = 9;

    /**
     * Экран сундука: его слоты сверху, инвентарь игрока снизу.
     *
     * Полки крафта здесь нет намеренно. Сундук — это перекладывание, а не
     * производство; смешав их в одном окне, игрок перестаёт понимать, из
     * какого хранилища берётся материал.
     *
     * @param chest массив слотов сундука; правится на месте вызывающим
     * @return куда пришёлся клик — в сундук ({@code container}) или в инвентарь
     */
    public SlotClick drawChest(int w, int h, double mx, double my,
            boolean clicked, boolean rightClicked,
            com.mineclone.world.ItemStack[] chest,
            com.mineclone.world.Inventory inv, int selectedSlot,
            com.mineclone.world.ItemStack cursor) {
        float slot = 42f, gap = 5f;
        int chestRows = (chest.length + CHEST_COLS - 1) / CHEST_COLS;
        float panelW = CHEST_COLS * slot + (CHEST_COLS - 1) * gap + 44f;
        // Высота собирается из тех же слагаемых, из которых потом считаются
        // ряды: заголовок, слоты сундука, подпись, три ряда инвентаря, хотбар
        // и нижнее поле. Литерал «на глаз» оставлял под панелью пустоту.
        float panelH = 60f + chestRows * (slot + gap) + 44f
                + 3 * (slot + gap) + 12f + slot + 22f;
        float panelX = w / 2f - panelW / 2f;
        float panelY = h / 2f - panelH / 2f;
        float gridX = panelX + 22f;
        float chestY = panelY + 60f;
        float invLabelY = chestY + chestRows * (slot + gap) + 26f;
        float invY = invLabelY + 18f;
        float hotbarY = invY + 3 * (slot + gap) + 12f;

        SlotClick action = SlotClick.none();
        com.mineclone.world.ItemStack hovered = null;
        float hoverX = 0, hoverY = 0;

        ui.begin(w, h);
        // Мир за окном и так размыт глубиной резкости — затемнение мягче,
        // а панель прозрачнее: сквозь неё видно матовое стекло.
        ui.quad(0, 0, w, h, 0f, 0f, 0f, ui.hasBackdrop() ? 0.30f : 0.65f);
        ui.glass(panelX, panelY, panelW, panelH);
        ui.quad(panelX, panelY, panelW, panelH, 0.06f, 0.07f, 0.10f, ui.hasBackdrop() ? 0.62f : 0.90f);
        ui.quad(panelX, panelY, panelW, 1.5f, 1f, 1f, 1f, 0.22f);
        ui.quad(panelX, panelY + panelH - 1.5f, panelW, 1.5f, 0f, 0f, 0f, 0.50f);
        ui.quad(panelX, panelY, 1.5f, panelH, 1f, 1f, 1f, 0.10f);
        ui.quad(panelX + panelW - 1.5f, panelY, 1.5f, panelH, 0f, 0f, 0f, 0.35f);

        // Слоты сундука.
        for (int i = 0; i < chest.length; i++) {
            float x = gridX + (i % CHEST_COLS) * (slot + gap);
            float y = chestY + (i / CHEST_COLS) * (slot + gap);
            boolean hov = hov(mx, my, x, y, slot, slot);
            drawSlotBack(x, y, slot, hov);
            if (chest[i] != null)
                drawItemIcon(chest[i], x + 6f, y + 6f, slot - 12f, 1f, hov);
            if (hov) {
                hovered = chest[i];
                hoverX = x;
                hoverY = y;
                if (clicked)
                    action = SlotClick.inContainer(i, false);
                else if (rightClicked)
                    action = SlotClick.inContainer(i, true);
            }
        }

        // Инвентарь игрока: три ряда и хотбар, как в обычном экране.
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++) {
                int index = 9 + row * 9 + col;
                float x = gridX + col * (slot + gap);
                float y = invY + row * (slot + gap);
                boolean hov = hov(mx, my, x, y, slot, slot);
                drawSlotBack(x, y, slot, hov);
                com.mineclone.world.ItemStack st = inv.get(index);
                if (st != null)
                    drawItemIcon(st, x + 6f, y + 6f, slot - 12f, 1f, hov);
                if (hov) {
                    hovered = st;
                    hoverX = x;
                    hoverY = y;
                    if (clicked)
                        action = SlotClick.at(index, false);
                    else if (rightClicked)
                        action = SlotClick.at(index, true);
                }
            }
        for (int col = 0; col < 9; col++) {
            float x = gridX + col * (slot + gap);
            boolean hov = hov(mx, my, x, hotbarY, slot, slot);
            drawSlotBack(x, hotbarY, slot, hov);
            com.mineclone.world.ItemStack st = inv.get(col);
            if (st != null)
                drawItemIcon(st, x + 6f, hotbarY + 6f, slot - 12f, 1f, hov);
            if (col == selectedSlot) {
                ui.quad(x - 3f, hotbarY - 3f, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
                ui.quad(x - 3f, hotbarY + slot, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
                ui.quad(x - 3f, hotbarY, 3f, slot, 1f, 1f, 1f, 0.95f);
                ui.quad(x + slot, hotbarY, 3f, slot, 1f, 1f, 1f, 0.95f);
            }
            if (hov) {
                hovered = st;
                hoverX = x;
                hoverY = hotbarY;
                if (clicked)
                    action = SlotClick.at(col, false);
                else if (rightClicked)
                    action = SlotClick.at(col, true);
            }
        }

        if (cursor != null)
            drawItemIcon(cursor, (float) mx - 18f, (float) my - 18f, 36f, 1f);
        ui.end();

        text.drawShadowed(font, "Chest", panelX + 22f, panelY + 36f, w, h, 1f, 0.94f, 0.82f);
        text.draw(small, "Inventory", gridX, invLabelY, w, h, 0.72f, 0.77f, 0.86f, 1f);
        if (hovered != null) {
            String name = hovered.isTool() ? hovered.displayName() : displayName(hovered.type);
            float twd = font.textWidth(name);
            float tx = Math.min(w - twd - 12f, Math.max(8f, hoverX + 4f));
            text.drawShadowed(font, name, tx, hoverY - 8f, w, h, 1f, 1f, 1f);
        }

        // Счётчики поверх: текст рисуется после ui.end().
        for (int i = 0; i < chest.length; i++) {
            if (chest[i] == null || chest[i].count <= 1)
                continue;
            float x = gridX + (i % CHEST_COLS) * (slot + gap);
            float y = chestY + (i / CHEST_COLS) * (slot + gap);
            drawCount(w, h, chest[i].count, x + 6f, y + 6f, slot - 12f);
        }
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++) {
                com.mineclone.world.ItemStack st = inv.get(9 + row * 9 + col);
                if (st != null)
                    drawCount(w, h, st.count, gridX + col * (slot + gap) + 6f,
                            invY + row * (slot + gap) + 6f, slot - 12f);
            }
        for (int col = 0; col < 9; col++) {
            com.mineclone.world.ItemStack st = inv.get(col);
            if (st != null)
                drawCount(w, h, st.count, gridX + col * (slot + gap) + 6f,
                        hotbarY + 6f, slot - 12f);
        }
        if (cursor != null && cursor.count > 1)
            drawCount(w, h, cursor.count, (float) mx - 18f, (float) my - 18f, 36f);

        return action;
    }

    // ---------------- печь ----------------

    /** Индексы слотов печи в {@link SlotClick}: что плавим, чем топим, что вышло. */
    public static final int FURNACE_INPUT = 0, FURNACE_FUEL = 1, FURNACE_OUTPUT = 2;

    /**
     * Экран печи: три слота, пламя и стрелка прогресса.
     *
     * Пламя и стрелка рисуются заливками, а не спрайтами: обе фигуры — это
     * шкала, и рисовать шкалу текстурой значит держать по кадру на каждое её
     * положение.
     */
    public SlotClick drawFurnace(int w, int h, double mx, double my,
            boolean clicked, boolean rightClicked,
            com.mineclone.world.Furnace furnace,
            com.mineclone.world.Inventory inv, int selectedSlot,
            com.mineclone.world.ItemStack cursor) {
        float slot = 42f, gap = 5f;
        float panelW = 9 * slot + 8 * gap + 44f;
        float topH = 150f;
        float panelH = 60f + topH + 44f + 3 * (slot + gap) + 12f + slot + 22f;
        float panelX = w / 2f - panelW / 2f;
        float panelY = h / 2f - panelH / 2f;
        float gridX = panelX + 22f;
        float topY = panelY + 60f;
        float invLabelY = topY + topH + 26f;
        float invY = invLabelY + 18f;
        float hotbarY = invY + 3 * (slot + gap) + 12f;

        // Три слота печи: что плавим сверху, топливо под ним, результат справа.
        float col = gridX + 72f;
        float inX = col, inY = topY + 4f;
        float fuelX = col, fuelY = topY + 92f;
        float outX = col + 168f, outY = topY + 46f;

        SlotClick action = SlotClick.none();
        com.mineclone.world.ItemStack hovered = null;
        float hoverX = 0, hoverY = 0;

        ui.begin(w, h);
        // Мир за окном и так размыт глубиной резкости — затемнение мягче,
        // а панель прозрачнее: сквозь неё видно матовое стекло.
        ui.quad(0, 0, w, h, 0f, 0f, 0f, ui.hasBackdrop() ? 0.30f : 0.65f);
        ui.glass(panelX, panelY, panelW, panelH);
        ui.quad(panelX, panelY, panelW, panelH, 0.06f, 0.07f, 0.10f, ui.hasBackdrop() ? 0.62f : 0.90f);
        ui.quad(panelX, panelY, panelW, 1.5f, 1f, 1f, 1f, 0.22f);
        ui.quad(panelX, panelY + panelH - 1.5f, panelW, 1.5f, 0f, 0f, 0f, 0.50f);
        ui.quad(panelX, panelY, 1.5f, panelH, 1f, 1f, 1f, 0.10f);
        ui.quad(panelX + panelW - 1.5f, panelY, 1.5f, panelH, 0f, 0f, 0f, 0.35f);

        com.mineclone.world.ItemStack[] slots = {
                furnace.input, furnace.fuel, furnace.output };
        float[] xs = { inX, fuelX, outX };
        float[] ys = { inY, fuelY, outY };
        for (int i = 0; i < 3; i++) {
            boolean hov = hov(mx, my, xs[i], ys[i], slot, slot);
            drawSlotBack(xs[i], ys[i], slot, hov);
            if (slots[i] != null)
                drawItemIcon(slots[i], xs[i] + 6f, ys[i] + 6f, slot - 12f, 1f, hov);
            if (hov) {
                hovered = slots[i];
                hoverX = xs[i];
                hoverY = ys[i];
                if (clicked)
                    action = SlotClick.inContainer(i, false);
                else if (rightClicked)
                    action = SlotClick.inContainer(i, true);
            }
        }

        drawFlame(inX + slot / 2f, fuelY - 14f, furnace.burnFraction());
        drawProgressArrow(inX + slot + 14f, inY + slot / 2f - 6f,
                outX - inX - slot - 28f, furnace.cookFraction());

        // Инвентарь игрока — та же раскладка, что в сундуке.
        for (int row = 0; row < 3; row++)
            for (int c = 0; c < 9; c++) {
                int index = 9 + row * 9 + c;
                float x = gridX + c * (slot + gap);
                float y = invY + row * (slot + gap);
                boolean hov = hov(mx, my, x, y, slot, slot);
                drawSlotBack(x, y, slot, hov);
                com.mineclone.world.ItemStack st = inv.get(index);
                if (st != null)
                    drawItemIcon(st, x + 6f, y + 6f, slot - 12f, 1f, hov);
                if (hov) {
                    hovered = st;
                    hoverX = x;
                    hoverY = y;
                    if (clicked)
                        action = SlotClick.at(index, false);
                    else if (rightClicked)
                        action = SlotClick.at(index, true);
                }
            }
        for (int c = 0; c < 9; c++) {
            float x = gridX + c * (slot + gap);
            boolean hov = hov(mx, my, x, hotbarY, slot, slot);
            drawSlotBack(x, hotbarY, slot, hov);
            com.mineclone.world.ItemStack st = inv.get(c);
            if (st != null)
                drawItemIcon(st, x + 6f, hotbarY + 6f, slot - 12f, 1f, hov);
            if (c == selectedSlot) {
                ui.quad(x - 3f, hotbarY - 3f, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
                ui.quad(x - 3f, hotbarY + slot, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
                ui.quad(x - 3f, hotbarY, 3f, slot, 1f, 1f, 1f, 0.95f);
                ui.quad(x + slot, hotbarY, 3f, slot, 1f, 1f, 1f, 0.95f);
            }
            if (hov) {
                hovered = st;
                hoverX = x;
                hoverY = hotbarY;
                if (clicked)
                    action = SlotClick.at(c, false);
                else if (rightClicked)
                    action = SlotClick.at(c, true);
            }
        }

        if (cursor != null)
            drawItemIcon(cursor, (float) mx - 18f, (float) my - 18f, 36f, 1f);
        ui.end();

        text.drawShadowed(font, "Furnace", panelX + 22f, panelY + 36f, w, h, 1f, 0.94f, 0.82f);
        text.draw(small, "Inventory", gridX, invLabelY, w, h, 0.72f, 0.77f, 0.86f, 1f);
        if (hovered != null) {
            String name = hovered.isTool() ? hovered.displayName() : displayName(hovered.type);
            float twd = font.textWidth(name);
            float tx = Math.min(w - twd - 12f, Math.max(8f, hoverX + 4f));
            text.drawShadowed(font, name, tx, hoverY - 8f, w, h, 1f, 1f, 1f);
        }

        for (int i = 0; i < 3; i++)
            if (slots[i] != null && slots[i].count > 1)
                drawCount(w, h, slots[i].count, xs[i] + 6f, ys[i] + 6f, slot - 12f);
        for (int row = 0; row < 3; row++)
            for (int c = 0; c < 9; c++) {
                com.mineclone.world.ItemStack st = inv.get(9 + row * 9 + c);
                if (st != null)
                    drawCount(w, h, st.count, gridX + c * (slot + gap) + 6f,
                            invY + row * (slot + gap) + 6f, slot - 12f);
            }
        for (int c = 0; c < 9; c++) {
            com.mineclone.world.ItemStack st = inv.get(c);
            if (st != null)
                drawCount(w, h, st.count, gridX + c * (slot + gap) + 6f,
                        hotbarY + 6f, slot - 12f);
        }
        if (cursor != null && cursor.count > 1)
            drawCount(w, h, cursor.count, (float) mx - 18f, (float) my - 18f, 36f);

        return action;
    }

    /** Пламя-шкала: тёмный силуэт, поверх него горящая часть снизу вверх. */
    private void drawFlame(float cx, float bottom, float fill) {
        float w = 20f, h = 26f;
        float x = cx - w / 2f, y = bottom - h;
        // Силуэт — четыре ступени, сужающиеся кверху; настоящее пламя тут и
        // не нужно, нужна читаемая шкала в форме огня.
        float[][] steps = { { 0f, 20f, 8f }, { 3f, 14f, 8f }, { 6f, 8f, 6f }, { 8f, 4f, 4f } };
        for (float[] st : steps)
            ui.quad(x + st[0], y + h - st[2] - stepY(steps, st), st[1], st[2],
                    0.26f, 0.24f, 0.26f, 0.85f);
        if (fill <= 0f)
            return;
        float lit = h * Math.min(1f, fill);
        for (float[] st : steps) {
            float sy = y + h - st[2] - stepY(steps, st);
            float top = y + h - lit;
            if (sy + st[2] <= top)
                continue;
            float visY = Math.max(sy, top);
            float visH = sy + st[2] - visY;
            ui.quad(x + st[0], visY, st[1], visH, 1f, 0.72f, 0.24f, 0.95f);
        }
    }

    /** Смещение ступени пламени снизу — сумма высот тех, что под ней. */
    private static float stepY(float[][] steps, float[] step) {
        float sum = 0f;
        for (float[] s : steps) {
            if (s == step)
                break;
            sum += s[2];
        }
        return sum;
    }

    /** Стрелка прогресса: тёмный жёлоб и заполняющаяся часть. */
    private void drawProgressArrow(float x, float y, float w, float fill) {
        float h = 12f;
        ui.quad(x, y, w, h, 0.10f, 0.11f, 0.14f, 0.85f);
        ui.quad(x, y, w * Math.max(0f, Math.min(1f, fill)), h, 0.96f, 0.93f, 0.85f, 0.9f);
        // Наконечник — треугольник из двух сужающихся полос.
        ui.quad(x + w, y - 4f, 6f, h + 8f, 0.10f, 0.11f, 0.14f, 0.85f);
        ui.quad(x + w + 6f, y - 1f, 5f, h + 2f, 0.10f, 0.11f, 0.14f, 0.85f);
        ui.quad(x + w + 11f, y + 3f, 4f, h - 6f, 0.10f, 0.11f, 0.14f, 0.85f);
    }

    /** Высота полки крафта в экране инвентаря: два ряда слотов. */
    private static final float CRAFT_ROW_H = 121f;
    /**
     * Сколько рецептов помещается в полку.
     *
     * Один ряд из девяти не вмещал таблицу: двенадцать инструментов плюс
     * материалы, и всё, что не влезло, становилось недоступным вообще —
     * рецепт без слота нельзя собрать. Два ряда покрывают таблицу целиком.
     */
    private static final int CRAFT_MAX = 18;

    /**
     * Слот — ниша в стекле, а не приподнятая плитка.
     *
     * Наведение показывается свечением по контуру: подмена цвета заливки
     * терялась на светлых иконках, и было неясно, какой слот под курсором.
     */
    private void drawSlotBack(float x, float y, float size, boolean hover) {
        if (hover)
            ui.quad(x - 2f, y - 2f, size + 4f, size + 4f, 1f, 1f, 1f, 0.17f);
        ui.quad(x, y, size, size,
                hover ? 0.25f : 0.15f, hover ? 0.28f : 0.17f, hover ? 0.34f : 0.22f,
                hover ? 0.66f : 0.48f);
        ui.quad(x, y, size, 1f, 1f, 1f, 1f, hover ? 0.24f : 0.10f);
        ui.quad(x, y + size - 1f, size, 1f, 0f, 0f, 0f, 0.30f);
    }

    /** Draws a stack-count number at the bottom-right of a slot, when count > 1. */
    private void drawCount(int sw, int sh, int count, float slotX, float slotY, float slotSize) {
        if (count <= 1) return;
        String s = Integer.toString(count);
        float cw = small.textWidth(s);
        float tx = slotX + slotSize - cw - 2f;
        float ty = slotY + slotSize - 3f;
        text.drawOutlined(small, s, tx, ty, sw, sh, 1f, 1f, 1f);
    }

    private void drawItemIcon(BlockType b, float x, float y, float size, float alpha) {
        if (b == null || b == BlockType.AIR)
            return;
        if (isCubeIcon(b))
            drawBlockIcon(b, x, y, size, alpha);
        else
            drawTileIcon(b == BlockType.GRASS ? b.topTile : b.sideTile, x, y, size, alpha);
    }

    private void drawItemIcon(com.mineclone.world.ItemStack s, float x, float y,
            float size, float alpha) {
        drawItemIcon(s, x, y, size, alpha, false);
    }

    /**
     * @param spin кубик вращается — выбранный слот или предмет под курсором
     */
    private void drawItemIcon(com.mineclone.world.ItemStack s, float x, float y,
            float size, float alpha, boolean spin) {
        if (s == null)
            return;
        if (!s.isTool() && !s.isFood() && isCubeIcon(s.type))
            drawBlockIcon(s.type, x, y, size, alpha, spin ? ICON_YAW + time * ICON_SPIN : ICON_YAW);
        else
            drawTileIcon(s.iconTile(), x, y, size, alpha);
        if (s.isTool())
            drawDurabilityBar(s, x, y, size);
    }

    /** Часы интерфейса — по ним вращаются кубики. */
    private float time;

    public void setTime(float seconds) {
        this.time = seconds;
    }

    private void drawTileIcon(int tile, float x, float y, float size, float alpha) {
        float[] uv = TextureAtlas.uv(tile);
        ui.quad(x + 3f, y + 4f, size, size, 0f, 0f, 0f, 0.25f * alpha);
        ui.texQuad(x, y, size, size, atlas.getTextureId(),
                uv[0], uv[1], uv[2], uv[3], 1f, 1f, 1f, alpha);
    }

    /**
     * Блок в слоте — изометрическим кубиком, а не плоской гранью.
     *
     * Три параллелограмма: крышка ромбом, левая и правая грани. Яркость
     * граней взята из того же профиля, что печёт мешер
     * ({@code FACE_LIGHT}), поэтому кубик в интерфейсе освещён так же, как
     * блок в мире, и они не выглядят из разных игр.
     *
     * Плоская грань остаётся у всего, что кубом не является: у инструментов,
     * еды, факела и прочих крестов объём только испортил бы силуэт.
     */
    private void drawBlockIcon(BlockType b, float x, float y, float size, float alpha) {
        drawBlockIcon(b, x, y, size, alpha, ICON_YAW);
    }

    /** Поворот кубика в покое: ровно та косая проекция 2:1, что была. */
    public static final float ICON_YAW = 45f;
    /** Скорость вращения кубика в выбранном слоте и под курсором, градусы в секунду. */
    public static final float ICON_SPIN = 55f;
    /** Высота боковой грани и подъём крышки в долях ребра — пропорции прежней иконки. */
    private static final float ICON_SIDE_H = 0.643f, ICON_TILT = 0.5f;

    /**
     * Видимые грани кубика, повёрнутого на {@code yawDeg} вокруг вертикали, в
     * единицах ребра относительно центра иконки (y вниз, как на экране).
     *
     * @return массив граней: {x0,y0, x1,y1, x2,y2, x3,y3, яркость, 0 — крышка / 1 — бок};
     *         углы по кругу, первый — левый верхний угол текстуры
     */
    public static float[][] isoCubeFaces(float yawDeg) {
        double a = Math.toRadians(yawDeg);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        float[][] corner = new float[4][];                  // углы крышки по кругу
        float[][] local = { { -0.5f, -0.5f }, { 0.5f, -0.5f }, { 0.5f, 0.5f }, { -0.5f, 0.5f } };
        for (int i = 0; i < 4; i++) {
            float rx = local[i][0] * c + local[i][1] * s;
            float rz = -local[i][0] * s + local[i][1] * c;
            corner[i] = new float[] { rx, rz };
        }
        java.util.List<float[]> faces = new java.util.ArrayList<>();
        // Бок: ребро между соседними углами крышки, видно, если нормаль
        // смотрит к зрителю (в +Z после поворота).
        for (int i = 0; i < 4; i++) {
            float[] p = corner[i], q = corner[(i + 1) % 4];
            float nx = q[1] - p[1], nz = -(q[0] - p[0]);    // внешняя нормаль ребра
            if (nz <= 1e-4f)
                continue;
            float len = (float) Math.hypot(nx, nz);
            // Свет слева: грань, повёрнутая влево, ярче — как FACE_LIGHT в
            // мире. Нормировка на 45° даёт ровно прежние 0.80 и 0.62.
            float t = Math.max(0f, Math.min(1f, 0.5f - 0.5f * (nx / len) / 0.7071f));
            float light = 0.62f + 0.18f * t;
            // Дальние углы выше на экране, ближние ниже.
            float yTopP = -ICON_SIDE_H * 0.5f + p[1] * ICON_TILT;
            float yTopQ = -ICON_SIDE_H * 0.5f + q[1] * ICON_TILT;
            // У видимой грани обход p→q идёт справа налево: левый верхний
            // угол текстуры — это q, иначе бока выходят зеркальными.
            faces.add(new float[] {
                    q[0], yTopQ, p[0], yTopP,
                    p[0], yTopP + ICON_SIDE_H, q[0], yTopQ + ICON_SIDE_H,
                    light, 1f });
        }
        // Крышка последней: она всегда сверху и всегда видна.
        float[] top = new float[10];
        for (int i = 0; i < 4; i++) {
            top[i * 2] = corner[i][0];
            top[i * 2 + 1] = -ICON_SIDE_H * 0.5f + corner[i][1] * ICON_TILT;
        }
        top[8] = 1f;
        top[9] = 0f;
        faces.add(top);
        return faces.toArray(new float[0][]);
    }

    /**
     * Блок в слоте — изометрическим кубиком, а не плоской гранью.
     *
     * При {@link #ICON_YAW} это ровно прежняя иконка: ромб крышки и две боковые
     * грани с яркостью из профиля мира. В выбранном слоте и под курсором кубик
     * медленно вращается — объём читается, даже если грани одного цвета.
     */
    private void drawBlockIcon(BlockType b, float x, float y, float size, float alpha, float yawDeg) {
        int tid = atlas.getTextureId();
        float[] topUv = TextureAtlas.uv(b.topTile);
        float[] sideUv = TextureAtlas.uv(b.sideTile);
        // Кубик чуть уже слота, чтобы остались поля и цифра количества не
        // наезжала на грань. Масштаб не зависит от поворота — иначе кубик
        // «дышал» бы по ширине, вращаясь.
        float scale = size * 0.88f / (float) Math.sqrt(2.0);
        float cx = x + size / 2f, cy = y + size * 0.46f;

        // Контактная тень: приплюснутый ромб под кубиком.
        float bottom = y + size * 0.88f;
        float rise = size * 0.88f * 0.25f, hw = size * 0.44f;
        float sy = bottom - rise * 0.30f, sh = rise * 0.42f, sw = hw * 1.08f;
        ui.quad4(new float[] { cx - sw, sy, cx, sy - sh, cx + sw, sy, cx, sy + sh },
                0f, 0f, 0f, 0.26f * alpha);

        for (float[] f : isoCubeFaces(yawDeg)) {
            float[] quad = new float[8];
            for (int i = 0; i < 4; i++) {
                quad[i * 2] = cx + f[i * 2] * scale;
                quad[i * 2 + 1] = cy + f[i * 2 + 1] * scale;
            }
            float[] uv = f[9] == 0f ? topUv : sideUv;
            float l = f[8];
            ui.texQuad4(quad, tid, uv[0], uv[1], uv[2], uv[3], l, l, l, alpha);
        }
    }

    /** Куб ли это. Кресты и слои объёмной иконкой только испортишь. */
    private static boolean isCubeIcon(BlockType b) {
        return b != null && b != BlockType.AIR && !b.isCross() && !b.isLayered()
                && b != BlockType.WATER && b != BlockType.WATER_FLOW;
    }

    /**
     * Полоска прочности под иконкой инструмента. Цвет едет от зелёного к
     * красному: числом износ читать некогда, а цветом — мгновенно.
     */
    private void drawDurabilityBar(com.mineclone.world.ItemStack s, float x, float y, float size) {
        float k = s.condition();
        if (k >= 1f)
            return;
        float barH = Math.max(2f, size * 0.10f);
        float by = y + size - barH;
        ui.quad(x, by, size, barH, 0.10f, 0.10f, 0.10f, 0.9f);
        ui.quad(x, by, size * k, barH, 1f - k, 0.15f + 0.75f * k, 0.12f, 1f);
    }

    private static String displayName(BlockType b) {
        String raw = b.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder(raw.length());
        boolean cap = true;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (cap && ch >= 'a' && ch <= 'z') {
                sb.append((char) (ch - 32));
                cap = false;
            } else {
                sb.append(ch);
                cap = ch == ' ';
            }
        }
        return sb.toString();
    }

    /**
     * Творческое меню: сначала все блоки, следом все инструменты.
     *
     * @return выбранная стопка или null
     */
    public com.mineclone.world.ItemStack drawCreativeMenu(int w, int h, double mx, double my,
            boolean clicked, com.mineclone.world.Inventory inv, int selectedSlot) {
        // AIR в список не идёт: ставить его нельзя, а тайла у него нет —
        // в слоте показывалась чужая текстура.
        BlockType[] all = BlockType.values();
        BlockType[] blocks = new BlockType[all.length - 1];
        for (int i = 1; i < all.length; i++)
            blocks[i - 1] = all[i];
        com.mineclone.world.ToolType[] tools = com.mineclone.world.ToolType.VALUES;
        com.mineclone.world.FoodType[] foods = com.mineclone.world.FoodType.VALUES;
        int total = blocks.length + tools.length + foods.length;
        int cols = 9;
        int rows = (int) Math.ceil((double) total / cols);
        float sw = 50f, gap = 8f;
        float tw = cols * sw + (cols - 1) * gap;
        float th = rows * sw + (rows - 1) * gap;
        float startX = w / 2f - tw / 2f;
        float startY = h / 2f - th / 2f;

        ui.begin(w, h);
        ui.quad(0, 0, w, h, 0f, 0f, 0f, 0.8f);

        com.mineclone.world.ItemStack picked = null;
        String hovered = null;
        float hx = 0, hy = 0, hw = 0;

        for (int i = 0; i < total; i++) {
            boolean isTool = i >= blocks.length && i < blocks.length + tools.length;
            boolean isFood = i >= blocks.length + tools.length;
            int tile = isFood ? foods[i - blocks.length - tools.length].tile
                     : isTool ? tools[i - blocks.length].tile
                     : blocks[i].sideTile;
            int c = i % cols;
            int r = i / cols;
            float x = startX + c * (sw + gap);
            float y = startY + r * (sw + gap);

            boolean hov = hov(mx, my, x, y, sw, sw);
            drawSlotBack(x, y, sw, hov);

            float p = 6f; // padding inside slot
            if (isTool || isFood)
                drawTileIcon(tile, x + p, y + p, sw - p * 2, 1f);
            else
                drawItemIcon(blocks[i], x + p, y + p, sw - p * 2, 1f);

            if (hov) {
                hovered = isFood ? foods[i - blocks.length - tools.length].displayName
                         : isTool ? tools[i - blocks.length].displayName
                         : displayName(blocks[i]);
                hx = x;
                hy = y;
                hw = sw;
                if (clicked)
                    picked = isFood
                            ? new com.mineclone.world.ItemStack(
                                    foods[i - blocks.length - tools.length], 1)
                            : isTool
                            ? new com.mineclone.world.ItemStack(tools[i - blocks.length])
                            : new com.mineclone.world.ItemStack(blocks[i], 1);
            }
        }

        // Selected hotbar indicator at the bottom to remind player which slot gets
        // replaced
        float stripW = 9 * (50f + 8f);
        float stripX = w / 2f - stripW / 2f;
        float stripY = h - 80f;
        ui.quad(stripX, stripY, stripW, 60f, 0.05f, 0.06f, 0.09f, 0.62f);
        ui.quad(stripX, stripY, stripW, 1.5f, 1f, 1f, 1f, 0.18f);
        ui.quad(stripX + selectedSlot * 58f, stripY, 58f, 60f, 0.38f, 0.78f, 0.24f, 0.45f);

        ui.end();

        // Draw tooltip
        if (hovered != null) {
            String name = hovered;
            float twd = font.textWidth(name);
            text.drawShadowed(font, name, hx + hw / 2f - twd / 2f, hy - 10f, w, h, 1f, 1f, 1f);
        }

        String title = "Creative Inventory (Press E or ESC to close)";
        float titleW = font.textWidth(title);
        text.drawShadowed(font, title, w / 2f - titleW / 2f, startY - 20f, w, h, 1f, 0.9f, 0.6f);

        return picked;
    }
}
