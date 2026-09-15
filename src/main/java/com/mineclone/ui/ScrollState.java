package com.mineclone.ui;

/**
 * Прокрутка списка: цель, к которой едем, и текущее смещение.
 *
 * <p>Колесо двигает цель, а смещение догоняет её по экспоненте — рывок на
 * целую строку за щелчок читается как перескок, а не как прокрутка.
 */
public final class ScrollState {

    /** Постоянная скорости догоняния, 1/с. */
    static final float EASE = 16f;

    private float offset;
    private float target;

    public float offset() {
        return offset;
    }

    public float target() {
        return target;
    }

    public float maxScroll(float contentH, float viewH) {
        return Math.max(0f, contentH - viewH);
    }

    public void scrollBy(float px, float contentH, float viewH) {
        target = clampTo(target + px, contentH, viewH);
    }

    /** Содержимое изменилось: цель и смещение не должны остаться за краем. */
    public void clamp(float contentH, float viewH) {
        target = clampTo(target, contentH, viewH);
        offset = Math.min(offset, maxScroll(contentH, viewH));
    }

    /** Докрутить так, чтобы строка [top, bottom] была видна целиком. */
    public void ensureVisible(float top, float bottom, float viewH) {
        if (top < target)
            target = top;
        else if (bottom > target + viewH)
            target = bottom - viewH;
    }

    /** Сразу к цели — при открытии экрана. */
    public void snap() {
        offset = target;
    }

    public void update(float dt) {
        float k = 1f - (float) Math.exp(-dt * EASE);
        offset += (target - offset) * k;
        if (Math.abs(target - offset) < 0.05f)
            offset = target;
    }

    private float clampTo(float v, float contentH, float viewH) {
        return Math.max(0f, Math.min(maxScroll(contentH, viewH), v));
    }
}
