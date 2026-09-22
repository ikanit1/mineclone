package com.mineclone.game;

import com.mineclone.audio.MusicSituation;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;

import java.util.List;

/**
 * Что музыке нужно знать о мире: пещера, укрытие, опасность, путь, стройка.
 *
 * Мировые пробы — дважды в секунду: столб над головой и мобы вокруг так быстро
 * не меняются, а шестьдесят раз в секунду это чистая трата. Занятия
 * накапливаются по событиям и по времени. Сколько каждый флаг держится
 * подряд, решает уже режиссёр — здесь только «да» или «нет» на этот кадр.
 */
public final class MusicSense {

    /** Как часто перечитывается мир. */
    public static final float SAMPLE_INTERVAL = 0.5f;
    /** Под землёй: небесный свет у головы не выше этого и столько твёрдых блоков над ней. */
    public static final int UNDERGROUND_SKY_MAX = 3, UNDERGROUND_SOLID_MIN = 5;
    /** Укрытие: крыша не выше стольких блоков и блочный свет у головы не ниже этого. */
    public static final int SHELTER_ROOF_MAX = 12, SHELTER_LIGHT_MIN = 7;
    /** Враг дальше этого музыку не тревожит. */
    public static final float DANGER_RANGE = 16f;
    /** Сколько опасность держится после последнего обнаружения: погоня мигает. */
    public static final float DANGER_MEMORY = 4f;
    /** Путь: окно, шаг выборки позиции и расстояние по прямой за окно. */
    public static final float TRAVEL_WINDOW = 60f, TRAVEL_SAMPLE = 5f, TRAVEL_DISTANCE = 80f;
    /** Стройка: постоянная затухания счётчика установок и порог. */
    public static final float BUILD_TAU = 45f, BUILD_THRESHOLD = 8f;
    /**
     * Сколько нужно пробыть в новом краю, чтобы музыка его признала.
     *
     * Без этого музыка дёргалась бы на каждом шаге: биом квантуется по
     * четыре блока, и идущий вдоль опушки пересекает границу десятки раз в
     * минуту. Двенадцать секунд — это «я действительно ушёл в другой край», а
     * не «я качнулся на границе».
     */
    public static final float REGION_HOLD = 12f;

    private static final int TRAIL = Math.round(TRAVEL_WINDOW / TRAVEL_SAMPLE) + 1;

    private float sampleTimer;
    private boolean underground, sheltered;
    /** Край, который музыка уже признала, и претендент с его выслугой. */
    private com.mineclone.audio.MusicMood region, pendingRegion;
    private float pendingHeld;
    private float dangerLeft;
    private float build;
    private final float[] trailX = new float[TRAIL], trailZ = new float[TRAIL];
    private int trailCount, trailHead;
    private float trailTimer;

    /** Игрок поставил блок. */
    public void onBlockPlaced() {
        build += 1f;
    }

    /** Вход в мир, возрождение, телепорт: прошлый путь и прошлые пробы не в счёт. */
    public void reset() {
        sampleTimer = 0f;
        underground = false;
        sheltered = false;
        dangerLeft = 0f;
        build = 0f;
        region = null;
        pendingRegion = null;
        pendingHeld = 0f;
        trailCount = 0;
        trailHead = 0;
        trailTimer = 0f;
    }

    /**
     * Ситуация кадра в мире.
     *
     * @param world null — мир не пробуется (тесты занятий)
     * @param paused на паузе ничего не копится и не затухает
     */
    public MusicSituation sample(float dt, MusicSituation.Scene scene, boolean paused, World world,
                                 Player player, List<Mob> mobs, float gameTime) {
        if (!paused) {
            build *= (float) Math.exp(-dt / BUILD_TAU);
            dangerLeft = Math.max(0f, dangerLeft - dt);
            trailTimer -= dt;
            if (trailTimer <= 0f) {
                trailTimer += TRAVEL_SAMPLE;
                pushTrail(player.position.x, player.position.z);
            }
            sampleTimer -= dt;
            if (sampleTimer <= 0f) {
                sampleTimer = SAMPLE_INTERVAL;
                if (world != null)
                    probe(world, player);
                if (threatNear(mobs, player))
                    dangerLeft = DANGER_MEMORY;
            }
            if (world != null)
                settleRegion(dt, world.biomes.biomeAt((int) Math.floor(player.position.x),
                        (int) Math.floor(player.position.z)).musicMood());
        }
        return new MusicSituation(scene, paused, MusicSituation.dayPart(gameTime), underground, sheltered,
                dangerLeft > 0f, exploring(), building(), player.flying, player.eyeInWater, region);
    }

