package com.mineclone.server;

import com.mineclone.net.CompositeTransport;
import com.mineclone.net.LanTransport;
import com.mineclone.net.Multiplayer;
import com.mineclone.net.NetContext;
import com.mineclone.net.NetTransport;
import com.mineclone.net.PhotonTransport;
import com.mineclone.net.RemotePlayer;
import com.mineclone.net.connect.RegionFinder;
import com.mineclone.net.connect.RoomCode;
import com.mineclone.net.direct.LanBeacon;
import com.mineclone.net.direct.PortMapper;
import com.mineclone.net.direct.PublicAddress;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.SaveManager;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.ChunkMesher;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.WorldSimulation;
import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Мир, который живёт без игрока.
 *
 * <p>До этого хозяин комнаты обязан был сидеть в игре: вышел — комната
 * кончилась, и вместе с ней построенное за вечер продолжение. Здесь мир тикает
 * сам, сохраняется сам и ждёт, пока кто-нибудь зайдёт.
 *
 * <p><b>Ни одного вызова GL, GLFW и OpenAL.</b> Это проверяется тестом, который
 * читает исходники пакета и ищет в них запретные импорты, — иначе первая же
 * удобная строчка из {@code Game} притащила бы за собой окно. Мир тикает
 * {@link WorldSimulation} — тем же классом, которым тикает игра, потому что
 * два разных кода для одного и того же разойдутся на первой же луже.
 *
 * <p>Двери открываются обе сразу ({@link CompositeTransport}): свой TCP-порт
 * для тех, у кого есть белый адрес или проброшенный порт, и комната Photon для
 * всех остальных. Не открылась одна — сервер работает на другой.
 *
 * <p>Мир крутится <b>вокруг участников</b>, а не вокруг точки появления: чанки
 * подгружаются около каждого, печи и случайные тики идут около первого из них.
 * Нет никого — сервер тикает время и дремлет.
 *
 * <p>Мобы у него бьют и участников: пакет «хозяин ранил гостя»
 * ({@code S_PLAYER_HURT}) появился вместе со снарядами, и прежнее ограничение
 * всей игры по сети снято.
 */
public final class DedicatedServer implements NetContext {

    /** Сколько раз в секунду тикает мир. */
    public static final float TICK_RATE = 20f;


    private final ServerConfig config;
    private final SaveManager save;
    private final Multiplayer net;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    private World world;
    /** Which generator made each chunk of the world. */
    private com.mineclone.world.gen.ChunkLedger ledger;
    /** The local owner's checkpoint survives server sessions that have no local player. */
    private LevelData ownerTemplate;
    private ChunkLoader loader;
    private WorldSimulation simulation;
    /** Мобы мира — те же правила, что у игры: {@link WorldSession}. */
    private WorldSession session;
    private final EntityStore entities = new EntityStore();
    private final Vector3f spawn = new Vector3f(8.5f, 80f, 8.5f);

    private CompositeTransport transport;
    private PortMapper portMapper;
    private LanBeacon.Sender beacon;
    private RoomCode roomCode;
    private String externalAddress = "";

    private com.mineclone.sim.WorldClock worldClock = new com.mineclone.sim.WorldClock();
    private final java.util.Map<String, byte[]> levelExtraSections = new java.util.LinkedHashMap<>();
    private float daylight = 1f;
    private float autosaveTimer;
    private volatile boolean running = true;
    private String stopReason = "";

    public DedicatedServer(ServerConfig config) {
        this.config = config;
        this.save = new SaveManager(new File(config.savesDir));
        this.save.setBackupRetention(config.backups);
        this.net = new Multiplayer(this);
    }

    // -------------------------------------------------------------- запуск

    /** Поднять мир и открыть двери. Блокирует до {@link #stop}. */
    public int run() {
        if (!openWorld()) return 2;
        openDoors();
        loop();
        shutdown();
        return 0;
    }

