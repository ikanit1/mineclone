package com.mineclone.render;

import org.joml.Vector3f;

/** Per-player animation, advanced once per simulation frame, shared by every render pass. */
public final class PlayerAnimation {
    public static final float STRIDE_RADIANS = 2.6f;
    public static final float ATTACK_TIME = 0.28f;

    /** Local joint angles in radians. Renderers only read this reusable pose. */
    public static final class Pose {
        public float rootY, chestY, bodyPitch, bodyRoll, bodyTwist;
        // A = anatomical left (-X), B = anatomical right (+X). B carries the item.
        public float legA, legB, legRollA, legRollB;
        public float legLengthA = 1, legLengthB = 1;
        public float armA, armB, armRollA, armRollB, armYawA, armYawB;
    }

    private final Pose pose = new Pose();
    private final Vector3f previous = new Vector3f();
    private boolean initialized, wasGrounded, queuedAttack;
    private float phase, clock, amount, air, water, flight, sprint;
    private float forward = 1, sideways, vertical, turn, previousYaw, motionSpeed;
    private float landing, landingVelocity, attackTime = ATTACK_TIME;

    public Pose pose() { return pose; }
    public float walkAmount() { return amount; }
    public float phase() { return phase; }

    public void reset(Vector3f position, float yaw, boolean grounded, boolean wet, boolean flying) {
        previous.set(position);
        previousYaw = yaw;
        initialized = true;
        wasGrounded = grounded && !wet && !flying;
        phase = clock = amount = sprint = sideways = vertical = turn = motionSpeed = 0;
        forward = 1;
        water = wet && !flying ? 1 : 0;
        flight = flying ? 1 : 0;
        air = !grounded && !wet && !flying ? 1 : 0;
        landing = landingVelocity = 0;
        attackTime = ATTACK_TIME;
        queuedAttack = false;
        evaluate();
    }

    /** Repeated mining finishes the current arc before starting the next one. */
    public void startSwing() {
        if (attackTime < ATTACK_TIME) queuedAttack = true;
        else attackTime = 0;
    }

    public void update(float dt, Vector3f position, float bodyYaw,
                       boolean grounded, boolean wet, boolean flying, boolean running) {
        if (!Float.isFinite(dt) || dt <= 0 || !position.isFinite() || !Float.isFinite(bodyYaw)) return;
        if (!initialized || previous.distanceSquared(position) > 64f) {
            reset(position, bodyYaw, grounded, wet, flying);
            return;
        }
        // Long stalls must not fast-forward an attack or inject an enormous landing impulse.
        dt = Math.min(dt, 0.1f);
        float dx = position.x - previous.x, dz = position.z - previous.z;
        float vy = (position.y - previous.y) / dt;
        float distance = (float) Math.hypot(dx, dz);
        float speed = distance / dt;
        boolean walking = grounded && !wet && !flying;
        float k = blend(12, dt);
        motionSpeed += (Math.min(speed, 12) - motionSpeed) * k;
        amount += ((walking ? clamp(speed / 4.8f, 0, 1) : 0) - amount) * k;
        water += ((wet && !flying ? 1 : 0) - water) * k;
        flight += ((flying ? 1 : 0) - flight) * k;
        air += ((!grounded && !wet && !flying ? 1 : 0) - air) * k;
        sprint += ((running && walking ? 1 : 0) - sprint) * blend(8, dt);
        if (walking) phase = (phase + distance * STRIDE_RADIANS) % ((float) Math.PI * 2);
        // Signed local travel keeps backpedalling and strafing coherent with the shoulders.
        if (speed > 0.03f) {
            float f = (dx * sin(bodyYaw) - dz * cos(bodyYaw)) / distance;
            float s = (dx * cos(bodyYaw) + dz * sin(bodyYaw)) / distance;
            forward += (f - forward) * k;
            sideways += (s - sideways) * k;
        }
        float turnSpeed = clamp(BodyRotation.wrap(bodyYaw - previousYaw) / dt, -5, 5);
        turn += (turnSpeed - turn) * blend(7, dt);
        if (walking && !wasGrounded && vertical < -0.8f)
            landingVelocity = Math.min(landingVelocity, -Math.min(1.8f, -vertical * 0.18f));
        // Analytic critically damped spring: stable at 30, 60 and 144 Hz.
        float spring = landingVelocity + 18 * landing;
        float decay = (float) Math.exp(-18 * dt);
        landing = (landing + spring * dt) * decay;
        landingVelocity = (landingVelocity - 18 * spring * dt) * decay;
        vertical += (clamp(vy, -12, 8) - vertical) * blend(16, dt);
        clock = (clock + dt) % (float) (Math.PI * 200);
        attackTime = Math.min(ATTACK_TIME, attackTime + dt);
        if (attackTime >= ATTACK_TIME && queuedAttack) {
            attackTime = 0;
            queuedAttack = false;
        }
        previous.set(position);
        previousYaw = bodyYaw;
        wasGrounded = walking;
        evaluate();
    }

