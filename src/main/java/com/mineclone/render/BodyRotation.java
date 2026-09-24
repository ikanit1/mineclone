package com.mineclone.render;

/**
 * Голова и корпус поворачиваются по отдельности.
 *
 * <p>Раньше модель целиком крутилась за камерой, и человек с шагом мыши
 * разворачивался всем телом мгновенно — так двигается флюгер, а не игрок.
 * Здесь три угла, как в Minecraft: голова всегда смотрит туда же, куда
 * камера, корпус живёт своей жизнью, а между ними держится предел
 * {@link #MAX_OFFSET}.
 *
 * <p>Правило корпуса простое: на ходу он доворачивается к направлению
 * движения — пошёл вбок, и плечи развернулись вбок. На месте он не двигается
 * вовсе, пока голова не отвернулась дальше предела; отвернулась — корпус
 * плавно подтягивается ровно до предела, а не до головы.
 *
 * <p>Предел проверяется и на ходу тоже, хотя разворот к движению этого вроде
 * бы не требует: при ходьбе боком направление движения отстоит от взгляда на
 * прямой угол, и без этой проверки шея выворачивалась бы на девяносто
 * градусов. Minecraft ограничивает разницу точно так же и без оглядки на то,
 * идёт игрок или стоит.
 *
 * <p>Класс без GL и без мира: им одинаково живут и свой игрок в третьем лице,
 * и чужие игроки в комнате, и проверяется он обычным тестом.
 */
public final class BodyRotation {

    /** Насколько голова может отвернуться от корпуса: 75°, как в Minecraft. */
    public static final float MAX_OFFSET = (float) Math.toRadians(75.0);
    /** Скорость, с которой корпус догоняет цель, 1/с. */
    private static final float TURN_RATE = 10f;
    /** Ниже этой горизонтальной скорости игрок считается стоящим, блоков в секунду. */
    private static final float MOVING_SPEED = 0.35f;

    /** Курс корпуса, радианы. Им повёрнута вся модель. */
    public float bodyYaw;
    /** Курс головы — всегда курс камеры. */
    public float headYaw;
    /** Наклон головы, радианы; корпус наклоняться не умеет. */
    public float headPitch;
    private boolean primed;

    /**
     * Кадр.
     *
     * @param cameraYaw   курс камеры, радианы
     * @param cameraPitch наклон камеры, радианы
     * @param velX,velZ   горизонтальная скорость, блоков в секунду
     */
    public void update(float dt, float cameraYaw, float cameraPitch, float velX, float velZ) {
        headYaw = cameraYaw;
        headPitch = clampPitch(cameraPitch);
        if (!primed) {
            // Первый кадр: корпус встаёт под голову, иначе модель появляется
            // спиной вперёд и доворачивается на глазах.
            primed = true;
            bodyYaw = cameraYaw;
            return;
        }
        float step = dt <= 0f ? 0f : 1f - (float) Math.exp(-dt * TURN_RATE);
        float speed = (float) Math.sqrt(velX * velX + velZ * velZ);
        if (speed > MOVING_SPEED) {
            // Курс камеры при нулевом угле смотрит по −Z — направление
            // движения переводится в ту же систему.
            float moveYaw = (float) Math.atan2(velX, -velZ);
            // При движении назад плечи остаются направлены вперёд: иначе
            // корпус пытается развернуться на 180°, а шея упирается в предел.
            if (Math.abs(wrap(moveYaw - headYaw)) > (float) Math.PI / 2f)
                moveYaw = wrap(moveYaw + (float) Math.PI);
            bodyYaw = lerpAngle(bodyYaw, moveYaw, step);
        }
        // Предел держится всегда: и стоя, и на ходу боком.
        float offset = wrap(headYaw - bodyYaw);
        if (Math.abs(offset) > MAX_OFFSET) {
            float target = headYaw - Math.signum(offset) * MAX_OFFSET;
            bodyYaw = lerpAngle(bodyYaw, target, step);
            // Подтягивание плавное, но за предел заезжать нельзя ни на кадр:
            // рывок мыши за один кадр отворачивает голову дальше, чем корпус
            // успевает доехать.
            offset = wrap(headYaw - bodyYaw);
            if (Math.abs(offset) > MAX_OFFSET)
                bodyYaw = headYaw - Math.signum(offset) * MAX_OFFSET;
        }
        bodyYaw = wrap(bodyYaw);
    }

    /** Поставить оба угла разом — после появления, телепорта и смены мира. */
    public void snap(float yaw, float pitch) {
        headYaw = yaw;
        bodyYaw = yaw;
        headPitch = clampPitch(pitch);
        primed = true;
    }

    /** На сколько голова отвёрнута от корпуса, радианы в диапазоне ±{@link #MAX_OFFSET}. */
    public float headOffset() {
        return wrap(headYaw - bodyYaw);
    }

    private static float clampPitch(float pitch) {
        float limit = (float) (Math.PI / 2.0);
        return pitch < -limit ? -limit : (pitch > limit ? limit : pitch);
    }

    /**
     * Сглаживание по кратчайшей дуге.
     *
     * <p>Обычный {@code lerp} по углам на переходе через ±180° едет длинной
     * дорогой — модель прокручивается вокруг себя там, где должна была
     * довернуться на градус.
     */
    public static float lerpAngle(float from, float to, float t) {
        float k = t < 0f ? 0f : (t > 1f ? 1f : t);
        return wrap(from + wrap(to - from) * k);
    }

    /** Привести угол к диапазону (−π, π]. */
    public static float wrap(float angle) {
        float a = angle % (float) (Math.PI * 2.0);
        if (a > (float) Math.PI)
            a -= (float) (Math.PI * 2.0);
        if (a <= -(float) Math.PI)
            a += (float) (Math.PI * 2.0);
        return a;
    }
}
