package com.mineclone.world;

import org.joml.Vector3f;

/**
 * Lightweight Verlet rope used by hanging vines, chains and suspension spans.
 * Positions and previous positions are kept separately, so the simulation has
 * inertia without storing explicit velocities. End points may be pinned.
 */
public final class RopeSimulation {
    public static final float GRAVITY = 16f;
    public static final int CONSTRAINT_ITERATIONS = 7;

    public static final class Node {
        public final Vector3f position = new Vector3f();
        public final Vector3f previous = new Vector3f();
        public boolean pinned;
    }

    private final Node[] nodes;
    private final float segmentLength;

    public RopeSimulation(Vector3f start, Vector3f end, int segments, boolean pinEnd) {
        if (segments < 2)
            throw new IllegalArgumentException("rope needs at least two segments");
        nodes = new Node[segments + 1];
        segmentLength = start.distance(end) / segments;
        for (int i = 0; i <= segments; i++) {
            nodes[i] = new Node();
            nodes[i].position.set(start).lerp(end, i / (float) segments);
            nodes[i].previous.set(nodes[i].position);
        }
        nodes[0].pinned = true;
        nodes[nodes.length - 1].pinned = pinEnd;
    }

    public void step(float dt, float windX, float windZ) {
        dt = Math.min(dt, 0.05f);
        float dt2 = dt * dt;
        for (Node n : nodes) {
            if (n.pinned)
                continue;
            float vx = (n.position.x - n.previous.x) * 0.992f;
            float vy = (n.position.y - n.previous.y) * 0.992f;
            float vz = (n.position.z - n.previous.z) * 0.992f;
            n.previous.set(n.position);
            n.position.add(vx + windX * dt2, vy - GRAVITY * dt2, vz + windZ * dt2);
        }
        for (int pass = 0; pass < CONSTRAINT_ITERATIONS; pass++)
            satisfyConstraints();
    }

    private void satisfyConstraints() {
        for (int i = 0; i < nodes.length - 1; i++) {
            Node a = nodes[i], b = nodes[i + 1];
            float dx = b.position.x - a.position.x;
            float dy = b.position.y - a.position.y;
            float dz = b.position.z - a.position.z;
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1e-5f)
                continue;
            float error = (length - segmentLength) / length;
            float ax = dx * error, ay = dy * error, az = dz * error;
            if (!a.pinned && !b.pinned) {
                a.position.add(ax * 0.5f, ay * 0.5f, az * 0.5f);
                b.position.sub(ax * 0.5f, ay * 0.5f, az * 0.5f);
            } else if (!a.pinned) {
                a.position.add(ax, ay, az);
            } else if (!b.pinned) {
                b.position.sub(ax, ay, az);
            }
        }
    }

    public void impulse(int index, float x, float y, float z) {
        if (index <= 0 || index >= nodes.length || nodes[index].pinned)
            return;
        nodes[index].previous.sub(x, y, z);
    }

    public Node[] nodes() { return nodes; }
    public float segmentLength() { return segmentLength; }
}
