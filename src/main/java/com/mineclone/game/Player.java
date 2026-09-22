package com.mineclone.game;

import com.mineclone.render.Camera;
import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import org.joml.Vector3f;

public class Player {
    public final Camera camera = new Camera();
    public final Vector3f velocity = new Vector3f();
    /** Множитель от временных состояний; Game обновляет его до шага физики. */
    public float statusSpeedMultiplier = 1f;
    public boolean onGround = false;
    public boolean flying = false;
    private com.mineclone.world.GameMode gameMode = com.mineclone.world.GameMode.SURVIVAL;
    private float jumpTapTimer = Float.MAX_VALUE;

    public void setGameMode(com.mineclone.world.GameMode mode) {
        if (mode == com.mineclone.world.GameMode.CREATIVE) {
            health = MAX_HEALTH;
            hunger = MAX_HUNGER;
        }
        if (gameMode == mode) return;
        gameMode = mode;
        jumpTapTimer = Float.MAX_VALUE;
        fallDistance = lastFallDamage = lastFallDistance = 0f;
        hurtCooldown = regenDelay = regenTimer = 0f;
        if (isCreative()) {
            health = MAX_HEALTH;
            hunger = MAX_HUNGER;
        } else {
            flying = false;
            velocity.y = 0f;
        }
    }

    public boolean isCreative() {
        return gameMode == com.mineclone.world.GameMode.CREATIVE;
    }

    /** Called with key edges: holding Space cannot count as a second tap. */
    public void updateFlightControls(float dt, boolean enabled, boolean jumpPressed, boolean flyPressed) {
        jumpTapTimer = Math.min(Float.MAX_VALUE / 2, jumpTapTimer + dt);
        if (!enabled || !isCreative()) {
            jumpTapTimer = Float.MAX_VALUE;
            return;
        }
        boolean toggle = flyPressed;
        if (jumpPressed) {
            toggle |= jumpTapTimer <= DOUBLE_TAP_WINDOW;
            jumpTapTimer = toggle ? Float.MAX_VALUE : 0f;
        }
        if (toggle) {
            flying = !flying;
            velocity.y = 0f;
            fallDistance = 0f;
            onGround = false;
        }
    }
    public boolean inWater = false;
    public boolean eyeInWater = false;
    /** Downward speed captured before water drag on the frame of entry. */
    public float waterEntrySpeed = 0f;
    public float swimSoundTimer = 0f;

    public float health = 20f;
    public static final float MAX_HEALTH = 20f;
    /** Окно неуязвимости после удара моба (MC: 10 тиков). */
    public static final float HURT_INVULN_TIME = 0.5f;
    /** >0 — урон от атак не проходит. */
    public float hurtCooldown = 0f;
    /** Пауза регена после полученного удара, секунды. */
    public static final float REGEN_DELAY_AFTER_HIT = 5f;
    /** Сколько ждать между 0.5 HP регена. */
    public static final float REGEN_INTERVAL = 6f;

    // ---- голод ----
    public static final float MAX_HUNGER = 20f;
    /** Порог сытости, ниже которого здоровье не восстанавливается. */
    public static final float REGEN_HUNGER_MIN = 14f;
    /** Сколько голода уходит за секунду просто от того, что игрок жив. */
    public static final float HUNGER_IDLE_DRAIN = 0.035f;
    /** Во сколько раз быстрее голод уходит на бегу. */
    public static final float HUNGER_SPRINT_MUL = 4f;
    /** Урон в секунду, когда сытость кончилась совсем. */
    public static final float STARVE_DAMAGE = 0.5f;
    /** Ниже этого здоровья голод больше не убивает — умирать должен бой. */
    public static final float STARVE_FLOOR = 1f;

    public float hunger = MAX_HUNGER;
    private float regenDelay = 0f;
    public float fallDistance = 0f;
    public float lastFallDamage = 0f;
    public float lastFallDistance = 0f;
    private boolean wasOnGround = false;
    private float regenTimer = 0f;

