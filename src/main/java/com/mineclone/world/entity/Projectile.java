package com.mineclone.world.entity;

import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import org.joml.Vector3f;

import java.util.List;

/**
 * Летящий снаряд: стрела и всё, что полетит после неё.
 *
 * <p>Не {@link ItemEntity}, и разница принципиальная. Предмет катится по земле
 * и ждёт, пока его подберут, — ему достаточно знать, где он сейчас. Снаряд
 * обязан попадать в цель <b>между кадрами</b>: на 28 блоках в секунду стрела
 * проходит полблока за кадр, и проверка «стою ли я внутри моба» её пропустит,
 * а на низком фпс она пролетит сквозь стену.
 *
 * <p>Поэтому шаг — не «сдвинуться и посмотреть», а <b>трассировка отрезка</b>
 * от старой позиции к новой: сначала ближайшее попадание в цель, потом в
 * блок. Туннелирование при этом лечится тем же механизмом, что и промахи, а
 * не отдельной заплатой.
 */
public final class Projectile {

    /** Дольше этого промах не висит в мире. */
    public static final float LIFETIME = 60f;
    /** Сколько воткнувшийся снаряд ждёт, пока его подберут. */
    public static final float STUCK_LIFETIME = 45f;
    /**
     * Длина подшага трассировки в блоках.
     *
     * Меньше половины блока, чтобы отрезок не перепрыгнул стену: то же
     * правило и по той же причине, что у {@code SoundOcclusion.STEP}.
     */
    private static final float STEP = 0.25f;
    /** Насколько снаряд отбрасывает цель относительно удара рукой. */
    public static final float KNOCKBACK = 0.6f;
    /** Ближе этого воткнувшийся снаряд подбирается. */
    public static final float PICKUP_RANGE = 1.4f;
    /** Гравитация снаряда: слабее, чем у предмета — стрела летит настильно. */
    public static final float GRAVITY = -16f;
    /** Сопротивление воздуха за секунду. */
    private static final float DRAG = 0.992f;

    public final Vector3f position = new Vector3f();
    public final Vector3f velocity = new Vector3f();
    /** Куда смотрит снаряд: у воткнувшегося это застывшее направление полёта. */
    public final Vector3f heading = new Vector3f(0f, 0f, 1f);
    /** Что вернётся при подборе. */
    public final String itemId;
    /** Кто выпустил: в него снаряд не попадает. */
    public final Hittable owner;
    /** Выпущен игроком — от этого зависит, засчитывать ли добычу. */
    public final boolean fromPlayer;
    public float damage;
    public float age;
    public boolean stuck;
    public boolean dead;

    public Projectile(String itemId, Hittable owner, boolean fromPlayer, float damage) {
        this.itemId = itemId;
        this.owner = owner;
        this.fromPlayer = fromPlayer;
        this.damage = damage;
    }

    /** Что случилось на этом шаге. */
    public enum Result { FLYING, HIT_TARGET, HIT_BLOCK, EXPIRED }

    /** В кого попали на этом шаге, если попали. */
    public Hittable struck;

    /**
     * Двигает снаряд на кадр.
     *
     * @param targets всё, во что можно попасть; владелец пропускается сам
     */
    public Result step(World world, float dt, List<? extends Hittable> targets) {
        struck = null;
        if (dead)
            return Result.EXPIRED;
        age += dt;
        if (stuck)
            return age > STUCK_LIFETIME ? expire() : Result.FLYING;
        if (age > LIFETIME)
            return expire();

        velocity.y += GRAVITY * dt;
        velocity.mul((float) Math.pow(DRAG, dt));
        if (velocity.lengthSquared() > 1e-8f)
            heading.set(velocity).normalize();

        float remaining = velocity.length() * dt;
        Vector3f dir = new Vector3f(velocity).normalize();
        while (remaining > 1e-5f) {
            float span = Math.min(STEP, remaining);
            Hittable victim = nearest(targets, dir, span);
            if (victim != null) {
                position.fma(Math.max(0f, hitDistance(victim, dir)), dir);
                victim.takeProjectile(damage, position.x, position.z, KNOCKBACK, fromPlayer);
                struck = victim;
                dead = true;
                return Result.HIT_TARGET;
            }
            Vector3f next = new Vector3f(position).fma(span, dir);
            if (blocks(world, next)) {
                stuck = true;
                velocity.set(0f);
                return Result.HIT_BLOCK;
            }
            position.set(next);
            remaining -= span;
        }
        return Result.FLYING;
    }

    /** Ближайшая цель на отрезке, кроме владельца. */
    private Hittable nearest(List<? extends Hittable> targets, Vector3f dir, float span) {
        if (targets == null)
            return null;
        Hittable best = null;
        float bestT = span;
        for (Hittable t : targets) {
            if (t == owner || t == null || !t.hittable())
                continue;
            float d = t.rayHitDistance(position, dir);
            if (d >= 0f && d <= bestT) {
                bestT = d;
                best = t;
            }
        }
        return best;
    }

    private float hitDistance(Hittable victim, Vector3f dir) {
        return victim.rayHitDistance(position, dir);
    }

    private static boolean blocks(World world, Vector3f at) {
        if (world == null)
            return false;
        BlockType b = world.getBlock((int) Math.floor(at.x), (int) Math.floor(at.y),
                (int) Math.floor(at.z));
        return b != BlockType.AIR && b.solid;
    }

    private Result expire() {
        dead = true;
        return Result.EXPIRED;
    }

    /** Можно ли подобрать: только воткнувшийся и только вблизи. */
    public boolean canPickUp(Vector3f by) {
        return stuck && !dead && position.distance(by) <= PICKUP_RANGE;
    }
}
