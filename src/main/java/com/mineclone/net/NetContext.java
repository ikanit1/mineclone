package com.mineclone.net;

import com.mineclone.save.ChunkSnapshot;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Mob;
import org.joml.Vector3f;

import java.util.List;

/**
 * Всё, что сессия знает об игре.
 *
 * <p>Тот же приём, что у окон инвентаря с их {@code WindowContext}: сетевая
 * сессия не видит {@code Game}, а видит десяток вопросов и поручений. Поэтому
 * её можно поднять в тесте без окна, GL и звука — и именно так проверяется
 * весь обмен, от рукопожатия до дельт чанков.
 *
 * <p>Списки мобов и предметов отдаются изменяемыми намеренно. У участника они
 * не живут своей жизнью, а целиком приходят от хозяина, и городить поверх
 * игровых объектов ещё один слой «показываемых» значило бы держать две копии
 * одного и того же.
 */
public interface NetContext {
    /** Stable installation identity; names can change without losing the checkpoint. */
    default String playerId() { return ""; }
    default PlayerData capturePlayerData() { return null; }
    default void restorePlayerData(PlayerData data) { }
    default PlayerData loadGuest(String id) { return null; }
    default void saveGuest(String id, PlayerData data) { }
    /** Only inventory/cursor change in a container response; never teleport a moving player. */
    default void containerInventory(ItemStack[] inventory, ItemStack cursor, boolean closed) { }

    // ------------------------------------------------------------------ мир

    /** Текущий мир или null, если игра в меню. */
    World world();

    long seed();

    String worldName();

    float timeOfDay();

    void setTimeOfDay(float t);

    /** The host's game time without a float's loss (protocol v8's {@code S_WELCOME}). */
    default double preciseTime() { return timeOfDay(); }

    /** The host's simulation ticks since the world began. */
    default long worldTicks() { return 0L; }

    /** Порядковый номер {@link com.mineclone.world.GameMode}. */
    int gameMode();

    /** Точка появления мира: x, y, z. */
    Vector3f spawn();

    /**
     * Участник получил мир хозяина — построить его у себя.
     *
     * <p>Мир не передаётся по сети: генерация детерминирована по сиду, и
     * участник строит ровно тот же рельеф сам. По сети едут только отличия —
     * то, что кто-то когда-то выкопал или поставил. Поэтому генерировать его
     * обязательно генератором хозяина: {@link RemoteWorld#generator()}.
     */
    void startRemoteWorld(RemoteWorld world);

    /**
     * Поставить блок, пришедший по сети.
     *
     * <p>Реализация обязана пометить правку как чужую: иначе наблюдатель мира
     * отправит её обратно, и правка будет ходить по кругу.
     *
     * @param broke блок сломали — сыграть звук и пыль, а не просто заменить
     */
    void applyRemoteBlock(int x, int y, int z, byte blockId, byte meta, boolean broke);

    /**
     * Игрок на другой машине поставил или сломал блок.
     *
     * <p>Состояние блока и звук разделены намеренно: один и тот же пакет
     * изменения мира также несут вода, рост растений и другие тихие тики.
     * Только это событие означает осознанное действие игрока.
     */
    void remoteBlockAction(int actor, int x, int y, int z, byte blockId, boolean broke);

    /** Снимок чанка с диска: хозяин отвечает и за то, что сейчас не загружено. */
    ChunkSnapshot loadSavedChunk(int cx, int cz);

    // --------------------------------------------------------------- игрок

    Vector3f playerPosition();

    float playerYaw();

    float playerPitch();

    /** Флаги {@link RemotePlayer}: на земле, бежит, в воде, летит, мёртв. */
    int playerFlags();

    float playerHealth();

    /** Appearance of the selected slot; no inventory authority is transferred. */
    default ItemStack playerHeldItem() { return null; }

    // ------------------------------------------------------------- существа

    /** Мобы мира: у хозяина — живые, у участника — присланные. */
    List<Mob> mobs();

    /** Предметы на земле. */
    List<ItemEntity> groundItems();

    /** Летящие и воткнувшиеся снаряды: хозяин их рассылает, участник показывает. */
    List<com.mineclone.world.entity.Projectile> projectiles();

    /**
     * Выпустить снаряд по просьбе участника.
     *
     * Стреляет всегда хозяин — как и с правкой блока, местный выстрел у
     * гостя нужен только ради отклика.
     *
     * @param actor чей выстрел: в стрелка снаряд не попадает
     */
    void shootFor(int actor, float x, float y, float z, float vx, float vy, float vz,
            float damage);

    /**
     * Хозяин сообщил, что игрок получил урон: сколько, чем, от кого и
     * откуда (протокол v8) — урон идёт через путь урона самого игрока.
     */
    void hurtByHost(com.mineclone.world.damage.DamageSource source, float damage);

    // ------------------------------------------------------- обратная связь

    /** Строка в чат: вход, выход, сообщение игрока. */
    void chatLine(String line);

    /** Короткое уведомление поверх интерфейса. */
    void status(String line);

    /** Хозяин прислал стопку: подобранное, выбитое, отданное. */
    void give(ItemStack stack);

    /** Сессия кончилась: причина текстом. */
    void netStopped(String reason);

    /** Участник: хозяин прислал содержимое открытого контейнера. */
    void containerFromHost(int x, int y, int z, int kind, ItemStack[] slots,
            float burnLeft, float burnMax, float cook);
}
