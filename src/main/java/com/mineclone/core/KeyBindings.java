package com.mineclone.core;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Раскладка клавиатуры: какое действие на какой клавише.
 *
 * <p>Переназначаются только клавиши клавиатуры. Кнопки мыши и системные
 * F-клавиши остаются жёсткими: F3 или Esc игра разбирает раньше любых
 * действий, и действие на такой клавише молча перестало бы работать.
 *
 * <p>Чистая логика без вызовов GLFW — коды клавиш это константы, поэтому
 * конфликты, имена и запись проверяются обычными тестами.
 */
public final class KeyBindings {

    /** Раздел в списке назначения клавиш. */
    public enum Group {
        MOVEMENT("Движение"),
        ACTIONS("Действия"),
        HOTBAR("Хотбар");

        public final String title;

        Group(String title) {
            this.title = title;
        }
    }

    public enum Action {
        FORWARD(Group.MOVEMENT, "Вперёд", GLFW_KEY_W),
        BACK(Group.MOVEMENT, "Назад", GLFW_KEY_S),
        LEFT(Group.MOVEMENT, "Влево", GLFW_KEY_A),
        RIGHT(Group.MOVEMENT, "Вправо", GLFW_KEY_D),
        JUMP(Group.MOVEMENT, "Прыжок, всплыть", GLFW_KEY_SPACE),
        DESCEND(Group.MOVEMENT, "Вниз в полёте и воде", GLFW_KEY_LEFT_SHIFT),
        SPRINT(Group.MOVEMENT, "Бег", GLFW_KEY_LEFT_CONTROL),
        FLY(Group.MOVEMENT, "Полёт", GLFW_KEY_F),
        INVENTORY(Group.ACTIONS, "Инвентарь", GLFW_KEY_E),
        DROP(Group.ACTIONS, "Выбросить предмет", GLFW_KEY_Q),
        INSPECT(Group.ACTIONS, "Осмотреть предмет", GLFW_KEY_R),
        CONSOLE(Group.ACTIONS, "Консоль", GLFW_KEY_T),
        SLOT_1(Group.HOTBAR, "Слот 1", GLFW_KEY_1),
        SLOT_2(Group.HOTBAR, "Слот 2", GLFW_KEY_2),
        SLOT_3(Group.HOTBAR, "Слот 3", GLFW_KEY_3),
        SLOT_4(Group.HOTBAR, "Слот 4", GLFW_KEY_4),
        SLOT_5(Group.HOTBAR, "Слот 5", GLFW_KEY_5),
        SLOT_6(Group.HOTBAR, "Слот 6", GLFW_KEY_6),
        SLOT_7(Group.HOTBAR, "Слот 7", GLFW_KEY_7),
        SLOT_8(Group.HOTBAR, "Слот 8", GLFW_KEY_8),
        SLOT_9(Group.HOTBAR, "Слот 9", GLFW_KEY_9);

        public final Group group;
        public final String title;
        public final int defaultKey;

        Action(Group group, String title, int defaultKey) {
            this.group = group;
            this.title = title;
            this.defaultKey = defaultKey;
        }
    }

    private static final Action[] SLOTS = {
            Action.SLOT_1, Action.SLOT_2, Action.SLOT_3, Action.SLOT_4, Action.SLOT_5,
            Action.SLOT_6, Action.SLOT_7, Action.SLOT_8, Action.SLOT_9 };

    /** Клавиши, которые игра разбирает сама, в обход раскладки. */
    private static final int[] RESERVED = {
            GLFW_KEY_ESCAPE, GLFW_KEY_F1, GLFW_KEY_F3, GLFW_KEY_F4, GLFW_KEY_F5,
            GLFW_KEY_F6, GLFW_KEY_F11 };

    private final EnumMap<Action, Integer> keys = new EnumMap<>(Action.class);

    public KeyBindings() {
        reset();
    }

    /** Действие слота хотбара по номеру 0..8. */
    public static Action slot(int index) {
        return SLOTS[index];
    }

    public int key(Action a) {
        return keys.get(a);
    }

    /**
     * Назначить клавишу. Системную или несуществующую не принимает и
     * возвращает false — прежняя клавиша остаётся.
     */
    public boolean set(Action a, int key) {
        if (!isBindable(key))
            return false;
        keys.put(a, key);
        return true;
    }

    public void reset() {
        for (Action a : Action.values())
            keys.put(a, a.defaultKey);
    }

    public void copyFrom(KeyBindings other) {
        keys.putAll(other.keys);
    }

    public KeyBindings copy() {
        KeyBindings k = new KeyBindings();
        k.copyFrom(this);
        return k;
    }

    /**
     * Действия, делящие клавишу с кем-то ещё. Подсвечиваются оба: игроку
     * нужно видеть, с кем столкнулась клавиша, а не только что строка «плохая».
     */
    public EnumSet<Action> conflicts() {
        EnumSet<Action> out = EnumSet.noneOf(Action.class);
        Action[] all = Action.values();
        for (int i = 0; i < all.length; i++)
            for (int j = i + 1; j < all.length; j++)
                if (key(all[i]) == key(all[j])) {
                    out.add(all[i]);
                    out.add(all[j]);
                }
        return out;
    }

