package com.mineclone.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Навигация меню: стек экранов, шаг назад и фейды между ними.
 *
 * <p>Переход — это не мгновенная подмена. Старый экран гаснет за
 * {@link #FADE_OUT}, новый проявляется за {@link #FADE_IN} и чуть всплывает
 * снизу. Ввод получает только верхний экран, и сразу: ждать конца анимации,
 * чтобы нажать кнопку, раздражает сильнее, чем помогает.
 *
 * <p>Снятый экран закрывается ({@link Screen#closed}) не в момент снятия, а
 * когда догаснет: пока он гаснет, его рисуют, и отпущенная к этому времени
 * текстура превью мира дала бы ошибку GL в последних кадрах перехода.
 *
 * <p>Логика переходов без GL — тесты водят стек через {@link #handle} и
 * {@link #update}, а рисует его {@link #frame}.
 */
public final class ScreenStack {

    /** За сколько секунд гаснет уходящий экран. */
    public static final float FADE_OUT = 0.09f;
    /** За сколько секунд проявляется новый. */
    public static final float FADE_IN = 0.16f;
    /** Насколько новый экран всплывает снизу, пиксели. */
    static final float RISE = 10f;

    private final Deque<Screen> stack = new ArrayDeque<>();
    private Screen leaving;
    private float leaveT = 1f;
    private float enterT = 1f;

    /** Новый корень: всё прежнее закрывается сразу, без фейда. */
    public void reset(Screen root) {
        clear();
        stack.push(root);
        enterT = 0f;
    }

    public void push(Screen s) {
        Screen old = stack.peek();
        stack.push(s);
        beginLeaving(old);
    }

    /** Снять верхний экран. Корень не снимается — это решает игра. */
    public void pop() {
        if (stack.size() <= 1)
            return;
        Screen gone = stack.pop();
        beginLeaving(gone);
        stack.peek().resumed();
    }

    /** Убрать всё: например, игра вернулась из паузы. */
    public void clear() {
        finishLeaving();
        while (!stack.isEmpty())
            stack.pop().closed();
    }

    private void beginLeaving(Screen outgoing) {
        finishLeaving();
        leaving = outgoing;
        leaveT = 0f;
        enterT = 0f;
    }

    /** Догасить уходящий экран немедленно; закрыть, если его нет в стеке. */
    private void finishLeaving() {
        if (leaving != null && !stack.contains(leaving))
            leaving.closed();
        leaving = null;
        leaveT = 1f;
    }

    public Screen top() {
        return stack.peek();
    }

    public int depth() {
        return stack.size();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    /** Уходящий экран, пока он ещё гаснет, иначе null. */
    public Screen leaving() {
        return leaving;
    }

    public void update(float dt) {
        enterT = Math.min(1f, enterT + dt / FADE_IN);
        if (leaving != null) {
            leaveT += dt / FADE_OUT;
            if (leaveT >= 1f)
                finishLeaving();
        }
    }

    /** Непрозрачность экрана в этом кадре, 0..1. */
    public float alpha(Screen s) {
        if (s == null)
            return 0f;
        if (s == top())
            return ease(enterT);
        if (s == leaving)
            return 1f - ease(leaveT);
        return 0f;
    }

    /** Смещение верхнего экрана вниз, пока он всплывает. */
    public float rise() {
        return (1f - ease(enterT)) * RISE;
    }

    public boolean acceptsInput(Screen s) {
        return s != null && s == top();
    }

    /**
     * Разобрать действие экрана: переходы съедаются здесь, остальное уходит
     * игре. Назад с корня тоже уходит игре — из паузы это «вернуться в игру»,
     * а с титульного экрана ничего.
     */
    public MenuAction handle(MenuAction a) {
        if (a == null)
            return MenuAction.NONE;
        switch (a.kind) {
            case PUSH -> {
                if (a.screen != null)
                    push(a.screen);
                return MenuAction.NONE;
            }
            case BACK -> {
                if (stack.size() > 1) {
                    pop();
                    return MenuAction.NONE;
                }
                return a;
            }
            default -> {
                return a;
            }
        }
    }

    /**
     * Нарисовать кадр меню: уходящий экран без ввода, затем верхний с вводом.
     * Esc разбирается после отрисовки — экран обязан успеть нарисоваться в
     * кадре, где его закрывают, иначе он мигнёт пустотой.
     */
    public MenuAction frame(MenuTheme theme) {
        update(theme.dt());
        if (leaving != null) {
            theme.beginScreen(alpha(leaving), 0f, false);
            leaving.draw(theme);
            theme.endScreen();
        }
        Screen top = top();
        if (top == null)
            return MenuAction.NONE;
        theme.beginScreen(alpha(top), rise(), true);
        MenuAction a = top.draw(theme);
        if ((a == null || a.kind == MenuAction.Kind.NONE) && theme.input().escape())
            a = top.escape();
        theme.endScreen();
        return handle(a);
    }

    private static float ease(float t) {
        float k = Math.max(0f, Math.min(1f, t));
        return k * k * (3f - 2f * k);
    }
}
