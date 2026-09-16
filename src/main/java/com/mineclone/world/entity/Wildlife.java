package com.mineclone.world.entity;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import org.joml.Vector3f;

import java.util.List;

/**
 * Поведение диких зверей: кролик, волк, птица.
 *
 * У скотины одна реакция — бежать от удара. Дикий зверь реагирует на игрока,
 * ещё не получив удара: кролик бросается наутёк, стоит подойти, птица
 * вспархивает и садится поодаль, волк не трогает игрока, пока тот не напал,
 * но гоняет кроликов и кур — и если игрок ударил одного, на него идёт вся стая.
 *
 * Правила — статические функции над полями моба; память живёт в самом
 * {@link Mob}, как и у дерева поведения нежити. Кто кому угроза и кто чья
 * добыча, раз в тик раскладывает {@link #sense}.
 */
public final class Wildlife {
    private Wildlife() {}

    // --- кролик ---
    /** Ближе этого игрок пугает кролика. */
    public static final float RABBIT_ALERT = 6f;
    /** Ближе этого пугает хищник. */
    public static final float THREAT_ALERT = 9f;
    /** Вертикальная скорость прыжка кролика. */
    private static final float HOP_VELOCITY = 5.4f;
    /** Кролик успокаивается, когда опасность дальше этого. */
    private static final float CALM_DISTANCE = 12f;

    // --- волк ---
    /** Сколько секунд волк помнит обиду на игрока. */
    public static final float ANGER_TIME = 25f;
    /** Радиус, в котором стая подхватывает злость. */
    public static final float PACK_RANGE = 14f;
    /** Дальше этого волк добычу не замечает. */
    public static final float PREY_RANGE = 16f;
    /** Укус волка — сердце. */
    public static final float BITE_DAMAGE = 2f;
    private static final float BITE_RANGE = 1.4f;
    private static final float BITE_COOLDOWN = 0.9f;
    /** Сытый волк не охотится столько секунд. */
    private static final float SATED_MIN = 60f, SATED_MAX = 140f;

    // --- птица ---
    /** Ближе этого игрок вспугивает птицу. */
    public static final float BIRD_ALERT = 7f;
    /** Сколько секунд длится полёт до посадки. */
    private static final float FLIGHT_MIN = 4f, FLIGHT_MAX = 9f;
    /** Скорость разворота в полёте: доля желаемой скорости за секунду. */
    private static final float FLY_STEER = 2.6f;

    /** Скотина бежит от хищника, если тот ближе этого. */
    public static final float PASSIVE_THREAT = 6f;

    /**
     * Раскладывает угрозы, добычу и злость стаи. Зовётся раз в тик перед
     * обновлением мобов — так же, как {@link MobHerd}.
     */
    public static void sense(List<Mob> mobs) {
        for (Mob m : mobs) {
            m.threatDist = Float.MAX_VALUE;
            if (m.type == MobType.WOLF)
                m.prey = null;
        }
        float preyRangeSq = PREY_RANGE * PREY_RANGE;
        float packRangeSq = PACK_RANGE * PACK_RANGE;
        for (int i = 0; i < mobs.size(); i++) {
            Mob a = mobs.get(i);
            if (a.dead)
                continue;
            for (int j = i + 1; j < mobs.size(); j++) {
                Mob b = mobs.get(j);
                if (b.dead)
                    continue;
                float dx = b.position.x - a.position.x, dz = b.position.z - a.position.z;
                float ds = dx * dx + dz * dz;
                boolean bPredator = b.type == MobType.WOLF || b.type.hostile;
                boolean aPredator = a.type == MobType.WOLF || a.type.hostile;
                if (bPredator && a.type != MobType.WOLF && !a.type.hostile
                        && ds < a.threatDist * a.threatDist) {
                    a.threatDist = (float) Math.sqrt(ds);
                    a.threatX = b.position.x;
                    a.threatZ = b.position.z;
                }
                if (aPredator && b.type != MobType.WOLF && !b.type.hostile
                        && ds < b.threatDist * b.threatDist) {
                    b.threatDist = (float) Math.sqrt(ds);
                    b.threatX = a.position.x;
                    b.threatZ = a.position.z;
                }
                if (a.type == MobType.WOLF && b.type.isPrey() && ds < preyRangeSq
                        && (a.prey == null || ds < a.position.distanceSquared(a.prey.position)))
                    a.prey = b;
                if (b.type == MobType.WOLF && a.type.isPrey() && ds < preyRangeSq
                        && (b.prey == null || ds < b.position.distanceSquared(b.prey.position)))
                    b.prey = a;
                if (a.type == MobType.WOLF && b.type == MobType.WOLF && ds < packRangeSq) {
                    if (a.angryTimer > 1f && b.angryTimer < a.angryTimer * 0.9f)
                        b.angryTimer = a.angryTimer * 0.9f;
                    if (b.angryTimer > 1f && a.angryTimer < b.angryTimer * 0.9f)
                        a.angryTimer = b.angryTimer * 0.9f;
                }
            }
        }
    }

