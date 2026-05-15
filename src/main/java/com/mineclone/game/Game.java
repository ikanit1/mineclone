package com.mineclone.game;

import com.mineclone.audio.SoundEngine;
import com.mineclone.audio.Sounds;
import com.mineclone.core.Input;
import com.mineclone.core.Window;
import com.mineclone.render.*;
import com.mineclone.world.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

public class Game {
    private static final int RENDER_RADIUS = 6;

    private enum State { MENU, PLAYING, PAUSED }

    private final Window window;
    private final Input input;
    private final World world;
    private final ChunkMesher mesher;
    private final ChunkLoader loader;
    private final Player player = new Player();
    private final TextureAtlas atlas;
    private final Shader chunkShader;
    private final Crosshair crosshair;
    private final BlockOutline outline;
    private final ParticleSystem particles = new ParticleSystem();
    private final SoundEngine sound = new SoundEngine();
    private final Sounds sounds = new Sounds();
    private Font font;
    private TextRenderer text;
    private UiRenderer ui;
    private Hud hud;

    private State state = State.MENU;
    private boolean showDebug = false;

    private int fpsFrames;
    private int fpsCurrent;
    private double fpsLastSample = 0;
    private int drawnChunks;

    private float stepDistance = 0f;
    private final Vector3f lastPos = new Vector3f();

    private final Map<Long, Mesh> chunkMeshes = new HashMap<>();
    private int selectedSlot = 2; // STONE
    private final BlockType[] hotbar = {
            BlockType.DIRT, BlockType.GRASS, BlockType.STONE, BlockType.COBBLE,
            BlockType.SAND, BlockType.WOOD, BlockType.PLANKS, BlockType.LEAVES, BlockType.WATER
    };

