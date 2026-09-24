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
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;

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

        /** Встать на кадр кинематографа меню и держать его. */
        void menuShot(int index, float phase);

        /** Номер идущего кадра фона, или −1. */
        int menuShotIndex();

        /** Сколько чанков идущего кадра ещё не загружено. */
        int menuMissingChunks();

        /** Сколько пейзажей нашла разведка; ноль — ещё ищет. */
        int menuShotCount();

        /** Остаток кроссфейда между кадрами фона, 1..0. */
        float menuDissolve();

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

        /** Поставить игрока в точку мира. */
        void teleport(float x, float y, float z);

        /** Повернуть взгляд на точку мира. */
        void lookAt(float x, float y, float z);

        /** Сколько чужих игроков видит сессия. */
        int netPlayers();

        /** Мы хозяин комнаты. */
        boolean netIsHost();

        String netRoomName();

        void spawnAnimationMobs();

        int mobTypeMask();

        void equipItem(String id);

        boolean seesHeldItem(String id);

        /** Точка появления мира в блоках: общая у хозяина и участника. */
        int[] spawnBlock();

        /** Имя блока в мире или пусто, если мира нет. */
        String blockAt(int x, int y, int z);

        /** Поставить блок напрямую — как это сделал бы игрок. */
        void setBlockAt(int x, int y, int z, String block);

        /** Где игрок по высоте: ходьба в стену не должна его поднимать. */
        float playerY();

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

        /**
         * Довернуть камеру, градусы. Мимо мыши: замеру нужен ровный поворот,
         * а не правдоподобный ввод.
         */
        void look(float yawDegrees, float pitchDegrees);
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

    /**
     * Сетевой прогон: {@code -Dmineclone.autopilot.net=host|join}.
     *
     * <p>Два процесса, настоящие сокеты, настоящая игра. Хозяин открывает мир
     * комнатой, участник входит, и каждый ставит блок, который обязан
     * появиться у другого. Проверяет ровно то, чего не видит ни один тест без
     * окна: как {@code Game} собирает сессию, строит чужой мир по сиду и
     * применяет чужую правку.
     */
    private static final String NET_MODE = System.getProperty("mineclone.autopilot.net", "");
    private static final int NET_PORT =
            Integer.getInteger("mineclone.autopilot.netPort", com.mineclone.net.LanTransport.DEFAULT_PORT);
    /**
     * Через что идёт сетевой прогон: {@code lan} или {@code photon}.
     *
     * <p>Прямое соединение проверяется всегда — оно ничего не стоит и ни от
     * кого не зависит. Photon поднимается отдельной командой: он тратит
     * бесплатный лимит одновременных игроков и требует интернета.
     */
    private static final String NET_VIA = System.getProperty("mineclone.autopilot.netVia", "lan");
    /**
     * Номер гостя в прогоне против выделенного сервера.
     *
     * <p>Гостей двое, и метки на площадке у них разные: первый ставит свою и
     * ждёт чужую, второй наоборот. Без номера оба ставили бы в одну клетку и
     * проверка «чужой блок дошёл» проходила бы от собственного блока.
     */
    private static final int NET_SLOT = Integer.getInteger("mineclone.autopilot.netSlot", 0);
    /**
     * Регион Photon задан явно, а не «авто»: хозяин и участник обязаны
     * оказаться на одном мастер-сервере, иначе комнаты друг друга они не
     * увидят.
     */
    private static final String NET_REGION = System.getProperty("mineclone.autopilot.netRegion", "eu");

    /**
     * Настройки сети для прогона.
     *
     * <p>Имя комнаты у Photon берётся от порта: он уникален на запуск, и два
     * прогона подряд не наступят друг другу на комнату.
     */
    /**
     * Дольше этого прогон не идёт: зависшая загрузка не должна висеть вечно.
     *
     * <p>Через Photon запас больше: путь до комнаты идёт через три сервера,
     * и каждый шаг — это круг до облака и обратно.
     */
    static final float TIMEOUT = "photon".equals(NET_VIA) ? 240f : 150f;

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

    private static com.mineclone.net.NetSettings netSettings(String address) {
        String who = address.isEmpty() ? "Хозяин" : "Гость";
        if ("photon".equals(NET_VIA))
            return new com.mineclone.net.NetSettings(com.mineclone.net.NetSettings.PHOTON,
                    who, "", NET_REGION, System.getProperty("mineclone.autopilot.netRoom",
                            "autopilot-" + NET_PORT), "", NET_PORT);
        return new com.mineclone.net.NetSettings(com.mineclone.net.NetSettings.LAN,
                who, "", "", "lan", address, NET_PORT);
    }

    /**
     * Пол площадки над точкой появления.
     *
     * <p>Рельеф у точки появления какой угодно — бывает и склон, и нависающая
     * скала. Ровная площадка в воздухе даёт обоим одну высоту, полный свет
     * неба и чистый фон: только на ней видно, где именно стоит чужая модель.
     */
    private static int[] floorAt(Driver d, int dx, int dz) {
        int[] spawn = d.spawnBlock();
        return new int[] { spawn[0] + dx, Math.min(116, spawn[1] + 20), spawn[2] + dz };
    }

    /** Блок-метка обмена: в воздухе над площадкой, чтобы её ни с чем не спутать. */
    private static int[] mark(Driver d, int dx, int dz) {
        int[] f = floorAt(d, dx, dz);
        return new int[] { f[0], f[1] + 3, f[2] };
    }

    /**
     * Метка-факел — на площадке, в стороне от обоих игроков: факел держится
     * только на опоре (BLK-03), а повисший в воздухе падает предметом.
     */
    private static int[] torchMark(Driver d) {
        int[] f = floorAt(d, 1, 2);
        return new int[] { f[0], f[1] + 1, f[2] };
    }

    /** Метка участника выделенного сервера: факел у первого, стекло у второго. */
    private static int[] slotMark(Driver d, int slot) {
        return slot == 0 ? torchMark(d) : mark(d, 2, 2);
    }

    /** Ширина и глубина площадки в блоках. */
    private static final int PAD_X = 10, PAD_Z = 4;
    /** Насколько участник стоит в стороне от хозяина, блоки. */
    private static final int APART = 6;

    /** Высота игрока перед тем, как он пошёл в стену. */
    private static float wallStartY;

    /**
     * Площадка со стеной в два блока прямо по курсу.
     *
     * Два блока, а не один: подъём, о котором идёт речь, выносил игрока
     * именно на высоту такой стены.
     */
    private static void buildWall(Driver d) {
        for (int dx = -2; dx <= 6; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                int[] at = floorAt(d, dx, dz);
                d.setBlockAt(at[0], at[1], at[2], "STONE");
                for (int up = 1; up <= 3; up++)
                    d.setBlockAt(at[0], at[1] + up, at[2], "AIR");
            }
        int[] here = floorAt(d, 0, 0);
        d.teleport(here[0] + 0.5f, here[1] + 1f, here[2] + 0.5f);
        // Стена в четырёх блоках впереди по +X, высотой два.
        for (int dz = -3; dz <= 3; dz++) {
            int[] at = floorAt(d, 4, dz);
            d.setBlockAt(at[0], at[1] + 1, at[2], "STONE");
            d.setBlockAt(at[0], at[1] + 2, at[2], "STONE");
        }
        d.lookAt(here[0] + 8.5f, here[1] + 1f + 1.62f, here[2] + 0.5f);
    }

    private static void buildPad(Driver d) {
        for (int dx = -1; dx < PAD_X; dx++)
            for (int dz = -1; dz < PAD_Z; dz++) {
                int[] at = floorAt(d, dx, dz);
                d.setBlockAt(at[0], at[1], at[2], "STONE");
            }
    }

    /** Встать на площадку в точке (dx, dz) и смотреть в глаза стоящему напротив. */
    private static void standAt(Driver d, int dx, int otherDx) {
        int[] here = floorAt(d, dx, 0);
        d.teleport(here[0] + 0.5f, here[1] + 1f, here[2] + 0.5f);
        int[] there = floorAt(d, otherDx, 0);
        d.lookAt(there[0] + 0.5f, there[1] + 1f + 1.62f, there[2] + 0.5f);
    }

    private static void ok(String what) {
        System.out.println("autopilot: ok - " + what);
    }

    private final List<Step> hostSteps = List.of(
            step("create a world for the room", 1.0f, d -> d.act(MenuAction.create(
                    new WorldSettings("Net room", 20260921L, GameMode.CREATIVE)))),
            when("the world is loaded", 0.5f, d -> in(d, "PLAYING"), d -> ok("world loaded")),
            step("open the room", 0.3f, d -> {
                java.util.List<com.mineclone.save.SaveManager.WorldInfo> worlds =
                        d.save().listWorlds(false);
                if (worlds.isEmpty())
                    throw new IllegalStateException("no world to host");
                d.act(MenuAction.netHost(worlds.get(0).id, netSettings("")));
            }),
            when("the room is open", 0.5f, d -> in(d, "PLAYING") && d.netIsHost(),
                    d -> {
                        d.equipItem("iron_pickaxe");
                        ok("hosting the room");
                        System.out.println("autopilot: room=" + d.netRoomName());
                    }),
            when("a guest arrived", 0.5f, d -> d.netPlayers() >= 1, d -> ok("a guest arrived")),
            step("build a platform", 0.3f, Autopilot::buildPad),
            step("spawn all mob species", 0.3f, Driver::spawnAnimationMobs),
            step("stand on it", 0.6f, d -> standAt(d, 0, APART)),
            step("place a block for the guest", 0.4f, d -> {
                int[] at = torchMark(d);
                d.setBlockAt(at[0], at[1], at[2], "TORCH");
            }),
            when("the guest answered with a block", 0.5f, d -> {
                int[] at = mark(d, 2, 2);
                return "GLASS".equals(d.blockAt(at[0], at[1], at[2]));
            }, d -> ok("the guest's block arrived")),
            when("the guest is standing where it said", 1.2f,
                    d -> d.netPlayers() >= 1, d -> ok("the guest is in place")),
            when("guest equipment arrived", 0.2f, d -> d.seesHeldItem("diamond_axe"),
                    d -> ok("guest equipment replicated")),
            step("look at the guest", 0.3f, d -> standAt(d, 0, APART)),
            step("shoot the host", 0.5f, d -> d.shot("net-host")),
            // Хозяин уходит последним: его выход закрывает комнату, и участник
            // успел бы снять титульный экран вместо мира.
            step("hold the room open", 5.0f, d -> ok("room held open for the guest")));

    private final List<Step> guestSteps = List.of(
            step("join the room", 1.5f,
                    d -> d.act(MenuAction.netJoin(netSettings("127.0.0.1:" + NET_PORT)))),
            when("the host's world is loaded", 0.5f, d -> in(d, "PLAYING"),
                    d -> { d.equipItem("diamond_axe"); ok("joined the world"); }),
            when("the host is visible", 0.5f, d -> d.netPlayers() >= 1 && !d.netIsHost(),
                    d -> ok("the host is visible")),
            when("all mob species arrived", 0.2f,
                    d -> d.mobTypeMask() == (1 << com.mineclone.world.entity.MobType.values().length) - 1,
                    d -> ok("all mob species replicated with valid poses")),
            when("the platform arrived", 0.5f, d -> {
                int[] at = floorAt(d, APART, 0);
                return "STONE".equals(d.blockAt(at[0], at[1], at[2]));
            }, d -> ok("the platform arrived")),
            step("stand on it", 0.4f, d -> standAt(d, APART, 0)),
            when("the host's block arrived", 0.5f, d -> {
                int[] at = torchMark(d);
                return "TORCH".equals(d.blockAt(at[0], at[1], at[2]));
            }, d -> ok("the host's block arrived")),
            step("answer with a block", 0.4f, d -> {
                int[] at = mark(d, 2, 2);
                d.setBlockAt(at[0], at[1], at[2], "GLASS");
            }),
            when("the answer stayed after the host confirmed it", 1.0f, d -> {
                int[] at = mark(d, 2, 2);
                return "GLASS".equals(d.blockAt(at[0], at[1], at[2]));
            }, d -> ok("the host kept the guest's block")),
            when("host equipment arrived", 0.2f, d -> d.seesHeldItem("iron_pickaxe"),
                    d -> ok("host equipment replicated")),
            step("look at the host", 0.3f, d -> standAt(d, APART, 0)),
            step("shoot the guest", 0.5f, d -> d.shot("net-guest")),
            step("hold still", 2.5f, d -> ok("held still for the host")));

    /**
     * Гость выделенного сервера.
     *
     * <p>Отличается от обычного гостя тем, что на той стороне нет игрока: мир
     * строит сервер, площадку строить некому, и всё, что можно проверить, —
     * что мир пришёл, что наша правка пережила подтверждение сервера и что
     * правку соседа сервер до нас донёс.
     */
    private final List<Step> serverGuestSteps = List.of(
            step("join the server", 1.5f,
                    d -> d.act(MenuAction.netJoin(netSettings("127.0.0.1:" + NET_PORT)))),
            when("the server's world is loaded", 0.5f, d -> in(d, "PLAYING"),
                    d -> { d.equipItem(NET_SLOT == 0 ? "iron_pickaxe" : "diamond_axe");
                        ok("joined the server's world"); }),
            when("we are a guest, not a host", 0.3f, d -> !d.netIsHost(),
                    d -> ok("the server owns the world")),
            // Площадку строит гость: у сервера нет рук. Хватает одного слоя
            // под ногами — проверяем обмен, а не строительство.
            step("build a floor", 0.4f, Autopilot::buildPad),
            step("stand on it", 0.5f, d -> standAt(d, NET_SLOT * APART, 0)),
            step("place our own block", 0.4f, d -> {
                int[] at = slotMark(d, NET_SLOT);
                d.setBlockAt(at[0], at[1], at[2], NET_SLOT == 0 ? "TORCH" : "GLASS");
            }),
            when("the server kept our block", 1.5f, d -> {
                int[] at = slotMark(d, NET_SLOT);
                return (NET_SLOT == 0 ? "TORCH" : "GLASS")
                        .equals(d.blockAt(at[0], at[1], at[2]));
            }, d -> ok("the server confirmed our block")),
            when("the other guest's block arrived", 1.0f, d -> {
                int other = NET_SLOT == 0 ? 1 : 0;
                int[] at = slotMark(d, other);
                return (other == 0 ? "TORCH" : "GLASS").equals(d.blockAt(at[0], at[1], at[2]));
            }, d -> ok("the server relayed the other guest's block")),
            when("the other guest is visible", 0.5f, d -> d.netPlayers() >= 1,
                    d -> ok("the other guest is visible")),
            when("equipment relayed through server", 0.2f,
                    d -> d.seesHeldItem(NET_SLOT == 0 ? "diamond_axe" : "iron_pickaxe"),
                    d -> ok("server relayed held equipment")),
            step("shoot the room", 0.5f, d -> d.shot("net-server-" + NET_SLOT)),
            step("hold still", 2.0f, d -> ok("held still")));

    /**
     * Пройти по всем кадрам кинематографа фона и снять каждый с двух
     * точек траектории.
     *
     * <p>Главное здесь не сами снимки, а проверка {@code menuMissingChunks}:
     * показанный кадр обязан отдавать ноль. Математику коридора
     * видимости проверяет обычный тест, а вот что этот коридор действительно
     * успел загрузиться до показа — только живая игра.
     */
    private static List<Step> cinematicSteps() {
        List<Step> out = new java.util.ArrayList<>();
        out.add(when("the scout found the landscapes", 0.2f,
                d -> d.menuShotCount() > 0,
                d -> ok("scout found " + d.menuShotCount() + " landscapes")));
        for (int i = 0; i < 5; i++) {
            final int n = i;
            out.add(step("ask for shot " + n, 0.05f, d -> d.menuShot(n, 0.18f)));
            out.add(when("shot " + n + " loaded whole", 0.4f,
                    d -> d.menuShotIndex() == wrap(d, n) && d.menuMissingChunks() == 0,
                    d -> ok("shot " + n + " shown with no missing chunks")));
            out.add(step("shoot shot " + n, 0.35f, d -> d.shot("bg-" + n + "-start")));
            out.add(step("fly on", 0.05f, d -> d.menuShot(n, 0.84f)));
            out.add(when("shot " + n + " whole at the end too", 0.35f,
                    d -> d.menuMissingChunks() == 0,
                    d -> ok("shot " + n + " end has no missing chunks")));
            out.add(step("shoot the end of shot " + n, 0.35f, d -> d.shot("bg-" + n + "-end")));
        }
        // Наплыв между кадрами — единственное, чего не видно на отдельных
        // снимках: кадр доводится почти до конца и отпускается расписанию.
        out.add(step("bring the last shot to its end", 0.05f, d -> d.menuShot(4, 0.97f)));
        out.add(when("that shot is whole", 0.3f,
                d -> d.menuShotIndex() == wrap(d, 4) && d.menuMissingChunks() == 0,
                d -> ok("the shot before the handover is whole")));
        out.add(step("let the schedule take over", 0.05f, d -> d.menuShot(-1, 0f)));
        out.add(when("the schedule dissolved into the next shot", 0.1f,
                d -> d.menuShotIndex() != wrap(d, 4) && d.menuDissolve() > 0.05f,
                d -> ok("shots dissolve into one another, and the new one is whole")));
        out.add(step("shoot the dissolve", 0.02f, d -> d.shot("bg-dissolve")));
        out.add(step("release the cinematic", 0.1f, d -> d.menuShot(-1, 0f)));
        return out;
    }

    /** Какой номер кадра на самом деле выбрал фон: кадров может быть меньше пяти. */
    private static int wrap(Driver d, int index) {
        return Math.floorMod(index, Math.max(1, d.menuShotCount()));
    }

    private static List<Step> concat(List<Step> a, List<Step> b) {
        List<Step> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    private final List<Step> menuSteps = concat(cinematicSteps(), List.of(
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
            // Ходьба в стену на живой физике: синтетический прогон
            // коллизии ловит не всё, а поднять игрока тут не должно ничем.
            step("build a wall to walk into", 0.4f, Autopilot::buildWall),
            step("walk into the wall", 0.1f, d -> {
                wallStartY = d.playerY();
                d.holdKey(GLFW_KEY_W, true);
            }),
            step("stop walking", 1.6f, d -> d.holdKey(GLFW_KEY_W, false)),
            step("the wall must not lift the player", 0.3f, d -> {
                float rose = d.playerY() - wallStartY;
                if (rose > 0.2f)
                    throw new IllegalStateException(
                            "walking into a wall lifted the player by " + rose + " blocks");
                ok("a wall stops the player instead of lifting them");
            }),
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
            expect("Esc leaves the creative window", 0.6f, "PLAYING")));

    private final Driver d;
    private final List<Step> steps;
    private float clock;
    private float stepClock;
    private int index;
    private boolean finished;

    Autopilot(Driver driver) {
        this.d = driver;
        this.steps = switch (NET_MODE) {
            case "host" -> hostSteps;
            case "join" -> guestSteps;
            case "server" -> serverGuestSteps;
            default -> menuSteps;
        };
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
