package com.mineclone.net;

import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.ItemStackCodec;
import com.mineclone.sim.Participant;
import com.mineclone.sim.Participants;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.Furnace;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.entity.Projectile;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Сетевая сессия: кто хозяин, кто где стоит и что стало с блоком.
 *
 * <p>Власть хозяйская. Мир живёт у хозяина комнаты: он его генерирует, тикает,
 * сохраняет и рассылает изменения. Участник не «сам себе мир с подсказками», а
 * зеркало: свои правки он применяет сразу, чтобы кирка не залипала, но
 * последнее слово всегда за хозяином. Разойтись они не могут — хозяин
 * рассылает результат каждой правки всем, включая того, кто её попросил.
 *
 * <p>Мир по сети не передаётся. Генерация детерминирована по сиду и это
 * проверено тестом, поэтому участник строит тот же рельеф сам, а по проводу
 * едет только разница: чем чанк отличается от свежесгенерированного. У
 * нетронутого чанка разницы нет вовсе, и это самый частый случай — поэтому
 * вход в чужой мир стоит десятки килобайт, а не десятки мегабайт. ADR:
 * {@code knowledge/decisions/multiplayer-photon.md}.
 *
 * <p>Класс не знает ни про GL, ни про окно, ни про {@code Game}: всё, что ему
 * нужно от игры, приходит через {@link NetContext}. Поэтому сессию поднимают
 * в тестах парой и гоняют настоящий обмен без единого кадра.
 */
public final class Multiplayer implements NetTransport.Listener {

    /** Роль в комнате. */
    public enum Role {
        NONE, HOST, CLIENT
    }

    /** Сколько заявок на дельту чанка участник шлёт за один тик. */
    private static final int CHUNK_REQUESTS_PER_TICK = 6;
    /** Через сколько молчания чужой игрок считается отвалившимся. */
    private static final float SILENCE_LIMIT = 12f;
    /**
     * Сколько участник пытается вернуться в комнату, прежде чем сдаться.
     *
     * <p>Тридцать секунд — это запас на переезд с вышки на вышку, на
     * переподключение Wi-Fi и на минутную грозу у провайдера. Меньше — и
     * обычный обрыв всё ещё стоит партии; больше — и человек сидит перед
     * застывшим миром, не понимая, вернётся он или нет.
     */
    public static final float RESUME_WINDOW = 30f;
    /** Пауза между попытками вернуться. */
    private static final float RESUME_RETRY = 3f;
    /** Как часто хозяин рассылает предметы на земле. */
    private static final float ITEM_SYNC_INTERVAL = 0.25f;
    /** Сколько строк чата помнится. */
    private static final int CHAT_HISTORY = 48;
    /** Вид контейнера в пакете. */
    public static final int CONTAINER_CHEST = 0;
    public static final int CONTAINER_FURNACE = 1;

    private final NetContext ctx;
    private final InventorySync inventorySync;
    private NetTransport transport;
    private NetChannel channel;
    private Role role = Role.NONE;

    private String nickname = "Игрок";
    private String roomName = "";
    private int hostActor;
    private boolean joined;
    /** Участник: пришло ли уже описание мира. */
    private boolean worldReady;
    private String status = "";
    private String lastError = "";

    /**
     * Чем поднять транспорт заново.
     *
     * <p>Переподключение не может переиспользовать прежний транспорт: у него
     * закрыты сокеты, а у Photon-клиента ещё и просрочен ключ сессии. Поэтому
     * сессия держит не сам транспорт, а способ сделать новый. Без фабрики
     * ({@link #start(NetTransport, String, boolean, String)}) переподключения
     * просто нет — так заходят тесты, которым оно ни к чему.
     */
    private TransportFactory factory;
    private boolean createRoom;
    /** Участник потерял связь и пробует вернуться. */
    private boolean resuming;
    private float resumeLeft;
    private float resumeRetry;
    private String resumeReason = "";

    private final Map<Integer, RemotePlayer> players = new LinkedHashMap<>();
    private final Deque<String> chat = new ArrayDeque<>();

    /**
     * Who the world is simulated for; see {@link #participants()}. Rebuilt from
     * the role, the handshake and the player map after every event that can
     * change them, so it never drifts from what the session believes.
     */
    private final Participants participants = new Participants();
    private Participant localParticipant;
    private final Map<Integer, RemoteParticipant> remoteParticipants = new HashMap<>();

    private float tickTimer;
    private float timeSyncTimer;
    private float itemSyncTimer;
    /** Пока true, правка блока пришла по сети и обратно не уходит. */
    private boolean applyingRemote;
    private boolean swingPending;
    private float infoTimer;
    private float lastSentHealth = -1f;

    // ---- участник: какие чанки уже спрошены ----
    private final Set<Long> requestedChunks = new HashSet<>();
    private final Deque<Long> chunkQueue = new ArrayDeque<>();

    // ---- хозяин: нумерация существ и фоновой расчёт дельт ----
    private final Map<Mob, Integer> mobIds = new IdentityHashMap<>();
    private final Map<ItemEntity, Integer> itemIds = new IdentityHashMap<>();
    private int nextEntityId = 1;
    private ExecutorService deltaWorker;
    private World pristine;
    private final ConcurrentLinkedQueue<Runnable> deltaResults = new ConcurrentLinkedQueue<>();

    // ---- участник: существа по номеру ----
    private final Map<Integer, Mob> shownMobs = new HashMap<>();
    private final Map<Integer, RemoteMob> mobPoses = new HashMap<>();
    private long mobSequence, lastMobSequence = -1;
    private long equipmentSequence;
    private String lastHeldId;
    private final Map<Integer, ItemEntity> shownItems = new HashMap<>();
    private final Random visualRandom = new Random(0x5EED);

    public Multiplayer(NetContext ctx) {
        this.ctx = ctx;
        this.inventorySync = new InventorySync(this,ctx);
    }

