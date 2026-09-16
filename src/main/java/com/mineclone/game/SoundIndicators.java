package com.mineclone.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Откуда пришёл громкий звук: дуги по краю экрана в сторону источника.
 *
 * Хранится мировое направление на источник, а не угол на экране: игрок
 * поворачивается, пока дуга гаснет, и она обязана поехать вместе с миром, а не
 * остаться приклеенной к тому месту экрана, где звук застал его.
 *
 * Только модель — без GL. Рисует {@link Hud}, решает, что считать громким,
 * {@code Game}.
 */
public final class SoundIndicators {

    /** Сколько живёт дуга, секунды. */
    public static final float LIFE = 1.8f;
    /** Тише этого звук не показывается: иначе экран мигает от каждого шага курицы. */
    public static final float MIN_STRENGTH = 0.15f;
    /**
     * Звук ближе этого угла к направлению взгляда не показывается: источник и
     * так в кадре, а дуга перед глазами только мешает целиться.
     */
    public static final float FRONT_HALF_ANGLE = 30f;
    /** Ближе этого угла новый звук обновляет старую дугу, а не заводит соседнюю. */
    public static final float MERGE_ANGLE = 22f;
    public static final int MAX = 10;

    /** Одна дуга. */
    public static final class Cue {
        /** Мировое направление на источник по горизонтали (единичное). */
        public float dirX, dirZ;
        public float strength;
        public float age;
        /** Враждебный источник рисуется тёплым цветом, прочие — нейтральным. */
        public boolean danger;

        /** 0..1 — сколько осталось яркости с учётом возраста. */
        public float alpha() {
            float k = 1f - age / LIFE;
            return Math.max(0f, k) * Math.min(1f, strength);
        }
    }

    private final List<Cue> cues = new ArrayList<>();

    /**
     * Звук из точки. Громкость уже с учётом расстояния и стен.
     *
     * @return true, если дуга добавлена или обновлена
     */
    public boolean add(float listenerX, float listenerZ, float yaw,
                       float sourceX, float sourceZ, float strength, boolean danger) {
        if (strength < MIN_STRENGTH)
            return false;
        float dx = sourceX - listenerX, dz = sourceZ - listenerZ;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.2f)
            return false;               // над ухом — направления у звука нет
        dx /= len;
        dz /= len;
        if (Math.abs(bearing(yaw, dx, dz)) < FRONT_HALF_ANGLE)
            return false;
        for (Cue c : cues) {
            float cos = c.dirX * dx + c.dirZ * dz;
            if (cos > (float) Math.cos(Math.toRadians(MERGE_ANGLE))) {
                c.dirX = dx;
                c.dirZ = dz;
                c.strength = Math.max(c.strength * (1f - c.age / LIFE), strength);
                c.age = 0f;
                c.danger |= danger;
                return true;
            }
        }
        if (cues.size() >= MAX)
            cues.remove(0);
        Cue c = new Cue();
        c.dirX = dx;
        c.dirZ = dz;
        c.strength = strength;
        c.danger = danger;
        cues.add(c);
        return true;
    }

    public void update(float dt) {
        cues.removeIf(c -> (c.age += dt) >= LIFE);
    }

    public List<Cue> cues() {
        return cues;
    }

    public void clear() {
        cues.clear();
    }

    /**
     * Угол на источник относительно взгляда, градусы: 0 — прямо, +90 — справа,
     * ±180 — за спиной.
     *
     * Вперёд у камеры — (sin yaw, −cos yaw), вправо — (cos yaw, sin yaw);
     * это ровно та геометрия, что тихо ломается при смене соглашения о yaw,
     * поэтому функция и вынесена под тест.
     */
    public static float bearing(float yaw, float dirX, float dirZ) {
        float fx = (float) Math.sin(yaw), fz = (float) -Math.cos(yaw);
        float rx = (float) Math.cos(yaw), rz = (float) Math.sin(yaw);
        float ahead = fx * dirX + fz * dirZ;
        float right = rx * dirX + rz * dirZ;
        return (float) Math.toDegrees(Math.atan2(right, ahead));
    }
}
