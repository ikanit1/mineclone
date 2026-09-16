package com.mineclone.game;

import com.mineclone.ui.KeybindScreen;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.Screen;
import com.mineclone.ui.SettingsScreen;
import com.mineclone.ui.WorldCreateScreen;
import com.mineclone.ui.WorldSelectScreen;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.GameMode;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_H;

/**
 * Автопилот меню в настоящей игре: {@code -Dmineclone.autopilot=<папка>}.
 *
 * <p>Офлайновые снимки экранов проверяют сами экраны, но не то, как игра их
 * собирает: живой фон под стеклом, экран загрузки над фоном, переходы между
 * состояниями, превью мира после сохранения. Автопилот проходит весь путь —
 * фон на закате и ночью, титул, миры, создание, загрузка, игра, пауза,
 * настройки, клавиши, выход в меню и повторный вход в тот же мир — снимает кадр
 * на каждом шаге и закрывает игру с ненулевым кодом, если что-то пошло не так.
 *
 * <p>Экраны открываются и действия подаются напрямую, мимо мыши: щелчки по
 * координатам ломались бы от любой правки раскладки, а сами щелчки уже
 * проверены снимками экранов. А вот Esc жмётся настоящий, через ввод: так
 * нашёлся баг, когда стек меню в том же кадре читал Esc, открывший паузу, и
 * пауза тут же закрывалась.
 */
final class Autopilot {

    /** Что автопилот может попросить у игры. */
    interface Driver {
        String state();

        void open(Screen s);

        void act(MenuAction a);

        /** Нажать клавишу на один кадр через настоящий ввод. */
        void pressKey(int key);

        void shot(String name);

        /** Снимок заказан, но ещё не снят. */
        boolean shotPending();

        void setMenuTime(float gameTime);

        boolean worldHasIcon();

        void quit(int exitCode);

        com.mineclone.save.SaveManager save();

        com.mineclone.ui.SettingsModel settings();

        /** Есть устройство звука и плеер музыки жив. */
        boolean musicAvailable();

        /** Играющий трек или null. */
        String musicTrack();

        /** Позиция играющего трека, секунды. */
        float musicPosition();

        /** Как {@code /music next}: погасить трек и начать другой. */
        void musicNext();

        /** Поставить курсор в виртуальные координаты интерфейса. */
        void mouseAt(float vx, float vy);

        /** Нажать или отпустить кнопку мыши. */
        void mouseButton(int button, boolean down);

        /** Зажать или отпустить клавишу — для сочетаний вроде F3+H. */
        void holdKey(int key, boolean down);

        /** Центр слота открытого окна в виртуальных координатах или null. */
        float[] windowSlotCenter(String groupId, int index);

        com.mineclone.world.Inventory inventory();

        boolean debugShown();

        boolean advancedTooltips();

        /** Переключить режим в творческий — окно креатива открывается только в нём. */
        void setCreative();
    }