    private boolean openWorld() {
        var decision = com.mineclone.save.WorldOpenPolicy.decide(save.readLevel(config.worldId));
        if (!decision.allowed()) {
            log(com.mineclone.save.WorldOpenPolicy.refusalMessage(config.worldId, decision));
            return false;
        }
        LevelData lvl = decision.data();
        ownerTemplate = lvl;
        try {
            save.beginWorldSession(config.worldId).join();
        } catch (java.util.concurrent.CompletionException failure) {
            log("world backup failed; refusing to open: " + failure.getCause());
            return false;
        }
        long seed = lvl != null ? lvl.seed
                : (config.seed != 0L ? config.seed : new Random().nextLong());
        var generator = lvl != null
                ? com.mineclone.world.gen.WorldGenSettings.decode(
                        lvl.extraSections.get(com.mineclone.world.gen.WorldGenSettings.SAVE_SECTION))
                : com.mineclone.world.gen.WorldGenSettings.forNewWorld();
        if (lvl == null)
            levelExtraSections.put(com.mineclone.world.gen.WorldGenSettings.SAVE_SECTION, generator.encode());
        // Every chunk keeps the generator that first made it (GEN-02); spawn and
        // the owner's checkpoint anchor the rebuild of a lost ledger.
        ledger = save.openLedger(config.worldId, generator, lvl == null ? new float[0][] : new float[][] {
                { (float) lvl.spawnX, (float) lvl.spawnZ }, { (float) lvl.px, (float) lvl.pz } });
        world = new World(seed, generator.policy(ledger));
        loader = new ChunkLoader(world, new ChunkMesher(world), save, config.worldId);
        // Меши сервер не строит: рисовать ему нечем, а восемь потоков,
        // складывающих треугольники в никуда, — это восемь занятых ядер.
        loader.setMeshing(false);
        simulation = new WorldSimulation(seed);
        world.setBlockObserver(net::onWorldBlockChanged);
        // The session reads the world's own time, and must exist before the
        // first chunk loads: items saved in a chunk enter the world through it.
        if (lvl != null)
            worldClock = com.mineclone.sim.WorldClock.fromSaved(lvl.timeOfDay,
                    lvl.extraSections.get(com.mineclone.sim.WorldClock.SAVE_SECTION));
        session = new WorldSession(world, worldClock, new MobSpawner(seed ^ 0x51E7B0BL), entities,
                net.participants(), sessionHost, WorldEvents.NONE);
        loader.setChunkLiveListener(session::adoptChunkItems);

        // Площадка появления: она же центр мира, когда участников нет.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                loader.loadNow(dx, dz);
        loader.drainLightFlood(9);
        if (lvl != null) {
            spawn.set((float) lvl.spawnX, (float) lvl.spawnY, (float) lvl.spawnZ);
            levelExtraSections.putAll(lvl.extraSections);
        } else {
            for (int y = Chunk.SIZE_Y - 1; y > 0; y--)
                if (world.getBlock(8, y, 8).solid) {
                    spawn.set(8.5f, y + 1.1f, 8.5f);
                    break;
                }
            saveLevel();
        }
        log("world '" + config.worldName + "' seed " + seed
                + (lvl == null ? " (new)" : " (loaded)"));
        return true;
    }

