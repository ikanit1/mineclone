package com.mineclone.render;

import java.util.concurrent.*;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL20.*;

/** Compiles startup programs on one shared, hidden context while assets are decoded. */
public final class ShaderPreloader implements AutoCloseable {
    private record Source(String vertex, String fragment) {}
    private static volatile ShaderPreloader active;
    private final ConcurrentHashMap<Source, CompletableFuture<Integer>> programs = new ConcurrentHashMap<>();
    private final long context;
    private final Thread worker;

    public static ShaderPreloader start(long share) { return new ShaderPreloader(share); }
    private ShaderPreloader(long share) {
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_SAMPLES, 0);
        context = glfwCreateWindow(16, 16, "Shader loader", 0, share);
        if (context == 0) throw new IllegalStateException("Cannot create shared shader context");
        String[][] sources = {
            {Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT}, {Shaders.CHUNK_VERTEX, Shaders.WATER_FRAGMENT},
            {Shaders.SHADOW_VERTEX, Shaders.SHADOW_FRAGMENT}, {Shaders.SHADOW_MOB_VERTEX, Shaders.SHADOW_FRAGMENT},
            {Shaders.INSTANCED_PARTICLE_VERTEX, Shaders.INSTANCED_PARTICLE_FRAGMENT},
            {Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT},
            {Shaders.UI_BATCH_VERTEX, Shaders.UI_BATCH_FRAGMENT}
        };
        Source[] jobs = new Source[sources.length];
        for (int i = 0; i < jobs.length; i++) {
            jobs[i] = new Source(sources[i][0], sources[i][1]);
            programs.put(jobs[i], new CompletableFuture<>());
        }
        // Keep the futures separately: the render thread consumes/removes map entries.
        var futures = new java.util.ArrayList<CompletableFuture<Integer>>();
        for (Source job : jobs) futures.add(programs.get(job));
        active = this;
        worker = new Thread(() -> {
            try {
                glfwMakeContextCurrent(context);
                GL.createCapabilities();
                for (int i = 0; i < jobs.length; i++) {
                    try {
                        int program = Shader.compileProgram(jobs[i].vertex, jobs[i].fragment);
                        glFinish(); // Publish completed shared objects before the main context uses them.
                        futures.get(i).complete(program);
                    } catch (Throwable failure) { futures.get(i).completeExceptionally(failure); }
                }
            } catch (Throwable failure) {
                for (var future : futures) future.completeExceptionally(failure);
            } finally {
                glfwMakeContextCurrent(0);
                GL.setCapabilities(null);
            }
        }, "mineclone-shaders");
        worker.setDaemon(true);
        worker.start();
    }
    static Integer take(String vertex, String fragment) {
        ShaderPreloader loader = active;
        if (loader == null) return null;
        var future = loader.programs.remove(new Source(vertex, fragment));
        return future == null ? null : future.join();
    }
    @Override public void close() {
        boolean interrupted = false;
        while (worker.isAlive()) {
            try { worker.join(); } catch (InterruptedException e) { interrupted = true; }
        }
        active = null;
        for (var future : programs.values())
            if (!future.isCompletedExceptionally()) glDeleteProgram(future.join());
        programs.clear();
        glfwDestroyWindow(context);
        if (interrupted) Thread.currentThread().interrupt();
    }
}
