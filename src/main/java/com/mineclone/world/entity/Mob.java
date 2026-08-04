package com.mineclone.world.entity;

import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import org.joml.Vector3f;

import java.util.Random;

/**
 * One mob: state plus AI state machine. The class stays independent from GL
 * and Player; Game translates tick flags into sounds, particles and damage.
 */
public class Mob {
    public enum State { IDLE, WANDER, FLEE, CHASE, ATTACK }

    private static final float IDLE_MIN = 2f, IDLE_MAX = 6f;
    private static final float WANDER_MIN = 2f, WANDER_MAX = 5f;
    private static final float FLEE_TIME = 5f;
    private static final float FLEE_SPEED_MUL = 1.5f;
    private static final float IDLE_SOUND_MIN = 5f, IDLE_SOUND_MAX = 15f;

    // --- ИИ, зомби ---
    private static final float AGGRO_RANGE = 16f;
    private static final float LOSE_RANGE = 24f;
    private static final float ATTACK_RANGE = 1.5f;
    private static final float ATTACK_RELEASE = 2f;
    private static final float ATTACK_COOLDOWN = 1f;
    /** Урон зомби игроку — 1.5 сердца. */
    public static final float ATTACK_DAMAGE = 3f;

    /** Сколько длится замах зомби — рендер поднимает руки на это время. */
    public static final float ATTACK_SWING_TIME = 0.35f;

    public static final float HURT_FLASH_TIME = 0.4f;
    /**
     * Окно неуязвимости после попадания (MC: hurtResistantTime, 10 тиков).
     * Без него урон определяется скоростью мыши: закликать моба можно за кадр.
     */
    public static final float INVULN_TIME = 0.5f;
    private static final float KNOCKBACK = 5f;
    private static final float KNOCKBACK_UP = 4f;

    /** Порог daylight, выше которого светло «как днём» — зомби горит. */
    private static final float BURN_DAYLIGHT = 0.35f;

    /** Пройденный путь между звуками шагов, метры. */
    private static final float STEP_DISTANCE = 1.4f;
    /** Сколько секунд упора в стену считается «застрял». */
    private static final float STUCK_TIME = 0.8f;
    /** Сколько идти вбок вдоль стены, прежде чем снова ломиться вперёд. */
    private static final float SIDESTEP_TIME = 1f;
    /** Безопасная высота падения в блоках — как у игрока. */
    private static final float SAFE_FALL = 3f;

    public final MobType type;
    public final Vector3f position = new Vector3f();
    public final Vector3f velocity = new Vector3f();
    /** Facing direction: (-sin yaw, 0, -cos yaw). */
    public float yaw;
    public float health;
    public State state = State.IDLE;

    public boolean onGround;
    public boolean hitWall;
    public boolean inWater;
    public boolean dead;
    public boolean burning;
    public float hurtFlash;
    public float walkedDistance;

    /** >0 — зомби замахивается, рендер поднимает руки. */
    public float attackSwing;

    public boolean justAttacked;
    public boolean justIdleSound;
    public boolean justStepSound;

    protected final Random rnd;
    private float stateTimer;
    private float idleSoundTimer;
    private float moveX, moveZ;
    private float knockX, knockZ;
    private float attackCooldown;
    private float stepDistance;
    private float invulnTime;
    private boolean wasOnGround;
    /** Сколько уже упирается в стену — накопитель для бокового обхода. */
    private float blockedTimer;
    private float sidestepTimer;
    /** +1 или -1: в какую сторону обходить (залипает на время обхода). */
    private float sidestepSign = 1f;

    public Mob(MobType type, float x, float y, float z, Random rnd) {
        this.type = type;
        this.rnd = rnd;
        this.position.set(x, y, z);
        this.health = type.maxHealth;
        this.yaw = rnd.nextFloat() * (float) (Math.PI * 2);
        this.idleSoundTimer = IDLE_SOUND_MIN + rnd.nextFloat() * (IDLE_SOUND_MAX - IDLE_SOUND_MIN);
        enterIdle();
    }

