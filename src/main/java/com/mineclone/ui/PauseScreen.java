package com.mineclone.ui;

/**
 * Пауза. Мир за ней размыт глубиной резкости, поэтому затемнение лёгкое.
 *
 * <p>После «Сохранить» панель сама подтверждает запись — иначе кнопку жмут
 * второй раз, не зная, сработала ли первая.
 */
public final class PauseScreen implements Screen {

    /** Сколько секунд держится подтверждение сохранения. */
    static final float SAVED_TIME = 2.2f;

    private final String worldName;
    private final SettingsModel settings;
    private float savedTimer;

    public PauseScreen(String worldName, SettingsModel settings) {
        this.worldName = worldName == null ? "" : worldName;
        this.settings = settings;
    }

    @Override
    public MenuAction draw(MenuTheme t) {
        int sw = t.width(), sh = t.height();
        savedTimer = Math.max(0f, savedTimer - t.dt());
        t.dim(0.28f);

        float bw = Math.min(340f, sw - 96f), bh = 46f, gap = 10f;
        float pw = bw + 48f, ph = 88f + 5 * (bh + gap) + 34f;
        float px = (sw - pw) / 2f, py = Math.max(16f, (sh - ph) / 2f);
        t.panel(px, py, pw, ph);
        t.textCentered("Пауза", sw / 2f, py + 42f, MenuTheme.HEADER, 1f);
        t.smallCentered(MenuTheme.ellipsize(t.small(), worldName, pw - 40f), sw / 2f, py + 66f,
                MenuTheme.TEXT_DIM, 1f);

        float bx = px + 24f, by = py + 88f;
        MenuAction result = MenuAction.NONE;
        if (t.button("pause.resume", bx, by, bw, bh, "Вернуться в игру", MenuTheme.Style.PRIMARY, true))
            result = MenuAction.of(MenuAction.Kind.RESUME);
        by += bh + gap;
        if (t.button("pause.save", bx, by, bw, bh, "Сохранить")) {
            result = MenuAction.of(MenuAction.Kind.SAVE);
            savedTimer = SAVED_TIME;
        }
        by += bh + gap;
        if (t.button("pause.settings", bx, by, bw, bh, "Настройки"))
            result = MenuAction.push(new SettingsScreen(settings));
        by += bh + gap;
        if (t.button("pause.menu", bx, by, bw, bh, "В главное меню"))
            result = MenuAction.of(MenuAction.Kind.MAIN_MENU);
        by += bh + gap;
        if (t.button("pause.quit", bx, by, bw, bh, "Выход из игры"))
            result = MenuAction.of(MenuAction.Kind.QUIT);

        if (savedTimer > 0f)
            t.smallCentered("Мир сохранён", sw / 2f, py + ph - 14f, MenuTheme.GOOD,
                    Math.min(1f, savedTimer / 0.4f));
        return result;
    }

    /** Esc из паузы — обратно в игру. */
    @Override
    public MenuAction escape() {
        return MenuAction.of(MenuAction.Kind.RESUME);
    }
}
