package com.mineclone.game;

import com.mineclone.audio.SoundEngine;
import com.mineclone.audio.Sounds;
import com.mineclone.core.AppPaths;
import com.mineclone.core.Input;
import com.mineclone.core.Window;
import com.mineclone.render.*;
import com.mineclone.world.*;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;

public class Game {
    private int renderRadius;
    private int fovDegrees;
    private float currentFov;
    private float lastDt = 0.016f;
    private float brightness;
    private float volume;
    private int maxFps;
    private boolean vsync;
    private boolean fullscreen;
    private boolean viewBobbing;
    private float mouseSensitivity;
    private boolean invertMouseY;
    private float musicVolume;
    private float effectsVolume;
    private int guiScale; // 0=Auto, 1=Small(1×), 2=Normal(2×), 3=Large(3×)

    private enum SettingsTab { HUB, VIDEO, CONTROLS, AUDIO }

    private enum State {
        MENU, LOADING, PLAYING, PAUSED, CREATIVE_MENU, DEAD
    }

    private final Window window;
    private final Input input;
    private World world;
    private ChunkMesher mesher;
    private ChunkLoader loader;
    private final Player player = new Player();
    private final TextureAtlas atlas;
    private final Shader chunkShader;
    private final HeldItemRenderer heldItemRenderer;
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
    private SettingsTab settingsTab = SettingsTab.HUB;

    private static final float TIME_SCALE = 0.005f; // ~21 min real = full day/night cycle (~10.5 min day, ~10.5 min
                                                    // night)
    private float gameTime = (float) (Math.PI / 6.0); // start at ~morning: sun 30° above eastern horizon
    private float daylight = 1.0f;

    private int fpsFrames;
    private int fpsCurrent;
    private double fpsLastSample = 0;
    private int drawnChunks;

    private float stepDistance = 0f;
    private float walkedDistance = 0f; // monotonic; drives view bob (never resets)
    private final Vector3f lastPos = new Vector3f();
    private float torchParticleTimer = 0f;
    private static final float WATER_TICK_INTERVAL = 0.18f; // ~5.5 Hz — one cell of spread per tick
    private float waterTickTimer = WATER_TICK_INTERVAL;
    private float totalTime = 0f;
    private boolean wasInWater = false;
    private float waterFlowSoundTimer = 0f;
    private boolean wireframe = false;
    private boolean consoleOpen = false;
    private final StringBuilder consoleLine = new StringBuilder();
    private static final int CHUNK_UNLOAD_MARGIN = 3;

    private final Map<Long, Mesh> chunkMeshes = new HashMap<>();
    private final Map<Long, Mesh> waterMeshes = new HashMap<>();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f scratchModel = new Matrix4f();
    private int selectedSlot = 0;
    private final BlockType[] inventory = com.mineclone.save.LevelData.defaultInventory();
    private BlockType cursorItem = BlockType.AIR;
    private final com.mineclone.save.SaveManager save = new com.mineclone.save.SaveManager();
    private String worldId;
    private String worldDisplayName = "";
    private boolean inWorldSelect = false;
    private String pendingDeleteId = null;
    private String selectedWorldId = null;
    private String renamingWorldId = null;
    private final StringBuilder renameBuffer = new StringBuilder();
    private int worldSelectScroll = 0;
    private java.util.List<com.mineclone.save.SaveManager.WorldInfo> worldList = java.util.List.of();
    private static final float AUTOSAVE_INTERVAL = 120f; // seconds
    private static final int RESPAWN_RADIUS = 10;
    private float autosaveTimer = AUTOSAVE_INTERVAL;
    private final Vector3f worldSpawn = new Vector3f(8.5f, 80.0f, 8.5f);
    private final Random respawnRandom = new Random();
    private final MenuBackground menuBackground;
    private float saveToastTimer = 0f; // seconds remaining for "Saved" toast
    private String commandToast = "";
    private float commandToastTimer = 0f;
    private float commandHelpTimer = 0f;
    private float loadingProgress = 0f;
    private float loadingVisualProgress = 0f;
    private float loadingTimer = 0f;
    /**
     * Eat mouseDown/mouseClicked until the user releases LMB. Prevents the
     * click that opened a panel from immediately grabbing a slider in it.
     */
    private boolean swallowMouseUntilUp = false;
    private float handSwing = 0f;
    private float equipProgress = 1f;
    private BlockType lastHeldBlock = inventory[0];