    public static final float WIDTH = 0.6f;
    public static final float HEIGHT = 1.8f;
    public static final float EYE_HEIGHT = 1.62f;

    public static final float WALK_SPEED = 4.8f;
    public static final float FLY_SPEED = 12f;
    /** Active fly speed; FLY_SPEED by default, overridable via the /speed command. */
    public float flySpeed = FLY_SPEED;
    public static final float JUMP_VELOCITY = 8.4f;
    public static final float GRAVITY = -28f;
    public static final float SWIM_SPEED = 2.0f;
    public static final float SWIM_UP_MAX = 2.5f;
    public static final float WATER_IDLE_SINK_ACCEL = 6.4f;
    public static final float WATER_IDLE_SINK_MAX = -1.6f;
    public static final float SINK_MAX = -2.0f;
    public static final float GROUND_ACCEL = 14f;
    public static final float AIR_ACCEL = 2.5f;
    private static final float WATER_LEDGE_PROBE = 0.22f;
    private static final float WATER_LEDGE_MAX_STEP = 1.45f;

    public static final float SPRINT_SPEED = WALK_SPEED * 1.3f;  // ≈6.24 m/s
    private static final float DOUBLE_TAP_WINDOW = 0.25f;

    public boolean isSprinting = false;
    private float wDoubleTapTimer = Float.MAX_VALUE; // MAX_VALUE = "W never pressed"
    public boolean justJumped = false;

    public final Vector3f position = new Vector3f(8, 90, 8);

    public void update(float dt, World world, com.mineclone.core.Input input) {
        update(dt, world, input, true, 1.0f, false);
    }

    public void update(float dt, World world, com.mineclone.core.Input input, boolean controlsEnabled) {
        update(dt, world, input, controlsEnabled, 1.0f, false);
    }

