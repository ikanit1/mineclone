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

        /** Skip whole days through the live world clock for a terrain recovery check. */
        void advanceDays(int days);

        /** Use the actual console command path and inspect its player-visible answer. */
        void executeCommand(String command);

        String commandToast();

        /** The host's authoritative rule; guests have no Ecology instance. */
        boolean ecologyEnabled();

        /** Повернуть взгляд на точку мира. */
        void lookAt(float x, float y, float z);

        /** Сколько чужих игроков видит сессия. */
        int netPlayers();

        /** Мы хозяин комнаты. */
        boolean netIsHost();

        String netRoomName();

        void spawnAnimationMobs();

        /** Claim a finite rabbit body on the owner; its ordinary brain performs the raid. */
        long spawnRaidRabbit(float x, float y, float z);

        /** Build a new disposable two-cave fixture; never alters a verification result. */
        void prepareLairArena();
        int[] lairAnchor();
        /** Disposable story C preparation and read-only observations; guests only observe. */
        void prepareBurnArena();
        void holdBurnMaterialization(boolean hold);
        int[] burnAnchor();
        /** DIRT grounds, GRASS grounds and new WOOD roots outside the six authored old roots. */
        int[] burnTerrainStats();
        long[] burnAnimalIds();
        long[] spiderIdsNear(float x, float y, float z, float radius);
        /** Current authoritative or S_MOBS pose: x/y/z/health/dead, or null. */
        float[] mobPose(long id);
        /** Aim and stand in reach, leaving damage to the ordinary mouse attack path. */
        boolean aimAtMob(long id);
        int[] otherNest(int oldX, int oldY, int oldZ);
        double gameTime();
        boolean sawChat(String text);

        /** Скот — коровы, свиньи, овцы — в ряд рядом с точкой появления; ни хищников, ни добычи. */
        void spawnAnimals();

        /** Id живых животных в радиусе от точки по горизонтали, по возрастанию (MOB-01, MOB-02). */
        long[] animalIdsNear(float x, float z, float radius);

        /** Творческий режим с полётом: дальний телепорт не кончится падением. */
        void flyInCreative();

        /** Обратно в выживание, на ноги. */
        void walkInSurvival();

        int mobTypeMask();

        void equipItem(String id);

        boolean seesHeldItem(String id);

        /** Точка появления мира в блоках: общая у хозяина и участника. */
        int[] spawnBlock();

        /** Имя блока в мире или пусто, если мира нет. */
        String blockAt(int x, int y, int z);

        /** Visible wear stage stored in the ground metadata. */
        int blockMetaAt(int x, int y, int z);

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
        void setSurvival();
        void command(String command);
        void setBlockMeta(int x,int y,int z,int meta);
        void wearArmor(String material);
        boolean seesArmor(String material);
        void lookAtOtherPlayer();

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
    private static final boolean TRAIL_MODE = Boolean.getBoolean("mineclone.autopilot.trail");
    private static final boolean ECOLOGY_MODE = Boolean.getBoolean("mineclone.autopilot.ecology");
    private static final boolean LAIR_MODE = "lair".equals(System.getProperty("mineclone.autopilot.netScenario", ""));
    private static final boolean BURN_MODE = "burn".equals(System.getProperty("mineclone.autopilot.netScenario", ""));
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

    /** Where the herd stands and which ids it had before the long walk (MOB-01, MOB-02). */
    private static final float HERD_RADIUS = 24f;
    private static float[] herdAt;
    private static long[] herdIds;

    private static boolean herdHere(Driver d) {
        long[] here = d.animalIdsNear(herdAt[0], herdAt[1], HERD_RADIUS);
        for (long id : herdIds)
            if (java.util.Arrays.binarySearch(here, id) < 0)
                return false;
        return true;
    }

    private static boolean herdGone(Driver d) {
        long[] here = d.animalIdsNear(herdAt[0], herdAt[1], HERD_RADIUS);
        for (long id : herdIds)
            if (java.util.Arrays.binarySearch(here, id) >= 0)
                return false;
        return true;
    }

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

    private static void enableEcologyIfRequested(Driver driver) {
        if (!ECOLOGY_MODE) return;
        driver.executeCommand("/ecology on");
        if (!driver.ecologyEnabled() || !"Ecology ON".equals(driver.commandToast()))
            throw new IllegalStateException("The actual owner command did not enable Living World");
        ok("living world owner enabled ecology");
    }

    private static void rejectGuestEcologyIfRequested(Driver driver) {
        if (!ECOLOGY_MODE) return;
        driver.executeCommand("/ecology on");
        if (!"Команды выполняет хозяин".equals(driver.commandToast()))
            throw new IllegalStateException("The actual guest ecology command did not show the owner-only refusal");
        ok("living world guest command refused");
    }

    private final List<Step> hostSteps = List.of(
            step("create a world for the room", 1.0f, d -> d.act(MenuAction.create(
                    new WorldSettings("Net room", 20260921L, GameMode.CREATIVE)))),
            when("the world is loaded", 0.5f, d -> in(d, "PLAYING"), d -> {
                ok("world loaded");
                enableEcologyIfRequested(d);
            }),
            step("open the room", 0.3f, d -> {
                java.util.List<com.mineclone.save.SaveManager.WorldInfo> worlds =
                        d.save().listWorlds(false);
                if (worlds.isEmpty())
                    throw new IllegalStateException("no world to host");
                d.act(MenuAction.netHost(worlds.get(0).id, netSettings("")));
            }),
            when("the room is open", 0.5f, d -> in(d, "PLAYING") && d.netIsHost(),
                    d -> {
                        if (ECOLOGY_MODE) {
                            if (!d.ecologyEnabled())
                                throw new IllegalStateException("Living World rule was lost while opening the room");
                            ok("living world rule survived room opening");
                        }
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
                    d -> {
                        d.equipItem("diamond_axe"); ok("joined the world");
                        rejectGuestEcologyIfRequested(d);
                    }),
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
                        ok("joined the server's world");
                        rejectGuestEcologyIfRequested(d);
                    }),
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

    private long[] lairInitialIds;
    private long lairInitialDay;
    private boolean lairAttacking;
    private boolean lairWatchingClosed;
    private boolean lairDeathWitness;
    private float lairAttackClock;
    private int[] lairNewCell;
    private static int[] lairCell(Driver d, boolean second) {
        int[] anchor = d.lairAnchor(); if (second) anchor[0] += 80; return anchor;
    }
    private static long lairDay(Driver d) { return com.mineclone.sim.ecology.Ecology.day(d.gameTime()); }
    private static long[] lairSpiders(Driver d, boolean second) {
        int[] p = lairCell(d, second); return d.spiderIdsNear(p[0] + .5f, p[1], p[2] + .5f, 24);
    }
    private static void standAtLair(Driver d, boolean midpoint) {
        int[] p = lairCell(d, false); d.flyInCreative();
        d.teleport(p[0] + (midpoint ? 40.5f : 3.5f), p[1] + 2f, p[2] + 4.5f);
        d.lookAt(p[0] + .5f, p[1] + .5f, p[2] + .5f);
    }
    private static boolean lairMark(Driver d, int slot, boolean death) {
        return d.sawChat(lairSignal(slot, death));
    }
    private static void putLairMark(Driver d, int slot, boolean death) {
        d.executeCommand(lairSignal(slot, death));
    }
    private static String lairSignal(int slot, boolean death) { return "[lair-" + (death ? "dead-" : "ready-") + slot + "]"; }
    private boolean lairDead(Driver d, long id) {
        float[] pose = d.mobPose(id); return lairDeathWitness && (pose == null || pose[3] <= 0 || pose[4] != 0);
    }
    private void oldLairClosed(Driver d, boolean requireDeath) {
        int[] p = lairCell(d, false);
        if (!"AIR".equals(d.blockAt(p[0], p[1], p[2]))) throw new IllegalStateException("Destroyed lair heart reappeared");
        long[] current = lairSpiders(d, false);
        for (long id : current) if (java.util.Arrays.binarySearch(lairInitialIds, id) < 0)
            throw new IllegalStateException("Destroyed territory emitted a new spider identity " + id);
        if (requireDeath && (!lairDead(d, lairInitialIds[0]) || current.length != lairInitialIds.length - 1))
            throw new IllegalStateException("Ordinary spider death did not leave exactly the other original wanderers");
    }
    private List<Step> lairSteps(String role) {
        boolean owner = role.equals("host"), attacker = !owner && (!role.equals("server") || NET_SLOT == 0);
        int slot = owner ? 1 : role.equals("server") ? NET_SLOT : 0;
        var route = new java.util.ArrayList<Step>();
        if (owner) {
            route.add(step("create disposable lair room", 1f, d -> d.act(MenuAction.create(new WorldSettings("Lair net room", 20260921L, GameMode.CREATIVE)))));
            route.add(when("lair owner world loaded", .5f, d -> in(d, "PLAYING"), d -> {
                d.prepareLairArena(); enableEcologyIfRequested(d); standAtLair(d, false);
            }));
            route.add(step("open disposable lair room", .3f, d -> {
                var worlds = d.save().listWorlds(false);
                if (worlds.isEmpty()) throw new IllegalStateException("Lair owner world is absent");
                d.act(MenuAction.netHost(worlds.get(0).id, netSettings("")));
            }));
            route.add(when("lair owner room opened", .5f, d -> in(d, "PLAYING") && d.netIsHost(), d -> {
                if (!d.ecologyEnabled()) throw new IllegalStateException("Living World was lost opening the lair room");
                ok("living world rule survived room opening"); System.out.println("autopilot: room=" + d.netRoomName());
                standAtLair(d, false); d.executeCommand("/time set night");
                if (com.mineclone.sim.WorldClock.daylightAt(d.gameTime()) >= com.mineclone.world.entity.MobSpawner.NIGHT_DAYLIGHT)
                    throw new IllegalStateException("Actual owner night command did not set night");
            }));
            route.add(when("lair guest joined", .3f, d -> d.netPlayers() >= 1, d -> ok("lair actual guest joined")));
        } else {
            route.add(step("join actual lair owner", 1.5f, d -> d.act(MenuAction.netJoin(netSettings("127.0.0.1:" + NET_PORT)))));
            route.add(when("lair guest world loaded", .5f, d -> in(d, "PLAYING"), d -> {
                if (d.netIsHost() || d.ecologyEnabled()) throw new IllegalStateException("Lair guest owns simulation");
                rejectGuestEcologyIfRequested(d); d.equipItem("iron_sword"); standAtLair(d, false);
            }));
        }
        route.add(when("actual nest and four emitted bodies arrived", .5f, d -> {
            int[] p = lairCell(d, false); return "SPIDER_NEST".equals(d.blockAt(p[0], p[1], p[2])) && lairSpiders(d, false).length == 4;
        }, d -> {
            lairInitialIds = lairSpiders(d, false); lairInitialDay = lairDay(d);
            for (long id : lairInitialIds) {
                float[] pose = d.mobPose(id);
                if (id <= 0 || pose == null || pose[3] <= 0) throw new IllegalStateException("Invalid emitted spider snapshot");
            }
            System.out.println("autopilot: lair initial IDs=" + java.util.Arrays.stream(lairInitialIds).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(",")));
            ok(owner ? "lair host saw nest64 and four finite emitted IDs" : "lair guest saw nest64 and four emitted S_MOBS IDs with ecology absent");
            putLairMark(d, slot, false); d.shot("lair-initial-" + role + "-" + slot);
        }));
        route.add(when("both peers acknowledged actual lair bodies", .3f, d -> lairMark(d, 0, false) && lairMark(d, 1, false), d -> {}));
        if (attacker) {
            route.add(step("aim at the real lair heart", .2f, d -> {
                int[] p = lairCell(d, false); d.teleport(p[0] + .6f, p[1] + 2f, p[2] + .5f);
                d.lookAt(p[0] + .5f, p[1] + .5f, p[2] + .5f);
            }));
            route.add(step("break heart with ordinary left input", .2f, d -> d.mouseButton(0, true)));
            route.add(step("release heart input", .15f, d -> d.mouseButton(0, false)));
        }
        route.add(when("host heart destruction reached each peer", .7f, d -> {
            int[] p = lairCell(d, false); return "AIR".equals(d.blockAt(p[0], p[1], p[2]));
        }, d -> {
            oldLairClosed(d, false);
            lairWatchingClosed = true;
            if (lairSpiders(d, false).length != 4) throw new IllegalStateException("Breaking the heart deleted an original wanderer");
            ok(attacker ? "lair heart broken through ordinary input" : "lair heart AIR propagated and original wanderers survived");
            d.shot("lair-broken-" + role + "-" + slot);
        }));
        if (attacker) route.add(step("attack one original wanderer through input", .2f, d -> { lairAttackClock = 0; lairAttacking = true; }));
        route.add(when("ordinary combat death reached each peer", .5f, d -> lairDead(d, lairInitialIds[0]), d -> {
            lairAttacking = false; d.mouseButton(0, false); oldLairClosed(d, true); standAtLair(d, false);
            putLairMark(d, slot, true); ok("lair original wanderer died through ordinary combat and mirrored");
            if (!attacker) ok("lair non-attacker witnessed authoritative dead pose");
        }));
        route.add(when("both peers confirmed the wanderer death", .5f, d -> lairMark(d, 0, true) && lairMark(d, 1, true), d -> {
            standAtLair(d, owner || slot == 1); ok("lair READY next night");
            if (owner) d.executeCommand("/time add 24");
        }));
        route.add(when("actual later night synchronized", .5f, d -> lairDay(d) >= lairInitialDay + 1, d -> {}));
        route.add(step("observe destroyed territory throughout later night", 5f, d -> {
            oldLairClosed(d, true); ok("lair old territory stayed closed next night"); ok("lair READY colonization");
            if (owner) d.executeCommand("/ecology advance 10");
        }));
        route.add(when("real other cave colonized", .7f, d -> {
            int[] p = lairCell(d, false); return lairDay(d) >= lairInitialDay + 11 && d.otherNest(p[0], p[1], p[2]) != null;
        }, d -> {
            int[] p = lairCell(d, false); lairNewCell = d.otherNest(p[0], p[1], p[2]);
            if (!attacker) { d.teleport(lairNewCell[0] + 3.5f, 128.1f, lairNewCell[2] + 4.5f); d.lookAt(lairNewCell[0] + .5f, lairNewCell[1] + .5f, lairNewCell[2] + .5f); }
        }));
        route.add(step("observe the real new colony under normal hostile caps", 3f, d -> {
            oldLairClosed(d, true);
            int[] p = lairNewCell;
            long[] newIds = d.spiderIdsNear(p[0] + .5f, p[1], p[2] + .5f, 24);
            if (newIds.length > 4) throw new IllegalStateException("New colony exceeded its finite emission bound");
            for (long id : newIds) if (java.util.Arrays.binarySearch(lairInitialIds, id) >= 0)
                throw new IllegalStateException("New cave reused an old spider identity");
            System.out.println("autopilot: lair replacement heart=" + p[0] + "," + p[1] + "," + p[2] + " emittedIDs=" + java.util.Arrays.toString(newIds));
            ok("lair other cave colonized after actual ten-day command and heart mirrored");
            d.shot("lair-colonized-" + role + "-" + slot);
        }));
        route.add(step("hold actual lair room for peer", owner ? 8f : 3f, d -> oldLairClosed(d, true)));
        return List.copyOf(route);
    }

    private long[] burnInitialIds;
    private long burnInitialDay, burnReturnedId;
    private int burnInitialDirt;
    private static void burnView(Driver d, boolean away) {
        int[] p = d.burnAnchor(); d.flyInCreative();
        // NORMAL server startup can fill the southern local animal cap before both guests finish loading.
        // Northern observation leaves ordinary cap room without changing populations or removing bodies.
        int side = "server".equals(NET_MODE) ? -1 : 1;
        d.teleport(p[0] + (away ? 0 : 29), p[1] + (away ? 1.01f : 2f), p[2] + (away ? side * 44 : 29));
        d.lookAt(p[0], p[1] + 2, p[2] + (away ? side * 90 : 0));
    }
    private List<Step> burnSteps(String role) {
        boolean owner = role.equals("host"), server = role.equals("server");
        int slot = owner ? 0 : server ? NET_SLOT : 1;
        boolean choosesReturned = owner || server && slot == 0;
        var route = new java.util.ArrayList<Step>();
        if (owner) {
            route.add(step("create disposable burned grove room", 1f, d -> d.act(MenuAction.create(
                    new WorldSettings("Burn scars net room", 20261006L, GameMode.CREATIVE)))));
            route.add(when("burn owner world loaded", .5f, d -> in(d,"PLAYING"), d -> {
                d.prepareBurnArena(); enableEcologyIfRequested(d); burnView(d,false);
            }));
            route.add(step("open actual burned grove room", .3f, d -> {
                var worlds = d.save().listWorlds(false); if (worlds.isEmpty()) throw new IllegalStateException("Burn owner world absent");
                d.act(MenuAction.netHost(worlds.get(0).id,netSettings("")));
            }));
            route.add(when("burn owner room opened", .5f, d -> in(d,"PLAYING")&&d.netIsHost(), d -> {
                if (!d.ecologyEnabled()) throw new IllegalStateException("Burn room opening lost Living World");
                d.holdBurnMaterialization(true);
                ok("living world rule survived room opening");System.out.println("autopilot: room="+d.netRoomName());burnView(d,false);
            }));
            route.add(when("burn real guest joined", .3f, d -> d.netPlayers()>=1,d -> {}));
        } else {
            route.add(step("join actual burned grove authority",1.5f,d -> d.act(MenuAction.netJoin(netSettings("127.0.0.1:"+NET_PORT)))));
            route.add(when("burn guest loaded",.5f,d -> in(d,"PLAYING"),d -> {
                if (d.netIsHost()||d.ecologyEnabled()) throw new IllegalStateException("Burn guest owns Ecology");
                rejectGuestEcologyIfRequested(d);burnView(d,false);
            }));
        }
        route.add(when("actual scorched grove and original finite animal reached peer",.5f,d ->
                d.burnTerrainStats()[0]>=192&&d.burnAnimalIds().length>0,d -> {
            burnInitialDirt=d.burnTerrainStats()[0];burnInitialDay=com.mineclone.sim.ecology.Ecology.day(d.gameTime());
            int[] p=d.burnAnchor();burnInitialIds=d.animalIdsNear(p[0],p[2],Float.MAX_VALUE);
            if (d.burnTerrainStats()[2]!=0) throw new IllegalStateException("Young trees existed before recovery command");
            System.out.println("autopilot: burn initial IDs="+java.util.Arrays.stream(burnInitialIds).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(",")));
            ok(owner?"burn host saw actual scorched ground and finite original animal":"burn guest saw actual scorched ground and original S_MOBS animal with ecology absent");
            d.executeCommand("[burn-ready-"+slot+"]");ok("burn READY seven days");d.shot("burn-scorched-"+role+"-"+slot);
        }));
        route.add(when("both peers acknowledged actual scorched grove",.3f,d -> d.sawChat("[burn-ready-0]")&&d.sawChat("[burn-ready-1]"),d -> {
            if(owner){d.executeCommand("/ecology advance 7");d.executeCommand("/time set noon");d.executeCommand("/ecology info");ok("burn owner actual seven-day command");}
        }));
        route.add(when("day seven grass and grown natural trees replicated",.5f,d ->
                com.mineclone.sim.ecology.Ecology.day(d.gameTime())>=burnInitialDay+7&&d.burnTerrainStats()[2]>0
                        &&d.burnTerrainStats()[0]<burnInitialDirt/2,d -> {
            int[] terrain=d.burnTerrainStats();System.out.println("autopilot: burn recovered terrain dirt="+terrain[0]+" grass="+terrain[1]+" youngTreeRoots="+terrain[2]);
            ok(owner?"burn host day7 grass and scheduled young trees":"burn guest day7 grass and scheduled young trees mirrored");
            d.shot("burn-day-seven-"+role+"-"+slot);burnView(d,true);d.executeCommand("[burn-grown-"+slot+"]");
        }));
        route.add(when("both actual peers look away for finite materialization",.3f,d -> d.sawChat("[burn-grown-0]")&&d.sawChat("[burn-grown-1]"),d -> {burnView(d,true);d.holdBurnMaterialization(false);}));
        route.add(when("ordinary returned animal available on authority and peer",.5f,d -> {
            for(long id:d.burnAnimalIds())if(java.util.Arrays.binarySearch(burnInitialIds,id)<0&&
                    (choosesReturned||d.sawChat("[burn-return-"+id+"]")))return true;return false;
        },d -> {
            for(long id:d.burnAnimalIds())if(java.util.Arrays.binarySearch(burnInitialIds,id)<0&&
                    (choosesReturned||d.sawChat("[burn-return-"+id+"]"))){burnReturnedId=id;break;}
            if(choosesReturned)d.executeCommand("[burn-return-"+burnReturnedId+"]");
            float[] pose=d.mobPose(burnReturnedId);if(pose==null||pose[3]<=0||pose[4]!=0)throw new IllegalStateException("Returned animal has no healthy ordinary S_MOBS pose");
            System.out.println("autopilot: burn returned IDs="+burnReturnedId);
            ok(owner?"burn host ordinary finite animal returned to recovered grove":"burn guest same returned finite animal mirrored with ecology absent");
            d.executeCommand("[burn-return-seen-"+slot+"]");
        }));
        route.add(when("both peers witnessed same returned identity",.5f,d -> d.sawChat("[burn-return-seen-0]")&&d.sawChat("[burn-return-seen-1]"),d -> {
            burnView(d,false);float[] pose=d.mobPose(burnReturnedId);if(pose!=null)d.lookAt(pose[0],pose[1]+.5f,pose[2]);d.shot("burn-animal-return-"+role+"-"+slot);
        }));
        route.add(step("hold recovered grove for other real peer",owner?8f:3f,d -> {
            if(d.burnTerrainStats()[2]<=0)throw new IllegalStateException("Recovered trees vanished");ok("burn finite return proof complete");
        }));
        return List.copyOf(route);
    }

    private static final String[] FORMS={"PLANK_SLAB","COBBLE_SLAB","STONE_SLAB","FENCE","FENCE_GATE","LADDER","TRAPDOOR"};
    private static void placeForms(Driver d){
        for(int i=0;i<FORMS.length;i++){
            int[] p=floorAt(d,2*i-6,3);d.setBlockAt(p[0],p[1],p[2],"STONE");
            d.setBlockAt(p[0]+1,p[1]+1,p[2],"STONE");
            d.setBlockAt(p[0],p[1]+1,p[2],FORMS[i]);if(FORMS[i].equals("LADDER"))d.setBlockMeta(p[0],p[1]+1,p[2],2);
        }
    }
    private static boolean seesForms(Driver d){for(int i=0;i<FORMS.length;i++){int[] p=floorAt(d,2*i-6,3);if(!FORMS[i].equals(d.blockAt(p[0],p[1]+1,p[2])))return false;}return true;}
    private static void prepareClimb(Driver d){int[] p=floorAt(d,-2,-2);d.setBlockAt(p[0],p[1],p[2],"STONE");
        for(int y=1;y<=7;y++){d.setBlockAt(p[0],p[1]+y,p[2],"AIR");d.setBlockAt(p[0]+1,p[1]+y,p[2],"STONE");if(y<=5){d.setBlockAt(p[0],p[1]+y,p[2],"LADDER");d.setBlockMeta(p[0],p[1]+y,p[2],2);}}
        d.teleport(p[0]+.5f,p[1]+1.01f,p[2]+.5f);d.lookAt(p[0]+3,p[1]+2.62f,p[2]+.5f);
    }
    private final List<Step> hostFormsSteps=List.of(
        step("shared seven building forms",.2f,d->{placeForms(d);d.wearArmor("iron");prepareClimb(d);}),
        step("climb ladder through W input",.4f,d->d.holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_W,true)),
        step("ladder climbed",1.1f,d->{d.holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_W,false);if(d.playerY()<floorAt(d,-2,-2)[1]+3.5f)throw new IllegalStateException("ladder did not climb");ok("ladder climbed through real input");standAt(d,0,APART);}),
        when("guest diamond armor arrived",.3f,d->d.seesArmor("diamond"),d->{ok("host sees guest armor");d.lookAtOtherPlayer();d.shot("armor-host");}));
    private final List<Step> guestFormsSteps=List.of(
        when("seven building forms arrived",.3f,Autopilot::seesForms,d->{ok("all seven forms replicated");d.wearArmor("diamond");}),
        when("host iron armor arrived",.3f,d->d.seesArmor("iron"),d->ok("guest sees host armor")),
        step("capture host after ladder climb",2.5f,d->{d.lookAtOtherPlayer();d.shot("armor-guest");}));

    // The raid field is away from the earlier animation-species display and its wolf.
    private static final int RAID_Z = -20;
    private static int[] farmCell(Driver d){return floorAt(d,3,ECOLOGY_MODE?RAID_Z:1);}
    private static int[] protectedRaidCell(Driver d){return floorAt(d,13,RAID_Z);}
    private static int[] raidGate(Driver d){return floorAt(d,3,RAID_Z-2);}
    private static int[] raidReadyMark(Driver d,int slot){return mark(d,18+slot,RAID_Z+8);}
    private static void raidWall(Driver d,int x,int z){
        int[] p=floorAt(d,x,z);
        for(int y=p[1]+1;y<=p[1]+3;y++)d.setBlockAt(p[0],y,p[2],"STONE");
    }
    /** Initial terrain only: neither this fixture nor the driver consumes a crop. */
    private static void prepareRaidArena(Driver d){
        for(int dx=0;dx<=15;dx++)for(int dz=RAID_Z-5;dz<=RAID_Z+4;dz++){
            if(dx>6&&dx<11)continue;
            int[] p=floorAt(d,dx,dz);d.setBlockAt(p[0],p[1],p[2],"GRASS");
            for(int y=p[1]+1;y<128;y++)d.setBlockAt(p[0],y,p[2],"AIR");
        }
        for(int dz=RAID_Z-5;dz<=RAID_Z+4;dz++)for(int dx:new int[]{0,6,11,15})raidWall(d,dx,dz);
        for(int dx=0;dx<=6;dx++){raidWall(d,dx,RAID_Z-5);raidWall(d,dx,RAID_Z+4);if(dx!=3)raidWall(d,dx,RAID_Z-2);}
        for(int dx=11;dx<=15;dx++){raidWall(d,dx,RAID_Z-5);raidWall(d,dx,RAID_Z+4);}
        int[] gate=raidGate(d);d.setBlockAt(gate[0],gate[1]+1,gate[2],"FENCE_GATE");
        int[] p=protectedRaidCell(d);
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)
            d.setBlockAt(p[0]+dx,p[1]+1,p[2]+dz,dx==0&&dz==-1?"FENCE_GATE":"FENCE");
        d.setBlockAt(p[0],p[1],p[2],"FARMLAND");d.setBlockMeta(p[0],p[1],p[2],7);
        d.setBlockAt(p[0],p[1]+1,p[2],"WHEAT");d.setBlockMeta(p[0],p[1]+1,p[2],7);
        d.setBlockAt(p[0]+2,p[1],p[2],"WATER");d.setBlockAt(p[0]-2,p[1]+1,p[2],"TORCH");
    }
    private static void standNearFarm(Driver d){
        int[] p=farmCell(d);d.teleport(p[0]+.5f,p[1]+1.001f,p[2]+2.5f);
    }
    private static void prepareFarm(Driver d){
        if(ECOLOGY_MODE&&d.netIsHost())prepareRaidArena(d);
        int[] p=farmCell(d);d.setBlockAt(p[0],p[1],p[2],"DIRT");
        for(int y=p[1]+1;y<p[1]+12;y++)d.setBlockAt(p[0],y,p[2],"AIR");
        d.setBlockAt(p[0]+1,p[1],p[2],"WATER");d.setBlockAt(p[0]-1,p[1]+1,p[2],"TORCH");
        if(ECOLOGY_MODE)standNearFarm(d);
        d.setSurvival();d.equipItem("iron_hoe");d.lookAt(p[0]+.5f,p[1]+1,p[2]+.5f);
    }
    private static void plantSharedTree(Driver d){
        int[] p=floorAt(d,7,2);d.setBlockAt(p[0],p[1],p[2],"DIRT");
        for(int y=p[1]+1;y<p[1]+14;y++)d.setBlockAt(p[0],y,p[2],"AIR");
        standAt(d,3,APART);d.equipItem("oak_sapling");d.lookAt(p[0]+.5f,p[1]+1,p[2]+.5f);
    }
    private static boolean sharedTreeGrown(Driver d){int[] p=floorAt(d,7,2);return "WOOD".equals(d.blockAt(p[0],p[1]+1,p[2]));}
    private final List<Step> farmHostSteps=List.of(
        step("prepare shared farm",.2f,Autopilot::prepareFarm),
        step("hoe through real right click",.3f,d->d.mouseButton(1,true)),
        step("release hoe",.1f,d->d.mouseButton(1,false)),
        when("host tilled farm",.2f,d->{int[] p=farmCell(d);return "FARMLAND".equals(d.blockAt(p[0],p[1],p[2]));},d->ok("farm tilled through input")),
        step("plant boundary sapling",.2f,Autopilot::plantSharedTree),
        step("sapling through real input",.3f,d->d.mouseButton(1,true)),
        step("release sapling",.1f,d->d.mouseButton(1,false)),
        when("guest seed arrived",.2f,d->{int[] p=farmCell(d);return "WHEAT".equals(d.blockAt(p[0],p[1]+1,p[2]));},d->{ok("guest planted farm");d.command("/time add 48");}),
        when("host crop ripened",.2f,d->{int[] p=farmCell(d);return d.blockMetaAt(p[0],p[1]+1,p[2])==7;},d->{ok("farm mature on host");d.shot("farm-host");}),
        step("hold farm room",8f,d->ok("farm host PASS")));
    private final List<Step> farmServerTillerSteps=List.of(
        step("prepare server farm",.2f,Autopilot::prepareFarm),
        step("hoe on server through input",.3f,d->d.mouseButton(1,true)),
        step("release server hoe",.1f,d->d.mouseButton(1,false)),
        when("server tilled farm",.2f,d->{int[] p=farmCell(d);return "FARMLAND".equals(d.blockAt(p[0],p[1],p[2]));},d->ok("farm tilled on server")),
        step("plant server boundary sapling",.2f,Autopilot::plantSharedTree),
        step("server sapling through input",.3f,d->d.mouseButton(1,true)),
        step("release server sapling",.1f,d->d.mouseButton(1,false)),
        when("server guest planted",.2f,d->{int[] p=farmCell(d);return "WHEAT".equals(d.blockAt(p[0],p[1]+1,p[2]));},d->ok("farm server READY")),
        when("server crop ripened at tiller",.2f,d->{int[] p=farmCell(d);return d.blockMetaAt(p[0],p[1]+1,p[2])==7;},d->{ok("farm mature at server tiller");d.shot("farm-server-tiller");}),
        step("hold server farmer",4f,d->ok("farm server tiller PASS")));
    private final List<Step> farmGuestSteps=List.of(
        when("shared tilled farm arrived",.2f,d->{int[] p=farmCell(d);return "FARMLAND".equals(d.blockAt(p[0],p[1],p[2]));},d->{
            int[] p=farmCell(d);if(ECOLOGY_MODE)standNearFarm(d);d.setSurvival();d.equipItem("wheat_seeds");d.lookAt(p[0]+.5f,p[1]+1,p[2]+.5f);}),
        step("allow mode checkpoint to reach host",2.2f,d->{ }),
        step("guest seeds through right click",.3f,d->d.mouseButton(1,true)),
        step("release seed",.1f,d->d.mouseButton(1,false)),
        when("plant confirmed",.3f,d->{int[] p=farmCell(d);return "WHEAT".equals(d.blockAt(p[0],p[1]+1,p[2])) && count(d,"wheat_seeds")==0;},d->{
            ok("farm guest READY");}),
        when("crop mature at guest",.3f,d->{int[] p=farmCell(d);return d.blockMetaAt(p[0],p[1]+1,p[2])==7;},d->{ok("farm mature on guest");d.shot("farm-guest");}),
        when("boundary tree grew at guest",.2f,Autopilot::sharedTreeGrown,d->ok("boundary sapling tree replicated")),
        step("hold farm guest",2f,d->ok("farm guest PASS")));

    private static boolean raidStages(Driver d,int stage){
        int[] p=farmCell(d),protectedCell=protectedRaidCell(d);
        return "WHEAT".equals(d.blockAt(p[0],p[1]+1,p[2]))&&d.blockMetaAt(p[0],p[1]+1,p[2])==stage
                &&"WHEAT".equals(d.blockAt(protectedCell[0],protectedCell[1]+1,protectedCell[2]))
                &&d.blockMetaAt(protectedCell[0],protectedCell[1]+1,protectedCell[2])==7;
    }
    private static boolean raidReady(Driver d,int slot){int[] p=raidReadyMark(d,slot);return "GLASS".equals(d.blockAt(p[0],p[1],p[2]));}
    private static void retreatFromRaid(Driver d){
        int[] p=floorAt(d,8,RAID_Z+16);d.flyInCreative();
        d.teleport(p[0]+.5f,p[1]+4f,p[2]+.5f);
        int[] crop=farmCell(d);d.lookAt(crop[0]+.5f,crop[1]+1,crop[2]+.5f);
    }
    private static void openRaidGate(Driver d){
        // Preserve irrigation without an exposed swim pocket beside the approach.
        // The mature farm was grown with its original source before release.
        int[] crop=farmCell(d);
        d.setBlockAt(crop[0]+1,crop[1],crop[2],"GRASS");
        d.setBlockAt(crop[0]+3,crop[1],crop[2],"WATER");
        int[] p=raidGate(d);d.setBlockMeta(p[0],p[1]+1,p[2],4);retreatFromRaid(d);
        ok("raid release gate opened");
    }
    private List<Step> raidHostSteps(){return List.of(
        when("both raid crops begin mature",.2f,d->raidStages(d,7),d->ok("raid host saw mature crop stage7")),
        when("guest acknowledged stage7",.2f,d->raidReady(d,0),d->{
            int[] a=farmCell(d),b=protectedRaidCell(d);
            long first=d.spawnRaidRabbit(a[0]+.5f,a[1]+1.001f,a[2]-3.5f);
            long second=d.spawnRaidRabbit(b[0]+.5f,b[1]+1.001f,b[2]-2.5f);
            if(first<=0||second<=0||first==second)throw new IllegalStateException("raid rabbits have no distinct finite identities");
            ok("raid host released two finite natural rabbits");openRaidGate(d);
        }),
        when("actual rabbit consumed the open crop",.2f,d->raidStages(d,0),d->{ok("raid host rabbit consumed stage7 to0");d.shot("raid-host");}),
        step("protected crop survives the raid",5f,d->{if(!raidStages(d,0))throw new IllegalStateException("raid crop changed after authoritative meal");ok("raid protected crop stayed stage7");})
    );}
    private List<Step> raidGuestSteps(boolean tiller){
        List<Step> route=new java.util.ArrayList<>();
        route.add(when("guest observed both mature raid crops",.2f,d->raidStages(d,7),d->{
            if(d.ecologyEnabled())throw new IllegalStateException("guest is running an authoritative Ecology instance");
            int slot=NET_MODE.equals("server")?NET_SLOT:0;int[] p=raidReadyMark(d,slot);
            d.setBlockAt(p[0],p[1],p[2],"GLASS");retreatFromRaid(d);ok("raid guest acknowledged mature crop stage7 with ecology absent");
        }));
        if(tiller)route.add(when("both server guests acknowledged stage7",.2f,d->raidReady(d,1),Autopilot::openRaidGate));
        route.add(when("authoritative raid reached the guest",.2f,d->raidStages(d,0),d->{
            if(d.ecologyEnabled())throw new IllegalStateException("guest simulated the raid locally");
            ok("raid guest received crop stage0 and protected stage7");d.shot("raid-guest"+NET_SLOT);
        }));
        route.add(step("protected crop remains mirrored",5f,d->{if(!raidStages(d,0))throw new IllegalStateException("guest raid crop changed after meal");ok("raid protected crop stayed stage7");}));
        return route;
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
            // Животные остаются на месте (MOB-01) и остаются собой (MOB-02): стадо
            // у точки появления переживает уход за 600 блоков — выгрузку своих
            // чанков — и возвращение, с теми же id. Путь настоящий: выгрузка игры,
            // запись, загрузчик и приём ожившего чанка.
            step("place a herd by the spawn", 0.3f, Driver::spawnAnimals),
            step("remember the herd", 1.5f, d -> {
                int[] home = d.spawnBlock();
                herdAt = new float[] { home[0] + 0.5f, home[2] - 7.5f };   // x, z of the row's middle
                herdIds = d.animalIdsNear(herdAt[0], herdAt[1], 12f);
                if (herdIds.length < 4)
                    throw new IllegalStateException("only " + herdIds.length + " animals stood by the spawn");
                ok("a herd of " + herdIds.length + " animals by the spawn");
            }),
            step("walk 600 blocks away", 0.2f, d -> {
                d.flyInCreative();
                d.teleport(herdAt[0] + 600f, d.spawnBlock()[1] + 31f, herdAt[1]);
            }),
            when("the herd leaves the world with its chunks", 0.5f, Autopilot::herdGone,
                    d -> ok("the herd left the world with its chunks")),
            step("come back", 0.5f, d -> {
                int[] home = d.spawnBlock();
                d.teleport(home[0] + 0.5f, home[1] + 1f, home[2] + 0.5f);
                d.walkInSurvival();
            }),
            when("the herd comes back", 0.5f, Autopilot::herdHere,
                    d -> ok("the herd came back after a 600-block walk with the same " + herdIds.length + " ids")),
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
            when("the herd survived leaving and reopening the world", 0.5f, Autopilot::herdHere,
                    d -> ok("the herd survived leaving and reopening the world with its ids")),
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

    /** Native movement and rendered before/after frames for the Living World trail. */
    private List<Step> trailSteps() {
        List<Step> route = new java.util.ArrayList<>();
        route.add(step("create a trail test world", 0.2f, d -> d.act(MenuAction.create(
                new WorldSettings("Trail smoke", 20260930L, GameMode.SURVIVAL)))));
        route.add(when("trail world loaded", 0.5f, d -> in(d, "PLAYING"), d -> ok("trail world loaded")));
        route.add(step("prepare a lit grass lane", 0.3f, d -> {
            for (int dx = -1; dx <= 10; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int[] at = floorAt(d, dx, dz);
                    d.setBlockAt(at[0], at[1], at[2], "GRASS");
                    for (int y = at[1] + 1; y < 128; y++) d.setBlockAt(at[0], y, at[2], "AIR");
                    if (dx == -1 || dx == 10) {
                        d.setBlockAt(at[0], at[1] + 1, at[2], "STONE");
                        d.setBlockAt(at[0], at[1] + 2, at[2], "STONE");
                    }
                }
            int[] start = floorAt(d, 0, 0), end = floorAt(d, 9, 0);
            d.teleport(start[0] + 0.5f, start[1] + 1.001f, start[2] + 0.5f);
            d.lookAt(end[0] + 0.5f, end[1] + 2.62f, end[2] + 0.5f);
            if (!"GRASS".equals(d.blockAt(start[0] + 5, start[1], start[2])))
                throw new IllegalStateException("grass lane was not placed in the loaded world");
        }));
        for (int pass = 0; pass < 10; pass++) {
            route.add(step("walk trail outward " + pass, 0.1f, d -> d.holdKey(GLFW_KEY_W, true)));
            route.add(step("turn back on trail " + pass, 2.3f, d -> {
                d.holdKey(GLFW_KEY_W, false);
                int[] start = floorAt(d, 0, 0);
                d.lookAt(start[0] - 2.5f, start[1] + 2.62f, start[2] + 0.5f);
            }));
            route.add(step("walk trail home " + pass, 0.1f, d -> d.holdKey(GLFW_KEY_W, true)));
            route.add(step("turn toward trail " + pass, 2.3f, d -> {
                d.holdKey(GLFW_KEY_W, false);
                int[] end = floorAt(d, 9, 0);
                d.lookAt(end[0] + 2.5f, end[1] + 2.62f, end[2] + 0.5f);
            }));
            if (pass == 1) {
                route.add(step("verify the growing trail", 0.3f, d -> {
                    int[] at = floorAt(d, 5, 0);
                    int stage = d.blockMetaAt(at[0], at[1], at[2]);
                    if (!"GRASS".equals(d.blockAt(at[0], at[1], at[2])) || stage < 1 || stage >= 5)
                        throw new IllegalStateException("the early trail did not gradually mark grass: " + stage);
                    ok("native walking began a surface trail without replacing grass");
                }));
                route.add(step("capture the growing trail", 0.5f, d -> d.shot("trail-growing")));
            }
        }
        route.add(step("verify the walked trail", 0.3f, d -> {
            int[] at = floorAt(d, 5, 0);
            if (!"GRASS".equals(d.blockAt(at[0], at[1], at[2]))
                    || d.blockMetaAt(at[0], at[1], at[2]) != 5)
                throw new IllegalStateException("native walking did not deepen the surface trail");
            ok("native walking formed a full surface trail");
        }));
        route.add(step("capture the used trail", 0.5f, d -> d.shot("trail-used")));
        route.add(step("leave the trail for ten days", 0.5f, d -> d.advanceDays(10)));
        route.add(when("the abandoned trail regrows", 0.5f, d -> {
            int[] at = floorAt(d, 5, 0);
            return "GRASS".equals(d.blockAt(at[0], at[1], at[2]))
                    && d.blockMetaAt(at[0], at[1], at[2]) == 0;
        }, d -> ok("the abandoned native trail regrew")));
        route.add(step("let passing particles clear", 3.0f, d -> {}));
        route.add(step("capture the regrown trail", 0.5f, d -> d.shot("trail-regrown")));
        return route;
    }

    private final Driver d;
    private final List<Step> steps;
    private float clock;
    private float stepClock;
    private int index;
    private boolean finished;

    Autopilot(Driver driver) {
        this.d = driver;
        boolean farm="farm".equals(System.getProperty("mineclone.autopilot.netScenario",""))||NET_MODE.equals("farm");
        String role=NET_MODE.equals("farm")?System.getProperty("mineclone.autopilot.farmRole","host"):NET_MODE;
        this.steps = TRAIL_MODE ? trailSteps() : LAIR_MODE ? lairSteps(role) : BURN_MODE ? burnSteps(role) : switch (role) {
            case "host" -> farm?concat(hostSteps,concat(hostFormsSteps,concat(farmHostSteps,ECOLOGY_MODE?raidHostSteps():List.of()))):hostSteps;
            case "join" -> farm?concat(guestSteps,concat(guestFormsSteps,concat(farmGuestSteps,ECOLOGY_MODE?raidGuestSteps(false):List.of()))):guestSteps;
            case "server" -> farm?concat(serverGuestSteps,NET_SLOT==0?concat(hostFormsSteps,concat(farmServerTillerSteps,ECOLOGY_MODE?raidGuestSteps(true):List.of())):concat(guestFormsSteps,concat(farmGuestSteps,ECOLOGY_MODE?raidGuestSteps(false):List.of()))):serverGuestSteps;
            default -> menuSteps;
        };
    }

    void update(float dt) {
        if (finished)
            return;
        if (lairInitialIds != null && !lairDeathWitness && in(d, "PLAYING")) {
            float[] pose = d.mobPose(lairInitialIds[0]);
            if (pose != null && (pose[3] <= 0 || pose[4] != 0)) {
                lairDeathWitness = true;
                System.out.println("autopilot: lair dead pose observed id=" + lairInitialIds[0] + " health=" + pose[3] + " dead=" + pose[4]);
            }
        }
        if (lairWatchingClosed && in(d, "PLAYING")) {
            try { oldLairClosed(d, false); }
            catch (IllegalStateException invalid) { fail(invalid.getMessage()); return; }
        }
        if (lairAttacking && lairInitialIds != null && in(d, "PLAYING")) {
            lairAttackClock += dt;
            d.mouseButton(0, d.aimAtMob(lairInitialIds[0]) && lairAttackClock % .9f < .09f);
        }
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
