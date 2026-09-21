package com.mineclone.game;

import java.util.ArrayList;
import java.util.List;

/**
 * F3 и сочетания с ним.
 *
 * <p>F3 сам по себе включает отладочный экран, но F3+H и F3+средняя кнопка —
 * отдельные команды, и отладка при них включаться не должна. Значит, решение
 * принимается на отпускании: пока клавиша зажата, ещё неизвестно, была ли это
 * команда или просто F3.
 *
 * <p>Без GL и без ввода: на вход три булевых значения, на выход список
 * действий. Поэтому проверяется обычным тестом, а не двумя руками на
 * клавиатуре.
 */
public final class DebugKeys {

    public enum Action { TOGGLE_DEBUG, TOGGLE_ADVANCED_TOOLTIPS, CYCLE_META }

    private boolean wasDown;
    private boolean comboUsed;

    /**
     * @param f3Down        зажата ли F3 прямо сейчас
     * @param hPressed      нажата ли H в этом кадре
     * @param middlePressed нажата ли средняя кнопка в этом кадре
     */
    public List<Action> update(boolean f3Down, boolean hPressed, boolean middlePressed) {
        List<Action> out = new ArrayList<>(1);
        if (f3Down && !wasDown)
            comboUsed = false;
        if (f3Down) {
            if (hPressed) {
                out.add(Action.TOGGLE_ADVANCED_TOOLTIPS);
                comboUsed = true;
            }
            if (middlePressed) {
                out.add(Action.CYCLE_META);
                comboUsed = true;
            }
        } else if (wasDown && !comboUsed) {
            out.add(Action.TOGGLE_DEBUG);
        }
        wasDown = f3Down;
        return out;
    }
}
