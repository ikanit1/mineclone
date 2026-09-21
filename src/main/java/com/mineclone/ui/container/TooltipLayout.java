package com.mineclone.ui.container;

/**
 * Куда положить подсказку, чтобы она не уехала за край экрана.
 *
 * <p>Обычное место — справа снизу от слота: там её не закрывает курсор. У
 * правого края она перекидывается влево, у нижнего — вверх. Если не помещается
 * никуда, её просто прижимают к экрану: показать урезанную подсказку лучше,
 * чем не показать никакой.
 */
public final class TooltipLayout {

    public enum Corner { RIGHT_BELOW, LEFT_BELOW, RIGHT_ABOVE, LEFT_ABOVE }

    public record Placement(Corner corner, float x, float y) {}

    private TooltipLayout() {}

    public static Placement place(float ax, float ay, float aw, float ah,
            float tw, float th, float screenW, float screenH, float gap, float margin) {
        for (Corner c : Corner.values()) {
            float x = cornerX(c, ax, aw, tw, gap);
            float y = cornerY(c, ay, ah, th, gap);
            if (x >= margin && x + tw <= screenW - margin
                    && y >= margin && y + th <= screenH - margin)
                return new Placement(c, x, y);
        }
        Corner c = Corner.RIGHT_BELOW;
        float x = clamp(cornerX(c, ax, aw, tw, gap), margin, screenW - margin - tw);
        float y = clamp(cornerY(c, ay, ah, th, gap), margin, screenH - margin - th);
        return new Placement(c, x, y);
    }

    private static float cornerX(Corner c, float ax, float aw, float tw, float gap) {
        return c == Corner.RIGHT_BELOW || c == Corner.RIGHT_ABOVE
                ? ax + aw + gap : ax - gap - tw;
    }

    private static float cornerY(Corner c, float ay, float ah, float th, float gap) {
        return c == Corner.RIGHT_BELOW || c == Corner.LEFT_BELOW
                ? ay + ah + gap : ay - gap - th;
    }

    private static float clamp(float v, float lo, float hi) {
        // Экран может оказаться уже подсказки — тогда верхняя граница ниже
        // нижней, и прижимать надо к началу, а не к отрицательной ширине.
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }
}
