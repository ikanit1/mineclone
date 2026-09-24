package com.mineclone.world.entity;

import org.joml.Vector3f;

/**
 * Во что может попасть снаряд.
 *
 * Отдельный интерфейс, а не список мобов, по двум причинам. Во-первых, стрела
 * бьёт и в игрока, а он не моб. Во-вторых, так полёт снаряда проверяется
 * тестом на фиктивной мишени, без мира и без живого моба.
 */
public interface Hittable {

    /** Дистанция вдоль луча до AABB, либо −1, если мимо. */
    float rayHitDistance(Vector3f origin, Vector3f dir);

    /** Можно ли в это ещё попасть: труп стрела прошивает насквозь. */
    boolean hittable();

    /**
     * Принять попадание.
     *
     * @param fromPlayer снаряд выпущен игроком — от этого зависит, засчитать
     *                   ли добычу с убитого
     */
    void takeProjectile(float damage, float fromX, float fromZ, float knockback,
                        boolean fromPlayer);

    /**
     * Принять попадание от участника с номером {@code attacker}
     * ({@link com.mineclone.world.damage.DamageSource#NO_ATTACKER} — не от
     * участника): моб помнит, чья стрела его убила.
     */
    default void takeProjectile(float damage, float fromX, float fromZ, float knockback,
                                boolean fromPlayer, int attacker) {
        takeProjectile(damage, fromX, fromZ, knockback, fromPlayer);
    }

    /** Номер участника, если это он, иначе {@code NO_ATTACKER}: так стрела знает, кто её пустил. */
    default int participantId() {
        return com.mineclone.world.damage.DamageSource.NO_ATTACKER;
    }
}