    public static boolean isReserved(int key) {
        for (int r : RESERVED)
            if (r == key)
                return true;
        return false;
    }

    public static boolean isBindable(int key) {
        return key >= GLFW_KEY_SPACE && key <= GLFW_KEY_LAST && !isReserved(key);
    }

    /**
     * Раскладка для записи: имя действия → код клавиши. По имени, а не по
     * порядку: новое действие в середине перечисления иначе сдвинуло бы
     * игроку все клавиши в старом файле.
     */
    public Map<String, Integer> toMap() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Action a : Action.values())
            out.put(a.name(), key(a));
        return out;
    }

    /** Незнакомые имена и запрещённые клавиши пропускаются: там остаётся умолчание. */
    public void load(Map<String, Integer> saved) {
        reset();
        for (Map.Entry<String, Integer> e : saved.entrySet()) {
            Action a = byName(e.getKey());
            if (a != null && e.getValue() != null)
                set(a, e.getValue());
        }
    }

    private static Action byName(String name) {
        for (Action a : Action.values())
            if (a.name().equals(name))
                return a;
        return null;
    }

    /** Подпись клавиши так, как она написана на клавиатуре. */
    public static String keyName(int key) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z)
            return String.valueOf((char) key);
        if (key >= GLFW_KEY_0 && key <= GLFW_KEY_9)
            return String.valueOf((char) key);
        if (key >= GLFW_KEY_F1 && key <= GLFW_KEY_F25)
            return "F" + (key - GLFW_KEY_F1 + 1);
        if (key >= GLFW_KEY_KP_0 && key <= GLFW_KEY_KP_9)
            return "Num " + (key - GLFW_KEY_KP_0);
        return switch (key) {
            case GLFW_KEY_SPACE -> "Пробел";
            case GLFW_KEY_APOSTROPHE -> "'";
            case GLFW_KEY_COMMA -> ",";
            case GLFW_KEY_MINUS -> "-";
            case GLFW_KEY_PERIOD -> ".";
            case GLFW_KEY_SLASH -> "/";
            case GLFW_KEY_SEMICOLON -> ";";
            case GLFW_KEY_EQUAL -> "=";
            case GLFW_KEY_LEFT_BRACKET -> "[";
            case GLFW_KEY_BACKSLASH -> "\\";
            case GLFW_KEY_RIGHT_BRACKET -> "]";
            case GLFW_KEY_GRAVE_ACCENT -> "`";
            case GLFW_KEY_ESCAPE -> "Esc";
            case GLFW_KEY_ENTER -> "Enter";
            case GLFW_KEY_TAB -> "Tab";
            case GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW_KEY_INSERT -> "Insert";
            case GLFW_KEY_DELETE -> "Delete";
            case GLFW_KEY_RIGHT -> "Стрелка вправо";
            case GLFW_KEY_LEFT -> "Стрелка влево";
            case GLFW_KEY_DOWN -> "Стрелка вниз";
            case GLFW_KEY_UP -> "Стрелка вверх";
            case GLFW_KEY_PAGE_UP -> "Page Up";
            case GLFW_KEY_PAGE_DOWN -> "Page Down";
            case GLFW_KEY_HOME -> "Home";
            case GLFW_KEY_END -> "End";
            case GLFW_KEY_CAPS_LOCK -> "Caps Lock";
            case GLFW_KEY_SCROLL_LOCK -> "Scroll Lock";
            case GLFW_KEY_NUM_LOCK -> "Num Lock";
            case GLFW_KEY_PRINT_SCREEN -> "Print Screen";
            case GLFW_KEY_PAUSE -> "Pause";
            case GLFW_KEY_KP_DECIMAL -> "Num .";
            case GLFW_KEY_KP_DIVIDE -> "Num /";
            case GLFW_KEY_KP_MULTIPLY -> "Num *";
            case GLFW_KEY_KP_SUBTRACT -> "Num -";
            case GLFW_KEY_KP_ADD -> "Num +";
            case GLFW_KEY_KP_ENTER -> "Num Enter";
            case GLFW_KEY_KP_EQUAL -> "Num =";
            case GLFW_KEY_LEFT_SHIFT -> "Левый Shift";
            case GLFW_KEY_RIGHT_SHIFT -> "Правый Shift";
            case GLFW_KEY_LEFT_CONTROL -> "Левый Ctrl";
            case GLFW_KEY_RIGHT_CONTROL -> "Правый Ctrl";
            case GLFW_KEY_LEFT_ALT -> "Левый Alt";
            case GLFW_KEY_RIGHT_ALT -> "Правый Alt";
            case GLFW_KEY_LEFT_SUPER -> "Левый Win";
            case GLFW_KEY_RIGHT_SUPER -> "Правый Win";
            case GLFW_KEY_MENU -> "Menu";
            default -> "Клавиша " + key;
        };
    }
}
