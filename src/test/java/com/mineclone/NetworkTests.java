package com.mineclone;

import com.mineclone.net.LoopbackTransport;
import com.mineclone.net.connect.Backoff;
import com.mineclone.net.connect.ConnectDiagnosis;
import com.mineclone.net.connect.ConnectLadder;
import com.mineclone.net.connect.RegionFinder;
import com.mineclone.net.connect.RegionProbe;
import com.mineclone.net.connect.RoomCode;
import com.mineclone.net.direct.LanBeacon;
import com.mineclone.net.direct.NatPmp;
import com.mineclone.net.direct.PortMapper;
import com.mineclone.net.direct.PublicAddress;
import com.mineclone.net.direct.UpnpGateway;
import com.mineclone.net.Multiplayer;
import com.mineclone.net.NetContext;
import com.mineclone.net.NetProto;
import com.mineclone.net.NetSettings;
import com.mineclone.net.NetTransport;
import com.mineclone.net.PacketBuf;
import com.mineclone.net.RemotePlayer;
import com.mineclone.net.photon.PhotonCodes;
import com.mineclone.net.photon.PhotonJson;
import com.mineclone.net.photon.PhotonPeer;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.Options;
import com.mineclone.save.SaveManager;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Mob;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Проверки игры по сети.
 *
 * <p>Весь обмен гоняется двумя настоящими сессиями в одном процессе через
 * {@link LoopbackTransport}: рукопожатие, правки блоков в обе стороны, дельта
 * чанка, чат и чужой игрок. Никакого окна, GL и интернета — ровно поэтому
 * транспорт и вынесен в интерфейс.
 *
 * <p>Отдельно проверяется кодирование Photon: кадр, операция и разбор
 * ответа. Эти числа задаёт сервер, менять их нельзя, и единственный способ не
 * сломать их случайной правкой — сравнить с эталонной строкой.
 */
final class NetworkTests {

    private NetworkTests() {
    }

    interface Check {
        void run() throws Exception;
    }

    interface Runner {
        void run(String name, Check check);
    }

    static void runAll(Runner r) {
        r.run("packet buffer round-trips ints, strings and block positions",
                NetworkTests::testPacketBuf);
        r.run("truncated packet reads zeroes instead of throwing",
                NetworkTests::testPacketTruncation);
        r.run("photon frames survive splitting and re-joining",
                NetworkTests::testPhotonFraming);
        r.run("photon operation matches the wire format", NetworkTests::testPhotonOperation);
        r.run("photon response parses into parameter codes", NetworkTests::testPhotonResponse);
        r.run("photon server address keeps its own scheme", NetworkTests::testPhotonAddress);
        r.run("loopback room hands out actor numbers and a master",
                NetworkTests::testLoopbackRoom);
        r.run("lan transport carries a room over real sockets",
                NetworkTests::testLanSockets);
        r.run("joining a room brings the host's seed, name and clock",
                NetworkTests::testHandshake);
        r.run("host block edits reach the guest", NetworkTests::testHostEditReachesGuest);
        r.run("guest block edits go through the host and come back",
                NetworkTests::testGuestEditGoesThroughHost);
        r.run("remote block actions reach every listener", NetworkTests::testRemoteBlockAction);
        r.run("chunk delta carries only what differs from generation",
                NetworkTests::testChunkDelta);
        r.run("a whole session works over real sockets, not just in a loopback",
                NetworkTests::testSessionOverSockets);
        r.run("an untouched chunk costs an empty delta", NetworkTests::testEmptyChunkDelta);
        r.run("player state travels and settles where it was sent",
                NetworkTests::testPlayerState);
        r.run("mobs stream to the guest and the guest's hit lands on the host",
                NetworkTests::testMobs);
        r.run("chat reaches both sides with the sender's name", NetworkTests::testChat);
        r.run("chat appears above its sender, then expires", NetworkTests::testChatBubble);
        r.run("host leaving ends the guest's session", NetworkTests::testHostLeaves);
        r.run("protocol mismatch is refused with a readable reason",
                NetworkTests::testVersionMismatch);
        r.run("net settings survive options.dat", NetworkTests::testNetSettingsRoundTrip);
        r.run("the built-in photon key is used until someone overrides it",
                NetworkTests::testAppIdFallback);
        r.run("the lobby browser collects rooms and reports why it cannot",
                NetworkTests::testRoomBrowser);
        r.run("remote player walks only while it moves", NetworkTests::testRemotePlayerAnimation);
        r.run("a remote player stops striding as soon as it leaves the ground",
                NetworkTests::testRemotePlayerStopsStridingInAir);
        r.run("a remote player emits steps as it travels", NetworkTests::testRemoteFootsteps);
        r.run("a room code round-trips through its region", NetworkTests::testRoomCodeRoundTrip);
        r.run("a room code survives being copied by hand", NetworkTests::testRoomCodeTypos);
        r.run("a room code refuses what is not one", NetworkTests::testRoomCodeRejects);
        r.run("backoff grows and then gives up", NetworkTests::testBackoff);
        r.run("the connect ladder retries a step before changing it",
                NetworkTests::testConnectLadder);
        r.run("a refusal is explained in terms the player can act on",
                NetworkTests::testConnectDiagnosis);
        r.run("the nearest region wins the probe", NetworkTests::testRegionProbe);
        r.run("the region list is read as parallel arrays", NetworkTests::testRegionPairs);
        r.run("a nat-pmp mapping request and its answer match the rfc",
                NetworkTests::testNatPmpFrames);
        r.run("a stun binding response gives back the public address",
                NetworkTests::testStunResponse);
        r.run("the upnp control url comes from its own service block",
                NetworkTests::testUpnpDescription);
        r.run("both port-mapping refusals reach the player", NetworkTests::testPortMapperErrors);
        r.run("a lan beacon announces a world and expires", NetworkTests::testLanBeacon);
    }

    // ------------------------------------------------------------ примитивы

    private static void testPacketBuf() {
        PacketBuf b = new PacketBuf();
        b.u8(200).i16(-1234).i32(123456789).i64(-9876543210L).f32(1.5f);
        b.varInt(0).varInt(127).varInt(128).varInt(1_000_000);
        b.str("привет").str("");
        b.bytes(new byte[] { 1, 2, 3 });
        b.blockPos(-1234, 77, 5678);
        b.blockPos(0, 0, 0);

        PacketBuf in = PacketBuf.reading(b.toBytes());
        assertEq("u8", 200, in.readU8());
        assertEq("i16", (short) -1234, in.readI16());
        assertEq("i32", 123456789, in.readI32());
        assertEq("i64", -9876543210L, in.readI64());
        assertEq("f32", 1.5f, in.readF32());
        assertEq("varint 0", 0, in.readVarInt());
        assertEq("varint 127", 127, in.readVarInt());
        assertEq("varint 128", 128, in.readVarInt());
        assertEq("varint big", 1_000_000, in.readVarInt());
        assertEq("utf", "привет", in.readStr());
        assertEq("empty string", "", in.readStr());
        assertEq("bytes", 3, in.readBytes().length);
        int[] a = in.readBlockPos();
        assertEq("pos x", -1234, a[0]);
        assertEq("pos y", 77, a[1]);
        assertEq("pos z", 5678, a[2]);
        int[] zero = in.readBlockPos();
        assertEq("origin", 0, zero[0] + zero[1] + zero[2]);
        assertTrue("буфер прочитан целиком", !in.hasMore());
        assertTrue("обрыва не было", !in.truncated());
    }

