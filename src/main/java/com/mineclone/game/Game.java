package com.mineclone.game;

import com.mineclone.audio.SoundEngine;
import com.mineclone.audio.Sounds;
import com.mineclone.core.AppPaths;
import com.mineclone.core.Input;
import com.mineclone.core.KeyBindings;
import com.mineclone.core.Window;
import com.mineclone.render.*;
import com.mineclone.ui.DeathScreen;
import com.mineclone.ui.LoadingScreen;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.MenuTheme;
import com.mineclone.ui.PauseScreen;
import com.mineclone.ui.Screen;
import com.mineclone.ui.ScreenStack;
import com.mineclone.ui.SettingsModel;
import com.mineclone.ui.TitleScreen;
import com.mineclone.ui.UiInput;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.*;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
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
    /** Раскладка клавиш: один объект на игру, Input читает действия через него. */
    private final KeyBindings keys = new KeyBindings();

    private enum State {
        MENU, LOADING, PLAYING, PAUSED, CREATIVE_MENU, CHEST_MENU, FURNACE_MENU, DEAD
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
    private final TrajectoryRenderer trajectoryRenderer;
    private final RopeRenderer ropeRenderer;
    private final SkyRenderer skyRenderer;
    private final com.mineclone.render.PlayerRenderer playerRenderer;
    private final Shader waterShader;
    private final Shader shadowShader;
    private final Shader shadowMobShader;
    private ShadowMap shadowMap;
    private PostProcess post;
    private final SceneLighting lighting = new SceneLighting();
    private final SkyRenderer.Dome skyDome = new SkyRenderer.Dome();
    /** Палитра кадра игры и отдельная — фона меню: у них разное время суток. */
    private final SkyPalette framePalette = new SkyPalette();
    private final SkyPalette menuPalette = new SkyPalette();
    private final PostProcess.Settings postSettings = new PostProcess.Settings();
    private final FrustumIntersection shadowFrustum = new FrustumIntersection();
    private final Matrix4f scratchLight = new Matrix4f();
    /** 0 = Fast (без теней и пост-эффектов), 1 = Fancy, 2 = Ultra. */
    private int shaderQuality;
    private final ParticleSystem particles = new ParticleSystem();
    private final java.util.Random natureRandom = new java.util.Random();
    private float natureTimer;
    /** Как часто на землю падают брызги дождя, секунды. */
    private float splashTimer;
    /** Погода и ночное небо в точке игрока — пересчитывается каждый кадр. */
    private final Atmosphere atmosphere = new Atmosphere();
    private PrecipitationRenderer precipitation;
    /** Сколько мороза набрал игрок — иней по краям кадра. */
    private final Frost frost = new Frost();
    /** Яд, оглушение, заморозка и плавная адаптация экспозиции. */
    private final AdvancedFeedback advancedFeedback = new AdvancedFeedback();
    /** Крен, кивок и проседание камеры от первого лица. */
    private final CameraMotion cameraMotion = new CameraMotion();
    private float lastCameraYaw;
    /** Дуги по краю экрана в сторону громких звуков. */
    private final SoundIndicators soundCues = new SoundIndicators();
    /** Плавная рамка выделения. */
    private final OutlineAnimator outlineAnim = new OutlineAnimator();
    /** Подсказка у прицела и её прозрачность. */
    private ContextHint.Hint shownHint;
    private float hintAlpha;
    /** Моб под прицелом в досягаемости удара — для подсказки. */
    private boolean aimingAtMob;
    /** Размытый кадр под стеклянными панелями. */
    private Backdrop backdrop;
    /** 3D-обломки разбитых блоков. */
    private final Debris debris = new Debris(0xDEB815L);
    private DebrisRenderer debrisRenderer;
    /** Предметы на земле. */
    private final java.util.List<com.mineclone.world.entity.ItemEntity> items = new java.util.ArrayList<>();
    private ItemRenderer itemRenderer;
    private final java.util.Random itemRandom = new java.util.Random();
    private float itemMergeTimer;
    private final Vector3f itemTarget = new Vector3f();
    /** Больше этого предметов на земле не держим: старые уходят первыми. */
    private static final int MAX_ITEMS = 480;
    /** Куда магнит тянет предмет: чуть ниже середины тела, чтобы лежащий у ног подбирался. */
    private static final float ITEM_TARGET_HEIGHT = 0.7f;
    /** Как часто сливаются соседние стопки на земле. */
    private static final float ITEM_MERGE_INTERVAL = 0.5f;
    /** Скорость броска по Q и подброс вверх, блоки/с. */
    private static final float THROW_SPEED = 5.5f, THROW_LIFT = 1.6f;
    private static final float THROW_CHARGE_TIME = 1.15f;
    private float throwCharge;
    private boolean chargingThrow;
    private boolean throwWholeStack;
    /** Дальше этого звук не даёт дуги — столько же, сколько слышит OpenAL. */
    private static final float SOUND_CUE_RANGE = 24f;
    private final java.util.List<com.mineclone.world.entity.Mob> mobs = new java.util.ArrayList<>();
    /** Только поставленные в этой сессии тяжёлые блоки участвуют в обрушениях. */
    private final java.util.Set<Long> playerStructures = new java.util.HashSet<>();
    private com.mineclone.world.entity.MobSpawner mobSpawner;
    private float mobSpawnTimer = 0f;
    /** Herd and wildlife neighbourhoods are perception data, not 60 Hz physics. */
    private float mobSenseTimer;
    /** Музыка: ситуация из мира → режиссёр → потоковый плеер. */
    private final MusicSense musicSense = new MusicSense();
    private com.mineclone.audio.MusicDirector music;
    private com.mineclone.audio.MusicPlayer musicPlayer;
    private com.mineclone.render.MobRenderer mobRenderer;
    private final SoundEngine sound = new SoundEngine();
    private final Sounds sounds = new Sounds();
    private Font font;
    private Font smallFont;
    private TextRenderer text;
    private UiRenderer ui;
    private Hud hud;

    private State state = State.MENU;
    private boolean showDebug = false;

    private static final float TIME_SCALE = 0.005f; // ~21 min real = full day/night cycle (~10.5 min day, ~10.5 min
                                                    // night)
    private float gameTime = (float) (Math.PI / 6.0); // start at ~morning: sun 30° above eastern horizon
    private float daylight = 1.0f;

    private int fpsFrames;
    private int fpsCurrent;
    private double fpsLastSample = 0;
    private int drawnChunks;
    /**
     * Разбивка времени кадра для F3. Средний FPS про рывки не говорит
     * ничего — нужно видеть худший кадр и то, в какой фазе он застрял.
     */
    private final FrameProfiler profiler = new FrameProfiler();

    private float stepDistance = 0f;
    /** Левая или правая нога игрока: следы идут в две дорожки, а не в колею. */
    private boolean stepLeft;
    private DecalRenderer decals;

    /** Открытый сундук: его слоты и координаты блока. */
    private com.mineclone.world.ItemStack[] openChest;
    private int chestX, chestY, chestZ;

    /** Открытая печь и её координаты. */
    private com.mineclone.world.Furnace openFurnace;
    private int furnaceX, furnaceY, furnaceZ;

    /** Темп тиков печей. Реже кадра: переплавка меряется секундами. */
    private static final float FURNACE_TICK = 0.25f;
    /** В скольких чанках вокруг игрока печи вообще работают. */
    private static final int FURNACE_RADIUS = 4;
    private float furnaceTimer;

    /** Расписание фоновой атмосферы: пещера, дождь, гром, вода. */
    private com.mineclone.audio.AmbientSound ambient;
    /** В каком радиусе ищется точка, откуда «донёсся» звук пещеры. */
    private static final int CAVE_SOUND_RADIUS = 14;
    /** Как часто перезамеряется замкнутость для эха. */
    private static final float REVERB_INTERVAL = 0.5f;
    private float reverbTimer;
    private float enclosure;

    /** Тайл отпечатка в атласе. */
    private static final int FOOTPRINT_TILE = 75;
    /** Сколько секунд держится след игрока. */
    private static final float FOOTPRINT_LIFE = 26f;
    /** Насколько нога отстоит от оси движения. */
    private static final float FOOTPRINT_SPREAD = 0.16f;
    private float walkedDistance = 0f; // monotonic; drives view bob (never resets)
    private float walkAmount = 0f;     // 0..1, гаснет на месте — размах шага модели

    // ---- обратная связь по урону ----
    /** Сколько держится красная виньетка после удара, секунды. */
    private static final float DAMAGE_FLASH_TIME = 0.55f;
    /** Сколько трясёт камеру после удара, секунды. */
    private static final float DAMAGE_SHAKE_TIME = 0.28f;
    /** Пауза перед тем, как «часы» потери начнут оседать. */
    private static final float HEALTH_GHOST_DELAY = 0.45f;
    private float damageFlash = 0f;
    private float damageShake = 0f;
    private float lastHealth = Player.MAX_HEALTH;
    private float healthGhost = Player.MAX_HEALTH;
    private float healthGhostDelay = 0f;
    /** Сколько прошло с переключения слота — на этом едет пружина хотбара. */
    private float slotAnim = 1f;
    private final Vector3f lastPos = new Vector3f();
    private float torchParticleTimer = 0f;
    /** Как часто идёт пар от дыхания на морозе, секунды. */
    private static final float BREATH_MIN = 3f, BREATH_MAX = 5f;
    private float breathTimer = BREATH_MIN;
    /** Урон в секунду от стояния в огне. */
    private static final float FIRE_DAMAGE_PER_SECOND = 2f;

    // ---- Слышимость: радиус в блоках, на котором моб замечает шум ----
    /** Ломающийся блок слышно дальше всего. */
    private static final float NOISE_BREAK = 16f;
    private static final float NOISE_PLACE = 10f;
    private static final float NOISE_SPRINT = 11f;
    private static final float NOISE_WALK = 4f;
    private static final float NOISE_LAND = 14f;
    private static final float WATER_TICK_INTERVAL = 0.18f; // ~5.5 Hz — one cell of spread per tick
    private float waterTickTimer = WATER_TICK_INTERVAL;
    private com.mineclone.world.BlockTicker blockTicker;
    private float blockTickTimer = com.mineclone.world.BlockTicker.TICK_INTERVAL;
    private float totalTime = 0f;
    private boolean wasInWater = false;
    /** Nearby-water probe is throttled; the actual sound source is continuous. */
    private float waterFlowProbeTimer = 0f;
    private Vector3f waterFlowSoundPosition;
    /** Expensive nearby-emitter search is cached between lighting probes. */
    private float voxelBounceProbeTimer;
    private final List<Long> visibleWaterKeys = new ArrayList<>(128);
    private boolean wireframe = false;
    /** Откуда смотрим: из глаз, из-за спины или в лицо. Переключается F5. */
    private enum ViewMode { FIRST, THIRD_BACK, THIRD_FRONT }
    private ViewMode viewMode = ViewMode.FIRST;
    /** Насколько далеко камера отходит от игрока в третьем лице. */
    private static final float THIRD_PERSON_DISTANCE = 3.6f;
    /** F1 убирает весь интерфейс — режим для скриншотов. */
    private boolean hideHud = false;

    // --- фоторежим ---
    /**
     * Свободная камера. Игрок при этом стоит на месте: фоторежим — это взгляд
     * со стороны, а не полёт. Иначе он подменял бы креатив-полёт и обходил
     * выживание.
     */
    private boolean photoMode = false;
    private final Vector3f photoPos = new Vector3f();
    private float photoYaw, photoPitch;
    /** Дистанция фокуса, блоки; колесо мыши двигает её. */
    private float photoFocus = 8f;
    /** Скорость свободной камеры, блоки в секунду. */
    private static final float PHOTO_SPEED = 9f;
    /** Ускорение по Ctrl и замедление по Alt — для дальних и точных кадров. */
    private static final float PHOTO_FAST = 4f, PHOTO_SLOW = 0.22f;
    /** Радиус размытия вне фокуса, пиксели. */
    private static final float PHOTO_DOF = 7f;
    /** Полуширина резкой зоны, блоки. */
    private static final float PHOTO_RANGE = 2.2f;
    /** Пределы дистанции фокуса. */
    private static final float PHOTO_FOCUS_MIN = 0.8f, PHOTO_FOCUS_MAX = 140f;
    /** Верх экрана по центру занят компасом — тосты начинаются под ним. */
    private static final float TOAST_Y = 78f;
    private boolean instantBreak = false;
    private boolean consoleOpen = false;
    private final StringBuilder consoleLine = new StringBuilder();
    private static final int CHUNK_UNLOAD_MARGIN = 3;

    private final Map<Long, Mesh> chunkMeshes = new HashMap<>();
    private final Map<Long, Mesh> waterMeshes = new HashMap<>();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f scratchModel = new Matrix4f();
    private int selectedSlot = 0;
    private com.mineclone.world.Inventory inventory = new com.mineclone.world.Inventory();
    private com.mineclone.world.GameMode gameMode = com.mineclone.world.GameMode.SURVIVAL;
    private com.mineclone.world.ItemStack cursorItem = null;
    private final com.mineclone.save.SaveManager save = new com.mineclone.save.SaveManager();
    private String worldId;
    private String worldDisplayName = "";
    /** Экраны меню: титул, миры, настройки, загрузка, пауза, смерть. */
    private final ScreenStack menus = new ScreenStack();
    private MenuTheme menuTheme;
    /** Настройки для экранов меню: изменение применяется сразу, запись — при закрытии. */
    private final SettingsModel settingsModel;
    private LoadingScreen loadingScreen;
    /** Доли ближнего радиуса: чанки есть, свет разлит, меши загружены. */
    private final float[] loadFractions = new float[3];
    /** Часы интерфейса: идут в любом состоянии, в отличие от мира. */
    private float uiClock;
    /** Превью мира для списка миров: снимается с первого кадра после сохранения. */
    private final Thumbnail thumbnail = new Thumbnail();
    private boolean iconRequested;
    /** В кадре, где меню открылось, его ввод пустой — см. {@link #openMenu}. */
    private boolean menuInputBlocked;
    /** Автопилот меню ({@code -Dmineclone.autopilot=<папка>}) или null в обычной игре. */
    private final Autopilot autopilot;
    private java.nio.file.Path shotDir;
    private String pendingShot;
    private int exitCode;
    private static final float AUTOSAVE_INTERVAL = 120f; // seconds
    private static final int RESPAWN_RADIUS = 10;
    private float autosaveTimer = AUTOSAVE_INTERVAL;
    private final Vector3f worldSpawn = new Vector3f(8.5f, 80.0f, 8.5f);
    private final Random respawnRandom = new Random();
    private final MenuBackground menuBackground;
    private String commandToast = "";
    private float commandToastTimer = 0f;
    private float commandHelpTimer = 0f;
    private float loadingTimer = 0f;
    /**
     * Eat mouseDown/mouseClicked until the user releases LMB. Prevents the
     * click that opened a panel from immediately grabbing a slider in it.
     */
    private boolean swallowMouseUntilUp = false;
    private float handSwing = 0f;
    private float equipProgress = 1f;
    private BlockType lastHeldBlock = BlockType.AIR;
    /** 0..1 — игрок разглядывает предмет в руке (зажата R). */
    private float inspect = 0f;
    private float inspectSpin = 0f;
    /** За сколько секунд предмет выезжает к глазам и обратно. */
    private static final float INSPECT_TIME = 0.22f;
    /** 0..1 — насколько размыт мир за открытым окном инвентаря или паузы. */
    private float menuBlur = 0f;
    /** Радиус размытия мира за окном, пиксели. */
    private static final float MENU_DOF = 5.5f;
    /** Радиус размытия фона при осмотре предмета, пиксели. */
    private static final float INSPECT_DOF = 4.5f;

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
        this.keys.copyFrom(opts.keys);
        this.input.setBindings(this.keys);
        this.settingsModel = new SettingsModel(this.keys);
        this.settingsModel.setListener(new SettingsModel.Listener() {
            @Override
            public void changed(SettingsModel m) {
                applySettings(m);
            }

            @Override
            public void committed(SettingsModel m) {
                save.saveOptions(buildOptions());
            }
        });
        String pilot = System.getProperty("mineclone.autopilot");
        if (pilot != null && !pilot.isBlank()) {
            // Прогон идёт на экране игрока: курсор не захватывается.
            shotDir = java.nio.file.Path.of(pilot);
            autopilot = new Autopilot(new PilotDriver());
            input.setGrabAllowed(false);
        } else {
            autopilot = null;
        }
        window.setVSync(this.vsync);
        window.setFullscreen(this.fullscreen);
        this.menuBackground = new MenuBackground(save);
        this.atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, regenAtlas);
        this.chunkShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
        this.heldItemRenderer = new HeldItemRenderer();
        this.crosshair = new Crosshair();
        this.outline = new BlockOutline();
        this.trajectoryRenderer = new TrajectoryRenderer();
        this.ropeRenderer = new RopeRenderer();
        this.breakOverlay = new BlockBreakOverlay();
        this.mobRenderer = new com.mineclone.render.MobRenderer();
        this.skyRenderer = new SkyRenderer();
        this.playerRenderer = new com.mineclone.render.PlayerRenderer();
        this.waterShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.WATER_FRAGMENT);
        this.shadowShader = new Shader(Shaders.SHADOW_VERTEX, Shaders.SHADOW_FRAGMENT);
        this.shadowMobShader = new Shader(Shaders.SHADOW_MOB_VERTEX, Shaders.SHADOW_FRAGMENT);
        this.shaderQuality = opts.shaderQuality;
        this.post = new PostProcess(window.getWidth(), window.getHeight());
        this.shadowMap = new ShadowMap(shadowMapSize(this.shaderQuality));
    }

    /**
      * Разрешение карты теней по уровню качества. Выше 2048 смысла нет:
      * на ближнем каскаде это уже 0.04 блока на тексель, а стоимость филла
      * растёт квадратично (замер: 3072 дороже 2048 на 0.6 мс в 1080p).
      */
    private static int shadowMapSize(int quality) {
        return quality >= 2 ? 2048 : 1024;
    }

    /** Геометрия изменилась — ленивые каскады обязаны переснять её. */
    private void invalidateShadows() {
        if (shadowMap != null)
            shadowMap.invalidate();
    }

    /** Пересоздаёт карту теней после смены качества в настройках. */
    private void rebuildShadowMap() {
        if (shadowMap != null)
            shadowMap.destroy();
        shadowMap = new ShadowMap(shadowMapSize(shaderQuality));
    }

    private BlockType currentBlock() {
        com.mineclone.world.ItemStack s = inventory.get(selectedSlot);
        return s == null || s.isTool() ? BlockType.AIR : s.type;
    }

    private com.mineclone.save.Options buildOptions() {
        return new com.mineclone.save.Options(
                renderRadius, fovDegrees, brightness, volume,
                maxFps, vsync, fullscreen, viewBobbing,
                mouseSensitivity, invertMouseY, musicVolume, effectsVolume, guiScale,
                shaderQuality, keys);
    }

    private int effectiveGuiScale() {
        if (guiScale >= 1 && guiScale <= 3) return guiScale;
        // Auto: 1× for ≤1080p, 2× for 1440p, 3× for 4K, capped at 4
        int h = window.getHeight();
        return Math.max(1, Math.min(4, h / 720));
    }

    /** Поля игры → модель настроек: F11 меняет полный экран в обход меню. */
    private void syncSettingsModel() {
        SettingsModel m = settingsModel;
        m.renderRadius = renderRadius;
        m.fov = fovDegrees;
        m.brightness = brightness;
        m.maxFps = maxFps;
        m.guiScale = guiScale;
        m.shaderQuality = shaderQuality;
        m.vsync = vsync;
        m.fullscreen = fullscreen;
        m.viewBobbing = viewBobbing;
        m.sensitivity = mouseSensitivity;
        m.invertY = invertMouseY;
        m.masterVolume = volume;
        m.musicVolume = musicVolume;
        m.effectsVolume = effectsVolume;
    }

    /** Модель настроек → игра; побочные эффекты — только у сменившихся значений. */
    private void applySettings(SettingsModel m) {
        if (renderRadius != m.renderRadius) {
            renderRadius = m.renderRadius;
            // Стриминг обходит радиус только при сдвиге игрока: без сброса новая
            // дальность ждала бы, пока игрок перейдёт в соседний чанк.
            lastStreamCX = Integer.MIN_VALUE;
        }
        fovDegrees = m.fov;
        brightness = m.brightness;
        maxFps = m.maxFps;
        guiScale = m.guiScale;
        if (shaderQuality != m.shaderQuality) {
            int prev = shaderQuality;
            shaderQuality = m.shaderQuality;
            if (shadowMapSize(shaderQuality) != shadowMapSize(prev))
                rebuildShadowMap();
        }
        if (vsync != m.vsync) {
            vsync = m.vsync;
            window.setVSync(vsync);
        }
        if (fullscreen != m.fullscreen) {
            fullscreen = m.fullscreen;
            window.setFullscreen(fullscreen);
        }
        viewBobbing = m.viewBobbing;
        mouseSensitivity = m.sensitivity;
        invertMouseY = m.invertY;
        if (volume != m.masterVolume || musicVolume != m.musicVolume) {
            if (volume != m.masterVolume)
                sound.setMasterVolume(m.masterVolume);
            volume = m.masterVolume;
            musicVolume = m.musicVolume;
            if (musicPlayer != null)
                musicPlayer.setVolume(volume, musicVolume);
        }
        if (effectsVolume != m.effectsVolume) {
            effectsVolume = m.effectsVolume;
            sound.setEffectsVolume(effectsVolume);
        }
    }

    /** Открыть меню с корня: модель настроек и буфер набранных символов — свежие. */
    private void openMenu(Screen root) {
        syncSettingsModel();
        input.pollChars();
        menus.reset(root);
        // Клавиша, которая открыла меню, в этом же кадре дойдёт до него ещё раз:
        // пауза по Esc ставится в обновлении, а стек читает Esc в отрисовке — и
        // без этого флага тут же возвращал бы в игру.
        menuInputBlocked = true;
    }

    /** Звуки интерфейса: тихий щелчок наведения и полноценный — нажатия. */
    private MenuTheme.Sounds menuSounds() {
        java.util.List<String> hover = sounds.uiHover();
        return new MenuTheme.Sounds() {
            @Override
            public void hover() {
                sound.playOneOf(hover, 0.14f, 1.25f + 0.1f * (float) Math.random());
            }

            @Override
            public void click() {
                sound.playOneOf(sounds.uiClick(), 0.55f, 1.0f);
            }
        };
    }

    /** Flush level.dat + every loaded chunk whose blocks changed since gen. */
    private void saveAll() {
        if (world == null)
            return;
        com.mineclone.world.ItemStack[] invSnapshot =
                new com.mineclone.world.ItemStack[com.mineclone.world.Inventory.SIZE];
        for (int i = 0; i < invSnapshot.length; i++) {
            com.mineclone.world.ItemStack s = inventory.get(i);
            invSnapshot[i] = s == null ? null : s.copy();
        }
        com.mineclone.save.LevelData d = new com.mineclone.save.LevelData(
                worldDisplayName,
                world.seed,
                player.position.x, player.position.y, player.position.z,
                worldSpawn.x, worldSpawn.y, worldSpawn.z,
                player.camera.yaw, player.camera.pitch,
                gameTime, selectedSlot, invSnapshot, gameMode, System.currentTimeMillis(),
                player.health, player.hunger);
        save.saveLevel(worldId, d);
        // Превью снимет ближайший кадр мира: сейчас идёт обновление, а не отрисовка.
        iconRequested = true;
        for (com.mineclone.world.Chunk c : world.getLoadedChunks()) {
            saveChunkIfModified(c);
        }
    }

    private void saveChunkIfModified(Chunk c) {
        java.util.List<com.mineclone.world.DroppedItem> dropped = itemsInChunk(c);
        // Предметы на земле пишутся вместе с чанком: был хоть один в прошлой
        // записи или есть сейчас — чанк записывается, даже если блоки те же.
        if (!c.modified && dropped.isEmpty() && c.savedItems == 0)
            return;
        save.saveChunkAsync(worldId,
                new com.mineclone.save.ChunkSnapshot(c.cx, c.cz,
                        c.copyBlocks(), c.copyMeta(), c.copyChests(), c.copyFurnaces(), dropped));
        c.savedItems = dropped.size();
        c.modified = false;
    }

    /** Предметы, лежащие в чанке, — копии для фоновой записи. */
    private java.util.List<com.mineclone.world.DroppedItem> itemsInChunk(Chunk c) {
        java.util.List<com.mineclone.world.DroppedItem> out = c.copyPendingItems();
        for (com.mineclone.world.entity.ItemEntity e : items) {
            if (e.stack.count <= 0)
                continue;
            if (Math.floorDiv((int) Math.floor(e.position.x), Chunk.SIZE_X) != c.cx
                    || Math.floorDiv((int) Math.floor(e.position.z), Chunk.SIZE_Z) != c.cz)
                continue;
            out.add(new com.mineclone.world.DroppedItem(e.stack.copy(), e.position.x, e.position.y,
                    e.position.z, e.age));
        }
        return out;
    }

    public void run() {
        sound.init();
        sound.setMasterVolume(volume);
        sound.setEffectsVolume(effectsVolume);
        musicPlayer = new com.mineclone.audio.MusicPlayer(sound.hasReverb());
        musicPlayer.setVolume(volume, musicVolume);
        music = new com.mineclone.audio.MusicDirector(
                com.mineclone.audio.MusicLibrary.scan(AppPaths.file("assets/music")), new java.util.Random());
        try {
            font = new Font(AppPaths.path("assets/minecraft.ttf"), 22f);
            smallFont = new Font(AppPaths.path("assets/minecraft.ttf"), 14f);
        } catch (java.io.IOException e) {
            // The font is a required bundled asset; without it there is no HUD and
            // no menu, so the window would just render blank. Fail loudly and
            // immediately instead of limping along in a half-initialized state.
            throw new IllegalStateException(
                    "Failed to load required font assets/minecraft.ttf: " + e.getMessage(), e);
        }
        text = new TextRenderer();
        ui = new UiRenderer();
        decals = new DecalRenderer();
        precipitation = new PrecipitationRenderer();
        backdrop = new Backdrop();
        debrisRenderer = new DebrisRenderer();
        itemRenderer = new ItemRenderer();
        ambient = new com.mineclone.audio.AmbientSound(new java.util.Random());
        hud = new Hud(font, smallFont, text, ui, atlas);
        menuTheme = new MenuTheme(ui, text, font, smallFont, atlas);
        menuTheme.setSounds(menuSounds());
        openMenu(new TitleScreen(save, settingsModel));

        // Start in menu with the cursor free.
        input.grabCursor(false);

        double lastTime = GLFW.glfwGetTime();
        while (!window.shouldClose()) {
            double frameStart = GLFW.glfwGetTime();
            float dt = (float) Math.min(0.05, frameStart - lastTime);
            lastTime = frameStart;

            profiler.beginFrame();
            profiler.begin(FrameProfiler.Phase.UPDATE, frameStart);
            uiClock += dt;

            input.update();
            if (autopilot != null)
                autopilot.update(dt);
            if (input.keyPressed(GLFW.GLFW_KEY_F5) && state != State.MENU) {
                viewMode = switch (viewMode) {
                    case FIRST -> ViewMode.THIRD_BACK;
                    case THIRD_BACK -> ViewMode.THIRD_FRONT;
                    case THIRD_FRONT -> ViewMode.FIRST;
                };
            }
            if (input.keyPressed(GLFW.GLFW_KEY_F6) && state == State.PLAYING)
                togglePhotoMode();
            if (input.keyPressed(GLFW.GLFW_KEY_F1) && state != State.MENU)
                hideHud = !hideHud;
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
                case CHEST_MENU -> updateChestMenu(dt);
                case FURNACE_MENU -> updateFurnaceMenu(dt);
                case DEAD -> updateDead(dt);
            }
            updateMusic(dt);

            sound.updateListener(player.camera.position, player.camera.forward());
            this.lastDt = dt;
            render();
            // Свёрнутое окно отдаёт кадр 0×0 — снимок ждёт, пока его развернут.
            if (pendingShot != null && window.getWidth() > 0 && window.getHeight() > 0) {
                captureScreenshot(pendingShot);
                pendingShot = null;
            }
            sound.tick();
            profiler.end(GLFW.glfwGetTime());
            window.update();
            profiler.endFrame(GLFW.glfwGetTime(), GLFW.glfwGetTime() - frameStart);

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
        updateCommandToast(dt);
    }

    private void updateCommandToast(float dt) {
        if (commandToastTimer > 0f)
            commandToastTimer -= dt;
        if (commandHelpTimer > 0f)
            commandHelpTimer -= dt;
    }

    private void beginLoadingToPlay() {
        loadingTimer = 0f;
        daylight = computeDaylight();
        player.camera.position.set(player.position.x, player.position.y + Player.EYE_HEIGHT, player.position.z);
        state = State.LOADING;
        input.grabCursor(false);
        loadingScreen = new LoadingScreen(worldDisplayName);
        openMenu(loadingScreen);
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
        slotAnim = 0f;
        gameMode = com.mineclone.world.GameMode.SURVIVAL;
        inventory = new com.mineclone.world.Inventory();

        if (lvl != null) {
            worldDisplayName = lvl.name.isEmpty() ? "World" : lvl.name;
            worldSpawn.set((float) lvl.spawnX, (float) lvl.spawnY, (float) lvl.spawnZ);
            player.position.set((float) lvl.px, (float) lvl.py, (float) lvl.pz);
            player.camera.yaw = lvl.yaw;
            player.camera.pitch = lvl.pitch;
            gameTime = lvl.timeOfDay;
            selectedSlot = Math.floorMod(lvl.selectedSlot, 9);
            gameMode = lvl.gameMode;
            inventory = new com.mineclone.world.Inventory();
            for (int i = 0; i < com.mineclone.world.Inventory.SIZE && i < lvl.inventory.length; i++)
                inventory.set(i, lvl.inventory[i]);
        } else {
            worldDisplayName = "World";
        }

        player.respawn(player.position.x, player.position.y, player.position.z);
        player.health = lvl != null ? lvl.health : Player.MAX_HEALTH;
        player.hunger = lvl != null ? lvl.hunger : Player.MAX_HUNGER;
        player.velocity.set(0, 0, 0);
        player.lastFallDistance = 0f;
        lastHeldBlock = currentBlock();
        cursorItem = null;
        player.flying = false;
        player.flySpeed = Player.FLY_SPEED;

        mobs.clear();
        playerStructures.clear();
        ropeRenderer.clear();
        items.clear();
        damageFlash = 0f;
        damageShake = 0f;
        lastHealth = player.health;
        healthGhost = player.health;
        healthGhostDelay = 0f;
        mobSpawner = new com.mineclone.world.entity.MobSpawner(world.seed ^ 0x51E7B0BL);
        blockTicker = new com.mineclone.world.BlockTicker(world.seed);
        atmosphere.snap();
        musicSense.reset();
        mobSpawnTimer = 0f;

        WaterSimulator.reset();
        beginLoadingToPlay();
    }

    /** Мир по настройкам экрана создания: level.dat с точкой появления — и сразу загрузка. */
    private void createWorld(WorldSettings ws) {
        String id = save.uniqueWorldId(com.mineclone.save.SaveFormat.newWorldId());
        Vector3f spawn = findDefaultSpawn(ws.seed);
        com.mineclone.save.LevelData fresh = new com.mineclone.save.LevelData(
                ws.name, ws.seed,
                spawn.x, spawn.y, spawn.z,
                spawn.x, spawn.y, spawn.z,
                0f, 0f, (float) (Math.PI / 6.0), 0,
                com.mineclone.save.LevelData.emptyInventory(), ws.mode, System.currentTimeMillis());
        save.saveLevel(id, fresh);
        startWorld(id);
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
        mobs.clear();
        playerStructures.clear();
        ropeRenderer.clear();
        mobSpawner = null;
        debris.clear();
        sound.stopAllLoops();
        waterFlowSoundPosition = null;
        waterFlowProbeTimer = 0f;
        voxelBounceProbeTimer = 0f;
        mobSenseTimer = 0f;
        soundCues.clear();
        items.clear();   // уже записаны в чанки через saveAll выше
        resetBreakState();
        world = null;
        mesher = null;
        loader = null;
        iconRequested = false;
        // Full GC while we're at the menu: collects the dropped world (tens of MB
        // of chunk arrays) immediately and lets G1 uncommit heap back to the OS,
        // instead of holding it until the next allocation spike.
        System.gc();
    }

    private void updateLoading(float dt) {
        loadingTimer += dt;
        updateCommandToast(dt);
        // Экран загрузки стоит над живым фоном меню, а не над полусобранным миром.
        menuBackground.update(dt);
        // На этом экране мир не рисуется, и кадру больше нечем заняться —
        // бюджеты подняты в разы. Это и делает загрузку быстрее, и позволяет
        // ждать большего радиуса, не удлиняя ожидание.
        ensureChunksLoaded(LOADING_UPLOAD_BUDGET_MS, LOADING_LIGHT_FLOODS_PER_FRAME);
        updateDirtyMeshes();
        computeLoadingProgress();
        if (loadingScreen != null)
            loadingScreen.update(loadStage, loadFractions[0], loadFractions[1], loadFractions[2]);
        if (worldReadyForPlay() && loadingTimer >= 0.45f) {
            state = State.PLAYING;
            input.grabCursor(true);
            menus.clear();
            loadingScreen = null;
        }
    }

    private float computeDaylight() {
        return Math.max(0f, (float) Math.sin(gameTime));
    }

    private void updatePlaying(float dt) {
        player.statusSpeedMultiplier = advancedFeedback.movementMultiplier();
        if (photoMode) {
            updatePhotoCamera(dt);
        }
        gameTime += dt * TIME_SCALE;
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

        if (input.pressed(KeyBindings.Action.INVENTORY)) {
            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
            state = State.CREATIVE_MENU;
            input.grabCursor(false);
            return;
        }
        if (input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
            pauseGame();
            return;
        }
        if (input.keyPressed(GLFW.GLFW_KEY_F3))
            showDebug = !showDebug;
        if (input.keyPressed(GLFW.GLFW_KEY_F4))
            wireframe = !wireframe;

        // Open console on T (game key — only when chat is closed).
        if (input.pressed(KeyBindings.Action.CONSOLE)) {
            consoleOpen = true;
            input.grabCursor(false);
            consoleLine.setLength(0);
            input.pollChars(); // discard 't'
            player.update(dt, world, input, false);
            updateActiveWorld(dt);
            return;
        }

        handleHotbar();
        updateChargedThrow(dt);
        updateHeldItem(dt);
        if (gameMode == com.mineclone.world.GameMode.SURVIVAL && player.flying)
            player.flying = false;
        // В фоторежиме игрок заморожен: управление уходит свободной камере,
        // и пропускать его сюда значило бы двигать заодно и его.
        player.update(dt, world, input, !photoMode, mouseSensitivity, invertMouseY);
        if (player.justJumped) {
            int jbx = (int) Math.floor(player.position.x);
            int jby = (int) Math.floor(player.position.y - 0.1f);
            int jbz = (int) Math.floor(player.position.z);
            BlockType jumpUnder = world.getBlock(jbx, jby, jbz);
            java.util.List<String> jumpSnd = sounds.step(jumpUnder);
            if (!jumpSnd.isEmpty())
                sound.playOneOfAt(jumpSnd, playerSoundPosition(), 0.35f, 1.05f + 0.1f * (float) Math.random());
            float hs = (float) Math.hypot(player.velocity.x, player.velocity.z);
            kickSnow(player.position.x, player.position.y, player.position.z,
                    hs > 0.1f ? player.velocity.x / hs : 0f, hs > 0.1f ? player.velocity.z / hs : 0f, true);
        }
        float landingDistance = player.lastFallDistance;
        updateCameraMotion(dt, landingDistance);
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
            // Покров снега не твёрдый и лежит в клетке ног: пыль при посадке
            // обязана быть снежной, а не земляной из-под него.
            BlockType cover = world.getBlock(bx, (int) Math.floor(player.position.y + 0.02f), bz);
            int tile = cover == BlockType.SNOW_LAYER ? BlockType.SNOW_LAYER.sideTile
                    : (ground != null && ground.solid) ? ground.sideTile : BlockType.DIRT.sideTile;
            kickSnow(player.position.x, player.position.y, player.position.z, 0f, 0f, true);
            float skyF = world.getSkyLight(bx, by + 1, bz) / (float) Chunk.MAX_LIGHT;
            float blkF = world.getBlockLightWorld(bx, by + 1, bz) / (float) Chunk.MAX_LIGHT;
            int count = (int) Math.min(22, 5 + landingDistance * 1.4f);
            emitNoise(player.position.x, player.position.y, player.position.z, NOISE_LAND);
            particles.emitLandingPuff(player.position.x, player.position.y, player.position.z,
                    tile, skyF, blkF, count);
        }
        player.lastFallDistance = 0f;
        if (player.isDead()) {
            sound.playOneOf(sounds.playerDeath(), 0.9f, 0.95f + 0.1f * (float) Math.random());
            state = State.DEAD;
            input.grabCursor(false);
            openMenu(new DeathScreen());
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
        updateFootsteps();
        // Стриминга здесь нет намеренно: его делает updateActiveWorld ниже,
        // а два вызова за кадр удваивали бы бюджет загрузки мешей на GPU.
        handleInteraction(dt);
        updateActiveWorld(dt);
        updateMobs(dt);
    }

    private void updateActiveWorld(float dt) {
        profiler.begin(FrameProfiler.Phase.STREAM, GLFW.glfwGetTime());
        ensureChunksLoaded();
        profiler.begin(FrameProfiler.Phase.UPDATE, GLFW.glfwGetTime());
        // Погода идёт по игровому времени, а не по сессии: она сохраняется
        // вместе с часами мира и не сбрасывается в ясно при каждом заходе.
        atmosphere.update(dt, world, gameTime, gameTime / TIME_SCALE, player.position);
        splashTimer -= dt;
        if (splashTimer <= 0f && state == State.PLAYING) {
            splashTimer = 0.05f;
            emitRainSplashes();
        }
        natureTimer -= dt;
        if (natureTimer <= 0f && state == State.PLAYING) {
            natureTimer = 0.18f;
            emitNatureParticles();
        }
        torchParticleTimer -= dt;
        if (torchParticleTimer <= 0f) {
            torchParticleTimer = 0.07f;
            emitTorchParticles();
        }
        particles.setWind(atmosphere.windX, atmosphere.windZ);
        particles.update(dt);
        debris.update(world, dt);
        updateItems(dt);
        if (decals != null)
            decals.update(dt);
        tickFurnaces(dt);
        updateWaterFlowSound(dt);
        updateAmbient(dt);
        totalTime += dt;
        applyFireDamage(dt);
        updateDamageFeedback(dt);
        updateBreath(dt);
        updateFrost(dt);
        advancedFeedback.update(dt,
                Math.max(0.03f, daylight * (1f - atmosphere.cloudiness * 0.55f)));
        if (advancedFeedback.active(AdvancedFeedback.Effect.POISON))
            player.takeDamage(dt * 0.35f);
        soundCues.update(dt);

        blockTickTimer -= dt;
        if (blockTickTimer <= 0f) {
            blockTickTimer = com.mineclone.world.BlockTicker.TICK_INTERVAL;
            // Тикеру — осадки фронта, а не местные: игрок может стоять в
            // пустыне, а снег обязан ложиться на соседнюю тундру.
            if (blockTicker != null)
                blockTicker.tick(world, player.position, atmosphere.global.precipitation());
        }

        waterTickTimer -= dt;
        if (waterTickTimer <= 0f) {
            waterTickTimer = WATER_TICK_INTERVAL;
            WaterSimulator.tick(world);
        }
        // После тиков воды и блоков: они и пачкают чанки, ради которых
        // ставятся заявки на перестройку.
        profiler.begin(FrameProfiler.Phase.STREAM, GLFW.glfwGetTime());
        updateDirtyMeshes();
        profiler.begin(FrameProfiler.Phase.UPDATE, GLFW.glfwGetTime());
    }

    /**
     * Брызги дождя на земле вокруг игрока.
     *
     * Сами капли летят на видеокарте ({@link PrecipitationRenderer}) и земли не
     * касаются — без брызг ливень выглядит как занавес перед камерой, а не
     * как погода над миром. Точка берётся с той же карты крыш: под навесом
     * брызг нет, как нет и дождя.
     */
    private void emitRainSplashes() {
        float rain = atmosphere.rain();
        if (rain < 0.15f || precipitation == null || !precipitation.field().isValid())
            return;
        int tries = 2 + (int) (rain * 7f);
        for (int i = 0; i < tries; i++) {
            int x = (int) Math.floor(player.position.x) + natureRandom.nextInt(17) - 8;
            int z = (int) Math.floor(player.position.z) + natureRandom.nextInt(17) - 8;
            float top = precipitation.field().heightAt(x, z);
            if (top == Float.MAX_VALUE || Math.abs(top - player.position.y) > 10f)
                continue;
            BlockType surface = world.getBlock(x, (int) top - 1, z);
            boolean water = surface == BlockType.WATER || surface == BlockType.WATER_FLOW;
            float sky = world.getSkyLight(x, (int) top, z) / (float) Chunk.MAX_LIGHT;
            particles.emitRainSplash(x + natureRandom.nextFloat(), top + 0.02f,
                    z + natureRandom.nextFloat(), water, sky);
        }
    }

    /**
     * Пар от дыхания на морозе — у игрока и у мобов рядом.
     *
     * Самая дешёвая деталь, которая сообщает «здесь холодно»: текстура снега
     * этого не говорит, её видно и на картинке без холода.
     */
    private void updateBreath(float dt) {
        breathTimer -= dt;
        if (breathTimer > 0f)
            return;
        breathTimer = BREATH_MIN + natureRandom.nextFloat() * (BREATH_MAX - BREATH_MIN);
        if (world == null)
            return;
        Vector3f fwd = player.camera.forward();
        if (coldAt(player.position.x, player.position.z))
            particles.emitBreath(player.camera.position.x + fwd.x * 0.35f,
                    player.camera.position.y - 0.12f,
                    player.camera.position.z + fwd.z * 0.35f, fwd.x, fwd.z);
        for (com.mineclone.world.entity.Mob m : mobs) {
            if (m.dead || !coldAt(m.position.x, m.position.z))
                continue;
            if (m.position.distanceSquared(player.position) > 32f * 32f)
                continue;
            float mx = (float) -Math.sin(m.yaw), mz = (float) -Math.cos(m.yaw);
            particles.emitBreath(m.position.x + mx * 0.4f,
                    m.position.y + m.type.height * 0.85f,
                    m.position.z + mz * 0.4f, mx, mz);
        }
    }

    private boolean coldAt(float x, float z) {
        return world.biomes.biomeAt((int) Math.floor(x), (int) Math.floor(z))
                == com.mineclone.world.Biome.TUNDRA;
    }

    private void emitNatureParticles() {
        for (int attempt = 0; attempt < 4; attempt++) {
            int x = (int) Math.floor(player.position.x) + natureRandom.nextInt(25) - 12;
            int z = (int) Math.floor(player.position.z) + natureRandom.nextInt(25) - 12;
            int y = (int) Math.floor(player.position.y) + natureRandom.nextInt(10) - 2;
            if (y < 1 || y >= Chunk.SIZE_Y - 1
                    || world.getChunkIfExists(Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z)) == null
                    ) continue;
            BlockType cell = world.getBlock(x, y, z);
            float sky = world.getSkyLight(x, y, z) / (float) Chunk.MAX_LIGHT;
            if (cell == BlockType.WATER || cell == BlockType.WATER_FLOW) {
                float ax = x + 0.5f - player.position.x;
                float az = z + 0.5f - player.position.z;
                particles.emitFish(x + natureRandom.nextFloat(), y + 0.3f,
                        z + natureRandom.nextFloat(), ax, az, sky);
                continue;
            }
            if (cell != BlockType.AIR) continue;
            boolean leaf = world.getBlock(x, y + 1, z) == BlockType.LEAVES;
            boolean firefly = daylight < 0.25f && sky > 0.5f
                    && world.getBlock(x, y - 1, z) == BlockType.GRASS;
            if (leaf || firefly)
                particles.emitNature(x + natureRandom.nextFloat(), y + 0.4f,
                        z + natureRandom.nextFloat(), false, firefly, sky);
        }
    }

    /**
     * Тик мобов: ИИ + физика, события в звуки/частицы/урон, затем спавн-деспавн.
     *
     * Зовётся только из updatePlaying — в PAUSED / CREATIVE_MENU / DEAD мобы
     * замирают (иначе зомби добивал бы игрока в меню паузы).
     *
     * Смерть игрока от зомби ловится проверкой player.isDead() в начале
     * следующего кадра — задержка в один кадр, специально не усложняем.
     */
    private void updateMobs(float dt) {
        if (world == null || mobSpawner == null)
            return;
        boolean hostileEnabled = gameMode == com.mineclone.world.GameMode.SURVIVAL;

        // Кто с кем пасётся — до тика: моб решает, куда идти, уже зная, где
        // его сородичи.
        mobSenseTimer -= dt;
        if (mobSenseTimer <= 0f) {
            mobSenseTimer = 0.20f;
            com.mineclone.world.entity.MobHerd.update(mobs);
            // Кто кому угроза и кто чья добыча — тоже до тика, как и стадо.
            com.mineclone.world.entity.Wildlife.sense(mobs);
        }
        float dayPhase = (gameTime % ((float) Math.PI * 2f)) / ((float) Math.PI * 2f);
        if (dayPhase < 0f) dayPhase += 1f;
        com.mineclone.world.entity.MobTactics.updateGroup(mobs, dayPhase, dt);
        BlockType heldBlock = currentBlock();
        boolean torchInHand = heldBlock != null && heldBlock.emittedLight > 0;

        java.util.List<com.mineclone.world.entity.Mob> killedByWolves = null;
        java.util.Iterator<com.mineclone.world.entity.Mob> it = mobs.iterator();
        while (it.hasNext()) {
            com.mineclone.world.entity.Mob m = it.next();
            m.setPlayerTorch(torchInHand);
            m.update(world, player.position, dt, daylight, hostileEnabled);

            if (m.justIdleSound) {
                boolean danger = m.type.hostile || m.isAngry();
                java.util.List<String> voice = m.isAngry() ? sounds.mobAngry(m.type) : sounds.mobSay(m.type);
                // Ночью волк иногда воет вместо дыхания — далеко слышно и
                // сразу понятно, что в лесу кто-то есть.
                if (m.type == com.mineclone.world.entity.MobType.WOLF && !m.isAngry()
                        && daylight < 0.2f && Math.random() < 0.3)
                    voice = sounds.mobHowl(m.type);
                playOccluded(voice, m.soundPosition(), 0.7f, 0.9f + 0.2f * (float) Math.random());
                // Дуга только от угрозы: мычание коровы за спиной ничего не
                // решает, а рычание зомби — решает.
                if (danger)
                    cueSound(m.soundPosition(), 1f, true);
            }

            if (m.justSplashed) {
                playOccluded(sounds.waterSplash(), m.soundPosition(), 0.45f, 1.1f + 0.2f * (float) Math.random());
                int wx = (int) Math.floor(m.position.x), wy = (int) Math.floor(m.position.y + 0.5f),
                    wz = (int) Math.floor(m.position.z);
                particles.emitWaterSplash(m.position.x, m.position.y + 0.4f, m.position.z,
                        world.getSkyLight(wx, wy, wz) / (float) Chunk.MAX_LIGHT,
                        world.getBlockLightWorld(wx, wy, wz) / (float) Chunk.MAX_LIGHT);
            }

            if (m.justEnraged) {
                // Ярость слышна и видна: низкий рёв и вспышка искр над головой.
                playOccluded(sounds.mobAngry(m.type), m.soundPosition(), 1f, 0.62f);
                particles.emitHitImpact(m.position.x, m.position.y + m.type.height, m.position.z,
                        0f, 1f, 0f, true, new float[] { 0.9f, 0.15f, 0.1f }, 1f, 0f);
                cueSound(m.soundPosition(), 1f, true);
            }

            if (m.justTookOff)
                playOccluded(sounds.mobFly(m.type), m.soundPosition(), 0.35f, 1f + 0.2f * (float) Math.random());

            if (m.justBitMob != null) {
                com.mineclone.world.entity.Mob prey = m.justBitMob;
                if (prey.hurt(com.mineclone.world.entity.Wildlife.BITE_DAMAGE, m.position.x, m.position.z, 0.6f, false)) {
                    playOccluded(sounds.mobAngry(m.type), m.soundPosition(), 0.6f, 1.1f);
                    playOccluded(sounds.mobHurt(prey.type), prey.soundPosition(), 0.6f, 1f);
                    if (prey.dead) {
                        if (killedByWolves == null)
                            killedByWolves = new java.util.ArrayList<>();
                        killedByWolves.add(m);
                    }
                }
            }

            if (m.justStepSound) {
                playOccluded(sounds.mobStep(m.type),
                        new Vector3f(m.position.x, m.position.y + 0.1f, m.position.z),
                        0.22f, 0.9f + 0.2f * (float) Math.random());
                if (m.type.hostile && m.state == com.mineclone.world.entity.Mob.State.CHASE)
                    cueSound(m.soundPosition(), 0.55f, true);
                // След моба крупнее или мельче по его габариту: курица и
                // корова не могут топтать снег одинаково.
                // Чётность шага берётся из пройденного пути самого моба:
                // общий переключатель на всех сбил бы дорожки в одну колею.
                boolean left = ((int) (m.walkedDistance
                        / com.mineclone.world.entity.Mob.STEP_DISTANCE)) % 2 == 0;
                dropFootprint(m.position.x, m.position.y, m.position.z, m.yaw,
                        0.46f + m.type.width * 0.5f, left);
                if (m.position.distanceSquared(player.position) < 24f * 24f)
                    kickSnow(m.position.x, m.position.y, m.position.z,
                            (float) -Math.sin(m.yaw), (float) -Math.cos(m.yaw), false);
            }

            // Урон игроку — через окно неуязвимости: иначе стая зомби снимает
            // здоровье втрое быстрее одного, у каждого ведь свой кулдаун.
            if (m.justAttacked && player.takeAttackDamage(m.attackDamage())) {
                applyMobKnockback(m);
                if (m.elite == com.mineclone.world.entity.MobTactics.Elite.VENOMOUS)
                    advancedFeedback.apply(AdvancedFeedback.Effect.POISON, 7f);
                else if (m.elite == com.mineclone.world.entity.MobTactics.Elite.FROST)
                    advancedFeedback.apply(AdvancedFeedback.Effect.FREEZE, 4f);
                else if (m.elite == com.mineclone.world.entity.MobTactics.Elite.BURNING)
                    advancedFeedback.apply(AdvancedFeedback.Effect.STUN, 1.1f);
                sound.playOneOfAt(sounds.hurt(), playerSoundPosition(),
                        0.8f, 0.9f + 0.1f * (float) Math.random());
            }

            if (m.burning) {
                particles.emitMobFlame(m.position.x, m.position.y + m.type.height * 0.5f,
                        m.position.z);
                if (Math.random() < 0.35)
                    particles.emitMobSmoke(m.position.x, m.position.y + m.type.height * 0.9f,
                            m.position.z);
            }

            // Звук и облако — один раз в момент смерти; сам моб ещё полсекунды
            // валится набок, и только потом уходит из списка.
            if (m.dead && !m.deathEffectsDone) {
                m.deathEffectsDone = true;
                com.mineclone.world.entity.MobTactics.leaderFell(mobs, m);
                giveMobDrop(m);
                playOccluded(sounds.mobDeath(m.type), m.soundPosition(),
                        0.8f, 0.95f + 0.1f * (float) Math.random());
                particles.emitMobDeath(m.position.x, m.position.y + m.type.height * 0.5f,
                        m.position.z, m.type.particleColor);
            }
            if (m.dead && m.deathTimer <= 0f)
                it.remove();
        }
        if (killedByWolves != null)
            for (com.mineclone.world.entity.Mob wolf : killedByWolves)
                com.mineclone.world.entity.Wildlife.sate(wolf);

        // Расталкивание: без него стадо слипается в одну точку, а моб спокойно
        // стоит внутри игрока. Игрока не двигаем — свою физику он считает сам.
        for (int i = 0; i < mobs.size(); i++) {
            com.mineclone.world.entity.Mob a = mobs.get(i);
            for (int j = i + 1; j < mobs.size(); j++) {
                com.mineclone.world.entity.Mob b = mobs.get(j);
                com.mineclone.world.entity.EntityPhysics.separate(
                        a.position, a.type.width, a.type.height, 0.25f,
                        b.position, b.type.width, b.type.height, 0.25f);
            }
            com.mineclone.world.entity.EntityPhysics.separate(
                    player.position, Player.WIDTH, Player.HEIGHT, 0f,
                    a.position, a.type.width, a.type.height, 0.5f);
        }

        mobSpawner.despawnFar(mobs, player.position);

        mobSpawnTimer -= dt;
        if (mobSpawnTimer <= 0f) {
            mobSpawnTimer = com.mineclone.world.entity.MobSpawner.TICK_INTERVAL;
            mobSpawner.trySpawn(world, mobs, player.position, daylight);
        }
    }

    /** Горизонтальное отбрасывание игрока от моба + небольшой подброс. */
    private void applyMobKnockback(com.mineclone.world.entity.Mob m) {
        float dx = player.position.x - m.position.x;
        float dz = player.position.z - m.position.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4f)
            return;
        player.velocity.x += dx / len * 4f;
        player.velocity.z += dz / len * 4f;
        if (player.onGround)
            player.velocity.y = 3f;
    }

    /** Пауза: Esc и кнопки разбирает стек меню, миру здесь делать нечего. */
    private void updatePaused(float dt) {
        updateCommandToast(dt);
    }

    private void updateCreativeMenu(float dt) {
        updateCommandToast(dt);
        if (input.pressed(KeyBindings.Action.INVENTORY) || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (cursorItem != null) {
                // Стопка с курсора возвращается в инвентарь, а что не влезло —
                // на землю. Раньше здесь был inventory.add по блоку, и инструмент
                // или еда на курсоре при закрытии просто исчезали.
                cursorItem.count = giveStack(cursorItem);
                throwStack(cursorItem);
                cursorItem = null;
            }
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

    /**
     * Сундук открыт: мир живёт дальше, игрок стоит. Ровно как в творческом
     * меню — окно инвентаря не должно останавливать время суток и мобов,
     * иначе им можно пользоваться как паузой.
     */
    private void updateChestMenu(float dt) {
        updateCommandToast(dt);
        // Сундук мог исчезнуть, пока окно открыто: его сломал огонь, или
        // чанк выгрузился. Окно в этом случае обязано закрыться само.
        if (openChest == null || world == null
                || world.getBlock(chestX, chestY, chestZ) != BlockType.CHEST) {
            closeChest();
            return;
        }
        if (input.pressed(KeyBindings.Action.INVENTORY) || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
            closeChest();
            return;
        }
        updateHeldItem(dt);
        player.update(dt, world, input, false);
        wasInWater = player.inWater;
        updateFootsteps();
        updateActiveWorld(dt);
    }

    /** Печь открыта: мир живёт дальше, как и при открытом сундуке. */
    private void updateFurnaceMenu(float dt) {
        updateCommandToast(dt);
        if (openFurnace == null || world == null
                || world.getBlock(furnaceX, furnaceY, furnaceZ) != BlockType.FURNACE) {
            closeFurnace();
            return;
        }
        if (input.pressed(KeyBindings.Action.INVENTORY) || input.keyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            sound.playOneOf(sounds.uiClick(), 1.0f, 1.0f);
            closeFurnace();
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
        updateCommandToast(dt);
        updateActiveWorld(dt);
    }

    private void respawnPlayer() {
        Vector3f spawn = findRespawnPosition();
        player.respawn(spawn.x, spawn.y, spawn.z);
        musicSense.reset();
        lastPos.set(player.position);
        wasInWater = false;
        waterFlowProbeTimer = 0f;
        waterFlowSoundPosition = null;
        sound.stopLoop("water-flow");
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
        for (int i = 0; i < 9; i++) {
            if (input.pressed(KeyBindings.slot(i)))
                selectedSlot = i;
        }
        // В фоторежиме колесо ведёт фокус, а не слот: иначе одно движение
        // делает сразу две вещи, и ни одну не делает предсказуемо.
        double scroll = photoMode ? 0 : input.getScroll();
        if (scroll != 0) {
            int dir = scroll > 0 ? -1 : 1; // scroll up -> previous slot
            selectedSlot = Math.floorMod(selectedSlot + dir, 9);
        }
        if (selectedSlot != prev) {
            equipProgress = 0f;
            slotAnim = 0f;   // перезапуск пружины выделения в хотбаре
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

        // Осмотр предмета: пока держишь R, предмет выезжает к глазам и медленно
        // поворачивается, а мир за ним уходит в размытие. Только в игре и
        // только от первого лица — в третьем лице рука не видна вовсе.
        boolean want = state == State.PLAYING && !consoleOpen && !photoMode
                && viewMode == ViewMode.FIRST && inventory.get(selectedSlot) != null
                && input.down(KeyBindings.Action.INSPECT);
        float step = dt / INSPECT_TIME;
        inspect = want ? Math.min(1f, inspect + step) : Math.max(0f, inspect - step);
        if (inspect > 0f)
            inspectSpin += dt * 0.8f;
        else
            inspectSpin = 0.25f;     // каждый осмотр начинается с выгодного ракурса
    }

    private void startHandSwing() {
        handSwing = 1f;
    }

    /**
     * Координата чанка игрока на прошлом проходе стриминга. Полный обход
     * радиуса и выгрузка дальних чанков нужны только когда игрок перешёл
     * границу чанка: раньше и то и другое крутилось каждый кадр — 225 проверок
     * и обход всех загруженных чанков на месте, где ничего не менялось.
     */
    private int lastStreamCX = Integer.MIN_VALUE, lastStreamCZ = Integer.MIN_VALUE;

    /** Сколько миллисекунд кадра отдаётся загрузке готовых мешей на GPU в игре. */
    private static final double UPLOAD_BUDGET_MS = 3.0;
    /** То же на экране загрузки: рисовать нечего, кадру нечем больше заняться. */
    private static final double LOADING_UPLOAD_BUDGET_MS = 22.0;
    /** Сколько чанков за кадр заливается блочным светом. */
    private static final int LIGHT_FLOODS_PER_FRAME = 4;
    private static final int LOADING_LIGHT_FLOODS_PER_FRAME = 32;
    /** Потолок заявок на перестройку меша за кадр — сам обход чанков дешёвый. */
    private static final int MESH_SUBMITS_PER_FRAME = 16;
    /**
     * В каком радиусе от игрока перестройка меша считается срочной. Дотянуться
     * игрок может на пять блоков, то есть правка всегда либо в его чанке, либо в
     * соседнем — а удар по блоку на границе пачкает оба сразу. Запоминать
     * «чанк последней правки» отдельно значило бы пропустить соседа.
     */
    private static final int URGENT_MESH_RADIUS = 1;

    private void ensureChunksLoaded() {
        ensureChunksLoaded(UPLOAD_BUDGET_MS, LIGHT_FLOODS_PER_FRAME);
    }

    private void ensureChunksLoaded(double uploadBudgetMs, int lightBudget) {
        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);
        boolean moved = pcx != lastStreamCX || pcz != lastStreamCZ;
        if (moved) {
            lastStreamCX = pcx;
            lastStreamCZ = pcz;
            loader.setPriorityCenter(pcx, pcz);
        }
        // Проход по радиусу нужен не только при сдвиге: чанк, только что
        // закончивший генерацию, становится пригодным для меширования, а
        // соседи — для своего. Пока конвейер не опустел, обход обязателен;
        // как опустел — в стоячем кадре не делается ничего.
        boolean streaming = loader.pendingGenCount() > 0
                || loader.pendingLightCount() > 0
                || loader.pendingMeshCount() > 0;
        if (moved || streaming)
            loader.ensureRadius(pcx, pcz, renderRadius + 1);
        loader.drainLightFlood(lightBudget);
        uploadReadyMeshes(uploadBudgetMs);
        if (moved)
            evictDistantChunks(pcx, pcz);
    }

    /**
     * Забирает готовые меши из фонового пула и грузит их на GPU, пока не
     * кончится бюджет кадра. Меши строятся в фоне несколькими потоками и
     * приезжают пачками; жёсткий счётчик «восемь штук» либо душил стриминг,
     * либо отдавал кадр загрузке — время честнее.
     */
    private void uploadReadyMeshes(double budgetMs) {
        double deadline = GLFW.glfwGetTime() + budgetMs / 1000.0;
        while (true) {
            java.util.List<ChunkLoader.Ready> batch = loader.drainReady(4);
            if (batch.isEmpty())
                break;
            for (ChunkLoader.Ready r : batch)
                uploadMesh(r);
            if (GLFW.glfwGetTime() >= deadline)
                break;
        }
    }

    private void uploadMesh(ChunkLoader.Ready r) {
        int cx = (int) (r.key >> 32);
        int cz = (int) (r.key & 0xFFFFFFFFL);
        Chunk c = world.getChunkIfExists(cx, cz);
        if (c == null) {
            loader.forget(r.key);
            return;
        }
        // Меш-потоков несколько, и порядок возврата ничем не задан. Меш,
        // построенный раньше, но финишировавший позже, вернул бы на место
        // только что сломанный блок — поэтому принимается строго новее
        // уже загруженного.
        if (!c.acceptMeshVersion(r.version))
            return;
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
        // Содержимое не менялось с момента постройки — флаг снимается. Если
        // менялось, флаг остаётся, и следующий кадр закажет свежий меш.
        c.clearDirtyIfCurrent(r.version);
        invalidateShadows();
        if (precipitation != null)
            precipitation.invalidate();
        WaterSimulator.activateChunkIfWater(world, cx, cz);
        // Предметы из сейва чанка поднимаются в мир, когда чанк готов.
        for (com.mineclone.world.DroppedItem d : c.takePendingItems())
            items.add(com.mineclone.world.entity.ItemEntity.restored(d, itemRandom));
    }

    private void evictDistantChunks(int pcx, int pcz) {
        int keepRadius = renderRadius + CHUNK_UNLOAD_MARGIN;
        java.util.List<Chunk> doomed = null;
        for (Chunk c : world.getLoadedChunks()) {
            if (Math.abs(c.cx - pcx) <= keepRadius && Math.abs(c.cz - pcz) <= keepRadius)
                continue;
            if (doomed == null)
                doomed = new java.util.ArrayList<>();
            doomed.add(c);
        }
        if (doomed == null)
            return;

        java.util.HashSet<Long> doomedKeys = new java.util.HashSet<>();
        for (Chunk c : doomed) {
            doomedKeys.add(World.key(c.cx, c.cz));
            saveChunkIfModified(c);
        }
        // Один проход по предметам на всю выгрузку. Прежний removeIf стоял
        // внутри цикла по чанкам, то есть список предметов перебирался столько
        // раз, сколько чанков выгружается за раз.
        items.removeIf(e -> doomedKeys.contains(World.key(
                Math.floorDiv((int) Math.floor(e.position.x), Chunk.SIZE_X),
                Math.floorDiv((int) Math.floor(e.position.z), Chunk.SIZE_Z))));

        for (Chunk c : doomed) {
            long key = World.key(c.cx, c.cz);
            Mesh old = chunkMeshes.remove(key);
            if (old != null)
                old.destroy();
            Mesh oldW = waterMeshes.remove(key);
            if (oldW != null)
                oldW.destroy();
            c.forgetUploadedMesh();
            loader.forget(key);
            WaterSimulator.forgetChunk(key);
            world.removeChunk(c.cx, c.cz);
        }
        invalidateShadows();
    }

    /**
     * Радиус, который загрузка обязана закрыть целиком. Раньше здесь стояла
     * тройка при радиусе отрисовки шесть: игрок попадал в мир в момент,
     * когда большая часть видимого ещё строилась, и первые секунды игры
     * стабильно дёргались.
     */
    private int loadingRadius() {
        return Math.max(1, Math.min(4, renderRadius));
    }

    /**
     * Сколько чанков может достраиваться уже в игре. Столько главный поток
     * дожёвывает за пару кадров, не роняя их.
     */
    private static final int LOADING_TAIL_ALLOWANCE = 8;

    /** Этап, на котором сейчас загрузка — его показывает экран. */
    private com.mineclone.world.LoadStage loadStage = com.mineclone.world.LoadStage.GENERATING;

    /**
     * Прогресс подготовки мира и этап, на котором она стоит.
     *
     * <p>Конвейер идёт тремя шагами — чанки существуют, свет разлит,
     * меши загружены — и каждый занимает треть полосы. Одно число не
     * говорило ничего о том, почему загрузка встала.
     */
    private float computeLoadingProgress() {
        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);
        int radius = loadingRadius();
        int total = (radius * 2 + 1) * (radius * 2 + 1);
        int generated = 0, lit = 0, built = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                long key = World.key(cx, cz);
                if (world.getChunkIfExists(cx, cz) == null)
                    continue;
                generated++;
                if (loader.hasPendingLightFlood(key))
                    continue;
                lit++;
                if (chunkMeshes.containsKey(key) || waterMeshes.containsKey(key)
                        || !world.getChunkIfExists(cx, cz).isDirty())
                    built++;
            }
        }
        float fGen = generated / (float) total;
        float fLit = lit / (float) total;
        float fBuilt = built / (float) total;
        loadFractions[0] = fGen;
        loadFractions[1] = fLit;
        loadFractions[2] = fBuilt;
        loadStage = fGen < 1f ? com.mineclone.world.LoadStage.GENERATING
                : fLit < 1f ? com.mineclone.world.LoadStage.LIGHTING
                : fBuilt < 1f ? com.mineclone.world.LoadStage.BUILDING
                : com.mineclone.world.LoadStage.DONE;
        return (fGen + fLit + fBuilt) / 3f;
    }

    /**
     * Мир готов принять игрока: ближний радиус закрыт целиком, а хвост
     * очереди короткий настолько, что достроится незаметно.
     */
    private boolean worldReadyForPlay() {
        return loadStage == com.mineclone.world.LoadStage.DONE
                && loader.pendingMeshCount() + loader.pendingLightCount()
                        <= LOADING_TAIL_ALLOWANCE;
    }

    private Raycaster.Hit lastHit = null;
    private static final int NO_BREAK = Integer.MIN_VALUE;
    private int breakX = NO_BREAK, breakY = NO_BREAK, breakZ = NO_BREAK;
    private float breakProgress = 0f;
    private float breakDigTimer = 0f;
    private BlockBreakOverlay breakOverlay;
    /** Дальность удара рукой по мобу. */
    private static final float MOB_REACH = 4.5f;
    /** Урон рукой — 1 сердце. */
    private static final float HAND_DAMAGE = 2f;
    /**
     * Кулдаун удара игрока. Вместе с окном неуязвимости моба
     * ({@code Mob.INVULN_TIME}) это и есть защита от закликивания: без них урон
     * определялся тем, как быстро игрок щёлкает мышью.
     */
    private static final float ATTACK_COOLDOWN = 0.5f;
    /** Множитель урона при ударе в падении (MC-крит). */
    private static final float CRIT_MULTIPLIER = 1.5f;
    /** Во сколько раз сильнее отброс при ударе в спринте. */
    private static final float SPRINT_KNOCKBACK = 1.6f;
    private float attackCooldown = 0f;

    private void handleInteraction(float dt) {
        Vector3f origin = new Vector3f(player.camera.position);
        Vector3f dir = player.camera.forward();
        lastHit = Raycaster.cast(world, origin, dir, 6f);

        // Моб под прицелом проверяется ДО работы с блоками: пока он на линии
        // взгляда и ближе блока, ломание не идёт вообще — не только в кадре
        // нажатия. Иначе, удерживая ЛКМ на мобе, игрок докапывался бы до блока
        // за ним.
        if (attackCooldown > 0f)
            attackCooldown -= dt;

        com.mineclone.world.entity.Mob aimedMob = pickAimedMob(origin, dir);
        aimingAtMob = aimedMob != null && !aimedMob.dead;
        if (aimedMob != null && attackCooldown <= 0f
                && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            attackCooldown = ATTACK_COOLDOWN;
            startHandSwing();
            // Крит — как в MC: удар в падении бьёт сильнее. Спринт-удар не
            // добавляет урона, но отбрасывает заметно дальше.
            boolean crit = !player.onGround && player.velocity.y < -0.1f;
            float damage = crit ? HAND_DAMAGE * CRIT_MULTIPLIER : HAND_DAMAGE;
            float knockback = player.isSprinting ? SPRINT_KNOCKBACK : 1f;
            float hitT = aimedMob.rayHitDistance(origin, dir);
            float hitY = origin.y + dir.y * Math.max(0f, hitT);
            float normalizedY = (hitY - aimedMob.position.y) / Math.max(0.1f, aimedMob.type.height);
            float lateral = (origin.x + dir.x * Math.max(0f, hitT) - aimedMob.position.x)
                    / Math.max(0.1f, aimedMob.type.width * 0.5f);
            var limb = com.mineclone.world.entity.LimbDamage.fromHitHeight(normalizedY, lateral);
            if (aimedMob.hurtLimb(damage, limb, player.position.x, player.position.z, knockback)) {
                playOccluded(sounds.mobHurt(aimedMob.type), aimedMob.soundPosition(),
                        0.8f, (crit ? 1.1f : 0.9f) + 0.2f * (float) Math.random());
                sound.playOneOfAt(sounds.playerAttack(crit ? "crit" : "strong"),
                        aimedMob.soundPosition(), 0.55f, 0.95f + 0.1f * (float) Math.random());
                emitHitImpact(aimedMob, origin, dir, Math.max(0f, hitT), crit);
            }
        }

        if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT) && tryEat())
            return;

        if (lastHit == null) {
            // Взмах в пустоту: рука и инструмент отыгрывают удар, а инструмент
            // ещё и свистит — без этого клик по воздуху выглядит зависанием.
            if (aimedMob == null && attackCooldown <= 0f
                    && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                attackCooldown = ATTACK_COOLDOWN * 0.6f;
                startHandSwing();
                if (heldTool() != null)
                    sound.playOneOf(sounds.playerAttack("sweep"), 0.25f,
                            0.95f + 0.15f * (float) Math.random());
            }
            resetBreakState();
            return;
        }

        // Debug stick: middle click cycles block metadata
        if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
            byte m = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
            world.setBlock(lastHit.x, lastHit.y, lastHit.z,
                    world.getBlock(lastHit.x, lastHit.y, lastHit.z), (byte) ((m + 1) & 0x0F));
        }

        // --- Left mouse: break ---
        if (aimedMob != null) {
            resetBreakState();
        } else if (instantBreak) {
            if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
                if (target.hardness > 0f && target.hardness < Float.MAX_VALUE) {
                    startHandSwing();
                    executeBlockBreak(lastHit.x, lastHit.y, lastHit.z, target);
                }
            }
            resetBreakState();
        } else if (input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
            if (target.hardness > 0f && target.hardness < Float.MAX_VALUE) {
                if (breakX == lastHit.x && breakY == lastHit.y && breakZ == lastHit.z) {
                    // Accumulate progress on the same block
                    breakProgress += dt * miningSpeed(target) / target.hardness;
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
            if (target == BlockType.CHEST) {
                openChest(lastHit.x, lastHit.y, lastHit.z);
                return;
            }
            if (target == BlockType.FURNACE) {
                openFurnace(lastHit.x, lastHit.y, lastHit.z);
                return;
            }
            if (target == BlockType.BEDROLL) {
                trySleep(lastHit.x, lastHit.y, lastHit.z);
                return;
            }
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
                if (py < 0 || py >= Chunk.SIZE_Y
                        || world.getChunkIfExists(Math.floorDiv(px, Chunk.SIZE_X),
                                Math.floorDiv(pz, Chunk.SIZE_Z)) == null)
                    return;
                if (!playerOccupies(px, py, pz)) {
                    BlockType placing = currentBlock();
                    if (placing == null || placing == BlockType.AIR)
                        return;
                    if (gameMode == com.mineclone.world.GameMode.SURVIVAL && !inventory.hasItem(selectedSlot))
                        return;
                    byte meta = 0;
                    boolean placed = false;
                    if (placing == BlockType.DOOR_CLOSED) {
                        meta = facingFromCamera();
                        // Place 2-block door: bottom + top
                        if (py + 1 < Chunk.SIZE_Y
                                && world.getBlock(px, py + 1, pz) == BlockType.AIR
                                && !playerOccupies(px, py + 1, pz)) {
                            emitNoise(px + 0.5f, py + 0.5f, pz + 0.5f, NOISE_PLACE);
                            sound.playOneOfAt(sounds.place(placing),
                                    blockSoundPosition(px, py, pz),
                                    0.8f, 0.85f + 0.2f * (float) Math.random());
                            world.setBlock(px, py, pz, BlockType.DOOR_CLOSED, meta);
                            world.setBlock(px, py + 1, pz, BlockType.DOOR_CLOSED, (byte) (meta | 0x4));
                            placed = true;
                        }
                    } else {
                        if (placing == BlockType.STAIRS)
                            meta = stairFacingFromCamera();
                        // Спальник рисуется тем же emitLayer, что снег, и
                        // высоту берёт из meta: с нулём он был бы плёнкой в
                        // 1/8 блока.
                        if (placing == BlockType.BEDROLL)
                            meta = BEDROLL_META;
                        emitNoise(px + 0.5f, py + 0.5f, pz + 0.5f, NOISE_PLACE);
                        sound.playOneOfAt(sounds.place(placing), blockSoundPosition(px, py, pz),
                                0.8f, 0.85f + 0.2f * (float) Math.random());
                        world.setBlock(px, py, pz, placing, meta);
                        if (com.mineclone.world.StructureStability.heavy(placing))
                            playerStructures.add(com.mineclone.world.StructureStability.placementKey(px, py, pz));
                        placed = true;
                    }
                    if (placed)
                        musicSense.onBlockPlaced();
                    if (placed && gameMode == com.mineclone.world.GameMode.SURVIVAL)
                        inventory.removeOne(selectedSlot);
                }
            }
        }
    }

    /**
     * Искры и брызги в точке, куда пришёлся удар: не в центр моба, а туда,
     * где луч взгляда вошёл в его коробку — тогда удар в голову и удар в бок
     * выглядят по-разному.
     */
    private void emitHitImpact(com.mineclone.world.entity.Mob m, Vector3f origin, Vector3f dir,
                               float t, boolean crit) {
        float hx = origin.x + dir.x * t, hy = origin.y + dir.y * t, hz = origin.z + dir.z * t;
        int bx = (int) Math.floor(hx), by = (int) Math.floor(hy), bz = (int) Math.floor(hz);
        float sky = world.getSkyLight(bx, by, bz) / (float) Chunk.MAX_LIGHT;
        float blk = world.getBlockLightWorld(bx, by, bz) / (float) Chunk.MAX_LIGHT;
        particles.emitHitImpact(hx, hy, hz, dir.x, dir.y, dir.z, crit, m.type.particleColor, sky, blk);
    }

    /**
     * Добыча с убитого моба выпадает на землю там, где он упал.
     *
     * Раньше предметов в мире не было, и добыча шла прямо в инвентарь — с
     * оговоркой про дальность, иначе моб, погибший в сорока блоках, кормил
     * игрока издалека. Теперь мясо лежит у трупа, и оговорка не нужна: за ним
     * надо дойти.
     */
    private void giveMobDrop(com.mineclone.world.entity.Mob m) {
        if (gameMode != com.mineclone.world.GameMode.SURVIVAL)
            return;
        com.mineclone.world.FoodType drop = m.type.drop();
        // Задранного волком съели — мяса с него нет.
        if (drop == null || m.eaten || m.type.dropCount() <= 0)
            return;
        dropItem(new com.mineclone.world.ItemStack(drop, m.type.dropCount()),
                m.position.x, m.position.y + m.type.height * 0.5f, m.position.z);
    }

    /**
     * Роняет стопку в мир с небольшим подскоком. Потолок числа предметов
     * защищает кадр: сверх него исчезает самый старый — список пополняется с
     * конца, и в его начале лежат давние.
     */
    private void dropItem(com.mineclone.world.ItemStack stack, float x, float y, float z) {
        if (stack == null || stack.count <= 0)
            return;
        addItemEntity(com.mineclone.world.entity.ItemEntity.popped(stack, x, y, z, itemRandom));
    }

    private void addItemEntity(com.mineclone.world.entity.ItemEntity e) {
        if (items.size() >= MAX_ITEMS)
            items.remove(0);
        items.add(e);
    }

    /**
     * Предметы на земле: физика, магнит, подбор, слияние стопок.
     *
     * Предмет в ещё не пришедшем чанке замирает: физика читала бы там воздух,
     * и он провалился бы сквозь землю, которой пока просто нет в памяти.
     */
    private void updateItems(float dt) {
        if (items.isEmpty() || world == null)
            return;
        boolean collect = state == State.PLAYING && !player.isDead();
        itemTarget.set(player.position.x, player.position.y + ITEM_TARGET_HEIGHT, player.position.z);
        int picked = 0;
        for (java.util.Iterator<com.mineclone.world.entity.ItemEntity> it = items.iterator(); it.hasNext(); ) {
            com.mineclone.world.entity.ItemEntity e = it.next();
            int cx = Math.floorDiv((int) Math.floor(e.position.x), Chunk.SIZE_X);
            int cz = Math.floorDiv((int) Math.floor(e.position.z), Chunk.SIZE_Z);
            if (world.getChunkIfExists(cx, cz) == null)
                continue;
            boolean take = collect && canTake(e.stack);
            e.update(world, itemTarget, take, dt);
            if (take && e.readyForPickup(itemTarget)) {
                int before = e.stack.count;
                e.stack.count = giveStack(e.stack);
                if (e.stack.count < before)
                    picked++;
            }
            if (e.expired() || e.position.y < -16f)
                it.remove();
        }
        if (picked > 0)
            sound.playOneOf(sounds.pickup(), 0.32f, 1.55f + 0.45f * itemRandom.nextFloat());

        itemMergeTimer -= dt;
        if (itemMergeTimer <= 0f) {
            itemMergeTimer = ITEM_MERGE_INTERVAL;
            com.mineclone.world.entity.ItemEntity.mergeNearby(items);
            items.removeIf(com.mineclone.world.entity.ItemEntity::expired);
        }
    }

    /**
     * Есть ли в инвентаре место хотя бы под один предмет из стопки. Без места
     * предмет не срывается к игроку: иначе полный инвентарь превращал бы
     * каждую россыпь у ног в дрожащий рой.
     */
    private boolean canTake(com.mineclone.world.ItemStack s) {
        if (s.isTool() || s.isFood()) {
            for (int i = 0; i < inventory.size(); i++) {
                com.mineclone.world.ItemStack t = inventory.get(i);
                if (t == null)
                    return true;
                if (s.isFood() && t.isFood() && t.food == s.food && !t.isFull())
                    return true;
            }
            return false;
        }
        return inventory.canAdd(s.type, 1);
    }

    /**
     * Бросок предмета из руки по Q: один предмет, с Ctrl — вся стопка.
     *
     * Брошенный летит туда, куда смотрит игрок, и долго не даётся в руки —
     * иначе магнит возвращал бы его обратно, не дав упасть.
     */
    private void throwHeldItem(boolean wholeStack) {
        throwHeldItem(wholeStack, 0.45f);
    }

    private void throwHeldItem(boolean wholeStack, float charge) {
        com.mineclone.world.ItemStack held = inventory.get(selectedSlot);
        if (held == null)
            return;
        com.mineclone.world.ItemStack thrown;
        if (wholeStack || held.count <= 1) {
            thrown = held;
            inventory.set(selectedSlot, null);
        } else {
            thrown = held.copy();
            thrown.count = 1;
            held.count--;
        }
        throwStack(thrown, charge);
        startHandSwing();
    }

    private void updateChargedThrow(float dt) {
        if (photoMode) {
            chargingThrow = false;
            throwCharge = 0f;
            return;
        }
        if (input.pressed(KeyBindings.Action.DROP)) {
            chargingThrow = inventory.get(selectedSlot) != null;
            throwCharge = 0f;
            throwWholeStack = input.keyDown(GLFW.GLFW_KEY_LEFT_CONTROL)
                    || input.keyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
        }
        if (chargingThrow && input.down(KeyBindings.Action.DROP))
            throwCharge = Math.min(1f, throwCharge + dt / THROW_CHARGE_TIME);
        if (chargingThrow && input.released(KeyBindings.Action.DROP)) {
            throwHeldItem(throwWholeStack, throwCharge);
            chargingThrow = false;
            throwCharge = 0f;
        }
    }

    /**
     * Бросает стопку перед игроком — туда, куда он смотрит. Так же уходит на
     * землю то, что осталось на курсоре при закрытии окна и не влезло
     * обратно: молча уничтожать предмет нельзя.
     */
    private void throwStack(com.mineclone.world.ItemStack thrown) {
        throwStack(thrown, 0.45f);
    }

    private void throwStack(com.mineclone.world.ItemStack thrown, float charge) {
        if (thrown == null || thrown.count <= 0 || world == null)
            return;
        Vector3f eye = player.camera.position;
        Vector3f fwd = player.camera.forward();
        float sx = eye.x + fwd.x * 0.45f, sy = eye.y - 0.3f + fwd.y * 0.45f, sz = eye.z + fwd.z * 0.45f;
        // Лицом в стену точка вылета оказалась бы в блоке — тогда из глаз.
        if (world.getBlock((int) Math.floor(sx), (int) Math.floor(sy), (int) Math.floor(sz)).solid) {
            sx = eye.x;
            sy = eye.y - 0.3f;
            sz = eye.z;
        }
        com.mineclone.world.entity.ItemEntity e = new com.mineclone.world.entity.ItemEntity(thrown,
                sx, sy, sz, com.mineclone.world.entity.ItemEntity.THROW_DELAY, itemRandom.nextFloat() * 6.28f);
        float strength = Math.max(0f, Math.min(1f, charge));
        float speed = THROW_SPEED * (0.45f + 1.15f * strength);
        e.velocity.set(fwd.x * speed + player.velocity.x,
                fwd.y * speed + THROW_LIFT * (0.65f + strength * 0.35f),
                fwd.z * speed + player.velocity.z);
        addItemEntity(e);
        sound.playOneOf(sounds.pickup(), 0.3f, 0.75f + 0.15f * itemRandom.nextFloat());
    }

    /**
     * Кладёт произвольную стопку игроку; возвращает остаток, который не влез.
     *
     * Инструмент не стопкуется, поэтому ему нужен целый свободный слот —
     * попытка слить его в существующую стопку уничтожила бы износ.
     */
    private int giveStack(com.mineclone.world.ItemStack s) {
        if (s == null || s.count <= 0)
            return 0;
        if (s.isTool()) {
            for (int i = 0; i < inventory.size(); i++)
                if (inventory.get(i) == null) {
                    inventory.set(i, s.copy());
                    return 0;
                }
            return s.count;
        }
        if (s.isFood())
            return inventory.addFood(s.food, s.count);
        return inventory.add(s.type, s.count);
    }

    /**
     * Открывает сундук под прицелом.
     *
     * Массив слотов заводится лениво, при первом открытии: сундуков в мире
     * может стоять много, и пустые двадцать семь ссылок на каждый — это
     * память и байты в сейве ни за что.
     */
    private void openChest(int x, int y, int z) {
        com.mineclone.world.ItemStack[] slots = world.createChest(x, y, z);
        if (slots == null)
            return;
        openChest = slots;
        chestX = x;
        chestY = y;
        chestZ = z;
        state = State.CHEST_MENU;
        input.grabCursor(false);
        swallowMouseUntilUp = true;
        emitNoise(x + 0.5f, y + 0.5f, z + 0.5f, NOISE_PLACE);
        sound.playOneOfAt(sounds.doorToggle(), blockSoundPosition(x, y, z),
                0.5f, 1.25f + 0.1f * (float) Math.random());
    }

    /**
     * Закрывает сундук. То, что осталось на курсоре, возвращается в мир:
     * сначала в инвентарь, что не влезло — обратно в сундук, а остаток — на
     * землю. Молча уничтожать предмет при закрытии окна нельзя.
     */
    private void closeChest() {
        if (cursorItem != null) {
            int leftover = giveStack(cursorItem);
            if (leftover > 0) {
                cursorItem.count = leftover;
                if (openChest != null)
                    addToChest(cursorItem);
                throwStack(cursorItem);
            }
            cursorItem = null;
        }
        if (openChest != null)
            sound.playOneOfAt(sounds.doorToggle(),
                    blockSoundPosition(chestX, chestY, chestZ),
                    0.5f, 1.05f + 0.1f * (float) Math.random());
        openChest = null;
        state = State.PLAYING;
        input.grabCursor(true);
    }

    /**
     * Клик по слоту печи.
     *
     * Выходной слот только отдаёт: положить туда что-то значило бы получить
     * из печи предмет, который она не плавила.
     */
    private com.mineclone.world.ItemStack clickFurnaceSlot(int slot, boolean right) {
        com.mineclone.world.ItemStack[] box = {
                openFurnace.input, openFurnace.fuel, openFurnace.output };
        if (slot == Hud.FURNACE_OUTPUT && cursorItem != null
                && (box[slot] == null || !box[slot].stacksWith(cursorItem)))
            return cursorItem;
        com.mineclone.world.ItemStack out = right
                ? com.mineclone.world.Inventory.rightClick(box, slot, cursorItem)
                : com.mineclone.world.Inventory.leftClick(box, slot, cursorItem);
        openFurnace.input = box[0];
        openFurnace.fuel = box[1];
        openFurnace.output = box[2];
        return out;
    }

    /** Высота спальника в meta: (3 + 1) / 8 — половина блока. */
    private static final byte BEDROLL_META = 3;

    /**
     * Лечь в спальник под прицелом.
     *
     * Сон — единственная механика, которая двигает время вперёд рывком,
     * поэтому тени, небо и каскады после него надо считать заново: иначе
     * первый кадр нового дня рисуется вчерашним светом.
     */
    private void trySleep(int x, int y, int z) {
        var verdict = com.mineclone.world.SleepRules.check(daylight, hostilesNear(x, y, z));
        switch (verdict) {
            case TOO_BRIGHT -> {
                showCommandToast("Спать можно только ночью");
                return;
            }
            case MONSTERS -> {
                showCommandToast("Рядом монстры — не до сна");
                return;
            }
            default -> { }
        }
        gameTime = com.mineclone.world.SleepRules.nextDawn(gameTime);
        daylight = computeDaylight();
        // Спальник становится точкой возрождения: ради этого его и носят с
        // собой, а не только ради пропуска ночи.
        worldSpawn.set(x + 0.5f, y + 1f, z + 0.5f);
        invalidateShadows();
        if (music != null)
            music.onWake();
        sound.playOneOfAt(sounds.place(BlockType.BEDROLL),
                blockSoundPosition(x, y, z), 0.5f, 0.85f);
        showCommandToast("Доброе утро. Точка возрождения здесь");
    }

    /** Сколько живых враждебных мобов рядом со спальником. */
    private int hostilesNear(int x, int y, int z) {
        float r = com.mineclone.world.SleepRules.MONSTER_RANGE;
        int n = 0;
        for (com.mineclone.world.entity.Mob m : mobs) {
            if (m.dead || !m.type.hostile)
                continue;
            float dx = m.position.x - (x + 0.5f);
            float dy = m.position.y - y;
            float dz = m.position.z - (z + 0.5f);
            if (dx * dx + dy * dy + dz * dz <= r * r)
                n++;
        }
        return n;
    }

    /** Открывает печь под прицелом. */
    private void openFurnace(int x, int y, int z) {
        com.mineclone.world.Furnace f = world.createFurnace(x, y, z);
        if (f == null)
            return;
        openFurnace = f;
        furnaceX = x;
        furnaceY = y;
        furnaceZ = z;
        state = State.FURNACE_MENU;
        input.grabCursor(false);
        swallowMouseUntilUp = true;
        sound.playOneOfAt(sounds.doorToggle(), blockSoundPosition(x, y, z),
                0.45f, 0.8f + 0.1f * (float) Math.random());
    }

    /**
     * Закрывает печь. То, что осталось на курсоре, уходит игроку, что не
     * влезло — во входной слот, если он пуст, а иначе на землю: молча
     * уничтожать предмет нельзя.
     */
    private void closeFurnace() {
        if (cursorItem != null) {
            int leftover = giveStack(cursorItem);
            if (leftover > 0) {
                cursorItem.count = leftover;
                if (openFurnace != null && openFurnace.input == null)
                    openFurnace.input = cursorItem;
                else
                    throwStack(cursorItem);
            }
            cursorItem = null;
        }
        openFurnace = null;
        state = State.PLAYING;
        input.grabCursor(true);
    }

    /**
     * Тик всех печей вокруг игрока.
     *
     * Радиусом, а не по всему миру: печь за горизонтом всё равно некому
     * смотреть, а обход всех загруженных чанков каждые четверть секунды —
     * это работа, растущая с дальностью прорисовки.
     */
    /**
     * Фоновая атмосфера: пещера, дождь, гром, вода.
     *
     * Звук пещеры играется не в голове, а из случайной тёмной точки рядом —
     * иначе он звучит как эффект интерфейса, а не как «что-то там, в
     * темноте», ради чего он и нужен.
     */
    private void updateAmbient(float dt) {
        if (ambient == null || sound == null || world == null)
            return;
        int ex = (int) Math.floor(player.camera.position.x);
        int ey = (int) Math.floor(player.camera.position.y);
        int ez = (int) Math.floor(player.camera.position.z);
        int skyLight = world.getSkyLight(ex, ey, ez);
        boolean dark = skyLight <= 3
                && world.getBlockLightWorld(ex, ey, ez) <= 7;
        boolean outdoors = com.mineclone.audio.AcousticProbe.freeRun(
                world, ex, ey, ez, 0, 1, 0) >= com.mineclone.audio.AcousticProbe.MAX_DISTANCE;
        // Эхо подстраивается реже кадра: шесть лучей по двадцать четыре блока
        // каждый — это не то, за что стоит платить шестьдесят раз в секунду,
        // а пространство вокруг головы так быстро не меняется.
        reverbTimer -= dt;
        if (reverbTimer <= 0f) {
            reverbTimer = REVERB_INTERVAL;
            float target = outdoors ? 0f : com.mineclone.audio.AcousticProbe.enclosure(world,
                    player.camera.position.x, player.camera.position.y, player.camera.position.z);
            // На улице send выключаем сразу: иначе длинный пещерный хвост
            // продолжает окрашивать шаги ещё несколько секунд после выхода.
            // Внутри переход по-прежнему плавный, чтобы комнаты не щёлкали.
            enclosure = outdoors ? 0f : enclosure + (target - enclosure) * 0.5f;
            sound.setEnclosure(enclosure);
        }
        // Гремит только гроза, а не метель: в снег грома не бывает.
        float thunderStorm = atmosphere.storm * (1f - atmosphere.snow);
        var cue = ambient.tick(dt, dark, player.eyeInWater, atmosphere.rain(), thunderStorm,
                atmosphere.windSpeed(), outdoors, skyLight);
        switch (cue) {
            case CAVE -> {
                Vector3f spot = caveSoundSpot();
                sound.playOneOfAt(sounds.ambientCave(), spot,
                        0.75f, 0.92f + 0.16f * (float) Math.random());
                cueSound(spot, 0.35f, false);
            }
            case RAIN -> sound.playOneOf(sounds.ambientRain(), 0.35f * Math.min(1f,
                    com.mineclone.audio.AmbientSound.heardRain(atmosphere.rain(), skyLight)), 1f);
            case WIND -> sound.playOneOf(sounds.ambientWind(),
                    Math.min(0.55f, 0.12f + atmosphere.windSpeed() * 0.07f),
                    0.85f + 0.2f * (float) Math.random());
            case THUNDER -> sound.playOneOf(sounds.ambientThunder(), 0.9f,
                    0.9f + 0.2f * (float) Math.random());
            case UNDERWATER -> sound.playOneOf(sounds.ambientUnderwater(), 0.55f, 1f);
            case UNDERWATER_EXTRA -> sound.playOneOf(sounds.ambientUnderwaterExtra(),
                    0.45f, 0.9f + 0.2f * (float) Math.random());
            case WATER_ENTER -> sound.playOneOf(sounds.waterEnter(), 0.7f, 1f);
            case WATER_EXIT -> sound.playOneOf(sounds.waterExit(), 0.7f, 1f);
            default -> { }
        }
    }

    /** Случайная тёмная точка рядом — оттуда и «донеслось». */
    private Vector3f caveSoundSpot() {
        Vector3f eye = player.camera.position;
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = natureRandom.nextInt(CAVE_SOUND_RADIUS * 2 + 1) - CAVE_SOUND_RADIUS;
            int dy = natureRandom.nextInt(13) - 6;
            int dz = natureRandom.nextInt(CAVE_SOUND_RADIUS * 2 + 1) - CAVE_SOUND_RADIUS;
            int x = (int) Math.floor(eye.x) + dx;
            int y = (int) Math.floor(eye.y) + dy;
            int z = (int) Math.floor(eye.z) + dz;
            if (y < 1 || y >= Chunk.SIZE_Y)
                continue;
            if (world.getBlock(x, y, z) != BlockType.AIR)
                continue;
            if (world.getSkyLight(x, y, z) > 3)
                continue;
            return new Vector3f(x + 0.5f, y + 0.5f, z + 0.5f);
        }
        return new Vector3f(eye);
    }

    private void tickFurnaces(float dt) {
        if (world == null)
            return;
        furnaceTimer -= dt;
        if (furnaceTimer > 0f)
            return;
        float step = FURNACE_TICK;
        furnaceTimer = FURNACE_TICK;
        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);
        for (int cx = pcx - FURNACE_RADIUS; cx <= pcx + FURNACE_RADIUS; cx++)
            for (int cz = pcz - FURNACE_RADIUS; cz <= pcz + FURNACE_RADIUS; cz++) {
                Chunk c = world.getChunkIfExists(cx, cz);
                if (c == null || c.furnaces().isEmpty())
                    continue;
                for (var f : c.furnaces().values())
                    // Чанк помечается изменённым только когда поменялись
                    // слоты: иначе горящая печь переписывала бы свой файл
                    // четыре раза в секунду всё время работы.
                    if (f.tick(step))
                        c.modified = true;
            }
    }

    /** Высыпает содержимое печи на землю — вызывается до того, как блок снят. */
    private void spillFurnace(int x, int y, int z) {
        com.mineclone.world.Furnace f = world.getFurnace(x, y, z);
        if (f == null)
            return;
        dropItem(f.input, x + 0.5f, y + 0.5f, z + 0.5f);
        dropItem(f.fuel, x + 0.5f, y + 0.5f, z + 0.5f);
        dropItem(f.output, x + 0.5f, y + 0.5f, z + 0.5f);
        f.input = null;
        f.fuel = null;
        f.output = null;
    }

    /**
     * Съесть то, что в руке.
     *
     * @return true, если еда пошла в дело — тогда правый клик на этом и
     *         заканчивается и не пытается ничего поставить
     */

    /** Кладёт стопку в открытый сундук; возвращает неразместившийся остаток. */
    private int addToChest(com.mineclone.world.ItemStack s) {
        if (openChest == null || s == null)
            return s == null ? 0 : s.count;
        for (int i = 0; i < openChest.length && s.count > 0; i++) {
            if (openChest[i] != null && openChest[i].stacksWith(s))
                s.count = openChest[i].addUpTo(s.count);
        }
        for (int i = 0; i < openChest.length && s.count > 0; i++) {
            if (openChest[i] == null) {
                openChest[i] = s.copy();
                s.count = 0;
            }
        }
        return s.count;
    }

    private boolean tryEat() {
        com.mineclone.world.ItemStack held = inventory.get(selectedSlot);
        if (held == null || !held.isFood())
            return false;   // не еда — правый клик идёт по обычному пути
        if (!player.canEat())
            return true;    // сыт: клик гасится, но ничего не тратит
        player.eat(held.food.nutrition);
        startHandSwing();
        sound.playOneOf(sounds.uiClick(), 0.5f, 0.75f + 0.1f * (float) Math.random());
        if (gameMode == com.mineclone.world.GameMode.SURVIVAL)
            inventory.removeOne(selectedSlot);
        return true;
    }

    /**
     * Ближайший моб под прицелом: параметрический ray-vs-AABB по всем мобам в
     * радиусе MOB_REACH. Возвращает null, если мобов на линии взгляда нет или
     * блок под прицелом ближе — тогда работают обычные блочные механики.
     */
    private com.mineclone.world.entity.Mob pickAimedMob(Vector3f origin, Vector3f dir) {
        if (mobs.isEmpty())
            return null;

        float blockDist = Float.MAX_VALUE;
        if (lastHit != null) {
            float t = com.mineclone.world.entity.EntityPhysics.rayAabbDistance(
                    origin.x, origin.y, origin.z, dir.x, dir.y, dir.z,
                    lastHit.x, lastHit.y, lastHit.z,
                    lastHit.x + 1f, lastHit.y + 1f, lastHit.z + 1f);
            if (t >= 0f)
                blockDist = t;
        }

        com.mineclone.world.entity.Mob best = null;
        float bestT = MOB_REACH;
        for (com.mineclone.world.entity.Mob m : mobs) {
            if (m.dead)
                continue;          // сквозь падающий труп бьём дальше, в блок
            float t = m.rayHitDistance(origin, dir);
            if (t >= 0f && t < bestT) {
                bestT = t;
                best = m;
            }
        }
        return (best != null && bestT < blockDist) ? best : null;
    }

    private void resetBreakState() {
        breakX = NO_BREAK; breakY = NO_BREAK; breakZ = NO_BREAK;
        breakProgress = 0f;
        breakDigTimer = 0f;
    }

    /**
     * Инструмент в руке, или null.
     *
     * Отдельный метод, потому что «что в руке» спрашивают и скорость копания,
     * и износ, и дроп — и все трое обязаны спрашивать одно и то же.
     */
    private com.mineclone.world.ItemStack heldTool() {
        com.mineclone.world.ItemStack s = inventory.get(selectedSlot);
        return s != null && s.isTool() ? s : null;
    }

    /**
     * Во сколько раз быстрее голых рук идёт копание этого блока.
     *
     * Неподходящий инструмент не помогает вообще: киркой по земле копается
     * ровно так же, как руками. Это и делает выбор инструмента осмысленным.
     */
    private float miningSpeed(BlockType target) {
        com.mineclone.world.ItemStack tool = heldTool();
        if (tool == null || !tool.tool.suits(target))
            return 1f;
        return tool.tool.speed;
    }

    /**
     * Даёт ли блок дроп при текущем инструменте. Камень без кирки крошится,
     * но ничего не оставляет — как в MC.
     */
    private boolean canHarvest(BlockType target) {
        int need = target.requiredToolLevel();
        if (need <= 0)
            return true;
        com.mineclone.world.ItemStack tool = heldTool();
        return tool != null && tool.tool.suits(target) && tool.tool.level >= need;
    }

    /** Сносит очко прочности и убирает инструмент, если он развалился. */
    private void wearHeldTool() {
        if (gameMode != com.mineclone.world.GameMode.SURVIVAL)
            return;
        com.mineclone.world.ItemStack tool = heldTool();
        if (tool == null)
            return;
        if (tool.wear()) {
            inventory.set(selectedSlot, null);
            sound.playOneOf(sounds.uiClick(), 0.7f, 0.7f);
            showCommandToast(tool.tool.displayName + " сломалась");
        }
    }

    private void executeBlockBreak(int x, int y, int z, BlockType target) {
        boolean harvest = canHarvest(target);
        startHandSwing();
        byte targetMeta = world.getBlockMeta(x, y, z);
        // Содержимое забирается ДО setBlock: он же и стирает сундук. Высыпается
        // на землю в любом режиме — это вещи игрока, и полный инвентарь больше
        // не повод оставлять сундук стоять.
        if (target == BlockType.CHEST)
            spillChest(x, y, z);
        if (target == BlockType.FURNACE)
            spillFurnace(x, y, z);
        sound.playOneOfAt(sounds.breakBlock(target), blockSoundPosition(x, y, z),
                0.8f, 0.9f + 0.2f * (float) Math.random());
        // Разбитый лёд возвращается водой: иначе замёрзшее озеро превращалось
        // бы в яму, а под льдом всегда была вода.
        world.setBlock(x, y, z, target == BlockType.ICE ? BlockType.WATER : BlockType.AIR);
        playerStructures.remove(com.mineclone.world.StructureStability.placementKey(x, y, z));
        if (gameMode == com.mineclone.world.GameMode.SURVIVAL) {
            BlockType drop = harvest ? target.getDrop() : BlockType.AIR;
            if (drop != BlockType.AIR)
                dropItem(new com.mineclone.world.ItemStack(drop, 1), x + 0.5f, y + 0.3f, z + 0.5f);
            wearHeldTool();
        }
        float pSky = world.getSkyLight(x, y, z) / (float) com.mineclone.world.Chunk.MAX_LIGHT;
        float pBlk = world.getBlockLightWorld(x, y, z) / (float) com.mineclone.world.Chunk.MAX_LIGHT;
        // Объёмные обломки вместо плоских квадратиков; у крестов (факел,
        // огонь) объёма нет — им остаются обычные частицы.
        if (target.isCross() || target == BlockType.SNOW_LAYER)
            particles.emitBlockBreak(x, y, z, target.particleColor, target.sideTile, pSky, pBlk);
        else if (target.hardness >= 6f)
            debris.spawnShatter(x, y, z, target, pSky, pBlk, Math.min(4f, target.hardness / 6f));
        else
            debris.spawn(x, y, z, target, pSky, pBlk);
        emitNoise(x + 0.5f, y + 0.5f, z + 0.5f, NOISE_BREAK);
        if (target == BlockType.DOOR_CLOSED || target == BlockType.DOOR_OPEN) {
            int otherY = ((targetMeta & 0x4) != 0) ? y - 1 : y + 1;
            BlockType other = world.getBlock(x, otherY, z);
            if (other == BlockType.DOOR_CLOSED || other == BlockType.DOOR_OPEN)
                world.setBlock(x, otherY, z, BlockType.AIR);
        }
        collapseUnsupportedNeighbours(x, y, z);
    }

    private void collapseUnsupportedNeighbours(int x, int y, int z) {
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        java.util.Set<Long> collapsed = new java.util.HashSet<>();
        for (int[] d : dirs) {
            int sx=x+d[0], sy=y+d[1], sz=z+d[2];
            for (int[] p : com.mineclone.world.StructureStability.unstablePlaced(
                    world, playerStructures, sx, sy, sz)) {
                long key = com.mineclone.world.StructureStability.placementKey(p[0], p[1], p[2]);
                if (!collapsed.add(key)) continue;
                BlockType b = world.getBlock(p[0], p[1], p[2]);
                world.setBlock(p[0], p[1], p[2], BlockType.AIR);
                playerStructures.remove(key);
                float sky = world.getSkyLight(p[0], p[1], p[2]) / (float) Chunk.MAX_LIGHT;
                float block = world.getBlockLightWorld(p[0], p[1], p[2]) / (float) Chunk.MAX_LIGHT;
                debris.spawnShatter(p[0], p[1], p[2], b, sky, block, 2.5f);
            }
        }
        if (!collapsed.isEmpty()) {
            emitNoise(x + 0.5f, y + 0.5f, z + 0.5f, NOISE_BREAK * 1.8f);
            invalidateShadows();
        }
    }

    /** Высыпает содержимое сундука на землю — вызывается до того, как блок снят. */
    private void spillChest(int x, int y, int z) {
        com.mineclone.world.ItemStack[] slots = world.getChest(x, y, z);
        if (slots == null)
            return;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null)
                continue;
            dropItem(slots[i], x + 0.5f, y + 0.5f, z + 0.5f);
            slots[i] = null;
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
                case "/weather" -> executeWeatherCommand(parts);
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
                        musicSense.reset();
                    }
                }
                case "/music" -> {
                    if (parts.length >= 2 && parts[1].equalsIgnoreCase("next")) {
                        music.requestNext();
                        showCommandToast("Music: next track");
                    } else {
                        showCommandToast("Music: " + music.status(musicPlayer.position()));
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
                case "/instamine" -> {
                    instantBreak = !instantBreak;
                    resetBreakState();
                    showCommandToast(instantBreak ? "Instamine ON" : "Instamine OFF");
                }
                case "/gamemode", "/gm" -> {
                    if (parts.length >= 2) {
                        String m = parts[1].toLowerCase();
                        if (m.startsWith("c")) {
                            gameMode = com.mineclone.world.GameMode.CREATIVE;
                            showCommandToast("Gamemode: Creative");
                        } else if (m.startsWith("s")) {
                            gameMode = com.mineclone.world.GameMode.SURVIVAL;
                            player.flying = false;
                            showCommandToast("Gamemode: Survival");
                        } else {
                            showCommandToast("Usage: /gamemode <creative|survival>");
                        }
                    } else {
                        showCommandToast("Usage: /gamemode <creative|survival>");
                    }
                }
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
                    // Часы переводятся внутри текущих суток: номер суток
                    // держит фазу луны и график погоды.
                    gameTime = com.mineclone.world.NightSky.withTimeOfDay(gameTime,
                            normalizeGameTime(preset));
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
                    gameTime = com.mineclone.world.NightSky.withTimeOfDay(gameTime,
                            normalizeGameTime(hoursToGameTime(hours)));
                    daylight = computeDaylight();
                    showCommandToast("Time: " + formatGameTime());
                } catch (NumberFormatException e) {
                    showCommandToast("Unknown time preset");
                }
            }
            case "add" -> {
                try {
                    float hours = Float.parseFloat(parts[2]);
                    // Без нормализации: прибавленные сутки — это новая ночь и новая луна.
                    gameTime = Math.max(0f, gameTime + hoursToRadians(hours));
                    daylight = computeDaylight();
                    showCommandToast("Added " + formatHours(hours) + " hours");
                } catch (NumberFormatException e) {
                    showCommandToast("Usage: /time add <hours>");
                }
            }
            default -> showCommandToast("Usage: /time set <preset|0-24>");
        }
    }

    /**
     * {@code /weather clear|cloudy|light|heavy|storm} — погода на десять минут.
     * Переход всё равно плавный: заказанная буря накатывает, а не включается.
     */
    private void executeWeatherCommand(String[] parts) {
        if (parts.length < 2) {
            com.mineclone.world.Weather.Kind k = atmosphere.global.kind();
            showCommandToast("Weather: " + k.name().toLowerCase()
                    + String.format("  wind %.1f", atmosphere.windSpeed()));
            return;
        }
        com.mineclone.world.Weather.Kind kind = switch (parts[1].toLowerCase()) {
            case "clear", "sun" -> com.mineclone.world.Weather.Kind.CLEAR;
            case "cloudy", "clouds" -> com.mineclone.world.Weather.Kind.CLOUDY;
            case "light", "drizzle" -> com.mineclone.world.Weather.Kind.LIGHT;
            case "heavy", "rain", "snow" -> com.mineclone.world.Weather.Kind.HEAVY;
            case "storm", "thunder", "blizzard" -> com.mineclone.world.Weather.Kind.STORM;
            default -> null;
        };
        if (kind == null) {
            showCommandToast("Usage: /weather clear|cloudy|light|heavy|storm");
            return;
        }
        atmosphere.force(kind, 600f);
        showCommandToast("Weather set to " + kind.name().toLowerCase());
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
        // Одна формула на консоль и компас: разъехавшись, они спорили бы
        // о времени суток.
        return Hud.clockText(gameTime);
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

    /**
     * Шумовое событие: всё, что слышно вокруг.
     *
     * Моб, услышавший шум вне поля зрения, идёт проверять точку, а не сразу
     * на игрока — именно это и превращает его из картонки в противника.
     * Погоню шум не прерывает, это решает сам {@link Mob#hearNoise}.
     */
    /**
     * Позиционный звук с учётом геометрии: стены между источником и ухом
     * глушат его. Без этого зомби за каменной стеной слышно так же громко,
     * как зомби в коридоре, и по звуку невозможно понять, открыт ли путь.
     */
    private void playOccluded(java.util.List<String> paths, Vector3f at, float volume, float pitch) {
        int walls = world == null ? 0
                : com.mineclone.audio.SoundOcclusion.solidBetween(world, player.camera.position, at);
        float gain = com.mineclone.audio.SoundOcclusion.gainFor(walls);
        if (gain < 0.06f)
            return;              // за шестью стенами звука фактически нет
        sound.playOneOfAt(paths, at, volume * gain, pitch,
                com.mineclone.audio.SoundOcclusion.muffle(walls));
    }

    private void emitNoise(float x, float y, float z, float loudness) {
        for (com.mineclone.world.entity.Mob m : mobs)
            m.hearNoise(x, y, z, loudness);
    }

    private void emitTorchParticles() {
        int px = (int) Math.floor(player.position.x);
        int py = (int) Math.floor(player.position.y);
        int pz = (int) Math.floor(player.position.z);
        for (int dx = -5; dx <= 5; dx++)
            for (int dy = -3; dy <= 5; dy++)
                for (int dz = -5; dz <= 5; dz++) {
                    BlockType b = world.getBlock(px + dx, py + dy, pz + dz);
                    if (b == BlockType.TORCH)
                        particles.emitTorchEffects(px + dx, py + dy, pz + dz);
                    else if (b == BlockType.FIRE)
                        particles.emitFire(px + dx, py + dy, pz + dz);
                }
    }

    /**
     * Урон от стояния в огне. Считается по обоим блокам, которые занимает
     * игрок: иначе можно стоять ногами в пламени, а голова «не горит».
     */
    private void applyFireDamage(float dt) {
        if (gameMode != com.mineclone.world.GameMode.SURVIVAL || player.isDead())
            return;
        int x = (int) Math.floor(player.position.x);
        int z = (int) Math.floor(player.position.z);
        int y = (int) Math.floor(player.position.y);
        if (world.getBlock(x, y, z) == BlockType.FIRE
                || world.getBlock(x, y + 1, z) == BlockType.FIRE)
            player.takeDamage(FIRE_DAMAGE_PER_SECOND * dt);
    }

    /**
     * Реакция на потерю здоровья: вспышка по краям кадра, толчок камеры и
     * оседающая «тень» потери на сердцах.
     *
     * Всё завязано на изменение health, а не на конкретный источник урона:
     * иначе каждый новый вид урона пришлось бы отдельно вспоминать.
     */
    private void updateDamageFeedback(float dt) {
        if (player.health < lastHealth - 0.01f) {
            damageFlash = 1f;
            damageShake = DAMAGE_SHAKE_TIME;
            healthGhostDelay = HEALTH_GHOST_DELAY;
        }
        if (player.health > lastHealth + 0.01f && healthGhost < player.health)
            healthGhost = player.health;   // лечение подтягивает тень сразу
        lastHealth = player.health;

        damageFlash = Math.max(0f, damageFlash - dt / DAMAGE_FLASH_TIME);
        damageShake = Math.max(0f, damageShake - dt);

        if (healthGhostDelay > 0f)
            healthGhostDelay -= dt;
        else if (healthGhost > player.health)
            healthGhost = Math.max(player.health, healthGhost - dt * 6f);

        slotAnim = Math.min(1f, slotAnim + dt * 2.6f);
    }

    private void updateFootsteps() {
        Vector3f cur = player.position;
        if (lastPos.x == 0 && lastPos.y == 0 && lastPos.z == 0) {
            lastPos.set(cur);
            return;
        }
        // Размах шага. walkedDistance монотонен и на месте не убывает, поэтому
        // без отдельной амплитуды стоящий игрок застывает с раскинутыми ногами.
        float moved = (float) Math.hypot(cur.x - lastPos.x, cur.z - lastPos.z);
        float target = (player.onGround && moved > 0.004f) ? 1f : 0f;
        walkAmount += (target - walkAmount) * Math.min(1f, lastDt * 12f);
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
                stepLeft = !stepLeft;
                dropFootprint(cur.x, cur.y, cur.z, player.camera.yaw, 0.70f, stepLeft);
                float len = Math.max(1e-4f, step);
                kickSnow(cur.x, cur.y, cur.z, dx / len, dz / len, player.isSprinting);
                emitNoise(cur.x, cur.y, cur.z,
                        player.isSprinting ? NOISE_SPRINT : NOISE_WALK);
                // Шаг звучит по поверхности: снег поверх дёрна, мокрая
                // трава в дождь, лёд, раскисшая земля.
                int feetY = (int) Math.floor(cur.y + 0.02f);
                BlockType feet = world.getBlock(bx, feetY, bz);
                int snowLevel = feet == BlockType.SNOW_LAYER ? world.getBlockMeta(bx, feetY, bz) & 0x7 : 0;
                boolean wet = atmosphere.rain() > 0.3f && world.getSkyLight(bx, feetY, bz) >= 12;
                // Геометрический след: покров действительно проминается, а
                // мокрая голая земля становится вязкой грязью.
                TerrainDeformation.footprint(world, bx, feetY, bz,
                        player.isSprinting ? 1.4f : 1f, wet);
                if (under == BlockType.THIN_ICE) {
                    world.setBlock(bx, by, bz, BlockType.WATER);
                    particles.emitWaterSplash(cur.x, cur.y, cur.z,
                            world.getSkyLight(bx, feetY, bz) / (float) Chunk.MAX_LIGHT,
                            world.getBlockLightWorld(bx, feetY, bz) / (float) Chunk.MAX_LIGHT);
                }
                com.mineclone.audio.Sounds.Material mat =
                        com.mineclone.audio.Sounds.stepMaterial(under, feet, snowLevel, wet);
                float vol = com.mineclone.audio.Sounds.stepVolume(mat) * (player.isSprinting ? 1.25f : 1f);
                sound.playOneOfAt(sounds.step(mat), new Vector3f(cur.x, cur.y + 0.15f, cur.z),
                        vol, com.mineclone.audio.Sounds.stepPitch(mat) * (0.95f + 0.1f * (float) Math.random()));
            }
        }
        lastPos.set(cur);
    }

    /**
     * Отпечаток под ногой, если грунт его держит.
     *
     * След остаётся только на снегу и песке — на камне и траве он выглядел бы
     * грязью, а не следом. Ноги чередуются и разнесены в стороны: одна колея
     * по центру читается волочением, а не шагами.
     */
    private void dropFootprint(float x, float y, float z, float yaw, float size,
            boolean left) {
        if (decals == null || world == null)
            return;
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        // Ищем верх опоры чуть ниже ног: на снежном слое стопа стоит на его
        // поверхности, а сам блок начинается ниже.
        int by = (int) Math.floor(y - 0.1f);
        BlockType under = world.getBlock(bx, by, bz);
        float top = by + 1f;
        if (under == BlockType.SNOW_LAYER) {
            // Слой держит толщину в meta: след ложится на его верх.
            top = by + snowSurface(bx, by, bz);
        } else if (!holdsFootprint(under)) {
            return;
        }

        float side = left ? FOOTPRINT_SPREAD : -FOOTPRINT_SPREAD;
        float fx = x + (float) Math.cos(yaw) * side;
        float fz = z - (float) Math.sin(yaw) * side;
        decals.add(fx, top, fz, yaw, size, FOOTPRINT_LIFE, 0.85f, FOOTPRINT_TILE);
    }

    /**
     * Снег из-под ноги — если под ногой снег. Покров не твёрдый и занимает
     * клетку ног, а снежный дёрн лежит под ней, поэтому проверяются обе.
     */
    private void kickSnow(float x, float y, float z, float dirX, float dirZ, boolean hard) {
        if (world == null)
            return;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int feet = (int) Math.floor(y + 0.02f);
        boolean snow = world.getBlock(bx, feet, bz) == BlockType.SNOW_LAYER
                || world.getBlock(bx, feet - 1, bz) == BlockType.SNOWY_GRASS
                || world.getBlock(bx, feet - 1, bz) == BlockType.SNOW_LAYER;
        if (!snow)
            return;
        float sky = world.getSkyLight(bx, feet, bz) / (float) Chunk.MAX_LIGHT;
        float blk = world.getBlockLightWorld(bx, feet, bz) / (float) Chunk.MAX_LIGHT;
        particles.emitSnowKick(x, y, z, dirX, dirZ, hard, sky, blk);
    }

    /** Грунт, на котором след виден: рыхлый и светлый. */
    private static boolean holdsFootprint(BlockType b) {
        return b == BlockType.SNOW_LAYER || b == BlockType.SAND
                || b == BlockType.SNOWY_GRASS;
    }

    /** Высота верхней грани снежного слоя в блоке, в долях блока. */
    private float snowSurface(int x, int y, int z) {
        // Та же формула, что в ChunkMesher.emitLayer: разойдись они — след
        // повиснет в воздухе или утонет в снегу.
        int level = world.getBlockMeta(x, y, z) & 0x7;
        return (level + 1) / 8f;
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
        // The source must be discovered before it becomes loud, otherwise it
        // pops into existence only when the player is already beside it.
        int R = (int) Math.ceil(SoundEngine.SPATIAL_MAX_DISTANCE);
        int bestX = 0, bestY = 0, bestZ = 0;
        int bestDist = Integer.MAX_VALUE;
        int minX = px - R, maxX = px + R, minZ = pz - R, maxZ = pz + R;
        int minY = Math.max(0, py - 2), maxY = Math.min(Chunk.SIZE_Y - 1, py + 4);
        int radiusSq = R * R;
        // Resolve each chunk once, then read its compact block array directly.
        // world.getBlock() here used to repeat floorDiv + hash lookup ~17k times per probe.
        for (int cx = Math.floorDiv(minX, Chunk.SIZE_X); cx <= Math.floorDiv(maxX, Chunk.SIZE_X); cx++)
            for (int cz = Math.floorDiv(minZ, Chunk.SIZE_Z); cz <= Math.floorDiv(maxZ, Chunk.SIZE_Z); cz++) {
                Chunk chunk = world.getChunkIfExists(cx, cz);
                if (chunk == null) continue;
                int baseX = cx * Chunk.SIZE_X, baseZ = cz * Chunk.SIZE_Z;
                int lx0 = Math.max(0, minX - baseX), lx1 = Math.min(Chunk.SIZE_X - 1, maxX - baseX);
                int lz0 = Math.max(0, minZ - baseZ), lz1 = Math.min(Chunk.SIZE_Z - 1, maxZ - baseZ);
                for (int y = minY; y <= maxY; y++)
                    for (int lx = lx0; lx <= lx1; lx++)
                        for (int lz = lz0; lz <= lz1; lz++) {
                            if (chunk.get(lx, y, lz) != BlockType.WATER_FLOW) continue;
                            int x = baseX + lx, z = baseZ + lz;
                            int dx = x - px, dy = y - py, dz = z - pz;
                            int dist = dx * dx + dy * dy + dz * dz;
                            if (dist <= radiusSq && dist < bestDist) {
                                bestDist = dist;
                                bestX = x;
                                bestY = y;
                                bestZ = z;
                            }
                        }
            }
        return bestDist == Integer.MAX_VALUE ? null : blockSoundPosition(bestX, bestY, bestZ);
    }

    /**
     * A stream is ambience, not a sporadic event: keep one positional source
     * alive and let OpenAL's distance curve make it fade in while approaching.
     */
    private void updateWaterFlowSound(float dt) {
        waterFlowProbeTimer -= dt;
        if (waterFlowProbeTimer > 0f)
            return;
        waterFlowProbeTimer = 0.25f;
        waterFlowSoundPosition = findNearbyFlowingWater();
        if (waterFlowSoundPosition == null) {
            sound.stopLoop("water-flow");
            return;
        }
        int walls = com.mineclone.audio.SoundOcclusion.solidBetween(
                world, player.camera.position, waterFlowSoundPosition);
        float gain = com.mineclone.audio.SoundOcclusion.gainFor(walls);
        sound.updateLoopOneOfAt("water-flow", sounds.waterFlow(), waterFlowSoundPosition,
                0.42f * gain, 0.94f, com.mineclone.audio.SoundOcclusion.muffle(walls));
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

    /**
     * Ставит грязные чанки в очередь фонового меш-пула.
     *
     * <p>Раньше этот метод сам строил до восьми мешей за кадр — каждый это
     * обход 32 768 ячеек чанка с расчётом AO на грань, и всё в главном
     * потоке. Именно отсюда брались фризы при копании и рывки при ходьбе.
     * Теперь кадр только обходит список чанков и раскладывает заявки.
     */
    private void updateDirtyMeshes() {
        int submitted = 0;
        for (Chunk c : world.getLoadedChunks()) {
            if (!c.isDirty())
                continue;
            long key = World.key(c.cx, c.cz);
            // Skip chunks whose saved blocks or deferred light flood are still
            // being applied. Meshing during that window bakes stale block/sky
            // light into chunk-shaped patches until a later rebuild catches up.
            if (loader.isPendingGen(key) || loader.hasPendingLightFlood(key))
                continue;
            if (!loader.neighboursReady(c.cx, c.cz))
                continue;
            boolean urgent = Math.abs(c.cx - lastStreamCX) <= URGENT_MESH_RADIUS
                    && Math.abs(c.cz - lastStreamCZ) <= URGENT_MESH_RADIUS;
            if (loader.submitMesh(c.cx, c.cz, key, urgent) && ++submitted >= MESH_SUBMITS_PER_FRAME)
                break;
        }
    }

    // ---------------- rendering ----------------

    /** HDR-путь жив только если драйвер собрал плавающий буфер. */
    private boolean hdrActive() {
        return post != null && post.isReady();
    }

    private boolean shadowsActive() {
        return shaderQuality >= 1 && shadowMap != null && shadowMap.isReady();
    }

    private void render() {
        int sw = window.getWidth(), sh = window.getHeight();
        boolean hdr = hdrActive();
        if (hdr)
            post.resize(sw, sh);
        glViewport(0, 0, sw, sh);

        if (state == State.MENU || state == State.LOADING) {
            renderMenu(hdr, sw, sh);
            return;
        }

        // --- палитра кадра ---------------------------------------------------
        // Та же функция рисует небо фона меню: две копии формулы спорили бы о
        // цвете неба.
        float clouds = atmosphere.cloudiness;
        framePalette.compute(gameTime, daylight, clouds, atmosphere.storm, atmosphere.moonlight, atmosphere.aurora);
        Vector3f skySrgb = framePalette.skySrgb;
        Vector3f skyLin = framePalette.skyLin;
        Vector3f zenith = framePalette.zenith;
        Vector3f horizon = framePalette.horizon;
        Vector3f ground = framePalette.ground;
        Vector3f sunDir = framePalette.sunDir;
        Vector3f lightDir = framePalette.lightDir;
        float moonK = framePalette.moonK;
        Vector3f lightCol = framePalette.lightCol;
        Vector3f skyAmb = framePalette.skyAmb;
        Vector3f groundAmb = framePalette.groundAmb;
        Vector3f sunGlow = framePalette.sunGlow;

        boolean underwater = player.eyeInWater;
        float visibility = underwater ? 1f : atmosphere.visibility;
        float fogEnd   = underwater ? 15f
                : Math.max(18f, renderRadius * Chunk.SIZE_X * 1.05f * visibility);
        float fogStart = underwater ? 2.5f : fogEnd * (0.10f + 0.42f * visibility);
        // Мгла ливня серо-синяя и тёмная, мгла метели — белёсая: снег сам
        // отражает свет, и белая мгла светлее неба над ней.
        Vector3f rainHaze = SkyPalette.linear(new Vector3f(0.40f, 0.44f, 0.50f));
        Vector3f snowHaze = SkyPalette.linear(new Vector3f(0.78f, 0.82f, 0.88f));
        Vector3f hazeCol = rainHaze.lerp(snowHaze, atmosphere.snow)
                .mul(0.06f + 0.94f * Math.max(daylight, 0.05f) * moonK);
        float hazeMix = underwater ? 0f : Math.min(0.92f, (1f - visibility) * 1.15f);
        Vector3f fogCol = underwater ? new Vector3f(0.010f, 0.045f, 0.130f)
                : new Vector3f(horizon).lerp(hazeCol, Math.min(1f, hazeMix * 1.1f));

        float targetFov = player.eyeInWater  ? fovDegrees * 0.85f
                : player.isSprinting ? fovDegrees + 10f
                : fovDegrees;
        currentFov += (targetFov - currentFov) * (1f - (float) Math.exp(-lastDt * 8f));
        Matrix4f proj = player.camera.getProjection(window.getAspect(), currentFov, 0.1f, 600f);
        Vector3f eye = cameraEye();
        Matrix4f view = cameraView(eye);

        // --- сборка освещения кадра ------------------------------------------
        lighting.camPos.set(eye);
        lighting.lightDir.set(lightDir);
        lighting.lightColor.set(lightCol);
        lighting.skyLight.set(skyAmb);
        lighting.groundLight.set(groundAmb);
        lighting.torchColor.set(1.55f, 0.88f, 0.42f);
        lighting.ambientColor.set(0.030f, 0.034f, 0.052f).mul(0.65f + 0.35f * daylight);
        lighting.fogColor.set(fogCol);
        lighting.fogSunColor.set(lightCol).mul(underwater ? 0f : 0.22f);
        lighting.fogStart = fogStart;
        lighting.fogEnd = fogEnd;
        lighting.brightness = brightness;
        lighting.time = totalTime;
        lighting.linearOut = hdr ? 1f : 0f;
        lighting.waterTint.set(0.34f, 0.66f, 0.92f);
        applyHeightFog(underwater, daylight, horizon);
        applyHeldLight();
        applyVoxelBounce();
        lighting.shadowTaps = shaderQuality >= 2 ? 2 : 1;
        lighting.shadowStrength = SunLight.shadowStrength(gameTime) * (1f - clouds * 0.7f) * moonK;
        lighting.shadows = shadowsActive() && lighting.shadowStrength > 0.002f && !underwater;
        if (shadowMap != null) {
            int size = shadowMap.getSize();
            lighting.shadowTexel = 1f / size;
            lighting.shadowSplit = SunLight.CASCADE_SPLIT;
            lighting.shadowFar = Math.min(SunLight.CASCADE1_RADIUS * 0.85f, fogEnd);
            lighting.shadowBias0 = SunLight.texelWorldSize(SunLight.CASCADE0_RADIUS, size) * 2.2f;
            lighting.shadowBias1 = SunLight.texelWorldSize(SunLight.CASCADE1_RADIUS, size) * 2.2f;
        }

        int pcx = (int) Math.floor(player.position.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(player.position.z / Chunk.SIZE_Z);

        // --- проход карты теней ----------------------------------------------
        profiler.begin(FrameProfiler.Phase.SHADOW, GLFW.glfwGetTime());
        if (lighting.shadows) {
            shadowMap.update(eye, player.camera.forward(), lightDir);
            renderShadowPass(pcx, pcz, sw, sh);
            // Матрицы берём ПОСЛЕ прохода: у непереснятого каскада она осталась
            // прежней, и сэмплить надо именно ею.
            lighting.shadowMat0.set(shadowMap.matrix(0));
            lighting.shadowMat1.set(shadowMap.matrix(1));
            shadowMap.endFrame();
        }

        // --- сцена ------------------------------------------------------------
        profiler.begin(FrameProfiler.Phase.WORLD, GLFW.glfwGetTime());
        Vector3f clear = underwater ? fogCol : skyLin;
        if (hdr) {
            post.begin(clear.x, clear.y, clear.z);
        } else {
            Vector3f c = underwater ? new Vector3f(0.04f, 0.14f, 0.55f) : skySrgb;
            glClearColor(c.x, c.y, c.z, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        }
        if (shadowMap != null)
            shadowMap.bind();

        if (!underwater) {
            SkyRenderer.Dome dome = skyDome;
            dome.zenith.set(zenith);
            dome.horizon.set(horizon);
            dome.ground.set(ground);
            dome.sunGlow.set(sunGlow);
            dome.lightDir.set(lightDir);
            dome.cloudiness = clouds;
            // Сияние не пробивается под землю: в пещере неба не видно.
            dome.aurora = atmosphere.aurora * skyExposure();
            dome.time = totalTime;
            dome.haze.set(fogCol);
            dome.hazeMix = hazeMix;
            dome.linearOut = hdr ? 1f : 0f;
            skyRenderer.renderDome(scratchLight.set(proj).mul(view).invert(), dome);
            // Спрайты неба — до геометрии и без записи в глубину.
            glDepthMask(false);
            skyRenderer.render(proj, view, player.position, gameTime, daylight, totalTime,
                    hdr ? 1f : 0f, atmosphere.moonPhase, clouds, hazeMix);
            glDepthMask(true);
        }

        chunkShader.bind();
        chunkShader.setMat4("uProjection", proj);
        chunkShader.setMat4("uView", view);
        chunkShader.setInt("uAtlas", 0);
        lighting.apply(chunkShader);
        applyWindSway(chunkShader, underwater);
        atlas.bind(0);

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

        // Мобы — после непрозрачных чанков и до воды: вода должна блендиться
        // поверх них, а не наоборот.
        if (world != null)
            mobRenderer.render(proj, view, mobs, world, daylight, lighting);
        if (debrisRenderer != null)
            debrisRenderer.render(proj, view, debris, atlas, lighting, daylight);
        if (itemRenderer != null && world != null)
            itemRenderer.render(proj, view, items, world, atlas, lighting, daylight);
        if (viewMode != ViewMode.FIRST && world != null) {
            int px = (int) Math.floor(player.position.x);
            int py = (int) Math.floor(player.position.y + 1f);
            int pz = (int) Math.floor(player.position.z);
            playerRenderer.render(proj, view, player.position, player.camera.yaw,
                    player.camera.pitch, walkedDistance, walkAmount, handSwing, lighting,
                    world.getSkyLight(px, py, pz) / (float) Chunk.MAX_LIGHT * daylight,
                    world.getBlockLightWorld(px, py, pz) / (float) Chunk.MAX_LIGHT);
        }

        // --- Transparent (water) pass ---
        int reflectionTex = hdr ? post.captureSceneColor() : 0;
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        waterShader.bind();
        waterShader.setMat4("uProjection", proj);
        waterShader.setMat4("uView", view);
        waterShader.setInt("uAtlas", 0);
        waterShader.setInt("uScene", 6);
        waterShader.setFloat("uSsrOn", reflectionTex != 0 && shaderQuality >= 2 ? 1f : 0f);
        lighting.apply(waterShader);
        waterShader.setVec3("uWaterTint", lighting.waterTint);
        atlas.bind(0);
        if (reflectionTex != 0) {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE6);
            glBindTexture(GL_TEXTURE_2D, reflectionTex);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        }

        visibleWaterKeys.clear();
        for (int cx = pcx - renderRadius; cx <= pcx + renderRadius; cx++) {
            for (int cz = pcz - renderRadius; cz <= pcz + renderRadius; cz++) {
                float wx = cx * Chunk.SIZE_X, wz = cz * Chunk.SIZE_Z;
                if (!frustum.testAab(wx, 0, wz, wx + Chunk.SIZE_X, Chunk.SIZE_Y, wz + Chunk.SIZE_Z))
                    continue;
                long key = World.key(cx, cz);
                if (waterMeshes.containsKey(key))
                    visibleWaterKeys.add(key);
            }
        }
        visibleWaterKeys.sort((ka, kb) -> {
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
        for (Long k : visibleWaterKeys) {
            int cx = (int) (k >> 32), cz = (int) (k & 0xFFFFFFFFL);
            waterShader.setMat4("uModel", scratchModel.translation(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z));
            Mesh wm = waterMeshes.get(k);
            if (wm != null)
                wm.render();
        }
        waterShader.unbind();
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        // --- End water pass ---

        // Осадки — после воды: дождь над озером должен лечь поверх её глади.
        if (precipitation != null && !underwater && world != null
                && (atmosphere.snowfall() > 0.01f || atmosphere.rain() > 0.01f)) {
            precipitation.updateField(world, eye);
            Vector3f flake = PrecipitationRenderer.flakeColor(skyAmb, lightCol, fogCol);
            Vector3f drop = PrecipitationRenderer.dropColor(flake);
            precipitation.render(proj, view, eye, totalTime, atmosphere.windX, atmosphere.windZ,
                    atmosphere.snowfall(), atmosphere.rain(), atmosphere.storm,
                    flake, drop, hdr ? 1f : 0f);
        }

        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL); // restore before outline/particles/UI

        // Рамка выделения доезжает до нового блока и плавно гаснет, а не
        // прыгает: при ведении прицела по стене скачки читались мерцанием.
        outlineAnim.update(lastDt, state == State.PLAYING && !photoMode ? outlineTarget() : null);
        if (outlineAnim.visible())
            outline.renderBox(proj, view, outlineAnim.box(), eye, outlineAnim.alpha(),
                    OutlineAnimator.pulse(totalTime), hdr ? 1f : 0f);
        if (ropeRenderer != null)
            ropeRenderer.render(world, player.position, proj, view, lastDt,
                    atmosphere.windX, atmosphere.windZ, hdr ? 1f : 0f);
        if (chargingThrow && throwCharge > 0.02f) {
            Vector3f start = new Vector3f(player.camera.position).add(0f, -0.3f, 0f);
            Vector3f fwd = player.camera.forward();
            float speed = THROW_SPEED * (0.45f + 1.15f * throwCharge);
            Vector3f launch = new Vector3f(fwd).mul(speed)
                    .add(player.velocity.x, THROW_LIFT * (0.65f + throwCharge * 0.35f), player.velocity.z);
            trajectoryRenderer.render(proj, view, start, launch, hdr ? 1f : 0f, throwCharge);
        }
        updateHint();

        if (state == State.PLAYING && breakX != NO_BREAK && breakProgress > 0f) {
            int stage = Math.min(9, (int) (breakProgress * 10f));
            breakOverlay.render(proj, view, breakX, breakY, breakZ, stage, atlas, hdr ? 1f : 0f);
        }

        // Следы кладутся до частиц: они лежат на грани, а частицы летают
        // над ней, и порядок между ними определяет глубина, а не удача.
        if (decals != null)
            decals.render(proj, view, atlas.getTextureId(), hdr);

        Vector3f camRight = player.camera.right();
        Vector3f camUp = new Vector3f(camRight).cross(player.camera.forward()).normalize();
        particles.render(proj, view, camRight, camUp, atlas,
                daylight, 0.04f + 0.18f * daylight, brightness, hdr ? 1f : 0f);

        // Глубина мира снимается ДО руки: рука чистит z-буфер под себя, а
        // туман и глубина резкости обязаны видеть мир, а не пустоту.
        if (hdr)
            post.resolveDepth();

        boolean menuOpen = state == State.PAUSED || state == State.CREATIVE_MENU
                || state == State.CHEST_MENU || state == State.FURNACE_MENU;
        float blurStep = lastDt / 0.25f;
        menuBlur = menuOpen ? Math.min(1f, menuBlur + blurStep) : Math.max(0f, menuBlur - blurStep);
        boolean handDrawn = false;
        if ((state == State.PLAYING || menuOpen) && !photoMode) {
            int ex = (int) Math.floor(player.position.x);
            int ey = (int) Math.floor(player.position.y + 0.6f); // eye level
            int ez = (int) Math.floor(player.position.z);
            float skyFrac = world.getSkyLight(ex, ey, ez) / (float) Chunk.MAX_LIGHT;
            float blockFrac = world.getBlockLightWorld(ex, ey, ez) / (float) Chunk.MAX_LIGHT;
            // Рука от первого лица и модель от третьего — взаимоисключающие:
            // рука рисуется поверх чистого z-буфера и в третьем лице висела
            // бы отдельным куском мяса посреди экрана.
            if (viewMode == ViewMode.FIRST) {
                com.mineclone.world.ItemStack heldStack = inventory.get(selectedSlot);
                com.mineclone.world.ToolType heldTool =
                        heldStack != null && heldStack.isTool() ? heldStack.tool : null;
                heldItemRenderer.render(atlas, currentBlock(), heldTool,
                        window.getAspect(), currentFov,
                        equipProgress, handSwing, walkedDistance, player.eyeInWater, viewBobbing,
                        daylight, brightness, skyFrac, blockFrac, hdr ? 1f : 0f,
                        heldStack != null ? heldStack.condition() : 1f, inspect, inspectSpin);
                handDrawn = true;
            }
        }

        // --- пост-обработка ----------------------------------------------------
        profiler.begin(FrameProfiler.Phase.POST, GLFW.glfwGetTime());
        if (hdr) {
            fillPostSettings(underwater, lightCol, daylight, proj, view, eye, lightDir, skyAmb);
            // Маска руки нужна только глубине резкости: без неё блит не делаем.
            if (handDrawn && postSettings.dofStrength > 0f)
                post.resolveHandDepth();
            post.resolveColor();
            post.render(postSettings, sunScreenUv(proj, view, sunDir, underwater));
        }

        // Мир готов, интерфейса ещё нет: ровно этот кадр и видно сквозь стекло.
        profiler.begin(FrameProfiler.Phase.HUD, GLFW.glfwGetTime());
        if (backdrop != null && ui != null) {
            backdrop.capture(sw, sh);
            ui.setBackdrop(backdrop.texture());
            captureWorldIcon(sw, sh);
        }
        drawUi();
    }

    /**
     * Превью мира для списка миров. Кадр берётся из того же резольва, что и
     * стекло: мир уже есть, интерфейса ещё нет.
     */
    private void captureWorldIcon(int sw, int sh) {
        if (!iconRequested || world == null || (state != State.PLAYING && state != State.PAUSED))
            return;
        iconRequested = false;
        int[] px = thumbnail.capture(backdrop.resolvedFramebuffer(), sw, sh);
        if (px != null)
            save.saveIconAsync(worldId, Thumbnail.WIDTH, Thumbnail.HEIGHT, px);
    }

    /**
     * Где на самом деле стоит камера.
     *
     * В третьем лице она отъезжает от глаз назад (или вперёд, если смотрим в
     * лицо) и упирается в первую твёрдую преграду — иначе камера проваливается
     * сквозь стену и игрок видит мир изнутри блока.
     */
    /**
     * Включает и выключает фоторежим.
     *
     * Камера начинает оттуда, где стоял глаз игрока, и с тем же поворотом:
     * иначе при включении кадр прыгает и непонятно, куда смотришь.
     */
    private void togglePhotoMode() {
        photoMode = !photoMode;
        if (photoMode) {
            photoPos.set(player.camera.position);
            photoYaw = player.camera.yaw;
            photoPitch = player.camera.pitch;
            photoFocus = focusDistance();
            showCommandToast("Фоторежим: F6 выход, колесо — фокус");
        } else {
            showCommandToast("Фоторежим выключен");
        }
    }

    /**
     * Свободная камера: движение в плоскости взгляда плюс вертикаль.
     *
     * Читает клавиши напрямую, минуя игрока: тот в фоторежиме заморожен, и
     * пропускать его ввод через обычное управление значило бы двигать заодно
     * и его.
     */
    private void updatePhotoCamera(float dt) {
        float sens = 0.0025f * mouseSensitivity;
        photoYaw += (float) (input.getDx() * sens);
        photoPitch -= (float) (input.getDy() * sens) * (invertMouseY ? -1f : 1f);
        float limit = (float) Math.toRadians(89.5);
        photoPitch = Math.max(-limit, Math.min(limit, photoPitch));

        float speed = PHOTO_SPEED * dt;
        if (input.down(KeyBindings.Action.SPRINT))
            speed *= PHOTO_FAST;
        if (input.keyDown(GLFW.GLFW_KEY_LEFT_ALT))
            speed *= PHOTO_SLOW;

        float sin = (float) Math.sin(photoYaw), cos = (float) Math.cos(photoYaw);
        float cp = (float) Math.cos(photoPitch), sp = (float) Math.sin(photoPitch);
        Vector3f fwd = new Vector3f(cp * sin, sp, -cp * cos);
        Vector3f right = new Vector3f(cos, 0f, sin);
        if (input.down(KeyBindings.Action.FORWARD))
            photoPos.add(new Vector3f(fwd).mul(speed));
        if (input.down(KeyBindings.Action.BACK))
            photoPos.sub(new Vector3f(fwd).mul(speed));
        if (input.down(KeyBindings.Action.RIGHT))
            photoPos.add(new Vector3f(right).mul(speed));
        if (input.down(KeyBindings.Action.LEFT))
            photoPos.sub(new Vector3f(right).mul(speed));
        if (input.down(KeyBindings.Action.JUMP))
            photoPos.y += speed;
        if (input.down(KeyBindings.Action.DESCEND))
            photoPos.y -= speed;

        // Колесо ведёт фокус по геометрической шкале: у близкого фокуса шаг
        // мелкий, у дальнего крупный — иначе на сорока блоках его не довести.
        double scroll = input.getScroll();
        if (scroll != 0)
            photoFocus = Math.max(PHOTO_FOCUS_MIN,
                    Math.min(PHOTO_FOCUS_MAX, photoFocus * (float) Math.pow(1.12, scroll)));
    }

    /** Дистанция до блока под прицелом или дефолт, если прицел в небе. */
    private float focusDistance() {
        if (lastHit == null)
            return 12f;
        Vector3f eye = photoMode ? photoPos : player.camera.position;
        return Math.max(PHOTO_FOCUS_MIN, (float) Math.sqrt(
                (lastHit.x + 0.5f - eye.x) * (lastHit.x + 0.5f - eye.x)
                + (lastHit.y + 0.5f - eye.y) * (lastHit.y + 0.5f - eye.y)
                + (lastHit.z + 0.5f - eye.z) * (lastHit.z + 0.5f - eye.z)));
    }

    private Vector3f cameraEye() {
        if (photoMode)
            return new Vector3f(photoPos);
        Vector3f eye = new Vector3f(player.camera.position);
        if (viewMode == ViewMode.FIRST || world == null)
            return eye;
        Vector3f dir = player.camera.forward().negate();
        if (viewMode == ViewMode.THIRD_FRONT)
            dir.negate();
        float dist = THIRD_PERSON_DISTANCE;
        float step = 0.1f;
        for (float t = step; t <= dist; t += step) {
            int bx = (int) Math.floor(eye.x + dir.x * t);
            int by = (int) Math.floor(eye.y + dir.y * t);
            int bz = (int) Math.floor(eye.z + dir.z * t);
            if (world.getBlock(bx, by, bz).solid) {
                dist = Math.max(0f, t - 0.25f);
                break;
            }
        }
        return eye.add(dir.mul(dist));
    }

    /** Матрица вида из текущей точки камеры: смотрим всегда на глаза игрока. */
    private Matrix4f cameraView(Vector3f eye) {
        Matrix4f view;
        if (photoMode) {
            float cp = (float) Math.cos(photoPitch), sp = (float) Math.sin(photoPitch);
            float sin = (float) Math.sin(photoYaw), cos = (float) Math.cos(photoYaw);
            Vector3f target = new Vector3f(eye).add(cp * sin, sp, -cp * cos);
            return new Matrix4f().lookAt(eye, target, new Vector3f(0, 1, 0));
        }
        if (viewMode == ViewMode.FIRST) {
            // Копия: матрицу камеры нельзя портить тряской, её переиспользуют.
            // Крен, кивок и проседание — в осях самой камеры, поэтому слева.
            view = new Matrix4f()
                    .rotationZ(cameraMotion.roll())
                    .rotateX(-cameraMotion.pitch())
                    .translate(0f, -cameraMotion.dip(), 0f)
                    .mul(player.camera.getView());
        } else {
            Vector3f target = new Vector3f(player.camera.position);
            if (viewMode == ViewMode.THIRD_BACK)
                target.add(player.camera.forward());
            view = new Matrix4f().lookAt(eye, target, new Vector3f(0, 1, 0));
        }
        return applyShake(view);
    }

    /**
     * Толчок камеры после удара. Поворотом, а не сдвигом: сдвиг на дистанции
     * вытянутой руки почти не виден, а крен читается мгновенно и не рискует
     * загнать камеру внутрь блока.
     */
    private Matrix4f applyShake(Matrix4f view) {
        if (damageShake <= 0f)
            return view;
        float k = damageShake / DAMAGE_SHAKE_TIME;
        float amp = k * k * 0.05f;
        return view.rotateZ((float) Math.sin(totalTime * 47.0) * amp)
                   .rotateX((float) Math.sin(totalTime * 31.0) * amp * 0.6f);
    }

    /**
     * Низовой туман: копится ночью и на рассвете, стелется по низинам и
     * усиливается дождём. Днём его нет — иначе мир постоянно выглядит
     * замыленным, а туман перестаёт быть событием.
     */
    private void applyHeightFog(boolean underwater, float day, Vector3f horizon) {
        // С объёмным туманом в посте старый аналитический не нужен: вдвоём
        // они сложились бы в двойную мглу.
        if (underwater || volumetricFog()) {
            lighting.heightFogDensity = 0f;
            return;
        }
        float night = Math.max(0f, Math.min(1f, (0.55f - day) / 0.45f));
        float m = night * night;                       // держится у самой земли до рассвета
        m *= 0.65f + 0.35f * atmosphere.precipitation; // в дождь гуще
        lighting.heightFogDensity = 0.030f * m;
        lighting.heightFogTop = World.SEA_LEVEL + 15f;
        lighting.heightFogDepth = 13f;
        // Туман светится сам: ночью он заметно светлее земли под ним,
        // иначе в темноте он неотличим от общей черноты.
        lighting.heightFogColor.set(horizon).mul(1.15f).add(0.010f, 0.012f, 0.018f);
    }

    /** Бокс рамки для блока под прицелом, или null — рамка не нужна. */
    private float[] outlineTarget() {
        if (lastHit == null || world == null)
            return null;
        BlockType ht = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
        float[] local;
        if (ht == BlockType.DOOR_CLOSED || ht == BlockType.DOOR_OPEN)
            local = BlockOutline.doorBox(world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z),
                    ht == BlockType.DOOR_OPEN);
        else if (ht.solid)
            local = new float[] { 0f, 0f, 0f, 1f, 1f, 1f };
        else
            return null;
        return new float[] {
                lastHit.x + local[0], lastHit.y + local[1], lastHit.z + local[2],
                lastHit.x + local[3], lastHit.y + local[4], lastHit.z + local[5] };
    }

    /**
     * Подсказка у прицела: появляется и гаснет плавно, а при уходе прицела
     * гаснет со старым текстом — иначе на долю секунды мелькала бы пустая плашка.
     */
    private void updateHint() {
        ContextHint.Hint target = null;
        if (state == State.PLAYING && !photoMode && !consoleOpen && world != null) {
            BlockType block = lastHit != null ? world.getBlock(lastHit.x, lastHit.y, lastHit.z) : null;
            target = ContextHint.forTarget(block, inventory.get(selectedSlot), player.canEat(),
                    aimingAtMob);
        }
        if (target != null) {
            shownHint = target;
            hintAlpha = Math.min(1f, hintAlpha + lastDt / 0.15f);
        } else {
            hintAlpha = Math.max(0f, hintAlpha - lastDt / 0.2f);
        }
    }

    /**
     * Крен и инерция камеры. Скорости берутся в осях взгляда: вперёд и вбок,
     * а не в мировых X/Z — наклон должен зависеть от того, куда идёшь
     * относительно взгляда, а не по сторонам света.
     *
     * Настройка «покачивание при ходьбе» выключает и это: кого укачивает от
     * шага, того укачает и от крена.
     */
    private void updateCameraMotion(float dt, float landingDistance) {
        float yaw = player.camera.yaw;
        float yawRate = dt > 0f ? (yaw - lastCameraYaw) / dt : 0f;
        lastCameraYaw = yaw;
        float sin = (float) Math.sin(yaw), cos = (float) Math.cos(yaw);
        float forward = player.velocity.x * sin - player.velocity.z * cos;
        float strafe = player.velocity.x * cos + player.velocity.z * sin;
        boolean enabled = viewBobbing && !photoMode && !player.flying && viewMode == ViewMode.FIRST;
        cameraMotion.update(dt, Math.max(-12f, Math.min(12f, yawRate)), strafe, forward,
                player.inWater ? 0f : landingDistance, enabled);
    }

    /**
     * Мороз копится от долгого холода и сходит у огня. Считается и в меню
     * инвентаря: мир в это время живёт дальше, и стужа тоже.
     */
    private void updateFrost(float dt) {
        if (world == null)
            return;
        int x = (int) Math.floor(player.position.x);
        int y = (int) Math.floor(player.position.y + 1f);
        int z = (int) Math.floor(player.position.z);
        var biome = world.biomes.biomeAt(x, z);
        float cold = Frost.coldness(biome, player.position.y, daylight, atmosphere.snowfall(),
                atmosphere.blizzard(), world.getSkyLight(x, y, z), player.inWater);
        frost.update(dt, cold, world.getBlockLightWorld(x, y, z));
    }

    /**
     * Дуга на краю экрана в сторону звука. Громкость уже с поправкой на
     * расстояние и стены: зомби за тремя блоками камня не должен сигналить
     * так же, как зомби за спиной в открытом поле.
     */
    private void cueSound(Vector3f at, float loudness, boolean danger) {
        if (world == null || at == null)
            return;
        Vector3f ear = player.camera.position;
        float dist = at.distance(ear);
        if (dist > SOUND_CUE_RANGE)
            return;
        float gain = com.mineclone.audio.SoundOcclusion.factor(world, ear, at);
        float strength = loudness * (1f - dist / SOUND_CUE_RANGE) * (0.35f + 0.65f * gain);
        soundCues.add(ear.x, ear.z, player.camera.yaw, at.x, at.z, strength, danger);
    }

    /**
     * Насколько голова под открытым небом, 0..1. Сияние, звёзды и мгла над
     * миром не должны просвечивать в пещеру.
     */
    private float skyExposure() {
        if (world == null)
            return 1f;
        Vector3f e = player.camera.position;
        return world.getSkyLight((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z))
                / (float) Chunk.MAX_LIGHT;
    }

    /**
     * Ветер для листвы. В штиль крона всё равно чуть дышит — совсем
     * неподвижный лес выглядит нарисованным.
     */
    private void applyWindSway(Shader s, boolean underwater) {
        float speed = atmosphere.windSpeed();
        float sway = underwater ? 0f : Math.min(1.8f, 0.30f + speed * 0.34f);
        float inv = speed > 1e-3f ? 1f / speed : 0f;
        s.setFloat("uWindSway", sway);
        if (inv > 0f)
            s.setVec2("uWindDir", atmosphere.windX * inv, atmosphere.windZ * inv);
        else
            s.setVec2("uWindDir", 0.8f, 0.6f);
        if (player != null) {
            s.setVec3("uInteractorPos", player.position.x, player.position.y + 0.8f, player.position.z);
            s.setFloat("uInteractorRadius", underwater ? 0f : 1.35f);
        } else {
            s.setVec3("uInteractorPos", 0f, -1000f, 0f);
            s.setFloat("uInteractorRadius", 0f);
        }
        com.mineclone.world.entity.Mob nearest = null;
        float nearestSq = 36f;
        for (com.mineclone.world.entity.Mob mob : mobs) {
            if (mob.dead || mob.type.width < 0.55f)
                continue;
            float ds = mob.position.distanceSquared(player.position);
            if (ds < nearestSq) {
                nearestSq = ds;
                nearest = mob;
            }
        }
        if (nearest != null) {
            s.setVec3("uMobInteractorPos", nearest.position.x,
                    nearest.position.y + nearest.type.height * 0.45f, nearest.position.z);
            s.setFloat("uMobInteractorRadius", underwater ? 0f : 1.0f + nearest.type.width * 0.55f);
        } else {
            s.setVec3("uMobInteractorPos", 0f, -1000f, 0f);
            s.setFloat("uMobInteractorRadius", 0f);
        }
    }

    /**
     * Источник света в руке. Без него пещеры непроходимы: блочный свет идёт
     * только от установленных блоков, поэтому факел в инвентаре под землёй
     * до сих пор ничего не давал.
     *
     * Свет вешается чуть впереди и ниже глаз — если посадить его ровно в
     * камеру, пол под ногами освещается по касательной и выглядит плоским.
     */
    private void applyHeldLight() {
        BlockType held = currentBlock();
        if (held == null || held.emittedLight <= 0 || state == State.MENU) {
            lighting.pointColor.set(0f, 0f, 0f);
            return;
        }
        Vector3f fwd = player.camera.forward();
        lighting.pointPos.set(player.camera.position)
                .add(fwd.x * 0.4f, -0.25f, fwd.z * 0.4f);
        lighting.pointRadius = held.emittedLight;
        float[] c = held.particleColor;
        lighting.pointColor.set(c[0], c[1], c[2]).mul(2.4f);
    }

    /** Ищет ближайший цветной эмиттер; одна проба даёт локальный Voxel-GI без 3D-текстуры. */
    private void applyVoxelBounce() {
        voxelBounceProbeTimer -= lastDt;
        if (voxelBounceProbeTimer > 0f)
            return;
        voxelBounceProbeTimer = 0.20f;
        lighting.bounceColor.set(0f);
        lighting.bouncePos.set(0f, -1000f, 0f);
        if (world == null) return;
        int ox = (int) Math.floor(player.position.x), oy = (int) Math.floor(player.position.y);
        int oz = (int) Math.floor(player.position.z);
        float best = Float.MAX_VALUE;
        BlockType bestBlock = null;
        for (int x = ox - 7; x <= ox + 7; x++)
            for (int y = Math.max(1, oy - 5); y <= Math.min(Chunk.SIZE_Y - 2, oy + 5); y++)
                for (int z = oz - 7; z <= oz + 7; z++) {
                    BlockType b = world.getBlock(x, y, z);
                    if (b.emittedLight <= 0) continue;
                    float ds = (x + 0.5f - player.position.x) * (x + 0.5f - player.position.x)
                            + (y + 0.5f - player.position.y) * (y + 0.5f - player.position.y)
                            + (z + 0.5f - player.position.z) * (z + 0.5f - player.position.z);
                    if (ds < best) {
                        best = ds; bestBlock = b;
                        lighting.bouncePos.set(x + 0.5f, y + 0.5f, z + 0.5f);
                    }
                }
        if (bestBlock != null) {
            float[] c = bestBlock.particleColor;
            float energy = bestBlock == BlockType.LAVA ? 1.05f : 0.58f;
            lighting.bounceColor.set(c[0], c[1], c[2]).mul(energy);
            lighting.bounceRadius = 8f;
        }
    }

    /**
     * Музыка: ситуация кадра — режиссёру, его команды — плееру.
     *
     * Меню и загрузка — одна сцена: экран загрузки стоит над фоном меню, и
     * трек меню доигрывает над ним, а не обрывается на нажатии «Играть».
     * Время суток в меню — у фона, а не у мира.
     */
    private void updateMusic(float dt) {
        if (music == null)
            return;
        com.mineclone.audio.MusicSituation s;
        if (state == State.MENU || state == State.LOADING || world == null) {
            s = com.mineclone.audio.MusicSituation.menu(
                    com.mineclone.audio.MusicSituation.dayPart(menuBackground.gameTime()));
        } else {
            s = musicSense.sample(dt, state == State.DEAD
                            ? com.mineclone.audio.MusicSituation.Scene.DEAD
                            : com.mineclone.audio.MusicSituation.Scene.WORLD,
                    state == State.PAUSED, world, player, mobs, gameTime);
        }
        music.update(dt, s, musicPlayer, musicPlayer);
    }

    /**
     * Экранная позиция солнца в 0..1 для god rays, или null — солнца не видно
     * и лучи считать не из чего.
     */
    private float[] sunScreenUv(Matrix4f proj, Matrix4f view, Vector3f sunDir, boolean underwater) {
        return sunScreenUv(proj, view, player.camera.position, sunDir, daylight, underwater);
    }

    private float[] sunScreenUv(Matrix4f proj, Matrix4f view, Vector3f eye, Vector3f sunDir,
                                float day, boolean underwater) {
        if (underwater || shaderQuality < 1 || day < 0.04f || sunDir.y <= 0.02f)
            return null;
        Vector3f p = new Vector3f(eye).add(new Vector3f(sunDir).mul(200f));
        Vector4f clip = new Matrix4f(proj).mul(view).transform(new Vector4f(p.x, p.y, p.z, 1f));
        if (clip.w <= 1e-4f)
            return null;
        float nx = clip.x / clip.w, ny = clip.y / clip.w;
        if (nx < -1.7f || nx > 1.7f || ny < -1.7f || ny > 1.7f)
            return null;
        return new float[] { nx * 0.5f + 0.5f, ny * 0.5f + 0.5f };
    }

    /** Объёмный туман живёт в посте: нужен HDR и хотя бы Fancy. */
    private boolean volumetricFog() {
        return hdrActive() && shaderQuality >= 1 && !player.eyeInWater;
    }

    private void fillPostSettings(boolean underwater, Vector3f lightCol, float day,
                                  Matrix4f proj, Matrix4f view, Vector3f eye, Vector3f lightDir,
                                  Vector3f skyAmb) {
        boolean fancy = shaderQuality >= 1;
        postSettings.bloomStrength = !fancy ? 0f : (shaderQuality >= 2 ? 0.60f : 0.42f);
        postSettings.bloomThreshold = 1.05f;
        postSettings.rayStrength = fancy ? 0.75f * day : 0f;
        postSettings.rayDensity = 0.62f;
        postSettings.rayDecay = 0.945f;
        Vector3f rc = new Vector3f(lightCol);
        float m = Math.max(0.001f, Math.max(rc.x, Math.max(rc.y, rc.z)));
        postSettings.rayColor.set(rc.mul(1f / m));
        postSettings.exposure = 0.95f * advancedFeedback.exposure();
        postSettings.vignette = 0.30f;
        postSettings.underwater = underwater ? 1f : 0f;
        postSettings.night = 1f - Math.min(1f, day * 3.2f);
        postSettings.saturation = 1.02f;
        postSettings.damage = damageFlash * damageFlash;
        // Глубина резкости: в фоторежиме — по фокусу камеры; за открытым окном
        // и при осмотре предмета — мир целиком уходит в размытие, резким
        // остаётся только то, что у самых глаз. В обычной игре её нет: она
        // мешает целиться и стоит двенадцати выборок на пиксель.
        if (photoMode) {
            postSettings.dofStrength = PHOTO_DOF;
            postSettings.dofFocus = photoFocus;
            postSettings.dofRange = PHOTO_RANGE;
        } else {
            float k = Math.max(menuBlur, inspect);
            postSettings.dofStrength = k <= 0.001f ? 0f
                    : Math.max(menuBlur * MENU_DOF, inspect * INSPECT_DOF);
            postSettings.dofFocus = 0.3f;
            postSettings.dofRange = 0.25f;
        }
        postSettings.near = 0.1f;
        postSettings.far = 600f;
        postSettings.frost = Math.max(frost.level(), advancedFeedback.amount(AdvancedFeedback.Effect.FREEZE));
        postSettings.poison = advancedFeedback.amount(AdvancedFeedback.Effect.POISON);
        postSettings.stun = advancedFeedback.amount(AdvancedFeedback.Effect.STUN);
        postSettings.fogTime = totalTime;

        if (volumetricFog() && world != null) {
            postSettings.fogDensity = atmosphere.mist;
            postSettings.fogHaze = atmosphere.haze;
            postSettings.fogTop = World.SEA_LEVEL + 15f;
            postSettings.fogDepth = 13f;
            postSettings.fogMaxDist = Math.min(96f, renderRadius * Chunk.SIZE_X);
            postSettings.fogWindX = atmosphere.windX;
            postSettings.fogWindZ = atmosphere.windZ;
            postSettings.fogLight.set(lightCol).mul(0.7f);
            postSettings.fogAmbient.set(skyAmb).mul(0.6f);
            postSettings.camPos.set(eye);
            postSettings.lightDir.set(lightDir);
            postSettings.invViewProj.set(proj).mul(view).invert();
            if (lighting.shadows && shadowMap != null) {
                postSettings.shadowMat.set(shadowMap.matrix(1));
                postSettings.fogShadowTex = shadowMap.texture(1);
            } else {
                postSettings.fogShadowTex = 0;
            }
        } else {
            postSettings.fogDensity = 0f;
            postSettings.fogHaze = 0f;
        }
    }

    /**
     * Чанки и мобы в карту теней. Куллинг — по ортографическому фрустуму
     * каскада: за его пределами кастер всё равно ни на что не ляжет.
     *
     * glPolygonOffset сдвигает кастера вглубь: вместе с normal-bias в шейдере
     * это и убирает shadow acne на пологом свете.
     */
    private void renderShadowPass(int pcx, int pcz, int sw, int sh) {
        boolean any = false;
        for (int c = 0; c < ShadowMap.CASCADES; c++)
            any |= shadowMap.needsRebuild(c);
        if (!any)
            return;
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(2.2f, 4.0f);
        glDisable(GL_CULL_FACE);
        atlas.bind(0);
        int radius = renderRadius + 1;
        for (int c = 0; c < ShadowMap.CASCADES; c++) {
            if (!shadowMap.needsRebuild(c))
                continue;
            shadowMap.begin(c);
            Matrix4f ls = shadowMap.matrix(c);
            shadowFrustum.set(scratchLight.set(ls));
            shadowShader.bind();
            shadowShader.setInt("uAtlas", 0);
            shadowShader.setMat4("uLightSpace", ls);
            shadowShader.setFloat("uTime", totalTime);
            applyWindSway(shadowShader, false);
            for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
                for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                    float wx = cx * Chunk.SIZE_X, wz = cz * Chunk.SIZE_Z;
                    if (!shadowFrustum.testAab(wx, 0, wz, wx + Chunk.SIZE_X, Chunk.SIZE_Y, wz + Chunk.SIZE_Z))
                        continue;
                    Mesh mesh = chunkMeshes.get(World.key(cx, cz));
                    if (mesh == null)
                        continue;
                    shadowShader.setMat4("uModel", scratchModel.translation(wx, 0, wz));
                    mesh.render();
                }
            }
            shadowShader.unbind();
            if (!mobs.isEmpty())
                mobRenderer.renderShadow(shadowMobShader, ls, mobs);
            // Игрок отбрасывает тень всегда, даже когда его самого не видно:
            // тень под ногами — главная подсказка о том, где ты стоишь.
            playerRenderer.renderShadow(shadowMobShader, ls, player.position,
                    player.camera.yaw, player.camera.pitch, walkedDistance, walkAmount, handSwing);
        }
        shadowMap.end(sw, sh);
        glPolygonOffset(0f, 0f);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glEnable(GL_CULL_FACE);
    }

    /**
     * Свет фона меню — из той же палитры, что игра, по времени суток фона.
     * Теней нет: карта теней снята с игрового мира, а не с фона.
     */
    private SceneLighting menuLighting(boolean hdr, SkyPalette p, float day) {
        SceneLighting l = new SceneLighting();
        l.camPos.set(menuBackground.cameraPosition());
        l.lightDir.set(p.lightDir);
        l.lightColor.set(p.lightCol);
        l.skyLight.set(p.skyAmb);
        l.groundLight.set(p.groundAmb);
        l.torchColor.set(1.55f, 0.88f, 0.42f);
        l.ambientColor.set(0.030f, 0.034f, 0.052f).mul(0.65f + 0.35f * day);
        l.fogColor.set(p.horizon);
        l.fogSunColor.set(p.lightCol).mul(0.22f);
        l.fogStart = MenuBackground.RADIUS * Chunk.SIZE_X * 0.55f;
        l.fogEnd = MenuBackground.RADIUS * Chunk.SIZE_X * 1.05f;
        l.brightness = 1f;
        l.time = uiClock;
        l.linearOut = hdr ? 1f : 0f;
        l.waterTint.set(0.34f, 0.66f, 0.92f);
        l.shadows = false;
        return l;
    }

    /**
     * Живой фон меню: купол неба и светила по суткам фона, мир, вода, пост.
     * Тем же порядком, что кадр игры, — иначе меню и игра снова выглядели бы
     * из разных игр.
     */
    private void renderMenu(boolean hdr, int sw, int sh) {
        MenuBackground bg = menuBackground;
        float mt = bg.gameTime(), day = bg.daylight(), clouds = bg.cloudiness();
        SkyPalette p = menuPalette;
        p.compute(mt, day, clouds, bg.storm(), bg.moonlight(), bg.aurora());
        Matrix4f proj = bg.projection(window.getAspect(), fovDegrees);
        Matrix4f view = bg.view();
        Vector3f eye = bg.cameraPosition();

        if (hdr) {
            post.begin(p.skyLin.x, p.skyLin.y, p.skyLin.z);
        } else {
            glClearColor(p.skySrgb.x, p.skySrgb.y, p.skySrgb.z, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        }
        SkyRenderer.Dome dome = skyDome;
        dome.zenith.set(p.zenith);
        dome.horizon.set(p.horizon);
        dome.ground.set(p.ground);
        dome.sunGlow.set(p.sunGlow);
        dome.lightDir.set(p.lightDir);
        dome.cloudiness = clouds;
        dome.aurora = bg.aurora();
        dome.time = uiClock;
        dome.haze.set(p.horizon);
        dome.hazeMix = 0f;
        dome.linearOut = hdr ? 1f : 0f;
        skyRenderer.renderDome(new Matrix4f(proj).mul(view).invert(), dome);
        glDepthMask(false);
        skyRenderer.render(proj, view, eye, mt, day, uiClock, hdr ? 1f : 0f, bg.moonPhase(), clouds, 0f);
        glDepthMask(true);

        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glDisable(GL_BLEND);
        bg.renderWorld(chunkShader, waterShader, atlas, proj, view, menuLighting(hdr, p, day));

        if (hdr) {
            boolean fancy = shaderQuality >= 1;
            postSettings.bloomStrength = fancy ? 0.35f : 0f;
            postSettings.bloomThreshold = 1.1f;
            postSettings.rayStrength = fancy ? 0.55f * day : 0f;
            postSettings.rayDensity = 0.62f;
            postSettings.rayDecay = 0.945f;
            Vector3f rc = new Vector3f(p.lightCol);
            float m = Math.max(0.001f, Math.max(rc.x, Math.max(rc.y, rc.z)));
            postSettings.rayColor.set(rc.mul(1f / m));
            postSettings.exposure = 0.97f;
            postSettings.vignette = 0.22f;
            postSettings.underwater = 0f;
            postSettings.night = 1f - Math.min(1f, day * 3.2f);
            postSettings.saturation = 1.02f;
            // Настройки общие с игрой: туман, иней и размытие оттуда в меню не
            // должны доезжать.
            postSettings.fogDensity = 0f;
            postSettings.fogHaze = 0f;
            postSettings.dofStrength = 0f;
            postSettings.frost = 0f;
            postSettings.poison = 0f;
            postSettings.stun = 0f;
            postSettings.damage = 0f;
            post.resolve();
            post.render(postSettings, sunScreenUv(proj, view, eye, p.sunDir, day, false));
        }
        // Меню — такое же стекло, как HUD: размытый фон снимается до интерфейса.
        if (backdrop != null && ui != null) {
            backdrop.capture(sw, sh);
            ui.setBackdrop(backdrop.texture());
        }
        drawUi();
    }

    private void drawUi() {
        if (hud == null)
            return;
        int w = window.getWidth(), h = window.getHeight();
        int scale = effectiveGuiScale();
        int vw = w / scale, vh = h / scale;
        hud.setTime(totalTime);

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

        // F1 прячет интерфейс целиком — режим для скриншотов. Меню и паузу
        // не трогаем: иначе из них не выйти.
        if ((hideHud || photoMode) && state == State.PLAYING)
            return;

        switch (state) {
            case MENU, LOADING -> handleMenuAction(drawMenus(vw, vh, scale));
            case PLAYING -> {
                if (player.eyeInWater && hud != null)
                    hud.drawWaterOverlay(vw, vh);
                crosshair.render(vw, vh);
                if (hintAlpha > 0.01f && shownHint != null)
                    hud.drawHint(vw, vh, shownHint, hintAlpha);
                hud.drawSoundCues(vw, vh, soundCues, player.camera.yaw);
                // Компас только в игре: в паузе и меню он ничего не решает,
                // а верх экрана там занят заголовком панели.
                hud.drawCompass(vw, vh, player.camera.yaw, gameTime);
                hud.drawHotbar(vw, vh, inventory, selectedSlot, slotAnim);
                hud.drawHearts(vw, vh, player.health, healthGhost);
                hud.drawHunger(vw, vh, player.hunger);
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
                            countLoadedChunks(), drawnChunks, tgt, tgtMeta, wireframe, skyL, blkL,
                            world.biomes.biomeAt(bx, bz).name(), mobs.size(),
                            profiler, loader.pendingMeshCount() + loader.pendingGenCount(),
                            music.status(musicPlayer.position()));
                }
                if (consoleOpen)
                    hud.drawConsole(vw, vh, consoleLine.toString());
            }
            case PAUSED -> {
                hud.drawHotbar(vw, vh, inventory, selectedSlot, slotAnim);
                hud.drawHearts(vw, vh, player.health, healthGhost);
                hud.drawHunger(vw, vh, player.hunger);
                handleMenuAction(drawMenus(vw, vh, scale));
            }
            case CREATIVE_MENU -> {
                hud.drawHotbar(vw, vh, inventory, selectedSlot, slotAnim);
                hud.drawHearts(vw, vh, player.health, healthGhost);
                hud.drawHunger(vw, vh, player.hunger);
                boolean clicked = input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                boolean rightClicked = input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                double mx = input.getCursorX() / scale, my = input.getCursorY() / scale;
                if (gameMode == com.mineclone.world.GameMode.CREATIVE) {
                    com.mineclone.world.ItemStack picked =
                            hud.drawCreativeMenu(vw, vh, mx, my, clicked, inventory, selectedSlot);
                    if (picked != null
                            && (picked.isTool() || picked.isFood() || picked.type != BlockType.AIR)) {
                        // Блоки в творческом выдаются полной стопкой,
                        // инструмент — ровно один: он и так не стопкуется.
                        if (!picked.isTool())
                            picked.count = com.mineclone.world.ItemStack.MAX_STACK;
                        inventory.set(selectedSlot, picked);
                        equipProgress = 0f;
                        sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                    }
                } else {
                    Hud.SlotClick sc = hud.drawInventory(vw, vh, mx, my, clicked, rightClicked,
                            inventory, selectedSlot, cursorItem);
                    if (sc.trash) {
                        cursorItem = null;
                        sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                    } else if (sc.recipe >= 0) {
                        // Список пересобирается тем же вызовом, что его и
                        // нарисовал, поэтому индекс валиден ровно сейчас.
                        var list = com.mineclone.world.Recipes.available(inventory);
                        if (sc.recipe < list.size()) {
                            var r = list.get(sc.recipe);
                            if (com.mineclone.world.Recipes.craft(inventory, r)) {
                                showCommandToast("Собрано: " + r.resultName());
                                sound.playOneOf(sounds.uiClick(), 0.5f, 0.9f);
                            }
                        }
                    } else if (sc.slot >= 0) {
                        cursorItem = sc.right
                                ? inventory.rightClick(sc.slot, cursorItem)
                                : inventory.leftClick(sc.slot, cursorItem);
                        equipProgress = 0f;
                        sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                    }
                }
            }
            case CHEST_MENU -> {
                hud.drawHearts(vw, vh, player.health, healthGhost);
                hud.drawHunger(vw, vh, player.hunger);
                boolean clicked = !swallowMouseUntilUp
                        && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                boolean rightClicked = !swallowMouseUntilUp
                        && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                double mx = input.getCursorX() / scale, my = input.getCursorY() / scale;
                if (openChest != null) {
                    Hud.SlotClick sc = hud.drawChest(vw, vh, mx, my, clicked, rightClicked,
                            openChest, inventory, selectedSlot, cursorItem);
                    if (sc.slot >= 0) {
                        // Одни и те же правила слияния на оба хранилища —
                        // статические методы Inventory, а не вторая копия.
                        cursorItem = sc.container
                                ? (sc.right
                                        ? com.mineclone.world.Inventory.rightClick(openChest, sc.slot, cursorItem)
                                        : com.mineclone.world.Inventory.leftClick(openChest, sc.slot, cursorItem))
                                : (sc.right
                                        ? inventory.rightClick(sc.slot, cursorItem)
                                        : inventory.leftClick(sc.slot, cursorItem));
                        if (sc.container)
                            world.markChestDirty(chestX, chestZ);
                        equipProgress = 0f;
                        sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                    }
                }
            }
            case FURNACE_MENU -> {
                hud.drawHearts(vw, vh, player.health, healthGhost);
                hud.drawHunger(vw, vh, player.hunger);
                boolean clicked = !swallowMouseUntilUp
                        && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                boolean rightClicked = !swallowMouseUntilUp
                        && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                double mx = input.getCursorX() / scale, my = input.getCursorY() / scale;
                if (openFurnace != null) {
                    Hud.SlotClick sc = hud.drawFurnace(vw, vh, mx, my, clicked, rightClicked,
                            openFurnace, inventory, selectedSlot, cursorItem);
                    if (sc.slot >= 0 && sc.container) {
                        cursorItem = clickFurnaceSlot(sc.slot, sc.right);
                        world.markChestDirty(furnaceX, furnaceZ);
                        equipProgress = 0f;
                        sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                    } else if (sc.slot >= 0) {
                        cursorItem = sc.right
                                ? inventory.rightClick(sc.slot, cursorItem)
                                : inventory.leftClick(sc.slot, cursorItem);
                        equipProgress = 0f;
                        sound.playOneOf(sounds.uiClick(), 0.4f, 1.1f + 0.1f * (float) Math.random());
                    }
                }
            }
            case DEAD -> {
                hud.drawHearts(vw, vh, player.health, healthGhost);
                hud.drawHunger(vw, vh, player.hunger);
                handleMenuAction(drawMenus(vw, vh, scale));
            }
        }

        if (commandToastTimer > 0f && font != null && !commandToast.isEmpty()) {
            float mw = font.textWidth(commandToast);
            float a = Math.min(1f, commandToastTimer / 0.35f);
            float y = TOAST_Y;
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

        // Метка версии везде, кроме титула: там она уже стоит в подвале экрана.
        if (state != State.MENU)
            hud.drawVersionLabel(vw, vh);
    }

    /** Кадр стека меню: снимок ввода, отрисовка и одно действие наружу. */
    private MenuAction drawMenus(int vw, int vh, int scale) {
        if (menuTheme == null || menus.isEmpty())
            return MenuAction.NONE;
        // Модель настроек — каждый кадр: F11 меняет полный экран в обход меню,
        // и открытый экран настроек иначе показывал бы старое значение.
        syncSettingsModel();
        UiInput in;
        if (menuInputBlocked) {
            in = UiInput.builder().at((float) (input.getCursorX() / scale), (float) (input.getCursorY() / scale)).build();
            input.pollChars();
            menuInputBlocked = false;
        } else {
            in = menuInput(scale);
        }
        menuTheme.begin(vw, vh, in, uiClock, lastDt);
        MenuAction a = menus.frame(menuTheme);
        menuTheme.end();
        return a;
    }

    /** Ввод этого кадра для меню — без GLFW дальше этой точки. */
    private UiInput menuInput(int scale) {
        UiInput.Builder b = UiInput.builder()
                .at((float) (input.getCursorX() / scale), (float) (input.getCursorY() / scale))
                .mouseDown(input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT))
                .mousePressed(input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT))
                .mouseReleased(input.mouseReleased(GLFW.GLFW_MOUSE_BUTTON_LEFT))
                .scroll((float) input.getScroll())
                .typed(input.pollChars());
        for (int k = GLFW.GLFW_KEY_SPACE; k <= GLFW.GLFW_KEY_LAST; k++) {
            if (input.keyPressed(k))
                b.key(k);
            else if (input.keyDown(k))
                b.held(k);
        }
        boolean ctrl = input.keyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || input.keyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
        if (ctrl && input.keyPressed(GLFW.GLFW_KEY_V))
            b.paste(input.clipboard());
        return b.build();
    }

    /** Действие экрана меню, дошедшее до игры. */
    private void handleMenuAction(MenuAction a) {
        switch (a.kind) {
            case PLAY_WORLD -> startWorld(a.worldId);
            case CREATE_WORLD -> createWorld(a.world);
            case RESUME, BACK -> {
                if (state == State.PAUSED)
                    resumeFromPause();
            }
            case SAVE -> saveAll();
            case MAIN_MENU -> {
                unloadWorld();
                state = State.MENU;
                input.grabCursor(false);
                openMenu(new TitleScreen(save, settingsModel));
            }
            case QUIT -> GLFW.glfwSetWindowShouldClose(window.getHandle(), true);
            case RESPAWN -> {
                respawnPlayer();
                state = State.PLAYING;
                input.grabCursor(true);
                menus.clear();
            }
            default -> {
            }
        }
    }

    /** Пауза: мир сохраняется сразу — с паузы чаще всего и выходят. */
    private void pauseGame() {
        state = State.PAUSED;
        saveAll();
        input.grabCursor(false);
        openMenu(new PauseScreen(worldDisplayName, settingsModel));
    }

    /** Код выхода процесса: у автопилота ненулевой — провал. */
    public int exitCode() {
        return exitCode;
    }

    /** Кадр целиком, с интерфейсом, в PNG — для автопилота. */
    private void captureScreenshot(String name) {
        int w = window.getWidth(), h = window.getHeight();
        java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
        int[] argb = new int[w * h];
        boolean uniform = true;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = (y * w + x) * 4;
                int c = 0xff000000 | (px.get(p) & 255) << 16 | (px.get(p + 1) & 255) << 8 | (px.get(p + 2) & 255);
                argb[(h - 1 - y) * w + x] = c;
                uniform &= c == argb[(h - 1) * w];
            }
        if (uniform)
            System.err.println("autopilot: " + name + " is a single colour");
        java.nio.file.Path file = shotDir.resolve(name + ".png");
        Thread t = new Thread(() -> {
            try {
                java.nio.file.Files.createDirectories(shotDir);
                java.awt.image.BufferedImage img =
                        new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, w, h, argb, 0, w);
                javax.imageio.ImageIO.write(img, "png", file.toFile());
                System.out.println("autopilot: " + file);
            } catch (java.io.IOException e) {
                System.err.println("autopilot: cannot write " + file + ": " + e.getMessage());
            }
        }, "autopilot-shot");
        t.start();
    }

    /** Мост автопилота к игре: только то, что нужно прогону. */
    private final class PilotDriver implements Autopilot.Driver {
        @Override
        public String state() {
            return state.name();
        }

        @Override
        public void open(Screen s) {
            syncSettingsModel();
            menus.push(s);
        }

        @Override
        public void act(MenuAction a) {
            handleMenuAction(a);
        }

        @Override
        public void shot(String name) {
            pendingShot = name;
        }

        @Override
        public void pressKey(int key) {
            input.inject(key);
        }

        @Override
        public void setMenuTime(float t) {
            menuBackground.setGameTime(t);
        }

        @Override
        public boolean shotPending() {
            return pendingShot != null;
        }

        @Override
        public boolean worldHasIcon() {
            return worldId != null && save.loadIcon(worldId) != null;
        }

        @Override
        public void quit(int code) {
            exitCode = code;
            GLFW.glfwSetWindowShouldClose(window.getHandle(), true);
        }

        @Override
        public com.mineclone.save.SaveManager save() {
            return save;
        }

        @Override
        public SettingsModel settings() {
            return settingsModel;
        }

        @Override
        public boolean musicAvailable() {
            return musicPlayer != null && musicPlayer.available() && musicPlayer.enabled();
        }

        @Override
        public String musicTrack() {
            return musicPlayer.currentTrackId();
        }

        @Override
        public float musicPosition() {
            return musicPlayer.position();
        }

        @Override
        public void musicNext() {
            music.requestNext();
        }
    }

    private void resumeFromPause() {
        if (player.isDead()) {
            state = State.DEAD;
            openMenu(new DeathScreen());
            return;
        }
        state = State.PLAYING;
        input.grabCursor(true);
        menus.clear();
    }

    private void drawCommandHelp(int w, int h) {
        String[] lines = {
                "Commands",
                "/help  - show this list",
                "/time [query]",
                "/time set day|sunrise|noon|sunset|night|midnight|0-24",
                "/time add <hours>",
                "/weather clear|cloudy|light|heavy|storm",
                "/tp <x> <y> <z>",
                "/spawnpoint [x y z]",
                "/fly",
                "/speed <value>",
                "/fill <block> [radius]",
                "/instamine",
                "/music [next] - what plays, or a fitting track now",
                "/debug",
                "/gamemode <creative|survival> - Switch game mode"
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
        // Экраны закрываются до GL: список миров отпускает текстуры превью.
        menus.clear();
        thumbnail.destroy();
        if (loader != null)
            loader.shutdown();
        menuBackground.destroy();
        // Поток музыки удаляет свои источники сам — до того, как умрёт контекст.
        if (musicPlayer != null)
            musicPlayer.close();
        sound.destroy();
        for (Mesh m : chunkMeshes.values())
            m.destroy();
        chunkMeshes.clear();
        for (Mesh m : waterMeshes.values())
            m.destroy();
        waterMeshes.clear();
        atlas.destroy();
        chunkShader.destroy();
        waterShader.destroy();
        shadowShader.destroy();
        shadowMobShader.destroy();
        if (shadowMap != null)
            shadowMap.destroy();
        if (post != null)
            post.destroy();
        heldItemRenderer.destroy();
        crosshair.destroy();
        outline.destroy();
        trajectoryRenderer.destroy();
        ropeRenderer.destroy();
        breakOverlay.destroy();
        mobRenderer.destroy();
        skyRenderer.destroy();
        playerRenderer.destroy();
        particles.destroy();
        if (ui != null)
            ui.destroy();
        if (text != null)
            text.destroy();
        if (font != null)
            font.destroy();
            if (smallFont != null)
                smallFont.destroy();
            if (decals != null)
                decals.destroy();
            if (precipitation != null)
                precipitation.destroy();
            if (backdrop != null)
                backdrop.destroy();
            if (debrisRenderer != null)
                debrisRenderer.destroy();
            if (itemRenderer != null)
                itemRenderer.destroy();
    }
}
