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
