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
        if (input.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F)) flying = !flying;

        // horizontal input
        Vector3f fwd = camera.forward(); fwd.y = 0;
        if (fwd.lengthSquared() > 0.0001) fwd.normalize();
        Vector3f right = camera.right(); right.y = 0;
        if (right.lengthSquared() > 0.0001) right.normalize();

        Vector3f wish = new Vector3f();
        if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_W)) wish.add(fwd);
        if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_S)) wish.sub(fwd);
        if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_D)) wish.add(right);
        if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_A)) wish.sub(right);
        if (wish.lengthSquared() > 0.0001) wish.normalize();

        float speed = flying ? FLY_SPEED : WALK_SPEED;
        velocity.x = wish.x * speed;
        velocity.z = wish.z * speed;

        if (flying) {
            velocity.y = 0;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))      velocity.y =  speed;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)) velocity.y = -speed;
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

    private void moveAxis(World world, float dx, float dy, float dz) {
        position.x += dx;
        position.y += dy;
        position.z += dz;
        if (flying) return;

        float hw = WIDTH / 2f;
        float minX = position.x - hw, maxX = position.x + hw;
        float minY = position.y,      maxY = position.y + HEIGHT;
        float minZ = position.z - hw, maxZ = position.z + hw;

        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);

        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    BlockType b = world.getBlock(x, y, z);
                    if (!b.solid) continue;
                    // overlap
                    if (dx > 0) { position.x = x - hw - 1e-4f; velocity.x = 0; }
                    else if (dx < 0) { position.x = x + 1 + hw + 1e-4f; velocity.x = 0; }
                    if (dy > 0) { position.y = y - HEIGHT - 1e-4f; velocity.y = 0; }
                    else if (dy < 0) { position.y = y + 1 + 1e-4f; velocity.y = 0; onGround = true; }
                    if (dz > 0) { position.z = z - hw - 1e-4f; velocity.z = 0; }
                    else if (dz < 0) { position.z = z + 1 + hw + 1e-4f; velocity.z = 0; }
                    // recompute bounds (single resolution is enough for small dt)
                    minX = position.x - hw; maxX = position.x + hw;
                    minY = position.y;      maxY = position.y + HEIGHT;
                    minZ = position.z - hw; maxZ = position.z + hw;
                }

        if (dy >= 0) onGround = false;
        // check ground contact: a block directly below
        if (dy == 0 && !onGround) {
            int yb = (int) Math.floor(position.y - 1e-3);
            int xa = (int) Math.floor(position.x - hw + 1e-3);
            int xb = (int) Math.floor(position.x + hw - 1e-3);
            int za = (int) Math.floor(position.z - hw + 1e-3);
            int zb = (int) Math.floor(position.z + hw - 1e-3);
            outer:
            for (int xi = xa; xi <= xb; xi++)
                for (int zi = za; zi <= zb; zi++)
                    if (world.getBlock(xi, yb, zi).solid) { onGround = true; break outer; }
        }
    }
}
