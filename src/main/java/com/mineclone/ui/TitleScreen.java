package com.mineclone.ui;

import com.mineclone.save.SaveManager;

import java.util.Random;

/**
 * Титульный экран: логотип, «Продолжить» последний мир и три пути дальше.
 *
 * <p>«Продолжить» — мир, в который играли последним, одной кнопкой: чаще
 * всего игрок открывает игру ровно ради него, и путь через список миров
 * отнимал бы два лишних щелчка каждый запуск.
 */
public final class TitleScreen implements Screen {

    public static final String VERSION = "v0.9.0 alpha";

    /** Строка под логотипом. Своя на каждый запуск. */
    static final String[] SPLASHES = {
            "Сделано из кубов!",
            "Факел — лучший друг",
            "Волки помнят обиды",
            "Луна меняет фазы",
            "Печь любит уголь",
            "Снег проседает под ногами",
            "Реки ищут море",
            "Ночью не копай вниз",
            "Теперь с живым меню!",
            "Блоки не кончаются",
    };

    private final SaveManager save;
    private final SettingsModel settings;
    private final String splash;
    private SaveManager.WorldInfo last;
    /** Настройки сети и куда их вернуть; null — экран сети недоступен. */
    private final com.mineclone.net.NetSettings netSettings;
    private final java.util.function.Consumer<com.mineclone.net.NetSettings> onNetSave;
    /** Лобби Photon: его держит игра, титул только передаёт дальше. */
    private com.mineclone.net.RoomBrowser browser;

    public TitleScreen(SaveManager save, SettingsModel settings) {
        this(save, settings, new Random().nextInt(SPLASHES.length));
    }

    /** С заданной строкой — для предпросмотра, где кадр должен повторяться. */
    public TitleScreen(SaveManager save, SettingsModel settings, int splashIndex) {
        this(save, settings, splashIndex, com.mineclone.net.NetSettings.defaults(), null);
    }

    /** Со случайной строкой и настройками сети — так его создаёт игра. */
    public static TitleScreen withNet(SaveManager save, SettingsModel settings,
            com.mineclone.net.NetSettings net,
            java.util.function.Consumer<com.mineclone.net.NetSettings> onNetSave,
            com.mineclone.net.RoomBrowser browser) {
        TitleScreen s = new TitleScreen(save, settings, new Random().nextInt(SPLASHES.length),
                net, onNetSave);
        s.browser = browser;
        return s;
    }

    /**
     * С настройками сети: игра отдаёт их сюда и забирает обратно изменёнными.
     *
     * <p>Экран сети создаётся титульным, а не игрой, потому что переходы между
     * экранами разбирает стек — игра видит только конечное действие.
     */
    public TitleScreen(SaveManager save, SettingsModel settings, int splashIndex,
            com.mineclone.net.NetSettings netSettings,
            java.util.function.Consumer<com.mineclone.net.NetSettings> onNetSave) {
        this.save = save;
        this.settings = settings;
        this.splash = SPLASHES[Math.floorMod(splashIndex, SPLASHES.length)];
        this.netSettings = netSettings == null
                ? com.mineclone.net.NetSettings.defaults() : netSettings;
        this.onNetSave = onNetSave;
        refresh();
    }

    private void refresh() {
        last = null;
        for (SaveManager.WorldInfo w : save.listWorlds(false))
            if (!w.corrupted) {
                last = w;   // список уже от последнего сыгранного
                break;
            }
    }

    @Override
    public void resumed() {
        refresh();
    }

    @Override
    public MenuAction draw(MenuTheme t) {
        int sw = t.width(), sh = t.height();

        float pixel = Math.max(7f, Math.min(15f, Math.min(sh / 58f, (sw - 80f) / MenuTheme.logoWidth("MINECLONE", 1f))));
        float logoTop = Math.max(28f, sh * 0.12f);
        float logoH = t.logo("MINECLONE", sw / 2f, logoTop, pixel);
        // Строка под логотипом пульсирует: заметна, но не мигает.
        float pulse = 0.78f + 0.22f * (float) Math.sin(t.time() * 3.2f);
        t.textCentered(splash, sw / 2f, logoTop + logoH + pixel * 2.6f, MenuTheme.ACCENT, pulse);

        float bw = Math.min(400f, sw - 88f), bh = 48f, gap = 10f;
        float bx = (sw - bw) / 2f;
        float by = Math.max(logoTop + logoH + pixel * 4.4f, sh * 0.44f);
        MenuAction result = MenuAction.NONE;
        // Кнопки на стеклянной карточке: над ярким небом и туманом одни
        // полупрозрачные кнопки сливались с фоном.
        int buttons = last != null ? 5 : 4;
        t.panel(bx - 20f, by - 20f, bw + 40f, buttons * (bh + gap) - gap + 40f);

        if (last != null) {
            String label = "Продолжить: " + last.displayName;
            if (t.button("title.continue", bx, by, bw, bh, label, MenuTheme.Style.PRIMARY, true))
                result = MenuAction.play(last.id);
            by += bh + gap;
        }
        if (t.button("title.single", bx, by, bw, bh, "Одиночная игра",
                last == null ? MenuTheme.Style.PRIMARY : MenuTheme.Style.NORMAL, true))
            result = MenuAction.push(new WorldSelectScreen(save, settings));
        by += bh + gap;
        if (t.button("title.multiplayer", bx, by, bw, bh, "Игра по сети"))
            result = MenuAction.push(
                    new MultiplayerScreen(netSettings, save, onNetSave, browser));
        by += bh + gap;
        if (t.button("title.settings", bx, by, bw, bh, "Настройки"))
            result = MenuAction.push(new SettingsScreen(settings));
        by += bh + gap;
        if (t.button("title.quit", bx, by, bw, bh, "Выход"))
            result = MenuAction.of(MenuAction.Kind.QUIT);

        float foot = sh - 16f;
        t.shadeBottom(56f, 0.45f);
        t.smallShadow("Mineclone " + VERSION, 16f, foot, MenuTheme.TEXT_DIM, 1f);
        String hint = "F11 — полный экран";
        t.smallShadow(hint, sw - 16f - t.smallWidth(hint), foot, MenuTheme.TEXT_DIM, 1f);
        return result;
    }

    @Override
    public MenuAction escape() {
        return MenuAction.NONE;
    }
}