    PacketBuf inventoryPacket(int code,int actor) { return channel.packet(code,true,actor); }
    int hostActor() { return hostActor; }
    void flushInventory() { if(channel!=null)channel.flush(); }
    void rejectPlayer(int actor,String reason) { channel.packet(NetProto.S_REJECT,true,actor).str(reason); }
    public boolean inventoryBusy() { return inventorySync.busy(); }
    public void savePlayerNow() { inventorySync.saveNow();flushInventory(); }
    public void bindContainer(com.mineclone.ui.container.ContainerMenu menu) { inventorySync.bind(menu); }
    public boolean closeContainer() { return inventorySync.close(); }
    public boolean requestDrop(ItemEntity item) { return inventorySync.drop(item); }

    // ------------------------------------------------------------- запуск

    /** Как сделать новый транспорт: нужен, чтобы вернуться после обрыва. */
    public interface TransportFactory {
        NetTransport create();
    }

    /**
     * Начать сессию, умеющую вернуться после обрыва.
     *
     * @param create создаём комнату (значит будем хозяином) или входим в чужую
     */
    public void start(TransportFactory maker, String room, boolean create, String nick) {
        start(maker.create(), room, create, nick);
        this.factory = maker;
        this.createRoom = create;
    }

    /**
     * Начать сессию на готовом транспорте.
     *
     * <p>Без фабрики, а значит и без переподключения: так заходят тесты и
     * петля, которой возвращаться неоткуда.
     *
     * @param create создаём комнату (значит будем хозяином) или входим в чужую
     */
    public void start(NetTransport t, String room, boolean create, String nick) {
        stop(null);
        this.transport = t;
        this.channel = new NetChannel(t);
        this.roomName = room == null ? "" : room;
        this.nickname = (nick == null || nick.isBlank()) ? "Игрок" : nick.trim();
        if (this.nickname.length() > NetProto.NAME_LIMIT)
            this.nickname = this.nickname.substring(0, NetProto.NAME_LIMIT);
        this.role = Role.NONE;
        this.status = "подключение…";
        this.lastError = "";
        t.connect(this.roomName, create, this.nickname);
    }

    public void stop(String reason) {
        if(channel!=null)inventorySync.stopping();
        factory = null;
        resuming = false;
        resumeLeft = 0f;
        resumeReason = "";
        if (transport != null) {
            if (channel != null)
                channel.discard();
            transport.disconnect();
        }
        transport = null;
        channel = null;
        role = Role.NONE;
        joined = false;
        worldReady = false;
        hostActor = 0;
        players.clear();
        requestedChunks.clear();
        chunkQueue.clear();
        mobIds.clear();
        itemIds.clear();
        shownMobs.clear();
        mobPoses.clear();
        mobSequence = 0;
        equipmentSequence = 0;
        lastHeldId = null;
        lastMobSequence = -1;
        shownItems.clear();
        deltaResults.clear();
        if (deltaWorker != null) {
            deltaWorker.shutdownNow();
            deltaWorker = null;
        }
        pristine = null;
        inventorySync.reset();
        if (reason != null && !reason.isEmpty()) {
            lastError = reason;
            status = reason;
        }
        syncParticipants();
    }

    // ------------------------------------------------------------- сведения

    public boolean active() {
        return transport != null;
    }

    public boolean isHost() {
        return role == Role.HOST;
    }

    public boolean isClient() {
        return role == Role.CLIENT;
    }

    /** Участник уже знает, в какой мир он попал. */
    public boolean worldReady() {
        return role == Role.HOST || worldReady;
    }

    public boolean joined() {
        return joined;
    }

    public String status() {
        return status;
    }

    public String lastError() {
        return lastError;
    }

    /** Связь потеряна и мы пробуем вернуться: мир пока держим. */
    public boolean resuming() {
        return resuming;
    }

    /** Сколько секунд ещё будем пробовать. */
    public float resumeLeft() {
        return Math.max(0f, resumeLeft);
    }

    public String roomName() {
        return roomName;
    }

    public String nickname() {
        return nickname;
    }

    public Collection<RemotePlayer> players() {
        return players.values();
    }

    /**
     * Everyone the local simulation runs for, as a live list.
     *
     * <p>Host: its own player plus every guest that completed the handshake and
     * has reported a position. Dedicated server: the guests only, because it
     * never registers a local participant. Offline: the local player alone.
     * Client: nobody — a guest mirrors the host's world and simulates nothing.
     * A timed-out guest leaves the list and returns with its next state while
     * its handshake identity is still valid; an actor that never said hello
     * never enters, whatever packets it sends.
     */
    public Participants participants() {
        return participants;
    }

    /** The player of this process; null for a dedicated server. */
    public void setLocalParticipant(Participant local) {
        localParticipant = local;
        syncParticipants();
    }

    /** This process's actor number in the room, or 0 when not in one. */
    public int localActor() {
        return transport != null && joined ? transport.myActor() : 0;
    }

    private void syncParticipants() {
        participants.clear();
        if (localParticipant != null && role != Role.CLIENT)
            participants.put(localParticipant);
        if (role != Role.HOST) {
            remoteParticipants.clear();
            return;
        }
        remoteParticipants.keySet().retainAll(players.keySet());
        for (RemotePlayer p : players.values()) {
            if (!p.placed() || !inventorySync.identified(p.actor))
                continue;
            RemoteParticipant wrapper = remoteParticipants.get(p.actor);
            // A guest that timed out comes back as a new RemotePlayer object.
            if (wrapper == null || wrapper.player() != p) {
                wrapper = new RemoteParticipant(p);
                remoteParticipants.put(p.actor, wrapper);
            }
            participants.put(wrapper);
        }
    }

    public List<String> chatLog() {
        return new ArrayList<>(chat);
    }

    /** Строка для отладочного экрана. */
    public String debugLine() {
        if (transport == null)
            return "offline";
        return (role == Role.HOST ? "host" : role == Role.CLIENT ? "client" : "…")
                + " " + transport.describe()
                + " peers=" + players.size()
                + " msg=" + (channel == null ? 0 : channel.messagesSent())
                + " tx=" + (channel == null ? 0 : channel.bytesSent() / 1024) + "k";
    }

    // --------------------------------------------------------------- кадр

    /** Один кадр сессии. Зовётся из игрового потока. */
    public void update(float dt) {
        updateSession(dt);
        syncParticipants();
    }