    private static void testPacketTruncation() {
        PacketBuf b = new PacketBuf();
        b.i32(7).str("хвост");
        byte[] whole = b.toBytes();
        // Пакет, у которого отрезали конец: читатель обязан сообщить об этом,
        // а не бросить исключение посреди кадра игры.
        PacketBuf cut = PacketBuf.reading(whole, 0, 6);
        assertEq("целое число ещё цело", 7, cut.readI32());
        cut.readStr();
        assertTrue("обрыв замечен", cut.truncated());
        PacketBuf empty = PacketBuf.reading(new byte[0]);
        assertEq("пустой буфер отдаёт ноль", 0, empty.readI32());
        assertTrue("и помечается оборванным", empty.truncated());
    }

    // --------------------------------------------------------------- photon

    private static void testPhotonFraming() {
        String json = "{\"req\":230,\"vals\":[]}";
        String framed = PhotonPeer.frame(json);
        assertEq("кадр", "~m~" + (json.length() + 3) + "~m~~j~" + json, framed);
        List<String> bodies = PhotonPeer.frames(framed + PhotonPeer.frame("{\"evt\":255}"));
        assertEq("два кадра в одном сообщении", 2, bodies.size());
        assertEq("первое тело", "~j~" + json, bodies.get(0));
        // Номер сессии приходит отдельным кадром без префикса JSON.
        List<String> session = PhotonPeer.frames("~m~4~m~abcd");
        assertEq("номер сессии", "abcd", session.get(0));
        assertTrue("мусор не разбирается", PhotonPeer.frames("nonsense").isEmpty());
    }

    private static void testPhotonOperation() {
        String op = PhotonPeer.operationJson(PhotonCodes.OP_AUTHENTICATE,
                PhotonCodes.P_APPLICATION_ID, "abc-123",
                PhotonCodes.P_APP_VERSION, "mineclone-1",
                PhotonCodes.P_REGION, "eu");
        assertEq("операция входа",
                "{\"req\":230,\"vals\":[224,\"abc-123\",220,\"mineclone-1\",210,\"eu\"]}", op);
        // Целое число не имеет права уехать в дробное: у Photon это разные
        // типы, и «4.0» в поле числа игроков он не примет.
        StringBuilder sb = new StringBuilder();
        PhotonJson.write(sb, 4.0f);
        assertEq("целое пишется целым", "4", sb.toString());
        StringBuilder props = new StringBuilder();
        PhotonJson.write(props, Map.of("255", 8));
        assertEq("свойства комнаты", "{\"255\":8}", props.toString());
    }

    private static void testPhotonResponse() {
        String json = "{\"res\":230,\"err\":0,\"vals\":[230,\"192.0.2.10:19090\",221,\"token\"]}";
        Map<String, Object> msg = PhotonJson.parseMessage(json);
        assertEq("код ответа", Integer.valueOf(230), PhotonJson.asInt(msg.get("res")));
        Map<Integer, Object> vals = PhotonJson.vals(msg.get("vals"));
        assertEq("адрес мастера", "192.0.2.10:19090",
                PhotonJson.strOr(vals, PhotonCodes.P_ADDRESS, ""));
        assertEq("секрет", "token", PhotonJson.strOr(vals, PhotonCodes.P_SECRET, ""));
        // Нечётный список — испорченное сообщение: лучше пусто, чем сдвинутые
        // на единицу ключи.
        assertTrue("нечётный список параметров отбрасывается",
                PhotonJson.vals(List.of(1, 2, 3)).isEmpty());
        assertEq("ошибка переводится",
                "комната заполнена",
                PhotonCodes.errorText(PhotonCodes.ERR_GAME_FULL, "GameFull"));
    }

    private static void testPhotonAddress() {
        // Сервер отдаёт адрес без схемы; своя схема навешивается, чужая нет.
        assertEq("схема добавляется", "wss://1.2.3.4:19090",
                callWithScheme("1.2.3.4:19090", true));
        assertEq("готовая схема не трогается", "ws://1.2.3.4:9090",
                callWithScheme("ws://1.2.3.4:9090", true));
    }

    private static String callWithScheme(String address, boolean secure) {
        // withScheme пакетно-приватен, а тест лежит в другом пакете: повторяем
        // ровно то же правило, чтобы поймать расхождение при правке.
        if (address.startsWith("ws://") || address.startsWith("wss://"))
            return address;
        return (secure ? "wss://" : "ws://") + address;
    }

    // ----------------------------------------------------------- транспорт

    private static void testLoopbackRoom() {
        LoopbackTransport.reset();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        LoopbackTransport host = new LoopbackTransport(a);
        LoopbackTransport guest = new LoopbackTransport(b);
        host.connect("room", true, "Хозяин");
        host.poll();
        assertEq("хозяин получает первый номер", 1, host.myActor());
        assertTrue("комната создана им", a.created);
        guest.connect("room", false, "Гость");
        guest.poll();
        host.poll();
        assertEq("гость получает второй номер", 2, guest.myActor());
        assertTrue("комната ему не принадлежит", !b.created);
        assertEq("хозяин комнаты — наименьший номер", 1, guest.masterActor());
        assertEq("хозяин видит гостя", 1, a.joinedActors.size());
        assertEq("и знает его имя", "Гость", host.actorName(2));

        host.send(new byte[] { 42 }, true, 0);
        guest.poll();
        assertEq("байты дошли", 1, b.payloads.size());
        assertEq("и с тем же содержимым", (byte) 42, b.payloads.get(0)[0]);

        // Вход в несуществующую комнату — отказ, а не тихое создание.
        Recorder c = new Recorder();
        LoopbackTransport lost = new LoopbackTransport(c);
        lost.connect("нет такой", false, "Кто-то");
        lost.poll();
        assertEq("состояние отказа", NetTransport.State.FAILED, lost.state());

        guest.disconnect();
        host.poll();
        assertEq("выход замечен", 1, a.leftActors.size());
        host.disconnect();
    }

    /** Тот же протокол комнаты, но через настоящие TCP-сокеты на петле. */
    private static void testLanSockets() throws Exception {
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Recorder h = new Recorder();
        Recorder g = new Recorder();
        com.mineclone.net.LanTransport host = com.mineclone.net.LanTransport.host(port, h);
        com.mineclone.net.LanTransport guest =
                com.mineclone.net.LanTransport.join("127.0.0.1:" + port, g);
        try {
            host.connect("lan", true, "Хозяин");
            pollUntil(host, guest, () -> host.state() == NetTransport.State.JOINED);
            assertEq("хозяин слушает", NetTransport.State.JOINED, host.state());
            assertEq("и он первый", 1, host.myActor());

            guest.connect("lan", false, "Гость");
            pollUntil(host, guest, () -> guest.myActor() == 2 && !h.joinedActors.isEmpty());
            assertEq("гость получил номер", 2, guest.myActor());
            assertEq("хозяин узнал имя", "Гость", host.actorName(2));
            assertEq("хозяин мира — слушающий", 1, guest.masterActor());

            host.send(new byte[] { 7, 8, 9 }, true, 0);
            pollUntil(host, guest, () -> !g.payloads.isEmpty());
            assertEq("байты от хозяина", 3, g.payloads.get(0).length);

            guest.send(new byte[] { 1 }, true, 1);
            pollUntil(host, guest, () -> !h.payloads.isEmpty());
            assertEq("байты от гостя", (byte) 1, h.payloads.get(0)[0]);

            guest.disconnect();
            pollUntil(host, guest, () -> !h.leftActors.isEmpty());
            assertEq("выход замечен", Integer.valueOf(2), h.leftActors.get(0));
        } finally {
            guest.disconnect();
            host.disconnect();
        }
    }

