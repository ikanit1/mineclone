package com.mineclone;

import com.mineclone.game.LocalParticipant;
import com.mineclone.game.Player;
import com.mineclone.net.*;
import com.mineclone.sim.*;
import com.mineclone.world.*;
import com.mineclone.world.damage.*;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.util.function.Consumer;

/** Participant queries and real loopback membership, independent of graphics. */
public final class ParticipantTests {
    public static void runAll(TestMain.Runner runner) {
        runner.run("nearest hostile target ignores dead and creative players with stable ties", ParticipantTests::nearest);
        runner.run("participant radius queries include the boundary without allocating", ParticipantTests::radius);
        runner.run("remote participants use accepted positions rather than rendered interpolation", ParticipantTests::remotePosition);
        runner.run("local participant follows player state and legacy attack invulnerability", ParticipantTests::local);
        runner.run("loopback membership follows handshake, leave, timeout and reconnect", ParticipantTests::membership);
        runner.run("dedicated authority has no local avatar and clients have no participants", ParticipantTests::authority);
    }

    private static void nearest() {
        Participants all = new Participants();
        Stub creative = new Stub(8, 1), dead = new Stub(7, 2), right = new Stub(5, 4), left = new Stub(3, -4);
        creative.mode = GameMode.CREATIVE; dead.alive = false;
        all.put(creative); all.put(dead); all.put(right); all.put(left);
        check(all.nearest(0, 0, 0, Participants.HOSTILE_TARGETS) == left, "wrong hostile target/tie");
        all.remove(left.id()); all.put(left);
        check(all.nearest(0, 0, 0, Participants.HOSTILE_TARGETS) == left, "insertion order changed tie");
        check(all.nearest(0, 0, 0, p -> false) == null, "empty predicate");
        left.position.x = 6;
        check(all.nearest(0, 0, 0, Participants.HOSTILE_TARGETS) == right, "positions are not live");
        all.put(right); check(all.size() == 4, "duplicate participant");
    }