    private void updateSession(float dt) {
        if (resuming) {
            tryResume(dt);
            if (transport == null)
                return;
        }
        if (transport == null)
            return;
        transport.poll();
        // Результаты фоновых дельт применяются здесь же: рассылка обязана
        // идти из того потока, что и всё остальное.
        Runnable r;
        while ((r = deltaResults.poll()) != null)
            r.run();
        if (transport == null)
            return;

        for (RemotePlayer p : players.values())
            p.update(dt);
        // На клиенте AI/физика мобов не тикают. Часы визуальных поз всё же
        // идут каждый кадр, иначе крылья и дыхание навсегда замирают.
        if (role == Role.CLIENT)
            for (RemoteMob pose : mobPoses.values())
                pose.update(dt);
        players.values().removeIf(p -> p.silence > SILENCE_LIMIT);

        if (!joined)
            return;
        inventorySync.update(dt);
        tickTimer += dt;
        float interval = 1f / NetProto.TICK_RATE;
        if (tickTimer < interval)
            return;
        tickTimer = 0f;
        sendPlayerState();
        if (role == Role.HOST) {
            timeSyncTimer += interval;
            if (timeSyncTimer >= NetProto.TIME_SYNC_INTERVAL) {
                timeSyncTimer = 0f;
                channel.packet(NetProto.S_TIME, true, NetChannel.ALL).f32(ctx.timeOfDay());
            }
            sendMobs();
            itemSyncTimer += interval;
            if (itemSyncTimer >= ITEM_SYNC_INTERVAL) {
                itemSyncTimer = 0f;
                sendItems();
            }
            // Снаряды идут каждым сетевым кадром, а не раз в секунду, как
            // предметы: стрела живёт секунду-две, и снимок раз в секунду
            // показал бы её телепортом или не показал вовсе.
            sendProjectiles();
        } else {
            pumpChunkRequests();
        }
        channel.flush();
    }

    private void sendPlayerState() {
        Vector3f p = ctx.playerPosition();
        if (p == null)
            return;
        PacketBuf b = channel.packet(NetProto.X_PLAYER_STATE, false, NetChannel.ALL);
        b.f32(p.x).f32(p.y).f32(p.z).f32(ctx.playerYaw()).f32(ctx.playerPitch())
                .u8(ctx.playerFlags());
        String heldId = heldItemId();
        if (!heldId.equals(lastHeldId)) {
            sendEquipment(NetChannel.ALL);
            lastHeldId = heldId;
        }
        if (swingPending) {
            swingPending = false;
            channel.send(NetProto.X_PLAYER_SWING, true, NetChannel.ALL);
        }
        // Здоровье и режим — редко и надёжно: каждый тик они не меняются, а
        // потерянный пакет с ними оставил бы чужую полоску здоровья враньём.
        infoTimer += 1f / NetProto.TICK_RATE;
        float health = ctx.playerHealth();
        if (infoTimer >= 2f || Math.abs(health - lastSentHealth) > 0.01f) {
            infoTimer = 0f;
            lastSentHealth = health;
            sendInfo(NetChannel.ALL);
        }
    }

    private String heldItemId() {
        ItemStack held = ctx.playerHeldItem();
        return held == null || held.count <= 0 ? "" : held.item.id.toString();
    }

    private void sendEquipment(int target) {
        channel.packet(NetProto.X_PLAYER_EQUIPMENT, true, target)
                .i64(++equipmentSequence).str(heldItemId());
    }

    private void sendInfo(int target) {
        sendIdentity(target);
        sendEquipment(target);
    }

    private void sendIdentity(int target) {
        channel.packet(NetProto.X_PLAYER_INFO, true, target)
                .str(nickname).u8(ctx.gameMode()).f32(ctx.playerHealth());
    }

    // -------------------------------------------------------------- блоки

    /**
     * Мир изменился у нас — рассказать остальным.
     *
     * <p>Зовётся наблюдателем мира, то есть на любую правку: и на удар киркой,
     * и на растёкшуюся воду, и на выросший кактус. У хозяина это рассылка
     * результата, у участника — просьба.
     */
    public void onWorldBlockChanged(int x, int y, int z, BlockType old, BlockType now, byte meta) {
        if (!joined || applyingRemote || channel == null)
            return;
        boolean broke = now == BlockType.AIR && old != BlockType.AIR;
        if (role == Role.HOST) {
            PacketBuf b = channel.packet(NetProto.S_BLOCK_SET, true, NetChannel.ALL);
            b.blockPos(x, y, z).u8(now.ordinal()).u8(meta).u8(broke ? 1 : 0);
        } else if (role == Role.CLIENT) {
            PacketBuf b = channel.packet(NetProto.C_BLOCK_EDIT, true, hostActor);
            b.blockPos(x, y, z).u8(now.ordinal()).u8(meta).u8(broke ? 1 : 0);
        }
    }

    /** Участник: чанк появился — спросить, чем он отличается от чистого. */
    public void noteChunkLoaded(int cx, int cz) {
        if (role != Role.CLIENT || !joined)
            return;
        long key = World.key(cx, cz);
        if (requestedChunks.add(key))
            chunkQueue.add(key);
    }

    private void pumpChunkRequests() {
        for (int i = 0; i < CHUNK_REQUESTS_PER_TICK && !chunkQueue.isEmpty(); i++) {
            long key = chunkQueue.poll();
            channel.packet(NetProto.C_CHUNK_REQUEST, true, hostActor)
                    .i32((int) (key >> 32)).i32((int) key);
        }
    }

    /** Замах рукой: уйдёт в ближайшем тике. */
    public void noteSwing() {
        swingPending = true;
    }

    /**
     * Поставлен или сломан блок. Само изменение мира уже едет своим пакетом,
     * но он также описывает тихие тики воды и растений — звук нужен только
     * для явного действия игрока.
     */
    public void noteBlockAction(BlockType block, boolean broke, int x, int y, int z) {
        if (!joined || channel == null || block == null)
            return;
        channel.packet(NetProto.X_BLOCK_ACTION, true, NetChannel.ALL)
                .blockPos(x, y, z).u8(block.ordinal()).u8(broke ? 1 : 0);
    }

