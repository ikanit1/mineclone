package com.mineclone.game;

import com.mineclone.audio.Sounds;
import com.mineclone.render.ParticleSystem;
import com.mineclone.sim.WorldEvents;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import java.util.List;
import org.joml.Vector3f;

/**
 * How the host shows what its mobs did: voices, splashes, footprints, fire,
 * deaths and the sound cues at the screen edge. It only reads the mobs and the
 * world — the dedicated server shows nothing and must simulate the same world.
 */
final class EntityPresentation implements WorldEvents {
    /** What the presentation needs from the game around it. */
    interface Stage {
        World world();
        float daylight();
        /** Where the player stands: snow is kicked up only within earshot of it. */
        Vector3f listener();
        void playOccluded(List<String> paths, Vector3f at, float volume, float pitch);
        void cueSound(Vector3f at, float loudness, boolean danger);
        void dropFootprint(float x, float y, float z, float yaw, float size, boolean left);
        void kickSnow(float x, float y, float z, float dirX, float dirZ, boolean hard);
        /** A sound at a point, heard through walls: a blast is loud. */
        void playAt(List<String> paths, Vector3f at, float volume, float pitch);
        /** A crater changes the shadows at once. */
        void invalidateShadows();
    }

    private static final float[] RAGE_SPARKS = { 0.9f, 0.15f, 0.1f };

    private final Sounds sounds;
    private final ParticleSystem particles;
    private final Stage stage;

    EntityPresentation(Sounds sounds, ParticleSystem particles, Stage stage) {
        this.sounds = sounds;
        this.particles = particles;
        this.stage = stage;
    }

    @Override
    public void mobVoice(Mob m) {
        boolean danger = m.type.hostile || m.isAngry();
        List<String> voice = m.isAngry() ? sounds.mobAngry(m.type) : sounds.mobSay(m.type);
        // Ночью волк иногда воет вместо дыхания — далеко слышно и
        // сразу понятно, что в лесу кто-то есть.
        if (m.type == MobType.WOLF && !m.isAngry() && stage.daylight() < 0.2f && Math.random() < 0.3)
            voice = sounds.mobHowl(m.type);
        stage.playOccluded(voice, m.soundPosition(), 0.7f, 0.9f + 0.2f * (float) Math.random());
        // Дуга только от угрозы: мычание коровы за спиной ничего не
        // решает, а рычание зомби — решает.
        if (danger)
            stage.cueSound(m.soundPosition(), 1f, true);
    }

    @Override
    public void mobSplash(Mob m) {
        float impact = Math.max(0.15f, Math.min(1.4f, m.splashSpeed / 7f));
        stage.playOccluded(sounds.waterSplash(), m.soundPosition(),
                0.2f + 0.42f * Math.min(1f, impact),
                1.12f - 0.16f * Math.min(1f, impact) + 0.08f * (float) Math.random());
        World world = stage.world();
        int wx = (int) Math.floor(m.position.x), wy = (int) Math.floor(m.position.y + 0.5f),
            wz = (int) Math.floor(m.position.z);
        particles.emitWaterSplash(m.position.x, m.position.y + 0.4f, m.position.z,
                world.getSkyLight(wx, wy, wz) / (float) Chunk.MAX_LIGHT,
                world.getBlockLightWorld(wx, wy, wz) / (float) Chunk.MAX_LIGHT, impact);
    }

    @Override
    public void mobEnraged(Mob m) {
        // Ярость слышна и видна: низкий рёв и вспышка искр над головой.
        stage.playOccluded(sounds.mobAngry(m.type), m.soundPosition(), 1f, 0.62f);
        particles.emitHitImpact(m.position.x, m.position.y + m.type.height, m.position.z,
                0f, 1f, 0f, true, RAGE_SPARKS, 1f, 0f);
        stage.cueSound(m.soundPosition(), 1f, true);
    }

    @Override
    public void mobTookOff(Mob m) {
        stage.playOccluded(sounds.mobFly(m.type), m.soundPosition(), 0.35f, 1f + 0.2f * (float) Math.random());
    }

    @Override
    public void mobBit(Mob predator, Mob prey) {
        stage.playOccluded(sounds.mobAngry(predator.type), predator.soundPosition(), 0.6f, 1.1f);
        stage.playOccluded(sounds.mobHurt(prey.type), prey.soundPosition(), 0.6f, 1f);
    }

    @Override
    public void mobStep(Mob m) {
        stage.playOccluded(sounds.mobStep(m.type),
                new Vector3f(m.position.x, m.position.y + 0.1f, m.position.z),
                0.22f, 0.9f + 0.2f * (float) Math.random());
        if (m.type.hostile && m.state == Mob.State.CHASE)
            stage.cueSound(m.soundPosition(), 0.55f, true);
        // След моба крупнее или мельче по его габариту: курица и
        // корова не могут топтать снег одинаково.
        // Чётность шага берётся из пройденного пути самого моба:
        // общий переключатель на всех сбил бы дорожки в одну колею.
        boolean left = ((int) (m.walkedDistance / Mob.STEP_DISTANCE)) % 2 == 0;
        stage.dropFootprint(m.position.x, m.position.y, m.position.z, m.yaw,
                0.46f + m.type.width * 0.5f, left);
        if (m.position.distanceSquared(stage.listener()) < 24f * 24f)
            stage.kickSnow(m.position.x, m.position.y, m.position.z,
                    (float) -Math.sin(m.yaw), (float) -Math.cos(m.yaw), false);
    }

    @Override
    public void mobBurning(Mob m) {
        particles.emitMobFlame(m.position.x, m.position.y + m.type.height * 0.5f, m.position.z);
        if (Math.random() < 0.35)
            particles.emitMobSmoke(m.position.x, m.position.y + m.type.height * 0.9f, m.position.z);
    }

    @Override
    public void explosion(float x, float y, float z, Mob source) {
        if (source != null)
            particles.emitMobDeath(x, y, z, source.type.particleColor);
        for (int i = 0; i < 12; i++)
            particles.emitMobFlame(x + (float) (Math.random() - 0.5) * 2f,
                    y + (float) Math.random() * 1.5f, z + (float) (Math.random() - 0.5) * 2f);
        stage.playAt(sounds.ambientThunder(), new Vector3f(x, y, z), 0.9f, 1.5f);
        stage.invalidateShadows();
    }

    @Override
    public void mobDied(Mob m) {
        stage.playOccluded(sounds.mobDeath(m.type), m.soundPosition(),
                0.8f, 0.95f + 0.1f * (float) Math.random());
        particles.emitMobDeath(m.position.x, m.position.y + m.type.height * 0.5f,
                m.position.z, m.type.particleColor);
    }
}
