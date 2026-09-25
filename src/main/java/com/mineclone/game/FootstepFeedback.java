package com.mineclone.game;

import com.mineclone.audio.Sounds;
import com.mineclone.net.RemotePlayer;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.TerrainDeformation;
import com.mineclone.world.World;
import org.joml.Vector3f;

/**
 * Steps, footprints, kicked snow and landings — the player's and the other
 * participants' — moved out of {@code Game} whole (SIM-08). It also keeps the
 * walked distance the view bob runs on.
 */
final class FootstepFeedback {
    /** Тайл отпечатка в атласе. */
    private static final int FOOTPRINT_TILE = 75;
    /** Сколько секунд держится след игрока. */
    private static final float FOOTPRINT_LIFE = 26f;
    /** Насколько нога отстоит от оси движения. */
    private static final float FOOTPRINT_SPREAD = 0.16f;
    /** Как далеко моб слышит шаг: бег громче ходьбы. */
    private static final float NOISE_SPRINT = 11f;
    private static final float NOISE_WALK = 4f;

    private final Game g;
    private float stepDistance = 0f;
    /** Левая или правая нога игрока: следы идут в две дорожки, а не в колею. */
    private boolean stepLeft;
    private float walkedDistance = 0f; // monotonic; drives view bob (never resets)
    private final Vector3f lastPos = new Vector3f();

    FootstepFeedback(Game game) {
        this.g = game;
    }

    /** How far the player has walked on the ground, ever: the view bob's phase. */
    float walkedDistance() {
        return walkedDistance;
    }

    /** The player appeared somewhere (a respawn): the step from the old place is no step. */
    void placed(Vector3f position) {
        lastPos.set(position);
    }

