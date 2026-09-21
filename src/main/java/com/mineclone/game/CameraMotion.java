package com.mineclone.game;

/**
 * Ощущение массы тела в камере от первого лица: крен в повороте и стрейфе,
 * инерционный кивок при разгоне и торможении, проседание при посадке.
 *
 * Всё на пружинах, а не на прямом присваивании: камера, которая наклоняется
 * ровно на угол поворота мыши, дёргается вместе с рукой и укачивает. Пружина
 * догоняет цель с запаздыванием и мягко перелетает — это и читается как вес.
 *
 * Чистая математика без GL: считается и проверяется тестами, а Game только
 * накладывает результат на матрицу вида.
 */
public final class CameraMotion {

    /** Крен на полной скорости стрейфа, радианы (около двух градусов). */
    public static final float STRAFE_LEAN = 0.034f;
    /** Крен на радиан в секунду поворота. */
    public static final float TURN_LEAN = 0.0045f;
    /** Предел крена: дальше это уже не вес, а качка. */
    public static final float MAX_LEAN = 0.055f;
    /** Кивок на единицу продольного ускорения (блоков/с²), радианы. */
    public static final float ACCEL_PITCH = 0.0022f;
    public static final float MAX_PITCH = 0.03f;
    /** Проседание камеры на блок падения и его предел. Посадка ощутима, но не укачивает. */
    public static final float LAND_DIP = 0.03f, MAX_DIP = 0.12f;

    private static final float ROLL_STIFFNESS = 70f, ROLL_DAMPING = 13f;
    private static final float PITCH_STIFFNESS = 90f, PITCH_DAMPING = 15f;
    private static final float DIP_STIFFNESS = 120f, DIP_DAMPING = 14f;

    private float roll, rollVel;
    private float pitch, pitchVel;
    private float dip, dipVel;
    private float lastForward;
    private boolean primed;

    /**
     * @param yawRate      скорость поворота, рад/с (плюс — вправо)
     * @param strafeSpeed  скорость вбок, блоков/с (плюс — вправо)
     * @param forwardSpeed скорость вперёд, блоков/с
     * @param landFall     высота только что закончившегося падения, блоки; 0 — не приземлялись
     * @param enabled      выключено — всё плавно возвращается в ноль
     */
    public void update(float dt, float yawRate, float strafeSpeed, float forwardSpeed,
                       float landFall, boolean enabled) {
        if (dt <= 0f)
            return;
        float accel = primed ? (forwardSpeed - lastForward) / dt : 0f;
        lastForward = forwardSpeed;
        primed = true;

        float rollTarget = 0f, pitchTarget = 0f;
        if (enabled) {
            // В поворот камеру заваливает внутрь, как на вираже; в стрейф —
            // в сторону движения.
            rollTarget = clamp(strafeSpeed / Player.SPRINT_SPEED * STRAFE_LEAN
                    + yawRate * TURN_LEAN, MAX_LEAN);
            // Разгон — голова отстаёт назад, торможение — клюёт вперёд.
            pitchTarget = clamp(accel * ACCEL_PITCH, MAX_PITCH);
            if (landFall > 0.6f)
                dipVel -= Math.min(MAX_DIP * 9f, landFall * LAND_DIP * 9f);
        }

        rollVel += (ROLL_STIFFNESS * (rollTarget - roll) - ROLL_DAMPING * rollVel) * dt;
        roll += rollVel * dt;
        pitchVel += (PITCH_STIFFNESS * (pitchTarget - pitch) - PITCH_DAMPING * pitchVel) * dt;
        pitch += pitchVel * dt;
        dipVel += (DIP_STIFFNESS * (0f - dip) - DIP_DAMPING * dipVel) * dt;
        dip += dipVel * dt;
        dip = Math.max(-MAX_DIP, Math.min(MAX_DIP * 0.3f, dip));
    }

    /**
     * Крен камеры, радианы; плюс — камера валится вправо (картинка при этом
     * поворачивается против часовой стрелки).
     */
    public float roll() { return roll; }

    /** Кивок камеры, радианы; плюс — вверх. */
    public float pitch() { return pitch; }

    /** Смещение глаз по вертикали, блоки; минус — вниз. */
    public float dip() { return dip; }

    public void reset() {
        roll = rollVel = pitch = pitchVel = dip = dipVel = 0f;
        primed = false;
    }

    private static float clamp(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }
}