    public void update(World world, Vector3f playerPos, float dt, float daylight,
                       boolean hostileEnabled) {
        justAttacked = false;
        justIdleSound = false;
        justStepSound = false;
        if (dead)
            return;
        if (hurtFlash > 0f)
            hurtFlash = Math.max(0f, hurtFlash - dt);
        if (invulnTime > 0f)
            invulnTime = Math.max(0f, invulnTime - dt);

        idleSoundTimer -= dt;
        if (idleSoundTimer <= 0f) {
            idleSoundTimer = IDLE_SOUND_MIN + rnd.nextFloat() * (IDLE_SOUND_MAX - IDLE_SOUND_MIN);
            justIdleSound = true;
        }

        if (attackCooldown > 0f)
            attackCooldown -= dt;

        float pdx = playerPos.x - position.x;
        float pdy = playerPos.y - position.y;
        float pdz = playerPos.z - position.z;
        float playerDist = (float) Math.sqrt(pdx * pdx + pdy * pdy + pdz * pdz);

        if (type.hostile && hostileEnabled)
            updateHostile(dt, pdx, pdz, playerDist);
        else
            updatePeaceful(dt);

        float speed = currentSpeed();
        // Пока идёт боковой обход — направление повёрнуто на 90°. Это не
        // pathfinding: настоящий A* вне области E1. Но без этого моб, упёршийся
        // в стену шире одного блока, бесконечно прыгает в неё на месте.
        float dirX = moveX, dirZ = moveZ;
        if (sidestepTimer > 0f) {
            sidestepTimer -= dt;
            dirX = -moveZ * sidestepSign;
            dirZ = moveX * sidestepSign;
        }
        velocity.x = dirX * speed + knockX;
        velocity.z = dirZ * speed + knockZ;
        float decay = (float) Math.pow(0.02, dt);
        knockX *= decay;
        knockZ *= decay;

        // step() обнулит vel.y при посадке — скорость удара нужно снять до него.
        float impactSpeed = -velocity.y;
        EntityPhysics.Contact c = EntityPhysics.step(world, position, velocity,
                type.width, type.height, dt, type.maxFallSpeed);
        onGround = c.onGround();
        hitWall = c.hitWall();
        inWater = c.inWater();

        // Накопитель «застрял» тикает по самому упору в стену, а не по контакту с
        // землёй: прыжок сбрасывает onGround в следующем кадре и обнулял бы
        // счётчик тем самым прыжком, который мы и лечим.
        if (hitWall) {
            blockedTimer += dt;
            if (onGround)
                velocity.y = EntityPhysics.JUMP_VELOCITY;
            if (blockedTimer > STUCK_TIME && sidestepTimer <= 0f) {
                sidestepTimer = SIDESTEP_TIME;
                sidestepSign = rnd.nextBoolean() ? 1f : -1f;
                blockedTimer = 0f;
            }
        } else {
            blockedTimer = 0f;
        }

        // Урон от падения считается по скорости удара, а не по высоте: у курицы
        // терминальная скорость -3 м/с, и ноль урона получается сам, без
        // спец-случая на вид моба.
        if (onGround && !wasOnGround && impactSpeed > 0f) {
            float fallBlocks = impactSpeed * impactSpeed / (2f * -EntityPhysics.GRAVITY);
            float dmg = fallBlocks - SAFE_FALL;
            if (dmg > 0f) {
                health -= dmg;
                hurtFlash = HURT_FLASH_TIME;
            }
        }
        wasOnGround = onGround;

        float hSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        walkedDistance += hSpeed * dt;
        if (onGround) {
            stepDistance += hSpeed * dt;
            if (stepDistance >= STEP_DISTANCE) {
                stepDistance = 0f;
                justStepSound = true;
            }
        }
        if (dirX != 0f || dirZ != 0f)
            yaw = (float) Math.atan2(-dirX, -dirZ);

        if (attackSwing > 0f)
            attackSwing = Math.max(0f, attackSwing - dt);

        applySunBurn(world, dt, daylight);
        if (health <= 0f)
            dead = true;
    }

    protected void updatePeaceful(float dt) {
        stateTimer -= dt;
        if (stateTimer > 0f)
            return;
        switch (state) {
            case IDLE -> enterWander();
            default -> enterIdle();
        }
    }