    /**
     * Край под ногами становится краем музыки, только продержавшись
     * {@link #REGION_HOLD}. Первый край признаётся сразу: при входе в мир
     * ждать нечего, а молчать двенадцать секунд не за что.
     */
    private void settleRegion(float dt, com.mineclone.audio.MusicMood under) {
        if (region == null) {
            region = under;
            pendingRegion = null;
            pendingHeld = 0f;
            return;
        }
        if (under == region) {
            pendingRegion = null;
            pendingHeld = 0f;
            return;
        }
        if (under != pendingRegion) {
            pendingRegion = under;
            pendingHeld = 0f;
        }
        if ((pendingHeld += dt) >= REGION_HOLD) {
            region = pendingRegion;
            pendingRegion = null;
            pendingHeld = 0f;
        }
    }

    /** Край, который музыка считает текущим. */
    public com.mineclone.audio.MusicMood region() {
        return region;
    }

    public boolean building() {
        return build >= BUILD_THRESHOLD;
    }

    /** Смещение по прямой за окно, а не длина пути: круги вокруг базы — не путешествие. */
    public boolean exploring() {
        if (trailCount < TRAIL)
            return false;
        int newest = Math.floorMod(trailHead - 1, TRAIL);
        int oldest = trailHead;   // буфер полон: следующий на запись — самый старый
        float dx = trailX[newest] - trailX[oldest], dz = trailZ[newest] - trailZ[oldest];
        return dx * dx + dz * dz >= TRAVEL_DISTANCE * TRAVEL_DISTANCE;
    }

    private void pushTrail(float x, float z) {
        trailX[trailHead] = x;
        trailZ[trailHead] = z;
        trailHead = (trailHead + 1) % TRAIL;
        trailCount = Math.min(TRAIL, trailCount + 1);
    }

    private void probe(World world, Player player) {
        int x = (int) Math.floor(player.position.x);
        int y = (int) Math.floor(player.position.y + Player.EYE_HEIGHT);
        int z = (int) Math.floor(player.position.z);
        underground = isUnderground(world.getSkyLight(x, y, z), solidAbove(world, x, y, z));
        sheltered = isSheltered(underground, roofDistance(world, x, y, z), world.getBlockLightWorld(x, y, z));
    }

    private static boolean threatNear(List<Mob> mobs, Player player) {
        if (mobs == null)
            return false;
        for (Mob m : mobs)
            if (threatens(m.dead, m.type.hostile, m.isAngry(), m.state, m.position.distance(player.position)))
                return true;
        return false;
    }

    // ---- правила — статикой, чтобы проверять без мира и мобов --------------------

    public static boolean isUnderground(int skyLight, int solidAbove) {
        return skyLight <= UNDERGROUND_SKY_MAX && solidAbove >= UNDERGROUND_SOLID_MIN;
    }

    /** @param roofDistance высота ближайшего твёрдого блока над головой; −1 — неба ничто не закрывает */
    public static boolean isSheltered(boolean underground, int roofDistance, int blockLight) {
        return !underground && roofDistance > 0 && roofDistance <= SHELTER_ROOF_MAX
                && blockLight >= SHELTER_LIGHT_MIN;
    }

    /** Только тот, кто идёт на игрока: зомби, бредущий мимо, — ещё не бой. */
    public static boolean threatens(boolean dead, boolean hostile, boolean angry, Mob.State state, float distance) {
        if (dead || !(hostile || angry) || distance > DANGER_RANGE)
            return false;
        return state == Mob.State.CHASE || state == Mob.State.ATTACK || state == Mob.State.STALK;
    }

    /** Сколько твёрдых блоков в столбе над клеткой. */
    public static int solidAbove(World world, int x, int y, int z) {
        int n = 0;
        for (int yy = Math.max(0, y + 1); yy < Chunk.SIZE_Y; yy++)
            if (world.getBlock(x, yy, z).solid)
                n++;
        return n;
    }

    /** На сколько блоков выше клетки первый твёрдый блок; −1 — над головой пусто. */
    public static int roofDistance(World world, int x, int y, int z) {
        for (int yy = Math.max(0, y + 1); yy < Chunk.SIZE_Y; yy++)
            if (world.getBlock(x, yy, z).solid)
                return yy - y;
        return -1;
    }
}
