package com.mineclone.item;

import com.mineclone.world.BlockType;

/**
 * Инструментальная часть предмета: класс, уровень материала и скорость.
 *
 * <p>Отдельной записью, а не полями {@link Item}: предмет без инструментальной
 * части не должен носить с собой три бессмысленных нуля, а проверка
 * {@code tool != null} читается лучше, чем {@code level > 0}.
 */
public record ToolSpec(ToolClass toolClass, int level, float speed) {

    /** Подходит ли инструмент к блоку — только по классу, без учёта уровня. */
    public boolean suits(BlockType block) {
        return block != null && block.preferredTool() == toolClass;
    }
}