    private static float dist(Mob a, Mob b) {
        float dx = b.position.x - a.position.x, dz = b.position.z - a.position.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /** Один тик дикого зверя. */
    static void tick(Mob m, MobContext c, boolean hostileEnabled) {
        switch (m.type) {
            case RABBIT -> rabbit(m, c);
            case WOLF -> wolf(m, c, hostileEnabled);
            case BIRD -> bird(m, c);
            default -> m.updatePeaceful(c.dt);
        }
    }

    // ---- кролик ---------------------------------------------------------------

    private static void rabbit(Mob m, MobContext c) {
        boolean playerNear = c.dist < RABBIT_ALERT;
        boolean threatNear = m.threatDist < THREAT_ALERT;
        if (m.state == Mob.State.FLEE) {
            m.stateTimer -= c.dt;
            float ax, az;
            if (threatNear && m.threatDist < c.dist) {
                ax = m.position.x - m.threatX;
                az = m.position.z - m.threatZ;
            } else {
                ax = -c.dx;
                az = -c.dz;
            }
            // Петляет: бегство по прямой ловится, зигзаг — нет.
            float zig = (float) Math.sin(m.animationTime * 3.1f) * 0.55f;
            float cos = (float) Math.cos(zig), sin = (float) Math.sin(zig);
            m.setMoveDirection(ax * cos - az * sin, ax * sin + az * cos);
            hop(m);
            if (m.stateTimer <= 0f && c.dist > CALM_DISTANCE && m.threatDist > CALM_DISTANCE)
                m.enterIdle();
            return;
        }
        if (playerNear || threatNear) {
            m.state = Mob.State.FLEE;
            m.stateTimer = 2.5f;
            return;
        }
        m.updatePeaceful(c.dt);
        if (m.state == Mob.State.WANDER)
            hop(m);
    }

    private static void hop(Mob m) {
        if (m.onGround && (m.moveX != 0f || m.moveZ != 0f))
            m.velocity.y = HOP_VELOCITY;
    }

    // ---- волк -----------------------------------------------------------------

    private static void wolf(Mob m, MobContext c, boolean hostileEnabled) {
        m.hungerTimer -= c.dt;
        if (m.angryTimer > 0f && hostileEnabled) {
            if (c.dist > 28f) {
                m.angryTimer = 0f;
                m.enterIdle();
                return;
            }
            if (m.state != Mob.State.ATTACK)
                m.state = Mob.State.CHASE;
            m.steerToTarget(c.world, c.dt, c.playerPos, c.dx, c.dz, c.dist);
            if (c.dist <= BITE_RANGE) {
                m.state = Mob.State.ATTACK;
                m.stopMoving();
                m.yaw = (float) Math.atan2(-c.dx, -c.dz);
                if (m.attackCooldown <= 0f) {
                    m.justAttacked = true;
                    m.attackSwing = Mob.ATTACK_SWING_TIME;
                    m.attackCooldown = BITE_COOLDOWN;
                }
            } else if (m.state == Mob.State.ATTACK && c.dist > BITE_RANGE + 0.6f) {
                m.state = Mob.State.CHASE;
            }
            return;
        }

        Mob prey = m.prey;
        if (prey != null && !prey.dead && m.hungerTimer <= 0f) {
            m.state = Mob.State.HUNT;
            m.navigateTo(c.world, c.dt, prey.position);
            float d = dist(m, prey);
            if (d < BITE_RANGE && m.attackCooldown <= 0f) {
                m.justBitMob = prey;
                m.attackSwing = Mob.ATTACK_SWING_TIME;
                m.attackCooldown = BITE_COOLDOWN;
                m.yaw = (float) Math.atan2(-(prey.position.x - m.position.x),
                        -(prey.position.z - m.position.z));
            }
            return;
        }
        if (prey != null && prey.dead && m.state == Mob.State.HUNT)
            sate(m);
        if (m.state == Mob.State.HUNT || m.state == Mob.State.CHASE || m.state == Mob.State.ATTACK)
            m.enterIdle();
        m.updatePeaceful(c.dt);
    }

    /** Волк поел: охота откладывается надолго. */
    public static void sate(Mob wolf) {
        wolf.hungerTimer = SATED_MIN + wolf.rnd.nextFloat() * (SATED_MAX - SATED_MIN);
        wolf.prey = null;
    }

    // ---- птица ----------------------------------------------------------------

    private static void bird(Mob m, MobContext c) {
        if (m.state == Mob.State.FLY) {
            m.stateTimer -= c.dt;
            float dx = m.flyTarget.x - m.position.x, dy = m.flyTarget.y - m.position.y,
                  dz = m.flyTarget.z - m.position.z;
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (m.landing) {
                if (d < 0.35f || m.onGround) {
                    m.landing = false;
                    m.enterIdle();
                    m.velocity.set(0f, 0f, 0f);
                }
                return;
            }
            // Пока игрок рядом, садиться некуда — летим дальше.
            if ((m.stateTimer <= 0f || d < 1.2f) && c.dist > BIRD_ALERT) {
                if (!pickLanding(m, c.world))
                    pickFlight(m, c, 6f);
            } else if (d < 1.2f) {
                pickFlight(m, c, 8f);
            }
            return;
        }
        boolean spooked = c.dist < BIRD_ALERT || m.threatDist < THREAT_ALERT * 0.7f;
        // Изредка взлетает и сама: сидящие намертво птицы выглядят чучелами.
        boolean restless = m.rnd.nextFloat() < c.dt * 0.03f;
        if (spooked || restless) {
            takeOff(m, c);
            return;
        }
        m.updatePeaceful(c.dt);
    }

    private static void takeOff(Mob m, MobContext c) {
        m.state = Mob.State.FLY;
        m.justTookOff = true;
        m.landing = false;
        m.velocity.y = 3.5f;
        pickFlight(m, c, 10f);
    }

    /** Дальше этого от родного места птица поворачивает обратно. */
    private static final float HOME_RANGE = 20f;

    /**
     * Цель полёта: прочь от игрока, выше крон — но не дальше
     * {@link #HOME_RANGE} от места, где птица жила: иначе, раз за разом
     * улетая от игрока, она уходила бы за край загруженного мира.
     */
    private static void pickFlight(Mob m, MobContext c, float minDistance) {
        m.stateTimer = FLIGHT_MIN + m.rnd.nextFloat() * (FLIGHT_MAX - FLIGHT_MIN);
        double angle = Math.atan2(-c.dz, -c.dx) + (m.rnd.nextDouble() - 0.5) * 2.2;
        float hx = m.homeX - m.position.x, hz = m.homeZ - m.position.z;
        if (hx * hx + hz * hz > HOME_RANGE * HOME_RANGE)
            angle = Math.atan2(hz, hx) + (m.rnd.nextDouble() - 0.5) * 0.8;
        float r = minDistance + m.rnd.nextFloat() * 10f;
        float tx = m.position.x + (float) Math.cos(angle) * r;
        float tz = m.position.z + (float) Math.sin(angle) * r;
        int ground = surface(c.world, (int) Math.floor(tx), (int) Math.floor(tz));
        // Высота считается от земли под целью, а не от текущей: иначе каждый
        // новый отрезок полёта поднимал бы птицу ещё выше.
        float base = ground >= 0 ? ground + 1f : m.homeY;
        float ty = base + 4f + m.rnd.nextFloat() * 5f;
        m.flyTarget.set(tx, Math.min(Chunk.SIZE_Y - 3, ty), tz);
    }

    /**
     * Место посадки под текущей точкой полёта. Сначала ищется крона или
     * трава, и только если их нет — любой твёрдый верх: над голым камнем
     * птица всё равно когда-то садится, а не летает вечно.
     */
    private static boolean pickLanding(Mob m, World world) {
        int bx = (int) Math.floor(m.position.x), bz = (int) Math.floor(m.position.z);
        for (int pass = 0; pass < 2; pass++)
            for (int attempt = 0; attempt < 8; attempt++) {
                int x = bx + m.rnd.nextInt(9) - 4, z = bz + m.rnd.nextInt(9) - 4;
                int y = surface(world, x, z);
                if (y < 0)
                    continue;
                BlockType top = world.getBlock(x, y, z);
                boolean natural = top == BlockType.LEAVES || top == BlockType.GRASS
                        || top == BlockType.SNOWY_GRASS;
                if (!top.solid || (pass == 0 && !natural))
                    continue;
                if (world.getBlock(x, y + 1, z).solid)
                    continue;
                m.flyTarget.set(x + 0.5f, y + 1.02f, z + 0.5f);
                m.landing = true;
                return true;
            }
        return false;
    }

    private static int surface(World world, int x, int z) {
        for (int y = Chunk.SIZE_Y - 2; y > 0; y--) {
            BlockType b = world.getBlock(x, y, z);
            if (b.solid || b == BlockType.WATER || b == BlockType.WATER_FLOW)
                return y;
        }
        return -1;
    }

    /**
     * Полёт без гравитации: скорость плавно доворачивает к цели, крылья
     * поднимают над препятствием. Физика ходьбы здесь не нужна — птица в
     * воздухе не упирается в ступеньки, а облетает их.
     */
    static void flyStep(Mob m, World world, float dt) {
        Vector3f to = new Vector3f(m.flyTarget).sub(m.position);
        float d = to.length();
        float speed = m.type.chaseSpeed * (m.landing ? 0.55f : 1f);
        if (d > 1e-3f)
            to.mul(Math.min(speed, d * 2.5f) / d);
        // Взмахи: полёт идёт волной, а не по линейке.
        to.y += (float) Math.sin(m.animationTime * 6f) * 0.6f * (m.landing ? 0.2f : 1f);
        float k = Math.min(1f, FLY_STEER * dt);
        m.velocity.x += (to.x - m.velocity.x) * k;
        m.velocity.y += (to.y - m.velocity.y) * k;
        m.velocity.z += (to.z - m.velocity.z) * k;

        float nx = m.position.x + m.velocity.x * dt;
        float ny = m.position.y + m.velocity.y * dt;
        float nz = m.position.z + m.velocity.z * dt;
        boolean blocked = world.getBlock((int) Math.floor(nx), (int) Math.floor(ny + m.type.height * 0.5f),
                (int) Math.floor(nz)).solid;
        if (blocked) {
            // Впереди препятствие: вверх, и цель тоже выше.
            m.velocity.x *= 0.3f;
            m.velocity.z *= 0.3f;
            m.velocity.y = 3f;
            m.flyTarget.y = Math.min(Chunk.SIZE_Y - 3, m.flyTarget.y + 1.5f);
            ny = m.position.y + m.velocity.y * dt;
            if (!world.getBlock((int) Math.floor(m.position.x), (int) Math.floor(ny + m.type.height),
                    (int) Math.floor(m.position.z)).solid)
                m.position.y = ny;
        } else {
            m.position.set(nx, ny, nz);
        }
        boolean ground = world.getBlock((int) Math.floor(m.position.x), (int) Math.floor(m.position.y - 0.02f),
                (int) Math.floor(m.position.z)).solid;
        if (ground && m.velocity.y < 0f) {
            m.position.y = (float) Math.floor(m.position.y - 0.02f) + 1.001f;
            m.velocity.y = 0f;
        }
        m.onGround = ground && m.landing;
        m.inWater = false;
        float h = (float) Math.sqrt(m.velocity.x * m.velocity.x + m.velocity.z * m.velocity.z);
        if (h > 0.2f)
            m.yaw = (float) Math.atan2(-m.velocity.x, -m.velocity.z);
        m.walkAmount += (0f - m.walkAmount) * Math.min(1f, dt * 10f);
    }
}
