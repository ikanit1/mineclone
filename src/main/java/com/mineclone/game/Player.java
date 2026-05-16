package com.mineclone.game;

import com.mineclone.render.Camera;
import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import org.joml.Vector3f;

public class Player {
    public final Camera camera = new Camera();
    public final Vector3f velocity = new Vector3f();
    public boolean onGround = false;
    public boolean flying = false;
    public boolean inWater = false;
    public float swimSoundTimer = 0f;

    public static final float WIDTH = 0.6f;
    public static final float HEIGHT = 1.8f;
    public static final float EYE_HEIGHT = 1.62f;

    public static final float WALK_SPEED = 4.8f;
    public static final float FLY_SPEED = 12f;
    public static final float JUMP_VELOCITY = 8.4f;
    public static final float GRAVITY = -28f;

    public final Vector3f position = new Vector3f(8, 90, 8);

    public void update(float dt, World world, com.mineclone.core.Input input) {
        // mouse look
        float sens = 0.0025f;
        camera.rotate((float) (input.getDx() * sens), (float) (input.getDy() * sens));

        // toggle fly
        if (input.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F))
            flying = !flying;

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
        if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_W))
            wish.add(fwd);
        if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_S))
            wish.sub(fwd);
        if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_D))
            wish.add(right);
        if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_A))
            wish.sub(right);
        if (wish.lengthSquared() > 0.0001)
            wish.normalize();

        float speed = flying ? FLY_SPEED : WALK_SPEED;
        velocity.x = wish.x * speed;
        velocity.z = wish.z * speed;

        inWater = !flying && touchingWater(world);

        if (flying) {
            velocity.y = 0;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))
                velocity.y = speed;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT))
                velocity.y = -speed;
        } else if (inWater) {
            velocity.y += GRAVITY * 0.12f * dt;
            velocity.y = Math.max(velocity.y, -3f);
            velocity.x *= (float) Math.pow(0.75, dt * 10);
            velocity.z *= (float) Math.pow(0.75, dt * 10);
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))
                velocity.y = 3.5f;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT))
                velocity.y = -3.5f;
            onGround = false;
            swimSoundTimer -= dt;
        } else {
            velocity.y += GRAVITY * dt;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) && onGround) {
                velocity.y = JUMP_VELOCITY;
                onGround = false;
            }
        }

        // Move with collisions axis by axis (AABB sweep)
        moveAxis(world, velocity.x * dt, 0, 0);
        moveAxis(world, 0, velocity.y * dt, 0);
        moveAxis(world, 0, 0, velocity.z * dt);

        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
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

    private void moveAxis(World world, float dx, float dy, float dz) {
        position.x += dx;
        position.y += dy;
        position.z += dz;
        if (flying)
            return;

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
                    } else if (dx < 0) {
                        position.x = x + 1 + hw + 1e-4f;
                        velocity.x = 0;
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
                    } else if (dz < 0) {
                        position.z = z + 1 + hw + 1e-4f;
                        velocity.z = 0;
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
            if (position.y < topSurface && position.y + HEIGHT > by) {
                float stepDelta = topSurface - position.y;
                if (stepDelta <= 0.55f) {
                    position.y = topSurface + 1e-4f;
                    onGround = true;
                } else {
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
    }
}
