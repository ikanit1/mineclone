package com.mineclone.ui;

import com.mineclone.render.Font;
import com.mineclone.render.TextRenderer;
import com.mineclone.render.TextureAtlas;
import com.mineclone.render.UiRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * Визуальный язык меню — одно место решает, как выглядит всё.
 *
 * <p>Тот же язык, что у HUD и компаса (ADR hud-glass-and-compass): матовое
 * стекло, тёмная полупрозрачная подложка, светлое ребро сверху и тёмное снизу,
 * наведение — свечение по контуру. Меню и игра больше не выглядят из разных
 * игр.
 *
 * <p>Виджеты немедленного режима: вызов рисует и сразу отвечает, нажали ли.
 * Состояние, которое переживает кадр (пружина наведения, какая кнопка зажата,
 * какое поле в фокусе, какой слайдер тянут), живёт здесь по строковому id.
 *
 * <p>Текст откладывается до {@link #flush()}: квадраты и буквы идут через
 * разные шейдеры, и чередовать их на каждом виджете значило бы переключать
 * программу десятки раз за кадр. Сброс идёт на границах слоёв — перед
 * диалогом, обрезкой и в конце экрана, — поэтому диалог перекрывает текст
 * под собой.
 */
public final class MenuTheme {

    /** Звуки интерфейса: наведение на новую кнопку и нажатие. */
    public interface Sounds {
        void hover();

        void click();

        Sounds SILENT = new Sounds() {
            @Override
            public void hover() {
            }

            @Override
            public void click() {
            }
        };
    }

    public enum Style {
        /** Обычная кнопка. */
        NORMAL,
        /** Главное действие экрана: «Играть», «Создать мир». */
        PRIMARY,
        /** Необратимое: «Удалить». */
        DANGER,
        /** Второстепенная, почти без подложки: вкладка, мелкое действие в строке. */
        QUIET
    }

    // ---- палитра ----
    public static final float[] TEXT = { 0.95f, 0.96f, 0.98f };
    public static final float[] TEXT_DIM = { 0.70f, 0.74f, 0.82f };
    public static final float[] TEXT_FAINT = { 0.48f, 0.52f, 0.60f };
    public static final float[] HEADER = { 1f, 0.94f, 0.82f };
    public static final float[] ACCENT = { 1f, 0.80f, 0.36f };
    public static final float[] DANGER = { 1f, 0.42f, 0.38f };
    public static final float[] GOOD = { 0.52f, 0.88f, 0.46f };

    /** Высота стандартной строки: кнопка, слайдер, поле. */
    public static final float ROW_H = 38f;
    /** Постоянная пружины наведения, 1/с. */
    static final float HOVER_RATE = 16f;
    /** Окно двойного клика, секунды. */
    static final float DOUBLE_CLICK = 0.35f;

    private final UiRenderer ui;
    private final TextRenderer text;
    private final Font font;
    private final Font small;
    private final TextureAtlas atlas;
    private Sounds sounds = Sounds.SILENT;

    private int sw, sh;
    private UiInput in = UiInput.NONE;
    private float time, dt;

    private float alpha = 1f;
    private float offsetY;
    private boolean screenInput = true;
    private boolean localInput = true;

    private final Map<String, Float> anim = new HashMap<>();
    private String pressedId;
    private String dragId;
    private String focusId;
    private String hoverId, lastHoverId;
    private String lastClickId;
    private float lastClickTime = -10f;
    private boolean lastWasDouble;

    private record Txt(Font f, String s, float x, float y, float r, float g, float b, float a, int style) {
    }

    private static final int PLAIN = 0, SHADOW = 1, OUTLINE = 2;
    private final List<Txt> pending = new ArrayList<>();
    private boolean uiOpen;
    private boolean clipping;
    private final int[] viewport = new int[4];

    public MenuTheme(UiRenderer ui, TextRenderer text, Font font, Font small, TextureAtlas atlas) {
        this.ui = ui;
        this.text = text;
        this.font = font;
        this.small = small != null ? small : font;
        this.atlas = atlas;
    }

    public void setSounds(Sounds s) {
        sounds = s != null ? s : Sounds.SILENT;
    }

    // ------------------------------------------------------------------ кадр

    public void begin(int screenW, int screenH, UiInput input, float timeSeconds, float frameDt) {
        sw = screenW;
        sh = screenH;
        in = input != null ? input : UiInput.NONE;
        time = timeSeconds;
        dt = Math.max(0f, frameDt);
        hoverId = null;
        alpha = 1f;
        offsetY = 0f;
        screenInput = true;
        localInput = true;
    }

    public void end() {
        flush();
        if (in.mouseReleased || !in.mouseDown)
            pressedId = null;
        if (!in.mouseDown)
            dragId = null;
        lastHoverId = hoverId;
    }

    /** Экран стека: своя прозрачность, всплытие и право на ввод. */
    public void beginScreen(float screenAlpha, float rise, boolean input) {
        flush();
        alpha = Math.max(0f, Math.min(1f, screenAlpha));
        offsetY = rise;
        screenInput = input;
        localInput = true;
    }

    public void endScreen() {
        flush();
        alpha = 1f;
        offsetY = 0f;
        screenInput = true;
        localInput = true;
    }

    /** Модальность внутри экрана: пока открыт диалог, всё под ним без ввода. */
    public void setInputEnabled(boolean on) {
        localInput = on;
    }

    public boolean inputEnabled() {
        return screenInput && localInput;
    }

    public UiInput input() {
        return in;
    }

    /** Ввод, если экран его сейчас принимает, иначе пустой снимок. */
    public UiInput activeInput() {
        return inputEnabled() ? in : UiInput.NONE;
    }

    public float dt() {
        return dt;
    }

    public float time() {
        return time;
    }

    public int width() {
        return sw;
    }

    public int height() {
        return sh;
    }

    public Font font() {
        return font;
    }

    public Font small() {
        return small;
    }

    public TextureAtlas atlas() {
        return atlas;
    }

    public float alpha() {
        return alpha;
    }

    // ------------------------------------------------------------- примитивы

    private void ensureUi() {
        if (!uiOpen) {
            ui.begin(sw, sh);
            uiOpen = true;
        }
    }

    public void quad(float x, float y, float w, float h, float r, float g, float b, float a) {
        float fa = a * alpha;
        if (fa <= 0.002f || w <= 0f || h <= 0f)
            return;
        ensureUi();
        ui.quad(x, y + offsetY, w, h, r, g, b, fa);
    }

    public void quad(float x, float y, float w, float h, float[] rgb, float a) {
        quad(x, y, w, h, rgb[0], rgb[1], rgb[2], a);
    }

    public void texQuad(float x, float y, float w, float h, int tex,
                        float u0, float v0, float u1, float v1,
                        float r, float g, float b, float a) {
        float fa = a * alpha;
        if (fa <= 0.002f)
            return;
        ensureUi();
        ui.texQuad(x, y + offsetY, w, h, tex, u0, v0, u1, v1, r, g, b, fa);
    }

    /** Тайл атласа прямоугольником. */
    public void tile(int tileIndex, float x, float y, float w, float h, float shade, float a) {
        float[] uv = TextureAtlas.uv(tileIndex);
        texQuad(x, y, w, h, atlas.getTextureId(), uv[0], uv[1], uv[2], uv[3], shade, shade, shade, a);
    }

    public void glass(float x, float y, float w, float h) {
        if (!ui.hasBackdrop())
            return;
        ensureUi();
        ui.glass(x, y + offsetY, w, h, alpha);
    }

    public boolean hasGlass() {
        return ui.hasBackdrop();
    }

    /** Затемнение всего экрана. */
    public void dim(float a) {
        quad(0, -offsetY, sw, sh, 0f, 0f, 0f, a);
    }

    /** Затемнение снизу экрана полосами — подвал с мелким текстом над ярким миром. */
    public void shadeBottom(float height, float a) {
        int steps = 12;
        float band = height / steps;
        for (int i = 0; i < steps; i++)
            quad(0, sh - offsetY - height + i * band, sw, band + 0.5f, 0f, 0f, 0f, a * (i + 1) / steps);
    }

    /**
     * Мягкая виньетка полосами, темнее к краю. Полос много и каждая слабая:
     * при шести ступени читались вложенными рамками.
     */
    public void vignette(float a) {
        int steps = 24;
        float bandX = sw * 0.16f / steps, bandY = sh * 0.20f / steps;
        for (int i = 0; i < steps; i++) {
            float k = a * (1f - i / (float) steps) * 0.09f;
            float ox = i * bandX, oy = i * bandY;
            quad(ox, -offsetY + oy, sw - 2 * ox, bandY, 0f, 0f, 0f, k);
            quad(ox, -offsetY + sh - oy - bandY, sw - 2 * ox, bandY, 0f, 0f, 0f, k);
            quad(ox, -offsetY + oy + bandY, bandX, sh - 2 * oy - 2 * bandY, 0f, 0f, 0f, k);
            quad(sw - ox - bandX, -offsetY + oy + bandY, bandX, sh - 2 * oy - 2 * bandY, 0f, 0f, 0f, k);
        }
    }

    /** Отрисовать отложенный текст. */
    public void flush() {
        // Текст идёт в тот же пакет, что и подложки под ним, но после них:
        // порядок в пакете и есть порядок слоёв, и диалог обязан перекрывать
        // текст, который под ним.
        if (!pending.isEmpty()) {
            if (!uiOpen) {
                ui.begin(sw, sh);
                uiOpen = true;
            }
            drawPending();
        }
        if (uiOpen) {
            ui.end();
            uiOpen = false;
        }
    }

    private void drawPending() {
        for (Txt t : pending) {
            switch (t.style) {
                case SHADOW -> {
                    text.draw(t.f, t.s, t.x + 1, t.y + 1, sw, sh, 0f, 0f, 0f, 0.70f * t.a);
                    text.draw(t.f, t.s, t.x, t.y, sw, sh, t.r, t.g, t.b, t.a);
                }
                case OUTLINE -> {
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dy = -1; dy <= 1; dy++)
                            if (dx != 0 || dy != 0)
                                text.draw(t.f, t.s, t.x + dx, t.y + dy, sw, sh, 0f, 0f, 0f, 0.85f * t.a);
                    text.draw(t.f, t.s, t.x, t.y, sw, sh, t.r, t.g, t.b, t.a);
                }
                default -> text.draw(t.f, t.s, t.x, t.y, sw, sh, t.r, t.g, t.b, t.a);
            }
        }
        pending.clear();
    }

    // ------------------------------------------------------------------ текст

    public float textWidth(String s) {
        return font.textWidth(s);
    }

    public float smallWidth(String s) {
        return small.textWidth(s);
    }

    /** Базовая линия, при которой строка шрифта стоит по центру полосы высотой h. */
    public float baseline(Font f, float y, float h) {
        return y + h / 2f + f.getPixelHeight() * 0.34f;
    }

    private void enqueue(Font f, String s, float x, float y, float[] rgb, float a, int style) {
        if (s == null || s.isEmpty() || a * alpha <= 0.002f)
            return;
        pending.add(new Txt(f, s, x, y + offsetY, rgb[0], rgb[1], rgb[2], a * alpha, style));
    }

    public void text(String s, float x, float baseline, float[] rgb, float a) {
        enqueue(font, s, x, baseline, rgb, a, SHADOW);
    }

    public void textCentered(String s, float cx, float baseline, float[] rgb, float a) {
        enqueue(font, s, cx - font.textWidth(s) / 2f, baseline, rgb, a, SHADOW);
    }

    public void textRight(String s, float right, float baseline, float[] rgb, float a) {
        enqueue(font, s, right - font.textWidth(s), baseline, rgb, a, SHADOW);
    }

    public void smallText(String s, float x, float baseline, float[] rgb, float a) {
        enqueue(small, s, x, baseline, rgb, a, PLAIN);
    }

    public void smallCentered(String s, float cx, float baseline, float[] rgb, float a) {
        enqueue(small, s, cx - small.textWidth(s) / 2f, baseline, rgb, a, PLAIN);
    }

    public void smallRight(String s, float right, float baseline, float[] rgb, float a) {
        enqueue(small, s, right - small.textWidth(s), baseline, rgb, a, PLAIN);
    }

    /** Мелкий текст с тенью — без панели под ним. */
    public void smallShadow(String s, float x, float baseline, float[] rgb, float a) {
        enqueue(small, s, x, baseline, rgb, a, SHADOW);
    }

    /** Текст с обводкой — поверх пёстрого фона без панели. */
    public void textOutlined(String s, float x, float baseline, float[] rgb, float a) {
        enqueue(font, s, x, baseline, rgb, a, OUTLINE);
    }

    /** Обрезать строку многоточием, чтобы она влезла в maxW. */
    public static String ellipsize(Font f, String s, float maxW) {
        if (s == null)
            return "";
        if (f.textWidth(s) <= maxW)
            return s;
        String dots = "…";
        float dw = f.textWidth(dots);
        int end = s.length();
        while (end > 0 && f.textWidth(s.substring(0, end)) + dw > maxW)
            end--;
        return s.substring(0, end) + dots;
    }

    // --------------------------------------------------------------- виджеты

    public boolean hovered(float x, float y, float w, float h) {
        if (!inputEnabled())
            return false;
        float my = in.mouseY - offsetY;
        return in.mouseX >= x && in.mouseX <= x + w && my >= y && my <= y + h;
    }

    /** Значение пружины id, догоняющее цель; 0..1. */
    public float spring(String id, boolean on) {
        float v = anim.getOrDefault(id, 0f);
        float target = on ? 1f : 0f;
        v += (target - v) * (1f - (float) Math.exp(-dt * HOVER_RATE));
        if (Math.abs(target - v) < 0.002f)
            v = target;
        anim.put(id, v);
        return v;
    }

    /** Отметить наведение: звук играет, когда курсор приходит на новую кнопку. */
    private void noteHover(String id) {
        hoverId = id;
        if (!id.equals(lastHoverId))
            sounds.hover();
    }

    /** Нажатие по отпусканию над тем же виджетом — стандартное поведение кнопки. */
    private boolean clickLogic(String id, boolean hov) {
        if (hov && in.mousePressed)
            pressedId = id;
        boolean clicked = hov && in.mouseReleased && id.equals(pressedId);
        if (clicked) {
            lastWasDouble = id.equals(lastClickId) && time - lastClickTime <= DOUBLE_CLICK;
            lastClickId = lastWasDouble ? null : id;
            lastClickTime = time;
        }
        return clicked;
    }

    /** Был ли последний клик, вернувший true, двойным. */
    public boolean wasDoubleClick() {
        return lastWasDouble;
    }

    /** Стеклянная панель: размытый фон, подложка и рёбра. */
    public void panel(float x, float y, float w, float h) {
        glass(x, y, w, h);
        quad(x, y, w, h, 0.055f, 0.062f, 0.085f, hasGlass() ? 0.64f : 0.90f);
        quad(x, y, w, 1.5f, 1f, 1f, 1f, 0.20f);
        quad(x, y + h - 1.5f, w, 1.5f, 0f, 0f, 0f, 0.50f);
        quad(x, y + 1.5f, 1.5f, h - 3f, 1f, 1f, 1f, 0.07f);
        quad(x + w - 1.5f, y + 1.5f, 1.5f, h - 3f, 0f, 0f, 0f, 0.30f);
    }

    /** Заголовок панели и тонкий разделитель под ним. Возвращает y под разделителем. */
    public float header(String title, float x, float y, float w) {
        text(title, x, y + font.getPixelHeight() + 4f, HEADER, 1f);
        float lineY = y + font.getPixelHeight() + 16f;
        quad(x, lineY, w, 1f, 1f, 1f, 1f, 0.12f);
        return lineY + 1f;
    }

    public boolean button(String id, float x, float y, float w, float h, String label) {
        return button(id, x, y, w, h, label, Style.NORMAL, true);
    }

    public boolean button(String id, float x, float y, float w, float h, String label,
                          Style style, boolean enabled) {
        boolean hov = enabled && hovered(x, y, w, h);
        if (hov)
            noteHover(id);
        boolean clicked = enabled && clickLogic(id, hov);
        boolean down = hov && in.mouseDown && id.equals(pressedId);
        float k = spring(id, hov);
        float a = enabled ? 1f : 0.45f;

        float[] base, lit;
        float fillA;
        switch (style) {
            case PRIMARY -> { base = new float[] { 0.19f, 0.39f, 0.21f }; lit = new float[] { 0.27f, 0.53f, 0.29f }; fillA = 0.86f; }
            case DANGER -> { base = new float[] { 0.44f, 0.13f, 0.13f }; lit = new float[] { 0.60f, 0.18f, 0.17f }; fillA = 0.86f; }
            case QUIET -> { base = new float[] { 0.12f, 0.14f, 0.18f }; lit = new float[] { 0.22f, 0.25f, 0.32f }; fillA = 0.42f; }
            default -> { base = new float[] { 0.13f, 0.15f, 0.20f }; lit = new float[] { 0.22f, 0.25f, 0.32f }; fillA = 0.66f; }
        }
        float press = down ? 0.035f : 0f;
        quad(x, y, w, h,
                lerp(base[0], lit[0], k) - press, lerp(base[1], lit[1], k) - press, lerp(base[2], lit[2], k) - press,
                (fillA + (1f - fillA) * 0.25f * k) * a);
        quad(x, y, w, 1.5f, 1f, 1f, 1f, (0.14f + 0.12f * k) * a);
        quad(x, y + h - 1.5f, w, 1.5f, 0f, 0f, 0f, 0.45f * a);
        if (k > 0.01f)
            outline(x - 1.5f, y - 1.5f, w + 3f, h + 3f, 1.5f, ACCENT, 0.85f * k);

        float[] color = style == Style.DANGER && !hov ? new float[] { 1f, 0.86f, 0.84f } : TEXT;
        float lw = font.textWidth(label);
        String shown = lw > w - 16f ? ellipsize(font, label, w - 16f) : label;
        textCentered(shown, x + w / 2f, baseline(font, y, h) + (down ? 1f : 0f), color, a);
        if (clicked)
            sounds.click();
        return clicked;
    }

    /** Рамка из четырёх полос. */
    public void outline(float x, float y, float w, float h, float t, float[] rgb, float a) {
        quad(x, y, w, t, rgb, a);
        quad(x, y + h - t, w, t, rgb, a);
        quad(x, y + t, t, h - 2 * t, rgb, a);
        quad(x + w - t, y + t, t, h - 2 * t, rgb, a);
    }

    /**
     * Строка списка: подложка, наведение и выбор. Содержимое строки рисует
     * экран сам. Возвращает true на клик; двойной — {@link #wasDoubleClick()}.
     */
    public boolean row(String id, float x, float y, float w, float h, boolean selected) {
        boolean hov = hovered(x, y, w, h);
        if (hov)
            noteHover(id);
        boolean clicked = clickLogic(id, hov);
        float k = spring(id, hov);
        float s = spring(id + "#sel", selected);
        quad(x, y, w, h, 0.10f + 0.06f * k + 0.05f * s, 0.12f + 0.06f * k + 0.05f * s,
                0.16f + 0.07f * k + 0.05f * s, 0.34f + 0.22f * k + 0.30f * s);
        quad(x, y, w, 1f, 1f, 1f, 1f, 0.06f + 0.06f * k);
        if (s > 0.01f) {
            quad(x, y, 3f, h, ACCENT, 0.95f * s);
            outline(x, y, w, h, 1.5f, ACCENT, 0.45f * s);
        }
        if (clicked)
            sounds.click();
        return clicked;
    }

    /**
     * Слайдер: подпись слева внутри полосы, значение справа, заполнение
     * акцентом. Возвращает новое положение 0..1.
     */
    public float slider(String id, float x, float y, float w, float h, String label, String value, float t) {
        boolean hov = hovered(x, y, w, h);
        if (hov)
            noteHover(id);
        if (hov && in.mousePressed)
            dragId = id;
        boolean dragging = id.equals(dragId) && in.mouseDown && inputEnabled();
        float nt = Math.max(0f, Math.min(1f, t));
        if (dragging)
            nt = Math.max(0f, Math.min(1f, (in.mouseX - x) / Math.max(1f, w)));
        float k = spring(id, hov || dragging);

        quad(x, y, w, h, 0.05f, 0.06f, 0.09f, 0.78f);
        quad(x, y, w * nt, h, ACCENT, 0.16f + 0.08f * k);
        quad(x, y + h - 3f, w * nt, 3f, ACCENT, 0.90f);
        quad(x, y, w, 1.5f, 1f, 1f, 1f, 0.10f);
        // Бегунок — брусок на нижней дорожке и тонкая риска: полноростовой
        // бегунок перечёркивал подпись посреди слова.
        float kx = x + nt * (w - 10f);
        quad(kx + 4f, y + 3f, 2f, h - 6f, 1f, 1f, 1f, 0.22f + 0.25f * k);
        quad(kx, y + h - 9f, 10f, 9f, 1f, 1f, 1f, 0.80f + 0.20f * k);
        quad(kx, y + h - 2f, 10f, 2f, 0f, 0f, 0f, 0.30f);
        if (k > 0.01f)
            outline(x - 1.5f, y - 1.5f, w + 3f, h + 3f, 1.5f, ACCENT, 0.55f * k);

        float bl = baseline(font, y, h);
        float valueW = font.textWidth(value);
        text(ellipsize(font, label, w - valueW - 44f), x + 14f, bl, TEXT, 1f);
        textRight(value, x + w - 16f, bl, ACCENT, 1f);
        return nt;
    }

    /** Переключатель: подпись и «тумблер» справа. Возвращает новое значение. */
    public boolean toggle(String id, float x, float y, float w, float h, String label, boolean value) {
        boolean hov = hovered(x, y, w, h);
        if (hov)
            noteHover(id);
        boolean clicked = clickLogic(id, hov);
        boolean v = clicked != value;
        float k = spring(id, hov);
        float on = spring(id + "#on", v);

        quad(x, y, w, h, 0.12f + 0.08f * k, 0.14f + 0.08f * k, 0.18f + 0.09f * k, 0.55f + 0.2f * k);
        quad(x, y, w, 1.5f, 1f, 1f, 1f, 0.12f + 0.08f * k);
        quad(x, y + h - 1.5f, w, 1.5f, 0f, 0f, 0f, 0.40f);
        if (k > 0.01f)
            outline(x - 1.5f, y - 1.5f, w + 3f, h + 3f, 1.5f, ACCENT, 0.7f * k);

        float tw = 44f, th = 20f;
        float tx = x + w - tw - 12f, ty = y + (h - th) / 2f;
        quad(tx, ty, tw, th, lerp(0.22f, 0.30f, on), lerp(0.24f, 0.64f, on), lerp(0.29f, 0.32f, on), 0.95f);
        quad(tx, ty, tw, 1f, 0f, 0f, 0f, 0.35f);
        float knob = th - 6f;
        quad(tx + 3f + (tw - knob - 6f) * on, ty + 3f, knob, knob, 0.96f, 0.97f, 0.99f, 1f);

        text(ellipsize(font, label, w - tw - 38f), x + 14f, baseline(font, y, h), TEXT, 1f);
        if (clicked)
            sounds.click();
        return v;
    }

    /** Ряд взаимоисключающих вариантов. Возвращает выбранный индекс. */
    public int segmented(String id, float x, float y, float w, float h, String[] options, int index) {
        int n = options.length;
        float gap = 4f;
        float cw = (w - gap * (n - 1)) / n;
        int result = index;
        for (int i = 0; i < n; i++) {
            float cx = x + i * (cw + gap);
            String cid = id + "#" + i;
            boolean hov = hovered(cx, y, cw, h);
            if (hov)
                noteHover(cid);
            boolean clicked = clickLogic(cid, hov);
            if (clicked && i != index) {
                result = i;
                sounds.click();
            }
            float k = spring(cid, hov);
            float sel = spring(cid + "#sel", i == result);
            quad(cx, y, cw, h,
                    lerp(0.13f + 0.08f * k, 0.19f, sel), lerp(0.15f + 0.08f * k, 0.39f, sel),
                    lerp(0.20f + 0.09f * k, 0.21f, sel), 0.62f + 0.24f * sel);
            quad(cx, y, cw, 1.5f, 1f, 1f, 1f, 0.12f + 0.1f * k);
            quad(cx, y + h - 1.5f, cw, 1.5f, 0f, 0f, 0f, 0.45f);
            if (k > 0.01f)
                outline(cx - 1.5f, y - 1.5f, cw + 3f, h + 3f, 1.5f, ACCENT, 0.7f * k);
            textCentered(ellipsize(font, options[i], cw - 12f), cx + cw / 2f, baseline(font, y, h),
                    i == result ? TEXT : TEXT_DIM, 1f);
        }
        return result;
    }

    public void focus(String id) {
        focusId = id;
    }

    public boolean focused(String id) {
        return id.equals(focusId);
    }

    public void unfocus() {
        focusId = null;
    }

    /**
     * Поле ввода. Фокус — кликом; в фокусе поле забирает набор текста.
     *
     * @return true — в поле нажали Enter
     */
    public boolean textField(String id, float x, float y, float w, float h,
                             TextField field, String placeholder) {
        boolean hov = hovered(x, y, w, h);
        if (hov)
            noteHover(id);
        if (inputEnabled() && in.mousePressed) {
            if (hov)
                focusId = id;
            else if (id.equals(focusId))
                focusId = null;
        }
        boolean focus = id.equals(focusId);
        boolean submitted = focus && inputEnabled() && field.edit(in, dt);
        float k = spring(id, hov || focus);

        quad(x, y, w, h, 0.025f, 0.03f, 0.045f, 0.86f);
        quad(x, y, w, 1f, 0f, 0f, 0f, 0.45f);
        quad(x, y + h - 2f, w, 2f, ACCENT, focus ? 0.95f : 0.22f + 0.25f * k);

        float pad = 12f;
        float bl = baseline(font, y, h);
        String s = field.text();
        if (s.isEmpty() && !focus) {
            text(placeholder, x + pad, bl, TEXT_FAINT, 1f);
        } else {
            // Длинная строка показывает хвост: каретка обычно в конце, и
            // видеть надо то, что набираешь, а не начало.
            String before = s.substring(0, field.caret());
            String visible = s;
            float room = w - 2 * pad;
            int start = 0;
            while (start < s.length() && font.textWidth(s.substring(start, Math.max(start, field.caret()))) > room)
                start++;
            visible = s.substring(start);
            while (font.textWidth(visible) > room && visible.length() > 0)
                visible = visible.substring(0, visible.length() - 1);
            text(visible, x + pad, bl, TEXT, 1f);
            if (focus && (int) (time * 2.2f) % 2 == 0) {
                float cx = x + pad + font.textWidth(before.substring(Math.min(start, before.length())));
                quad(Math.min(cx, x + w - pad), y + 8f, 2f, h - 16f, TEXT, 0.9f);
            }
        }
        return submitted;
    }

    // ------------------------------------------------------------- прокрутка

    /** Обрезать рисование прямоугольником (в виртуальных координатах). */
    public void beginClip(float x, float y, float w, float h) {
        flush();
        glGetIntegerv(GL_VIEWPORT, viewport);
        float sx = viewport[2] / (float) Math.max(1, sw);
        float sy = viewport[3] / (float) Math.max(1, sh);
        int cx = Math.round(x * sx);
        int cy = Math.round((sh - (y + offsetY) - h) * sy);
        glEnable(GL_SCISSOR_TEST);
        glScissor(viewport[0] + cx, viewport[1] + cy, Math.max(0, Math.round(w * sx)), Math.max(0, Math.round(h * sy)));
        clipping = true;
    }

    public void endClip() {
        flush();
        if (clipping)
            glDisable(GL_SCISSOR_TEST);
        clipping = false;
    }

    /**
     * Колесо над областью и полоса прокрутки справа. Зовётся после содержимого.
     */
    public void scrollArea(String id, float x, float y, float w, float h, ScrollState st, float contentH) {
        st.clamp(contentH, h);
        if (hovered(x, y, w, h) && in.scroll != 0f)
            st.scrollBy(-in.scroll * 64f, contentH, h);

        float max = st.maxScroll(contentH, h);
        if (max <= 0f) {
            st.update(dt);
            return;
        }
        float barX = x + w - 6f;
        float thumbH = Math.max(28f, h * h / contentH);
        float thumbY = y + (h - thumbH) * (st.offset() / max);
        boolean hov = hovered(barX - 6f, y, 18f, h);
        if (hov && in.mousePressed)
            dragId = id;
        if (id.equals(dragId) && in.mouseDown && inputEnabled()) {
            float t = (in.mouseY - offsetY - y - thumbH / 2f) / Math.max(1f, h - thumbH);
            st.scrollBy(Math.max(0f, Math.min(1f, t)) * max - st.target(), contentH, h);
            st.snap();
        }
        float k = spring(id, hov || id.equals(dragId));
        quad(barX, y, 6f, h, 1f, 1f, 1f, 0.05f);
        quad(barX, thumbY, 6f, thumbH, 1f, 1f, 1f, 0.26f + 0.24f * k);
        st.update(dt);
    }

    // ------------------------------------------------------------ индикаторы

    /** Логотип из блоков; pixel — размер одного блока буквы. Возвращает высоту. */
    public float logo(String word, float cx, float top, float pixel) {
        BlockLogo.draw(this, word, cx, top, pixel, time);
        return BlockLogo.ROWS * pixel;
    }

    /** Ширина логотипа при этом размере блока. */
    public static float logoWidth(String word, float pixel) {
        return BlockLogo.columns(word) * pixel;
    }

    /** Полоса прогресса с бегущим бликом. */
    public void progressBar(float x, float y, float w, float h, float t) {
        float p = Math.max(0f, Math.min(1f, t));
        quad(x, y, w, h, 0.04f, 0.05f, 0.07f, 0.85f);
        quad(x, y, w, 1f, 0f, 0f, 0f, 0.5f);
        float fw = (w - 4f) * p;
        quad(x + 2f, y + 2f, fw, h - 4f, 0.36f, 0.72f, 0.33f, 0.95f);
        quad(x + 2f, y + 2f, fw, 2f, 0.62f, 0.92f, 0.52f, 0.85f);
        if (fw > 24f) {
            float sweep = (time * 140f) % (fw + 60f) - 30f;
            float sx = Math.max(x + 2f, x + 2f + sweep);
            float ex = Math.min(x + 2f + fw, x + 2f + sweep + 30f);
            if (ex > sx)
                quad(sx, y + 2f, ex - sx, h - 4f, 1f, 1f, 1f, 0.16f);
        }
        quad(x, y + h - 1f, w, 1f, 1f, 1f, 1f, 0.08f);
    }

    /** Крутилка из восьми квадратов с бегущей яркостью. */
    public void spinner(float cx, float cy, float r, float[] rgb) {
        int n = 8;
        float head = (time * 10f) % n;
        for (int i = 0; i < n; i++) {
            double ang = i / (double) n * Math.PI * 2.0;
            float px = cx + (float) Math.cos(ang) * r;
            float py = cy + (float) Math.sin(ang) * r;
            float behind = (head - i + n) % n;
            float a = 0.2f + 0.8f * Math.max(0f, 1f - behind / 5f);
            quad(px - 2.5f, py - 2.5f, 5f, 5f, rgb, a);
        }
    }

    /** Галочка пиксель-артом, size — ширина. */
    public void check(float x, float y, float size, float[] rgb, float a) {
        float p = size / 7f;
        int[][] pts = { { 0, 3 }, { 1, 4 }, { 2, 5 }, { 3, 4 }, { 4, 3 }, { 5, 2 }, { 6, 1 }, { 1, 3 }, { 2, 4 }, { 5, 1 } };
        for (int[] q : pts)
            quad(x + q[0] * p, y + q[1] * p, p + 0.5f, p + 0.5f, rgb, a);
    }

    static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }
}