    /** Сколько предметов этого вида лежит в инвентаре — для проверок окна. */
    private static int count(Driver d, String id) {
        com.mineclone.world.Inventory inv = d.inventory();
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            com.mineclone.world.ItemStack s = inv.get(i);
            if (s != null && s.item.id.toString().equals("mineclone:" + id))
                n += s.count;
        }
        return n;
    }

    /** Ставит курсор в центр слота окна; падает, если слота нет. */
    private static Step toSlot(String name, float delay, String group, int index) {
        return step(name, delay, d -> {
            float[] c = d.windowSlotCenter(group, index);
            if (c == null)
                throw new IllegalStateException(name + ": no slot " + group + "#" + index);
            d.mouseAt(c[0], c[1]);
        });
    }

    /**
     * Расширенные подсказки до сочетания F3+H.
     *
     * <p>Значение приезжает из options.dat и переживает прогон, поэтому
     * проверять надо переключение, а не «стало включено»: второй запуск
     * подряд иначе падал бы на собственном следе.
     */
    private boolean tooltipsBefore;

    /** Позиция трека на прошлом замере — чтобы проверить, что музыка идёт, а не стоит. */
    private float musicMark = -1f;
    private String musicMarkTrack;

    /**
     * Музыка звучит на самом деле: трек выбран, поток OpenAL его крутит.
     * Без устройства звука (удалённая машина) проверка пропускается, а не падает.
     */
    private Step music(String name, float delay) {
        return step(name, delay, d -> {
            if (!d.musicAvailable()) {
                System.out.println("autopilot: skip - " + name + " (no audio device)");
                return;
            }
            String track = d.musicTrack();
            if (track == null)
                throw new IllegalStateException(name + ": no music is playing");
            float pos = d.musicPosition();
            if (track.equals(musicMarkTrack) && pos <= musicMark + 0.5f)
                throw new IllegalStateException(name + ": " + track + " stands still at " + pos + " s");
            System.out.println("autopilot: ok - " + name + " (" + track + " at " + pos + " s)");
            musicMark = pos;
            musicMarkTrack = track;
        });
    }

    /** Дольше этого прогон не идёт: зависшая загрузка не должна висеть вечно. */
    static final float TIMEOUT = 150f;

    /**
     * Шаг: дождаться условия и паузы после предыдущего шага, сделать дело.
     * Снимок и переход — всегда разные шаги: иначе на снимке проступает
     * первый кадр фейда следующего экрана.
     */
    private record Step(String name, float delay, Predicate<Driver> ready, Consumer<Driver> action) {
    }

    private static Step step(String name, float delay, Consumer<Driver> action) {
        return new Step(name, delay, d -> true, action);
    }

    private static Step when(String name, float delay, Predicate<Driver> ready, Consumer<Driver> action) {
        return new Step(name, delay, ready, action);
    }

    private static Step expect(String name, float delay, String state) {
        return new Step(name, delay, d -> true, d -> {
            if (!state.equals(d.state()))
                throw new IllegalStateException(name + ": expected " + state + ", got " + d.state());
            System.out.println("autopilot: ok - " + name);
        });
    }

    private static boolean in(Driver d, String state) {
        return state.equals(d.state());
    }

    private final List<Step> steps = List.of(
            step("dusk", 2.5f, d -> d.setMenuTime((float) (Math.PI * 0.93))),
            step("shoot dusk", 0.4f, d -> d.shot("00-title-dusk")),
            step("night", 0.1f, d -> d.setMenuTime((float) (Math.PI * 1.5))),
            step("shoot night", 0.4f, d -> d.shot("00-title-night")),
            step("morning", 0.1f, d -> d.setMenuTime(0.55f)),
            step("shoot title", 0.4f, d -> d.shot("01-title")),
            music("menu music is playing", 0.2f),
            music("menu music moves on", 1.5f),
            step("next track", 0.1f, Driver::musicNext),
            step("the next track replaces the old one", 2.0f, d -> {
                if (!d.musicAvailable())
                    return;
                if (d.musicTrack() == null || d.musicTrack().equals(musicMarkTrack))
                    throw new IllegalStateException("next track: still " + d.musicTrack());
                System.out.println("autopilot: ok - next track " + d.musicTrack());
            }),
            music("the next track plays through its fade-in", 1.5f),
            music("the next track moves on", 1.5f),
            step("open worlds", 0.2f, d -> d.open(new WorldSelectScreen(d.save(), d.settings()))),
            step("shoot worlds", 0.8f, d -> d.shot("02-worlds")),
            step("open create", 0.2f, d -> d.open(new WorldCreateScreen(d.save(), d.settings()))),
            step("shoot create", 0.8f, d -> d.shot("03-create")),
            step("create world", 0.2f, d -> d.act(MenuAction.create(
                    new WorldSettings("Автопилот", 20260915L, GameMode.SURVIVAL)))),
            when("shoot loading", 0.3f, d -> in(d, "LOADING") || in(d, "PLAYING"), d -> {
                if (in(d, "LOADING"))
                    d.shot("04-loading");
            }),
            when("shoot play", 3f, d -> in(d, "PLAYING"), d -> d.shot("05-play")),
            step("Esc", 0.2f, d -> d.pressKey(GLFW_KEY_ESCAPE)),
            expect("Esc opens the pause and it stays open", 0.6f, "PAUSED"),
            step("Esc", 0.1f, d -> d.pressKey(GLFW_KEY_ESCAPE)),
            expect("Esc in the pause resumes the game", 0.6f, "PLAYING"),
            step("Esc", 0.2f, d -> d.pressKey(GLFW_KEY_ESCAPE)),
            expect("Esc pauses again", 0.6f, "PAUSED"),
            step("shoot pause", 0.6f, d -> d.shot("06-pause")),
            step("open settings", 0.2f, d -> d.open(new SettingsScreen(d.settings(), 0))),
            step("shoot settings", 0.8f, d -> d.shot("07-settings")),
            step("open keys", 0.2f, d -> d.open(new KeybindScreen(d.settings()))),
            step("shoot keys", 0.8f, d -> d.shot("08-keys")),
            step("Esc closes keys", 0.2f, d -> d.pressKey(GLFW_KEY_ESCAPE)),
            step("Esc closes settings", 0.4f, d -> d.pressKey(GLFW_KEY_ESCAPE)),
            expect("Esc from settings lands in the pause, not the game", 0.6f, "PAUSED"),
            step("main menu", 0.2f, d -> d.act(MenuAction.of(MenuAction.Kind.MAIN_MENU))),
            expect("main menu unloads the world", 0.3f, "MENU"),
            step("shoot title with Continue", 1.2f, d -> d.shot("09-title-continue")),
            step("world icon", 0.5f, d -> {
                if (!d.worldHasIcon())
                    throw new IllegalStateException("world icon was not saved");
                System.out.println("autopilot: ok - world icon saved");
            }),
            music("menu music returns after leaving the world", 3.0f),
            step("continue the same world", 0.2f, d -> {
                java.util.List<com.mineclone.save.SaveManager.WorldInfo> worlds = d.save().listWorlds(false);
                if (worlds.isEmpty())
                    throw new IllegalStateException("continue: no saved world");
                d.act(MenuAction.play(worlds.get(0).id));
            }),
            when("the same world loads a second time", 8f,
                    d -> in(d, "PLAYING"),
                    d -> System.out.println("autopilot: ok - same world loaded a second time")),
            step("pause after re-entry", 0.2f, d -> d.pressKey(GLFW_KEY_ESCAPE)),
            expect("pause opens after re-entry", 0.6f, "PAUSED"),
            step("return to menu again", 0.2f,
                    d -> d.act(MenuAction.of(MenuAction.Kind.MAIN_MENU))),
            expect("second return unloads the world", 0.3f, "MENU"),
            step("create a random-seed world", 0.2f, d -> d.act(MenuAction.create(
                    new WorldSettings("Автопилот случайный", new java.util.Random().nextLong(),
                            GameMode.SURVIVAL)))),
            when("random-seed world loads", 8f,
                    d -> in(d, "PLAYING"),
                    d -> System.out.println("autopilot: ok - random-seed world loaded")),

            // --- окна инвентаря ------------------------------------------
            step("stock the inventory", 0.2f, d -> {
                d.inventory().set(9, com.mineclone.world.ItemStack.of("cobblestone", 10));
                System.out.println("autopilot: ok - ten cobblestone in storage");
            }),
            step("open the inventory", 0.2f, d -> d.pressKey(GLFW_KEY_E)),
            expect("E opens the inventory window", 0.5f, "WINDOW"),
            step("shoot the window", 0.4f, d -> d.shot("10-window-inventory")),

            toSlot("cursor on the stack", 0.2f, "main", 0),
            step("pick the stack up", 0.2f, d -> d.mouseButton(0, true)),
            step("release", 0.1f, d -> d.mouseButton(0, false)),
            step("the stack is on the cursor", 0.2f, d -> {
                if (d.inventory().get(9) != null)
                    throw new IllegalStateException("the slot did not empty");
                System.out.println("autopilot: ok - ten cobblestone on the cursor");
            }),
            toSlot("drag start", 0.1f, "main", 1),
            step("press and hold", 0.1f, d -> d.mouseButton(0, true)),
            toSlot("drag over the second slot", 0.15f, "main", 2),
            toSlot("drag over the third slot", 0.15f, "main", 3),
            step("release the drag", 0.15f, d -> d.mouseButton(0, false)),
            step("the drag split evenly", 0.3f, d -> {
                com.mineclone.world.Inventory inv = d.inventory();
                for (int i = 10; i <= 12; i++) {
                    com.mineclone.world.ItemStack s = inv.get(i);
                    if (s == null || s.count != 3)
                        throw new IllegalStateException("slot " + i + " got "
                                + (s == null ? "nothing" : s.count + "") + ", expected 3");
                }
                System.out.println("autopilot: ok - three slots took three each");
            }),
            step("shoot the drag", 0.3f, d -> d.shot("11-window-drag")),

            step("Esc closes the window", 0.2f, d -> d.pressKey(GLFW_KEY_ESCAPE)),
            expect("Esc returns to the game", 0.6f, "PLAYING"),
            step("the cursor came back", 0.2f, d -> {
                int total = count(d, "cobblestone");
                if (total != 10)
                    throw new IllegalStateException("cobblestone: " + total + ", expected 10");
                System.out.println("autopilot: ok - nothing was lost on close");
            }),

            // --- F3 и его сочетания ---------------------------------------
            step("remember the tooltip setting", 0.2f, d -> tooltipsBefore = d.advancedTooltips()),
            step("hold F3", 0.2f, d -> d.holdKey(GLFW_KEY_F3, true)),
            step("press H", 0.2f, d -> d.pressKey(GLFW_KEY_H)),
            step("release F3", 0.3f, d -> d.holdKey(GLFW_KEY_F3, false)),
            step("F3+H flipped the tooltips, not the debug screen", 0.4f, d -> {
                if (d.advancedTooltips() == tooltipsBefore)
                    throw new IllegalStateException("advanced tooltips did not change");
                if (d.debugShown())
                    throw new IllegalStateException("the debug screen came on as well");
                System.out.println("autopilot: ok - F3+H flipped tooltips to "
                        + d.advancedTooltips() + " without the debug screen");
            }),

            // --- творческое окно -------------------------------------------
            step("switch to creative", 0.2f,
                    d -> d.inventory().set(0, com.mineclone.world.ItemStack.of("stone", 1))),
            step("open the creative window", 0.3f, d -> {
                d.setCreative();
                d.pressKey(GLFW_KEY_E);
            }),
            expect("E opens the creative window", 0.5f, "WINDOW"),
            step("shoot creative", 0.5f, d -> d.shot("12-window-creative")),
            step("Esc closes creative", 0.3f, d -> d.pressKey(GLFW_KEY_ESCAPE)),
            expect("Esc leaves the creative window", 0.6f, "PLAYING"));

    private final Driver d;
    private float clock;
    private float stepClock;
    private int index;
    private boolean finished;

    Autopilot(Driver driver) {
        this.d = driver;
    }

    void update(float dt) {
        if (finished)
            return;
        clock += dt;
        if (clock > TIMEOUT) {
            fail("timeout at step '" + (index < steps.size() ? steps.get(index).name() : "end")
                    + "' in state " + d.state());
            return;
        }
        if (d.shotPending())
            return;   // следующий шаг — только когда кадр предыдущего снят
        stepClock += dt;
        if (index >= steps.size()) {
            finished = true;
            System.out.println("autopilot: all checks passed");
            d.quit(0);
            return;
        }
        Step s = steps.get(index);
        if (!s.ready().test(d)) {
            stepClock = 0f;
            return;
        }
        if (stepClock < s.delay())
            return;
        try {
            s.action().accept(d);
        } catch (IllegalStateException e) {
            fail(e.getMessage());
            return;
        }
        index++;
        stepClock = 0f;
    }

    private void fail(String message) {
        finished = true;
        System.err.println("autopilot: FAILED - " + message);
        d.quit(1);
    }
}