    /**
     * Зомби: агрится в 16 блоках (без проверки прямой видимости — MVP), бьёт
     * ближе 1.5, отпускает цель за 24. Пока игрок далеко — ведёт себя как мирный.
     */
    private void updateHostile(float dt, float dx, float dz, float dist) {
        boolean chasing = state == State.CHASE || state == State.ATTACK;
        if (chasing) {
            if (dist > LOSE_RANGE) {
                enterIdle();
                return;
            }
        } else if (dist <= AGGRO_RANGE) {
            state = State.CHASE;
        } else {
            updatePeaceful(dt);
            return;
        }

        setMoveDirection(dx, dz);
        if (dist <= ATTACK_RANGE) {
            state = State.ATTACK;
            stopMoving();
            yaw = (float) Math.atan2(-dx, -dz);   // moveX/Z обнулены — держим лицо к игроку
            if (attackCooldown <= 0f) {
                justAttacked = true;
                attackSwing = ATTACK_SWING_TIME;
                attackCooldown = ATTACK_COOLDOWN;
            }
        } else if (state == State.ATTACK && dist > ATTACK_RELEASE) {
            state = State.CHASE;
        }
    }

    protected void enterIdle() {
        state = State.IDLE;
        stateTimer = IDLE_MIN + rnd.nextFloat() * (IDLE_MAX - IDLE_MIN);
        moveX = 0f;
        moveZ = 0f;
    }

    protected void enterWander() {
        state = State.WANDER;
        stateTimer = WANDER_MIN + rnd.nextFloat() * (WANDER_MAX - WANDER_MIN);
        float angle = rnd.nextFloat() * (float) (Math.PI * 2);
        moveX = (float) Math.sin(angle);
        moveZ = (float) Math.cos(angle);
    }

    protected void setMoveDirection(float dx, float dz) {
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4f)
            return;
        moveX = dx / len;
        moveZ = dz / len;
    }

    protected void stopMoving() {
        moveX = 0f;
        moveZ = 0f;
    }

    protected float currentSpeed() {
        return switch (state) {
            case IDLE -> 0f;
            case FLEE -> type.walkSpeed * FLEE_SPEED_MUL;
            case CHASE, ATTACK -> type.chaseSpeed;
            case WANDER -> type.walkSpeed;
        };
    }

    private void applySunBurn(World world, float dt, float daylight) {
        burning = false;
        if (!type.burnsInSunlight || daylight <= BURN_DAYLIGHT)
            return;
        int bx = (int) Math.floor(position.x);
        int bz = (int) Math.floor(position.z);
        int startY = (int) Math.floor(position.y + type.height);
        for (int y = startY; y < Chunk.SIZE_Y; y++)
            if (world.getBlock(bx, y, bz).solid)
                return;
        burning = true;
        health -= 2f * dt;
    }

    /** Удар с обычным отбросом. */
    public boolean hurt(float amount, float fromX, float fromZ) {
        return hurt(amount, fromX, fromZ, 1f);
    }

    /**
     * Получить урон от точки (fromX, fromZ): отбрасывание, вспышка, а мирный
     * моб убегает.
     *
     * Попадание в окне неуязвимости игнорируется целиком — иначе урон
     * определяется тем, как быстро игрок щёлкает мышью.
     *
     * @param knockbackMul множитель отброса (спринт-удар бьёт сильнее)
     * @return true, если урон прошёл
     */
    public boolean hurt(float amount, float fromX, float fromZ, float knockbackMul) {
        if (invulnTime > 0f)
            return false;
        invulnTime = INVULN_TIME;
        health -= amount;
        hurtFlash = HURT_FLASH_TIME;
        float dx = position.x - fromX;
        float dz = position.z - fromZ;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len > 1e-4f) {
            knockX = dx / len * KNOCKBACK * knockbackMul;
            knockZ = dz / len * KNOCKBACK * knockbackMul;
            if (onGround)
                velocity.y = KNOCKBACK_UP;
            if (!type.hostile) {
                setMoveDirection(dx, dz);
                state = State.FLEE;
                stateTimer = FLEE_TIME;
            }
        }
        if (health <= 0f)
            dead = true;
        return true;
    }

    public float rayHitDistance(Vector3f origin, Vector3f dir) {
        float hw = type.width / 2f;
        return EntityPhysics.rayAabbDistance(origin.x, origin.y, origin.z, dir.x, dir.y, dir.z,
                position.x - hw, position.y, position.z - hw,
                position.x + hw, position.y + type.height, position.z + hw);
    }

    public Vector3f soundPosition() {
        return new Vector3f(position.x, position.y + type.height * 0.6f, position.z);
    }
}
