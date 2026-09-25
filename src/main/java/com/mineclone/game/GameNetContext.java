package com.mineclone.game;

import com.mineclone.net.Multiplayer;
import com.mineclone.net.NetContext;
import com.mineclone.net.PlayerData;
import com.mineclone.net.RemotePlayer;
import com.mineclone.net.RemoteWorld;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.sim.WorldSession;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.Furnace;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.Projectile;
import java.util.List;
import org.joml.Vector3f;

/**
 * Всё, что сетевая сессия знает об игре.
 *
 * <p>Тот же приём, что у окон инвентаря: сессия видит не {@code Game}, а
 * полтора десятка вопросов. Поэтому её проверяют парой в обычном тесте —
 * без окна, GL и звука. Moved out of {@code Game} whole (SIM-08); it reads
 * the game's current world, player and clock on every call.
 */
final class GameNetContext implements NetContext {
    private final Game g;

    GameNetContext(Game game) {
        this.g = game;
    }

    @Override public String playerId() { return g.save.playerId(); }
    @Override public PlayerData capturePlayerData() {
        return new PlayerData(g.capturePlayer());
    }
    @Override public void restorePlayerData(PlayerData data) {
        // v8: the host's whole record, so what this build does not model
        // (equipment, effects, a spawn, newer sections) comes back with the
        // guest's next checkpoint instead of being lost.
        g.restorePlayer(data.record());
    }
    @Override public PlayerData loadGuest(String id) {
        return g.worldId == null ? null : g.save.loadGuest(g.worldId, id);
    }
    @Override public void saveGuest(String id, PlayerData data) {
        if (g.worldId != null) g.save.saveGuest(g.worldId, id, data);
    }
    @Override public void containerInventory(ItemStack[] slots, ItemStack cursor, boolean closed) {
        for (int i = 0; i < slots.length; i++) g.inventory.set(i, slots[i]);
        if (g.activeWindow != null) g.activeWindow.menu().setCursor(closed ? null : cursor);
        if (closed && g.activeWindow != null) g.finishCloseWindow();
    }

    @Override
    public World world() {
        return g.world;
    }

    @Override
    public long seed() {
        return g.world == null ? 0L : g.world.seed;
    }

    @Override
    public String worldName() {
        return g.worldDisplayName;
    }

    @Override
    public float timeOfDay() {
        return g.worldClock.gameTimeFloat();
    }

    @Override public double preciseTime() { return g.worldClock.gameTime(); }
    @Override public long worldTicks() { return g.worldClock.worldTicks(); }

    @Override
    public void setTimeOfDay(float t) {
        g.worldClock.setGameTime(t);
        g.daylight = g.computeDaylight();
        g.invalidateShadows();
    }

    @Override
    public int gameMode() {
        return g.gameMode.ordinal();
    }

    @Override
    public Vector3f spawn() {
        return g.worldSpawn;
    }

    @Override
    public void startRemoteWorld(RemoteWorld remote) {
        g.startRemoteWorld(remote);
    }

    /** Правка блока, пришедшая по сети: та же, что своя, но без отправки обратно. */
    @Override
    public void applyRemoteBlock(int x, int y, int z, byte id, byte meta, boolean broke) {
        World world = g.world;
        if (world == null)
            return;
        BlockType now = BlockType.byId(id);
        BlockType before = world.getBlock(x, y, z);
        if (before == now && world.getBlockMeta(x, y, z) == meta)
            return;
        // A chest or furnace a guest removed spills inside setBlock, through
        // its behaviour — on the host only: a guest's world has no drop sink.
        world.setBlock(x, y, z, now, meta);
        if (!broke || before == BlockType.AIR)
            return;
        float pSky = world.getSkyLight(x, y, z) / (float) Chunk.MAX_LIGHT;
        float pBlk = world.getBlockLightWorld(x, y, z) / (float) Chunk.MAX_LIGHT;
        if (before.isCross() || before == BlockType.SNOW_LAYER)
            g.particles.emitBlockBreak(x, y, z, before.particleColor, before.sideTile, pSky, pBlk);
        else
            g.debris.spawn(x, y, z, before, pSky, pBlk);
    }

    @Override
    public void remoteBlockAction(int actor, int x, int y, int z, byte blockId,
            boolean broke) {
        playRemoteBlockAction(x, y, z, blockId, broke);
        // Гостя мобы слышат так же, как своего игрока.
        g.emitNoise(x + 0.5f, y + 0.5f, z + 0.5f, broke ? WorldSession.NOISE_BREAK : WorldSession.NOISE_PLACE);
    }

    /** Воспроизвести именно действие другого игрока, не тихий тик мира. */
    private void playRemoteBlockAction(int x, int y, int z, byte blockId, boolean broke) {
        if (g.world == null)
            return;
        BlockType block = BlockType.byId(blockId);
        List<String> samples = broke ? g.sounds.breakBlock(block) : g.sounds.place(block);
        g.sound.playOneOfAt(samples, Game.blockSoundPosition(x, y, z), broke ? 0.72f : 0.68f,
                0.9f + 0.2f * (float) Math.random());
        if (!broke)
            g.emitPlacementParticles(x, y, z, block);
    }