    public void update(float dt, World world, com.mineclone.core.Input input,
            boolean controlsEnabled, float sensitivity, boolean invertY) {
        justJumped = false;
        boolean wetAtFrameStart = inWater;
        float entryVelocityY = velocity.y;
        waterEntrySpeed = 0f;
        if (hurtCooldown > 0f)
            hurtCooldown = Math.max(0f, hurtCooldown - dt);
        if (controlsEnabled)
            checkSprintActivation(input, dt);

        // mouse look
        float sens = 0.0025f * sensitivity;
        if (controlsEnabled)
            camera.rotate((float) (input.getDx() * sens),
                    (float) (input.getDy() * (invertY ? -sens : sens)));

        updateFlightControls(dt, controlsEnabled,
                controlsEnabled && input.pressed(com.mineclone.core.KeyBindings.Action.JUMP),
                controlsEnabled && input.pressed(com.mineclone.core.KeyBindings.Action.FLY));

        // horizontal input
        Vector3f fwd = camera.forward();
        fwd.y = 0;
        if (fwd.lengthSquared() > 0.0001)
            fwd.normalize();
        Vector3f right = camera.right();
        right.y = 0;
        if (right.lengthSquared() > 0.0001)
            right.normalize();

        Vector3f wish = new Vector3f();
        if (controlsEnabled && input.down(com.mineclone.core.KeyBindings.Action.FORWARD))
            wish.add(fwd);
        if (controlsEnabled && input.down(com.mineclone.core.KeyBindings.Action.BACK))
            wish.sub(fwd);
        if (controlsEnabled && input.down(com.mineclone.core.KeyBindings.Action.RIGHT))
            wish.add(right);
        if (controlsEnabled && input.down(com.mineclone.core.KeyBindings.Action.LEFT))
            wish.sub(right);
        if (wish.lengthSquared() > 0.0001)
            wish.normalize();

        float speed = (flying ? flySpeed * (isSprinting ? 2f : 1f) : (isSprinting ? SPRINT_SPEED : WALK_SPEED))
                * (isCreative() ? 1f : Math.max(0.15f, statusSpeedMultiplier));
        boolean jumpDown = controlsEnabled && input.down(com.mineclone.core.KeyBindings.Action.JUMP);

        inWater = touchingWater(world);
        eyeInWater = eyeBlockIsWater(world);
        if (inWater && !wetAtFrameStart)
            waterEntrySpeed = Math.max(0f, -entryVelocityY);

        if (flying) {
            velocity.x = wish.x * speed;
            velocity.z = wish.z * speed;
            velocity.y = 0;
            if (jumpDown)
                velocity.y = speed;
            if (controlsEnabled && input.down(com.mineclone.core.KeyBindings.Action.DESCEND))
                velocity.y = -speed;
        } else if (inWater) {
            // Горизонталь: exponential lerp к wish*SWIM_SPEED (инерция воды)
            float hDrag = (float) Math.pow(0.15, dt);
            velocity.x = velocity.x * hDrag + wish.x * SWIM_SPEED * statusSpeedMultiplier * (1f - hDrag);
            velocity.z = velocity.z * hDrag + wish.z * SWIM_SPEED * statusSpeedMultiplier * (1f - hDrag);

            boolean sinking = controlsEnabled && input.down(com.mineclone.core.KeyBindings.Action.DESCEND);

            // vertical drag: slow sink by default; SPACE overrides to swim up
            float waterVDrag = (float) Math.pow(0.8, dt / 0.05f);
            velocity.y *= waterVDrag;

            if (jumpDown) {
                if (!eyeInWater) {
                    // Water exit jump: instant JUMP_VELOCITY on first press, like land jump
                    if (input.pressed(com.mineclone.core.KeyBindings.Action.JUMP))
                        velocity.y = JUMP_VELOCITY;
                    else
                        velocity.y = Math.min(velocity.y + 12f * dt, JUMP_VELOCITY);
                } else {
                    // Underwater: swim upward
                    velocity.y = Math.min(velocity.y + 12f * dt, SWIM_UP_MAX);
                }
            } else if (!sinking && velocity.y > WATER_IDLE_SINK_MAX) {
                velocity.y = Math.max(velocity.y - WATER_IDLE_SINK_ACCEL * dt, WATER_IDLE_SINK_MAX);
            }
            if (sinking)
                velocity.y = Math.max(velocity.y - 8f * dt, SINK_MAX);

            onGround = false;
            swimSoundTimer -= dt;
        } else {
            // MC-подобное движение: экспоненциальное приближение к цели.
            if (onGround) speed *= world.getBlock((int) Math.floor(position.x),
                    (int) Math.floor(position.y - 0.05f), (int) Math.floor(position.z)).walkSpeedMultiplier();
            // Сильный разгон/торможение на земле, слабый контроль в воздухе.
            float targetX = wish.x * speed;
            float targetZ = wish.z * speed;
            // На льду сцепление слабое: и разгон, и торможение растягиваются
            // в скольжение, но в воздухе хуже не становится.
            float rate = onGround ? Math.max(AIR_ACCEL, GROUND_ACCEL * gripUnderFeet(world)) : AIR_ACCEL;
            float t = 1f - (float) Math.exp(-rate * dt);
            velocity.x += (targetX - velocity.x) * t;
            velocity.z += (targetZ - velocity.z) * t;
            velocity.y += GRAVITY * dt;
            if (jumpDown && onGround) {
                velocity.y = JUMP_VELOCITY;
                onGround = false;
                justJumped = true;
            }
        }

        float prevY = position.y;

        // Move with collisions axis by axis (AABB sweep)
        // Substeps keep fast flight from tunnelling through one-block walls.
        int steps = Math.max(1, (int) Math.ceil(velocity.length() * dt / 0.2f));
        float stepDt = dt / steps;
        for (int step = 0; step < steps; step++) {
            moveAxis(world, velocity.x * stepDt, 0, 0);
            boolean descending = velocity.y < 0f;
            moveAxis(world, 0, velocity.y * stepDt, 0);
            if (flying && descending && onGround) flying = false;
            moveAxis(world, 0, 0, velocity.z * stepDt);
        }
        if (!flying && inWater && !eyeInWater && jumpDown) {
            tryClimbWaterLedge(world, wish);
        }

        // Refresh water contact after movement (player may have entered water this frame)
        inWater = touchingWater(world);
        eyeInWater = eyeBlockIsWater(world);
        if (inWater && !wetAtFrameStart)
            waterEntrySpeed = Math.max(0f, -entryVelocityY);

        // Flight and water end the current fall. Include the landing frame's
        // descent, but never carry a previous fall through a flying stop.
        if (inWater || flying || isCreative())
            fallDistance = 0f;
        else if (position.y < prevY)
            fallDistance += prevY - position.y;

        // Landing: apply fall damage (guard !inWater covers same-frame water+ground)
        if (onGround && !wasOnGround) {
            if (!inWater && !flying && !isCreative()) {
                float dmg = Math.max(0f, fallDistance - 3f);
                lastFallDamage = dmg;
                if (dmg > 0f) {
                    takeDamage(dmg);
                }
                lastFallDistance = fallDistance;
            }
            fallDistance = 0f;
        }
        wasOnGround = onGround;

        tickHunger(dt, controlsEnabled);

        // Медленный реген (~0.5 HP за REGEN_INTERVAL) с паузой после боя и
        // только на сытый желудок: голод — это и есть цена урона.
        if (regenDelay > 0f) {
            regenDelay = Math.max(0f, regenDelay - dt);
            regenTimer = 0f;
        } else if (controlsEnabled && canRegen()) {
            regenTimer += dt;
            if (regenTimer >= REGEN_INTERVAL) {
                if (health < MAX_HEALTH)
                    health = Math.min(MAX_HEALTH, health + 0.5f);
                regenTimer = 0f;
            }
        }

        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
    }

