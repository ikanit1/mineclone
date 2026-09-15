package com.mineclone.game;

import com.mineclone.ui.KeybindScreen;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.Screen;
import com.mineclone.ui.SettingsScreen;
import com.mineclone.ui.WorldCreateScreen;
import com.mineclone.ui.WorldSelectScreen;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.GameMode;

/**
 * Автопилот меню в настоящей игре: {@code -Dmineclone.autopilot=<папка>}.
 *
 * <p>Офлайновые снимки экранов проверяют сами экраны, но не то, как игра их
 * собирает: живой фон под стеклом, экран загрузки над фоном, переходы между
 * состояниями, превью мира после сохранения. Автопилот проходит весь путь —
 * титул, миры, создание, загрузка, игра, пауза, настройки, клавиши, выход в
 * меню — снимает кадр на каждом шаге и закрывает игру.
 *
 * <p>Экраны открываются и действия подаются напрямую, мимо мыши: щелчки по
 * координатам ломались бы от любой правки раскладки, а сами щелчки уже
 * проверены снимками экранов.
 */
final class Autopilot {

    /** Что автопилот может попросить у игры. */
    interface Driver {
        String state();

        void open(Screen s);

        void act(MenuAction a);

        void pause();

        void shot(String name);

        /** Снимок заказан, но ещё не снят. */
        boolean shotPending();

        boolean worldHasIcon();

        void quit(int exitCode);

        com.mineclone.save.SaveManager save();

        com.mineclone.ui.SettingsModel settings();
    }

    /** Дольше этого прогон не идёт: зависшая загрузка не должна висеть вечно. */
    static final float TIMEOUT = 150f;

    private final Driver d;
    private float clock;
    private float stepClock;
    private int step;

    Autopilot(Driver driver) {
        this.d = driver;
    }

    void update(float dt) {
        clock += dt;
        if (d.shotPending())
            return;   // следующий шаг — только когда кадр предыдущего снят
        stepClock += dt;
        if (clock > TIMEOUT) {
            System.err.println("autopilot: timeout at step " + step + " in state " + d.state());
            d.quit(2);
            return;
        }
        switch (step) {
            // Снимок и переход никогда не в одном кадре: иначе на снимке
            // проступает первый кадр фейда следующего экрана.
            case 0 -> after(2.5f, () -> d.shot("01-title"));
            case 1 -> after(0.3f, () -> d.open(new WorldSelectScreen(d.save(), d.settings())));
            case 2 -> after(0.8f, () -> d.shot("02-worlds"));
            case 3 -> after(0.2f, () -> d.open(new WorldCreateScreen(d.save(), d.settings())));
            case 4 -> after(0.8f, () -> d.shot("03-create"));
            case 5 -> after(0.2f, () ->
                    d.act(MenuAction.create(new WorldSettings("Автопилот", 20260915L, GameMode.SURVIVAL))));
            case 6 -> {
                if ("LOADING".equals(d.state()))
                    after(0.3f, () -> d.shot("04-loading"));
                else if ("PLAYING".equals(d.state()))
                    next();   // загрузка успела пройти за один кадр — снимать нечего
            }
            case 7 -> {
                if ("PLAYING".equals(d.state()))
                    after(3f, () -> d.shot("05-play"));
                else
                    stepClock = 0f;
            }
            case 8 -> after(0.2f, d::pause);
            case 9 -> after(1.0f, () -> d.shot("06-pause"));
            case 10 -> after(0.2f, () -> d.open(new SettingsScreen(d.settings(), 0)));
            case 11 -> after(0.8f, () -> d.shot("07-settings"));
            case 12 -> after(0.2f, () -> d.open(new KeybindScreen(d.settings())));
            case 13 -> after(0.8f, () -> d.shot("08-keys"));
            case 14 -> after(0.2f, () -> d.act(MenuAction.of(MenuAction.Kind.MAIN_MENU)));
            case 15 -> after(1.5f, () -> d.shot("09-title-continue"));
            case 16 -> after(0.5f, () -> {
                boolean icon = d.worldHasIcon();
                System.out.println("autopilot: world icon " + (icon ? "saved" : "MISSING"));
                d.quit(icon ? 0 : 1);
            });
            default -> {
            }
        }
    }

    private void after(float seconds, Runnable action) {
        if (stepClock < seconds)
            return;
        action.run();
        next();
    }

    private void next() {
        step++;
        stepClock = 0f;
    }
}