    private void evaluate() {
        float ground = clamp(1 - air - water - flight, 0, 1);
        float stride = sin(phase) * amount * (0.57f + 0.18f * sprint);
        float jump = clamp(vertical / 7, -1, 1);
        float paddle = sin(clock * 4.8f);
        float breath = sin(clock * 2.2f);
        float attack = attackEnvelope(attackTime / ATTACK_TIME);
        float impact = -landing;
        pose.legA = stride * forward + air * (0.14f + jump * 0.27f)
                + water * (0.16f + paddle * 0.3f) - flight * 0.10f;
        pose.legB = -stride * forward + air * (-0.12f - jump * 0.18f)
                + water * (0.16f - paddle * 0.3f) - flight * 0.16f;
        pose.legRollA = stride * sideways;
        pose.legRollB = -stride * sideways;
        pose.legLengthA = 1 - impact - 0.035f * amount * Math.max(0, cos(phase));
        pose.legLengthB = 1 - impact - 0.035f * amount * Math.max(0, -cos(phase));
        // Lowest foot stays at floor height, including the corners of the block-shaped foot.
        pose.rootY = -Math.min(footBottom(pose.legA, pose.legRollA, pose.legLengthA),
                footBottom(pose.legB, pose.legRollB, pose.legLengthB)) * ground;
        pose.chestY = breath * 0.006f * (1 - amount * 0.65f);
        pose.bodyPitch = -amount * (0.055f + sprint * 0.13f) * forward
                - water * 0.27f - flight * clamp(motionSpeed * 0.025f + Math.abs(vertical) * 0.025f, 0.05f, 0.25f)
                - air * 0.07f - impact * 1.6f - attack * 0.065f;
        pose.bodyRoll = sin(phase) * amount * 0.025f - sideways * amount * 0.06f - turn * 0.009f;
        pose.bodyTwist = cos(phase) * amount * 0.045f + attack * 0.20f;
        float idle = (0.025f + breath * 0.025f) * (1 - amount * 0.65f);
        pose.armA = -stride * forward * 0.8f + idle + air * (0.15f + jump * 0.18f)
                + water * (0.55f + paddle * 0.50f) + flight * 0.16f;
        pose.armB = stride * forward * 0.8f - idle + air * (0.15f + jump * 0.18f)
                + water * (0.55f - paddle * 0.50f) + flight * 0.16f;
        pose.armB = pose.armB * (1 - attack * 0.85f) + attack * 1.55f;
        pose.armRollA = -0.055f - breath * 0.018f - air * 0.12f - water * 0.32f - flight * 0.20f
                - stride * sideways * 0.45f;
        pose.armRollB = 0.055f + breath * 0.018f + air * 0.12f + water * 0.32f + flight * 0.20f
                + stride * sideways * 0.45f + attack * 0.08f;
        pose.armYawA = 0;
        pose.armYawB = attack * 0.10f;
    }

    private static float footBottom(float pitch, float roll, float length) {
        return 0.7f - 0.7f * length * cos(pitch) * cos(roll)
                - 0.12f * (Math.abs(cos(roll) * sin(pitch)) + Math.abs(sin(roll)));
    }

    private static float attackEnvelope(float t) {
        return t < 0.32f ? smooth(t / 0.32f) : 1 - smooth((t - 0.32f) / 0.68f);
    }
    private static float smooth(float t) { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); }
    private static float blend(float rate, float dt) { return 1 - (float) Math.exp(-rate * dt); }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private static float sin(float v) { return (float) Math.sin(v); }
    private static float cos(float v) { return (float) Math.cos(v); }
}
