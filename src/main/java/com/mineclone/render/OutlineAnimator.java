package com.mineclone.render;

/**
 * Плавная рамка выделения: появляется и гаснет по альфе, а при переводе
 * прицела на соседний блок не прыгает, а доезжает до него.
 *
 * Скачок рамки на каждый новый блок читается как мерцание — особенно когда
 * игрок ведёт прицелом по стене. Короткий доезд в несколько сотых секунды
 * такое мерцание убирает, но не делает прицел «ватным»: рамка всегда в
 * пределах пары кадров от настоящего блока.
 *
 * Чистое состояние без GL — рисует {@link BlockOutline}.
 */
public final class OutlineAnimator {

    /** Постоянная времени доезда рамки, секунды. */
    public static final float SLIDE_TIME = 0.035f;
    /** Время появления и исчезновения. */
    public static final float FADE_IN = 0.08f, FADE_OUT = 0.12f;
    /** Дальше этого рамка не доезжает, а перескакивает: прицел ушёл на другой край мира. */
    public static final float SNAP_DISTANCE = 3.5f;

    /** minX, minY, minZ, maxX, maxY, maxZ — мировые координаты. */
    private final float[] box = new float[6];
    private float alpha;
    private boolean visible;

    /**
     * @param target бокс блока под прицелом (6 чисел) или null — прицел в пустоте
     */
    public void update(float dt, float[] target) {
        if (target == null) {
            alpha = Math.max(0f, alpha - dt / FADE_OUT);
            if (alpha <= 0f)
                visible = false;
            return;
        }
        if (!visible || alpha <= 0f || farFrom(target)) {
            System.arraycopy(target, 0, box, 0, 6);
            visible = true;
        } else {
            float k = 1f - (float) Math.exp(-dt / SLIDE_TIME);
            for (int i = 0; i < 6; i++)
                box[i] += (target[i] - box[i]) * k;
        }
        alpha = Math.min(1f, alpha + dt / FADE_IN);
    }

    private boolean farFrom(float[] t) {
        float dx = (t[0] + t[3]) - (box[0] + box[3]);
        float dy = (t[1] + t[4]) - (box[1] + box[4]);
        float dz = (t[2] + t[5]) - (box[2] + box[5]);
        return dx * dx + dy * dy + dz * dz > 4f * SNAP_DISTANCE * SNAP_DISTANCE;
    }

    public float alpha() {
        return alpha;
    }

    /** Текущий бокс рамки; пока рамки нет, содержимое не определено. */
    public float[] box() {
        return box;
    }

    public boolean visible() {
        return visible && alpha > 0f;
    }

    /** Мягкая пульсация свечения 0.8..1: живая рамка, но не мигалка. */
    public static float pulse(float time) {
        return 0.9f + 0.1f * (float) Math.sin(time * 3.1);
    }
}