    @Override
    public ChunkSnapshot loadSavedChunk(int cx, int cz) {
        return g.worldId == null ? null : g.save.loadChunk(g.worldId, cx, cz);
    }

    @Override
    public Vector3f playerPosition() {
        return g.world == null ? null : g.player.position;
    }

    @Override
    public float playerYaw() {
        return g.player.camera.yaw;
    }

    @Override
    public float playerPitch() {
        return g.player.camera.pitch;
    }

    @Override
    public int playerFlags() {
        Player player = g.player;
        int f = 0;
        if (player.onGround)
            f |= RemotePlayer.F_ON_GROUND;
        if (player.isSprinting)
            f |= RemotePlayer.F_SPRINT;
        if (player.inWater)
            f |= RemotePlayer.F_IN_WATER;
        if (player.flying)
            f |= RemotePlayer.F_FLYING;
        if (player.isDead())
            f |= RemotePlayer.F_DEAD;
        return f;
    }

    @Override
    public ItemStack playerHeldItem() {
        return g.inventory.get(g.selectedSlot);
    }

    @Override
    public float playerHealth() {
        return g.player.health;
    }

    @Override
    public List<Mob> mobs() {
        return g.world == null ? null : g.mobs;
    }

    @Override
    public List<ItemEntity> groundItems() {
        return g.world == null ? null : g.items;
    }

    @Override
    public List<Projectile> projectiles() {
        return g.world == null ? null : g.projectiles;
    }

    /** Выстрел участника: снаряд рождается у хозяина и летит у него же. */
    @Override
    public void shootFor(int actor, float x, float y, float z,
                         float vx, float vy, float vz, float damage) {
        var shot = new Projectile(com.mineclone.item.Bow.AMMO, g.net.playerOf(actor), true, damage);
        shot.position.set(x, y, z);
        shot.velocity.set(vx, vy, vz);
        if (shot.velocity.lengthSquared() > 1e-6f)
            shot.heading.set(shot.velocity).normalize();
        g.addProjectile(shot);
    }

    @Override
    public void hurtByHost(DamageSource source, float damage) {
        // Обратную связь поднимет updateDamageFeedback по самой
        // потере здоровья — ей всё равно, кто и чем ударил. Отброс и
        // звук — как у своего игрока хозяина: от удара и от взрыва
        // (participantStruck, blasted), стрела не отбрасывает.
        if (!g.player.damage(source, damage) || !source.hasOrigin())
            return;
        var kind = source.type();
        if (kind == DamageType.MELEE || kind == DamageType.EXPLOSION)
            g.applyKnockbackFrom(source.originX(), source.originZ());
        if (kind == DamageType.MELEE)
            g.sound.playOneOfAt(g.sounds.hurt(), g.playerSoundPosition(),
                    0.8f, 0.9f + 0.1f * (float) Math.random());
    }

    @Override
    public void chatLine(String line) {
        g.netChat.addLast(line);
        while (g.netChat.size() > 8)
            g.netChat.removeFirst();
        g.netChatTimer = Game.NET_CHAT_LINGER;
    }

    @Override
    public void status(String line) {
        g.showCommandToast(line);
    }

    @Override
    public void give(ItemStack stack) {
        if (stack == null || stack.count <= 0)
            return;
        int left = g.giveStack(stack);
        if (left > 0)
            g.dropItem(stack.copyWithCount(left), g.player.position.x,
                    g.player.position.y + 0.6f, g.player.position.z);
    }

    @Override
    public void netStopped(String reason) {
        g.onNetStopped(reason);
    }

    /** Участник: хозяин прислал настоящее содержимое открытого контейнера. */
    @Override
    public void containerFromHost(int x, int y, int z, int kind, ItemStack[] slots,
            float burnLeft, float burnMax, float cook) {
        World world = g.world;
        if (world == null)
            return;
        if (kind == Multiplayer.CONTAINER_CHEST
                && world.getBlock(x, y, z) == BlockType.CHEST) {
            ItemStack[] live = world.createChest(x, y, z);
            for (int i = 0; i < live.length; i++)
                live[i] = i < slots.length ? slots[i] : null;
        } else if (kind == Multiplayer.CONTAINER_FURNACE
                && world.getBlock(x, y, z) == BlockType.FURNACE) {
            Furnace f = world.createFurnace(x, y, z);
            f.input = slots.length > 0 ? slots[0] : null;
            f.fuel = slots.length > 1 ? slots[1] : null;
            f.output = slots.length > 2 ? slots[2] : null;
            f.burnLeft = burnLeft;
            f.burnMax = burnMax;
            f.cook = cook;
        }
    }
}
