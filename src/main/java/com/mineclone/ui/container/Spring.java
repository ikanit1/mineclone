package com.mineclone.ui.container;

/**
 * Пружина: величина догоняет цель с заданной частотой и затуханием.
 *
 * <p>Не линейная интерполяция: у неё нет инерции, и всё, что ею двигают,
 * трогается и останавливается одинаково резко. Пружина же продолжает ехать
 * после того, как цель перестала меняться, — именно это и читается глазом
 * как «живое».
 *
 * <p>Шаг полу-неявный и дробится на подшаги не длиннее {@link #MAX_STEP}:
 * при просадке кадра явный шаг в 50 мс раскачивает пружину до бесконечности,
 * и подпрыгнувший интерфейс — последнее, что нужно в такой момент.
 */
public final class Spring {

    /** Самый длинный подшаг интегрирования. */
    public static final float MAX_STEP = 1f / 240f;

    public float value;
    public float velocity;

    private final float omega;
    private final float zeta;

    /**
     * @param frequency    колебаний в секунду — как быстро догоняет
     * @param dampingRatio 1 — без перелёта, меньше — с перелётом
     */
    public Spring(float frequency, float dampingRatio) {
        this.omega = (float) (2 * Math.PI * frequency);
        this.zeta = dampingRatio;
    }

    public Spring(float frequency, float dampingRatio, float start) {
        this(frequency, dampingRatio);
        this.value = start;
    }

    public void update(float target, float dt) {
        if (dt <= 0f)
            return;
        int steps = (int) Math.ceil(dt / MAX_STEP);
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = omega * omega * (target - value) - 2f * zeta * omega * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
    }

    /** Поставить на место без движения — при открытии окна. */
    public void snap(float v) {
        value = v;
        velocity = 0f;
    }

    public boolean settled(float target, float epsilon) {
        return Math.abs(value - target) < epsilon && Math.abs(velocity) < epsilon;
    }
}
