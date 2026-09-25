package com.mineclone.game;

import com.mineclone.audio.AcousticProbe;
import com.mineclone.audio.AmbientSound;
import com.mineclone.audio.RainAmbience;
import com.mineclone.audio.SoundEngine;
import com.mineclone.audio.SoundOcclusion;
import com.mineclone.audio.StormAmbience;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import org.joml.Vector3f;

/**
 * What the world around the player sounds and looks like on its own: cave,
 * rain, storm and underwater beds, the room's echo, a stream and lava nearby,
 * fish, leaves and fireflies, torch smoke, breath in the cold. Moved out of
 * {@code Game} whole (SIM-08); it reads the game's current world and player.
 */
final class AmbientEffects {
    /** В каком радиусе ищется точка, откуда «донёсся» звук пещеры. */
    private static final int CAVE_SOUND_RADIUS = 14;
    /** Как часто перезамеряется замкнутость для эха. */
    private static final float REVERB_INTERVAL = 0.5f;
    /** Как часто идёт пар от дыхания на морозе, секунды. */
    private static final float BREATH_MIN = 3f, BREATH_MAX = 5f;

    private final Game g;
    /** Расписание фоновой атмосферы: пещера, дождь, гром, вода. */
    private AmbientSound ambient;
    private RainAmbience rainAmbience;
    private StormAmbience stormAmbience;
    private float reverbTimer;
    /** Сглаженная замкнутость: сколько звука возвращается. */
    private float enclosure;
    /** Сглаженный размер помещения в блоках: как долго звук затухает. */
    private float roomSize;
    private float breathTimer = BREATH_MIN;
    private float waterFlowProbeTimer = 0f;
    private Vector3f waterFlowSoundPosition;
    private float lavaEffectTimer;

    AmbientEffects(Game game) {
        this.g = game;
    }

    /** Once the sound engine is up. */
    void start() {
        ambient = new AmbientSound(new java.util.Random());
        rainAmbience = new RainAmbience(g.sound, g.sounds.ambientRain());
        stormAmbience = new StormAmbience(g.sound, g.sounds.ambientWind());
    }

    /** The world is closed: the rain and storm beds stop, the stream is forgotten. */
    void reset() {
        if (rainAmbience != null) rainAmbience.reset();
        if (stormAmbience != null) stormAmbience.reset();
        forgetStream();
    }

    /** The player is elsewhere (a respawn): the nearest stream is looked for again. */
    void forgetStream() {
        waterFlowSoundPosition = null;
        waterFlowProbeTimer = 0f;
    }

    /**
     * Пар от дыхания на морозе — у игрока и у мобов рядом.
     *
     * Самая дешёвая деталь, которая сообщает «здесь холодно»: текстура снега
     * этого не говорит, её видно и на картинке без холода.
     */
    void updateBreath(float dt) {
        breathTimer -= dt;
        if (breathTimer > 0f)
            return;
        breathTimer = BREATH_MIN + g.natureRandom.nextFloat() * (BREATH_MAX - BREATH_MIN);
        if (g.world == null)
            return;
        Player player = g.player;
        Vector3f fwd = player.camera.forward();
        if (coldAt(player.position.x, player.position.z))
            g.particles.emitBreath(player.camera.position.x + fwd.x * 0.35f,
                    player.camera.position.y - 0.12f,
                    player.camera.position.z + fwd.z * 0.35f, fwd.x, fwd.z);
        for (Mob m : g.mobs) {
            if (m.dead || !coldAt(m.position.x, m.position.z))
                continue;
            if (m.position.distanceSquared(player.position) > 32f * 32f)
                continue;
            float mx = (float) -Math.sin(m.yaw), mz = (float) -Math.cos(m.yaw);
            g.particles.emitBreath(m.position.x + mx * 0.4f,
                    m.position.y + m.type.height * 0.85f,
                    m.position.z + mz * 0.4f, mx, mz);
        }
    }

    private boolean coldAt(float x, float z) {
        return g.world.biomes.biomeAt((int) Math.floor(x), (int) Math.floor(z))
                .isCold();
    }

