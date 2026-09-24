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
 * Рамка идёт по рёбрам формы блока ({@code Shapes.edges}, BLK-02): у ступени
 * это Г-образный контур, у факела — его палочка. Доезжает бокс, описанный
 * вокруг формы, а рёбра цели переносятся в него: форма меняется сразу, место —
 * плавно.
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
    /** Рёбра цели в мировых координатах и бокс, в котором они измерены. */
    private final float[] targetEdges = new float[com.mineclone.world.shape.Shapes.MAX_EDGES * 6];
    private final float[] targetBox = new float[6];
    private int edgeCount;
    private final float[] boxScratch = new float[12 * 6];

    /**
     * @param target бокс вокруг формы под прицелом или null — прицел в пустоте
     * @param edges  рёбра формы, по шесть чисел, в мировых координатах
     * @param count  сколько рёбер
     */
    public void update(float dt, float[] target, float[] edges, int count) {
        if (target != null) {
            System.arraycopy(edges, 0, targetEdges, 0, count * 6);
            edgeCount = count;
            System.arraycopy(target, 0, targetBox, 0, 6);
        }
        slide(dt, target);
    }

    /**
     * Рамка по двенадцати рёбрам одного бокса.
     *
     * @param target бокс блока под прицелом (6 чисел) или null — прицел в пустоте
     */
    public void update(float dt, float[] target) {
        if (target == null) {
            slide(dt, null);
            return;
        }
        update(dt, target, boxScratch, boxEdges(target, boxScratch));
    }

    /** Двенадцать рёбер бокса, по шесть чисел; возвращает их число. */
    public static int boxEdges(float[] b, float[] out) {
        int n = 0;
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            for (int corner = 0; corner < 4; corner++) {
                int o = n++ * 6;
                float pu = b[(corner & 1) == 0 ? u : u + 3], pv = b[(corner & 2) == 0 ? v : v + 3];
                out[o + axis] = b[axis];
                out[o + 3 + axis] = b[axis + 3];
                out[o + u] = pu;
                out[o + 3 + u] = pu;
                out[o + v] = pv;
                out[o + 3 + v] = pv;
            }
        }
        return n;
    }

    /**
     * Рёбра цели, перенесённые в текущий, ещё доезжающий бокс.
     *
     * @return сколько рёбер записано в out (шесть чисел на ребро)
     */
    public int edges(float[] out) {
        for (int i = 0; i < edgeCount * 6; i++) {
            int axis = i % 3;
            float lo = targetBox[axis], span = targetBox[axis + 3] - lo;
            float f = span > 1e-6f ? (targetEdges[i] - lo) / span : 0f;
            out[i] = box[axis] + f * (box[axis + 3] - box[axis]);
        }
        return edgeCount;
    }

    private void slide(float dt, float[] target) {
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
