package com.mineclone.ui;

/** Экран смерти: красная дымка, возрождение или выход в меню. Esc не закрывает. */
public final class DeathScreen implements Screen {

    private float clock;

    @Override
    public MenuAction draw(MenuTheme t) {
        int sw = t.width(), sh = t.height();
        clock += t.dt();
        // Дымка наплывает, а не падает шторкой.
        float k = Math.min(1f, clock / 0.6f);
        t.quad(0, 0, sw, sh, 0.42f, 0.02f, 0.02f, 0.50f * k);
        t.vignette(k);

        float pw = Math.min(460f, sw - 48f), ph = 246f;
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f;
        t.panel(px, py, pw, ph);
        // Стекло показывает кадр без красной дымки — панель подкрашивается
        // сама, иначе она выглядит окном в другой, спокойный мир.
        t.quad(px, py, pw, ph, 0.40f, 0.03f, 0.03f, 0.30f);
        t.textCentered("Вы погибли", sw / 2f, py + 52f, MenuTheme.DANGER, 1f);
        t.smallCentered("Инвентарь остаётся с вами.", sw / 2f, py + 84f, MenuTheme.TEXT_DIM, 1f);
        t.smallCentered("Возрождение — у точки возрождения мира.", sw / 2f, py + 104f, MenuTheme.TEXT_DIM, 1f);

        float bw = pw - 48f, bh = 46f;
        MenuAction result = MenuAction.NONE;
        if (t.button("death.respawn", px + 24f, py + 128f, bw, bh, "Возродиться", MenuTheme.Style.PRIMARY, true))
            result = MenuAction.of(MenuAction.Kind.RESPAWN);
        if (t.button("death.menu", px + 24f, py + 128f + bh + 10f, bw, bh, "В главное меню"))
            result = MenuAction.of(MenuAction.Kind.MAIN_MENU);
        return result;
    }

    @Override
    public MenuAction escape() {
        return MenuAction.NONE;
    }
}
