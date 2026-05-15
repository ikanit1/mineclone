package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {
    public final Vector3f position = new Vector3f(0, 80, 0);
    public float yaw = 0f;     // radians, around Y
    public float pitch = 0f;   // radians, around X

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();

    public void rotate(float dYaw, float dPitch) {
        yaw += dYaw;
        pitch = Math.max(-1.5533f, Math.min(1.5533f, pitch + dPitch));
    }

    public Vector3f forward() {
        return new Vector3f(
                (float) (Math.cos(pitch) * Math.sin(yaw)),
                (float) -Math.sin(pitch),
                (float) (-Math.cos(pitch) * Math.cos(yaw))
        ).normalize();
    }

    public Vector3f right() {
        return new Vector3f(
                (float) Math.cos(yaw),
                0f,
                (float) Math.sin(yaw)
        ).normalize();
    }

    public Matrix4f getView() {
        Vector3f f = forward();
        Vector3f center = new Vector3f(position).add(f);
        return view.identity().lookAt(position, center, new Vector3f(0, 1, 0));
    }

    public Matrix4f getProjection(float aspect, float fovDeg, float near, float far) {
        return projection.identity().perspective((float) Math.toRadians(fovDeg), aspect, near, far);
    }
}
