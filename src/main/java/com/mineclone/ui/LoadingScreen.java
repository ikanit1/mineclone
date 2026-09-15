package com.mineclone.ui;

import com.mineclone.world.LoadStage;

/**
 * Экран загрузки мира.
 *
 * <p>Вместо одной полосы — этапы конвейера чанков ({@link LoadStage}): что уже
 * готово, что идёт сейчас и сколько его сделано. «87 %» не говорит, почему
 * загрузка встала; «Освещение, 40 %» — говорит. Внизу — совет, который
 * меняется по таймеру.
 *
 * <p>Esc здесь ничего не делает: прерванная на середине загрузка оставила бы
 * мир полусобранным, а ждать её конца — секунды.
 */
public final class LoadingScreen implements Screen {

    /** Состояние строки этапа. */
    public enum Row { DONE, ACTIVE, PENDING }

    /** Как долго держится один совет, секунды. */
    public static final float TIP_SECONDS = 6f;

    /**
     * Советы — только правда об этой игре. Переназначаемые клавиши по имени
     * не называются: после смены раскладки совет врал бы.
     */
    static final String[] TIPS = {
            "F5 переключает вид: из глаз, из-за спины и в лицо.",
            "F6 — фоторежим: свободная камера, колесо мыши ведёт фокус.",
            "F1 прячет интерфейс — удобно для скриншотов.",
            "Нежить обходит освещённые места: дорога в факелах безопаснее.",
            "Зомби горят на солнце и днём ищут тень.",
            "Спальник ночью переводит время к рассвету и переносит точку возрождения.",
            "Жареное мясо вдвое сытнее сырого — печь окупается.",
            "Удар в падении критический: урон в полтора раза выше.",
            "Ударите одного волка — на вас пойдёт вся стая.",
            "Бросок можно зарядить: чем дольше держите клавишу, тем дальше полетит.",
            "Одна и та же буря в лесу идёт ливнем, а в тундре — метелью.",
            "В новолуние ночь заметно темнее, чем при полной луне.",
            "Вода в мороз замерзает, а лёд у огня тает.",
            "Команда /help в консоли покажет все команды.",
    };

    private static final LoadStage[] ROWS = { LoadStage.GENERATING, LoadStage.LIGHTING, LoadStage.BUILDING };

    private final String worldName;
    private LoadStage stage = LoadStage.GENERATING;
    private final float[] fractions = new float[3];
    private float target, shown;
    private float clock;

    public LoadingScreen(String worldName) {
        this.worldName = worldName == null || worldName.isEmpty() ? "Мир" : worldName;
    }

    /**
     * Свежие данные конвейера.
     *
     * @param generated доля ближнего радиуса, где чанки уже существуют
     * @param lit       доля, где разлит свет
     * @param built     доля, где загружены меши
     */
    public void update(LoadStage stage, float generated, float lit, float built) {
        this.stage = stage;
        fractions[0] = clamp01(generated);
        fractions[1] = clamp01(lit);
        fractions[2] = clamp01(built);
        target = (fractions[0] + fractions[1] + fractions[2]) / 3f;
    }

    public static Row rowState(LoadStage current, LoadStage row) {
        if (current == LoadStage.DONE || row.ordinal() < current.ordinal())
            return Row.DONE;
        return row == current ? Row.ACTIVE : Row.PENDING;
    }

    public static String tip(float time) {
        int i = (int) Math.floor(Math.max(0f, time) / TIP_SECONDS);
        return TIPS[i % TIPS.length];
    }

    @Override
    public MenuAction draw(MenuTheme t) {
        clock += t.dt();
        // Полоса догоняет цель плавно: конвейер отчитывается рывками, а
        // прыгающая полоса выглядит как зависание между прыжками.
        shown += (target - shown) * (1f - (float) Math.exp(-t.dt() * 7f));

        int sw = t.width(), sh = t.height();
        t.dim(0.35f);
        float pw = Math.min(560f, sw - 48f), ph = 292f;
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f - 20f;
        t.panel(px, py, pw, ph);

        float cx = sw / 2f;
        t.textCentered(MenuTheme.ellipsize(t.font(), worldName, pw - 48f), cx, py + 46f, MenuTheme.HEADER, 1f);
        t.smallCentered("Подготовка мира", cx, py + 70f, MenuTheme.TEXT_DIM, 1f);
        t.quad(px + 24f, py + 86f, pw - 48f, 1f, 1f, 1f, 1f, 0.10f);

        float rowY = py + 104f;
        for (int i = 0; i < ROWS.length; i++) {
            float ry = rowY + i * 40f;
            Row state = rowState(stage, ROWS[i]);
            float iconX = px + 36f, iconY = ry + 20f;
            switch (state) {
                case DONE -> t.check(iconX - 9f, iconY - 7f, 18f, MenuTheme.GOOD, 1f);
                case ACTIVE -> t.spinner(iconX, iconY, 8f, MenuTheme.ACCENT);
                default -> t.quad(iconX - 3f, iconY - 3f, 6f, 6f, MenuTheme.TEXT_FAINT, 0.8f);
            }
            float[] color = state == Row.PENDING ? MenuTheme.TEXT_FAINT : MenuTheme.TEXT;
            float bl = t.baseline(t.font(), ry, 40f);
            t.text(ROWS[i].title, px + 64f, bl, color, 1f);
            if (state == Row.DONE)
                t.smallRight("готово", px + pw - 28f, bl - 2f, MenuTheme.GOOD, 1f);
            else if (state == Row.ACTIVE)
                t.smallRight(Math.round(fractions[i] * 100f) + " %", px + pw - 28f, bl - 2f, MenuTheme.ACCENT, 1f);
        }

        float barY = py + ph - 52f;
        t.progressBar(px + 28f, barY, pw - 56f, 16f, shown);
        t.smallCentered(Math.round(shown * 100f) + " %", cx, barY + 36f, MenuTheme.TEXT_DIM, 1f);

        // Совет под панелью: проявляется и гаснет на стыке интервалов, а не
        // подменяется посреди чтения.
        float phase = (clock % TIP_SECONDS) / TIP_SECONDS;
        float tipA = Math.min(1f, Math.min(phase, 1f - phase) * 10f);
        String tip = MenuTheme.ellipsize(t.small(), "Совет: " + tip(clock), sw - 48f);
        t.smallCentered(tip, cx, py + ph + 36f, MenuTheme.TEXT_DIM, tipA);
        return MenuAction.NONE;
    }

    @Override
    public MenuAction escape() {
        return MenuAction.NONE;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
