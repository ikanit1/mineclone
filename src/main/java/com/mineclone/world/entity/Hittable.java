package com.mineclone.world.entity;

import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.damage.Damageable;
import org.joml.Vector3f;

/**
 * Во что может попасть снаряд.
 *
 * Отдельный интерфейс, а не список мобов, по двум причинам. Во-первых, стрела
 * бьёт и в игрока, а он не моб. Во-вторых, так полёт снаряда проверяется
 * тестом на фиктивной мишени, без мира и без живого моба. Попадание — обычный
 * {@link #damage урон} с источником {@link DamageType#PROJECTILE}.
 */
public interface Hittable extends Damageable {

    /** Дистанция вдоль луча до AABB, либо −1, если мимо. */
    float rayHitDistance(Vector3f origin, Vector3f dir);

    /** Можно ли в это ещё попасть: труп стрела прошивает насквозь. */
    boolean hittable();

    /**
     * Адаптер (SURV-01): попадание без стрелка. Высота точки удара старому
     * вызову неизвестна — отброс её и не читает.
     *
     * @param fromPlayer снаряд выпущен игроком — от этого зависит, засчитать
     *                   ли добычу с убитого
     */
    default void takeProjectile(float damage, float fromX, float fromZ, float knockback,
                                boolean fromPlayer) {
        damage(fromPlayer
                ? DamageSource.byPlayer(DamageType.PROJECTILE, DamageSource.NO_ATTACKER, fromX, 0f, fromZ, knockback)
                : DamageSource.byMob(DamageType.PROJECTILE, null, fromX, 0f, fromZ, knockback), damage);
    }

    /** Номер участника, если это он, иначе {@code NO_ATTACKER}: так стрела знает, кто её пустил. */
    default int participantId() {
        return DamageSource.NO_ATTACKER;
    }
}