    public void sendChat(String text) {
        if (!joined || text == null || text.isBlank())
            return;
        String line = text.trim();
        if (line.length() > NetProto.CHAT_LIMIT)
            line = line.substring(0, NetProto.CHAT_LIMIT);
        channel.packet(NetProto.X_CHAT, true, NetChannel.ALL).str(line);
        channel.flush();
        addChat(nickname + ": " + line);
    }

    private void addChat(String line) {
        chat.addLast(line);
        while (chat.size() > CHAT_HISTORY)
            chat.removeFirst();
        ctx.chatLine(line);
    }

    // ---------------------------------------------------------- контейнеры

    /** Участник открыл сундук или печь — попросить настоящее содержимое. */
    public void requestContainer(int x, int y, int z) {
        inventorySync.open(x,y,z);
    }

    /**
     * Участник дотянулся до предмета — решает всё равно хозяин.
     *
     * <p>Следующий подбор и изменение инвентаря ждут ответа хозяина,
     * чтобы снимок открываемого сундука не затёр только что подобранное.
     */
    public void requestPickup(ItemEntity e) {
        if (role != Role.CLIENT || !joined || inventoryBusy())
            return;
        Integer id = idOf(shownItems, e);
        if (id == null)
            return;
        inventorySync.pickup(id);
    }

    /** Участник ударил моба: урон и отброс считает хозяин. */
    public void requestMobHit(Mob m, float damage, float knockback, float fromX, float fromZ) {
        if (role != Role.CLIENT || !joined)
            return;
        Integer id = idOf(shownMobs, m);
        if (id == null)
            return;
        channel.packet(NetProto.C_MOB_HIT, true, hostActor)
                .varInt(id).f32(damage).f32(knockback).f32(fromX).f32(fromZ);
    }

    /** Удалённый игрок по номеру, либо null: нужен снаряду, чтобы не бить стрелка. */
    public RemotePlayer playerOf(int actor) {
        return players.get(actor);
    }

    /** Номер существа в карте показываемых; поиск по ссылке, а не по равенству. */
    private static <T> Integer idOf(Map<Integer, T> shown, T value) {
        for (Map.Entry<Integer, T> e : shown.entrySet())
            if (e.getValue() == value)
                return e.getKey();
        return null;
    }

    // -------------------------------------------------- ответы транспорта

    @Override
    public void onState(NetTransport.State state, String detail) {
        switch (state) {
            case CONNECTING -> status = detail;
            case JOINED -> status = "в комнате " + detail;
            case FAILED -> {
                lastError = detail;
                if (resuming) {
                    // Попытка вернуться не удалась — это ещё не конец: окно
                    // на то и окно, чтобы вместить несколько попыток. Закрыть
                    // сессию здесь значило бы дать ровно одну.
                    resumeReason = detail;
                    status = "связь потеряна, возвращаемся…";
                    dropTransport();
                    return;
                }
                if (canResume()) {
                    beginResume(detail);
                    return;
                }
                ctx.netStopped(detail);
                stop(detail);
            }
            case CLOSED -> status = "отключено";
            default -> {
            }
        }
        syncParticipants();
    }

    /**
     * Есть ли куда возвращаться.
     *
     * <p>Только участнику и только с построенным миром. Хозяину возвращаться
     * не к чему — мир у него и так в памяти, а комнату он откроет заново сам.
     * Участнику же до того, как мир построен, терять нечего: он ещё на экране
     * загрузки, и честный отказ там полезнее молчаливого ожидания.
     */
    private boolean canResume() {
        return factory != null && role == Role.CLIENT && worldReady && !resuming;
    }

    /**
     * Потеряли связь — держим мир и пробуем вернуться.
     *
     * <p>Мир не выгружается: он построен из сида и никуда не делся, а
     * выгрузить его значит отдать игроку титульный экран за секундный обрыв.
     * Не вернулись за {@link #RESUME_WINDOW} — тогда уже по-настоящему.
     */
    private void beginResume(String reason) {
        resumeReason = reason == null ? "" : reason;
        resuming = true;
        resumeLeft = RESUME_WINDOW;
        resumeRetry = 0f;
        joined = false;
        status = "связь потеряна, возвращаемся…";
        addChat("Связь потеряна — возвращаемся в комнату");
        dropTransport();
    }

    /** Закрыть транспорт, не трогая мир и не забывая, кто мы. */
    private void dropTransport() {
        if (channel != null)
            channel.discard();
        if (transport != null)
            transport.disconnect();
        transport = null;
        channel = null;
        // Чужие игроки приедут снова: их положение за время обрыва устарело,
        // а фигуры, застывшие там, где их застал обрыв, — это враньё.
        players.clear();
        // Дельты чанков спросим заново: пока нас не было, там могли копать.
        requestedChunks.clear();
        chunkQueue.clear();
        shownMobs.clear();
        mobPoses.clear();
        mobSequence = 0;
        equipmentSequence = 0;
        lastHeldId = null;
        lastMobSequence = -1;
        shownItems.clear();
    }

    /** Очередная попытка вернуться. Зовётся из {@link #update}. */
    private void tryResume(float dt) {
        resumeLeft -= dt;
        if (resumeLeft <= 0f) {
            resuming = false;
            String why = resumeReason.isEmpty() ? "связь потеряна" : resumeReason;
            ctx.netStopped(why);
            stop(why);
            return;
        }
        if (transport != null)
            return;
        resumeRetry -= dt;
        if (resumeRetry > 0f)
            return;
        resumeRetry = RESUME_RETRY;
        transport = factory.create();
        channel = new NetChannel(transport);
        // Создавать комнату не пытаемся, даже если так начинали: она уже есть,
        // а «создать» на её месте значит войти вторым хозяином в пустой мир.
        transport.connect(roomName, false, nickname);
    }

    /**
     * Спросить дельты для всего, что уже построено.
     *
     * <p>Обычно заявка на дельту уходит, когда чанк только загрузился. После
     * возвращения таких событий не будет — чанки давно стоят, — а за время
     * обрыва в них могли копать. Поэтому спрашиваем всё разом.
     */
    private void requestLoadedChunks() {
        World world = ctx.world();
        if (world == null)
            return;
        for (Chunk c : world.getLoadedChunks())
            noteChunkLoaded(c.cx, c.cz);
    }