    void update() {
        Player player = g.player;
        World world = g.world;
        Vector3f cur = player.position;
        if (lastPos.x == 0 && lastPos.y == 0 && lastPos.z == 0) {
            lastPos.set(cur);
            return;
        }
        // First-person bob and audible footsteps keep their own distance counter.
        if (player.onGround && !player.inWater && !player.flying) {
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
                g.emitNoise(cur.x, cur.y, cur.z,
                        player.isSprinting ? NOISE_SPRINT : NOISE_WALK);
                // Шаг звучит по поверхности: снег поверх дёрна, мокрая
                // трава в дождь, лёд, раскисшая земля.
                int feetY = (int) Math.floor(cur.y + 0.02f);
                BlockType feet = world.getBlock(bx, feetY, bz);
                int snowLevel = feet == BlockType.SNOW_LAYER ? world.getBlockMeta(bx, feetY, bz) & 0x7 : 0;
                boolean wet = g.atmosphere.rain() > 0.3f && world.getSkyLight(bx, feetY, bz) >= 12;
                // Геометрический след: покров действительно проминается, а
                // мокрая голая земля становится вязкой грязью.
                TerrainDeformation.footprint(world, bx, feetY, bz,
                        player.isSprinting ? 1.4f : 1f, wet);
                if (under == BlockType.THIN_ICE) {
                    world.setBlock(bx, by, bz, BlockType.WATER);
                    g.particles.emitWaterSplash(cur.x, cur.y, cur.z,
                            world.getSkyLight(bx, feetY, bz) / (float) Chunk.MAX_LIGHT,
                            world.getBlockLightWorld(bx, feetY, bz) / (float) Chunk.MAX_LIGHT);
                }
                Sounds.Material mat = Sounds.stepMaterial(under, feet, snowLevel, wet);
                float vol = Sounds.stepVolume(mat) * (player.isSprinting ? 1.25f : 1f);
                g.sound.playOneOfAt(g.sounds.step(mat), new Vector3f(cur.x, cur.y + 0.15f, cur.z),
                        vol, Sounds.stepPitch(mat) * (0.95f + 0.1f * (float) Math.random()));
            }
        }
        lastPos.set(cur);
    }

    /**
     * Чужая модель считает шаги из тех же сетевых снимков, что и её анимация.
     * Так хозяин и остальные участники слышат ходьбу без ещё одного потока
     * пакетов, а звук всегда остаётся у фактического положения модели.
     */
    void playRemotePlayers() {
        World world = g.world;
        if (world == null || !g.net.active())
            return;
        for (RemotePlayer rp : g.net.players()) {
            if (!rp.placed() || rp.isDead())
                continue;
            if (rp.consumeSwingStart()) {
                g.sound.playOneOfAt(g.sounds.playerAttack("sweep"),
                        new Vector3f(rp.position.x, rp.position.y + 0.9f, rp.position.z),
                        0.18f, 0.9f + 0.15f * (float) Math.random());
            }
            while (rp.consumeFootstep()) {
                Vector3f p = rp.position;
                if ((rp.flags & RemotePlayer.F_IN_WATER) != 0) {
                    g.sound.playOneOfAt(g.sounds.waterSwim(),
                            new Vector3f(p.x, p.y + 0.25f, p.z),
                            0.32f, 0.9f + 0.2f * (float) Math.random());
                    continue;
                }
                int bx = (int) Math.floor(p.x);
                int by = (int) Math.floor(p.y - 0.05f);
                int bz = (int) Math.floor(p.z);
                BlockType under = world.getBlock(bx, by, bz);
                g.sound.playOneOfAt(g.sounds.step(under), new Vector3f(p.x, p.y + 0.15f, p.z),
                        0.34f, 0.94f + 0.12f * (float) Math.random());
            }
        }
    }

    /**
     * Отпечаток под ногой, если грунт его держит.
     *
     * След остаётся только на снегу и песке — на камне и траве он выглядел бы
     * грязью, а не следом. Ноги чередуются и разнесены в стороны: одна колея
     * по центру читается волочением, а не шагами.
     */
    void dropFootprint(float x, float y, float z, float yaw, float size,
            boolean left) {
        World world = g.world;
        if (g.decals == null || world == null)
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
            top = by + snowSurface(world, bx, by, bz);
        } else if (!holdsFootprint(under)) {
            return;
        }

        float side = left ? FOOTPRINT_SPREAD : -FOOTPRINT_SPREAD;
        float fx = x + (float) Math.cos(yaw) * side;
        float fz = z - (float) Math.sin(yaw) * side;
        g.decals.add(fx, top, fz, yaw, size, FOOTPRINT_LIFE, 0.85f, FOOTPRINT_TILE);
    }

    /**
     * Снег из-под ноги — если под ногой снег. Покров не твёрдый и занимает
     * клетку ног, а снежный дёрн лежит под ней, поэтому проверяются обе.
     */
    void kickSnow(float x, float y, float z, float dirX, float dirZ, boolean hard) {
        World world = g.world;
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
        g.particles.emitSnowKick(x, y, z, dirX, dirZ, hard, sky, blk);
    }

    /** Грунт, на котором след виден: рыхлый и светлый. */
    private static boolean holdsFootprint(BlockType b) {
        return b == BlockType.SNOW_LAYER || b == BlockType.SAND
                || b == BlockType.SNOWY_GRASS;
    }

    /** Высота верхней грани снежного слоя в блоке, в долях блока. */
    private static float snowSurface(World world, int x, int y, int z) {
        // Та же формула, что в ChunkMesher.emitLayer: разойдись они — след
        // повиснет в воздухе или утонет в снегу.
        int level = world.getBlockMeta(x, y, z) & 0x7;
        return (level + 1) / 8f;
    }

    void landing(float fallDistance) {
        Player player = g.player;
        int bx = (int) Math.floor(player.position.x);
        int by = (int) Math.floor(player.position.y - 0.05f);
        int bz = (int) Math.floor(player.position.z);
        BlockType under = g.world.getBlock(bx, by, bz);
        float volume = Math.min(0.65f, 0.32f + fallDistance * 0.08f);
        g.sound.playOneOfAt(g.sounds.step(under), g.playerSoundPosition(), volume,
                0.85f + 0.15f * (float) Math.random());
    }
}