    /**
     * Открыть двери.
     *
     * <p>Проброс порта и замер региона делаются здесь, до входа в комнату:
     * оба стоят секунд, оба могут не выйти, и оба должны успеть сказать об
     * этом человеку у консоли до того, как он начнёт диктовать друзьям адрес.
     */
    private void openDoors() {
        List<CompositeTransport.Door> doorList = new ArrayList<>();
        if (config.direct)
            doorList.add(door("свой порт", l -> LanTransport.host(config.port, l)));
        String region = config.photon ? resolveRegion() : "";
        if (config.photon && !region.isEmpty()) {
            roomCode = config.room.isEmpty()
                    ? RoomCode.generate(region, new Random())
                    : RoomCode.parse(config.room);
            if (roomCode == null) {
                log("room code '" + config.room + "' is not a code — generating a fresh one");
                roomCode = RoomCode.generate(region, new Random());
            }
            final String appId = config.effectiveAppId();
            final String pinned = roomCode.region();
            doorList.add(door("Photon", l -> new PhotonTransport(appId, pinned, l)));
        }
        if (doorList.isEmpty()) {
            stop("neither direct nor photon is enabled — nothing to open");
            return;
        }
        if (config.direct && config.upnp)
            openPort();

        transport = new CompositeTransport(netListener(), doorList);
        // Двери открывает сессия, а не мы: позвать connectDoor здесь и потом
        // net.start значит открыть каждую дважды — второй раз порт уже занят
        // нами же. Имя комнаты у дверей одно; своему порту оно безразлично,
        // облаку это код.
        net.start(transport, roomCode != null ? roomCode.roomName() : "lan", true,
                config.worldName);

        if (config.direct) {
            beacon = new LanBeacon.Sender(config.port);
            log("direct: " + PublicAddress.localAddress() + ":" + config.port
                    + " (local network)");
            if (!externalAddress.isEmpty())
                log("direct: " + externalAddress + ":" + config.port + " (internet)");
        }
        if (roomCode != null)
            log("room code: " + roomCode.pretty() + "  region " + roomCode.region());
        announceDoors();
    }

    /**
     * Сказать вслух, открылась ли хоть одна дверь.
     *
     * <p>Сервер, в который никто не может войти, продолжает тикать мир — и это
     * верно: остановить его значило бы потерять несохранённое. Но человек у
     * консоли обязан узнать об этом первой же строчкой, а не через полчаса от
     * друга, который не смог зайти.
     */
    private void announceDoors() {
        if (transport == null)
            return;
        boolean any = false;
        for (int i = 0; i < transport.doorCount(); i++)
            any |= transport.doorOpen(i);
        if (any)
            return;
        // Двери открываются не мгновенно; окончательный приговор выносит
        // первая же проверка после входа, а здесь только предупреждение.
        for (int i = 0; i < transport.doorCount(); i++)
            if (!transport.doorDetail(i).isEmpty())
                log("door '" + transport.doorLabel(i) + "': " + transport.doorDetail(i));
    }