    void emitNatureParticles() {
        World world = g.world;
        Player player = g.player;
        java.util.Random natureRandom = g.natureRandom;
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
                g.particles.emitFish(x + natureRandom.nextFloat(), y + 0.3f,
                        z + natureRandom.nextFloat(), ax, az, sky);
                continue;
            }
            if (cell != BlockType.AIR) continue;
            boolean leaf = world.getBlock(x, y + 1, z) == BlockType.LEAVES;
            boolean firefly = g.daylight < 0.25f && sky > 0.5f
                    && world.getBlock(x, y - 1, z) == BlockType.GRASS;
            if (leaf || firefly)
                g.particles.emitNature(x + natureRandom.nextFloat(), y + 0.4f,
                        z + natureRandom.nextFloat(), false, firefly, sky);
        }
    }

    /**
     * Фоновая атмосфера: пещера, дождь, гром, вода.
     *
     * Звук пещеры играется не в голове, а из случайной тёмной точки рядом —
     * иначе он звучит как эффект интерфейса, а не как «что-то там, в
     * темноте», ради чего он и нужен.
     */
    void updateAmbient(float dt) {
        World world = g.world;
        SoundEngine sound = g.sound;
        if (ambient == null || sound == null || world == null)
            return;
        Player player = g.player;
        Atmosphere atmosphere = g.atmosphere;
        int ex = (int) Math.floor(player.camera.position.x);
        int ey = (int) Math.floor(player.camera.position.y);
        int ez = (int) Math.floor(player.camera.position.z);
        int skyLight = world.getSkyLight(ex, ey, ez);
        rainAmbience.update(dt, atmosphere.rain(), skyLight, player.eyeInWater);
        boolean dark = skyLight <= 3
                && world.getBlockLightWorld(ex, ey, ez) <= 7;
        boolean outdoors = AcousticProbe.freeRun(
                world, ex, ey, ez, 0, 1, 0) >= AcousticProbe.MAX_DISTANCE;
        stormAmbience.update(dt, atmosphere.storm, atmosphere.dust, atmosphere.windSpeed(),
                skyLight, outdoors, player.eyeInWater);
        if (player.eyeInWater)
            sound.updateLoopOneOf("underwater-ambient", g.sounds.ambientUnderwater(), 0.48f, 1f);
        else
            sound.stopLoop("underwater-ambient");
        // Эхо подстраивается реже кадра: шесть лучей по двадцать четыре блока
        // каждый — это не то, за что стоит платить шестьдесят раз в секунду,
        // а пространство вокруг головы так быстро не меняется.
        reverbTimer -= dt;
        if (reverbTimer <= 0f) {
            reverbTimer = REVERB_INTERVAL;
            var probe = outdoors ? AcousticProbe.Room.OPEN
                    : AcousticProbe.room(world, player.camera.position.x,
                            player.camera.position.y, player.camera.position.z);
            // На улице send выключаем сразу: иначе длинный пещерный хвост
            // продолжает окрашивать шаги ещё несколько секунд после выхода.
            // Внутри переход по-прежнему плавный, чтобы комнаты не щёлкали.
            // Размер ведётся своим сглаживанием: выйдя из чулана в зал, игрок
            // слышит, как хвост удлиняется, а не как он переключается.
            if (outdoors) {
                enclosure = 0f;
                roomSize = probe.size();
            } else {
                enclosure += (probe.closed() - enclosure) * 0.5f;
                roomSize += (probe.size() - roomSize) * 0.5f;
            }
            sound.setRoom(new AcousticProbe.Room(enclosure, roomSize));
        }
        // Гремит только гроза, а не метель: в снег грома не бывает.
        float thunderStorm = atmosphere.thunderstorm();
        var cue = ambient.tick(dt, dark, player.eyeInWater, atmosphere.rain(), thunderStorm,
                atmosphere.windSpeed(), outdoors, skyLight);
        switch (cue) {
            case CAVE -> {
                Vector3f spot = caveSoundSpot();
                sound.playOneOfAt(g.sounds.ambientCave(), spot,
                        0.75f, 0.92f + 0.16f * (float) Math.random());
                g.cueSound(spot, 0.35f, false);
            }
            case RAIN -> { /* The continuous rain bed is maintained independently above. */ }
            case WIND -> { if (atmosphere.storm < 0.05f) sound.playOneOf(g.sounds.ambientWind(),
                    Math.min(0.55f, 0.12f + atmosphere.windSpeed() * 0.07f),
                    0.85f + 0.2f * (float) Math.random()); }
            case THUNDER -> sound.playOneOf(g.sounds.ambientThunder(), 0.9f,
                    0.9f + 0.2f * (float) Math.random());
            case UNDERWATER -> { /* continuous loop is maintained above */ }
            case UNDERWATER_EXTRA -> sound.playOneOf(g.sounds.ambientUnderwaterExtra(),
                    0.45f, 0.9f + 0.2f * (float) Math.random());
            case WATER_ENTER -> sound.playOneOf(g.sounds.waterEnter(), 0.7f, 1f);
            case WATER_EXIT -> sound.playOneOf(g.sounds.waterExit(), 0.7f, 1f);
            default -> { }
        }
    }

    /** Случайная тёмная точка рядом — оттуда и «донеслось». */
    private Vector3f caveSoundSpot() {
        World world = g.world;
        java.util.Random natureRandom = g.natureRandom;
        Vector3f eye = g.player.camera.position;
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

    void emitTorchParticles() {
        World world = g.world;
        Player player = g.player;
        int px = (int) Math.floor(player.position.x);
        int py = (int) Math.floor(player.position.y);
        int pz = (int) Math.floor(player.position.z);
        for (int dx = -5; dx <= 5; dx++)
            for (int dy = -3; dy <= 5; dy++)
                for (int dz = -5; dz <= 5; dz++) {
                    BlockType b = world.getBlock(px + dx, py + dy, pz + dz);
                    if (b == BlockType.TORCH)
                        g.particles.emitTorchEffects(px + dx, py + dy, pz + dz);
                    else if (b == BlockType.FIRE)
                        g.particles.emitFire(px + dx, py + dy, pz + dz);
                }
    }

    /** Local, bounded surface probe; neither particles nor sound scale with render distance. */
    void updateLavaEffects(float dt) {
        lavaEffectTimer -= dt;
        if (lavaEffectTimer > 0f) return;
        lavaEffectTimer = .25f;
        World world = g.world;
        Player player = g.player;
        SoundEngine sound = g.sound;
        java.util.Random natureRandom = g.natureRandom;
        int px=(int)Math.floor(player.position.x), py=(int)Math.floor(player.position.y),
            pz=(int)Math.floor(player.position.z);
        Vector3f nearest=null;
        float nearestDist=Float.MAX_VALUE;
        int emitted=0;
        for(int dx=-8;dx<=8;dx++) for(int dz=-8;dz<=8;dz++) for(int dy=-4;dy<=4;dy++) {
            int x=px+dx,y=py+dy,z=pz+dz;
            if(world.getBlock(x,y,z)!=BlockType.LAVA || world.getBlock(x,y+1,z)!=BlockType.AIR) continue;
            int level=world.getBlockMeta(x,y,z)&15;
            float h=level==0||level>=8?1f:(8-level)/8f;
            float dist=dx*dx+dy*dy+dz*dz;
            if(dist<nearestDist) { nearestDist=dist; nearest=new Vector3f(x+.5f,y+h,z+.5f); }
            if(emitted<3 && natureRandom.nextFloat()<.012f) {
                g.particles.emitLavaPop(x+natureRandom.nextFloat(),y+h+.03f,z+natureRandom.nextFloat());
                if(emitted==0) sound.playOneOfAt(g.sounds.lavaPop(),new Vector3f(x+.5f,y+h,z+.5f),.35f,.85f+natureRandom.nextFloat()*.3f);
                emitted++;
            }
        }
        if(nearest==null) sound.stopLoop("lava-ambient");
        else sound.updateLoopOneOfAt("lava-ambient",g.sounds.lavaAmbient(),nearest,.45f,1f,
                SoundOcclusion.muffle(
                    SoundOcclusion.solidBetween(world,player.camera.position,nearest)));
    }

    private Vector3f findNearbyFlowingWater() {
        World world = g.world;
        Player player = g.player;
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
        return bestDist == Integer.MAX_VALUE ? null : new Vector3f(bestX + 0.5f, bestY + 0.5f, bestZ + 0.5f);
    }

    /**
     * A stream is ambience, not a sporadic event: keep one positional source
     * alive and let OpenAL's distance curve make it fade in while approaching.
     */
    void updateWaterFlowSound(float dt) {
        waterFlowProbeTimer -= dt;
        if (waterFlowProbeTimer > 0f)
            return;
        waterFlowProbeTimer = 0.25f;
        waterFlowSoundPosition = findNearbyFlowingWater();
        if (waterFlowSoundPosition == null) {
            g.sound.stopLoop("water-flow");
            return;
        }
        int walls = SoundOcclusion.solidBetween(
                g.world, g.player.camera.position, waterFlowSoundPosition);
        float gain = SoundOcclusion.gainFor(walls);
        g.sound.updateLoopOneOfAt("water-flow", g.sounds.waterFlow(), waterFlowSoundPosition,
                0.42f * gain, 0.94f, SoundOcclusion.muffle(walls));
    }
}
