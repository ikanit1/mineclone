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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

public class Game {
    private int renderRadius = 6;
    private int fovDegrees = 75;
    private float brightness = 1.0f;
    private float volume = 1.0f;

    private enum State {
        MENU, PLAYING, PAUSED, CREATIVE_MENU
    }

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
    private final SkyRenderer skyRenderer;
    private final ParticleSystem particles = new ParticleSystem();
    private final SoundEngine sound = new SoundEngine();
    private final Sounds sounds = new Sounds();
    private Font font;
    private TextRenderer text;
    private UiRenderer ui;
    private Hud hud;

    private State state = State.MENU;
    private boolean showDebug = false;
    private boolean inSettings = false;

    private static final float TIME_SCALE = 0.005f; // ~21 min real = full day/night cycle (~10.5 min day, ~10.5 min
                                                    // night)
    private float gameTime = (float) (Math.PI / 6.0); // start at ~morning: sun 30° above eastern horizon
    private float daylight = 1.0f;

    private int fpsFrames;
    private int fpsCurrent;
    private double fpsLastSample = 0;
    private int drawnChunks;

    private float stepDistance = 0f;
    private final Vector3f lastPos = new Vector3f();
    private float torchParticleTimer = 0f;
    private float waterTickTimer = 0.5f;
    private float totalTime = 0f;
    private boolean wasInWater = false;
    private boolean wireframe = false;
    private boolean consoleOpen = false;
    private final StringBuilder consoleLine = new StringBuilder();

    private final Map<Long, Mesh> chunkMeshes = new HashMap<>();
    private final Map<Long, Mesh> waterMeshes = new HashMap<>();
    private int selectedSlot = 0;
    private final BlockType[] hotbar = {
            BlockType.STONE, BlockType.DIRT, BlockType.GRASS, BlockType.PLANKS,
            BlockType.GLASS, BlockType.DOOR_CLOSED, BlockType.STAIRS, BlockType.TORCH, BlockType.WATER
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
        this.skyRenderer = new SkyRenderer();
    }

    private BlockType currentBlock() {
        return hotbar[selectedSlot];
    }

    public void run() {
        sound.init();
        try {
            font = new Font("assets/minecraft.ttf", 22f);
        } catch (java.io.IOException e) {
            System.err.println("Failed to load font: " + e.getMessage());
        }
        text = new TextRenderer();
        ui = new UiRenderer();
        if (font != null)
            hud = new Hud(font, text, ui, atlas);

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
            if (input.keyPressed(GLFW.GLFW_KEY_F11))
                window.toggleFullscreen();

            switch (state) {
                case MENU -> updateMenu(dt);
                case PLAYING -> updatePlaying(dt);
                case PAUSED -> updatePaused();
                case CREATIVE_MENU -> updateCreativeMenu(dt);
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

    private float computeDaylight() {
        return Math.max(0f, (float) Math.sin(gameTime));
    }

    private static Vector3f skyColor(float d) {
        float[] night = { 0.02f, 0.03f, 0.08f };
        float[] horizon = { 0.85f, 0.45f, 0.20f };
        float[] day = { 0.55f, 0.75f, 0.95f };
        float[] a, b;
        float t;
        if (d < 0.3f) {
            a = night;
            b = horizon;
            t = d / 0.3f;
        } else {
            a = horizon;
            b = day;
            t = (d - 0.3f) / 0.7f;
        }
        return new Vector3f(a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t);
    }

    private void updatePlaying(float dt) {
        gameTime += dt * TIME_SCALE;
        daylight = computeDaylight();
        if (input.keyPressed(GLFW.GLFW_KEY_E)) {
            state = State.CREATIVE_MENU;
            input.grabCursor(false);
            return;
        }
        if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            state = State.PAUSED;
            input.grabCursor(false);
            return;
        }
        if (input.keyPressed(GLFW.GLFW_KEY_F3))
            showDebug = !showDebug;
        if (input.keyPressed(GLFW.GLFW_KEY_F4))
            wireframe = !wireframe;

        // Console: open with T, close with Escape, execute with Enter
        if (!consoleOpen && input.keyPressed(GLFW.GLFW_KEY_T)) {
            consoleOpen = true;
            input.grabCursor(false);
            consoleLine.setLength(0);
            input.pollChars(); // discard 't'
            return;
        }
        if (consoleOpen) {
            String typed = input.pollChars();
            consoleLine.append(typed);
            if (input.keyPressed(GLFW.GLFW_KEY_BACKSPACE) && consoleLine.length() > 0)
                consoleLine.deleteCharAt(consoleLine.length() - 1);
            if (input.keyPressed(GLFW.GLFW_KEY_ENTER)) {
                executeCommand(consoleLine.toString().trim());
                consoleOpen = false;
                consoleLine.setLength(0);
                input.grabCursor(true);
            }
            if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                consoleOpen = false;
                consoleLine.setLength(0);
                input.grabCursor(true);
            }
            return; // don't update player while console is open
        }

        handleHotbar();
        player.update(dt, world, input);
        if (player.inWater && player.swimSoundTimer <= 0f) {
            player.swimSoundTimer = 0.8f;
            sound.playOneOf(sounds.waterSwim(), 0.4f, 0.9f + 0.2f * (float) Math.random());
        }
        if (player.inWater && !wasInWater) {
            sound.playOneOf(sounds.waterSplash(), 0.8f, 0.9f + 0.1f * (float) Math.random());
        }
        wasInWater = player.inWater;
        updateFootsteps();
        ensureChunksLoaded();
        handleInteraction();
        torchParticleTimer -= dt;
        if (torchParticleTimer <= 0f) {
            torchParticleTimer = 0.07f;
            emitTorchParticles();
        }
        particles.update(dt);
        totalTime += dt;
        waterTickTimer -= dt;
        if (waterTickTimer <= 0f) {
            waterTickTimer = 0.5f;
            WaterSimulator.tick(world);
        }
        updateDirtyMeshes();
    }