    private static CompositeTransport.Door door(String label,
            java.util.function.Function<NetTransport.Listener, NetTransport> factory) {
        return new CompositeTransport.Door() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public NetTransport open(NetTransport.Listener listener) {
                return factory.apply(listener);
            }
        };
    }

    /**
     * Какой регион облака взять.
     *
     * <p>Пустой в настройках означает «замерить». Замер блокирующий и
     * недолгий: сервер всё равно ещё никого не принял, а выбрать регион надо
     * <b>до</b> того, как родится код комнаты — регион внутри него.
     */
    private String resolveRegion() {
        if (!config.region.isEmpty())
            return config.region;
        log("measuring photon regions...");
        final String[] picked = { "" };
        final Object done = new Object();
        RegionFinder finder = new RegionFinder(config.effectiveAppId());
        synchronized (done) {
            finder.start(outcome -> {
                synchronized (done) {
                    picked[0] = outcome.region();
                    if (!outcome.ok())
                        log("region probe failed: " + outcome.error());
                    done.notifyAll();
                }
            });
            try {
                done.wait(12_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        finder.close();
        if (picked[0].isEmpty()) {
            log("no region answered — photon door stays closed");
            return "";
        }
        log("photon region: " + picked[0]);
        return picked[0];
    }

    private void openPort() {
        portMapper = new PortMapper();
        PortMapper.Result result = portMapper.open(config.port, "mineclone");
        if (result.ok()) {
            externalAddress = result.externalAddress();
            log("port " + config.port + " opened via " + result.method());
        } else {
            externalAddress = result.externalAddress();
            log("port forwarding failed: " + result.error());
            if (externalAddress.isEmpty())
                log("guests outside your network will have to use the room code");
        }
    }

    // ---------------------------------------------------------------- цикл

    private void loop() {
        long last = System.nanoTime();
        float step = 1f / TICK_RATE;
        float accumulator = 0f;
        while (running) {
            long now = System.nanoTime();
            float elapsed = (float) ((now - last) / 1e9);
            last = now;
            // Потолок в полсекунды: машина, уснувшая на минуту, не должна
            // просыпаться и отыгрывать минуту мира за один кадр.
            accumulator += Math.min(0.5f, elapsed);
            while (accumulator >= step) {
                accumulator -= step;
                tick(step);
            }
            long spent = System.nanoTime() - now;
            long sleep = (long) (step * 1e9) - spent;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }

    private void tick(float dt) {
        Runnable task;
        while ((task = tasks.poll()) != null)
            task.run();
        if (!running)
            return;

        worldClock.advance(dt);
        daylight = worldClock.daylight();
        net.update(dt);
        streamChunks();
        // Around every guest, with the weather the game's players get: snow
        // settles on the server, rain puts its fires out.
        simulation.update(world, dt, session.centres(), precipitation(), true);
        session.adoptFallingDrops();
        session.tickMobs(dt);
        session.tickItems(dt);
        session.tickProjectiles(dt);
        if (beacon != null) {
            beacon.describe(config.worldName, config.motd.isEmpty() ? "сервер" : config.motd,
                    net.players().size(), config.maxPlayers);
            beacon.update(dt);
        }
        autosaveTimer -= dt;
        if (autosaveTimer <= 0f) {
            autosaveTimer = config.autosaveSeconds;
            saveWorld();
        }
    }

    /** Осадки фронта — те же, что у игроков в игре: {@link com.mineclone.world.Weather} от сида и времени. */
    float precipitation() {
        return com.mineclone.world.Weather.sample(world.seed, worldClock.frontSeconds()).precipitation();
    }

    /** Держать мир загруженным вокруг каждого участника. */
    private void streamChunks() {
        List<Vector3f> around = new ArrayList<>();
        for (RemotePlayer p : net.players())
            around.add(p.position);
        if (around.isEmpty())
            around.add(spawn);
        for (Vector3f p : around) {
            int cx = (int) Math.floor(p.x / Chunk.SIZE_X);
            int cz = (int) Math.floor(p.z / Chunk.SIZE_Z);
            loader.setPriorityCenter(cx, cz);
            loader.ensureRadius(cx, cz, config.viewDistance);
        }
        loader.drainLightFlood(8);
        for (var warning : save.drainWorldWarnings(config.worldId)) log(warning.message());
    }

    /**
     * Что сессия берёт у сервера. Мир живёт вокруг каждого гостя; нет никого —
     * вокруг точки появления, чтобы вода у спавна всё же дотекла. Стрелы летят
     * в гостей.
     */
    private final WorldSession.Host sessionHost = new WorldSession.Host() {
        @Override public Vector3f mobFocus() { return spawn; }
        @Override public void projectileTargets(List<com.mineclone.world.entity.Hittable> out) { out.addAll(net.players()); }
    };

    // ------------------------------------------------------------ хозяйство

    /** Остановить сервер: цикл доиграет текущий тик и выйдет. */
    public void stop(String reason) {
        stopReason = reason == null ? "" : reason;
        running = false;
    }

    /** Поручение из другого потока (консоль). Выполнится в начале тика. */
    public void submit(Runnable task) {
        tasks.add(task);
    }

    private void shutdown() {
        log("stopping" + (stopReason.isEmpty() ? "" : ": " + stopReason));
        if (net != null)
            net.stop("сервер остановлен");
        if (beacon != null)
            beacon.close();
        if (transport != null)
            transport.disconnect();
        if (portMapper != null)
            portMapper.close();
        saveWorld();
        if (loader != null)
            loader.shutdown();
        save.flushAndAwait();
        log("stopped");
    }

    /** Записать мир целиком: уровень и все изменённые чанки. */
    public void saveWorld() {
        if (world == null)
            return;
        saveLevel();
        int written = 0;
        for (Chunk c : world.getLoadedChunks()) {
            ChunkSnapshot snapshot = session.snapshotChunk(c);
            if (snapshot == null)
                continue;
            save.saveChunkAsync(config.worldId, snapshot);
            written++;
        }
        save.saveLedgerAsync(config.worldId, ledger.encodeIfDirty());
        if (written > 0)
            log("saved " + written + " chunks");
    }

    /** Snapshot current authoritative state, then ZIP it on the save writer. */
    public void backupWorld() {
        if (world == null) { log("no world to back up"); return; }
        saveWorld();
        save.backupWorld(config.worldId, "manual").whenComplete((backup, failure) -> {
            if (failure != null) log("backup failed: " + failure.getMessage());
            else log("backup created: " + backup.path());
        });
    }

    private void saveLevel() {
        var sections = new java.util.LinkedHashMap<>(levelExtraSections);
        sections.put(com.mineclone.sim.WorldClock.SAVE_SECTION, worldClock.encode());
        // A server has no local owner to recapture. Preserve the checkpoint from
        // the loaded single-player world instead of replacing its inventory,
        // pending cursor stacks and vitals with a fresh empty player.
        LevelData owner = ownerTemplate;
        com.mineclone.save.PlayerRecord record = owner != null ? owner.player
                : com.mineclone.save.PlayerRecord.builder().pose(spawn.x, spawn.y, spawn.z, 0, 0, 0).build();
        save.saveLevel(config.worldId, new LevelData(
                config.worldName, world.seed,
                owner != null ? com.mineclone.save.PlayerRecord.keepPrecision(spawn.x, owner.spawnX) : spawn.x,
                owner != null ? com.mineclone.save.PlayerRecord.keepPrecision(spawn.y, owner.spawnY) : spawn.y,
                owner != null ? com.mineclone.save.PlayerRecord.keepPrecision(spawn.z, owner.spawnZ) : spawn.z,
                worldClock.gameTimeFloat(),
                config.creative ? GameMode.CREATIVE : GameMode.SURVIVAL,
                System.currentTimeMillis(), record, sections));
    }

    /** Состав комнаты для консоли. */
    public List<String> playerLines() {
        List<String> out = new ArrayList<>();
        for (RemotePlayer p : net.players()) {
            String door = transport == null ? "" : transport.doorOf(p.actor);
            out.add("#" + p.actor + " " + (p.name.isEmpty() ? "?" : p.name)
                    + " at " + (int) p.position.x + "," + (int) p.position.y
                    + "," + (int) p.position.z
                    + (door.isEmpty() ? "" : " via " + door));
        }
        return out;
    }

    public Multiplayer net() {
        return net;
    }

    public RoomCode roomCode() {
        return roomCode;
    }

    public ServerConfig config() {
        return config;
    }

    public float gameTime() {
        return worldClock.gameTimeFloat();
    }

    public void setGameTime(float t) {
        worldClock.setGameTime(t);
    }

    public boolean running() {
        return running;
    }

    /** Сообщения латиницей: у консоли Windows кодировка не UTF-8. */
    static void log(String line) {
        System.out.println("[server] " + line);
    }

    // ------------------------------------------------------- NetContext

    private NetTransport.Listener netListener() {
        return net;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long seed() {
        return world == null ? 0L : world.seed;
    }

    @Override
    public String worldName() {
        return config.worldName;
    }

    @Override
    public float timeOfDay() {
        return worldClock.gameTimeFloat();
    }

    @Override public com.mineclone.net.PlayerData loadGuest(String id) {
        return save.loadGuest(config.worldId,id);
    }

    @Override public void saveGuest(String id,com.mineclone.net.PlayerData data) {
        save.saveGuest(config.worldId,id,data);
    }

    @Override
    public void setTimeOfDay(float t) {
        worldClock.setGameTime(t);
    }

    @Override
    public int gameMode() {
        return (config.creative ? GameMode.CREATIVE : GameMode.SURVIVAL).ordinal();
    }

    @Override
    public Vector3f spawn() {
        return spawn;
    }

    @Override
    public void startRemoteWorld(long seed, String name, float time, int mode,
            float sx, float sy, float sz) {
        // Сервер — всегда хозяин: чужой мир ему принимать неоткуда.
    }

    @Override
    public void applyRemoteBlock(int x, int y, int z, byte blockId, byte meta, boolean broke) {
        if (world == null)
            return;
        BlockType type = BlockType.byId(blockId);
        if (type == null)
            return;
        BlockType before = world.getBlock(x, y, z);
        if (before != type) {
            if (before == BlockType.CHEST) {
                ItemStack[] slots = world.getChest(x, y, z);
                if (slots != null) for (int i = 0; i < slots.length; i++) {
                    spillItem(slots[i], x, y, z);
                    slots[i] = null;
                }
            } else if (before == BlockType.FURNACE) {
                var furnace = world.getFurnace(x, y, z);
                if (furnace != null) {
                    spillItem(furnace.input, x, y, z);
                    spillItem(furnace.fuel, x, y, z);
                    spillItem(furnace.output, x, y, z);
                    furnace.input = furnace.fuel = furnace.output = null;
                }
            }
        }
        world.setBlock(x, y, z, type, meta);
    }

    private void spillItem(ItemStack stack, int x, int y, int z) {
        session.dropStack(stack, x + .5f, y + .5f, z + .5f);
    }

    @Override
    public void remoteBlockAction(int actor, int x, int y, int z, byte blockId, boolean broke) {
        // Звук и пыль — дело тех, у кого есть экран; мобы же слышат и здесь.
        if (session != null)
            session.noise(x + 0.5f, y + 0.5f, z + 0.5f,
                    broke ? WorldSession.NOISE_BREAK : WorldSession.NOISE_PLACE);
    }

    @Override
    public ChunkSnapshot loadSavedChunk(int cx, int cz) {
        return save.loadChunk(config.worldId, cx, cz);
    }

    @Override
    public Vector3f playerPosition() {
        // Тела у сервера нет, и это не заглушка: на null сессия перестаёт
        // рассылать состояние игрока, и в комнате не появляется фигура,
        // стоящая на спавне и глядящая в одну точку.
        return null;
    }

    @Override
    public float playerYaw() {
        return 0f;
    }

    @Override
    public float playerPitch() {
        return 0f;
    }

    @Override
    public int playerFlags() {
        return 0;
    }

    @Override
    public float playerHealth() {
        return 20f;
    }

    @Override
    public List<Mob> mobs() {
        return entities.mobs;
    }

    @Override
    public List<ItemEntity> groundItems() {
        return entities.items;
    }

    @Override
    public List<com.mineclone.world.entity.Projectile> projectiles() {
        return entities.projectiles;
    }

    /** Выстрел участника: сервер выпускает снаряд у себя и рассылает его всем. */
    @Override
    public void shootFor(int actor, float x, float y, float z,
                         float vx, float vy, float vz, float damage) {
        var shot = new com.mineclone.world.entity.Projectile("arrow",
                net == null ? null : net.playerOf(actor), true, damage);
        shot.position.set(x, y, z);
        shot.velocity.set(vx, vy, vz);
        shot.heading.set(vx, vy, vz).normalize();
        entities.projectiles.add(shot);
    }

    /** У сервера своего игрока нет — ранить в нём некого. */
    @Override
    public void hurtByHost(com.mineclone.world.damage.DamageSource source, float damage) {
    }

    @Override
    public void chatLine(String line) {
        System.out.println(line);
    }

    @Override
    public void status(String line) {
        log(line);
    }

    @Override
    public void give(ItemStack stack) {
        // Хозяину никто ничего не даёт: это пакет в обратную сторону.
    }

    @Override
    public void netStopped(String reason) {
        log("network: " + reason);
    }

    @Override
    public void containerFromHost(int x, int y, int z, int kind, ItemStack[] slots,
            float burnLeft, float burnMax, float cook) {
        // Содержимое контейнеров у нас и так своё.
    }
}