    private static void radius() {
        Participants all = new Participants(); all.put(new Stub(1, 5)); all.put(new Stub(2, 5.01f));
        int[] hits = {0}; Consumer<Participant> count = p -> hits[0]++;
        check(all.forEachWithin(0, 0, 0, 5, Participants.HOSTILE_TARGETS, count) == 1 && hits[0] == 1,
                "radius boundary");
        boolean bad = false;
        try { all.forEachWithin(0, 0, 0, Float.NaN, Participants.HOSTILE_TARGETS, count); }
        catch (IllegalArgumentException expected) { bad = true; }
        check(bad, "NaN radius accepted");
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean allocation && allocation.isThreadAllocatedMemorySupported()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
            for (int i = 0; i < 20_000; i++) query(all, count);
            long thread = Thread.currentThread().getId(), before = allocation.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 20_000; i++) query(all, count);
            long bytes = allocation.getThreadAllocatedBytes(thread) - before;
            check(bytes < 4096, "queries allocated " + bytes + " bytes");
        }
    }

    private static void query(Participants all, Consumer<Participant> action) {
        all.nearest(0, 0, 0, Participants.HOSTILE_TARGETS);
        all.forEachWithin(0, 0, 0, 5, Participants.HOSTILE_TARGETS, action);
    }

    private static void remotePosition() {
        RemotePlayer player = new RemotePlayer(4, "guest"); player.gameMode = GameMode.SURVIVAL.ordinal();
        player.accept(0, 20, 0, 0, 0, 0); player.update(1);
        player.accept(4, 20, 0, 0, 0, 0);
        RemoteParticipant participant = new RemoteParticipant(player);
        check(player.position.x == 0 && participant.position().x() == 4, "simulation used interpolation");
        Vector3fc eye = participant.eye();
        check(eye.y() == 20 + Player.EYE_HEIGHT && eye == participant.eye(), "eye allocation/height");
        player.flags |= RemotePlayer.F_DEAD;
        check(!participant.alive(), "dead flag ignored");
        player.flags = 0; player.health = Float.NaN;
        check(!participant.alive(), "invalid health accepted");
    }

    private static void local() {
        Player player = new Player(); Inventory inventory = new Inventory();
        LocalParticipant participant = new LocalParticipant(player, () -> inventory, () -> 1,
                () -> player.isCreative() ? GameMode.CREATIVE : GameMode.SURVIVAL, () -> true);
        player.position.set(2, 3, 4);
        check(participant.position() == player.position && participant.eye().y() == 3 + Player.EYE_HEIGHT, "local pose");
        check(participant.local() && participant.active() && participant.armor() == ArmorView.NONE, "local adapter");
        DamageSource melee = DamageSource.of(DamageType.MELEE);
        check(participant.damage(melee, 3) && player.health == 17, "local attack");
        check(!participant.damage(melee, 3) && player.health == 17, "attack invulnerability bypassed");
        player.setGameMode(GameMode.CREATIVE);
        check(!participant.damage(melee, 3), "creative damage");
    }

    private static void membership() {
        try (Room room = new Room(true)) {
            Participants live = room.host.participants(); room.pump(8);
            check(live.size() == 2, "host local + accepted guest missing");
            int actor = room.guestTransport.myActor();
            Participant guest = live.getById(actor); check(guest != null && !guest.local(), "guest identity");
            float health = room.guestContext.health;
            check(guest.damage(DamageSource.of(DamageType.MELEE), 2), "remote damage not forwarded");
            room.pump(3); check(room.guestContext.health == health - 2, "guest did not receive damage");
            room.host.onActorJoin(999, "unidentified");
            room.host.onPayload(999, new PacketBuf().u8(NetProto.X_PLAYER_STATE).f32(0).f32(80).f32(0)
                    .f32(0).f32(0).u8(0).toBytes());
            check(live.size() == 2, "unidentified placeholder entered simulation");
            room.guest.stop(null); room.host.update(.1f);
            check(live.size() == 1, "leave did not remove guest");
            room.rejoin(); room.pump(8); check(live.size() == 2, "reconnect did not restore guest");
            actor = room.guestTransport.myActor();
            room.host.update(13f); check(live.size() == 1, "silent guest was not removed");
            room.host.onPayload(actor, new PacketBuf().u8(NetProto.X_PLAYER_STATE).f32(0).f32(80).f32(0)
                    .f32(0).f32(0).u8(0).toBytes());
            check(live.size() == 1, "timed-out identity revived without handshake");
            room.host.stop(null); check(live.size() == 1, "offline local participant disappeared");
        }
    }

    private static void authority() {
        try (Room room = new Room(false)) {
            room.pump(8);
            check(room.host.participants().size() == 1, "dedicated authority spawned a local avatar");
            check(room.guest.participants().size() == 0, "client acquired simulation participants");
        }
    }

    private static final class Room implements AutoCloseable {
        final NetworkTests.TestContext hostContext = new NetworkTests.TestContext(new World(73), "participants");
        final NetworkTests.TestContext guestContext = new NetworkTests.TestContext(null, "guest");
        final Multiplayer host = new Multiplayer(hostContext), guest = new Multiplayer(guestContext);
        LoopbackTransport guestTransport;
        Room(boolean localHost) {
            LoopbackTransport.reset(); hostContext.mode = guestContext.mode = GameMode.SURVIVAL.ordinal();
            if (localHost) host.setLocalParticipant(new Stub(1, 0));
            guest.setLocalParticipant(new Stub(2, 0));
            host.start(new LoopbackTransport(host), "participants", true, "host");
            rejoin();
        }
        void rejoin() {
            guestTransport = new LoopbackTransport(guest);
            guest.start(guestTransport, "participants", false, "guest");
        }
        void pump(int n) { for (int i = 0; i < n; i++) { host.update(.1f); guest.update(.1f); } }
        public void close() { guest.stop(null); host.stop(null); LoopbackTransport.reset(); }
    }

    private static final class Stub implements Participant {
        final int id; final Vector3f position = new Vector3f();
        boolean alive = true; GameMode mode = GameMode.SURVIVAL;
        Stub(int id, float x) { this.id = id; position.x = x; }
        public int id() { return id; }
        public Vector3fc position() { return position; }
        public Vector3fc eye() { return position; }
        public GameMode mode() { return mode; }
        public boolean alive() { return alive; }
        public ArmorView armor() { return ArmorView.NONE; }
        public boolean damage(DamageSource source, float amount) { return false; }
        public boolean local() { return true; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