    public Game(Window window, boolean regenAtlas) {
        this.window = window;
        this.input = new Input(window.getHandle());
        com.mineclone.save.Options opts = save.loadOptions();
        this.renderRadius    = opts.renderRadius;
        this.fovDegrees      = opts.fovDegrees;
        this.currentFov      = opts.fovDegrees;
        this.brightness      = opts.brightness;
        this.volume          = opts.masterVolume;
        this.maxFps          = opts.maxFps;
        this.vsync           = opts.vsync;
        this.fullscreen      = opts.fullscreen;
        this.viewBobbing     = opts.viewBobbing;
        this.mouseSensitivity = opts.mouseSensitivity;
        this.invertMouseY    = opts.invertMouseY;
        this.musicVolume     = opts.musicVolume;
        this.effectsVolume   = opts.effectsVolume;
        this.guiScale        = opts.guiScale;
        window.setVSync(this.vsync);
        window.setFullscreen(this.fullscreen);
        this.menuBackground = new MenuBackground(save);
        this.atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, regenAtlas);
        this.chunkShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
        this.heldItemRenderer = new HeldItemRenderer();
        this.crosshair = new Crosshair();
        this.outline = new BlockOutline();
        this.breakOverlay = new BlockBreakOverlay();
        this.skyRenderer = new SkyRenderer();
    }

    private BlockType currentBlock() {
        return inventory[selectedSlot];
    }

    private com.mineclone.save.Options buildOptions() {
        return new com.mineclone.save.Options(
                renderRadius, fovDegrees, brightness, volume,
                maxFps, vsync, fullscreen, viewBobbing,
                mouseSensitivity, invertMouseY, musicVolume, effectsVolume, guiScale);
    }

    private int effectiveGuiScale() {
        if (guiScale >= 1 && guiScale <= 3) return guiScale;
        // Auto: 1× for ≤1080p, 2× for 1440p, 3× for 4K, capped at 4
        int h = window.getHeight();
        return Math.max(1, Math.min(4, h / 720));
    }

    private void drawActiveSettingsTab(int w, int h, double mx, double my,
            boolean down, boolean clicked) {
        Hud.MenuAction a = Hud.MenuAction.NONE;
        switch (settingsTab) {
            case HUB -> a = hud.drawSettingsHub(w, h, mx, my, clicked);
            case VIDEO -> {
                float[] sv = { renderRadius, fovDegrees, brightness,
                        maxFps == 0 ? 260f : maxFps, guiScale };
                boolean[] bt = { vsync, fullscreen, viewBobbing };
                boolean prevVsync = vsync, prevFull = fullscreen;
                a = hud.drawVideoSettings(w, h, mx, my, down, clicked, sv, bt);
                renderRadius = Math.round(sv[0]);
                fovDegrees   = Math.round(sv[1]);
                brightness   = sv[2];
                maxFps       = sv[3] >= 255f ? 0 : Math.round(sv[3]);
                guiScale     = Math.max(0, Math.min(3, Math.round(sv[4])));
                vsync        = bt[0]; fullscreen = bt[1]; viewBobbing = bt[2];
                if (vsync != prevVsync)  window.setVSync(vsync);
                if (fullscreen != prevFull) window.setFullscreen(fullscreen);
            }
            case CONTROLS -> {
                float[] sv = { mouseSensitivity };
                boolean[] bt = { invertMouseY };
                a = hud.drawControlsSettings(w, h, mx, my, down, clicked, sv, bt);
                mouseSensitivity = sv[0];
                invertMouseY     = bt[0];
            }
            case AUDIO -> {
                float[] sv = { volume, musicVolume, effectsVolume };
                a = hud.drawAudioSettings(w, h, mx, my, down, clicked, sv);
                if (sv[0] != volume)        { volume       = sv[0]; sound.setMasterVolume(volume); }
                if (sv[1] != musicVolume)   { musicVolume   = sv[1]; sound.setMusicVolume(musicVolume); }
                if (sv[2] != effectsVolume) { effectsVolume = sv[2]; sound.setEffectsVolume(effectsVolume); }
            }
        }
        switch (a) {
            case SETTINGS_OPEN_VIDEO    -> { settingsTab = SettingsTab.VIDEO;    swallowMouseUntilUp = true; }
            case SETTINGS_OPEN_CONTROLS -> { settingsTab = SettingsTab.CONTROLS; swallowMouseUntilUp = true; }
            case SETTINGS_OPEN_AUDIO    -> { settingsTab = SettingsTab.AUDIO;    swallowMouseUntilUp = true; }
            case SETTINGS_SUB_BACK -> {
                settingsTab = SettingsTab.HUB;
                sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                swallowMouseUntilUp = true;
            }
            case SETTINGS_BACK -> {
                inSettings = false;
                settingsTab = SettingsTab.HUB;
                save.saveOptions(buildOptions());
                sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
            }
            default -> {}
        }
    }

    /** Flush level.dat + every loaded chunk whose blocks changed since gen. */
    private void saveAll() {
        if (world == null)
            return;
        com.mineclone.save.LevelData d = new com.mineclone.save.LevelData(
                worldDisplayName,
                world.seed,
                player.position.x, player.position.y, player.position.z,
                worldSpawn.x, worldSpawn.y, worldSpawn.z,
                player.camera.yaw, player.camera.pitch,
                gameTime, selectedSlot, inventory);
        save.saveLevel(worldId, d);
        for (com.mineclone.world.Chunk c : world.getLoadedChunks()) {
            saveChunkIfModified(c);
        }
    }

    private void saveChunkIfModified(Chunk c) {
        if (!c.modified)
            return;
        save.saveChunkAsync(worldId,
                new com.mineclone.save.ChunkSnapshot(c.cx, c.cz,
                        c.copyBlocks(), c.copyMeta()));
        c.modified = false;
    }

    public void run() {
        sound.init();
        sound.setMasterVolume(volume);
        sound.setMusicVolume(musicVolume);
        sound.setEffectsVolume(effectsVolume);
        try {
            font = new Font(AppPaths.path("assets/minecraft.ttf"), 22f);
        } catch (java.io.IOException e) {
            System.err.println("Failed to load font: " + e.getMessage());
        }
        text = new TextRenderer();
        ui = new UiRenderer();
        if (font != null)
            hud = new Hud(font, text, ui, atlas);

        // Start in menu with the cursor free.
        input.grabCursor(false);

        double lastTime = GLFW.glfwGetTime();
        while (!window.shouldClose()) {
            double frameStart = GLFW.glfwGetTime();
            float dt = (float) Math.min(0.05, frameStart - lastTime);
            lastTime = frameStart;

            input.update();
            if (input.keyPressed(GLFW.GLFW_KEY_F11)) {
                window.toggleFullscreen();
                fullscreen = window.isFullscreen();
            }

            switch (state) {
                case MENU -> updateMenu(dt);
                case LOADING -> updateLoading(dt);
                case PLAYING -> updatePlaying(dt);
                case PAUSED -> updatePaused(dt);
                case CREATIVE_MENU -> updateCreativeMenu(dt);
                case DEAD -> updateDead(dt);
            }

            sound.updateListener(player.camera.position, player.camera.forward());
            this.lastDt = dt;
            render();
            sound.tick();
            window.update();

            if (!vsync && maxFps > 0) {
                double target = 1.0 / maxFps;
                double elapsed = GLFW.glfwGetTime() - frameStart;
                if (elapsed < target) {
                    long sleepMs = (long) ((target - elapsed) * 1000.0);
                    if (sleepMs > 0) {
                        try { Thread.sleep(sleepMs); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
            }
        }
        if (world != null) {
            saveAll();
            save.flushAndAwait();
        }
        cleanup();
    }

    // ---------------- state updates ----------------

    private void updateMenu(float dt) {
        menuBackground.update(dt);
        if (saveToastTimer > 0f)
            saveToastTimer -= dt;
        updateCommandToast(dt);

        if (renamingWorldId != null) {
            String typed = input.pollChars();
            for (char c : typed.toCharArray())
                if (renameBuffer.length() < 32) renameBuffer.append(c);
            if (input.keyPressed(GLFW.GLFW_KEY_BACKSPACE) && renameBuffer.length() > 0)
                renameBuffer.deleteCharAt(renameBuffer.length() - 1);
            if (input.keyPressed(GLFW.GLFW_KEY_ENTER) || input.keyPressed(GLFW.GLFW_KEY_KP_ENTER))
                applyRename();
            if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE))
                renamingWorldId = null;
        } else if (inWorldSelect) {
            int delta = (int) input.getScroll();
            if (delta != 0)
                worldSelectScroll = Math.max(0, worldSelectScroll - delta);
        }
    }

    private void applyRename() {
        String newName = renameBuffer.toString().trim();
        if (!newName.isEmpty() && renamingWorldId != null) {
            save.renameWorld(renamingWorldId, newName);
            if (renamingWorldId.equals(worldId)) worldDisplayName = newName;
            worldList = save.listWorlds();
        }
        renamingWorldId = null;
    }

    private void updateCommandToast(float dt) {
        if (commandToastTimer > 0f)
            commandToastTimer -= dt;
        if (commandHelpTimer > 0f)
            commandHelpTimer -= dt;
    }

    private void beginLoadingToPlay() {
        loadingProgress = 0f;
        loadingVisualProgress = 0f;
        loadingTimer = 0f;
        daylight = computeDaylight();
        player.camera.position.set(player.position.x, player.position.y + Player.EYE_HEIGHT, player.position.z);
        state = State.LOADING;
        input.grabCursor(false);
        swallowMouseUntilUp = true;
    }

    private void startWorld(String id) {
        if (world != null) unloadWorld();
        this.worldId = id;
        com.mineclone.save.LevelData lvl = save.loadLevel(id);
        long seed = (lvl != null) ? lvl.seed : new java.util.Random().nextLong();
        this.world = new World(seed);
        this.mesher = new ChunkMesher(world);
        this.loader = new ChunkLoader(world, mesher, save, id);

        // Preload spawn 3x3 so the player has ground under their feet immediately.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                loader.applySnapshot(world.getChunk(dx, dz));
        loader.drainLightFlood(9);

        // Default spawn: find solid surface at (8, ?, 8).
        int sx = 8, sz = 8;
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
            if (world.getBlock(sx, y, sz).solid) {
                player.position.set(sx + 0.5f, y + 1.1f, sz + 0.5f);
                break;
            }
        }
        worldSpawn.set(player.position.x, player.position.y, player.position.z);
        gameTime = (float) (Math.PI / 6.0);
        selectedSlot = 0;
        System.arraycopy(com.mineclone.save.LevelData.defaultInventory(), 0,
                inventory, 0, inventory.length);

        if (lvl != null) {
            worldDisplayName = lvl.name.isEmpty() ? "World" : lvl.name;
            worldSpawn.set((float) lvl.spawnX, (float) lvl.spawnY, (float) lvl.spawnZ);
            player.position.set((float) lvl.px, (float) lvl.py, (float) lvl.pz);
            player.camera.yaw = lvl.yaw;
            player.camera.pitch = lvl.pitch;
            gameTime = lvl.timeOfDay;
            selectedSlot = Math.floorMod(lvl.selectedSlot, 9);
            System.arraycopy(lvl.inventory, 0, inventory, 0,
                    Math.min(inventory.length, lvl.inventory.length));
        } else {
            worldDisplayName = "World";
        }

        player.health = Player.MAX_HEALTH;
        player.velocity.set(0, 0, 0);
        player.lastFallDistance = 0f;
        lastHeldBlock = currentBlock();
        cursorItem = BlockType.AIR;
        player.flying = false;
        player.flySpeed = Player.FLY_SPEED;

        WaterSimulator.reset();
        beginLoadingToPlay();
    }

    private void createWorld() {
        String id = com.mineclone.save.SaveFormat.newWorldId();

        // Find lowest free N for display name "World N".
        java.util.List<com.mineclone.save.SaveManager.WorldInfo> existing = save.listWorlds();
        java.util.Set<Integer> usedNums = new java.util.HashSet<>();
        for (com.mineclone.save.SaveManager.WorldInfo wi : existing) {
            int n = trailingWorldN(wi.displayName);
            if (n > 0)
                usedNums.add(n);
        }
        int n = 1;
        while (usedNums.contains(n))
            n++;
        String displayName = "World " + n;

        long seed = new java.util.Random().nextLong();
        Vector3f spawn = findDefaultSpawn(seed);
        com.mineclone.save.LevelData fresh = new com.mineclone.save.LevelData(
                displayName, seed,
                spawn.x, spawn.y, spawn.z,
                spawn.x, spawn.y, spawn.z,
                0f, 0f, (float) (Math.PI / 6.0), 0,
                com.mineclone.save.LevelData.defaultInventory());
        save.saveLevel(id, fresh);
        startWorld(id);
    }

    private static int trailingWorldN(String displayName) {
        if (!displayName.startsWith("World "))
            return -1;
        try {
            return Integer.parseInt(displayName.substring(6));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Save, flush, stop loader threads, destroy GL meshes, null world refs.
     * Must be called from the main (GL) thread only.
     */
    private void unloadWorld() {
        if (world == null)
            return;
        saveAll();
        save.flushAndAwait();
        loader.shutdown();
        for (Mesh m : chunkMeshes.values())
            m.destroy();
        for (Mesh m : waterMeshes.values())
            m.destroy();
        chunkMeshes.clear();
        waterMeshes.clear();
        WaterSimulator.reset();
        resetBreakState();
        world = null;
        mesher = null;
        loader = null;
    }

    private void updateLoading(float dt) {
        loadingTimer += dt;
        updateCommandToast(dt);
        ensureChunksLoaded();
        updateDirtyMeshes();
        loadingProgress = computeLoadingProgress();
        loadingVisualProgress += (loadingProgress - loadingVisualProgress)
                * Math.min(1f, dt * 8f);
        if (loadingProgress >= 1f && loadingTimer >= 0.45f) {
            loadingVisualProgress = 1f;
            state = State.PLAYING;
            input.grabCursor(true);
            swallowMouseUntilUp = false;
        }
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
        if (saveToastTimer > 0f)
            saveToastTimer -= dt;
        updateCommandToast(dt);
        autosaveTimer -= dt;
        if (autosaveTimer <= 0f) {
            autosaveTimer = AUTOSAVE_INTERVAL;
            saveAll();
        }
        daylight = computeDaylight();

        // Console intercepts ALL game-input keys while open — keep typing
        // isolated from game actions (E, ESC, T, hotbar digits, F3/F4).
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
            updateHeldItem(dt);
            player.update(dt, world, input, false);
            wasInWater = player.inWater;
            updateFootsteps();
            updateActiveWorld(dt);
            return; // keep the world ticking, but don't move the player while typing
        }

        if (input.keyPressed(GLFW.GLFW_KEY_E)) {
            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
            state = State.CREATIVE_MENU;
            input.grabCursor(false);
            return;
        }
        if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
            state = State.PAUSED;
            saveAll();
            input.grabCursor(false);
            swallowMouseUntilUp = true; // pause panel pops where cursor is — eat the click
            return;
        }
        if (input.keyPressed(GLFW.GLFW_KEY_F3))
            showDebug = !showDebug;
        if (input.keyPressed(GLFW.GLFW_KEY_F4))
            wireframe = !wireframe;

        // Open console on T (game key — only when chat is closed).
        if (input.keyPressed(GLFW.GLFW_KEY_T)) {
            consoleOpen = true;
            input.grabCursor(false);
            consoleLine.setLength(0);
            input.pollChars(); // discard 't'
            player.update(dt, world, input, false);
            updateActiveWorld(dt);
            return;
        }

        handleHotbar();
        updateHeldItem(dt);
        player.update(dt, world, input, true, mouseSensitivity, invertMouseY);
        if (player.justJumped) {
            int jbx = (int) Math.floor(player.position.x);
            int jby = (int) Math.floor(player.position.y - 0.1f);
            int jbz = (int) Math.floor(player.position.z);
            BlockType jumpUnder = world.getBlock(jbx, jby, jbz);
            java.util.List<String> jumpSnd = sounds.step(jumpUnder);
            if (!jumpSnd.isEmpty())
                sound.playOneOfAt(jumpSnd, playerSoundPosition(), 0.35f, 1.05f + 0.1f * (float) Math.random());
        }
        float landingDistance = player.lastFallDistance;
        if (landingDistance > 0.05f && !player.inWater) {
            playLandingStep(landingDistance);
        }
        if (player.lastFallDamage > 0f) {
            sound.playOneOfAt(sounds.hurt(), playerSoundPosition(), 0.8f, 0.9f + 0.1f * (float) Math.random());
            if (player.lastFallDamage >= 4f)
                sound.playOneOfAt(sounds.fallBig(), playerSoundPosition(), 0.9f, 0.95f + 0.1f * (float) Math.random());
            else
                sound.playOneOfAt(sounds.fallSmall(), playerSoundPosition(), 0.7f,
                        0.95f + 0.1f * (float) Math.random());
            player.lastFallDamage = 0f;
        }
        if (landingDistance >= 2f) {
            int bx = (int) Math.floor(player.position.x);
            int by = (int) Math.floor(player.position.y - 0.05f);
            int bz = (int) Math.floor(player.position.z);
            BlockType ground = world.getBlock(bx, by, bz);
            int tile = (ground != null && ground.solid) ? ground.sideTile : BlockType.DIRT.sideTile;
            float skyF = world.getSkyLight(bx, by + 1, bz) / (float) Chunk.MAX_LIGHT;
            float blkF = world.getBlockLightWorld(bx, by + 1, bz) / (float) Chunk.MAX_LIGHT;
            int count = (int) Math.min(22, 5 + landingDistance * 1.4f);
            particles.emitLandingPuff(player.position.x, player.position.y, player.position.z,
                    tile, skyF, blkF, count);
        }
        player.lastFallDistance = 0f;
        if (player.isDead()) {
            sound.playOneOf(sounds.playerDeath(), 0.9f, 0.95f + 0.1f * (float) Math.random());
            state = State.DEAD;
            input.grabCursor(false);
            return;
        }
        float hSpeed = (float) Math.sqrt(player.velocity.x * player.velocity.x + player.velocity.z * player.velocity.z);
        if (player.inWater && player.swimSoundTimer <= 0f && hSpeed > 0.3f) {
            player.swimSoundTimer = 1.2f;
            sound.playOneOfAt(sounds.waterSwim(), playerSoundPosition(), 0.4f, 0.9f + 0.2f * (float) Math.random());
        }
        if (player.inWater && !wasInWater) {
            sound.playOneOfAt(sounds.waterSplash(), playerSoundPosition(), 0.8f, 0.9f + 0.1f * (float) Math.random());
            int bx = (int) Math.floor(player.position.x);
            int by = (int) Math.floor(player.position.y + 0.5f);
            int bz = (int) Math.floor(player.position.z);
            float skyF = world.getSkyLight(bx, by, bz) / (float) Chunk.MAX_LIGHT;
            float blkF = world.getBlockLightWorld(bx, by, bz) / (float) Chunk.MAX_LIGHT;
            particles.emitWaterSplash(player.position.x, player.position.y + 0.5f, player.position.z, skyF, blkF);
        }
        if (!player.inWater && wasInWater) {
            sound.playOneOfAt(sounds.waterSplash(), playerSoundPosition(), 0.5f, 0.9f + 0.1f * (float) Math.random());
        }
        wasInWater = player.inWater;
        waterFlowSoundTimer -= dt;
        if (waterFlowSoundTimer <= 0f) {
            Vector3f waterSoundPos = findNearbyFlowingWater();
            if (waterSoundPos != null) {
                sound.playOneOfAt(sounds.waterFlow(), waterSoundPos, 0.35f, 0.85f + 0.15f * (float) Math.random());
                waterFlowSoundTimer = 5f + (float) Math.random() * 7f;
            } else {
                waterFlowSoundTimer = 2f;
            }
        }
        updateFootsteps();
        ensureChunksLoaded();
        handleInteraction(dt);
        updateActiveWorld(dt);
    }

    private void updateActiveWorld(float dt) {
        ensureChunksLoaded();
        torchParticleTimer -= dt;
        if (torchParticleTimer <= 0f) {
            torchParticleTimer = 0.07f;
            emitTorchParticles();
        }
        particles.update(dt);
        totalTime += dt;
        waterTickTimer -= dt;
        if (waterTickTimer <= 0f) {
            waterTickTimer = WATER_TICK_INTERVAL;
            WaterSimulator.tick(world);
        }
        updateDirtyMeshes();
    }

    private void updatePaused(float dt) {
        if (saveToastTimer > 0f)
            saveToastTimer -= dt;
        updateCommandToast(dt);
        if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (inSettings) {
                if (settingsTab != SettingsTab.HUB) {
                    settingsTab = SettingsTab.HUB;
                } else {
                    inSettings = false;
                    save.saveOptions(buildOptions());
                }
            } else if (player.isDead()) {
                state = State.DEAD;
                input.grabCursor(false);
            } else {
                state = State.PLAYING;
                input.grabCursor(true);
            }
            return;
        }
        updateHeldItem(dt);
        player.update(dt, world, input, false);
        wasInWater = player.inWater;
        updateFootsteps();
        updateActiveWorld(dt);
    }

    private void updateCreativeMenu(float dt) {
        updateCommandToast(dt);
        if (input.keyPressed(GLFW.GLFW_KEY_E) || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
            state = State.PLAYING;
            input.grabCursor(true);
            return;
        }
        updateHeldItem(dt);
        player.update(dt, world, input, false);
        wasInWater = player.inWater;
        updateFootsteps();
        updateActiveWorld(dt);
    }

    private void updateDead(float dt) {
        gameTime += dt * TIME_SCALE;
        daylight = computeDaylight();
        if (saveToastTimer > 0f)
            saveToastTimer -= dt;
        updateCommandToast(dt);
        updateActiveWorld(dt);
    }

    private void respawnPlayer() {
        Vector3f spawn = findRespawnPosition();
        player.respawn(spawn.x, spawn.y, spawn.z);
        lastPos.set(player.position);
        wasInWater = false;
        waterFlowSoundTimer = 0f;
        ensureChunksLoaded();
        updateDirtyMeshes();
    }

    private Vector3f findRespawnPosition() {
        int baseX = (int) Math.floor(worldSpawn.x);
        int baseZ = (int) Math.floor(worldSpawn.z);
        for (int attempts = 0; attempts < 24; attempts++) {
            int dx = respawnRandom.nextInt(RESPAWN_RADIUS * 2 + 1) - RESPAWN_RADIUS;
            int dz = respawnRandom.nextInt(RESPAWN_RADIUS * 2 + 1) - RESPAWN_RADIUS;
            Vector3f pos = findSurfaceSpawn(baseX + dx, baseZ + dz);
            if (pos != null)
                return pos;
        }
        Vector3f exact = findSurfaceSpawn(baseX, baseZ);
        if (exact != null)
            return exact;
        return new Vector3f(worldSpawn.x, worldSpawn.y, worldSpawn.z);
    }

    private Vector3f findSurfaceSpawn(int wx, int wz) {
        world.getChunk(Math.floorDiv(wx, Chunk.SIZE_X), Math.floorDiv(wz, Chunk.SIZE_Z));
        for (int y = Chunk.SIZE_Y - 3; y > 0; y--) {
            BlockType ground = world.getBlock(wx, y, wz);
            BlockType feet = world.getBlock(wx, y + 1, wz);
            BlockType head = world.getBlock(wx, y + 2, wz);
            if (ground.solid && !feet.solid && !head.solid)
                return new Vector3f(wx + 0.5f, y + 1.0001f, wz + 0.5f);
        }
        return null;
    }

    private static Vector3f findDefaultSpawn(long seed) {
        World spawnWorld = new World(seed);
        int sx = 8;
        int sz = 8;
        spawnWorld.getChunk(Math.floorDiv(sx, Chunk.SIZE_X), Math.floorDiv(sz, Chunk.SIZE_Z));
        for (int y = Chunk.SIZE_Y - 3; y > 0; y--) {
            BlockType ground = spawnWorld.getBlock(sx, y, sz);
            BlockType feet = spawnWorld.getBlock(sx, y + 1, sz);
            BlockType head = spawnWorld.getBlock(sx, y + 2, sz);
            if (ground.solid && !feet.solid && !head.solid)
                return new Vector3f(sx + 0.5f, y + 1.0001f, sz + 0.5f);
        }
        return new Vector3f(sx + 0.5f, World.SEA_LEVEL + 8.0f, sz + 0.5f);
    }

    private void handleHotbar() {
        int prev = selectedSlot;
        int[] keys = { GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4,
                GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9 };
        for (int i = 0; i < keys.length && i < 9; i++) {
            if (input.keyPressed(keys[i]))
                selectedSlot = i;
        }
        double scroll = input.getScroll();
        if (scroll != 0) {
            int dir = scroll > 0 ? -1 : 1; // scroll up -> previous slot
            selectedSlot = Math.floorMod(selectedSlot + dir, 9);
        }
        if (selectedSlot != prev) {
            equipProgress = 0f;
            sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
        }
    }

    private void updateHeldItem(float dt) {
        BlockType held = currentBlock();
        if (held != lastHeldBlock) {
            lastHeldBlock = held;
            equipProgress = 0f;
        }
        equipProgress = Math.min(1f, equipProgress + dt * 7.5f);
        handSwing = Math.max(0f, handSwing - dt * 4.5f);
    }

    private void startHandSwing() {
        handSwing = 1f;
    }

    private void ensureChunksLoaded() {
        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);
        loader.ensureRadius(pcx, pcz, renderRadius + 1);
        loader.drainLightFlood(8);
        for (ChunkLoader.Ready r : loader.drainReady(8)) {
            int cx = (int) (r.key >> 32);
            int cz = (int) (r.key & 0xFFFFFFFFL);
            if (world.getChunkIfExists(cx, cz) == null) {
                loader.forget(r.key);
                continue;
            }
            Mesh old = chunkMeshes.remove(r.key);
            if (old != null)
                old.destroy();
            Mesh oldW = waterMeshes.remove(r.key);
            if (oldW != null)
                oldW.destroy();
            if (!r.data[0].isEmpty())
                chunkMeshes.put(r.key, r.data[0].upload());
            if (!r.data[1].isEmpty())
                waterMeshes.put(r.key, r.data[1].upload());
            WaterSimulator.activateChunkIfWater(world, cx, cz);
        }
        evictDistantChunks(pcx, pcz);
    }

    private void evictDistantChunks(int pcx, int pcz) {
        int keepRadius = renderRadius + CHUNK_UNLOAD_MARGIN;
        for (Chunk c : world.getLoadedChunks()) {
            if (Math.abs(c.cx - pcx) <= keepRadius && Math.abs(c.cz - pcz) <= keepRadius)
                continue;
            saveChunkIfModified(c);
            long key = World.key(c.cx, c.cz);
            Mesh old = chunkMeshes.remove(key);
            if (old != null)
                old.destroy();
            Mesh oldW = waterMeshes.remove(key);
            if (oldW != null)
                oldW.destroy();
            loader.forget(key);
            WaterSimulator.forgetChunk(key);
            world.removeChunk(c.cx, c.cz);
        }
    }

    private float computeLoadingProgress() {
        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);
        int radius = Math.max(1, Math.min(3, renderRadius));
        int ready = 0;
        int total = (radius * 2 + 1) * (radius * 2 + 1);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                long key = World.key(cx, cz);
                if (world.getChunkIfExists(cx, cz) != null && chunkMeshes.containsKey(key))
                    ready++;
            }
        }
        return ready / (float) total;
    }

    private Raycaster.Hit lastHit = null;
    private static final int NO_BREAK = Integer.MIN_VALUE;
    private int breakX = NO_BREAK, breakY = NO_BREAK, breakZ = NO_BREAK;
    private float breakProgress = 0f;
    private float breakDigTimer = 0f;
    private BlockBreakOverlay breakOverlay;

    private void handleInteraction(float dt) {
        Vector3f origin = new Vector3f(player.camera.position);
        Vector3f dir = player.camera.forward();
        lastHit = Raycaster.cast(world, origin, dir, 6f);

        if (lastHit == null) {
            resetBreakState();
            return;
        }

        // Debug stick: middle click cycles block metadata
        if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
            byte m = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
            world.setBlock(lastHit.x, lastHit.y, lastHit.z,
                    world.getBlock(lastHit.x, lastHit.y, lastHit.z), (byte) ((m + 1) & 0x0F));
        }

        // --- Left mouse: hold-to-break ---
        if (input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
            if (target.hardness > 0f && target.hardness < Float.MAX_VALUE) {
                if (breakX == lastHit.x && breakY == lastHit.y && breakZ == lastHit.z) {
                    // Accumulate progress on the same block
                    breakProgress += dt / target.hardness;
                    breakDigTimer -= dt;
                    if (breakDigTimer <= 0f) {
                        startHandSwing();
                        sound.playOneOfAt(sounds.dig(target),
                                blockSoundPosition(lastHit.x, lastHit.y, lastHit.z),
                                0.8f, 0.9f + 0.2f * (float) Math.random());
                        breakDigTimer = 0.4f;
                    }
                    if (breakProgress >= 1f) {
                        executeBlockBreak(lastHit.x, lastHit.y, lastHit.z, target);
                        resetBreakState();
                    }
                } else {
                    // New target block
                    breakX = lastHit.x; breakY = lastHit.y; breakZ = lastHit.z;
                    breakProgress = 0f;
                    breakDigTimer = 0f;
                }
            } else {
                resetBreakState();
            }
        } else {
            resetBreakState();
        }

        // --- Right mouse: place / interact (unchanged) ---
        if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            startHandSwing();
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
                sound.playOneOfAt(sounds.doorToggle(), blockSoundPosition(lastHit.x, lastHit.y, lastHit.z),
                        0.8f, 0.95f + 0.1f * (float) Math.random());
            } else {
                int px = lastHit.x + lastHit.nx;
                int py = lastHit.y + lastHit.ny;
                int pz = lastHit.z + lastHit.nz;
                if (!playerOccupies(px, py, pz)) {
                    BlockType placing = currentBlock();
                    if (placing == null || placing == BlockType.AIR)
                        return;
                    byte meta = 0;
                    if (placing == BlockType.DOOR_CLOSED) {
                        meta = facingFromCamera();
                        // Place 2-block door: bottom + top
                        if (world.getBlock(px, py + 1, pz) == BlockType.AIR
                                && !playerOccupies(px, py + 1, pz)) {
                            sound.playOneOfAt(sounds.place(placing), blockSoundPosition(px, py, pz),
                                    0.8f, 0.85f + 0.2f * (float) Math.random());
                            world.setBlock(px, py, pz, BlockType.DOOR_CLOSED, meta);
                            world.setBlock(px, py + 1, pz, BlockType.DOOR_CLOSED, (byte) (meta | 0x4));
                        }
                    } else {
                        if (placing == BlockType.STAIRS)
                            meta = stairFacingFromCamera();
                        sound.playOneOfAt(sounds.place(placing), blockSoundPosition(px, py, pz),
                                0.8f, 0.85f + 0.2f * (float) Math.random());
                        world.setBlock(px, py, pz, placing, meta);
                    }
                }
            }
        }
    }

    private void resetBreakState() {
        breakX = NO_BREAK; breakY = NO_BREAK; breakZ = NO_BREAK;
        breakProgress = 0f;
        breakDigTimer = 0f;
    }

    private void executeBlockBreak(int x, int y, int z, BlockType target) {
        startHandSwing();
        byte targetMeta = world.getBlockMeta(x, y, z);
        sound.playOneOfAt(sounds.dig(target), blockSoundPosition(x, y, z),
                0.8f, 0.9f + 0.2f * (float) Math.random());
        world.setBlock(x, y, z, BlockType.AIR);
        float pSky = world.getSkyLight(x, y, z) / (float) com.mineclone.world.Chunk.MAX_LIGHT;
        float pBlk = world.getBlockLightWorld(x, y, z) / (float) com.mineclone.world.Chunk.MAX_LIGHT;
        particles.emitBlockBreak(x, y, z, target.particleColor, target.sideTile, pSky, pBlk);
        if (target == BlockType.DOOR_CLOSED || target == BlockType.DOOR_OPEN) {
            int otherY = ((targetMeta & 0x4) != 0) ? y - 1 : y + 1;
            BlockType other = world.getBlock(x, otherY, z);
            if (other == BlockType.DOOR_CLOSED || other == BlockType.DOOR_OPEN)
                world.setBlock(x, otherY, z, BlockType.AIR);
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
        if (cmd.isEmpty())
            return;
        String[] parts = cmd.split("\\s+");
        try {
            switch (parts[0]) {
                case "/time" -> executeTimeCommand(parts);
                case "/help", "/commands" -> showCommandHelp();
                case "/speed" -> {
                    if (parts.length >= 2) {
                        float s = Math.max(0.5f, Float.parseFloat(parts[1]));
                        player.flying = true;
                        player.flySpeed = s;
                        showCommandToast(String.format("Fly speed: %.1f", s));
                    } else {
                        showCommandToast("Usage: /speed <value>");
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
                case "/spawnpoint" -> executeSpawnPointCommand(parts);
                case "/fly" -> player.flying = !player.flying;
                case "/fill" -> {
                    String blockName = parts.length >= 2 ? parts[1].toUpperCase() : "WATER";
                    int radius = parts.length >= 3 ? Integer.parseInt(parts[2]) : 4;
                    BlockType fill;
                    try {
                        fill = BlockType.valueOf(blockName);
                    } catch (IllegalArgumentException e) {
                        fill = BlockType.WATER;
                    }
                    int cx = (int) Math.floor(player.position.x);
                    int cy = (int) Math.floor(player.position.y);
                    int cz = (int) Math.floor(player.position.z);
                    for (int dx = -radius; dx <= radius; dx++)
                        for (int dz = -radius; dz <= radius; dz++)
                            world.setBlock(cx + dx, cy, cz + dz, fill);
                }
                case "/debug" -> showDebug = !showDebug;
            }
        } catch (NumberFormatException e) {
            showCommandToast("Invalid number");
        }
    }

    private void executeSpawnPointCommand(String[] parts) {
        if (parts.length == 1) {
            worldSpawn.set(player.position);
        } else if (parts.length >= 4) {
            float x = Float.parseFloat(parts[1]);
            float y = Float.parseFloat(parts[2]);
            float z = Float.parseFloat(parts[3]);
            worldSpawn.set(x, y, z);
        } else {
            showCommandToast("Usage: /spawnpoint [x y z]");
            return;
        }
        saveAll();
        showCommandToast(String.format("Spawn point set: %.1f %.1f %.1f",
                worldSpawn.x, worldSpawn.y, worldSpawn.z));
    }

    private void executeTimeCommand(String[] parts) {
        if (parts.length == 1 || (parts.length == 2 && parts[1].equalsIgnoreCase("query"))) {
            showCommandToast("Time: " + formatGameTime());
            return;
        }
        if (parts.length < 3) {
            showCommandToast("Usage: /time set <preset|0-24>");
            return;
        }

        String op = parts[1].toLowerCase();
        switch (op) {
            case "set" -> {
                Float preset = timePreset(parts[2].toLowerCase());
                if (preset != null) {
                    gameTime = normalizeGameTime(preset);
                    daylight = computeDaylight();
                    showCommandToast("Time set to " + parts[2].toLowerCase());
                    return;
                }
                try {
                    float hours = Float.parseFloat(parts[2]);
                    if (hours < 0f || hours > 24f) {
                        showCommandToast("Usage: /time set <preset|0-24>");
                        return;
                    }
                    gameTime = normalizeGameTime(hoursToGameTime(hours));
                    daylight = computeDaylight();
                    showCommandToast("Time: " + formatGameTime());
                } catch (NumberFormatException e) {
                    showCommandToast("Unknown time preset");
                }
            }
            case "add" -> {
                try {
                    float hours = Float.parseFloat(parts[2]);
                    gameTime = normalizeGameTime(gameTime + hoursToRadians(hours));
                    daylight = computeDaylight();
                    showCommandToast("Added " + formatHours(hours) + " hours");
                } catch (NumberFormatException e) {
                    showCommandToast("Usage: /time add <hours>");
                }
            }
            default -> showCommandToast("Usage: /time set <preset|0-24>");
        }
    }

    private Float timePreset(String name) {
        return switch (name) {
            case "day", "sunrise" -> (float) (Math.PI / 6.0);
            case "noon" -> (float) (Math.PI / 2.0);
            case "sunset" -> (float) (5.0 * Math.PI / 6.0);
            case "night" -> (float) (7.0 * Math.PI / 6.0);
            case "midnight" -> (float) (3.0 * Math.PI / 2.0);
            default -> null;
        };
    }

    private static float hoursToGameTime(float hours) {
        return hoursToRadians(hours) - (float) (Math.PI / 2.0);
    }

    private static float hoursToRadians(float hours) {
        return (hours / 24f) * (float) (Math.PI * 2.0);
    }

    private static float normalizeGameTime(float t) {
        float cycle = (float) (Math.PI * 2.0);
        t %= cycle;
        if (t < 0f)
            t += cycle;
        return t;
    }

    private String formatGameTime() {
        float cycle = (float) (Math.PI * 2.0);
        float hours = ((normalizeGameTime(gameTime) + (float) (Math.PI / 2.0)) / cycle) * 24f;
        hours %= 24f;
        int totalMinutes = Math.floorMod(Math.round(hours * 60f), 24 * 60);
        int hh = totalMinutes / 60;
        int mm = totalMinutes % 60;
        return String.format("%02d:%02d", hh, mm);
    }

    private static String formatHours(float hours) {
        if (Math.abs(hours - Math.round(hours)) < 0.0001f)
            return String.format("%.1f", hours);
        return String.valueOf(hours);
    }

    private void showCommandToast(String message) {
        commandToast = message;
        commandToastTimer = 2.0f;
    }

    private void showCommandHelp() {
        commandHelpTimer = 6.0f;
        showCommandToast("Showing command help");
    }

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
            float step = (float) Math.sqrt(dx * dx + dz * dz);
            stepDistance += step;
            walkedDistance += step;
            if (stepDistance > 2.0f) {
                stepDistance = 0f;
                int bx = (int) Math.floor(cur.x);
                int by = (int) Math.floor(cur.y - 0.1f);
                int bz = (int) Math.floor(cur.z);
                BlockType under = world.getBlock(bx, by, bz);
                sound.playOneOfAt(sounds.step(under), new Vector3f(cur.x, cur.y + 0.15f, cur.z),
                        0.35f, 0.95f + 0.1f * (float) Math.random());
            }
        }
        lastPos.set(cur);
    }

    private void playLandingStep(float fallDistance) {
        int bx = (int) Math.floor(player.position.x);
        int by = (int) Math.floor(player.position.y - 0.05f);
        int bz = (int) Math.floor(player.position.z);
        BlockType under = world.getBlock(bx, by, bz);
        float volume = Math.min(0.65f, 0.32f + fallDistance * 0.08f);
        sound.playOneOfAt(sounds.step(under), playerSoundPosition(), volume, 0.85f + 0.15f * (float) Math.random());
    }

    private Vector3f playerSoundPosition() {
        return new Vector3f(player.position.x, player.position.y + 0.7f, player.position.z);
    }

    private static Vector3f blockSoundPosition(int x, int y, int z) {
        return new Vector3f(x + 0.5f, y + 0.5f, z + 0.5f);
    }

    private Vector3f findNearbyFlowingWater() {
        int px = (int) Math.floor(player.position.x);
        int py = (int) Math.floor(player.position.y);
        int pz = (int) Math.floor(player.position.z);
        int R = 6;
        int bestX = 0, bestY = 0, bestZ = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int x = px - R; x <= px + R; x++)
            for (int y = py - 2; y <= py + 4; y++)
                for (int z = pz - R; z <= pz + R; z++)
                    if (world.getBlock(x, y, z) == com.mineclone.world.BlockType.WATER_FLOW) {
                        int dx = x - px, dy = y - py, dz = z - pz;
                        int dist = dx * dx + dy * dy + dz * dz;
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestX = x;
                            bestY = y;
                            bestZ = z;
                        }
                    }
        return bestDist == Integer.MAX_VALUE ? null : blockSoundPosition(bestX, bestY, bestZ);
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
            if (!c.dirty)
                continue;
            long key = World.key(c.cx, c.cz);
            // Skip chunks whose saved blocks or deferred light flood are still
            // being applied. Meshing during that window bakes stale block/sky
            // light into chunk-shaped patches until a later rebuild catches up.
            if (loader.isPendingGen(key) || loader.hasPendingLightFlood(key))
                continue;
            if (!loader.neighboursReady(c.cx, c.cz))
                continue;
            Mesh old = chunkMeshes.remove(key);
            if (old != null)
                old.destroy();
            Mesh oldW = waterMeshes.remove(key);
            if (oldW != null)
                oldW.destroy();
            MeshData[] data = mesher.buildData(c);
            if (!data[0].isEmpty())
                chunkMeshes.put(key, data[0].upload());
            if (!data[1].isEmpty())
                waterMeshes.put(key, data[1].upload());
            loader.markMeshed(key);
            c.dirty = false;
            if (++rebuilt >= 8)
                break;
        }
    }

    // ---------------- rendering ----------------

    private void render() {
        glViewport(0, 0, window.getWidth(), window.getHeight());
        if (state == State.MENU) {
            glClearColor(0.55f, 0.75f, 0.95f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            menuBackground.render(chunkShader, atlas, window.getAspect(), fovDegrees);
            // Vignette: dark edges. Negative-alpha center quad clamps to 0,
            // so the visible effect is just the outer dim — reads as a vignette
            // against the orbiting backdrop without needing a radial shader.
            int sw = window.getWidth(), sh = window.getHeight();
            ui.begin(sw, sh);
            ui.quad(0, 0, sw, sh, 0f, 0f, 0f, 0.35f);
            float fx = sw * 0.18f, fy = sh * 0.18f;
            ui.quad(fx, fy, sw - 2 * fx, sh - 2 * fy, 0f, 0f, 0f, -0.18f);
            ui.end();
            drawUi();
            return;
        }
        Vector3f sky = skyColor(daylight);
        glClearColor(sky.x, sky.y, sky.z, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        float targetFov = player.eyeInWater  ? fovDegrees * 0.85f
                : player.isSprinting ? fovDegrees + 10f
                : fovDegrees;
        currentFov += (targetFov - currentFov) * (1f - (float) Math.exp(-lastDt * 8f));
        Matrix4f proj = player.camera.getProjection(window.getAspect(), currentFov, 0.1f, 600f);
        Matrix4f view = player.camera.getView();

        // Sky (sun + moon) — rendered before chunks, no depth write so they sit behind
        // geometry
        glDepthMask(false);
        skyRenderer.render(proj, view, player.position, gameTime, daylight, totalTime);
        glDepthMask(true);

        chunkShader.bind();
        chunkShader.setMat4("uProjection", proj);
        chunkShader.setMat4("uView", view);
        chunkShader.setInt("uAtlas", 0);
        Vector3f fogColor = player.eyeInWater ? new Vector3f(0.04f, 0.14f, 0.55f) : sky;
        float fogStart = player.eyeInWater ? 3f : renderRadius * Chunk.SIZE_X * 0.5f;
        float fogEnd = player.eyeInWater ? 12f : renderRadius * Chunk.SIZE_X * 1.0f;
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
        proj.mul(view, scratchModel);
        frustum.set(scratchModel);
        drawnChunks = 0;
        for (int cx = pcx - renderRadius; cx <= pcx + renderRadius; cx++) {
            for (int cz = pcz - renderRadius; cz <= pcz + renderRadius; cz++) {
                float wx = cx * Chunk.SIZE_X, wz = cz * Chunk.SIZE_Z;
                if (!frustum.testAab(wx, 0, wz, wx + Chunk.SIZE_X, Chunk.SIZE_Y, wz + Chunk.SIZE_Z))
                    continue;
                Mesh mesh = chunkMeshes.get(World.key(cx, cz));
                if (mesh == null)
                    continue;
                chunkShader.setMat4("uModel", scratchModel.translation(wx, 0, wz));
                mesh.render();
                drawnChunks++;
            }
        }
        chunkShader.unbind();

        // --- Transparent (water) pass ---
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
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

        List<Long> waterKeys = new ArrayList<>();
        for (int cx = pcx - renderRadius; cx <= pcx + renderRadius; cx++) {
            for (int cz = pcz - renderRadius; cz <= pcz + renderRadius; cz++) {
                float wx = cx * Chunk.SIZE_X, wz = cz * Chunk.SIZE_Z;
                if (!frustum.testAab(wx, 0, wz, wx + Chunk.SIZE_X, Chunk.SIZE_Y, wz + Chunk.SIZE_Z))
                    continue;
                long key = World.key(cx, cz);
                if (waterMeshes.containsKey(key))
                    waterKeys.add(key);
            }
        }
        waterKeys.sort((ka, kb) -> {
            int cxa = (int) (ka >> 32), cza = (int) (ka & 0xFFFFFFFFL);
            int cxb = (int) (kb >> 32), czb = (int) (kb & 0xFFFFFFFFL);
            float dxa = cxa * Chunk.SIZE_X + Chunk.SIZE_X * 0.5f - player.position.x;
            float dza = cza * Chunk.SIZE_Z + Chunk.SIZE_Z * 0.5f - player.position.z;
            float dxb = cxb * Chunk.SIZE_X + Chunk.SIZE_X * 0.5f - player.position.x;
            float dzb = czb * Chunk.SIZE_Z + Chunk.SIZE_Z * 0.5f - player.position.z;
            float distA = dxa * dxa + dza * dza;
            float distB = dxb * dxb + dzb * dzb;
            return Float.compare(distB, distA); // far first
        });
        for (Long k : waterKeys) {
            int cx = (int) (k >> 32), cz = (int) (k & 0xFFFFFFFFL);
            chunkShader.setMat4("uModel", scratchModel.translation(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z));
            Mesh wm = waterMeshes.get(k);
            if (wm != null)
                wm.render();
        }
        chunkShader.unbind();
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
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

        if (state == State.PLAYING && breakX != NO_BREAK && breakProgress > 0f) {
            int stage = Math.min(9, (int) (breakProgress * 10f));
            breakOverlay.render(proj, view, breakX, breakY, breakZ, stage, atlas);
        }

        Vector3f camRight = player.camera.right();
        Vector3f camUp = new Vector3f(camRight).cross(player.camera.forward()).normalize();
        particles.render(proj, view, camRight, camUp, atlas,
                daylight, 0.04f + 0.18f * daylight, brightness);

        if (state == State.PLAYING || state == State.PAUSED || state == State.CREATIVE_MENU) {
            int ex = (int) Math.floor(player.position.x);
            int ey = (int) Math.floor(player.position.y + 0.6f); // eye level
            int ez = (int) Math.floor(player.position.z);
            float skyFrac = world.getSkyLight(ex, ey, ez) / (float) Chunk.MAX_LIGHT;
            float blockFrac = world.getBlockLightWorld(ex, ey, ez) / (float) Chunk.MAX_LIGHT;
            heldItemRenderer.render(atlas, currentBlock(), window.getAspect(), currentFov,
                    equipProgress, handSwing, walkedDistance, player.eyeInWater, viewBobbing,
                    daylight, brightness, skyFrac, blockFrac);
        }

        drawUi();
    }

    private void drawUi() {
        if (hud == null)
            return;
        int w = window.getWidth(), h = window.getHeight();
        int scale = effectiveGuiScale();
        int vw = w / scale, vh = h / scale;

        // fps sampling
        fpsFrames++;
        double now = GLFW.glfwGetTime();
        if (now - fpsLastSample >= 0.5) {
            fpsCurrent = (int) Math.round(fpsFrames / (now - fpsLastSample));
            fpsFrames = 0;
            fpsLastSample = now;
        }

        // Release the mouse-swallow once the user lets go of LMB.
        if (swallowMouseUntilUp && !input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT))
            swallowMouseUntilUp = false;

        switch (state) {
            case MENU -> {
                boolean clicked = !swallowMouseUntilUp
                        && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                boolean down = !swallowMouseUntilUp
                        && input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                double mx = input.getCursorX() / scale, my = input.getCursorY() / scale;

                if (inWorldSelect) {
                    if (renamingWorldId != null) {
                        String rdn = renamingWorldId;
                        for (com.mineclone.save.SaveManager.WorldInfo wi : worldList)
                            if (wi.id.equals(renamingWorldId)) { rdn = wi.displayName; break; }
                        Hud.MenuAction ra = hud.drawRenameDialog(vw, vh, rdn,
                                renameBuffer.toString(), mx, my, clicked);
                        if (ra == Hud.MenuAction.SAVE)   applyRename();
                        else if (ra == Hud.MenuAction.CANCEL) renamingWorldId = null;
                        break;
                    }

                    if (pendingDeleteId != null) {
                        String dn = pendingDeleteId;
                        for (com.mineclone.save.SaveManager.WorldInfo wi : worldList)
                            if (wi.id.equals(pendingDeleteId)) { dn = wi.displayName; break; }
                        Hud.MenuAction da = hud.drawConfirm(vw, vh,
                                "Delete \"" + dn + "\"? This cannot be undone.",
                                "Delete", mx, my, clicked,
                                Hud.MenuAction.DELETE_WORLD_CONFIRM);
                        if (da == Hud.MenuAction.DELETE_WORLD_CONFIRM) {
                            save.deleteWorld(pendingDeleteId);
                            pendingDeleteId = null;
                            selectedWorldId = null;
                            worldList = save.listWorlds();
                            worldSelectScroll = 0;
                        } else if (da == Hud.MenuAction.CANCEL
                                || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                            pendingDeleteId = null;
                        }
                    } else {
                        int maxScroll = Math.max(0, worldList.size() - 1);
                        worldSelectScroll = Math.max(0, Math.min(worldSelectScroll, maxScroll));
                        Hud.WorldSelectAction wa = hud.drawWorldSelect(
                                vw, vh, mx, my, clicked, worldList, worldSelectScroll, selectedWorldId);
                        if (wa.playId != null) {
                            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                            inWorldSelect = false;
                            selectedWorldId = null;
                            startWorld(wa.playId);
                        } else if (wa.selectId != null) {
                            selectedWorldId = wa.selectId;
                            swallowMouseUntilUp = true;
                        } else if (wa.renameId != null) {
                            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                            renamingWorldId = wa.renameId;
                            renameBuffer.setLength(0);
                            for (com.mineclone.save.SaveManager.WorldInfo wi : worldList)
                                if (wi.id.equals(wa.renameId)) { renameBuffer.append(wi.displayName); break; }
                            input.pollChars();
                            swallowMouseUntilUp = true;
                        } else if (wa.deleteId != null) {
                            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                            pendingDeleteId = wa.deleteId;
                            swallowMouseUntilUp = true;
                        } else if (wa.newWorld) {
                            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                            inWorldSelect = false;
                            selectedWorldId = null;
                            createWorld();
                        } else if (wa.back || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                            inWorldSelect = false;
                            selectedWorldId = null;
                        }
                    }
                    break;
                }

                if (inSettings) {
                    drawActiveSettingsTab(vw, vh, mx, my, down, clicked);
                    if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                        if (settingsTab != SettingsTab.HUB) {
                            settingsTab = SettingsTab.HUB;
                            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                        } else {
                            inSettings = false;
                            settingsTab = SettingsTab.HUB;
                            save.saveOptions(buildOptions());
                            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                        }
                    }
                    break;
                }

                Hud.MenuAction a = hud.drawMainMenu(vw, vh, mx, my, clicked);
                if (a != Hud.MenuAction.NONE)
                    sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                switch (a) {
                    case SINGLEPLAYER -> {
                        worldList = save.listWorlds();
                        worldSelectScroll = 0;
                        selectedWorldId = null;
                        inWorldSelect = true;
                        swallowMouseUntilUp = true;
                    }
                    case SETTINGS -> {
                        inSettings = true;
                        settingsTab = SettingsTab.HUB;
                        swallowMouseUntilUp = true;
                    }
                    case QUIT -> GLFW.glfwSetWindowShouldClose(window.getHandle(), true);
                    default -> {
                    }
                }
            }
            case LOADING -> {
                hud.drawLoading(vw, vh, loadingVisualProgress, loadingTimer);
            }
            case PLAYING -> {
                if (player.eyeInWater && hud != null)
                    hud.drawWaterOverlay(vw, vh);
                crosshair.render(vw, vh);
                hud.drawHotbar(vw, vh, inventory, selectedSlot);
                hud.drawHearts(vw, vh, player.health);
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
                    hud.drawDebug(vw, vh, fpsCurrent, player.position, pcx, pcz,
                            countLoadedChunks(), drawnChunks, tgt, tgtMeta, wireframe, skyL, blkL);
                }
                if (consoleOpen)
                    hud.drawConsole(vw, vh, consoleLine.toString());
            }
            case PAUSED -> {
                hud.drawHotbar(vw, vh, inventory, selectedSlot);
                hud.drawHearts(vw, vh, player.health);
                boolean clicked = !swallowMouseUntilUp
                        && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                boolean down = !swallowMouseUntilUp
                        && input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                double mx = input.getCursorX() / scale, my = input.getCursorY() / scale;
                if (inSettings) {
                    drawActiveSettingsTab(vw, vh, mx, my, down, clicked);
                } else {
                    Hud.MenuAction a = hud.drawPauseMenu(vw, vh, mx, my, clicked);
                    if (a != Hud.MenuAction.NONE)
                        sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                    switch (a) {
                        case RESUME -> {
                            state = State.PLAYING;
                            input.grabCursor(true);
                        }
                        case SAVE -> {
                            saveAll();
                            saveToastTimer = 1.6f;
                            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
                        }
                        case SETTINGS -> {
                            inSettings = true;
                            swallowMouseUntilUp = true;
                        }
                        case MAIN_MENU -> {
                            unloadWorld();
                            saveToastTimer = 1.6f;
                            inSettings = false;
                            state = State.MENU;
                            input.grabCursor(false);
                            swallowMouseUntilUp = true;
                        }
                        case QUIT -> GLFW.glfwSetWindowShouldClose(window.getHandle(), true);
                        default -> {
                        }
                    }
                }
            }
            case CREATIVE_MENU -> {
                hud.drawHotbar(vw, vh, inventory, selectedSlot);
                hud.drawHearts(vw, vh, player.health);
                boolean clicked = input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                boolean rightClicked = input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                double mx = input.getCursorX() / scale, my = input.getCursorY() / scale;
                Hud.InventoryAction action = hud.drawInventory(vw, vh, mx, my, clicked, rightClicked,
                        inventory, selectedSlot, cursorItem);
                if (action.paletteItem != null) {
                    cursorItem = action.paletteItem;
                    equipProgress = 0f;
                    sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                } else if (action.slot >= 0) {
                    BlockType slotItem = inventory[action.slot];
                    inventory[action.slot] = cursorItem == null ? BlockType.AIR : cursorItem;
                    cursorItem = slotItem == null ? BlockType.AIR : slotItem;
                    equipProgress = 0f;
                    sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                } else if (action.clearCursor) {
                    cursorItem = BlockType.AIR;
                    sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                }
            }
            case DEAD -> {
                hud.drawHearts(vw, vh, player.health);
                boolean clicked = !swallowMouseUntilUp
                        && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                double mx = input.getCursorX() / scale, my = input.getCursorY() / scale;
                Hud.MenuAction a = hud.drawDeathScreen(vw, vh, mx, my, clicked);
                if (a == Hud.MenuAction.RESPAWN) {
                    respawnPlayer();
                    state = State.PLAYING;
                    input.grabCursor(true);
                    swallowMouseUntilUp = true;
                }
            }
        }

        if (saveToastTimer > 0f && font != null) {
            String msg = "Saved";
            float mw = font.textWidth(msg);
            float a = Math.min(1f, saveToastTimer / 0.4f);
            ui.begin(vw, vh);
            ui.quad(vw / 2f - mw / 2f - 12f, 32f, mw + 24f, font.getPixelHeight() + 16f,
                    0f, 0f, 0f, 0.55f * a);
            ui.end();
            text.draw(font, msg, vw / 2f - mw / 2f, 50f + font.getPixelHeight() * 0.5f,
                    vw, vh, 0.55f, 1f, 0.55f, a);
        }

        if (commandToastTimer > 0f && font != null && !commandToast.isEmpty()) {
            float mw = font.textWidth(commandToast);
            float a = Math.min(1f, commandToastTimer / 0.35f);
            float y = saveToastTimer > 0f ? 82f : 32f;
            ui.begin(vw, vh);
            ui.quad(vw / 2f - mw / 2f - 12f, y, mw + 24f, font.getPixelHeight() + 16f,
                    0f, 0f, 0f, 0.55f * a);
            ui.end();
            text.draw(font, commandToast, vw / 2f - mw / 2f,
                    y + 18f + font.getPixelHeight() * 0.5f,
                    vw, vh, 0.85f, 0.95f, 1f, a);
        }

        if (commandHelpTimer > 0f && font != null) {
            drawCommandHelp(vw, vh);
        }

        // always show version label
        hud.drawVersionLabel(vw, vh);
    }

    private void drawCommandHelp(int w, int h) {
        String[] lines = {
                "Commands",
                "/help  - show this list",
                "/time [query]",
                "/time set day|sunrise|noon|sunset|night|midnight|0-24",
                "/time add <hours>",
                "/tp <x> <y> <z>",
                "/spawnpoint [x y z]",
                "/fly",
                "/speed <value>",
                "/fill <block> [radius]",
                "/debug"
        };
        float lineH = font.getPixelHeight() + 5f;
        float panelW = 0f;
        for (String line : lines)
            panelW = Math.max(panelW, font.textWidth(line));
        panelW += 32f;
        float panelH = lines.length * lineH + 26f;
        float x = Math.max(24f, w / 2f - panelW / 2f);
        float y = Math.max(80f, h / 2f - panelH / 2f);
        float a = Math.min(1f, commandHelpTimer / 0.35f);

        ui.begin(w, h);
        ui.quad(x, y, panelW, panelH, 0f, 0f, 0f, 0.72f * a);
        ui.quad(x, y, panelW, 2f, 1f, 1f, 1f, 0.20f * a);
        ui.quad(x, y + panelH - 2f, panelW, 2f, 0f, 0f, 0f, 0.55f * a);
        ui.end();

        float textY = y + 22f;
        for (int i = 0; i < lines.length; i++) {
            float r = i == 0 ? 1f : 0.82f;
            float g = i == 0 ? 0.95f : 0.90f;
            float b = i == 0 ? 0.55f : 1f;
            text.drawShadowed(font, lines[i], x + 16f, textY + i * lineH,
                    w, h, r, g, b);
        }
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
        if (loader != null)
            loader.shutdown();
        menuBackground.destroy();
        sound.destroy();
        for (Mesh m : chunkMeshes.values())
            m.destroy();
        chunkMeshes.clear();
        for (Mesh m : waterMeshes.values())
            m.destroy();
        waterMeshes.clear();
        atlas.destroy();
        chunkShader.destroy();
        heldItemRenderer.destroy();
        crosshair.destroy();
        outline.destroy();
        breakOverlay.destroy();
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
