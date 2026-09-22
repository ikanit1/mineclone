package com.mineclone;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.EntityPhysics;
import com.mineclone.world.entity.Hittable;
import com.mineclone.world.entity.Projectile;
import org.joml.Vector3f;

import java.util.List;

/**
 * Снаряд обязан попадать между кадрами, а не «стоять внутри цели».
 *
 * Ровно это отличает его от {@code ItemEntity} и ровно это невозможно
 * заметить на глаз: промах из-за высокой скорости выглядит как промах.
 */
final class ProjectileTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("an arrow hits a target it would fly past in one frame", ProjectileTests::tunnelTarget);
        r.run("an arrow never passes through a wall, however fast", ProjectileTests::tunnelWall);
        r.run("an arrow does not hit whoever fired it", ProjectileTests::owner);
        r.run("a stuck arrow waits to be picked up, a lost one expires", ProjectileTests::stuck);
        r.run("a shooter leads its shot so the arrow actually arrives", ProjectileTests::lead);
        r.run("a melee species never takes the shooting branch", ProjectileTests::meleeNeverShoots);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** Мишень в точке с габаритами моба; считает попадания. */
    private static final class Dummy implements Hittable {
        final Vector3f at;
        final float w = 0.6f, h = 1.8f;
        int hits;
        float took;
        boolean alive = true;

        Dummy(float x, float y, float z) { at = new Vector3f(x, y, z); }

        @Override public float rayHitDistance(Vector3f origin, Vector3f dir) {
            return EntityPhysics.rayAabbDistance(origin.x, origin.y, origin.z, dir.x, dir.y, dir.z,
                    at.x - w / 2, at.y, at.z - w / 2, at.x + w / 2, at.y + h, at.z + w / 2);
        }
        @Override public boolean hittable() { return alive; }
        @Override public void takeProjectile(float damage, float fx, float fz, float kb,
                                             boolean fromPlayer) {
            hits++;
            took += damage;
        }
    }

    private static World flat(int floorY) {
        World w = new World(4242L);
        for (int x = -40; x <= 40; x++)
            for (int z = -8; z <= 8; z++) {
                Chunk c = w.getChunk(Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z));
                int lx = Math.floorMod(x, Chunk.SIZE_X), lz = Math.floorMod(z, Chunk.SIZE_Z);
                for (int y = 0; y < Chunk.SIZE_Y; y++)
                    c.set(lx, y, lz, y <= floorY ? BlockType.STONE : BlockType.AIR);
            }
        return w;
    }

    /**
     * Главное свойство. Мишень в 12 блоках, стрела летит 60 блоков/с, кадр —
     * четверть секунды: за один шаг снаряд проходит 15 блоков, то есть
     * оказывается сразу за целью, ни разу не побывав внутри неё. Проверка
     * «стою ли я в мобе» тут не сработала бы ни на одном кадре.
     */
    private static void tunnelTarget() {
        World w = flat(40);
        Dummy target = new Dummy(12f, 41f, 0f);
        Projectile p = new Projectile("arrow", null, true, 6f);
        p.position.set(0f, 42f, 0f);
        p.velocity.set(60f, 0f, 0f);
        Projectile.Result last = Projectile.Result.FLYING;
        for (int i = 0; i < 20 && last == Projectile.Result.FLYING; i++)
            last = p.step(w, 0.25f, List.of(target));
        check(last == Projectile.Result.HIT_TARGET,
                "a fast arrow must still hit, got " + last + " after " + p.position.x + " blocks");
        check(target.hits == 1, "and hit exactly once, got " + target.hits);
        check(target.took == 6f, "carrying its damage, got " + target.took);
    }

    /** То же самое для стены: сквозь неё снаряд не проходит ни на какой скорости. */
    private static void tunnelWall() {
        for (float speed : new float[] { 10f, 60f, 200f, 900f }) {
            World w = flat(40);
            for (int y = 41; y <= 46; y++)
                w.setBlock(15, y, 0, BlockType.STONE);
            Projectile p = new Projectile("arrow", null, true, 6f);
            p.position.set(0f, 42.5f, 0.5f);
            p.velocity.set(speed, 0f, 0f);
            Projectile.Result last = Projectile.Result.FLYING;
            for (int i = 0; i < 60 && last == Projectile.Result.FLYING; i++)
                last = p.step(w, 0.25f, List.of());
            check(last == Projectile.Result.HIT_BLOCK,
                    "speed " + speed + ": expected the wall, got " + last);
            check(p.position.x <= 16f,
                    "speed " + speed + ": stopped past the wall at x=" + p.position.x);
        }
    }

    /** Выстрел в упор вниз не должен убивать стрелка. */
    private static void owner() {
        World w = flat(40);
        Dummy shooter = new Dummy(0f, 41f, 0f);
        Dummy other = new Dummy(6f, 41f, 0f);
        Projectile p = new Projectile("arrow", shooter, true, 6f);
        p.position.set(0f, 42f, 0f);
        p.velocity.set(30f, 0f, 0f);
        Projectile.Result last = Projectile.Result.FLYING;
        for (int i = 0; i < 40 && last == Projectile.Result.FLYING; i++)
            last = p.step(w, 1f / 60f, List.of(shooter, other));
        check(shooter.hits == 0, "the shooter must never be hit by their own shot");
        check(other.hits == 1, "but the target must be, got " + other.hits);
    }

    /**
     * Упреждение по высоте: без него стрелок мажет тем сильнее, чем дальше
     * цель, и на глаз это неотличимо от «промазал». Симулируем выстрел и
     * смотрим, попал ли.
     */
    private static void lead() {
        World w = flat(40);
        for (float range : new float[] { 5f, 10f, 18f, 26f }) {
            Dummy target = new Dummy(range, 41f, 0f);
            float ex = 0f, ey = 42.3f, ez = 0f;
            var aim = com.mineclone.world.entity.Mob.aimWithLead(ex, ey, ez,
                    target.at.x, target.at.y + 1.1f, target.at.z,
                    com.mineclone.world.entity.Mob.SHOOT_SPEED);
            check(aim != null, "range " + range + ": the shot must be aimable");
            check(aim.y > 0f || range < 6f, "range " + range + ": distant shots must be lifted");
            Projectile p = new Projectile("arrow", null, false, 4f);
            p.position.set(ex, ey, ez);
            p.velocity.set(aim);
            Projectile.Result last = Projectile.Result.FLYING;
            for (int i = 0; i < 600 && last == Projectile.Result.FLYING; i++)
                last = p.step(w, 1f / 60f, List.of(target));
            check(last == Projectile.Result.HIT_TARGET,
                    "range " + range + ": expected a hit, got " + last);
        }
        // Вырожденный случай: цель там же, где стрелок.
        check(com.mineclone.world.entity.Mob.aimWithLead(0, 40, 0, 0, 40, 0, 20f) == null,
                "a shot at oneself has no direction");
    }

    /** Ближний вид не должен стрелять: дальность у него нулевая. */
    private static void meleeNeverShoots() {
        for (var type : com.mineclone.world.entity.MobType.values())
            check(type.rangedRange == 0f,
                    type + " must not shoot yet: the first shooter comes with the new mobs");
    }

    /** Воткнувшийся снаряд ждёт подбора, потерянный — исчезает сам. */
    private static void stuck() {
        World w = flat(40);
        Projectile p = new Projectile("arrow", null, true, 6f);
        p.position.set(0f, 44f, 0.5f);
        p.velocity.set(2f, -6f, 0f);
        Projectile.Result last = Projectile.Result.FLYING;
        for (int i = 0; i < 600 && last == Projectile.Result.FLYING; i++)
            last = p.step(w, 1f / 60f, List.of());
        check(last == Projectile.Result.HIT_BLOCK, "it must land in the ground, got " + last);
        check(p.stuck && !p.dead, "and stay there to be picked up");
        check(p.canPickUp(new Vector3f(p.position)), "close by it can be taken");
        check(!p.canPickUp(new Vector3f(p.position).add(8f, 0f, 0f)), "from afar it cannot");

        // Забытый снаряд не копится в мире вечно.
        for (int i = 0; i < 60 * 60 && !p.dead; i++)
            p.step(w, 1f / 60f, List.of());
        check(p.dead, "a forgotten arrow eventually goes away");
    }
}
