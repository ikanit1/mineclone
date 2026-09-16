package com.mineclone.render;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Pure math shared by interaction bending, leg IK, coloured bounce and throw previews. */
public final class ProceduralEffects {
    private ProceduralEffects() {}

    public static Vector3f vegetationBend(Vector3f vertex, Vector3f actor, float radius, float strength) {
        float dx = vertex.x - actor.x, dz = vertex.z - actor.z;
        float d = (float)Math.sqrt(dx*dx + dz*dz);
        if (d >= radius || d < 1e-4f) return new Vector3f(vertex);
        float k = (1f - d / radius); k = k * k * strength;
        return new Vector3f(vertex.x + dx / d * k, vertex.y - k * 0.18f, vertex.z + dz / d * k);
    }

    /** Vertical correction and pitch for a two-segment leg. */
    public static float[] legIk(float hipY, float footTargetY, float legLength) {
        float reach = Math.max(-legLength * 0.42f, Math.min(legLength * 0.35f, footTargetY - (hipY - legLength)));
        float pitch = (float)Math.asin(Math.max(-0.8f, Math.min(0.8f, reach / Math.max(0.01f, legLength))));
        return new float[] { reach, pitch };
    }

    /** Cheap coloured voxel bounce from one nearby emissive block. */
    public static Vector3f bouncedLight(float[] emitterRgb, float distance, float radius, float occlusion) {
        float k = Math.max(0f, 1f - distance / Math.max(0.01f, radius));
        k = k * k * Math.max(0f, Math.min(1f, occlusion)) * 0.42f;
        return new Vector3f(emitterRgb[0] * k, emitterRgb[1] * k, emitterRgb[2] * k);
    }

    public static List<Vector3f> throwArc(Vector3f start, Vector3f direction, Vector3f inherited,
                                          float speed, float gravity, float seconds, int samples) {
        List<Vector3f> out = new ArrayList<>(samples);
        Vector3f v = new Vector3f(direction).normalize().mul(speed).add(inherited);
        for (int i = 0; i < samples; i++) {
            float t = seconds * i / Math.max(1, samples - 1);
            out.add(new Vector3f(start.x + v.x*t, start.y + v.y*t - 0.5f*gravity*t*t, start.z + v.z*t));
        }
        return out;
    }
}