    /**
     * Голод: медленно уходит сам, быстрее на бегу, а на нуле начинает
     * отнимать здоровье — но не досмерти.
     *
     * Пол в {@link #STARVE_FLOOR} стоит намеренно: смерть от голода в игре
     * без земледелия и без сундуков — это не вызов, а тупик. Голодный игрок
     * должен становиться уязвимым, а добивать его должен зомби.
     */
    public void tickHunger(float dt, boolean active) {
        if (!active || isCreative() || flying || health <= 0f)
            return;
        float drain = HUNGER_IDLE_DRAIN * dt;
        if (isSprinting)
            drain *= HUNGER_SPRINT_MUL;
        hunger = Math.max(0f, hunger - drain);
        if (hunger <= 0f && health > STARVE_FLOOR)
            health = Math.max(STARVE_FLOOR, health - STARVE_DAMAGE * dt);
    }

    /**
     * Можно ли сейчас восстанавливать здоровье. Вынесено отдельно, чтобы
     * правило можно было проверить тестом: прогнать сам update без реального
     * GLFW-ввода нельзя.
     */
    public boolean canRegen() {
        return !isCreative() && regenDelay <= 0f && health > 0f && hunger >= REGEN_HUNGER_MIN;
    }

    /** Съесть: поднимает сытость, но не выше предела. */
    public void eat(float nutrition) {
        hunger = Math.min(MAX_HUNGER, hunger + nutrition);
    }

    /** Есть смысл, только если желудок не полон — иначе еда тратится впустую. */
    public boolean canEat() {
        return hunger < MAX_HUNGER - 0.01f;
    }

    /**
     * Сцепление с блоком под ногами — по самому цепкому из тех, на чём стоим:
     * одной ногой на камне уже не скользишь, иначе край озера превращался бы в
     * каток, с которого не уйти.
     */
    private float gripUnderFeet(World world) {
        float hw = WIDTH / 2f - 0.02f;
        int y = (int) Math.floor(position.y - 0.05f);
        float grip = -1f;
        for (float dx : new float[] { -hw, hw })
            for (float dz : new float[] { -hw, hw }) {
                BlockType b = world.getBlock((int) Math.floor(position.x + dx), y,
                        (int) Math.floor(position.z + dz));
                if (b.solid)
                    grip = Math.max(grip, b.grip());
            }
        return grip < 0f ? 1f : grip;
    }

