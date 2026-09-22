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
import com.mineclone.world.DroppedItem;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.WorldSimulation;
import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobHerd;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobTactics;
import com.mineclone.world.entity.Wildlife;
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
    /** Игровых радиан в секунду: тот же темп суток, что в игре. */
    public static final float TIME_SCALE = 0.005f;
    /** Реже этого мобы не пересчитывают стадо и добычу. */
    private static final float SENSE_INTERVAL = 0.2f;

    private final ServerConfig config;
    private final SaveManager save;
    private final Multiplayer net;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    private World world;
    private ChunkLoader loader;
    private WorldSimulation simulation;
    private MobSpawner spawner;
    private final List<Mob> mobs = new ArrayList<>();
    private final List<ItemEntity> groundItems = new ArrayList<>();
    /** Летящие снаряды: сервер их симулирует и рассылает. */
    private final List<com.mineclone.world.entity.Projectile> projectiles =
            new java.util.ArrayList<>();
    private final Vector3f spawn = new Vector3f(8.5f, 80f, 8.5f);

    private CompositeTransport transport;
    private PortMapper portMapper;
    private LanBeacon.Sender beacon;
    private RoomCode roomCode;
    private String externalAddress = "";

    private float gameTime = (float) (Math.PI / 6.0);
    private float daylight = 1f;
    private float senseTimer;
    private float spawnTimer;
    private float autosaveTimer;
    private volatile boolean running = true;
    private String stopReason = "";

    public DedicatedServer(ServerConfig config) {
        this.config = config;
        this.save = new SaveManager(new File(config.savesDir));
        this.net = new Multiplayer(this);
    }

    // -------------------------------------------------------------- запуск

    /** Поднять мир и открыть двери. Блокирует до {@link #stop}. */
    public void run() {
        openWorld();
        openDoors();
        loop();
        shutdown();
    }

    private void openWorld() {
        LevelData lvl = save.loadLevel(config.worldId);
        long seed = lvl != null ? lvl.seed
                : (config.seed != 0L ? config.seed : new Random().nextLong());
        world = new World(seed);
        loader = new ChunkLoader(world, new ChunkMesher(world), save, config.worldId);
        // Меши сервер не строит: рисовать ему нечем, а восемь потоков,
        // складывающих треугольники в никуда, — это восемь занятых ядер.
        loader.setMeshing(false);
        simulation = new WorldSimulation(seed);
        spawner = new MobSpawner(seed ^ 0x51E7B0BL);
        world.setBlockObserver(net::onWorldBlockChanged);

        // Площадка появления: она же центр мира, когда участников нет.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                loader.applySnapshot(world.getChunk(dx, dz));
        loader.drainLightFlood(9);
        if (lvl != null) {
            spawn.set((float) lvl.spawnX, (float) lvl.spawnY, (float) lvl.spawnZ);
            gameTime = lvl.timeOfDay;
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

        gameTime += dt * TIME_SCALE;
        daylight = Math.max(0f, (float) Math.sin(gameTime));
        net.update(dt);
        streamChunks();
        Vector3f centre = centre();
        simulation.update(world, dt, centre, 0f, true);
        for (DroppedItem d : world.falling.drainDrops())
            groundItems.add(ItemEntity.restored(d, new Random()));
        tickMobs(dt, centre);
        tickItems(dt);
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

    /**
     * Вокруг чего крутится мир.
     *
     * <p>Вокруг первого участника, а не вокруг точки появления: печи, тики
     * блоков и мобы должны жить там, где кто-то есть. Нет никого — вокруг
     * точки появления, чтобы вода у спавна всё же дотекла.
     */
    private Vector3f centre() {
        for (RemotePlayer p : net.players())
            return p.position;
        return spawn;
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
    }

    private void tickMobs(float dt, Vector3f centre) {
        senseTimer -= dt;
        if (senseTimer <= 0f) {
            senseTimer = SENSE_INTERVAL;
            MobHerd.update(mobs);
            Wildlife.sense(mobs);
        }
        float dayPhase = (gameTime % ((float) Math.PI * 2f)) / ((float) Math.PI * 2f);
        if (dayPhase < 0f)
            dayPhase += 1f;
        MobTactics.updateGroup(mobs, dayPhase, dt);
        boolean hostile = !config.creative;
        List<Mob> fed = null;
        java.util.Iterator<Mob> it = mobs.iterator();
        while (it.hasNext()) {
            Mob m = it.next();
            if (!m.updateLod(world, centre, dt, daylight, hostile))
                continue;
            if (m.justBitMob != null) {
                Mob prey = m.justBitMob;
                if (prey.hurt(Wildlife.BITE_DAMAGE, m.position.x, m.position.z, 0.6f, false)
                        && prey.dead) {
                    if (fed == null)
                        fed = new ArrayList<>();
                    fed.add(m);
                }
            }
            if (m.dead && !m.deathEffectsDone) {
                m.deathEffectsDone = true;
                MobTactics.leaderFell(mobs, m);
            }
            if (m.dead && m.deathTimer <= 0f)
                it.remove();
        }
        if (fed != null)
            for (Mob wolf : fed)
                Wildlife.sate(wolf);
        spawner.despawnFar(mobs, centre);
        spawnTimer -= dt;
        if (spawnTimer <= 0f) {
            spawnTimer = MobSpawner.TICK_INTERVAL;
            spawner.trySpawn(world, mobs, centre, daylight);
        }
    }

    /**
     * Предметы на земле.
     *
     * <p>Подбирать их сервер не пытается: ему некуда класть. Участники просят
     * подбор сами пакетом {@code C_ITEM_PICK}, и {@link Multiplayer} отвечает
     * им от нашего имени — так одна стопка не уходит в два инвентаря.
     */
    private void tickItems(float dt) {
        groundItems.removeIf(e -> {
            int cx = Math.floorDiv((int) Math.floor(e.position.x), Chunk.SIZE_X);
            int cz = Math.floorDiv((int) Math.floor(e.position.z), Chunk.SIZE_Z);
            if (world.getChunkIfExists(cx, cz) == null)
                return false;
            e.update(world, null, false, dt);
            return e.expired() || e.position.y < -16f;
        });
    }

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
            List<DroppedItem> dropped = itemsInChunk(c);
            if (!c.modified && dropped.isEmpty() && c.savedItems == 0)
                continue;
            byte[] blocks = c.copyBlocks(), meta = c.copyMeta();
            world.falling.snapshot(c, blocks, meta, dropped);
            save.saveChunkAsync(config.worldId, new ChunkSnapshot(c.cx, c.cz,
                    blocks, meta, c.copyChests(), c.copyFurnaces(), dropped));
            c.savedItems = dropped.size();
            c.modified = false;
            written++;
        }
        if (written > 0)
            log("saved " + written + " chunks");
    }

    private void saveLevel() {
        // Инвентарь и положение игрока у сервера пустые: игрока нет. Поля в
        // level.dat всё равно нужны — файл общий с одиночной игрой, и мир,
        // снятый с сервера, обязан открываться в ней как свой.
        save.saveLevel(config.worldId, new LevelData(
                config.worldName, world.seed,
                spawn.x, spawn.y, spawn.z,
                spawn.x, spawn.y, spawn.z,
                0f, 0f, gameTime, 0,
                new ItemStack[Inventory.SIZE],
                config.creative ? GameMode.CREATIVE : GameMode.SURVIVAL,
                System.currentTimeMillis(), 20f, 20f, null, null));
    }

    private List<DroppedItem> itemsInChunk(Chunk c) {
        List<DroppedItem> out = new ArrayList<>();
        for (ItemEntity e : groundItems) {
            if (e.stack == null || e.stack.count <= 0)
                continue;
            int cx = Math.floorDiv((int) Math.floor(e.position.x), Chunk.SIZE_X);
            int cz = Math.floorDiv((int) Math.floor(e.position.z), Chunk.SIZE_Z);
            if (cx == c.cx && cz == c.cz)
                out.add(e.toDropped());
        }
        return out;
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
        return gameTime;
    }

    public void setGameTime(float t) {
        gameTime = t;
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
        return gameTime;
    }

    @Override
    public void setTimeOfDay(float t) {
        gameTime = t;
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
        world.setBlock(x, y, z, type, meta);
    }

    @Override
    public void remoteBlockAction(int actor, int x, int y, int z, byte blockId, boolean broke) {
        // Звук и пыль — дело тех, у кого есть экран.
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
        return mobs;
    }

    @Override
    public List<ItemEntity> groundItems() {
        return groundItems;
    }

    @Override
    public List<com.mineclone.world.entity.Projectile> projectiles() {
        return projectiles;
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
        projectiles.add(shot);
    }

    /** У сервера своего игрока нет — ранить в нём некого. */
    @Override
    public void hurtByHost(float damage) {
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
