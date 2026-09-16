package com.mineclone.item;

/**
 * Класс инструмента: чем предмет копает быстро.
 *
 * <p>Отдельно от материала: уровень решает, что вообще даёт дроп, а класс —
 * что копается быстро. Меч стоит здесь заранее — оружие появляется планом B,
 * но блоки уже сейчас должны уметь сказать «мечом меня не копают».
 */
public enum ToolClass {
    PICKAXE, AXE, SHOVEL, SWORD;

    /** Имя из данных ({@code "pickaxe"}) или {@code null}, если такого класса нет. */
    public static ToolClass byName(String s) {
        for (ToolClass c : values())
            if (c.name().equalsIgnoreCase(s))
                return c;
        return null;
    }

    /** Как класс пишется в данных. */
    public String dataName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
