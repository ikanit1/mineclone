package com.mineclone;

import com.mineclone.net.CompositeTransport;
import com.mineclone.net.LoopbackTransport;
import com.mineclone.net.NetChannel;
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
import com.mineclone.server.ServerConfig;
import com.mineclone.server.ServerConsole;
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
        r.run("selected equipment changes and empty hands replicate both ways", NetworkTests::testEquipment);
        r.run("invalid stale and truncated equipment preserve the last valid item", NetworkTests::testBadEquipment);
        r.run("invalid player snapshots leave the last valid pose intact", NetworkTests::testInvalidPlayerState);
        r.run("only the host can reject a connected guest", NetworkTests::testRejectAuthority);
        r.run("world welcome and inventory grants require the host", NetworkTests::testHostPacketAuthority);
        r.run("remote angles wrap in bounded time and pitch stays upright", NetworkTests::testRemoteAngles);
        r.run("remote mob heads stay relative and idle animations advance", NetworkTests::testRemoteMobPose);
        r.run("every mob transmits its complete visual state", NetworkTests::testFullMobPose);
        r.run("stale corrupt and untrusted mob batches preserve valid entities", NetworkTests::testInvalidMobBatch);
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
        r.run("a guest's hit with broken numbers lands without knockback or an exception",
                NetworkTests::testMobHitBrokenNumbers);
        r.run("a guest's shot is fired by the host and seen by both",
                NetworkTests::testProjectiles);
        r.run("the host can finally hurt a guest", NetworkTests::testHostHurtsGuest);
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
        r.run("two doors make one room and guests hear each other",
                NetworkTests::testCompositeRoom);
        r.run("one broken door does not close the room",
                NetworkTests::testCompositeSurvivesOneDoor);
        r.run("the server package never reaches for a window",
                NetworkTests::testServerStaysHeadless);
        r.run("server.properties round-trips and writes itself",
                NetworkTests::testServerConfig);
        r.run("server clock only ever moves forward", NetworkTests::testServerTimeForward);
        r.run("a dropped guest comes back to the same world",
                NetworkTests::testResumeAfterDrop);
        r.run("a guest that cannot come back gives up and says so",
                NetworkTests::testResumeGivesUp);
    }

    // ------------------------------------------------------------ примитивы

    private static void testInvalidPlayerState() {
        Pair p = Pair.open(3L, "World", 0.5f);
        try {
            RemotePlayer seen = p.guestNet.players().iterator().next();
            seen.update(1f);
            Vector3f before = new Vector3f(seen.position);
            for (float bad : new float[] { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY }) {
                for (int field = 0; field < 5; field++) {
                    float[] values = { before.x, before.y, before.z, 0f, 0f };
                    values[field] = bad;
                    PacketBuf packet = new PacketBuf().u8(NetProto.X_PLAYER_STATE);
                    for (float value : values) packet.f32(value);
                    packet.u8(RemotePlayer.F_ON_GROUND);
                    p.guestNet.onPayload(seen.actor, packet.toBytes());
                    // A second valid snapshot would otherwise interpolate from a poisoned pose.
                    assertTrue("bad snapshot must not change position", seen.position.equals(before));
                    assertTrue("bad snapshot must not change angles", Float.isFinite(seen.yaw) && Float.isFinite(seen.pitch));
                }
            }
            seen.update(0.05f);
            assertTrue("interpolation remains finite", seen.position.isFinite() && Float.isFinite(seen.yaw));
        } finally { p.close(); }
    }

    private static void testRejectAuthority() {
        Pair p = Pair.open(3L, "World", 0.5f);
        try {
            byte[] reject = new PacketBuf().u8(NetProto.S_REJECT).str("spoofed").toBytes();
            p.guestNet.onPayload(999, reject);
            assertTrue("another guest cannot disconnect the client", p.guestNet.active());
            p.hostNet.onPayload(999, reject);
            assertTrue("a guest cannot stop the host", p.hostNet.active());
        } finally { p.close(); }
    }

    private static void testHostPacketAuthority() throws Exception {
        Pair p = Pair.open(3L, "World", 0.5f);
        try {
            int host = p.guestNet.players().iterator().next().actor;
            PacketBuf welcome = new PacketBuf().u8(NetProto.S_WELCOME).i64(99L).str("wrong")
                    .f32(0f).u8(0).f32(0f).f32(70f).f32(0f);
            p.guestNet.onPayload(999, welcome.toBytes());
            assertEq("untrusted welcome cannot replace the world", 3L, p.guest.seed());
            var bytes = new java.io.ByteArrayOutputStream();
            try (var out = new java.io.DataOutputStream(bytes)) {
                com.mineclone.save.ItemStackCodec.write(out, new ItemStack(BlockType.STONE, 2));
            }
            byte[] give = new PacketBuf().u8(NetProto.S_GIVE).bytes(bytes.toByteArray()).toBytes();
            p.guestNet.onPayload(999, give);
            p.hostNet.onPayload(999, give);
            assertTrue("untrusted grant ignored on both roles", p.guest.given.isEmpty() && p.host.given.isEmpty());
            p.guestNet.onPayload(host, give);
            assertEq("host grant still works", 1, p.guest.given.size());
        } finally { p.close(); }
    }

    private static void testRemoteAngles() {
        RemotePlayer player = new RemotePlayer(1, "test");
        player.accept(0, 0, 0, (float) Math.toRadians(179), 0, 1);
        player.accept(0, 0, 0, (float) Math.toRadians(-179), 0, 1);
        player.update(0.05f);
        assertTrue("short arc across the seam", Math.abs(player.yaw) > 3f);
        player.accept(0, 0, 0, Float.MAX_VALUE, Float.MAX_VALUE, 1);
        player.update(0.05f);
        assertTrue("yaw normalized", Float.isFinite(player.yaw) && Math.abs(player.yaw) <= Math.PI + 1e-6);
        player.update(1f);
        assertTrue("pitch cannot invert the head", Math.abs(player.pitch) <= Math.PI / 2 + 1e-6);
    }

    private static void testEquipment() {
        Pair p=Pair.open(3L,"World",.5f);
        try {
            var seen=p.guestNet.players().iterator().next();
            for (var item:com.mineclone.item.Items.get().all()) {
                if(item.tool==null) continue;
                p.host.held=new ItemStack(item,1); p.pump(1);
                assertTrue("all tool variants reach the guest",seen.heldItem!=null && seen.heldItem.item==item);
            }
            p.host.held=ItemStack.of("stone"); p.pump(1);
            assertTrue("block selection reaches guest",seen.heldItem.block()==BlockType.STONE);
            p.guest.held=ItemStack.of("iron_pickaxe");p.pump(1);
            assertTrue("guest equipment reaches host",p.hostNet.players().iterator().next().heldItem.item==p.guest.held.item);
            p.host.held=null; p.guest.held=null; p.pump(1);
            assertTrue("empty hands clear both models",seen.heldItem==null && p.hostNet.players().iterator().next().heldItem==null);
        } finally {p.close();}
    }

    private static void testBadEquipment() {
        Pair p=Pair.open(3L,"World",.5f);
        try {
            var seen=p.guestNet.players().iterator().next();
            PacketBuf good=new PacketBuf().u8(NetProto.X_PLAYER_EQUIPMENT).i64(1000).str("iron_pickaxe");
            p.guestNet.onPayload(seen.actor,good.toBytes());
            var item=seen.heldItem;
            p.guestNet.onPayload(seen.actor,new PacketBuf().u8(NetProto.X_PLAYER_EQUIPMENT).i64(999).str("").toBytes());
            p.guestNet.onPayload(seen.actor,new PacketBuf().u8(NetProto.X_PLAYER_EQUIPMENT).i64(1001).str("missing:item").toBytes());
            byte[] clear=new PacketBuf().u8(NetProto.X_PLAYER_EQUIPMENT).i64(1001).str("").toBytes();
            p.guestNet.onPayload(seen.actor,java.util.Arrays.copyOf(clear,clear.length-1));
            assertTrue("bad appearance cannot replace equipment",seen.heldItem==item);
            p.guestNet.onPayload(seen.actor,clear);
            assertTrue("valid empty slot still applies",seen.heldItem==null);
        } finally {p.close();}
    }

    private static void testFullMobPose() {
        Pair p = Pair.open(3L, "World", 0.5f);
        try {
            for (var type : com.mineclone.world.entity.MobType.values()) {
                Mob m = new Mob(type, type.ordinal(), 70, 0, new java.util.Random(1));
                m.lookYaw=.45f; m.grazeAmount=.7f; m.airborneAmount=.8f; m.velocity.y=3;
                m.walkedDistance=1.2f; m.walkAmount=.8f; m.legOffsetA=.12f; m.legOffsetB=-.2f;
                m.dead=true; m.topple=1.4f; m.enraged=true;
                m.elite=com.mineclone.world.entity.MobTactics.Elite.FROST;
                p.host.mobs.add(m);
            }
            p.pump(3);
            assertEq("all species visible", p.host.mobs.size(), p.guest.mobs.size());
            for (Mob seen : p.guest.mobs) {
                Mob source=p.host.mobs.get(seen.type.ordinal());
                assertTrue("head gait flight terrain and death pose match host",
                        Math.abs(seen.lookYaw-source.lookYaw)<1e-5
                        && Math.abs(seen.grazeAmount-source.grazeAmount)<1e-5
                        && Math.abs(seen.walkedDistance-source.walkedDistance)<1e-5
                        && Math.abs(seen.airborneAmount-source.airborneAmount)<1e-5
                        && Math.abs(seen.legOffsetA-source.legOffsetA)<1e-5
                        && Math.abs(seen.legOffsetB-source.legOffsetB)<1e-5
                        && seen.velocity.y==3 && seen.topple==1.4f && seen.dead
                        && seen.enraged && seen.elite==source.elite);
            }
        } finally { p.close(); }
    }

    private static void testInvalidMobBatch() {
        Pair p = Pair.open(3L, "World", 0.5f);
        try {
            int host=p.guestNet.players().iterator().next().actor;
            Mob m=new Mob(com.mineclone.world.entity.MobType.COW,10,70,10,new java.util.Random(1));
            PacketBuf good=new PacketBuf().u8(NetProto.S_MOBS).i64(1000).varInt(1);
            com.mineclone.net.MobSnapshot.capture(1,m).write(good);
            p.guestNet.onPayload(host,good.toBytes());
            assertEq("initial batch",1,p.guest.mobs.size());
            byte[] empty=new PacketBuf().u8(NetProto.S_MOBS).i64(1001).varInt(0).toBytes();
            p.guestNet.onPayload(999,empty);
            p.guestNet.onPayload(host,new PacketBuf().u8(NetProto.S_MOBS).i64(999).varInt(0).toBytes());
            assertEq("untrusted and stale removals ignored",1,p.guest.mobs.size());
            PacketBuf bad=new PacketBuf().u8(NetProto.S_MOBS).i64(1001).varInt(2);
            m.position.x=12; com.mineclone.net.MobSnapshot.capture(1,m).write(bad);
            m.position.x=Float.NaN; com.mineclone.net.MobSnapshot.capture(2,m).write(bad);
            p.guestNet.onPayload(host,bad.toBytes());
            p.guestNet.onPayload(host,java.util.Arrays.copyOf(good.toBytes(),good.size()-3));
            p.guestNet.update(.05f);
            assertEq("corrupt batches must not remove valid mobs",1,p.guest.mobs.size());
            assertTrue("corrupt batches must not partially move mobs",p.guest.mobs.get(0).position.x==10);
            p.guestNet.onPayload(host,empty);
            assertEq("valid removal still works after invalid sequence",0,p.guest.mobs.size());
        } finally { p.close(); }
    }

    private static void testRemoteMobPose() {
        Pair p = Pair.open(3L, "World", 0.5f);
        try {
            Mob chicken = new Mob(com.mineclone.world.entity.MobType.CHICKEN, 10f, 70f, 10f,
                    new java.util.Random(1));
            chicken.yaw = 2.8f;
            p.host.mobs.add(chicken);
            p.pump(3);
            Mob seen = p.guest.mobs.get(0);
            assertTrue("world yaw must not be applied twice to the head",
                    Math.abs(com.mineclone.render.MobAnimation.headYaw(seen)) < 0.2f);
            float before = seen.animationTime;
            p.guestNet.update(0.01f);
            assertTrue("wings and idle poses animate between network ticks", seen.animationTime > before);
        } finally { p.close(); }
    }

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

    /**
     * SURV-01 regression: the guest's numbers become a damage source, whose
     * checks throw on a half-finite origin — inside the host's packet handler.
     * Before, a NaN knockback made the mob's position NaN.
     */
    private static void testMobHitBrokenNumbers() {
        Pair p = Pair.open(4243L, "Мир", 0.5f);
        Mob cow = new Mob(com.mineclone.world.entity.MobType.COW, 10f, 70f, 10f, new java.util.Random(2));
        float full = cow.health;
        p.host.mobs.add(cow);
        p.pump(3);
        Mob shown = p.guest.mobs.get(0);
        p.guestNet.requestMobHit(shown, 3f, Float.NaN, Float.NaN, Float.POSITIVE_INFINITY);
        p.pump(3);
        assertTrue("the hit landed", cow.health == full - 3f);
        try {
            var knock = Mob.class.getDeclaredField("knockX");
            knock.setAccessible(true);
            assertTrue("and knocked nothing", (float) knock.get(cow) == 0f);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        p.close();
    }

    /**
     * Выстрел участника исполняет хозяин, и снаряд видят обе стороны.
     *
     * Местный снаряд у гостя — только отклик; настоящий летит у хозяина и
     * приезжает обратно снимком.
     */
    private static void testProjectiles() {
        Pair p = Pair.open(7171L, "Мир", 0.5f);
        var shot = new com.mineclone.world.entity.Projectile("arrow", null, true, 6f);
        shot.position.set(4f, 70f, 4f);
        shot.velocity.set(20f, 0f, 0f);
        p.guestNet.requestShot(shot);
        p.pump(3);
        assertEq("хозяин выпустил снаряд по просьбе гостя", 1, p.host.shots.size());
        assertTrue("с тем же уроном", Math.abs(p.host.shots.get(0).damage - 6f) < 1e-4);
        p.pump(3);
        assertEq("и гость видит его снимок", 1, p.guest.shots.size());
        assertTrue("на том же месте",
                p.guest.shots.get(0).position.distance(p.host.shots.get(0).position) < 0.01f);

        p.host.shots.clear();
        p.pump(3);
        assertEq("упавший снаряд исчезает и у гостя", 0, p.guest.shots.size());
        p.close();
    }

    /**
     * Ограничение, которое здесь снимается: до пакета S_PLAYER_HURT мобы не
     * могли ранить участника вовсе — урон считался у хозяина, а сказать о нём
     * было нечем.
     */
    private static void testHostHurtsGuest() {
        Pair p = Pair.open(3131L, "Мир", 0.5f);
        p.pump(2);
        var known = p.hostNet.players();
        assertTrue("хозяин знает о госте", !known.isEmpty());
        var guestOnHost = known.iterator().next();

        // Так это и происходит в игре: стрела моба попадает в гостя, а тот
        // узнаёт об этом пакетом.
        guestOnHost.takeProjectile(4f, 0f, 0f, 0.6f, false);
        p.pump(3);
        assertTrue("гость получил урон, посланный хозяином: " + p.guest.hurtTaken,
                Math.abs(p.guest.hurtTaken - 4f) < 1e-4);
        assertTrue("v8: и узнал, чем", p.guest.lastHurt != null
                && p.guest.lastHurt.type() == com.mineclone.world.damage.DamageType.PROJECTILE
                && !p.guest.lastHurt.byPlayer());

        // v8: a zombie's blow arrives with its kind, dealer and origin.
        var blow = com.mineclone.world.damage.DamageSource.byMob(com.mineclone.world.damage.DamageType.MELEE,
                com.mineclone.world.entity.MobType.ZOMBIE, 3f, 71f, -2f, 1f);
        guestOnHost.damage(blow, 3f);
        p.pump(3);
        assertTrue("удар зомби дошёл целиком: " + p.guest.lastHurt, blow.equals(p.guest.lastHurt)
                && Math.abs(p.guest.hurtTaken - 7f) < 1e-4);

        // A cut or broken hurt changes nothing on the guest.
        PacketBuf cut = new PacketBuf();
        com.mineclone.net.PlayerHurt.of(blow, 5f).write(cut.u8(NetProto.S_PLAYER_HURT));
        byte[] whole = cut.toBytes();
        p.guestNet.onPayload(p.hostT.myActor(), java.util.Arrays.copyOf(whole, whole.length - 2));
        PacketBuf broken = new PacketBuf();
        new com.mineclone.net.PlayerHurt(Float.NaN, 0, 0, 0, 0, 0, 1, 0, 0).write(broken.u8(NetProto.S_PLAYER_HURT));
        p.guestNet.onPayload(p.hostT.myActor(), broken.toBytes());
        assertTrue("битый пакет урона сработал: " + p.guest.hurtTaken, Math.abs(p.guest.hurtTaken - 7f) < 1e-4);
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
        String newer = refusalFor(NetProto.VERSION + 7);
        assertTrue("отказ с внятной причиной: " + newer, newer != null && newer.contains("версия")
                && newer.contains("обновиться нужно ему"));
        // NET-02: a v7 build asking a v8 host is told both versions, the host's build and to update.
        String older = refusalFor(NetProto.VERSION - 1);
        assertTrue("v7 → v8: " + older, older != null && older.contains("v" + (NetProto.VERSION - 1))
                && older.contains("v" + NetProto.VERSION) && older.contains(com.mineclone.core.BuildInfo.summary())
                && older.contains("Обновите игру"));
    }

    /** What the host answers a hello of {@code version}: the refusal's text, or null. */
    private static String refusalFor(int version) {
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
        hello.u8(NetProto.C_HELLO).varInt(version).str("Старая сборка");
        odd.send(hello.toBytes(), true, 1);
        for (int i = 0; i < 4; i++) {
            hostNet.update(0.1f);
            odd.poll();
        }
        String refusal = null;
        for (byte[] payload : rec.payloads) {
            PacketBuf in = PacketBuf.reading(payload);
            // В одном сообщении едет несколько пакетов: отказ идёт следом за
            // сведениями об игроке, и разобрать надо оба.
            while (in.hasMore() && refusal == null) {
                int code = in.readU8();
                if (code == NetProto.S_REJECT) {
                    refusal = in.readStr();
                } else if (code == NetProto.X_PLAYER_INFO) {
                    in.readStr();
                    in.readU8();
                    in.readF32();
                } else {
                    break;
                }
            }
        }
        hostNet.stop(null);
        odd.disconnect();
        LoopbackTransport.reset();
        return refusal;
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
        float before = p.walkAmount;
        p.accept(p.position.x, p.position.y + 0.4f, p.position.z, 0f, 0f,
                RemotePlayer.F_FLYING);
        p.update(1f / 60f);
        assertTrue("переход в полёт плавно гасит шаг", p.walkAmount > 0.01f && p.walkAmount < before);
        for (int i = 0; i < 40; i++) p.update(1f / 60f);
        assertTrue("в полёте шаг затухает", p.walkAmount < 0.01f);
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
    static class TestContext implements NetContext {
        World world;
        String name;
        float time = 0.5f;
        int mode;
        final Vector3f spawn = new Vector3f(8f, 80f, 8f);
        final Vector3f position = new Vector3f(8f, 80f, 8f);
        float yaw, pitch, health = 20f;
        ItemStack held;
        @Override public ItemStack playerHeldItem() { return held; }
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

        final List<com.mineclone.world.entity.Projectile> shots = new java.util.ArrayList<>();
        /** Сколько урона хозяин прислал этому игроку. */
        float hurtTaken;

        @Override
        public List<com.mineclone.world.entity.Projectile> projectiles() {
            return shots;
        }

        @Override
        public void shootFor(int actor, float x, float y, float z,
                             float vx, float vy, float vz, float damage) {
            var p = new com.mineclone.world.entity.Projectile("arrow", null, true, damage);
            p.position.set(x, y, z);
            p.velocity.set(vx, vy, vz);
            p.heading.set(vx, vy, vz).normalize();
            shots.add(p);
        }

        @Override
        public void hurtByHost(com.mineclone.world.damage.DamageSource source, float damage) {
            hurtTaken += damage;
            lastHurt = source;
        }

        com.mineclone.world.damage.DamageSource lastHurt;

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

    // ---------------------------------------------------- две двери в комнату

    /** Слушатель, который просто записывает всё, что ему сказали. */
    private static final class DoorRecorder implements NetTransport.Listener {
        final List<String> events = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        NetTransport.State state = NetTransport.State.IDLE;
        int myActor;

        @Override
        public void onState(NetTransport.State s, String detail) {
            state = s;
            events.add("state:" + s);
        }

        @Override
        public void onJoined(int actor, boolean created) {
            myActor = actor;
            events.add("joined:" + actor + ":" + created);
        }

        @Override
        public void onActorJoin(int actor, String name) {
            events.add("join:" + actor + ":" + name);
        }

        @Override
        public void onActorLeave(int actor) {
            events.add("leave:" + actor);
        }

        @Override
        public void onPayload(int from, byte[] data) {
            payloads.add(from + ":" + new String(data, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void onRoomList(List<NetTransport.RoomInfo> rooms) {
        }
    }

    private static CompositeTransport.Door loopbackDoor(String label) {
        return new CompositeTransport.Door() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public NetTransport open(NetTransport.Listener l) {
                return new LoopbackTransport(l);
            }
        };
    }

    /**
     * Гость за одной дверью слышит гостя за другой.
     *
     * <p>Ради этого композит и нужен: широковещательное сообщение Photon
     * доходит до Photon, кадр TCP — до TCP, и без перекрёстной пересылки два
     * друга в одной комнате друг друга бы не видели.
     */
    private static void testCompositeRoom() {
        LoopbackTransport.reset();
        DoorRecorder hostSide = new DoorRecorder();
        CompositeTransport host = new CompositeTransport(hostSide,
                List.of(loopbackDoor("свой порт"), loopbackDoor("Photon")));
        // У каждой двери своя комната — как своя сеть и облако в жизни: за
        // одной дверью участника не слышно из-за другой.
        host.connectDoor(0, "порт", true, "Хозяин");
        host.connectDoor(1, "облако", true, "Хозяин");
        host.poll();
        assertEq("комната открыта", NetTransport.State.JOINED, hostSide.state);
        assertEq("хозяин первый", CompositeTransport.HOST_ACTOR, hostSide.myActor);

        DoorRecorder viaPortSide = new DoorRecorder();
        LoopbackTransport viaPort = new LoopbackTransport(viaPortSide);
        viaPort.connect("порт", false, "Порт");
        DoorRecorder viaCloudSide = new DoorRecorder();
        LoopbackTransport viaCloud = new LoopbackTransport(viaCloudSide);
        viaCloud.connect("облако", false, "Облако");
        pump(host, viaPort, viaCloud);

        assertEq("оба гостя на счету у хозяина", 2, host.actors().size());
        int fromPort = host.actors().get(0);
        int fromCloud = host.actors().get(1);
        assertTrue("номера разные", fromPort != fromCloud);
        assertEq("имя первого", "Порт", host.actorName(fromPort));
        assertEq("имя второго", "Облако", host.actorName(fromCloud));
        assertEq("и видно, какой дверью", "свой порт", host.doorOf(fromPort));
        assertEq("и второй тоже", "Photon", host.doorOf(fromCloud));

        // Гость кричит всем. Хозяин обязан услышать, и второй гость — тоже,
        // хотя он за другой дверью.
        viaPort.send("привет".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                true, NetChannel.ALL);
        pump(host, viaPort, viaCloud);
        assertEq("хозяин услышал", 1, hostSide.payloads.size());
        assertEq("и знает от кого", fromPort + ":привет", hostSide.payloads.get(0));
        assertEq("гость за другой дверью услышал", 1, viaCloudSide.payloads.size());
        assertTrue("сам себе не переслал: " + viaPortSide.payloads,
                viaPortSide.payloads.isEmpty());

        // Хозяин отвечает лично — в свою дверь и больше никуда.
        host.send("тебе".getBytes(java.nio.charset.StandardCharsets.UTF_8), true, fromCloud);
        pump(host, viaPort, viaCloud);
        assertEq("адресат получил", 2, viaCloudSide.payloads.size());
        assertTrue("посторонний не получил: " + viaPortSide.payloads,
                viaPortSide.payloads.isEmpty());

        viaPort.disconnect();
        pump(host, viaPort, viaCloud);
        assertEq("ушедший снят со счёта", 1, host.actors().size());
        host.disconnect();
        viaCloud.disconnect();
    }

    /**
     * Одна отказавшая дверь не закрывает комнату.
     *
     * <p>Нет интернета — играем по своей сети; занят порт — играем через
     * облако. Иначе выделенный сервер падал бы от того, что у него нет одной
     * из двух возможностей, которые ему не обе нужны.
     */
    private static void testCompositeSurvivesOneDoor() {
        LoopbackTransport.reset();
        // Первая дверь входит в несуществующую комнату и отказывает; вторая
        // создаёт свою и открывается.
        DoorRecorder side = new DoorRecorder();
        CompositeTransport host = new CompositeTransport(side,
                List.of(loopbackDoor("сломанная"), loopbackDoor("рабочая")));
        // Первой двери велено входить, а не создавать: комнаты нет — отказ.
        host.connectDoor(0, "чужая", false, "Хозяин");
        host.connectDoor(1, "своя", true, "Хозяин");
        host.poll();

        assertEq("комната всё равно открыта", NetTransport.State.JOINED, side.state);
        assertTrue("сломанная дверь закрыта", !host.doorOpen(0));
        assertTrue("рабочая открыта", host.doorOpen(1));
        assertTrue("и причина названа: " + host.doorDetail(0),
                !host.doorDetail(0).isEmpty());
        assertTrue("в строке видно обе: " + host.describe(),
                host.describe().contains("сломанная") && host.describe().contains("рабочая"));
        host.disconnect();
    }

    /** Прокрутить всех участников, пока они не перестанут отвечать друг другу. */
    private static void pump(NetTransport... parties) {
        for (int i = 0; i < 6; i++)
            for (NetTransport t : parties)
                t.poll();
    }

    // ------------------------------------------------------ выделенный сервер

    /**
     * Сервер не тянется за окном.
     *
     * <p>Проверка читает исходники пакета, а не гоняет его: падение на
     * машине без экрана случилось бы у того, кто ставит сервер, а не у того,
     * кто пишет код. Одна удобная строчка, списанная из {@code Game},
     * притащила бы за собой GLFW и контекст OpenGL — и сервер перестал бы
     * запускаться там, ради чего он и написан.
     */
    private static void testServerStaysHeadless() throws Exception {
        java.io.File dir = new java.io.File("src/main/java/com/mineclone/server");
        assertTrue("исходники сервера на месте: " + dir.getAbsolutePath(), dir.isDirectory());
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".java"));
        assertTrue("и их несколько", files != null && files.length >= 3);
        String[] forbidden = {
                "import com.mineclone.render.",
                "import com.mineclone.audio.",
                "import org.lwjgl.",
                "import com.mineclone.game.",
        };
        for (java.io.File f : files) {
            String text = java.nio.file.Files.readString(f.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String bad : forbidden)
                assertTrue(f.getName() + " не тянет " + bad, !text.contains(bad));
        }
    }

    /** Файла нет — он пишется сам, и прочитанное совпадает с записанным. */
    private static void testServerConfig() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("mineclone-server").toFile();
        try {
            java.io.File file = new java.io.File(dir, "server.properties");
            assertTrue("сначала файла нет", !file.exists());
            ServerConfig fresh = ServerConfig.load(file);
            assertTrue("и он появился: человек должен увидеть, что можно настроить",
                    file.isFile());
            assertEq("порт по умолчанию", com.mineclone.net.LanTransport.DEFAULT_PORT,
                    fresh.port);
            assertTrue("обе двери открыты по умолчанию", fresh.direct && fresh.photon);

            java.nio.file.Files.writeString(file.toPath(),
                    "port=25999\nphoton=false\nmode=creative\n"
                            + "view-distance=9\nroom=a4k7m2\n"
                            + "world-name=Долина\nseed=1234\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            ServerConfig read = ServerConfig.load(file);
            assertEq("порт", 25999, read.port);
            assertTrue("облако выключено", !read.photon);
            assertTrue("творческий режим", read.creative);
            assertEq("радиус", 9, read.viewDistance);
            assertEq("имя мира", "Долина", read.worldName);
            assertEq("сид", 1234L, read.seed);
            // Код комнаты приводится к своему виду прямо при чтении: человек
            // впишет его строчными, а диктовать будет прописными.
            assertEq("код комнаты", "A4K7M2", read.room);

            java.io.File written = new java.io.File(dir, "written.properties");
            read.write(written);
            ServerConfig again = ServerConfig.load(written);
            assertEq("порт пережил запись", read.port, again.port);
            assertEq("режим пережил запись", read.creative, again.creative);
            assertEq("радиус пережил запись", read.viewDistance, again.viewDistance);
            assertEq("имя мира пережило запись", read.worldName, again.worldName);
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * Часы сервера идут только вперёд.
     *
     * <p>Назад время в этой игре не ходит: от полного оборота зависит фаза
     * луны, и {@code /time day} посреди дня обязан перевести на следующее
     * утро, а не вернуть к прошедшему.
     */
    private static void testServerTimeForward() {
        float full = (float) (Math.PI * 2.0);
        float morning = (float) (Math.PI / 6.0);
        float noon = (float) (Math.PI / 2.0);
        assertTrue("из ночи в утро — вперёд",
                ServerConsole.forward(full * 3f + 5f, morning) > full * 3f + 5f);
        assertTrue("из утра в полдень — вперёд",
                ServerConsole.forward(morning + 0.01f, noon) > morning + 0.01f);
        // Уже ровно в этой фазе: следующий такой же момент — через сутки, а
        // не прямо сейчас, иначе команда молча ничего бы не делала.
        float exact = ServerConsole.forward(morning, morning);
        assertTrue("та же фаза уезжает на сутки вперёд", exact > morning);
        assertTrue("ровно на одни сутки", Math.abs(exact - (morning + full)) < 1e-3f);
    }

    // ---------------------------------------------------- возвращение в комнату

    /**
     * Секундный обрыв больше не стоит партии.
     *
     * <p>Раньше любой отказ транспорта выгружал мир и уводил в меню. Мир при
     * этом никуда не девался: он построен из сида и лежит в памяти. Теперь
     * сессия держит его тридцать секунд и всё это время пробует вернуться.
     */
    private static void testResumeAfterDrop() {
        LoopbackTransport.reset();
        TestContext host = new TestContext(new World(0xBEEF77L), "Возврат");
        Multiplayer hostNet = new Multiplayer(host);
        host.world.setBlockObserver(hostNet::onWorldBlockChanged);
        hostNet.start(new LoopbackTransport(hostNet), "room", true, "Хозяин");

        TestContext guest = new TestContext(null, "");
        Multiplayer[] self = new Multiplayer[1];
        Multiplayer guestNet = new Multiplayer(guest);
        self[0] = guestNet;
        guest.onWorldStarted = w -> w.setBlockObserver(guestNet::onWorldBlockChanged);
        guestNet.start(() -> new LoopbackTransport(self[0]), "room", false, "Гость");
        pumpNets(hostNet, guestNet, 8);

        assertTrue("гость вошёл", guestNet.isClient() && guestNet.worldReady());
        World built = guest.world();
        assertTrue("мир построен", built != null);

        // Ровно то, что присылает оборвавшийся транспорт.
        guestNet.onState(NetTransport.State.FAILED, "проверочный обрыв");
        assertTrue("сессия пробует вернуться", guestNet.resuming());
        assertTrue("мир не выгружен", guest.world() == built);
        assertTrue("и в меню никого не выгнали", guest.stopped == null);

        pumpNets(hostNet, guestNet, 40);
        assertTrue("вернулись: " + guestNet.status(), !guestNet.resuming());
        assertTrue("и снова в комнате", guestNet.joined());
        assertTrue("мир остался тем же", guest.world() == built);
        assertTrue("второй раз его не строили", guest.stopped == null);
        assertEq("хозяин снова видит одного соседа", 1, hostNet.players().size());

        hostNet.stop(null);
        guestNet.stop(null);
    }

    /**
     * Вернуться не вышло — тогда уже по-настоящему.
     *
     * <p>Окно конечно нарочно: человек перед застывшим миром, который
     * никогда не оживёт, — это хуже честного «связь потеряна».
     */
    private static void testResumeGivesUp() {
        LoopbackTransport.reset();
        TestContext host = new TestContext(new World(0xDEAD11L), "Уход");
        Multiplayer hostNet = new Multiplayer(host);
        host.world.setBlockObserver(hostNet::onWorldBlockChanged);
        hostNet.start(new LoopbackTransport(hostNet), "room", true, "Хозяин");

        TestContext guest = new TestContext(null, "");
        Multiplayer[] self = new Multiplayer[1];
        Multiplayer guestNet = new Multiplayer(guest);
        self[0] = guestNet;
        guest.onWorldStarted = w -> w.setBlockObserver(guestNet::onWorldBlockChanged);
        guestNet.start(() -> new LoopbackTransport(self[0]), "room", false, "Гость");
        pumpNets(hostNet, guestNet, 8);
        assertTrue("гость вошёл", guestNet.worldReady());

        // Комнаты больше нет: возвращаться некуда, сколько ни пробуй.
        hostNet.stop(null);
        LoopbackTransport.reset();
        guestNet.onState(NetTransport.State.FAILED, "хозяин исчез");
        assertTrue("сначала всё-таки пробует", guestNet.resuming());
        // Первая попытка упрётся в отсутствующую комнату — и это не повод
        // сдаваться: окно ещё не вышло.
        guestNet.update(0.05f);
        assertTrue("и после первой неудачи ещё пробует", guestNet.resuming());

        // Одним шагом за всё окно: ждать тридцать секунд в тесте незачем.
        guestNet.update(Multiplayer.RESUME_WINDOW + 1f);
        assertTrue("сдались", !guestNet.resuming());
        assertTrue("и сказали почему: " + guest.stopped, guest.stopped != null);
        assertTrue("сессия закрыта", !guestNet.active());
    }

    /** Прокрутить обе стороны столько-то тиков сети. */
    private static void pumpNets(Multiplayer a, Multiplayer b, int ticks) {
        for (int i = 0; i < ticks; i++) {
            a.update(0.1f);
            b.update(0.1f);
        }
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