    @Override
    public void onJoined(int myActor, boolean created) {
        boolean returning = resuming;
        resuming = false;
        resumeLeft = 0f;
        joined = true;
        role = created ? Role.HOST : Role.CLIENT;
        hostActor = created ? myActor : transport.masterActor();
        status = role == Role.HOST ? "мир открыт" : "вход в мир…";
        if (role == Role.HOST) {
            deltaWorker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mineclone-net-chunks");
                t.setDaemon(true);
                return t;
            });
            // The baseline must generate exactly like the live world, or every
            // generator difference would travel as an "edit".
            World live = ctx.world();
            pristine = live != null ? live.blankTwin() : new World(ctx.seed());
            worldReady = true;
            addChat("Мир открыт: комната «" + roomName + "»");
        } else {
            channel.packet(NetProto.C_HELLO, true, hostActor)
                    .varInt(NetProto.VERSION).str(nickname).str(inventorySync.identity());
            channel.flush();
            if (returning) {
                // Мир у нас уже есть — второй раз строить его не надо, и
                // хозяйский WELCOME это учтёт сам: startRemoteWorld у
                // участника, который уже в мире, ничего не пересоздаёт.
                status = "вернулись в комнату";
                addChat("Связь восстановлена");
                requestLoadedChunks();
            }
        }
        syncParticipants();
    }

    @Override
    public void onActorJoin(int actor, String name) {
        RemotePlayer joinedPlayer = players.computeIfAbsent(actor, a -> new RemotePlayer(a, name));
        joinedPlayer.name = name;
        joinedPlayer.onHurt = damage -> hurtPlayer(actor, (float) damage);
        if (role == Role.HOST)
            addChat(displayName(actor) + " подключается…");
        // Новому соседу надо знать, кто мы: он только что пришёл и наш
        // редкий пакет сведений мог уйти задолго до него.
        if (joined && channel != null)
            // Keep the pre-handshake message readable by older builds so they can read S_REJECT.
            sendIdentity(actor);
        syncParticipants();
    }

    @Override
    public void onActorLeave(int actor) {
        inventorySync.left(actor);
        RemotePlayer gone = players.remove(actor);
        if (gone != null)
            addChat(gone.name.isEmpty() ? "Игрок вышел" : gone.name + " вышел");
        if (role == Role.CLIENT && actor == hostActor) {
            // Мир жил у хозяина. Переезжать некуда: у нас нет ни содержимого
            // сундуков, ни состояния мобов, ни права писать сейв.
            ctx.netStopped("хозяин мира вышел");
            stop("хозяин мира вышел");
        }
        syncParticipants();
    }

    @Override
    public void onRoomList(List<NetTransport.RoomInfo> rooms) {
        // Список комнат нужен экрану, а не сессии: она к этому моменту уже
        // знает, куда идёт.
    }

    @Override
    public void onPayload(int from, byte[] data) {
        PacketBuf in = PacketBuf.reading(data);
        while (in.hasMore()) {
            int code = in.readU8();
            // Незнакомый код или оборванное тело — дальше в этом сообщении
            // каша: длина пакета нигде не написана, и следующий байт уже не
            // код. Остаток выбрасывается целиком.
            if (!handle(from, code, in) || in.truncated())
                break;
        }
        syncParticipants();
    }

    private boolean handle(int from, int code, PacketBuf in) {
        if(inventorySync.handle(from,code,in))return true;
        switch (code) {
            case NetProto.C_HELLO -> onHello(from, in);
            case NetProto.S_WELCOME -> onWelcome(from, in);
            case NetProto.S_REJECT -> {
                String why = in.readStr();
                if (!in.truncated() && role == Role.CLIENT && from == hostActor) {
                    ctx.netStopped(why);
                    stop(why);
                }
            }
            case NetProto.X_PLAYER_STATE -> {
                float x = in.readF32(), y = in.readF32(), z = in.readF32();
                float yaw = in.readF32(), pitch = in.readF32();
                int flags = in.readU8();
                if (!in.truncated())
                    player(from).accept(x, y, z, yaw, pitch, flags);
            }
            case NetProto.X_PLAYER_EQUIPMENT -> {
                long sequence = in.readI64();
                String id = in.readStr();
                if (!in.truncated()) player(from).acceptEquipment(sequence, id);
            }
            case NetProto.X_PLAYER_INFO -> {
                String name = in.readStr();
                int mode = in.readU8();
                float health = in.readF32();
                if (in.truncated())
                    return false;
                RemotePlayer p = player(from);
                p.name = name;
                p.gameMode = mode;
                p.health = health;
            }
            case NetProto.X_PLAYER_SWING -> player(from).startSwing();
            case NetProto.X_BLOCK_ACTION -> {
                int[] at = in.readBlockPos();
                int id = in.readU8();
                boolean broke = in.readU8() != 0;
                if (!in.truncated())
                    ctx.remoteBlockAction(from, at[0], at[1], at[2], (byte) id, broke);
            }
            case NetProto.X_PLAYER_LIFE -> in.readU8();
            case NetProto.S_BLOCK_SET -> {
                int[] at = in.readBlockPos();
                int id = in.readU8();
                int meta = in.readU8();
                boolean broke = in.readU8() != 0;
                if (!in.truncated() && role == Role.CLIENT && from == hostActor)
                    applyRemote(at[0], at[1], at[2], (byte) id, (byte) meta, broke);
            }
            case NetProto.C_BLOCK_EDIT -> {
                int[] at = in.readBlockPos();
                int id = in.readU8();
                int meta = in.readU8();
                boolean broke = in.readU8() != 0;
                if (!in.truncated() && role == Role.HOST)
                    // Хозяин применяет правку у себя, а наблюдатель мира сам
                    // разошлёт её всем — включая того, кто просил.
                    ctx.applyRemoteBlock(at[0], at[1], at[2], (byte) id, (byte) meta, broke);
            }
            case NetProto.C_CHUNK_REQUEST -> {
                int cx = in.readI32(), cz = in.readI32();
                if (!in.truncated() && role == Role.HOST)
                    queueChunkDelta(from, cx, cz);
            }
            case NetProto.S_CHUNK_DELTA -> onChunkDelta(from, in);
            case NetProto.S_TIME -> {
                float t = in.readF32();
                if (!in.truncated() && role == Role.CLIENT && from == hostActor)
                    ctx.setTimeOfDay(t);
            }
            case NetProto.S_MOBS -> onMobs(from, in);
            case NetProto.S_ITEMS -> onItems(from, in);
            case NetProto.S_PROJECTILES -> onProjectiles(from, in);
            case NetProto.C_SHOOT -> onShoot(from, in);
            case NetProto.S_PLAYER_HURT -> onPlayerHurt(from, in);
            case NetProto.C_MOB_HIT -> {
                int id = in.readVarInt();
                float damage = in.readF32(), knockback = in.readF32();
                float fx = in.readF32(), fz = in.readF32();
                if (!in.truncated() && role == Role.HOST)
                    hostMobHit(id, damage, knockback, fx, fz);
            }
            case NetProto.C_ITEM_PICK -> {
                // Retired in v7: pickup must checkpoint both ownership changes together.
                in.readVarInt();
            }
            case NetProto.S_GIVE -> {
                ItemStack s = readStack(in);
                if (!in.truncated() && s != null && role == Role.CLIENT && from == hostActor)
                    ctx.give(s);
            }
            case NetProto.X_CHAT -> {
                String line = in.readStr();
                if (!in.truncated()) {
                    player(from).say(line);
                    addChat(displayName(from) + ": " + line);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private RemotePlayer player(int actor) {
        return players.computeIfAbsent(actor,
                a -> {
                    RemotePlayer made = new RemotePlayer(a,
                            transport == null ? "" : transport.actorName(a));
                    made.onHurt = damage -> hurtPlayer(a, (float) damage);
                    return made;
                });
    }

    private String displayName(int actor) {
        RemotePlayer p = players.get(actor);
        if (p != null && !p.name.isEmpty())
            return p.name;
        String n = transport == null ? "" : transport.actorName(actor);
        return n.isEmpty() ? "Игрок " + actor : n;
    }

    // ------------------------------------------------------- рукопожатие

    private void onHello(int from, PacketBuf in) {
        int version = in.readVarInt();
        String name = in.readStr();
        if (in.truncated() || role != Role.HOST)
            return;
        player(from).name = name;
        if (version != NetProto.VERSION) {
            channel.packet(NetProto.S_REJECT, true, from)
                    .str("другая версия игры: у вас " + version + ", у хозяина " + NetProto.VERSION);
            channel.flush();
            return;
        }
        String identity=in.readStr();
        if(in.truncated())return;
        Vector3f spawn = ctx.spawn();
        PacketBuf b = channel.packet(NetProto.S_WELCOME, true, from);
        b.i64(ctx.seed()).str(ctx.worldName()).f32(ctx.timeOfDay()).u8(ctx.gameMode())
                .f32(spawn.x).f32(spawn.y).f32(spawn.z);
        sendInfo(from);
        inventorySync.hello(from,identity);
        channel.flush();
        addChat(name + " вошёл в мир");
    }

    private void onWelcome(int from, PacketBuf in) {
        long seed = in.readI64();
        String name = in.readStr();
        float time = in.readF32();
        int mode = in.readU8();
        float sx = in.readF32(), sy = in.readF32(), sz = in.readF32();
        if (in.truncated() || role != Role.CLIENT || from != hostActor)
            return;
        hostActor = from;
        boolean returning = worldReady && ctx.world() != null && ctx.seed() == seed;
        worldReady = true;
        status = "мир «" + name + "»";
        requestedChunks.clear();
        chunkQueue.clear();
        if (returning) {
            // Тот же мир, в который мы и так стоим: строить его заново значит
            // выкинуть игрока на экран загрузки из-за секундного обрыва.
            // Время суток всё равно берём хозяйское — за обрыв оно ушло.
            ctx.setTimeOfDay(time);
            requestLoadedChunks();
            return;
        }
        ctx.startRemoteWorld(seed, name, time, mode, sx, sy, sz);
        addChat("Вошли в мир «" + name + "»");
    }

    private void applyRemote(int x, int y, int z, byte id, byte meta, boolean broke) {
        applyingRemote = true;
        try {
            ctx.applyRemoteBlock(x, y, z, id, meta, broke);
        } finally {
            applyingRemote = false;
        }
    }

    // ------------------------------------------------------- дельты чанков

    /**
     * Посчитать, чем чанк отличается от чистой генерации, и отправить разницу.
     *
     * <p>Живой чанк читается здесь, в игровом потоке: у {@link Chunk} чтение
     * без блокировок, но читать его параллельно с правкой блока всё равно
     * значит получить кашу из двух состояний. А генерация чистой копии и само
     * сравнение уходят в фоновый поток — вместе они стоят миллисекунды,
     * которых в кадре нет.
     */
    private void queueChunkDelta(int actor, int cx, int cz) {
        World world = ctx.world();
        if (world == null || deltaWorker == null)
            return;
        Chunk live = world.getChunkIfExists(cx, cz);
        byte[] blocks;
        byte[] meta;
        if (live != null) {
            blocks = live.copyBlocks();
            meta = live.copyMeta();
        } else {
            // Чанка нет в памяти — но он мог быть изменён когда-то раньше и
            // лежать в сейве. Пустой ответ был бы тихой потерей построек.
            ChunkSnapshot saved = ctx.loadSavedChunk(cx, cz);
            if (saved == null) {
                sendEmptyDelta(actor, cx, cz);
                return;
            }
            blocks = saved.blocks;
            meta = saved.meta;
        }
        final byte[] fBlocks = blocks, fMeta = meta;
        // Чистый мир берётся сюда, а не читается из поля в задаче: сессию
        // могут закрыть, пока задача ещё в очереди, и поле к тому моменту
        // уже пустое.
        final World base = pristine;
        deltaWorker.execute(() -> {
            try {
                if (base == null)
                    return;
                PacketBuf body = buildDelta(base, cx, cz, fBlocks, fMeta);
                deltaResults.add(() -> {
                    if (channel == null)
                        return;
                    PacketBuf out = channel.packet(NetProto.S_CHUNK_DELTA, true, actor);
                    out.i32(cx).i32(cz).bytes(body.toBytes());
                });
            } catch (RuntimeException e) {
                System.err.println("chunk delta failed for " + cx + "," + cz + ": " + e);
            }
        });
    }

    private void sendEmptyDelta(int actor, int cx, int cz) {
        channel.packet(NetProto.S_CHUNK_DELTA, true, actor)
                .i32(cx).i32(cz).bytes(new PacketBuf(1).varInt(0).toBytes());
    }

    /** Сравнение с чистой генерацией. Работает в фоновом потоке. */
    private static PacketBuf buildDelta(World base, int cx, int cz, byte[] blocks, byte[] meta) {
        Chunk fresh = base.getChunk(cx, cz);
        byte[] baseBlocks = fresh.copyBlocks();
        byte[] baseMeta = fresh.copyMeta();
        base.removeChunk(cx, cz);
        PacketBuf cells = new PacketBuf(1024);
        int count = 0;
        PacketBuf body = new PacketBuf(1024);
        int n = Math.min(blocks.length, baseBlocks.length);
        for (int i = 0; i < n; i++) {
            byte b = blocks[i];
            byte m = i < meta.length ? meta[i] : 0;
            byte bb = baseBlocks[i];
            byte bm = i < baseMeta.length ? baseMeta[i] : 0;
            if (b == bb && m == bm)
                continue;
            cells.varInt(i).u8(b).u8(m);
            count++;
        }
        body.varInt(count);
        byte[] tail = cells.toBytes();
        for (byte t : tail)
            body.u8(t);
        return body;
    }

    private void onChunkDelta(int from, PacketBuf in) {
        int cx = in.readI32(), cz = in.readI32();
        byte[] body = in.readBytes();
        if (in.truncated() || role != Role.CLIENT || from != hostActor)
            return;
        World world = ctx.world();
        if (world == null || world.getChunkIfExists(cx, cz) == null) {
            // Чанк успели выгрузить, пока ответ летел: спросим снова, когда он
            // снова появится.
            requestedChunks.remove(World.key(cx, cz));
            return;
        }
        PacketBuf cells = PacketBuf.reading(body);
        int count = cells.readVarInt();
        applyingRemote = true;
        try {
            for (int i = 0; i < count && !cells.truncated(); i++) {
                int index = cells.readVarInt();
                int id = cells.readU8();
                int meta = cells.readU8();
                if (index < 0 || index >= Chunk.SIZE_X * Chunk.SIZE_Y * Chunk.SIZE_Z)
                    continue;
                int x = index % Chunk.SIZE_X;
                int rest = index / Chunk.SIZE_X;
                int z = rest % Chunk.SIZE_Z;
                int y = rest / Chunk.SIZE_Z;
                ctx.applyRemoteBlock(cx * Chunk.SIZE_X + x, y, cz * Chunk.SIZE_Z + z,
                        (byte) id, (byte) meta, false);
            }
        } finally {
            applyingRemote = false;
        }
    }

    // ------------------------------------------------------------ существа

    private void sendMobs() {
        List<Mob> mobs = ctx.mobs();
        if (mobs == null) return;
        PacketBuf b = channel.packet(NetProto.S_MOBS, false, NetChannel.ALL);
        b.i64(++mobSequence).varInt(mobs.size());
        for (Mob m : mobs)
            MobSnapshot.capture(mobIds.computeIfAbsent(m, k -> nextEntityId++), m).write(b);
        mobIds.keySet().retainAll(new HashSet<>(mobs));
    }

    private void onMobs(int from, PacketBuf in) {
        long sequence = in.readI64();
        int count = in.readVarInt();
        if (count < 0 || count > in.remaining() / MobSnapshot.MIN_BYTES) {
            // A corrupt count loses framing; discard the remainder of this message.
            while (in.hasMore()) in.readU8();
            return;
        }
        List<MobSnapshot> snapshots = new ArrayList<>(count);
        Set<Integer> seen = new HashSet<>();
        boolean valid = true;
        for (int i = 0; i < count; i++) {
            MobSnapshot s = MobSnapshot.read(in);
            valid &= s.valid() && seen.add(s.id());
            snapshots.add(s);
        }
        // Read first, apply atomically. A truncated batch must not despawn valid mobs.
        if (!valid || in.truncated() || role != Role.CLIENT || from != hostActor
                || sequence <= lastMobSequence || ctx.mobs() == null) return;
        lastMobSequence = sequence;
        for (MobSnapshot s : snapshots) {
            Mob m = shownMobs.get(s.id());
            if (m == null || m.type.ordinal() != s.type()) {
                m = new Mob(MobType.values()[s.type()], s.x(), s.y(), s.z(), visualRandom);
                shownMobs.put(s.id(), m);
                mobPoses.put(s.id(), new RemoteMob(m));
            }
            mobPoses.get(s.id()).accept(s);
        }
        shownMobs.keySet().retainAll(seen);
        mobPoses.keySet().retainAll(seen);
        List<Mob> out = ctx.mobs();
        out.clear();
        out.addAll(shownMobs.values());
    }

    /**
     * Снаряды хозяина — всем.
     *
     * Летит только то, что нужно нарисовать: позиция, курс и «воткнулся ли».
     * Урон и владелец остаются у хозяина — участник по снаряду не считает
     * ничего, и подделать попадание ему нечем.
     */
    private void sendProjectiles() {
        List<Projectile> shots = ctx.projectiles();
        if (shots == null)
            return;
        PacketBuf b = channel.packet(NetProto.S_PROJECTILES, false, NetChannel.ALL);
        b.varInt(shots.size());
        for (Projectile p : shots)
            b.f32(p.position.x).f32(p.position.y).f32(p.position.z)
                    .f32(p.heading.x).f32(p.heading.y).f32(p.heading.z)
                    .varInt(p.stuck ? 1 : 0);
    }

    private void onProjectiles(int from, PacketBuf in) {
        int count = in.readVarInt();
        boolean mine = role == Role.CLIENT && from == hostActor;
        List<Projectile> out = mine ? ctx.projectiles() : null;
        if (out != null)
            out.clear();
        for (int i = 0; i < count && !in.truncated(); i++) {
            float x = in.readF32(), y = in.readF32(), z = in.readF32();
            float hx = in.readF32(), hy = in.readF32(), hz = in.readF32();
            boolean stuck = in.readVarInt() != 0;
            if (out == null)
                continue;
            // Снимок, а не сущность: участник снаряды не симулирует, поэтому
            // список каждый раз пересобирается целиком. Сверять их по номерам
            // незачем — живут они секунды.
            Projectile p = new Projectile("arrow", null, false, 0f);
            p.position.set(x, y, z);
            p.heading.set(hx, hy, hz);
            p.stuck = stuck;
            out.add(p);
        }
    }

    /** Участник просит выстрелить: стреляет хозяин. */
    public void requestShot(Projectile shot) {
        if (role != Role.CLIENT || !joined)
            return;
        channel.packet(NetProto.C_SHOOT, true, hostActor)
                .f32(shot.position.x).f32(shot.position.y).f32(shot.position.z)
                .f32(shot.velocity.x).f32(shot.velocity.y).f32(shot.velocity.z)
                .f32(shot.damage);
    }

    private void onShoot(int from, PacketBuf in) {
        float x = in.readF32(), y = in.readF32(), z = in.readF32();
        float vx = in.readF32(), vy = in.readF32(), vz = in.readF32();
        float damage = in.readF32();
        if (role != Role.HOST || in.truncated())
            return;
        ctx.shootFor(from, x, y, z, vx, vy, vz, damage);
    }

    /**
     * Хозяин сообщает участнику, что тот получил урон.
     *
     * Ровно этого пакета не хватало, чтобы мобы могли ранить гостя: урон
     * всегда считался у хозяина, а сказать о нём было нечем.
     */
    public void hurtPlayer(int actor, float damage) {
        if (role != Role.HOST || damage <= 0f)
            return;
        channel.packet(NetProto.S_PLAYER_HURT, true, actor).f32(damage);
    }

    private void onPlayerHurt(int from, PacketBuf in) {
        float damage = in.readF32();
        if (role == Role.CLIENT && from == hostActor && !in.truncated())
            ctx.hurtByHost(damage);
    }

    private void sendItems() {
        List<ItemEntity> items = ctx.groundItems();
        if (items == null)
            return;
        PacketBuf b = channel.packet(NetProto.S_ITEMS, true, NetChannel.ALL);
        b.varInt(items.size());
        for (ItemEntity e : items) {
            int id = itemIds.computeIfAbsent(e, k -> nextEntityId++);
            b.varInt(id).f32(e.position.x).f32(e.position.y).f32(e.position.z);
            writeStack(b, e.stack);
        }
        itemIds.keySet().retainAll(new HashSet<>(items));
    }

    private void onItems(int from, PacketBuf in) {
        int count = in.readVarInt();
        boolean mine = role == Role.CLIENT && from == hostActor;
        List<ItemEntity> out = mine ? ctx.groundItems() : null;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < count && !in.truncated(); i++) {
            int id = in.readVarInt();
            float x = in.readF32(), y = in.readF32(), z = in.readF32();
            ItemStack stack = readStack(in);
            if (out == null || stack == null)
                continue;
            seen.add(id);
            ItemEntity e = shownItems.get(id);
            if (e == null || !e.stack.stacksWith(stack)) {
                e = new ItemEntity(stack, x, y, z, 0f, (id * 0.37f) % 6.283f);
                shownItems.put(id, e);
            } else {
                e.stack.count = stack.count;
            }
            e.position.set(x, y, z);
        }
        if (out == null)
            return;
        shownItems.keySet().retainAll(seen);
        out.clear();
        out.addAll(shownItems.values());
    }

    /**
     * Удар участника по мобу.
     *
     * <p>Урон и отброс считает хозяин, потому что у него живёт сам моб. У
     * участника удар при этом отыгрывается сразу — звук, брызги, вспышка, —
     * и поправляется следующим снимком: ждать ответа сервера, чтобы меч
     * зазвенел, значит превратить бой в переписку.
     */
    private void hostMobHit(int id, float damage, float knockback, float fromX, float fromZ) {
        if (damage <= 0f || damage > 100f)
            return;
        for (Map.Entry<Mob, Integer> e : mobIds.entrySet()) {
            if (e.getValue() != id)
                continue;
            Mob m = e.getKey();
            if (!m.dead)
                m.hurt(damage, fromX, fromZ, Math.max(0f, Math.min(4f, knockback)), true);
            return;
        }
    }

    void pickupInto(int id, com.mineclone.world.Inventory inventory) {
        List<ItemEntity> items = ctx.groundItems();
        if (items == null)
            return;
        for (Map.Entry<ItemEntity, Integer> e : itemIds.entrySet()) {
            if (e.getValue() != id)
                continue;
            ItemEntity ent = e.getKey();
            if (!items.contains(ent))
                return;
            int left=inventory.add(ent.stack);
            if(left==0){items.remove(ent);itemIds.remove(ent);}
            else ent.stack.count=left;
            return;
        }
    }

    // ------------------------------------------------------------- стопки

    /**
     * Стопка едет тем же кодеком, что и в сейв.
     *
     * <p>Свой формат «id и количество» пришлось бы чинить каждый раз, когда у
     * предмета появляется новая часть: износ инструмента, своё имя, начинка
     * блока. Кодек сейва уже умеет всё это и переживает незнакомые компоненты.
     */
    static void writeStack(PacketBuf b, ItemStack s) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            ItemStackCodec.write(out, s);
        } catch (IOException e) {
            // Запись в массив в памяти не падает; если это всё же случилось,
            // отправим пустую стопку вместо поломки всего сообщения.
            b.bytes(new byte[0]);
            return;
        }
        b.bytes(bytes.toByteArray());
    }

    static ItemStack readStack(PacketBuf b) {
        byte[] raw = b.readBytes();
        if (raw.length == 0)
            return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            return ItemStackCodec.read(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
