package com.mineclone;

import com.mineclone.core.Window;
import com.mineclone.render.*;
import com.mineclone.world.*;
import org.joml.*;
import static org.lwjgl.opengl.GL33.*;

/** Hidden context smoke: shader interfaces, texture arrays, compute, instancing, fences and queries. */
public final class OptimizationGlSmoke {
    public static void main(String[] args) {
        Window window = new Window("Optimization smoke", 128, 128, false);
        window.init();
        try {
            System.out.println("OpenGL " + glGetString(GL_VERSION));
            ShaderPreloader preload = ShaderPreloader.start(window.getHandle());
            TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
            Shader chunk = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
            Shader shadow = new Shader(Shaders.SHADOW_VERTEX, Shaders.SHADOW_FRAGMENT);
            Shader mobShadow = new Shader(Shaders.SHADOW_MOB_VERTEX, Shaders.SHADOW_FRAGMENT);
            Shader water = new Shader(Shaders.CHUNK_VERTEX, Shaders.WATER_FRAGMENT);
            preload.close();
            compareParticlePhysics();
            compareGpuPhaseTiming();
            ParticleSystem particles = new ParticleSystem();
            PrecipitationRenderer precipitation = new PrecipitationRenderer();
            OcclusionCuller culler = new OcclusionCuller();
            Matrix4f projection = new Matrix4f().perspective(1.2f, 1f, 0.1f, 200f);
            Matrix4f view = new Matrix4f().lookAt(8,70,28,8,64,8,0,1,0);
            Chunk c = new Chunk(0,0);
            for (int x=0;x<16;x++) for(int z=0;z<16;z++) c.set(x,64,z,BlockType.STONE);
            c.computeSkyLight();
            Mesh mesh = new ChunkMesher(new World(1)).buildData(c)[0].upload();
            for (int burst = 0; burst < 1000; burst++) particles.emitBlockBreak(8,66,8,new float[]{1,1,1},3,1,0);
            for (int frame = 0; frame < 180; frame++) {
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                atlas.bind(0);
                chunk.bind(); chunk.setMat4("uProjection", projection); chunk.setMat4("uView", view); chunk.setMat4("uModel", new Matrix4f());
                chunk.setInt("uShadow0",4); chunk.setInt("uShadow1",5);
                culler.begin(projection,view);
                culler.enqueue(World.key(0,0),0,0,0,16,128,16);
                if (!culler.hidden(World.key(0,0))) mesh.render();
                culler.flush();
                particles.emitBlockBreak(8,66,8,new float[]{1,1,1},3,1,0);
                particles.emitRainSplash(8,65,8,false,1);
                particles.update(1f/60);
                particles.render(projection,view,new Vector3f(1,0,0),new Vector3f(0,1,0),atlas,1,0.2f,1,0);
                int error = glGetError();
                if (error != GL_NO_ERROR) throw new AssertionError("GL error frame " + frame + ": 0x" + Integer.toHexString(error));
            }
            glFinish();
            mesh.destroy(); culler.destroy(); particles.destroy(); precipitation.destroy(); chunk.destroy(); shadow.destroy(); mobShadow.destroy(); water.destroy(); atlas.destroy();
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("GL resource cleanup error");
            System.out.println("Optimization GL smoke passed");
        } finally { window.destroy(); }
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }

    /** Small real-GL test; glFinish is a test barrier, never part of the timer implementation. */
    private static void compareGpuPhaseTiming() {
        int beginStamp = glGenQueries(), endStamp = glGenQueries();
        try (GpuTimers timers = new GpuTimers()) {
            double phaseSum = 0, frameSum = 0;
            long overhead = 0;
            for (int frame = 0; frame < 100; frame++) {
                long cpu = System.nanoTime();
                timers.beginFrame();
                if (frame >= 20) overhead += System.nanoTime() - cpu;
                glQueryCounter(beginStamp, GL_TIMESTAMP);
                for (GpuTimers.Phase phase : GpuTimers.Phase.values()) {
                    cpu = System.nanoTime();
                    timers.next(phase);
                    if (frame >= 20) overhead += System.nanoTime() - cpu;
                    for (int draw = 0; draw < 256; draw++) {
                        glClearColor(draw / 256f, phase.ordinal() / 7f, 0.25f, 1);
                        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    }
                }
                cpu = System.nanoTime();
                timers.endFrame();
                if (frame >= 20) overhead += System.nanoTime() - cpu;
                glQueryCounter(endStamp, GL_TIMESTAMP);
                glFinish();
                timers.poll();
                long elapsed = glGetQueryObjectui64(endStamp, GL_QUERY_RESULT)
                        - glGetQueryObjectui64(beginStamp, GL_QUERY_RESULT);
                if (frame >= 20) {
                    phaseSum += timers.totalMillis();
                    frameSum += elapsed / 1_000_000.0;
                }
                if (timers.sampleFrame() != frame || timers.totalMillis() <= 0)
                    throw new AssertionError("GPU phase queries did not publish a complete measured frame");
            }
            double error = java.lang.Math.abs(phaseSum - frameSum) / frameSum;
            System.out.printf(java.util.Locale.ROOT,
                    "GPU_TIMERS_GL: phase_sum=%.3f frame=%.3f ms error=%.2f%% CPU_overhead=%.4f ms/frame%n",
                    phaseSum / 80, frameSum / 80, error * 100, overhead / 80.0 / 1_000_000.0);
            if (error > 0.10) throw new AssertionError("GPU phase sum differs from frame timestamps: " + error);
        } finally {
            glDeleteQueries(beginStamp);
            glDeleteQueries(endStamp);
        }
    }
    private static void compareParticlePhysics() {
        if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL43 || Boolean.getBoolean("mineclone.cpuParticles")) return;
        String previousGpu = System.getProperty("mineclone.gpuParticles");
        System.setProperty("mineclone.gpuParticles", "true");
        ParticleSystem gpu;
        try { gpu = new ParticleSystem(); }
        finally {
            if (previousGpu == null) System.clearProperty("mineclone.gpuParticles");
            else System.setProperty("mineclone.gpuParticles", previousGpu);
        }
        System.setProperty("mineclone.cpuParticles", "true");
        ParticleSystem cpu;
        try { cpu = new ParticleSystem(); } finally { System.clearProperty("mineclone.cpuParticles"); }
        try {
            ((java.util.Random)field(gpu,"rnd")).setSeed(1);
            ((java.util.Random)field(cpu,"rnd")).setSeed(1);
            for (ParticleSystem p : new ParticleSystem[]{gpu,cpu}) {
                p.emitBlockBreak(0,10,0,new float[]{1,1,1},3,1,0);
                p.emitBreath(0,10,0,1,0); p.setWind(1,2);
                for(int step=0;step<12;step++) p.update(1f/60);
            }
            int count = (int)field(gpu,"count");
            Object physics = field(gpu,"gpu");
            int buffer = (int)field(physics,"buffer");
            float[] values = new float[count*12];
            glBindBuffer(GL_ARRAY_BUFFER,buffer); glGetBufferSubData(GL_ARRAY_BUFFER,0,values);
            String[] fields = {"x","y","z","size","vx","vy","vz","gravityScale"};
            for(int c=0;c<fields.length;c++) {
                float[] expected = (float[])field(cpu,fields[c]);
                for(int j=0;j<count;j++) if(java.lang.Math.abs(expected[j]-values[j*12+c]) > 0.0001f)
                    throw new AssertionError("GPU/CPU physics mismatch: " + fields[c]);
            }
            System.out.println("GPU/CPU particle physics parity passed");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        finally { gpu.destroy(); cpu.destroy(); }
    }

}