    private void updatePaused() {
        if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (inSettings) {
                inSettings = false;
            } else {
                state = State.PLAYING;
                input.grabCursor(true);
            }
            return;
        }
        ensureChunksLoaded();
        updateDirtyMeshes();
    }

    private void updateCreativeMenu(float dt) {
        if (input.keyPressed(GLFW.GLFW_KEY_E) || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            state = State.PLAYING;
            input.grabCursor(true);
            return;
        }
        ensureChunksLoaded();
        updateDirtyMeshes();
    }

    private void handleHotbar() {
        int prev = selectedSlot;
        int[] keys = { GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4,
                GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9 };
        for (int i = 0; i < keys.length && i < hotbar.length; i++) {
            if (input.keyPressed(keys[i]))
                selectedSlot = i;
        }
        double scroll = input.getScroll();
        if (scroll != 0) {
            int dir = scroll > 0 ? -1 : 1; // scroll up -> previous slot
            selectedSlot = Math.floorMod(selectedSlot + dir, hotbar.length);
        }
        if (selectedSlot != prev)
            sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
    }

    private void ensureChunksLoaded() {
        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);
        loader.ensureRadius(pcx, pcz, renderRadius + 1);
        for (ChunkLoader.Ready r : loader.drainReady(3)) {
            Mesh old = chunkMeshes.remove(r.key);
            if (old != null) old.destroy();
            Mesh oldW = waterMeshes.remove(r.key);
            if (oldW != null) oldW.destroy();
            if (!r.data[0].isEmpty()) chunkMeshes.put(r.key, r.data[0].upload());
            if (!r.data[1].isEmpty()) waterMeshes.put(r.key, r.data[1].upload());
        }
    }

    private Raycaster.Hit lastHit = null;

    private void handleInteraction() {
        Vector3f origin = new Vector3f(player.camera.position);
        Vector3f dir = player.camera.forward();
        lastHit = Raycaster.cast(world, origin, dir, 6f);

        if (lastHit != null) {
            // Debug stick: middle click cycles block metadata
            if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
                byte m = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
                world.setBlock(lastHit.x, lastHit.y, lastHit.z,
                        world.getBlock(lastHit.x, lastHit.y, lastHit.z), (byte)((m + 1) & 0x0F));
            }
            if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
                if (target != BlockType.BEDROCK) {
                    byte targetMeta = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
                    sound.playOneOf(sounds.dig(target), 0.8f, 0.9f + 0.2f * (float) Math.random());
                    particles.emitBlockBreak(lastHit.x, lastHit.y, lastHit.z, target.particleColor, target.sideTile);
                    world.setBlock(lastHit.x, lastHit.y, lastHit.z, BlockType.AIR);
                    // Remove the other half of a 2-block door
                    if (target == BlockType.DOOR_CLOSED || target == BlockType.DOOR_OPEN) {
                        int otherY = ((targetMeta & 0x4) != 0) ? lastHit.y - 1 : lastHit.y + 1;
                        BlockType other = world.getBlock(lastHit.x, otherY, lastHit.z);
                        if (other == BlockType.DOOR_CLOSED || other == BlockType.DOOR_OPEN)
                            world.setBlock(lastHit.x, otherY, lastHit.z, BlockType.AIR);
                    }
                }
            }
            if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
                if (target == BlockType.DOOR_CLOSED || target == BlockType.DOOR_OPEN) {
                    byte m = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
                    BlockType next = (target == BlockType.DOOR_CLOSED) ? BlockType.DOOR_OPEN : BlockType.DOOR_CLOSED;
                    world.setBlock(lastHit.x, lastHit.y, lastHit.z, next, m);
                    // Toggle the other half too
                    int otherY = ((m & 0x4) != 0) ? lastHit.y - 1 : lastHit.y + 1;
                    BlockType other = world.getBlock(lastHit.x, otherY, lastHit.z);
                    if (other == BlockType.DOOR_CLOSED || other == BlockType.DOOR_OPEN) {
                        byte om = world.getBlockMeta(lastHit.x, otherY, lastHit.z);
                        world.setBlock(lastHit.x, otherY, lastHit.z, next, om);
                    }
                    sound.playOneOf(sounds.doorToggle(), 0.8f, 0.95f + 0.1f * (float) Math.random());
                } else {
                    int px = lastHit.x + lastHit.nx;
                    int py = lastHit.y + lastHit.ny;
                    int pz = lastHit.z + lastHit.nz;
                    if (!playerOccupies(px, py, pz)) {
                        BlockType placing = currentBlock();
                        byte meta = 0;
                        if (placing == BlockType.DOOR_CLOSED) {
                            meta = facingFromCamera();
                            // Place 2-block door: bottom + top
                            if (world.getBlock(px, py + 1, pz) == BlockType.AIR
                                    && !playerOccupies(px, py + 1, pz)) {
                                sound.playOneOf(sounds.place(placing), 0.8f, 0.85f + 0.2f * (float) Math.random());
                                world.setBlock(px, py, pz, BlockType.DOOR_CLOSED, meta);
                                world.setBlock(px, py + 1, pz, BlockType.DOOR_CLOSED, (byte) (meta | 0x4));
                            }
                        } else {
                            if (placing == BlockType.STAIRS)
                                meta = stairFacingFromCamera();
                            sound.playOneOf(sounds.place(placing), 0.8f, 0.85f + 0.2f * (float) Math.random());
                            world.setBlock(px, py, pz, placing, meta);
                        }
                    }
                }
            }
        }
    }

    private byte facingFromCamera() {
        Vector3f fwd = player.camera.forward();
        float ax = Math.abs(fwd.x), az = Math.abs(fwd.z);
        if (ax > az)
            return (byte) (fwd.x > 0 ? 1 : 3);
        else
            return (byte) (fwd.z > 0 ? 0 : 2);
    }

    private void executeCommand(String cmd) {
        if (cmd.isEmpty()) return;
        String[] parts = cmd.split("\\s+");
        try {
            switch (parts[0]) {
                case "/time" -> {
                    if (parts.length >= 3 && parts[1].equals("set")) {
                        gameTime = switch (parts[2]) {
                            case "day"   -> (float)(Math.PI / 6.0);
                            case "noon"  -> (float)(Math.PI / 2.0);
                            case "night" -> (float)(Math.PI * 1.2);
                            default -> gameTime;
                        };
                    }
                }
                case "/speed" -> {
                    if (parts.length >= 2) {
                        float s = Float.parseFloat(parts[1]);
                        player.flying = true;
                        // speed is applied through Player constants — temporarily override via meta-speed factor
                        // Store in a dedicated field
                        customFlySpeed = s;
                    }
                }
                case "/tp" -> {
                    if (parts.length >= 4) {
                        float tx = Float.parseFloat(parts[1]);
                        float ty = Float.parseFloat(parts[2]);
                        float tz = Float.parseFloat(parts[3]);
                        player.position.set(tx, ty, tz);
                    }
                }
                case "/fly" -> player.flying = !player.flying;
                case "/fill" -> {
                    String blockName = parts.length >= 2 ? parts[1].toUpperCase() : "WATER";
                    int radius = parts.length >= 3 ? Integer.parseInt(parts[2]) : 4;
                    BlockType fill;
                    try { fill = BlockType.valueOf(blockName); }
                    catch (IllegalArgumentException e) { fill = BlockType.WATER; }
                    int cx = (int) Math.floor(player.position.x);
                    int cy = (int) Math.floor(player.position.y);
                    int cz = (int) Math.floor(player.position.z);
                    for (int dx = -radius; dx <= radius; dx++)
                        for (int dz = -radius; dz <= radius; dz++)
                            world.setBlock(cx + dx, cy, cz + dz, fill);
                }
                case "/debug" -> showDebug = !showDebug;
            }
        } catch (NumberFormatException ignored) {}
    }

    private float customFlySpeed = -1f;

    private byte stairFacingFromCamera() {
        Vector3f fwd = player.camera.forward();
        float ax = Math.abs(fwd.x), az = Math.abs(fwd.z);
        if (ax > az)
            return (byte) (fwd.x > 0 ? 1 : 3);
        else
            return (byte) (fwd.z > 0 ? 2 : 0);
    }

    private void emitTorchParticles() {
        int px = (int) Math.floor(player.position.x);
        int py = (int) Math.floor(player.position.y);
        int pz = (int) Math.floor(player.position.z);
        for (int dx = -5; dx <= 5; dx++)
            for (int dy = -3; dy <= 5; dy++)
                for (int dz = -5; dz <= 5; dz++)
                    if (world.getBlock(px + dx, py + dy, pz + dz) == BlockType.TORCH)
                        particles.emitTorchEffects(px + dx, py + dy, pz + dz);
    }

    private void updateFootsteps() {
        Vector3f cur = player.position;
        if (lastPos.x == 0 && lastPos.y == 0 && lastPos.z == 0) {
            lastPos.set(cur);
            return;
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
        float minY = player.position.y, maxY = player.position.y + Player.HEIGHT;
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
            Mesh oldW = waterMeshes.remove(key);
            if (oldW != null) oldW.destroy();
            MeshData[] data = mesher.buildData(c);
            if (!data[0].isEmpty()) chunkMeshes.put(key, data[0].upload());
            if (!data[1].isEmpty()) waterMeshes.put(key, data[1].upload());
            loader.markMeshed(key);
            c.dirty = false;
            if (++rebuilt >= 4) break;
        }
    }

    // ---------------- rendering ----------------

    private void render() {
        glViewport(0, 0, window.getWidth(), window.getHeight());
        Vector3f sky = skyColor(daylight);
        glClearColor(sky.x, sky.y, sky.z, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        Matrix4f proj = player.camera.getProjection(window.getAspect(), fovDegrees, 0.1f, 600f);
        Matrix4f view = player.camera.getView();

        // Sky (sun + moon) — rendered before chunks, no depth write so they sit behind
        // geometry
        glDepthMask(false);
        skyRenderer.render(proj, view, player.position, gameTime, daylight);
        glDepthMask(true);

        chunkShader.bind();
        chunkShader.setMat4("uProjection", proj);
        chunkShader.setMat4("uView", view);
        chunkShader.setInt("uAtlas", 0);
        Vector3f fogColor = player.inWater ? new Vector3f(0.04f, 0.14f, 0.55f) : sky;
        float fogStart = player.inWater ? 3f  : renderRadius * Chunk.SIZE_X * 0.5f;
        float fogEnd   = player.inWater ? 12f : renderRadius * Chunk.SIZE_X * 1.0f;
        chunkShader.setVec3("uFogColor", fogColor);
        chunkShader.setFloat("uFogStart", fogStart);
        chunkShader.setFloat("uFogEnd", fogEnd);
        chunkShader.setFloat("uAmbient", 0.04f + 0.18f * daylight);
        chunkShader.setFloat("uDaylight", daylight);
        chunkShader.setFloat("uBrightness", brightness);
        chunkShader.setFloat("uTime", totalTime);
        atlas.bind(0);

        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);

        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);
        glDisable(GL_BLEND);
        drawnChunks = 0;
        for (Map.Entry<Long, Mesh> e : chunkMeshes.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 32);
            int cz = (int) (k & 0xFFFFFFFFL);
            if (Math.abs(cx - pcx) > renderRadius || Math.abs(cz - pcz) > renderRadius)
                continue;
            Matrix4f model = new Matrix4f().translate(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z);
            chunkShader.setMat4("uModel", model);
            e.getValue().render();
            drawnChunks++;
        }
        chunkShader.unbind();

        // --- Transparent (water) pass ---
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        chunkShader.bind();
        chunkShader.setMat4("uProjection", proj);
        chunkShader.setMat4("uView", view);
        chunkShader.setInt("uAtlas", 0);
        chunkShader.setVec3("uFogColor", fogColor);
        chunkShader.setFloat("uFogStart", fogStart);
        chunkShader.setFloat("uFogEnd", fogEnd);
        chunkShader.setFloat("uAmbient", 0.04f + 0.18f * daylight);
        chunkShader.setFloat("uDaylight", daylight);
        chunkShader.setFloat("uBrightness", brightness);
        chunkShader.setFloat("uTime", totalTime);
        atlas.bind(0);

        List<Long> waterKeys = new ArrayList<>(waterMeshes.keySet());
        waterKeys.sort((ka, kb) -> {
            int cxa = (int)(ka >> 32), cza = (int)(ka & 0xFFFFFFFFL);
            int cxb = (int)(kb >> 32), czb = (int)(kb & 0xFFFFFFFFL);
            float dxa = cxa * Chunk.SIZE_X + Chunk.SIZE_X * 0.5f - player.position.x;
            float dza = cza * Chunk.SIZE_Z + Chunk.SIZE_Z * 0.5f - player.position.z;
            float dxb = cxb * Chunk.SIZE_X + Chunk.SIZE_X * 0.5f - player.position.x;
            float dzb = czb * Chunk.SIZE_Z + Chunk.SIZE_Z * 0.5f - player.position.z;
            float distA = dxa * dxa + dza * dza;
            float distB = dxb * dxb + dzb * dzb;
            return Float.compare(distB, distA); // far first
        });
        for (Long k : waterKeys) {
            int cx = (int)(k >> 32), cz = (int)(k & 0xFFFFFFFFL);
            if (Math.abs(cx - pcx) > renderRadius || Math.abs(cz - pcz) > renderRadius)
                continue;
            Matrix4f model = new Matrix4f().translate(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z);
            chunkShader.setMat4("uModel", model);
            waterMeshes.get(k).render();
        }
        chunkShader.unbind();
        glDepthMask(true);
        glDisable(GL_BLEND);
        // --- End water pass ---

        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL); // restore before outline/particles/UI

        if (state == State.PLAYING && lastHit != null) {
            BlockType ht = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
            if (ht == BlockType.STAIRS) {
                byte meta = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
                outline.renderStairs(proj, view, lastHit.x, lastHit.y, lastHit.z, meta);
            } else if (ht == BlockType.DOOR_CLOSED || ht == BlockType.DOOR_OPEN) {
                byte meta = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
                outline.renderDoor(proj, view, lastHit.x, lastHit.y, lastHit.z, meta,
                        ht == BlockType.DOOR_OPEN);
            } else if (ht.solid) {
                outline.render(proj, view, lastHit.x, lastHit.y, lastHit.z);
            }
        }

        Vector3f camRight = player.camera.right();
        Vector3f camUp = new Vector3f(camRight).cross(player.camera.forward()).normalize();
        particles.render(proj, view, camRight, camUp, atlas);

        drawUi();
    }

    private void drawUi() {
        if (hud == null)
            return;
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
                if (a != Hud.MenuAction.NONE)
                    sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                if (a == Hud.MenuAction.START) {
                    state = State.PLAYING;
                    input.grabCursor(true);
                } else if (a == Hud.MenuAction.QUIT)
                    GLFW.glfwSetWindowShouldClose(window.getHandle(), true);
            }
            case PLAYING -> {
                if (player.inWater && hud != null)
                    hud.drawWaterOverlay(w, h);
                crosshair.render(w, h);
                hud.drawHotbar(w, h, hotbar, selectedSlot);
                if (showDebug) {
                    int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
                    int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);
                    BlockType tgt = lastHit != null ? world.getBlock(lastHit.x, lastHit.y, lastHit.z) : null;
                    byte tgtMeta = lastHit != null ? world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z) : 0;
                    int bx = (int) Math.floor(player.position.x);
                    int by = (int) Math.floor(player.position.y + Player.EYE_HEIGHT);
                    int bz = (int) Math.floor(player.position.z);
                    int skyL = world.getSkyLight(bx, by, bz);
                    int blkL = world.getBlockLightWorld(bx, by, bz);
                    hud.drawDebug(w, h, fpsCurrent, player.position, pcx, pcz,
                            countLoadedChunks(), drawnChunks, tgt, tgtMeta, wireframe, skyL, blkL);
                }
                if (consoleOpen)
                    hud.drawConsole(w, h, consoleLine.toString());
            }
            case PAUSED -> {
                hud.drawHotbar(w, h, hotbar, selectedSlot);
                boolean clicked = input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                double mx = input.getCursorX(), my = input.getCursorY();
                if (inSettings) {
                    float[] sv = { renderRadius, fovDegrees, brightness, volume };
                    Hud.MenuAction a = hud.drawSettings(w, h, mx, my,
                            input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT), clicked, sv);
                    renderRadius = Math.round(sv[0]);
                    fovDegrees = Math.round(sv[1]);
                    brightness = sv[2];
                    if (sv[3] != volume) {
                        volume = sv[3];
                        sound.setMasterVolume(volume);
                    }
                    if (a == Hud.MenuAction.SETTINGS_BACK) {
                        inSettings = false;
                        sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                    }
                } else {
                    Hud.MenuAction a = hud.drawPauseMenu(w, h, mx, my, clicked);
                    if (a != Hud.MenuAction.NONE)
                        sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                    switch (a) {
                        case RESUME -> {
                            state = State.PLAYING;
                            input.grabCursor(true);
                        }
                        case SETTINGS -> inSettings = true;
                        case QUIT -> GLFW.glfwSetWindowShouldClose(window.getHandle(), true);
                        default -> {
                        }
                    }
                }
            }
            case CREATIVE_MENU -> {
                hud.drawHotbar(w, h, hotbar, selectedSlot);
                boolean clicked = input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                double mx = input.getCursorX(), my = input.getCursorY();
                BlockType picked = hud.drawCreativeMenu(w, h, mx, my, clicked, hotbar, selectedSlot);
                if (picked != null) {
                    hotbar[selectedSlot] = picked;
                    sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                }
            }
        }

        // always show version label
        hud.drawVersionLabel(w, h);
    }

    private int countLoadedChunks() {
        int n = 0;
        for (Chunk c : world.getLoadedChunks()) {
            if (c != null)
                n++;
        }
        return n;
    }

    private void cleanup() {
        loader.shutdown();
        sound.destroy();
        for (Mesh m : chunkMeshes.values()) m.destroy();
        chunkMeshes.clear();
        for (Mesh m : waterMeshes.values()) m.destroy();
        waterMeshes.clear();
        atlas.destroy();
        chunkShader.destroy();
        crosshair.destroy();
        outline.destroy();
        skyRenderer.destroy();
        particles.destroy();
        if (ui != null)
            ui.destroy();
        if (text != null)
            text.destroy();
        if (font != null)
            font.destroy();
    }
}