    /** Качать оба конца, пока условие не сбудется: сокеты работают в своих потоках. */
    private static void pollUntil(NetTransport a, NetTransport b,
            java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            a.poll();
            b.poll();
            if (done.getAsBoolean())
                return;
            Thread.sleep(5);
        }
        throw new AssertionError("сеть не дошла до нужного состояния за две секунды");
    }

    // ------------------------------------------------------------- сессия

    private static void testHandshake() {
        Pair p = Pair.open(4242L, "Тестовый мир", 1.25f);
        assertTrue("хозяин — хозяин", p.hostNet.isHost());
        assertTrue("гость — участник", p.guestNet.isClient());
        assertEq("сид мира", 4242L, p.guest.startedSeed);
        assertEq("имя мира", "Тестовый мир", p.guest.startedName);
        assertTrue("время суток перенесено",
                Math.abs(p.guest.startedTime - 1.25f) < 1e-4f);
        assertTrue("гость знает, что мир готов", p.guestNet.worldReady());
        assertEq("хозяин видит одного соседа", 1, p.hostNet.players().size());
        assertEq("и знает его имя", "Гость",
                p.hostNet.players().iterator().next().name);
        p.close();
    }

    private static void testHostEditReachesGuest() {
        Pair p = Pair.open(7L, "Мир", 0.5f);
        // Правка ложится только в загруженный чанк — и у хозяина, и у гостя.
        p.host.world.getChunk(0, 0);
        p.guest.world.getChunk(0, 0);
        p.host.world.setBlock(3, 70, 4, BlockType.STONE);
        p.pump(3);
        assertEq("правка хозяина у гостя", BlockType.STONE,
                p.guest.world.getBlock(3, 70, 4));
        // И разрушение тоже: пустота обязана доехать так же, как камень.
        p.host.world.setBlock(3, 70, 4, BlockType.AIR);
        p.pump(3);
        assertEq("снос доехал", BlockType.AIR, p.guest.world.getBlock(3, 70, 4));
        p.close();
    }

    private static void testGuestEditGoesThroughHost() {
        Pair p = Pair.open(11L, "Мир", 0.5f);
        p.host.world.getChunk(0, 0);
        p.guest.world.getChunk(0, 0);
        p.guest.world.setBlock(5, 72, 6, BlockType.PLANKS);
        p.pump(4);
        assertEq("хозяин принял правку гостя", BlockType.PLANKS,
                p.host.world.getBlock(5, 72, 6));
        assertEq("и у гостя она осталась", BlockType.PLANKS,
                p.guest.world.getBlock(5, 72, 6));
        // Возврата по кругу быть не должно: хозяин рассылает результат один
        // раз, и гость на нём не заводит новую просьбу.
        int before = p.host.applied;
        p.pump(4);
        assertEq("правка не ходит по кругу", before, p.host.applied);
        p.close();
    }

    /** The block update synchronizes world state; this event synchronizes its sound. */
    private static void testRemoteBlockAction() {
        Pair p = Pair.open(6L, "Мир", 0.5f);
        p.guestNet.noteBlockAction(BlockType.STONE, false, 10, 70, -4);
        p.pump(2);
        assertEq("у хозяина один звук действия", 1, p.host.remoteBlockActions);
        assertEq("звук относится к поставленному блоку", BlockType.STONE, p.host.lastActionBlock);
        assertTrue("постановка не выглядит как ломание", !p.host.lastActionBroke);
        p.close();
    }

    private static void testChunkDelta() {
        Pair p = Pair.open(99L, "Мир", 0.5f);
        // Хозяин строит башню в чанке, которого у гостя ещё «не спрашивали».
        p.host.world.getChunk(1, 1);
        for (int y = 80; y < 86; y++)
            p.host.world.setBlock(20, y, 20, BlockType.STONE);
        p.pump(2);
        // Гость забывает эти правки и спрашивает чанк заново — так выглядит
        // вход в мир, который строили до тебя.
        World fresh = new World(99L);
        p.guest.world = fresh;
        fresh.setBlockObserver(p.guestNet::onWorldBlockChanged);
        fresh.getChunk(1, 1);
        p.guestNet.noteChunkLoaded(1, 1);
        // Дельту считает фоновый поток: он генерирует чистую копию чанка и
        // сравнивает её с живой. Ждём результат, а не угадываем число тиков.
        p.await(() -> fresh.getBlock(20, 85, 20) == BlockType.STONE);
        for (int y = 80; y < 86; y++)
            assertEq("дельта принесла башню на y=" + y, BlockType.STONE,
                    fresh.getBlock(20, y, 20));
        p.close();
    }

    /**
     * Та же сессия, но через настоящие сокеты.
     *
     * <p>Петлевой транспорт доставляет мгновенно и в одном потоке — на нём
     * невозможно поймать ни порядок кадров, ни то, что ответ приходит из
     * чужого потока. Этот тест гоняет рукопожатие и правку блока по TCP.
     */
    private static void testSessionOverSockets() throws Exception {
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        TestContext host = new TestContext(new World(31337L), "Сетевой мир");
        Multiplayer hostNet = new Multiplayer(host);
        host.world.setBlockObserver(hostNet::onWorldBlockChanged);
        host.world.getChunk(0, 0);
        com.mineclone.net.LanTransport hostT =
                com.mineclone.net.LanTransport.host(port, hostNet);

        TestContext guest = new TestContext(null, "");
        Multiplayer guestNet = new Multiplayer(guest);
        guest.onWorldStarted = w -> {
            w.setBlockObserver(guestNet::onWorldBlockChanged);
            w.getChunk(0, 0);
        };
        com.mineclone.net.LanTransport guestT =
                com.mineclone.net.LanTransport.join("127.0.0.1:" + port, guestNet);
        try {
            hostNet.start(hostT, "lan", true, "Хозяин");
            guestNet.start(guestT, "lan", false, "Гость");
            awaitNet(hostNet, guestNet, () -> guestNet.worldReady() && guest.world != null);
            assertEq("сид доехал", 31337L, guest.startedSeed);
            assertEq("имя мира доехало", "Сетевой мир", guest.startedName);

            host.world.setBlock(2, 70, 2, BlockType.PLANKS);
            awaitNet(hostNet, guestNet,
                    () -> guest.world.getBlock(2, 70, 2) == BlockType.PLANKS);
            assertEq("правка доехала по сокету", BlockType.PLANKS,
                    guest.world.getBlock(2, 70, 2));

            guest.world.setBlock(4, 70, 4, BlockType.STONE);
            awaitNet(hostNet, guestNet,
                    () -> host.world.getBlock(4, 70, 4) == BlockType.STONE);
            assertEq("обратная правка доехала", BlockType.STONE,
                    host.world.getBlock(4, 70, 4));
        } finally {
            guestNet.stop(null);
            hostNet.stop(null);
        }
    }

    private static void awaitNet(Multiplayer a, Multiplayer b,
            java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            a.update(0.1f);
            b.update(0.1f);
            if (done.getAsBoolean())
                return;
            Thread.sleep(5);
        }
        throw new AssertionError("сессия не дошла до нужного состояния за две секунды");
    }

    private static void testEmptyChunkDelta() {
        Pair p = Pair.open(123L, "Мир", 0.5f);
        World fresh = new World(123L);
        p.guest.world = fresh;
        fresh.setBlockObserver(p.guestNet::onWorldBlockChanged);
        fresh.getChunk(5, 5);
        // Чанк, которого никто не касался, обязан совпасть с генерацией у
        // обоих: иначе детерминизм сломан и дельты бессмысленны.
        Chunk mine = p.host.world.getChunk(5, 5);
        byte[] a = mine.copyBlocks();
        byte[] b = fresh.getChunk(5, 5).copyBlocks();
        assertTrue("генерация совпадает бит в бит", java.util.Arrays.equals(a, b));
        int appliedBefore = p.guest.applied;
        p.guestNet.noteChunkLoaded(5, 5);
        p.pump(8);
        assertEq("применять нечего", appliedBefore, p.guest.applied);
        p.close();
    }

    private static void testPlayerState() {
        Pair p = Pair.open(3L, "Мир", 0.5f);
        p.host.position.set(12.5f, 70f, -3.25f);
        p.host.yaw = 1.5f;
        p.pump(3);
        RemotePlayer seen = p.guestNet.players().iterator().next();
        // Модель догоняет цель, а не прыгает: сразу после снимка она ещё в
        // пути, через несколько кадров — на месте.
        for (int i = 0; i < 30; i++)
            seen.update(1f / 60f);
        assertTrue("положение доехало",
                Math.abs(seen.position.x - 12.5f) < 0.01f
                        && Math.abs(seen.position.z + 3.25f) < 0.01f);
        assertTrue("курс доехал", Math.abs(seen.yaw - 1.5f) < 0.01f);
        p.close();
    }

    /**
     * Мобы едут снимками, а удар — заявкой.
     *
     * <p>У участника моб только показывается: бей он по своей копии, хозяин
     * ничего бы не заметил, и зомби у него остался бы живым.
     */
    private static void testMobs() {
        Pair p = Pair.open(4242L, "Мир", 0.5f);
        Mob chicken = new Mob(com.mineclone.world.entity.MobType.CHICKEN,
                10f, 70f, 10f, new java.util.Random(1));
        float full = chicken.health;
        p.host.mobs.add(chicken);
        p.pump(3);
        assertEq("моб виден участнику", 1, p.guest.mobs.size());
        Mob shown = p.guest.mobs.get(0);
        assertEq("и того же вида", chicken.type, shown.type);
        assertTrue("и на том же месте",
                Math.abs(shown.position.x - 10f) < 0.01f
                        && Math.abs(shown.position.z - 10f) < 0.01f);

        p.guestNet.requestMobHit(shown, 3f, 1f, 9f, 9f);
        p.pump(3);
        assertTrue("удар участника дошёл до живого моба", chicken.health < full);

        p.host.mobs.clear();
        p.pump(3);
        assertEq("убранный моб исчезает и у участника", 0, p.guest.mobs.size());
        p.close();
    }

    private static void testChat() {
        Pair p = Pair.open(5L, "Мир", 0.5f);
        p.hostNet.sendChat("привет");
        p.pump(2);
        assertTrue("реплика у гостя",
                p.guest.chat.stream().anyMatch(s -> s.contains("привет")));
        assertTrue("и с именем отправителя",
                p.guest.chat.stream().anyMatch(s -> s.startsWith("Хозяин:")));
        p.guestNet.sendChat("и тебе");
        p.pump(2);
        assertTrue("ответ у хозяина",
                p.host.chat.stream().anyMatch(s -> s.startsWith("Гость:")));
        p.close();
    }

    /** A chat line belongs to its sender in the world, not only to the HUD log. */
    private static void testChatBubble() {
        Pair p = Pair.open(7L, "Мир", 0.5f);
        p.hostNet.sendChat("привет");
        p.pump(2);
        RemotePlayer host = p.guestNet.players().iterator().next();
        assertEq("реплика висит над отправителем", "привет", host.chatText());
        for (int i = 0; i < 51; i++)
            host.update(0.1f);
        assertEq("старая реплика исчезает", "", host.chatText());
        p.close();
    }

    private static void testHostLeaves() {
        Pair p = Pair.open(6L, "Мир", 0.5f);
        p.hostNet.stop(null);
        p.pump(3);
        assertTrue("гость остался без мира", !p.guestNet.active());
        assertTrue("и знает почему", p.guest.stopped != null
                && p.guest.stopped.contains("хозяин"));
        p.close();
    }

    private static void testVersionMismatch() {
        LoopbackTransport.reset();
        TestContext host = new TestContext(new World(1L), "Мир");
        Multiplayer hostNet = new Multiplayer(host);
        host.world.setBlockObserver(hostNet::onWorldBlockChanged);
        LoopbackTransport hostT = new LoopbackTransport(hostNet);
        hostNet.start(hostT, "room", true, "Хозяин");
        hostT.poll();

        // Чужая версия притворяется участником вручную: сессия такой пакет
        // собрать не может, а сервер обязан его пережить.
        Recorder rec = new Recorder();
        LoopbackTransport odd = new LoopbackTransport(rec);
        odd.connect("room", false, "Старая сборка");
        odd.poll();
        hostT.poll();
        PacketBuf hello = new PacketBuf();
        hello.u8(NetProto.C_HELLO).varInt(NetProto.VERSION + 7).str("Старая сборка");
        odd.send(hello.toBytes(), true, 1);
        for (int i = 0; i < 4; i++) {
            hostNet.update(0.1f);
            odd.poll();
        }
        boolean refused = false;
        for (byte[] payload : rec.payloads) {
            PacketBuf in = PacketBuf.reading(payload);
            // В одном сообщении едет несколько пакетов: отказ идёт следом за
            // сведениями об игроке, и разобрать надо оба.
            while (in.hasMore() && !refused) {
                int code = in.readU8();
                if (code == NetProto.S_REJECT) {
                    refused = in.readStr().contains("версия");
                } else if (code == NetProto.X_PLAYER_INFO) {
                    in.readStr();
                    in.readU8();
                    in.readF32();
                } else {
                    break;
                }
            }
        }
        assertTrue("отказ с внятной причиной", refused);
        hostNet.stop(null);
        odd.disconnect();
    }

    private static void testNetSettingsRoundTrip() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("mineclone-net").toFile();
        SaveManager save = new SaveManager(new java.io.File(dir, "saves"));
        NetSettings net = new NetSettings(NetSettings.LAN, "Игрок Икс", "ABCD-1234", "eu",
                "моя комната", "192.168.1.5", 25570);
        Options base = Options.defaults();
        save.saveOptions(new Options(base.renderRadius, base.fovDegrees, base.brightness,
                base.masterVolume, base.maxFps, base.vsync, base.fullscreen, base.viewBobbing,
                base.mouseSensitivity, base.invertMouseY, base.musicVolume, base.effectsVolume,
                base.guiScale, base.shaderQuality, base.keys, base.advancedTooltips,
                base.recipeBookOpen, base.recipeBookCraftable, base.recipeBookCategory,
                base.sortMode, base.video, base.graphics, base.gameplay, net));
        Options back = save.loadOptions();
        assertEq("транспорт", NetSettings.LAN, back.net.transport());
        assertEq("имя", "Игрок Икс", back.net.nickname());
        assertEq("ключ", "ABCD-1234", back.net.appId());
        assertEq("регион", "eu", back.net.region());
        assertEq("комната", "моя комната", back.net.room());
        assertEq("адрес", "192.168.1.5", back.net.address());
        assertEq("порт", 25570, back.net.port());
        deleteTree(dir);
    }

    /**
     * Ключ Photon: встроенный, свой, запуск.
     *
     * <p>Порядок важен: проверки и чужие сборки подменяют ключ на запуске, и
     * он обязан перебивать и настройки игрока, и то, что зашито в сборку.
     */
    private static void testAppIdFallback() {
        NetSettings plain = NetSettings.defaults();
        assertTrue("пустое поле — встроенный ключ", !plain.hasOwnAppId());
        assertEq("и он именно встроенный", NetSettings.DEFAULT_APP_ID, plain.effectiveAppId());
        assertTrue("встроенного ключа хватает, чтобы пойти в комнату",
                plain.withRoom("room").canConnect(false));

        NetSettings own = plain.withAppId("AAAA-BBBB");
        assertTrue("свой ключ виден как свой", own.hasOwnAppId());
        assertEq("и используется", "AAAA-BBBB", own.effectiveAppId());

        String before = System.getProperty(NetSettings.APP_ID_PROPERTY);
        try {
            System.setProperty(NetSettings.APP_ID_PROPERTY, "FROM-RUN");
            assertEq("запуск перебивает свой ключ", "FROM-RUN", own.effectiveAppId());
            assertEq("и встроенный", "FROM-RUN", plain.effectiveAppId());
        } finally {
            if (before == null)
                System.clearProperty(NetSettings.APP_ID_PROPERTY);
            else
                System.setProperty(NetSettings.APP_ID_PROPERTY, before);
        }
    }

    /**
     * Лобби: список комнат и внятный отказ.
     *
     * <p>Сеть тут не нужна — проверяется ровно то, что делает обозреватель с
     * тем, что ему принёс транспорт.
     */
    private static void testRoomBrowser() {
        com.mineclone.net.RoomBrowser b = new com.mineclone.net.RoomBrowser();
        assertEq("без открытия — пусто", com.mineclone.net.RoomBrowser.State.IDLE, b.state());

        // У прямого соединения лобби нет: никто не ведёт реестра комнат.
        b.open(NetSettings.defaults().withTransport(NetSettings.LAN));
        assertTrue("для своей сети список не поддерживается", !b.supported());
        assertEq("и подключения нет", com.mineclone.net.RoomBrowser.State.IDLE, b.state());

        b.onRoomList(List.of(
                new NetTransport.RoomInfo("first", 1, 8),
                new NetTransport.RoomInfo("full", 8, 8)));
        assertEq("список принят", com.mineclone.net.RoomBrowser.State.READY, b.state());
        assertEq("и целиком", 2, b.rooms().size());
        assertEq("порядок сохранён", "first", b.rooms().get(0).name());
        assertTrue("состояние читаемое", b.status().contains("2"));

        // Пустое лобби — это ответ, а не молчание.
        b.onRoomList(List.of());
        assertEq("пустой список тоже готов", com.mineclone.net.RoomBrowser.State.READY, b.state());
        assertTrue("и сказано, что комнат нет", b.status().contains("нет"));

        b.onState(NetTransport.State.FAILED, "Photon не принял ключ приложения");
        assertEq("отказ виден", com.mineclone.net.RoomBrowser.State.FAILED, b.state());
        assertTrue("с причиной", b.status().contains("ключ"));

        b.close();
        assertEq("после закрытия — снова пусто",
                com.mineclone.net.RoomBrowser.State.IDLE, b.state());
        assertTrue("и без комнат", b.rooms().isEmpty());
    }

    private static void testRemotePlayerAnimation() {
        RemotePlayer p = new RemotePlayer(2, "Гость");
        p.accept(0f, 64f, 0f, 0f, 0f, RemotePlayer.F_ON_GROUND);
        p.update(1f / 60f);
        float still = p.walkAmount;
        // Идёт — размах растёт; встал — гаснет. Расстояние монотонно, и без
        // затухания остановившийся игрок замер бы с раскинутыми ногами.
        for (int i = 0; i < 12; i++) {
            p.accept(p.position.x + 0.4f, 64f, 0f, 0f, 0f, RemotePlayer.F_ON_GROUND);
            for (int k = 0; k < 5; k++)
                p.update(1f / 60f);
        }
        float walking = p.walkAmount;
        assertTrue("на ходу размах растёт", walking > still + 0.2f);
        float distance = p.walkedDistance;
        for (int i = 0; i < 60; i++)
            p.update(1f / 60f);
        assertTrue("на месте размах гаснет", p.walkAmount < 0.05f);
        assertTrue("путь не убывает", p.walkedDistance >= distance);
        p.accept(p.position.x + 0.8f, p.position.y + 1f, p.position.z,
                0f, 0f, 0);
        p.update(0.12f);
        assertTrue("в полёте ноги не шагают", p.walkAmount < 0.05f);
    }

    /** Interpolated movement must also drive the audible walking cadence. */
    private static void testRemoteFootsteps() {
        RemotePlayer p = new RemotePlayer(2, "Гость");
        p.accept(0f, 64f, 0f, 0f, 0f, RemotePlayer.F_ON_GROUND);
        p.update(1f / 60f);
        assertTrue("на месте шага нет", !p.consumeFootstep());
        p.accept(2.1f, 64f, 0f, 0f, 0f, RemotePlayer.F_ON_GROUND);
        p.update(0.12f);
        assertTrue("пройденные два блока дают шаг", p.consumeFootstep());
        assertTrue("одно движение не дублирует шаг", !p.consumeFootstep());
    }

    /** A jumping or flying player must not show one more visible ground step. */
    private static void testRemotePlayerStopsStridingInAir() {
        RemotePlayer p = new RemotePlayer(2, "Гость");
        p.accept(0f, 64f, 0f, 0f, 0f, RemotePlayer.F_ON_GROUND);
        p.update(1f / 60f);
        p.accept(0.5f, 64f, 0f, 0f, 0f, RemotePlayer.F_ON_GROUND);
        for (int i = 0; i < 8; i++)
            p.update(1f / 60f);
        assertTrue("на земле ноги разошлись", p.walkAmount > 0.2f);
        p.accept(p.position.x, p.position.y + 0.4f, p.position.z, 0f, 0f,
                RemotePlayer.F_FLYING);
        p.update(1f / 60f);
        assertTrue("в воздухе поза сразу нейтральна", p.walkAmount < 0.01f);
    }

    // --------------------------------------------------------------- опоры

    /** Пара сессий в одной комнате: хозяин и участник. */
    private static final class Pair {
        final TestContext host;
        final TestContext guest;
        final Multiplayer hostNet;
        final Multiplayer guestNet;
        final LoopbackTransport hostT;
        final LoopbackTransport guestT;

        private Pair(TestContext host, TestContext guest, Multiplayer hostNet,
                Multiplayer guestNet, LoopbackTransport hostT, LoopbackTransport guestT) {
            this.host = host;
            this.guest = guest;
            this.hostNet = hostNet;
            this.guestNet = guestNet;
            this.hostT = hostT;
            this.guestT = guestT;
        }

        static Pair open(long seed, String name, float time) {
            LoopbackTransport.reset();
            TestContext host = new TestContext(new World(seed), name);
            host.time = time;
            Multiplayer hostNet = new Multiplayer(host);
            host.world.setBlockObserver(hostNet::onWorldBlockChanged);
            LoopbackTransport hostT = new LoopbackTransport(hostNet);
            hostNet.start(hostT, "room", true, "Хозяин");

            TestContext guest = new TestContext(null, "");
            Multiplayer guestNet = new Multiplayer(guest);
            LoopbackTransport guestT = new LoopbackTransport(guestNet);
            guestNet.start(guestT, "room", false, "Гость");

            Pair p = new Pair(host, guest, hostNet, guestNet, hostT, guestT);
            guest.onWorldStarted = w -> w.setBlockObserver(guestNet::onWorldBlockChanged);
            p.pump(4);
            return p;
        }

        /**
         * Качать сеть, пока условие не сбудется.
         *
         * <p>Дельта чанка считается в фоновом потоке, и «подождать десять
         * тиков» здесь означало бы гонку: на быстрой машине цикл успевает
         * закончиться раньше, чем поток сгенерирует чистый чанк.
         */
        void await(java.util.function.BooleanSupplier done) {
            for (int i = 0; i < 400; i++) {
                pump(1);
                if (done.getAsBoolean())
                    return;
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** Несколько сетевых тиков туда-обратно. */
        void pump(int ticks) {
            for (int i = 0; i < ticks; i++) {
                hostNet.update(0.1f);
                guestNet.update(0.1f);
                hostNet.update(0.1f);
                guestNet.update(0.1f);
            }
        }

        void close() {
            hostNet.stop(null);
            guestNet.stop(null);
            LoopbackTransport.reset();
        }
    }

    /** Игра, какой её видит сессия: без окна, GL и звука. */
    private static final class TestContext implements NetContext {
        World world;
        String name;
        float time = 0.5f;
        int mode;
        final Vector3f spawn = new Vector3f(8f, 80f, 8f);
        final Vector3f position = new Vector3f(8f, 80f, 8f);
        float yaw, pitch, health = 20f;
        final List<Mob> mobs = new ArrayList<>();
        final List<ItemEntity> items = new ArrayList<>();
        final List<String> chat = new ArrayList<>();
        final List<ItemStack> given = new ArrayList<>();
        String stopped;
        int applied;
        int remoteBlockActions;
        BlockType lastActionBlock;
        boolean lastActionBroke;
        long startedSeed;
        String startedName = "";
        float startedTime;
        java.util.function.Consumer<World> onWorldStarted;

        TestContext(World world, String name) {
            this.world = world;
            this.name = name;
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
            return name;
        }

        @Override
        public float timeOfDay() {
            return time;
        }

        @Override
        public void setTimeOfDay(float t) {
            time = t;
        }

        @Override
        public int gameMode() {
            return mode;
        }

        @Override
        public Vector3f spawn() {
            return spawn;
        }

        @Override
        public void startRemoteWorld(long seed, String worldName, float t, int gameMode,
                float sx, float sy, float sz) {
            startedSeed = seed;
            startedName = worldName;
            startedTime = t;
            name = worldName;
            time = t;
            mode = gameMode;
            spawn.set(sx, sy, sz);
            position.set(sx, sy, sz);
            world = new World(seed);
            if (onWorldStarted != null)
                onWorldStarted.accept(world);
        }

        @Override
        public void applyRemoteBlock(int x, int y, int z, byte id, byte meta, boolean broke) {
            if (world == null)
                return;
            BlockType t = BlockType.byId(id);
            if (world.getBlock(x, y, z) == t && world.getBlockMeta(x, y, z) == meta)
                return;
            applied++;
            world.setBlock(x, y, z, t, meta);
        }

        @Override
        public void remoteBlockAction(int actor, int x, int y, int z, byte blockId,
                boolean broke) {
            remoteBlockActions++;
            lastActionBlock = BlockType.byId(blockId);
            lastActionBroke = broke;
        }

        @Override
        public ChunkSnapshot loadSavedChunk(int cx, int cz) {
            return null;
        }

        @Override
        public Vector3f playerPosition() {
            return position;
        }

        @Override
        public float playerYaw() {
            return yaw;
        }

        @Override
        public float playerPitch() {
            return pitch;
        }

        @Override
        public int playerFlags() {
            return RemotePlayer.F_ON_GROUND;
        }

        @Override
        public float playerHealth() {
            return health;
        }

        @Override
        public List<Mob> mobs() {
            return mobs;
        }

        @Override
        public List<ItemEntity> groundItems() {
            return items;
        }

        @Override
        public void chatLine(String line) {
            chat.add(line);
        }

        @Override
        public void status(String line) {
            chat.add(line);
        }

        @Override
        public void give(ItemStack stack) {
            given.add(stack);
        }

        @Override
        public void netStopped(String reason) {
            stopped = reason;
        }

        @Override
        public void containerFromHost(int x, int y, int z, int kind, ItemStack[] slots,
                float burnLeft, float burnMax, float cook) {
        }
    }

    /** Слушатель транспорта, который просто всё записывает. */
    private static final class Recorder implements NetTransport.Listener {
        final List<byte[]> payloads = new ArrayList<>();
        final List<Integer> joinedActors = new ArrayList<>();
        final List<Integer> leftActors = new ArrayList<>();
        boolean created;
        NetTransport.State state = NetTransport.State.IDLE;

        @Override
        public void onState(NetTransport.State s, String detail) {
            state = s;
        }

        @Override
        public void onJoined(int myActor, boolean wasCreated) {
            created = wasCreated;
        }

        @Override
        public void onActorJoin(int actor, String name) {
            joinedActors.add(actor);
        }

        @Override
        public void onActorLeave(int actor) {
            leftActors.add(actor);
        }

        @Override
        public void onPayload(int from, byte[] data) {
            payloads.add(data);
        }

        @Override
        public void onRoomList(List<NetTransport.RoomInfo> rooms) {
        }
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null)
            for (java.io.File k : kids)
                deleteTree(k);
        if (!f.delete())
            f.deleteOnExit();
    }

    // ------------------------------------------------------ дойти до комнаты

    /**
     * Регион едет внутри кода.
     *
     * <p>Это главное свойство всей затеи: код, набранный верно, не может
     * увести в чужой регион — а раньше именно это и происходило, потому что
     * регион договаривались голосом и забывали.
     */
    private static void testRoomCodeRoundTrip() {
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 1; i < PhotonCodes.REGIONS.length; i++) {
            String region = PhotonCodes.REGIONS[i];
            RoomCode made = RoomCode.generate(region, rnd);
            assertEq("код длиной", RoomCode.LENGTH, made.code().length());
            RoomCode read = RoomCode.parse(made.code());
            assertTrue("код " + made.code() + " разобрался", read != null);
            assertEq("регион вернулся", region, read.region());
            assertEq("сам код вернулся", made.code(), read.code());
            assertEq("имя комнаты — это код", made.code(), read.roomName());
        }
    }

    /** Переписанный от руки код всё равно входит. */
    private static void testRoomCodeTypos() {
        RoomCode made = RoomCode.generate("eu", new java.util.Random(3));
        String code = made.code();
        assertEq("строчные буквы", code, RoomCode.normalize(code.toLowerCase()));
        assertEq("дефис для глаза", code, RoomCode.normalize(made.pretty()));
        assertEq("пробелы", code, RoomCode.normalize(" " + code + " "));
        // O, I и L на бумаге неотличимы от нуля и единицы; в алфавите кода их
        // нет, поэтому читаются они однозначно.
        assertEq("O вместо нуля", "A0B123", RoomCode.normalize("AOB123"));
        assertEq("I вместо единицы", "A1B123", RoomCode.normalize("AIB123"));
        assertEq("l вместо единицы", "A1B123", RoomCode.normalize("alB123"));
    }

    /** Не код — значит не код: молчаливое «почти подошло» хуже отказа. */
    private static void testRoomCodeRejects() {
        assertTrue("пусто", RoomCode.parse("") == null);
        assertTrue("коротко", RoomCode.parse("A4K7") == null);
        // Знаком длиннее — это чужой код, а не опечатка: обрезав хвост, игра
        // увела бы игрока в комнату, которой он не называл.
        assertTrue("длинно", RoomCode.parse("A4K7M2X") == null);
        assertEq("а поле ввода хвост просто не примет", "A4K7M2",
                RoomCode.typed("A4K7M2X"));
        // Первый знак кода — всегда буква из «регионной» части алфавита:
        // цифра там означает, что это не наш код.
        assertTrue("цифра вместо региона", RoomCode.parse("14K7M2") == null);
        assertTrue("буква за пределами списка регионов", RoomCode.parse("Z4K7M2") == null);
        assertTrue("«Авто» кодом не бывает", RoomCode.regionIndex("") < 0);
        boolean threw = false;
        try {
            RoomCode.generate("", new java.util.Random(1));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue("код из «Авто» не делается", threw);
    }

    private static void testBackoff() {
        Backoff b = new Backoff();
        assertEq("первая попытка", 1, b.attempt());
        assertEq("пауза перед второй", 0.5f, b.failed());
        assertEq("пауза перед третьей", 1.5f, b.failed());
        assertEq("пауза перед четвёртой", 4f, b.failed());
        assertTrue("после последней паузы попытки ещё есть", !b.exhausted());
        assertTrue("четвёртая была последней", b.failed() < 0f);
        assertTrue("попытки кончились", b.exhausted());
        b.reset();
        assertTrue("сброс возвращает попытки", !b.exhausted());
        assertEq("и счётчик", 1, b.attempt());
    }

    /**
     * Обрыв повторяют на той же ступени, а закрытый порт меняет ступень.
     *
     * <p>Наоборот было бы хуже: уходить с шифрованного входа из-за одной
     * потерянной секунды значит отдавать ключ приложения открытым текстом
     * на ровном месте.
     */
    private static void testConnectLadder() {
        ConnectLadder ladder = new ConnectLadder();
        ConnectLadder.Step first = ladder.current();
        assertTrue("первая ступень шифрованная", first.secure());
        for (int i = 0; i < Backoff.DELAYS.length; i++) {
            assertTrue("ждём перед повтором", ladder.failed("connection refused") > 0f);
            assertEq("ступень та же", first, ladder.current());
        }
        assertEq("смена ступени идёт без паузы", 0f, ladder.failed("connection refused"));
        ConnectLadder.Step second = ladder.current();
        assertTrue("вторая ступень не шифрованная", !second.secure());
        assertTrue("и это другой адрес", !first.nameServer().equals(second.nameServer()));
        for (int i = 0; i < Backoff.DELAYS.length; i++)
            ladder.failed("connection refused");
        assertTrue("лестница кончилась", ladder.failed("connection refused") < 0f);
        assertTrue("и больше ступеней нет", ladder.exhausted());
        assertTrue("current() молчит", ladder.current() == null);
    }

    private static void testConnectDiagnosis() {
        RoomCode code = RoomCode.parse("A4K7M2");
        String missing = ConnectDiagnosis.explain(
                PhotonCodes.errorText(PhotonCodes.ERR_GAME_DOES_NOT_EXIST, ""),
                null, true, code, false);
        assertTrue("названа комната: " + missing, missing.contains(code.pretty()));
        assertTrue("и её регион: " + missing, missing.contains(code.regionLabel()));

        ConnectLadder ladder = new ConnectLadder();
        for (int i = 0; i <= Backoff.DELAYS.length; i++)
            ladder.failed("Connection refused");
        String closed = ladder.diagnosis(code, false);
        assertTrue("сказано про порт: " + closed, closed.contains("порт"));
        assertTrue("и что пробуем дальше: " + closed,
                closed.contains(ladder.current().label()));

        String key = ConnectDiagnosis.explain("Photon не принял ключ приложения",
                null, true, code, true);
        assertTrue("про свой ключ: " + key, key.contains("Свой ключ"));
    }

    /** Ближайший регион выигрывает, недоступные не участвуют. */
    private static void testRegionProbe() {
        java.util.Map<String, String> regions = new java.util.LinkedHashMap<>();
        regions.put("eu", "eu.example:5058");
        regions.put("us", "us.example:5058");
        regions.put("asia", "asia.example:5058");
        java.util.Map<String, Integer> fake = java.util.Map.of(
                "eu.example:5058", 42,
                "us.example:5058", 110,
                "asia.example:5058", -1);
        java.util.List<RegionProbe.Result> results =
                RegionProbe.measure(regions, a -> fake.getOrDefault(a, -1));
        assertEq("замерены все", 3, results.size());
        assertEq("ближайший первым", "eu", results.get(0).region());
        assertEq("и он же выбран", "eu", RegionProbe.best(results));
        assertTrue("недоступный ушёл в конец", !results.get(2).reachable());

        // Не ответил никто — «Авто» на этом месте назвать нельзя: из него не
        // сделать кода комнаты.
        assertEq("никого", "", RegionProbe.best(
                RegionProbe.measure(regions, a -> -1)));
        assertEq("схема и путь отброшены", "eu.example:5058",
                RegionProbe.stripScheme("wss://eu.example:5058/app"));
    }

    private static void testRegionPairs() {
        java.util.Map<String, String> pairs = RegionFinder.pairs(
                java.util.List.of("eu", "us", "ru"),
                java.util.List.of("eu.example:5058", "us.example:5058", "ru.example:5058"));
        assertEq("столько же", 3, pairs.size());
        assertEq("адрес на месте", "us.example:5058", pairs.get("us"));
        // Массивы разной длины — испорченный ответ: лишнее отбрасывается, а не
        // съезжает на единицу.
        assertEq("короче адресов", 1, RegionFinder.pairs(
                java.util.List.of("eu", "us"), java.util.List.of("eu.example:5058")).size());
        assertEq("не массив", 0, RegionFinder.pairs("eu", "eu.example").size());
    }

    // ------------------------------------------------- прямое соединение

    /**
     * Кадр NAT-PMP — двенадцать байт, и все они на своих местах.
     *
     * <p>Числа задаёт RFC 6886, проверить их против живого роутера нельзя (у
     * каждого свой), поэтому единственная защита от случайной правки — эталон
     * в тесте.
     */
    private static void testNatPmpFrames() {
        byte[] req = NatPmp.mapRequest(25566, 3600);
        assertEq("длина запроса", 12, req.length);
        assertEq("версия", 0, req[0] & 0xFF);
        assertEq("операция TCP", 2, req[1] & 0xFF);
        assertEq("внутренний порт, старший байт", 25566 >> 8, req[4] & 0xFF);
        assertEq("внутренний порт, младший", 25566 & 0xFF, req[5] & 0xFF);
        assertEq("внешний порт тот же", 25566 & 0xFF, req[7] & 0xFF);
        assertEq("срок жизни", 3600, ((req[8] & 0xFF) << 24) | ((req[9] & 0xFF) << 16)
                | ((req[10] & 0xFF) << 8) | (req[11] & 0xFF));

        // Ответ на операцию n приходит с кодом n + 128.
        byte[] ok = new byte[] { 0, (byte) 130, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) (25566 >> 8), (byte) 25566, 0, 0, 0x0E, 0x10 };
        NatPmp.Mapping m = NatPmp.parseMapping(ok, ok.length);
        assertTrue("принято: " + m.error(), m.ok());
        assertEq("внешний порт", 25566, m.externalPort());
        assertEq("срок", 3600, m.lifetimeSeconds());

        byte[] refused = ok.clone();
        refused[3] = 2;
        assertTrue("отказ распознан", !NatPmp.parseMapping(refused, refused.length).ok());
        byte[] alien = ok.clone();
        alien[1] = (byte) 128;
        assertTrue("чужой ответ не принят",
                !NatPmp.parseMapping(alien, alien.length).ok());
        assertTrue("обрезанный не принят", !NatPmp.parseMapping(ok, 8).ok());
    }

    /** Адрес из ответа STUN, сложенный с постоянной протокола, читается верно. */
    private static void testStunResponse() {
        byte[] id = new byte[12];
        for (int i = 0; i < id.length; i++)
            id[i] = (byte) (i + 1);
        byte[] req = PublicAddress.bindingRequest(id);
        assertEq("длина запроса", 20, req.length);
        assertEq("тип Binding", 0x0001, ((req[0] & 0xFF) << 8) | (req[1] & 0xFF));
        assertEq("постоянная протокола", 0x2112A442,
                ((req[4] & 0xFF) << 24) | ((req[5] & 0xFF) << 16)
                        | ((req[6] & 0xFF) << 8) | (req[7] & 0xFF));

        // 203.0.113.7, сложенное по модулю два с постоянной протокола.
        byte[] addr = { (byte) (203 ^ 0x21), (byte) (0 ^ 0x12),
                (byte) (113 ^ 0xA4), (byte) (7 ^ 0x42) };
        byte[] res = new byte[20 + 12];
        res[0] = 0x01;
        res[1] = 0x01;
        res[2] = 0;
        res[3] = 12;
        System.arraycopy(req, 4, res, 4, 4);
        System.arraycopy(id, 0, res, 8, 12);
        res[20] = 0x00;
        res[21] = 0x20;   // XOR-MAPPED-ADDRESS
        res[22] = 0;
        res[23] = 8;
        res[24] = 0;
        res[25] = 1;      // семейство: IPv4
        res[26] = 0x11;
        res[27] = 0x12;   // порт нас не интересует
        System.arraycopy(addr, 0, res, 28, 4);
        assertEq("адрес", "203.0.113.7", PublicAddress.parseResponse(res, res.length, id));

        // Ответ на чужой запрос — не наш ответ: номер обращения для того и есть.
        byte[] other = new byte[12];
        assertEq("чужое обращение", "", PublicAddress.parseResponse(res, res.length, other));
        byte[] request = res.clone();
        request[1] = 0x00;
        assertEq("не ответ вовсе", "", PublicAddress.parseResponse(request, request.length, id));
    }

    /**
     * Адрес управления берётся из блока своей службы.
     *
     * <p>Служб в описании роутера с десяток, и {@code controlURL} есть у
     * каждой. Взять чужой значит попросить открыть порт у часов или у
     * принт-сервера.
     */
    private static void testUpnpDescription() {
        String xml = "<root><device><serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1"
                + "</serviceType><controlURL>/wrong</controlURL></service>"
                + "<service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1"
                + "</serviceType><controlURL>/ctl/IPConn</controlURL></service>"
                + "</serviceList></device></root>";
        assertEq("свой адрес управления", "/ctl/IPConn",
                UpnpGateway.controlUrl(xml, "urn:schemas-upnp-org:service:WANIPConnection:1"));
        assertEq("чужой службы нет", "",
                UpnpGateway.controlUrl(xml, "urn:schemas-upnp-org:service:WANPPPConnection:1"));
        assertEq("относительный путь достроен", "http://192.168.1.1:5000/ctl/IPConn",
                UpnpGateway.absolute("http://192.168.1.1:5000/rootDesc.xml", "/ctl/IPConn"));
        assertEq("полный остаётся собой", "http://10.0.0.1/ctl",
                UpnpGateway.absolute("http://192.168.1.1:5000/rootDesc.xml", "http://10.0.0.1/ctl"));
        assertEq("заголовок без учёта регистра", "http://192.168.1.1:5000/rootDesc.xml",
                UpnpGateway.header("HTTP/1.1 200 OK\r\nlocation: "
                        + "http://192.168.1.1:5000/rootDesc.xml\r\n", "LOCATION"));
        assertTrue("занятый порт назван",
                UpnpGateway.errorOf("<errorCode>718</errorCode>").contains("занят"));
    }

    /** Не вышло ни так, ни так — игрок должен увидеть обе причины. */
    private static void testPortMapperErrors() {
        String both = PortMapper.combine("роутер не отозвался на UPnP", "шлюз не ответил");
        assertTrue("UPnP назван: " + both, both.contains("UPnP"));
        assertTrue("NAT-PMP назван: " + both, both.contains("NAT-PMP"));
        assertEq("одна и та же причина не повторяется дважды", "шлюз не ответил",
                PortMapper.combine("шлюз не ответил", "шлюз не ответил"));
        assertEq("пустая сторона молчит", "роутер отказал",
                PortMapper.combine("", "роутер отказал"));
        assertTrue("без причин всё равно понятно",
                !PortMapper.combine("", "").isEmpty());
    }

    /**
     * Маяк объявляет мир, а чужое объявление в список не попадает.
     *
     * <p>Адрес в объявление не пишется нарочно: приёмник и так знает, откуда
     * пакет, а записанный разошёлся бы с настоящим у всякого, у кого две
     * сетевые карты.
     */
    private static void testLanBeacon() {
        byte[] announcement = LanBeacon.encode(25566, "Долина", "Гриша", 2, 8);
        LanBeacon.Announcement a =
                LanBeacon.decode(announcement, announcement.length, "192.168.1.5");
        assertTrue("объявление разобрано", a != null);
        assertEq("порт", 25566, a.port());
        assertEq("мир", "Долина", a.world());
        assertEq("хозяин", "Гриша", a.host());
        assertEq("внутри", 2, a.players());
        assertEq("вмещает", 8, a.maxPlayers());
        assertEq("адрес от приёмника", "192.168.1.5:25566", a.dialable());

        // Сборка с другим протоколом не должна попадать в список: ткнуть в неё
        // значит получить отказ при входе.
        byte[] alien = announcement.clone();
        alien[7] = (byte) (alien[7] + 1);
        assertTrue("чужая версия отброшена",
                LanBeacon.decode(alien, alien.length, "192.168.1.5") == null);
        assertTrue("мусор отброшен",
                LanBeacon.decode(new byte[] { 1, 2, 3 }, 3, "192.168.1.5") == null);
        assertTrue("обрезанное отброшено",
                LanBeacon.decode(announcement, 9, "192.168.1.5") == null);
    }

    // ------------------------------------------------------------- проверки

    static void assertTrue(String what, boolean cond) {
        if (!cond)
            throw new AssertionError("expected true: " + what);
    }

    static void assertEq(String what, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
}
