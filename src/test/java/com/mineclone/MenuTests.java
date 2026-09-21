package com.mineclone;

import com.mineclone.core.KeyBindings;
import com.mineclone.core.KeyBindings.Action;
import com.mineclone.save.LevelData;
import com.mineclone.save.Options;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.GameMode;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Проверки меню: раскладка клавиш, формат настроек, экраны и их логика.
 *
 * Отдельным классом по той же причине, что {@link FeatureTests}: раннер и
 * счётчики общие, {@code TestMain} зовёт {@link #runAll} перед итогом.
 */
final class MenuTests {
    private MenuTests() {}

    interface Check { void run() throws Exception; }
    interface Runner { void run(String name, Check check); }

    static void runAll(Runner r) {
        r.run("default key layout has no conflicts", MenuTests::testKeyDefaults);
        r.run("a shared key flags both actions until reset", MenuTests::testKeyConflict);
        r.run("system keys cannot be bound", MenuTests::testReservedKeys);
        r.run("key names read like the keyboard", MenuTests::testKeyNames);
        r.run("bindings persist by action name", MenuTests::testBindingsByName);
        r.run("options v5 keep a custom layout", MenuTests::testOptionsKeysRoundTrip);
        r.run("options v4 still load, with default keys", MenuTests::testOptionsV4StillLoads);
        r.run("renaming a world keeps its hunger", MenuTests::testRenameKeepsHunger);
        r.run("screen stack pushes, steps back and hands the root's back to the game",
                MenuTests::testScreenStackNavigation);
        r.run("screen transitions fade the new screen in and let the old one go",
                MenuTests::testScreenStackFades);
        r.run("text field types at the caret and stops at its length", MenuTests::testTextFieldEditing);
        r.run("held backspace repeats only after a delay", MenuTests::testTextFieldRepeat);
        r.run("setText survives a caret left over from the old text",
                MenuTests::testTextFieldSetText);
        r.run("typing a room code in lower case lands upper case",
                MenuTests::testRoomCodeField);
        r.run("text field pastes one clean line", MenuTests::testTextFieldPaste);
        r.run("scroll stays inside the content and eases to its target", MenuTests::testScrollState);
        r.run("russian plurals pick one, few and many", MenuTests::testPlural);
        r.run("file sizes read in bytes, KB, MB and GB", MenuTests::testFileSize);
        r.run("last played says today, yesterday or a date", MenuTests::testLastPlayed);
        r.run("game day counts from one", MenuTests::testGameDay);
        r.run("seed text: empty is random, digits are the seed, words hash", MenuTests::testParseSeed);
        r.run("default world name takes the lowest free number", MenuTests::testDefaultWorldName);
        r.run("world list knows mode, day, size and icon, newest first", MenuTests::testWorldInfo);
        r.run("duplicating a world copies level, chunks and icon", MenuTests::testDuplicateWorld);
        r.run("world ids never collide within one second", MenuTests::testUniqueWorldId);
        r.run("world icon survives a save and load", MenuTests::testIconRoundTrip);
        r.run("thumbnail crops the frame to its own aspect", MenuTests::testThumbnailCrop);
        r.run("loading rows are done, active or pending by stage", MenuTests::testLoadingStages);
        r.run("loading tips rotate on a timer", MenuTests::testLoadingTips);
        r.run("settings sliders map to whole values and back", MenuTests::testSettingsSliders);
        r.run("sky palette is the frame math it replaced, bit for bit", MenuTests::testSkyPalette);
        r.run("menu day lasts two minutes, its night one", MenuTests::testMenuDayCycle);
    }

    /**
     * Палитра кадра вынесена из Game.render, чтобы меню рисовало небо той же
     * формулой. Здесь та формула слово в слово, как она стояла в кадре:
     * разойдутся — значит, вынос что-то поменял в картинке игры.
     */
    private static void testSkyPalette() {
        float[][] cases = {
                { (float) (Math.PI / 6.0), 0f, 0f, 1f, 0f },
                { (float) (Math.PI / 2.0), 0.7f, 0.4f, 1f, 0f },
                { (float) (Math.PI * 1.4), 0.2f, 0f, 0.4f, 0.8f },
                { 0.02f, 1f, 1f, 0.32f, 0.3f },
        };
        com.mineclone.render.SkyPalette p = new com.mineclone.render.SkyPalette();
        for (float[] c : cases) {
            float gameTime = c[0], clouds = c[1], storm = c[2], moonlight = c[3], aurora = c[4];
            float daylight = Math.max(0f, (float) Math.sin(gameTime));
            p.compute(gameTime, daylight, clouds, storm, moonlight, aurora);

            org.joml.Vector3f skySrgb = legacySkyColor(daylight);
            skySrgb.lerp(new org.joml.Vector3f(0.32f, 0.36f, 0.42f).mul(0.15f + daylight * 0.85f), clouds * 0.62f);
            org.joml.Vector3f skyLin = new org.joml.Vector3f((float) Math.pow(skySrgb.x, 2.2),
                    (float) Math.pow(skySrgb.y, 2.2), (float) Math.pow(skySrgb.z, 2.2));
            org.joml.Vector3f zenith = new org.joml.Vector3f(skyLin).mul(0.70f).add(0.000f, 0.004f, 0.024f);
            org.joml.Vector3f horizon = new org.joml.Vector3f(skyLin).mul(1.32f);
            org.joml.Vector3f ground = new org.joml.Vector3f(skyLin).mul(0.30f).add(0.012f, 0.010f, 0.008f);
            boolean moonUp = Math.sin(gameTime) <= 0.0;
            float moonK = moonUp ? moonlight : 1f;
            org.joml.Vector3f lightCol = com.mineclone.render.SunLight.lightColor(gameTime)
                    .mul((1f - clouds * 0.72f - storm * 0.10f) * moonK);
            org.joml.Vector3f skyAmb = com.mineclone.render.SunLight.skyAmbient(skyLin, daylight)
                    .mul(daylight + (1f - daylight) * (0.62f + 0.38f * moonlight))
                    .mul(1f - clouds * 0.12f - storm * 0.08f);
            skyAmb.add(0.006f * aurora, 0.034f * aurora, 0.018f * aurora);
            org.joml.Vector3f groundAmb = com.mineclone.render.SunLight.groundAmbient(skyAmb);
            org.joml.Vector3f sunGlow = new org.joml.Vector3f(lightCol).mul(daylight > 0.02f ? 0.85f : 0.30f);

            String at = " at t=" + gameTime;
            assertEq("небо sRGB" + at, skySrgb, p.skySrgb);
            assertEq("небо линейное" + at, skyLin, p.skyLin);
            assertEq("зенит" + at, zenith, p.zenith);
            assertEq("горизонт" + at, horizon, p.horizon);
            assertEq("земля" + at, ground, p.ground);
            assertEq("цвет светила" + at, lightCol, p.lightCol);
            assertEq("небесный ambient" + at, skyAmb, p.skyAmb);
            assertEq("земной ambient" + at, groundAmb, p.groundAmb);
            assertEq("гало" + at, sunGlow, p.sunGlow);
            assertEq("множитель луны" + at, moonK, p.moonK);
            assertEq("направление света" + at, com.mineclone.render.SunLight.lightDirection(gameTime), p.lightDir);
        }
    }

    private static org.joml.Vector3f legacySkyColor(float d) {
        float[] night = { 0.02f, 0.03f, 0.08f };
        float[] horizon = { 0.85f, 0.45f, 0.20f };
        float[] day = { 0.55f, 0.75f, 0.95f };
        float[] a, b;
        float t;
        if (d < 0.3f) {
            a = night;
            b = horizon;
            t = d / 0.3f;
        } else {
            a = horizon;
            b = day;
            t = (d - 0.3f) / 0.7f;
        }
        return new org.joml.Vector3f(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t);
    }

    /**
     * Фон меню живёт полными сутками за три минуты, но ночь идёт вдвое быстрее
     * дня: полторы минуты темноты под меню — это уже не атмосфера, а плохо
     * видно кнопки.
     */
    private static void testMenuDayCycle() {
        float t = 0f, dayTime = 0f, nightTime = 0f;
        float step = 1f / 120f;
        for (int i = 0; i < 180 * 120; i++) {
            if (Math.sin(t) > 0.0)
                dayTime += step;
            else
                nightTime += step;
            t = com.mineclone.game.MenuBackground.advance(t, step);
        }
        assertTrue("за три минуты прошли сутки (" + t + ")", Math.abs(t - Math.PI * 2.0) < 0.05);
        assertTrue("день около двух минут (" + dayTime + ")", Math.abs(dayTime - 120f) < 1.5f);
        assertTrue("ночь около минуты (" + nightTime + ")", Math.abs(nightTime - 60f) < 1.5f);
    }

    /**
     * Ползунок отдаёт 0..1, а игре нужны целые чанки и кадры. Круговой перевод
     * обязан возвращать то же значение: иначе открытие настроек само сдвигало
     * бы дальность на чанк.
     */
    private static void testSettingsSliders() {
        assertEq("минимум дальности", 2, com.mineclone.ui.SettingsScreen.radiusAt(0f));
        assertEq("максимум дальности", 16, com.mineclone.ui.SettingsScreen.radiusAt(1f));
        for (int r = 2; r <= 16; r++)
            assertEq("дальность туда-обратно " + r, r,
                    com.mineclone.ui.SettingsScreen.radiusAt(com.mineclone.ui.SettingsScreen.radiusT(r)));
        for (int f = 50; f <= 120; f++)
            assertEq("fov туда-обратно " + f, f,
                    com.mineclone.ui.SettingsScreen.fovAt(com.mineclone.ui.SettingsScreen.fovT(f)));
        assertEq("правый край — без ограничения", 0, com.mineclone.ui.SettingsScreen.fpsAt(1f));
        assertEq("левый край", 30, com.mineclone.ui.SettingsScreen.fpsAt(0f));
        assertEq("144 к/с туда-обратно", 144,
                com.mineclone.ui.SettingsScreen.fpsAt(com.mineclone.ui.SettingsScreen.fpsT(144)));
        assertEq("без ограничения туда-обратно", 0,
                com.mineclone.ui.SettingsScreen.fpsAt(com.mineclone.ui.SettingsScreen.fpsT(0)));
        assertEq("подпись без ограничения", "Без ограничения", com.mineclone.ui.SettingsScreen.fpsLabel(0));
        assertEq("подпись кадров", "144 к/с", com.mineclone.ui.SettingsScreen.fpsLabel(144));
        assertEq("чувствительность туда-обратно", 1.25f,
                com.mineclone.ui.SettingsScreen.sensitivityAt(com.mineclone.ui.SettingsScreen.sensitivityT(1.25f)));
    }

    // ---------------------------------------------------------- форматирование

    private static void testPlural() {
        String[] f = { "чанк", "чанка", "чанков" };
        assertEq("1", "1 чанк", com.mineclone.ui.MenuText.count(1, f[0], f[1], f[2]));
        assertEq("2", "2 чанка", com.mineclone.ui.MenuText.count(2, f[0], f[1], f[2]));
        assertEq("5", "5 чанков", com.mineclone.ui.MenuText.count(5, f[0], f[1], f[2]));
        assertEq("11", "11 чанков", com.mineclone.ui.MenuText.count(11, f[0], f[1], f[2]));
        assertEq("12", "12 чанков", com.mineclone.ui.MenuText.count(12, f[0], f[1], f[2]));
        assertEq("21", "21 чанк", com.mineclone.ui.MenuText.count(21, f[0], f[1], f[2]));
        assertEq("104", "104 чанка", com.mineclone.ui.MenuText.count(104, f[0], f[1], f[2]));
        assertEq("0", "0 чанков", com.mineclone.ui.MenuText.count(0, f[0], f[1], f[2]));
    }

    private static void testFileSize() {
        assertEq("байты", "512 Б", com.mineclone.ui.MenuText.fileSize(512));
        assertEq("килобайты", "2 КБ", com.mineclone.ui.MenuText.fileSize(2048));
        assertEq("мегабайты", "3,4 МБ", com.mineclone.ui.MenuText.fileSize(3_565_158L));
        assertEq("гигабайты", "5,0 ГБ", com.mineclone.ui.MenuText.fileSize(5L * 1024 * 1024 * 1024));
    }

    private static void testLastPlayed() {
        java.time.ZoneId utc = java.time.ZoneOffset.UTC;
        long now = java.time.LocalDateTime.of(2026, 9, 15, 14, 0).atZone(utc).toInstant().toEpochMilli();
        long today = java.time.LocalDateTime.of(2026, 9, 15, 9, 5).atZone(utc).toInstant().toEpochMilli();
        long yesterday = java.time.LocalDateTime.of(2026, 9, 14, 23, 59).atZone(utc).toInstant().toEpochMilli();
        long older = java.time.LocalDateTime.of(2026, 9, 13, 8, 0).atZone(utc).toInstant().toEpochMilli();
        assertEq("сегодня", "сегодня, 09:05", com.mineclone.ui.MenuText.lastPlayed(today, now, utc));
        assertEq("вчера", "вчера, 23:59", com.mineclone.ui.MenuText.lastPlayed(yesterday, now, utc));
        assertEq("дата", "13.09.2026", com.mineclone.ui.MenuText.lastPlayed(older, now, utc));
        assertEq("не открывали", "ещё не открывали", com.mineclone.ui.MenuText.lastPlayed(0L, now, utc));
    }

    private static void testGameDay() {
        assertEq("новый мир", "День 1", com.mineclone.ui.MenuText.gameDay((float) (Math.PI / 6.0)));
        assertEq("двенадцатые сутки", "День 12",
                com.mineclone.ui.MenuText.gameDay((float) (Math.PI * 2.0 * 11 + 1.0)));
    }

    // ------------------------------------------------------------------- миры

    private static void testParseSeed() {
        java.util.function.LongSupplier dice = () -> 777L;
        assertEq("пусто — случайный", 777L, com.mineclone.ui.WorldSettings.parseSeed("", dice));
        assertEq("пробелы — случайный", 777L, com.mineclone.ui.WorldSettings.parseSeed("   ", dice));
        assertEq("число", 12345L, com.mineclone.ui.WorldSettings.parseSeed("12345", dice));
        assertEq("отрицательное с пробелами", -7L, com.mineclone.ui.WorldSettings.parseSeed(" -7 ", dice));
        assertEq("слово — хеш", (long) "hello".hashCode(), com.mineclone.ui.WorldSettings.parseSeed("hello", dice));
        String huge = "99999999999999999999";
        assertEq("переполнение — хеш строки", (long) huge.hashCode(),
                com.mineclone.ui.WorldSettings.parseSeed(huge, dice));
    }

    private static void testDefaultWorldName() {
        assertEq("первый", "Мир 1", com.mineclone.ui.WorldSettings.defaultName(java.util.List.of()));
        assertEq("дырка в нумерации", "Мир 2",
                com.mineclone.ui.WorldSettings.defaultName(java.util.List.of("Мир 1", "Остров", "Мир 3")));
    }

    private static void writeWorld(SaveManager sm, String id, String name, long lastPlayed, GameMode mode, float tod) {
        sm.saveLevel(id, new LevelData(name, 42L, 8, 70, 8, 8, 70, 8, 0f, 0f, tod, 0,
                LevelData.emptyInventory(), mode, lastPlayed, 20f, 20f));
    }

    private static void testWorldInfo() throws Exception {
        SaveManager sm = freshManager();
        writeWorld(sm, "old", "Старый", 1_000L, GameMode.CREATIVE, (float) (Math.PI * 2.0 * 4 + 0.5));
        writeWorld(sm, "new", "Новый", 9_000L, GameMode.SURVIVAL, 0.5f);
        sm.saveChunkAsync("new", new com.mineclone.save.ChunkSnapshot(0, 0,
                new byte[SaveFormat.CHUNK_VOLUME], new byte[SaveFormat.CHUNK_VOLUME]));
        sm.flushAndAwait();
        sm.saveIconAsync("old", 4, 2, new int[8]);
        sm.flushAndAwait();

        java.util.List<SaveManager.WorldInfo> list = sm.listWorlds();
        assertEq("два мира", 2, list.size());
        assertEq("свежий первым", "new", list.get(0).id);
        SaveManager.WorldInfo n = list.get(0), o = list.get(1);
        assertEq("режим", GameMode.SURVIVAL, n.mode);
        assertEq("режим старого", GameMode.CREATIVE, o.mode);
        assertEq("сутки", 5L, (long) Math.floor(o.timeOfDay / (Math.PI * 2.0)) + 1);
        assertTrue("размер с чанком больше", n.sizeBytes > o.sizeBytes - 100 && n.sizeBytes > 0);
        assertTrue("превью есть у старого", o.hasIcon);
        assertTrue("у нового нет", !n.hasIcon);
    }

    private static void testDuplicateWorld() throws Exception {
        SaveManager sm = freshManager();
        writeWorld(sm, "w1", "Остров", 5_000L, GameMode.SURVIVAL, 1f);
        byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME];
        blocks[123] = 7;
        sm.saveChunkAsync("w1", new com.mineclone.save.ChunkSnapshot(2, -1, blocks, new byte[SaveFormat.CHUNK_VOLUME]));
        sm.saveIconAsync("w1", 2, 2, new int[] { 0xff0000, 0x00ff00, 0x0000ff, 0xffffff });
        sm.flushAndAwait();

        String copy = sm.duplicateWorld("w1");
        assertTrue("новый id", copy != null && !copy.equals("w1"));
        LevelData d = sm.loadLevel(copy);
        assertEq("имя копии", "Остров (копия)", d.name);
        assertEq("сид тот же", 42L, d.seed);
        assertEq("режим тот же", GameMode.SURVIVAL, d.gameMode);
        com.mineclone.save.ChunkSnapshot c = sm.loadChunk(copy, 2, -1);
        assertTrue("чанк скопирован", c != null && c.blocks[123] == 7);
        assertTrue("превью скопировано", sm.loadIcon(copy) != null);
        assertEq("оригинал на месте", "Остров", sm.loadLevel("w1").name);
        assertEq("в списке двое", 2, sm.listWorlds().size());
    }

    private static void testUniqueWorldId() throws Exception {
        SaveManager sm = freshManager();
        writeWorld(sm, "world_x", "A", 1L, GameMode.SURVIVAL, 0f);
        assertEq("свободный id не трогаем", "world_y", sm.uniqueWorldId("world_y"));
        assertEq("занятый получает суффикс", "world_x_2", sm.uniqueWorldId("world_x"));
        writeWorld(sm, "world_x_2", "B", 1L, GameMode.SURVIVAL, 0f);
        assertEq("и следующий", "world_x_3", sm.uniqueWorldId("world_x"));
    }

    private static void testIconRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        writeWorld(sm, "w1", "A", 1L, GameMode.SURVIVAL, 0f);
        int[] px = new int[6 * 3];
        px[0] = 0x123456;
        px[17] = 0xabcdef;
        sm.saveIconAsync("w1", 6, 3, px);
        sm.flushAndAwait();
        java.awt.image.BufferedImage img = sm.loadIcon("w1");
        assertTrue("прочиталось", img != null);
        assertEq("ширина", 6, img.getWidth());
        assertEq("высота", 3, img.getHeight());
        assertEq("левый верхний пиксель", 0x123456, img.getRGB(0, 0) & 0xffffff);
        assertEq("правый нижний", 0xabcdef, img.getRGB(5, 2) & 0xffffff);
        assertTrue("у мира без превью — null", sm.loadIcon("nope") == null);
    }

    private static void testThumbnailCrop() {
        assertTrue("16:9 целиком", java.util.Arrays.equals(new int[] { 0, 0, 1920, 1080 },
                com.mineclone.render.Thumbnail.crop(1920, 1080, 256, 144)));
        assertTrue("5:4 режется по высоте", java.util.Arrays.equals(new int[] { 0, 152, 1280, 720 },
                com.mineclone.render.Thumbnail.crop(1280, 1024, 256, 144)));
        assertTrue("сверхширокий режется по ширине", java.util.Arrays.equals(new int[] { 400, 0, 1920, 1080 },
                com.mineclone.render.Thumbnail.crop(2720, 1080, 256, 144)));
    }

    private static void testLoadingStages() {
        com.mineclone.world.LoadStage L = com.mineclone.world.LoadStage.LIGHTING;
        assertEq("пройденный", com.mineclone.ui.LoadingScreen.Row.DONE,
                com.mineclone.ui.LoadingScreen.rowState(L, com.mineclone.world.LoadStage.GENERATING));
        assertEq("текущий", com.mineclone.ui.LoadingScreen.Row.ACTIVE,
                com.mineclone.ui.LoadingScreen.rowState(L, com.mineclone.world.LoadStage.LIGHTING));
        assertEq("впереди", com.mineclone.ui.LoadingScreen.Row.PENDING,
                com.mineclone.ui.LoadingScreen.rowState(L, com.mineclone.world.LoadStage.BUILDING));
        assertEq("всё готово", com.mineclone.ui.LoadingScreen.Row.DONE,
                com.mineclone.ui.LoadingScreen.rowState(com.mineclone.world.LoadStage.DONE,
                        com.mineclone.world.LoadStage.BUILDING));
    }

    private static void testLoadingTips() {
        float step = com.mineclone.ui.LoadingScreen.TIP_SECONDS;
        assertEq("внутри интервала совет тот же",
                com.mineclone.ui.LoadingScreen.tip(0.1f), com.mineclone.ui.LoadingScreen.tip(step * 0.9f));
        assertTrue("через интервал другой",
                !com.mineclone.ui.LoadingScreen.tip(0.1f).equals(com.mineclone.ui.LoadingScreen.tip(step + 0.1f)));
    }

    // ------------------------------------------------------------ стек экранов

    /** Экран-заглушка: считает, сколько раз его вернули наверх и закрыли. */
    private static final class FakeScreen implements com.mineclone.ui.Screen {
        int resumed, closed;

        @Override
        public com.mineclone.ui.MenuAction draw(com.mineclone.ui.MenuTheme theme) {
            return com.mineclone.ui.MenuAction.NONE;
        }

        @Override
        public void resumed() { resumed++; }

        @Override
        public void closed() { closed++; }
    }

    private static void testScreenStackNavigation() {
        com.mineclone.ui.ScreenStack st = new com.mineclone.ui.ScreenStack();
        FakeScreen root = new FakeScreen(), child = new FakeScreen();
        st.reset(root);
        assertEq("один экран", 1, st.depth());
        assertTrue("он наверху", st.top() == root);

        assertEq("переход съеден стеком", com.mineclone.ui.MenuAction.Kind.NONE,
                st.handle(com.mineclone.ui.MenuAction.push(child)).kind);
        assertEq("два экрана", 2, st.depth());
        assertTrue("дочерний наверху", st.top() == child);

        assertEq("шаг назад съеден стеком", com.mineclone.ui.MenuAction.Kind.NONE,
                st.handle(com.mineclone.ui.MenuAction.back()).kind);
        assertTrue("корень снова наверху", st.top() == root);
        assertEq("корень вернулся", 1, root.resumed);
        // Закрывается, когда догаснет: пока гаснет, его ещё рисуют.
        assertEq("гаснущий ещё не закрыт", 0, child.closed);
        st.update(1f);
        assertEq("догас — закрыт", 1, child.closed);

        // Назад с корня решает игра: из паузы это «вернуться в игру», с титула — ничего.
        assertEq("назад с корня уходит игре", com.mineclone.ui.MenuAction.Kind.BACK,
                st.handle(com.mineclone.ui.MenuAction.back()).kind);
        assertEq("корень остался", 1, st.depth());
        assertEq("чужое действие проходит насквозь", com.mineclone.ui.MenuAction.Kind.QUIT,
                st.handle(com.mineclone.ui.MenuAction.of(com.mineclone.ui.MenuAction.Kind.QUIT)).kind);

        st.reset(new FakeScreen());
        assertEq("сброс закрывает прежний корень", 1, root.closed);
    }

    private static void testScreenStackFades() {
        com.mineclone.ui.ScreenStack st = new com.mineclone.ui.ScreenStack();
        FakeScreen root = new FakeScreen(), child = new FakeScreen();
        st.reset(root);
        st.update(1f);
        assertEq("корень проявился", 1f, st.alpha(root));

        st.handle(com.mineclone.ui.MenuAction.push(child));
        assertEq("новый экран начинает с нуля", 0f, st.alpha(child));
        assertTrue("старый ещё виден", st.alpha(root) > 0.99f);
        assertTrue("старый не принимает ввод", !st.acceptsInput(root));
        assertTrue("новый принимает сразу", st.acceptsInput(child));

        st.update(com.mineclone.ui.ScreenStack.FADE_OUT * 0.5f);
        float mid = st.alpha(root);
        assertTrue("старый гаснет (" + mid + ")", mid > 0f && mid < 1f);
        assertTrue("новый проявляется", st.alpha(child) > 0f && st.alpha(child) < 1f);

        st.update(1f);
        assertEq("новый целиком", 1f, st.alpha(child));
        assertEq("старый ушёл", 0f, st.alpha(root));
        assertTrue("и больше не рисуется", st.leaving() == null);
    }

    // ------------------------------------------------------------ поле ввода

    private static com.mineclone.ui.UiInput.Builder in() {
        return com.mineclone.ui.UiInput.builder();
    }

    /**
     * Замена текста при курсоре посреди старого.
     *
     * <p>{@code setText} чистит буфер, а вставляет по текущей позиции курсора:
     * оставшийся от прежнего текста, тот указывал за конец пустой строки, и
     * экран сети падал исключением посреди кадра меню.
     */
    private static void testTextFieldSetText() {
        com.mineclone.ui.TextField f =
                new com.mineclone.ui.TextField("", 6, com.mineclone.ui.TextField.ANY);
        f.edit(in().typed("ab").build(), 0.016f);
        assertEq("курсор в конце", 2, f.caret());
        f.setText("");
        assertEq("пусто", "", f.text());
        assertEq("и курсор с ним", 0, f.caret());

        f.setText("abcd");
        f.edit(in().key(GLFW_KEY_HOME).build(), 0.016f);
        assertEq("курсор в начале", 0, f.caret());
        f.setText("xy");
        assertEq("замена целиком", "xy", f.text());
        assertEq("курсор в конце нового", 2, f.caret());

        // Замена длиннее предела обрезается, а курсор остаётся внутри строки.
        f.setText("abcdefghij");
        assertEq("обрезано по длине", "abcdef", f.text());
        assertEq("курсор не за краем", f.text().length(), f.caret());
    }

    /**
     * Поле кода комнаты приводит набранное к виду кода.
     *
     * <p>Это и был тот путь, на котором экран падал: игрок печатает строчными,
     * приведение меняет строку, поле переписывается — а курсор к тому моменту
     * уже стоял не в начале.
     */
    private static void testRoomCodeField() {
        com.mineclone.ui.TextField f = new com.mineclone.ui.TextField("",
                com.mineclone.net.connect.RoomCode.LENGTH,
                c -> !com.mineclone.net.connect.RoomCode.normalize(String.valueOf(c)).isEmpty());
        // Ровно то, что делает экран каждый кадр.
        for (String key : new String[] { "a", "4", "k", "7", "m", "2" }) {
            f.edit(in().typed(key).build(), 0.016f);
            String typed = com.mineclone.net.connect.RoomCode.typed(f.text());
            if (!typed.equals(f.text()))
                f.setText(typed);
        }
        assertEq("код прописными", "A4K7M2", f.text());
        assertTrue("и он разбирается",
                com.mineclone.net.connect.RoomCode.parse(f.text()) != null);

        // O, I и L на бумаге неотличимы от нуля и единицы — поле их принимает
        // и тут же приводит.
        com.mineclone.ui.TextField g = new com.mineclone.ui.TextField("A",
                com.mineclone.net.connect.RoomCode.LENGTH,
                c -> !com.mineclone.net.connect.RoomCode.normalize(String.valueOf(c)).isEmpty());
        g.edit(in().typed("oil").build(), 0.016f);
        g.setText(com.mineclone.net.connect.RoomCode.typed(g.text()));
        assertEq("O, I и L прочитаны", "A011", g.text());
    }

    private static void testTextFieldEditing() {
        com.mineclone.ui.TextField f = new com.mineclone.ui.TextField("", 5, com.mineclone.ui.TextField.ANY);
        f.edit(in().typed("abcdefg").build(), 0.016f);
        assertEq("обрезано по длине", "abcde", f.text());
        f.edit(in().key(GLFW_KEY_BACKSPACE).build(), 0.016f);
        assertEq("backspace", "abcd", f.text());
        f.edit(in().key(GLFW_KEY_LEFT).build(), 0.016f);
        f.edit(in().key(GLFW_KEY_LEFT).build(), 0.016f);
        f.edit(in().typed("X").build(), 0.016f);
        assertEq("вставка у каретки", "abXcd", f.text());
        f.edit(in().key(GLFW_KEY_DELETE).build(), 0.016f);
        assertEq("delete после каретки", "abXd", f.text());
        f.edit(in().key(GLFW_KEY_HOME).build(), 0.016f);
        f.edit(in().typed("Z").build(), 0.016f);
        assertEq("home", "ZabXd", f.text());
        assertTrue("enter — отправка", f.edit(in().key(GLFW_KEY_ENTER).build(), 0.016f));
        assertTrue("без enter — нет", !f.edit(in().build(), 0.016f));

        com.mineclone.ui.TextField digits = new com.mineclone.ui.TextField("", 10, Character::isDigit);
        digits.edit(in().typed("a1b2").build(), 0.016f);
        assertEq("фильтр", "12", digits.text());
    }

    /**
     * Зажатый Backspace обязан стирать дальше, но не сразу: иначе короткое
     * нажатие на медленном кадре съедает два символа.
     */
    private static void testTextFieldRepeat() {
        com.mineclone.ui.TextField f = new com.mineclone.ui.TextField("abcdef", 32, com.mineclone.ui.TextField.ANY);
        f.edit(in().key(GLFW_KEY_BACKSPACE).held(GLFW_KEY_BACKSPACE).build(), 0.016f);
        assertEq("нажатие стирает один", "abcde", f.text());
        f.edit(in().held(GLFW_KEY_BACKSPACE).build(), 0.30f);
        assertEq("до задержки не повторяет", "abcde", f.text());
        f.edit(in().held(GLFW_KEY_BACKSPACE).build(), 0.20f);
        assertTrue("после задержки повторяет (" + f.text() + ")", f.text().length() < 5);
        String held = f.text();
        f.edit(in().build(), 1f);
        assertEq("отпустил — перестал", held, f.text());
    }

    private static void testTextFieldPaste() {
        com.mineclone.ui.TextField f = new com.mineclone.ui.TextField("ab", 8, com.mineclone.ui.TextField.ANY);
        f.edit(in().held(GLFW_KEY_LEFT_CONTROL).key(GLFW_KEY_V).paste("12\n34").build(), 0.016f);
        assertEq("только первая строка", "ab12", f.text());
        f.edit(in().held(GLFW_KEY_LEFT_CONTROL).key(GLFW_KEY_V).paste("3\t456789").build(), 0.016f);
        assertEq("без табуляции и по длине", "ab123456", f.text());
    }

    private static void testScrollState() {
        com.mineclone.ui.ScrollState s = new com.mineclone.ui.ScrollState();
        s.scrollBy(-500f, 300f, 200f);
        assertEq("выше начала не уходит", 0f, s.target());
        s.scrollBy(1000f, 300f, 200f);
        assertEq("ниже конца не уходит", 100f, s.target());
        s.update(10f);
        assertTrue("доезжает до цели", Math.abs(s.offset() - 100f) < 0.01f);

        s.clamp(250f, 200f);
        assertEq("содержимое сжалось — цель тоже", 50f, s.target());
        assertTrue("и сразу не за краем", s.offset() <= 50f);

        s.clamp(1000f, 200f);
        s.ensureVisible(600f, 680f, 200f);
        assertEq("низ строки виден", 480f, s.target());
        s.ensureVisible(10f, 90f, 200f);
        assertEq("верх строки виден", 10f, s.target());
        assertTrue("короткое содержимое не прокручивается",
                new com.mineclone.ui.ScrollState().maxScroll(150f, 200f) == 0f);
    }

    // ---------------------------------------------------------------- клавиши

    private static void testKeyDefaults() {
        KeyBindings k = new KeyBindings();
        assertTrue("без конфликтов по умолчанию", k.conflicts().isEmpty());
        assertEq("вперёд", GLFW_KEY_W, k.key(Action.FORWARD));
        assertEq("инвентарь", GLFW_KEY_E, k.key(Action.INVENTORY));
        assertEq("девятый слот", GLFW_KEY_9, k.key(Action.SLOT_9));
        assertEq("слот по номеру", Action.SLOT_3, KeyBindings.slot(2));
    }

    /**
     * Конфликт подсвечивается у обоих действий: игроку нужно видеть, с кем
     * столкнулась клавиша, а не только что новая строка «плохая».
     */
    private static void testKeyConflict() {
        KeyBindings k = new KeyBindings();
        assertTrue("клавиша назначена", k.set(Action.JUMP, GLFW_KEY_W));
        EnumSet<Action> c = k.conflicts();
        assertEq("двое в конфликте", 2, c.size());
        assertTrue("прыжок подсвечен", c.contains(Action.JUMP));
        assertTrue("и шаг вперёд тоже", c.contains(Action.FORWARD));
        k.reset();
        assertTrue("сброс снимает конфликт", k.conflicts().isEmpty());
        assertEq("и возвращает пробел", GLFW_KEY_SPACE, k.key(Action.JUMP));
    }

    /**
     * F3, F5 и Esc разбирает сама игра раньше любых действий. Назначь на них
     * прыжок — и прыжок молча не будет работать, а из меню не выйти.
     */
    private static void testReservedKeys() {
        KeyBindings k = new KeyBindings();
        assertTrue("Esc отклонён", !k.set(Action.FORWARD, GLFW_KEY_ESCAPE));
        assertTrue("F3 отклонён", !k.set(Action.FORWARD, GLFW_KEY_F3));
        assertEq("клавиша не сдвинулась", GLFW_KEY_W, k.key(Action.FORWARD));
        assertTrue("стрелка подходит", k.set(Action.FORWARD, GLFW_KEY_UP));
        assertEq("и назначилась", GLFW_KEY_UP, k.key(Action.FORWARD));
    }

    private static void testKeyNames() {
        assertEq("буква", "W", KeyBindings.keyName(GLFW_KEY_W));
        assertEq("цифра", "7", KeyBindings.keyName(GLFW_KEY_7));
        assertEq("пробел", "Пробел", KeyBindings.keyName(GLFW_KEY_SPACE));
        assertEq("шифт", "Левый Shift", KeyBindings.keyName(GLFW_KEY_LEFT_SHIFT));
        assertEq("функциональная", "F2", KeyBindings.keyName(GLFW_KEY_F2));
        assertEq("цифровой блок", "Num 5", KeyBindings.keyName(GLFW_KEY_KP_5));
        assertEq("незнакомая", "Клавиша 161", KeyBindings.keyName(GLFW_KEY_WORLD_1));
    }

    /**
     * Раскладка пишется по имени действия: вставь новое действие в середину
     * перечисления — и запись по порядку сдвинула бы игроку все клавиши.
     */
    private static void testBindingsByName() {
        Map<String, Integer> saved = new LinkedHashMap<>();
        saved.put("JUMP", GLFW_KEY_J);
        saved.put("NO_SUCH_ACTION", GLFW_KEY_K);
        saved.put("FORWARD", GLFW_KEY_ESCAPE);   // системная — отбрасывается
        KeyBindings k = new KeyBindings();
        k.load(saved);
        assertEq("прыжок из файла", GLFW_KEY_J, k.key(Action.JUMP));
        assertEq("запрещённая осталась по умолчанию", GLFW_KEY_W, k.key(Action.FORWARD));
        assertEq("прочие не тронуты", GLFW_KEY_E, k.key(Action.INVENTORY));

        KeyBindings again = new KeyBindings();
        again.load(k.toMap());
        assertEq("круговая запись", GLFW_KEY_J, again.key(Action.JUMP));
    }

    // --------------------------------------------------------------- настройки

    private static void testOptionsKeysRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        KeyBindings keys = new KeyBindings();
        keys.set(Action.DROP, GLFW_KEY_G);
        Options in = new Options(8, 90, 0.7f, 0.5f, 144, false, true, false,
                1.5f, true, 0.25f, 0.9f, 2, 2, keys);
        sm.saveOptions(in);
        Options out = sm.loadOptions();
        assertEq("клавиша броска", GLFW_KEY_G, out.keys.key(Action.DROP));
        assertEq("остальное на месте", GLFW_KEY_E, out.keys.key(Action.INVENTORY));
        assertEq("и прежние поля", 2, out.shaderQuality);
    }

    /**
     * Файл настроек четвёртой версии, записанный побайтно так, как его писала
     * игра до раскладки. Прецедент уже был: подъём версии чанка однажды
     * выбросил правки во всех мирах.
     */
    private static void testOptionsV4StillLoads() throws Exception {
        File root = freshRoot();
        SaveManager sm = new SaveManager(new File(root, "saves"));
        File f = new File(root, SaveFormat.OPTIONS_FILE);
        try (DataOutputStream o = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)))) {
            o.writeInt(SaveFormat.MAGIC);
            o.writeInt(4);
            o.writeInt(9);        // renderRadius
            o.writeInt(88);       // fov
            o.writeFloat(0.6f);   // brightness
            o.writeFloat(0.4f);   // master
            o.writeInt(120);      // maxFps
            o.writeBoolean(false);
            o.writeBoolean(false);
            o.writeBoolean(true);
            o.writeFloat(1.25f);  // sensitivity
            o.writeBoolean(true); // invertY
            o.writeFloat(0.3f);   // music
            o.writeFloat(0.8f);   // effects
            o.writeInt(3);        // guiScale
            o.writeInt(0);        // shaderQuality
        }
        Options out = sm.loadOptions();
        assertEq("дальность", 9, out.renderRadius);
        assertEq("fov", 88, out.fovDegrees);
        assertEq("чувствительность", 1.25f, out.mouseSensitivity);
        assertEq("масштаб", 3, out.guiScale);
        assertEq("шейдеры", 0, out.shaderQuality);
        assertTrue("раскладка по умолчанию", out.keys.conflicts().isEmpty());
        assertEq("вперёд по умолчанию", GLFW_KEY_W, out.keys.key(Action.FORWARD));
    }

    // -------------------------------------------------------------------- миры

    private static void testRenameKeepsHunger() throws Exception {
        SaveManager sm = freshManager();
        sm.saveLevel("w1", new LevelData("Old", 5L, 1, 2, 3, 1, 2, 3, 0f, 0f, 0f, 0,
                LevelData.emptyInventory(), GameMode.SURVIVAL, 10L, 12f, 7f));
        sm.renameWorld("w1", "New");
        LevelData out = sm.loadLevel("w1");
        assertEq("имя", "New", out.name);
        assertEq("здоровье", 12f, out.health);
        assertEq("сытость", 7f, out.hunger);
    }

    // ---------------------------------------------------------------- помощники

    private static SaveManager freshManager() throws Exception {
        return new SaveManager(new File(freshRoot(), "saves"));
    }

    private static File freshRoot() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("mineclone-menu-").toFile();
        dir.deleteOnExit();
        return dir;
    }

    static void assertTrue(String what, boolean cond) {
        if (!cond) throw new AssertionError("expected true: " + what);
    }

    static void assertEq(String what, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
}
