package com.mineclone.game;

import com.mineclone.render.Font;
import com.mineclone.render.ItemIcons;
import com.mineclone.render.TextRenderer;
import com.mineclone.render.TextureAtlas;
import com.mineclone.render.UiRenderer;
import com.mineclone.world.BlockType;
import org.joml.Vector3f;

/**
 * Игровой интерфейс поверх мира: F3, хотбар, сердца, сытость, компас,
 * подсказки и консоль.
 *
 * <p>Окна инвентаря, сундука, печи и креатива здесь больше не живут — они
 * переехали в {@code ui.container} на общий каркас. Меню, пауза и загрузка
 * живут в {@code com.mineclone.ui}.
 */
public class Hud {

    private final Font font;
    private final Font small;
    private final TextRenderer text;
    private final UiRenderer ui;
    private final TextureAtlas atlas;
    private final ItemIcons icons;

    public Hud(Font font, Font small, TextRenderer text, UiRenderer ui, TextureAtlas atlas) {
        this.font = font;
        this.small = small != null ? small : font;
        this.text = text;
        this.ui = ui;
        this.atlas = atlas;
        this.icons = new ItemIcons(ui, atlas);
        this.icons.setFont(this.small);
    }

    /** Иконки предметов — их же рисуют окна инвентаря. */
    public ItemIcons icons() {
        return icons;
    }

    // ---------------- F3 debug overlay ----------------

    public void drawDebug(int screenW, int screenH, int fps, Vector3f pos,
            int chunkX, int chunkZ, int loadedChunks, int drawnChunks,
            BlockType target, byte targetMeta, boolean wireframe, int skyLight, int blockLight,
            String biome, int mobCount,
            com.mineclone.game.FrameProfiler profiler, int chunkQueue, String music) {
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
                "Music: " + music,
                // Счётчик прошлого кадра: свой собственный оверлей в него
                // попасть уже не успел бы, а мерить надо интерфейс, а не себя.
                "UI: " + com.mineclone.render.UiRenderer.lastFrameDrawCalls() + " draw calls",
        };
        ui.begin(screenW, screenH);
        for (String s : lines) {
            text.drawShadowed(font, s, 8, y, screenW, screenH, 1f, 1f, 1f);
            y += lineH;
        }
        ui.end();
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
            if (stack != null) {
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
        // Счётчики — в том же пакете, что иконки под ними: порядок внутри
        // пакета и есть порядок слоёв, и цифра всё равно ложится поверх.
        for (int i = 0; i < n; i++) {
            float x = x0 + i * (slot + pad);
            com.mineclone.world.ItemStack s = hotbar.get(i);
            if (s != null && s.count > 1)
                drawCount(screenW, screenH, s.count, x, y0, slot);
        }
        ui.end();
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

    private void drawCount(int sw, int sh, int count, float slotX, float slotY, float slotSize) {
        icons.drawCount(text, small, count, slotX, slotY, slotSize, sw, sh);
    }

    /** @param spin кубик вращается — выбранный слот или предмет под курсором */
    private void drawItemIcon(com.mineclone.world.ItemStack s, float x, float y,
            float size, float alpha, boolean spin) {
        icons.draw(s, x, y, size, alpha,
                spin ? ItemIcons.ICON_YAW + time * ItemIcons.ICON_SPIN : ItemIcons.ICON_YAW);
    }

    private void drawItemIcon(com.mineclone.world.ItemStack s, float x, float y,
            float size, float alpha) {
        drawItemIcon(s, x, y, size, alpha, false);
    }

    /** Часы интерфейса — по ним вращаются кубики. */
    private float time;

    public void setTime(float seconds) {
        this.time = seconds;
    }

}