    private boolean eyeBlockIsWater(World world) {
        int ex = (int) Math.floor(position.x);
        int ey = (int) Math.floor(position.y + EYE_HEIGHT);
        int ez = (int) Math.floor(position.z);
        BlockType b = world.getBlock(ex, ey, ez);
        return b == BlockType.WATER || b == BlockType.WATER_FLOW;
    }

    private boolean touchingWater(World world) {
        float hw = WIDTH / 2f;
        int x0 = (int) Math.floor(position.x - hw);
        int x1 = (int) Math.floor(position.x + hw - 0.01f);
        int y0 = (int) Math.floor(position.y + 0.1f);
        int y1 = (int) Math.floor(position.y + HEIGHT * 0.75f);
        int z0 = (int) Math.floor(position.z - hw);
        int z1 = (int) Math.floor(position.z + hw - 0.01f);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    BlockType b = world.getBlock(x, y, z);
                    if (b == BlockType.WATER || b == BlockType.WATER_FLOW)
                        return true;
                }
        return false;
    }

    private boolean tryClimbWaterLedge(World world, Vector3f wish) {
        if (wish.lengthSquared() < 0.0001f)
            return false;

        Vector3f dir = new Vector3f(wish);
        dir.normalize();
        float targetX = position.x + dir.x * WATER_LEDGE_PROBE;
        float targetZ = position.z + dir.z * WATER_LEDGE_PROBE;
        float hw = WIDTH / 2f;

        int x0 = (int) Math.floor(targetX - hw + 1e-3f);
        int x1 = (int) Math.floor(targetX + hw - 1e-3f);
        int z0 = (int) Math.floor(targetZ - hw + 1e-3f);
        int z1 = (int) Math.floor(targetZ + hw - 1e-3f);
        int y0 = (int) Math.floor(position.y - 0.2f);
        int y1 = (int) Math.floor(position.y + WATER_LEDGE_MAX_STEP);

        float bestTop = Float.POSITIVE_INFINITY;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    BlockType b = world.getBlock(x, y, z);
                    if (!b.solid)
                        continue;
                    float top = y + 1.0f;
                    float step = top - position.y;
                    if (step > 0.05f && step <= WATER_LEDGE_MAX_STEP && top < bestTop
                            && hasBodyClearance(world, targetX, top + 1e-4f, targetZ)) {
                        bestTop = top;
                    }
                }

        if (!Float.isFinite(bestTop))
            return false;

        position.x = targetX;
        position.y = bestTop + 1e-4f;
        position.z = targetZ;
        velocity.y = 0f;
        onGround = true;
        return true;
    }

    private boolean hasBodyClearance(World world, float x, float y, float z) {
        float hw = WIDTH / 2f;
        int x0 = (int) Math.floor(x - hw + 1e-3f);
        int x1 = (int) Math.floor(x + hw - 1e-3f);
        int y0 = (int) Math.floor(y);
        int y1 = (int) Math.floor(y + HEIGHT - 1e-3f);
        int z0 = (int) Math.floor(z - hw + 1e-3f);
        int z1 = (int) Math.floor(z + hw - 1e-3f);
        for (int bx = x0; bx <= x1; bx++)
            for (int by = y0; by <= y1; by++)
                for (int bz = z0; bz <= z1; bz++)
                    if (world.getBlock(bx, by, bz).solid)
                        return false;
        return true;
    }

    private void checkSprintActivation(com.mineclone.core.Input input, float dt) {
        boolean wPressed = input.pressed(com.mineclone.core.KeyBindings.Action.FORWARD);
        boolean wDown    = input.down(com.mineclone.core.KeyBindings.Action.FORWARD);
        boolean ctrlDown = input.down(com.mineclone.core.KeyBindings.Action.SPRINT);

        // Double-tap W: second press within DOUBLE_TAP_WINDOW activates sprint
        if (wPressed) {
            if (wDoubleTapTimer < DOUBLE_TAP_WINDOW && (flying || !inWater))
                isSprinting = true;
            wDoubleTapTimer = 0f;
        } else {
            wDoubleTapTimer = Math.min(wDoubleTapTimer + dt, Float.MAX_VALUE / 2);
        }

        // Ctrl + W: immediate activation
        if (ctrlDown && wDown && (flying || !inWater))
            isSprinting = true;

        // Cancel: W not held, or entered water/fly
        if (!wDown || (inWater && !flying))
            isSprinting = false;
    }

    /** Пакетная видимость ради теста физики: он живёт в этом же пакете. */
    void moveAxis(World world, float dx, float dy, float dz) {
        position.x += dx;
        position.y += dy;
        position.z += dz;

        float hw = WIDTH / 2f;
        float minX = position.x - hw, maxX = position.x + hw;
        float minY = position.y, maxY = position.y + HEIGHT;
        float minZ = position.z - hw, maxZ = position.z + hw;

        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);

        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    BlockType b = world.getBlock(x, y, z);
                    if (!b.solid)
                        continue;
                    if (b == BlockType.STAIRS) {
                        resolveStairs(world, x, y, z, dx, dy, dz, hw);
                        minX = position.x - hw;
                        maxX = position.x + hw;
                        minY = position.y;
                        maxY = position.y + HEIGHT;
                        minZ = position.z - hw;
                        maxZ = position.z + hw;
                        continue;
                    }
                    // overlap
                    if (dx > 0) {
                        position.x = x - hw - 1e-4f;
                        velocity.x = 0;
                        isSprinting = false;
                    } else if (dx < 0) {
                        position.x = x + 1 + hw + 1e-4f;
                        velocity.x = 0;
                        isSprinting = false;
                    }
                    if (dy > 0) {
                        position.y = y - HEIGHT - 1e-4f;
                        velocity.y = 0;
                    } else if (dy < 0) {
                        position.y = y + 1 + 1e-4f;
                        velocity.y = 0;
                        onGround = true;
                    }
                    if (dz > 0) {
                        position.z = z - hw - 1e-4f;
                        velocity.z = 0;
                        isSprinting = false;
                    } else if (dz < 0) {
                        position.z = z + 1 + hw + 1e-4f;
                        velocity.z = 0;
                        isSprinting = false;
                    }
                    // recompute bounds (single resolution is enough for small dt)
                    minX = position.x - hw;
                    maxX = position.x + hw;
                    minY = position.y;
                    maxY = position.y + HEIGHT;
                    minZ = position.z - hw;
                    maxZ = position.z + hw;
                }

        if (dy >= 0)
            onGround = false;
        // check ground contact: a block directly below
        if (dy == 0 && !onGround) {
            float py = position.y;
            int yb = (int) Math.floor(py - 1e-3);
            int xa = (int) Math.floor(position.x - hw + 1e-3);
            int xb = (int) Math.floor(position.x + hw - 1e-3);
            int za = (int) Math.floor(position.z - hw + 1e-3);
            int zb = (int) Math.floor(position.z + hw - 1e-3);
            outer: for (int xi = xa; xi <= xb; xi++)
                for (int zi = za; zi <= zb; zi++) {
                    BlockType bl = world.getBlock(xi, yb, zi);
                    if (bl == BlockType.STAIRS) {
                        byte m = world.getBlockMeta(xi, yb, zi);
                        int f2 = m & 0x3;
                        float rx = position.x - xi, rz = position.z - zi;
                        boolean step = switch (f2) {
                            case 0 -> rz < 0.5f;
                            case 1 -> rx >= 0.5f;
                            case 2 -> rz >= 0.5f;
                            default -> rx < 0.5f;
                        };
                        float top = step ? yb + 1.0f : yb + 0.5f;
                        if (Math.abs(py - top) < 0.1f) {
                            onGround = true;
                            break outer;
                        }
                    } else if (bl.solid) {
                        onGround = true;
                        break outer;
                    }
                }
        }
    }

    /**
     * Resolves collision between the player and a stair block.
     */
    private void resolveStairs(World world, int bx, int by, int bz,
            float dx, float dy, float dz, float hw) {
        byte meta = world.getBlockMeta(bx, by, bz);
        int facing = meta & 0x3;

        float relX = position.x - bx;
        float relZ = position.z - bz;

        boolean overStep = switch (facing) {
            case 0 -> relZ < 0.5f;
            case 1 -> relX >= 0.5f;
            case 2 -> relZ >= 0.5f;
            default -> relX < 0.5f;
        };

        float topSurface = overStep ? by + 1.0f : by + 0.5f;

        if (dy < 0) {
            // Use previous position to detect crossing through the surface (handles fast-falling)
            float prevY = position.y - dy;
            if (prevY >= topSurface - 1e-3f && position.y < topSurface) {
                position.y = topSurface + 1e-4f;
                velocity.y = 0;
                onGround = true;
            }
        } else if (dy > 0) {
            if (overStep && position.y + HEIGHT >= by + 1.0f - 1e-3f
                    && position.y + HEIGHT <= by + 1.0f + HEIGHT) {
                position.y = by + 1.0f - HEIGHT - 1e-4f;
                velocity.y = 0;
            }
        } else {
            // Автоподъёма на ступень здесь больше нет. Он ставил игрока на
            // верх ступени одним присваиванием, то есть мгновенно, и это
            // читалось как телепорт — тем сильнее, чем выше оказывалась
            // поверхность: у одного из направлений подъём выходил на целый
            // блок за два кадра подряд. Ступень теперь упирает, как любой
            // другой блок, и берётся прыжком.
            if (position.y < topSurface && position.y + HEIGHT > by) {
                if (dx > 0) {
                    position.x = bx - hw - 1e-4f;
                    velocity.x = 0;
                } else if (dx < 0) {
                    position.x = bx + 1 + hw + 1e-4f;
                    velocity.x = 0;
                }
                if (dz > 0) {
                    position.z = bz - hw - 1e-4f;
                    velocity.z = 0;
                } else if (dz < 0) {
                    position.z = bz + 1 + hw + 1e-4f;
                    velocity.z = 0;
                }
            }
        }
    }

    public void takeDamage(float amount) {
        if (isCreative()) return;
        health = Math.max(0f, health - amount);
    }

    /**
     * Урон от атаки моба. В отличие от {@link #takeDamage} уважает окно
     * неуязвимости: без него стая зомби снимает здоровье втрое быстрее одного,
     * потому что у каждого свой кулдаун удара.
     *
     * Урон от падения продолжает идти через takeDamage — там окно не нужно,
     * иначе падения станут дешевле, чем задумано.
     *
     * @return true, если урон прошёл
     */
    public boolean takeAttackDamage(float amount) {
        if (isCreative() || hurtCooldown > 0f)
            return false;
        hurtCooldown = HURT_INVULN_TIME;
        regenDelay = REGEN_DELAY_AFTER_HIT;
        takeDamage(amount);
        return true;
    }

    public void respawn(float x, float y, float z) {
        health = MAX_HEALTH;
        hunger = MAX_HUNGER;
        hurtCooldown = 0f;
        fallDistance = 0f;
        regenTimer = 0f;
        regenDelay = 0f;
        lastFallDamage = 0f;
        lastFallDistance = 0f;
        position.set(x, y, z);
        velocity.set(0, 0, 0);
        onGround = false;
        inWater = false;
        eyeInWater = false;
        wasOnGround = false;
        isSprinting = false;
        wDoubleTapTimer = jumpTapTimer = Float.MAX_VALUE;
        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
    }

    public boolean isDead() {
        return health <= 0f;
    }
}