    public Game(Window window, boolean regenAtlas) {
        this.window = window;
        this.input = new Input(window.getHandle());
        this.world = new World(1337L);
        this.mesher = new ChunkMesher(world);
        this.loader = new ChunkLoader(world, mesher);
        this.atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, regenAtlas);
        this.chunkShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
        this.crosshair = new Crosshair();
        this.outline = new BlockOutline();
    }

    private BlockType currentBlock() { return hotbar[selectedSlot]; }

    public void run() {
        sound.init();
        try {
            font = new Font("assets/minecraft.ttf", 22f);
        } catch (java.io.IOException e) {
            System.err.println("Failed to load font: " + e.getMessage());
        }
        text = new TextRenderer();
        ui = new UiRenderer();
        if (font != null) hud = new Hud(font, text, ui, atlas);

        // Preload spawn 3x3 so the world is ready to act as a menu backdrop and
        // the player has ground on Start. Everything else streams via ChunkLoader.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                world.getChunk(dx, dz);

        int sx = 8, sz = 8;
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
            if (world.getBlock(sx, y, sz).solid) {
                player.position.set(sx + 0.5f, y + 1.1f, sz + 0.5f);
                break;
            }
        }

        // Start in menu with the cursor free.
        input.grabCursor(false);

        double lastTime = GLFW.glfwGetTime();
        while (!window.shouldClose()) {
            double now = GLFW.glfwGetTime();
            float dt = (float) Math.min(0.05, now - lastTime);
            lastTime = now;

            input.update();

            switch (state) {
                case MENU    -> updateMenu(dt);
                case PLAYING -> updatePlaying(dt);
                case PAUSED  -> updatePaused();
            }

            render();
            sound.tick();
            window.update();
        }
        cleanup();
    }

    // ---------------- state updates ----------------

    private void updateMenu(float dt) {
        // Slow panorama spin for the backdrop.
        player.camera.rotate(0.10f * dt, 0f);

        // chunks keep streaming in the background even on the menu
        ensureChunksLoaded();
        updateDirtyMeshes();
    }

    private void updatePlaying(float dt) {
        if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            state = State.PAUSED;
            input.grabCursor(false);
            return;
        }
        if (input.keyPressed(GLFW.GLFW_KEY_F3)) showDebug = !showDebug;

        handleHotbar();
        player.update(dt, world, input);
        updateFootsteps();
        ensureChunksLoaded();
        handleInteraction();
        particles.update(dt);
        updateDirtyMeshes();
    }

    private void updatePaused() {
        if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            state = State.PLAYING;
            input.grabCursor(true);
            return;
        }
        // keep streaming so the world stays consistent if the player resumes
        ensureChunksLoaded();
        updateDirtyMeshes();
    }

    private void handleHotbar() {
        int prev = selectedSlot;
        int[] keys = {GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4,
                GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9};
        for (int i = 0; i < keys.length && i < hotbar.length; i++) {
            if (input.keyPressed(keys[i])) selectedSlot = i;
        }
        double scroll = input.getScroll();
        if (scroll != 0) {
            int dir = scroll > 0 ? -1 : 1; // scroll up -> previous slot
            selectedSlot = Math.floorMod(selectedSlot + dir, hotbar.length);
        }
        if (selectedSlot != prev) sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
    }

    private void ensureChunksLoaded() {
        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);
        loader.ensureRadius(pcx, pcz, RENDER_RADIUS + 1);
        for (ChunkLoader.Ready r : loader.drainReady(3)) {
            Mesh old = chunkMeshes.remove(r.key);
            if (old != null) old.destroy();
            chunkMeshes.put(r.key, r.data.upload());
        }
    }

    private Raycaster.Hit lastHit = null;

    private void handleInteraction() {
        Vector3f origin = new Vector3f(player.camera.position);
        Vector3f dir = player.camera.forward();
        lastHit = Raycaster.cast(world, origin, dir, 6f);

        if (lastHit != null) {
            if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
                if (target != BlockType.BEDROCK) {
                    sound.playOneOf(sounds.dig(target), 0.8f, 0.9f + 0.2f * (float) Math.random());
                    particles.emitBlockBreak(lastHit.x, lastHit.y, lastHit.z, target.particleColor);
                    world.setBlock(lastHit.x, lastHit.y, lastHit.z, BlockType.AIR);
                }
            }
            if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                int px = lastHit.x + lastHit.nx;
                int py = lastHit.y + lastHit.ny;
                int pz = lastHit.z + lastHit.nz;
                if (!playerOccupies(px, py, pz)) {
                    sound.playOneOf(sounds.place(currentBlock()), 0.8f, 0.85f + 0.2f * (float) Math.random());
                    world.setBlock(px, py, pz, currentBlock());
                }
            }
        }
    }

    private void updateFootsteps() {
        Vector3f cur = player.position;
        if (lastPos.x == 0 && lastPos.y == 0 && lastPos.z == 0) {
            lastPos.set(cur); return;
        }
        if (player.onGround) {
            float dx = cur.x - lastPos.x, dz = cur.z - lastPos.z;
            stepDistance += (float) Math.sqrt(dx * dx + dz * dz);
            if (stepDistance > 2.0f) {
                stepDistance = 0f;
                int bx = (int) Math.floor(cur.x);
                int by = (int) Math.floor(cur.y - 0.1f);
                int bz = (int) Math.floor(cur.z);
                BlockType under = world.getBlock(bx, by, bz);
                sound.playOneOf(sounds.step(under), 0.35f, 0.95f + 0.1f * (float) Math.random());
            }
        }
        lastPos.set(cur);
    }

    private boolean playerOccupies(int bx, int by, int bz) {
        float hw = Player.WIDTH / 2f;
        float minX = player.position.x - hw, maxX = player.position.x + hw;
        float minY = player.position.y,      maxY = player.position.y + Player.HEIGHT;
        float minZ = player.position.z - hw, maxZ = player.position.z + hw;
        return bx + 1 > minX && bx < maxX
            && by + 1 > minY && by < maxY
            && bz + 1 > minZ && bz < maxZ;
    }

    private void updateDirtyMeshes() {
        int rebuilt = 0;
        for (Chunk c : world.getLoadedChunks()) {
            if (!c.dirty) continue;
            long key = World.key(c.cx, c.cz);
            Mesh old = chunkMeshes.remove(key);
            if (old != null) old.destroy();
            chunkMeshes.put(key, mesher.build(c));
            loader.markMeshed(key);
            c.dirty = false;
            if (++rebuilt >= 4) break;
        }
    }

    // ---------------- rendering ----------------

    private void render() {
        glViewport(0, 0, window.getWidth(), window.getHeight());
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        Matrix4f proj = player.camera.getProjection(window.getAspect(), 75f, 0.1f, 400f);
        Matrix4f view = player.camera.getView();

        chunkShader.bind();
        chunkShader.setMat4("uProjection", proj);
        chunkShader.setMat4("uView", view);
        chunkShader.setInt("uAtlas", 0);
        chunkShader.setVec3("uFogColor", new Vector3f(0.55f, 0.75f, 0.95f));
        chunkShader.setFloat("uFogStart", RENDER_RADIUS * Chunk.SIZE_X * 0.5f);
        chunkShader.setFloat("uFogEnd",   RENDER_RADIUS * Chunk.SIZE_X * 1.0f);
        chunkShader.setFloat("uAmbient", 0.22f);
        chunkShader.setFloat("uDaylight", 1.0f);
        atlas.bind(0);

        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);

        glDisable(GL_BLEND);
        drawnChunks = 0;
        for (Map.Entry<Long, Mesh> e : chunkMeshes.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 32);
            int cz = (int) (k & 0xFFFFFFFFL);
            if (Math.abs(cx - pcx) > RENDER_RADIUS || Math.abs(cz - pcz) > RENDER_RADIUS) continue;
            Matrix4f model = new Matrix4f().translate(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z);
            chunkShader.setMat4("uModel", model);
            e.getValue().render();
            drawnChunks++;
        }
        chunkShader.unbind();

        if (state == State.PLAYING && lastHit != null) {
            outline.render(proj, view, lastHit.x, lastHit.y, lastHit.z);
        }

        Vector3f camRight = player.camera.right();
        Vector3f camUp = new Vector3f(camRight).cross(player.camera.forward()).normalize();
        particles.render(proj, view, camRight, camUp);

        drawUi();
    }

    private void drawUi() {
        if (hud == null) return;
        int w = window.getWidth(), h = window.getHeight();

        // fps sampling
        fpsFrames++;
        double now = GLFW.glfwGetTime();
        if (now - fpsLastSample >= 0.5) {
            fpsCurrent = (int) Math.round(fpsFrames / (now - fpsLastSample));
            fpsFrames = 0;
            fpsLastSample = now;
        }

        switch (state) {
            case MENU -> {
                boolean clicked = input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                Hud.MenuAction a = hud.drawMainMenu(w, h, input.getCursorX(), input.getCursorY(), clicked);
                if (a != Hud.MenuAction.NONE) sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                if (a == Hud.MenuAction.START)     { state = State.PLAYING; input.grabCursor(true); }
                else if (a == Hud.MenuAction.QUIT) GLFW.glfwSetWindowShouldClose(window.getHandle(), true);
            }
            case PLAYING -> {
                crosshair.render(w, h);
                hud.drawHotbar(w, h, hotbar, selectedSlot);
                if (showDebug) {
                    int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
                    int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);
                    hud.drawDebug(w, h, fpsCurrent, player.position, pcx, pcz,
                            countLoadedChunks(), drawnChunks);
                }
            }
            case PAUSED -> {
                hud.drawHotbar(w, h, hotbar, selectedSlot);
                boolean clicked = input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                Hud.MenuAction a = hud.drawPauseMenu(w, h, input.getCursorX(), input.getCursorY(), clicked);
                if (a != Hud.MenuAction.NONE) sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                if (a == Hud.MenuAction.RESUME)    { state = State.PLAYING; input.grabCursor(true); }
                else if (a == Hud.MenuAction.QUIT) GLFW.glfwSetWindowShouldClose(window.getHandle(), true);
            }
        }
    }

    private int countLoadedChunks() {
        int n = 0;
        for (Chunk ignored : world.getLoadedChunks()) n++;
        return n;
    }

    private void cleanup() {
        loader.shutdown();
        sound.destroy();
        for (Mesh m : chunkMeshes.values()) m.destroy();
        chunkMeshes.clear();
        atlas.destroy();
        chunkShader.destroy();
        crosshair.destroy();
        outline.destroy();
        particles.destroy();
        if (ui != null) ui.destroy();
        if (text != null) text.destroy();
        if (font != null) font.destroy();
    }
}
