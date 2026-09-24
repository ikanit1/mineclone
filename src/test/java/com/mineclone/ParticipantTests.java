package com.mineclone;

import com.mineclone.game.LocalParticipant;
import com.mineclone.game.Player;
import com.mineclone.net.*;
import com.mineclone.sim.*;
import com.mineclone.world.*;
import com.mineclone.world.damage.*;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Participant queries and real loopback membership, independent of graphics. */
public final class ParticipantTests {
    public static void runAll(TestMain.Runner runner) {
        runner.run("nearest hostile target ignores dead and creative players with stable ties", ParticipantTests::nearest);
        runner.run("participant radius queries include the boundary without allocating", ParticipantTests::radius);
        runner.run("remote participants use accepted positions rather than rendered interpolation", ParticipantTests::remotePosition);
        runner.run("local participant follows player state and the attack invulnerability window", ParticipantTests::local);
        runner.run("damage kinds split armor, invulnerability and fire as specified", ParticipantTests::damageKinds);
        runner.run("loopback membership follows handshake, leave, timeout and reconnect", ParticipantTests::membership);
        runner.run("dedicated authority has no local avatar and clients have no participants", ParticipantTests::authority);
        runner.run("the simulation package never reaches for the client", ParticipantTests::headless);
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
        Stub replacement = new Stub(5, 9);
        all.put(replacement);
        check(all.getById(5) == replacement && all.size() == 4, "same id did not replace");
        check(all.get(0).id() == 3 && all.get(3).id() == 8, "list is not in id order");
        check(all.remove(42) == null && all.remove(3) == left && all.getById(3) == null, "removal");
    }

    private static void radius() {
        Participants all = new Participants(); all.put(new Stub(1, 5)); all.put(new Stub(2, 5.01f));
        int[] hits = {0}; Consumer<Participant> count = p -> hits[0]++;
        check(all.forEachWithin(0, 0, 0, 5, Participants.HOSTILE_TARGETS, count) == 1 && hits[0] == 1,
                "radius boundary");
        for (float bad : new float[] { Float.NaN, -1f, Float.POSITIVE_INFINITY }) {
            boolean rejected = false;
            try { all.forEachWithin(0, 0, 0, bad, Participants.HOSTILE_TARGETS, count); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "radius " + bad + " accepted");
        }
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
        check(participant.id() == 4 && !participant.local() && participant.armor() == ArmorView.NONE, "remote adapter");
        float[] sent = {0};
        player.onHurt = damage -> sent[0] += (float) damage;
        check(participant.damage(DamageSource.of(DamageType.MELEE), 3) && sent[0] == 3, "hit was not forwarded");
        check(!participant.damage(DamageSource.of(DamageType.MELEE), Float.NaN) && sent[0] == 3, "NaN hit forwarded");
        player.gameMode = GameMode.CREATIVE.ordinal();
        check(!participant.damage(DamageSource.of(DamageType.MELEE), 3) && sent[0] == 3, "creative guest hit");
        player.gameMode = 77;
        check(participant.mode() == GameMode.CREATIVE, "unknown mode must not make a guest a target");
        player.gameMode = GameMode.SURVIVAL.ordinal();
        player.flags |= RemotePlayer.F_DEAD;
        check(!participant.alive(), "dead flag ignored");
        player.flags = 0; player.health = Float.NaN;
        check(!participant.alive(), "invalid health accepted");
    }

    private static void local() {
        Player player = new Player();
        int[] actor = {0};
        LocalParticipant participant = new LocalParticipant(player, () -> actor[0]);
        player.position.set(2, 3, 4);
        check(participant.position() == player.position && participant.eye().y() == 3 + Player.EYE_HEIGHT, "local pose");
        check(participant.local() && participant.armor() == ArmorView.NONE && participant.id() == 0, "local adapter");
        actor[0] = 1;
        check(participant.id() == 1, "actor number is not live");
        DamageSource melee = DamageSource.of(DamageType.MELEE);
        check(participant.damage(melee, 3) && player.health == 17, "local attack");
        check(!participant.damage(melee, 3) && player.health == 17, "attack invulnerability bypassed");
        check(participant.damage(DamageSource.of(DamageType.FALL), 2) && player.health == 15,
                "fall damage was swallowed by the attack window");
        check(!participant.damage(melee, -1) && !participant.damage(melee, Float.NaN) && player.health == 15,
                "invalid amounts");
        player.setGameMode(GameMode.CREATIVE);
        check(participant.mode() == GameMode.CREATIVE, "local mode");
        check(!participant.damage(melee, 3) && player.health == Player.MAX_HEALTH, "creative damage");
        // SURV-01: the void is the one thing creative does not survive.
        check(participant.damage(DamageSource.of(DamageType.VOID), 3) && player.health == Player.MAX_HEALTH - 3,
                "the void spared a creative player");
        player.setGameMode(GameMode.SURVIVAL);
        player.health = 0;
        check(!participant.alive() && !participant.damage(DamageSource.of(DamageType.FALL), 1), "dead participant");
    }

    private static void damageKinds() {
        for (DamageType type : new DamageType[] { DamageType.DROWN, DamageType.STARVE, DamageType.POISON })
            check(type.bypassesArmor(), type + " must ignore armor");
        for (DamageType type : new DamageType[] { DamageType.MELEE, DamageType.PROJECTILE, DamageType.EXPLOSION })
            check(!type.bypassesArmor() && !type.bypassesInvulnerability(), type + " is an attack");
        check(DamageType.FALL.bypassesInvulnerability(), "fall must bypass the attack window");
        check(DamageType.FIRE.isFire() && DamageType.LAVA.isFire() && !DamageType.LIGHTNING.isFire(), "fire kinds");
        DamageSource source = DamageSource.of(DamageType.CACTUS);
        check(source.attackerId() == DamageSource.NO_ATTACKER && source.attackerType() == null
                && Float.isNaN(source.originX()) && source.knockback() == 0, "anonymous source");
        for (Runnable bad : new Runnable[] {
                () -> new DamageSource(null, -1, null, Float.NaN, Float.NaN, Float.NaN, 0, null, 0),
                () -> new DamageSource(DamageType.MELEE, -2, null, Float.NaN, Float.NaN, Float.NaN, 0, null,
                        DamageSource.PLAYER),
                () -> new DamageSource(DamageType.MELEE, 3, null, 1, Float.NaN, 2, 0, null, DamageSource.PLAYER),
                () -> new DamageSource(DamageType.MELEE, 3, null, 1, 2, 3, -1, null, DamageSource.PLAYER),
                // A number without the player flag, a player that is a mob, an unknown flag.
                () -> new DamageSource(DamageType.MELEE, 3, null, 1, 2, 3, 1, null, 0),
                () -> new DamageSource(DamageType.MELEE, -1, com.mineclone.world.entity.MobType.ZOMBIE,
                        1, 2, 3, 1, null, DamageSource.PLAYER),
                () -> new DamageSource(DamageType.MELEE, -1, null, 1, 2, 3, 1, null, 4),
                () -> new ArmorView(-1, 0) }) {
            boolean rejected = false;
            try { bad.run(); }
            catch (IllegalArgumentException | NullPointerException expected) { rejected = true; }
            check(rejected, "invalid damage data accepted");
        }
    }

    private static void membership() {
        try (Room room = new Room(true)) {
            Participants live = room.host.participants(); room.pump(8);
            check(live.size() == 2, "host local + accepted guest missing");
            int actor = room.guestTransport.myActor();
            Participant guest = live.getById(actor); check(guest != null && !guest.local(), "guest identity");
            check(live.getById(1) != null && live.getById(1).local(), "host's own player missing");
            check(guest.damage(DamageSource.of(DamageType.MELEE), 2), "remote damage not forwarded");
            room.pump(3); check(room.guestContext.hurtTaken == 2, "guest did not receive damage");
            room.host.onActorJoin(999, "unidentified");
            room.host.onPayload(999, state());
            check(live.size() == 2 && live.getById(999) == null, "unidentified placeholder entered simulation");
            room.guest.stop(null); room.host.update(.1f);
            check(live.size() == 1, "leave did not remove guest");
            room.rejoin(); room.pump(8); check(live.size() == 2, "reconnect did not restore guest");
            actor = room.guestTransport.myActor();
            room.host.update(13f); check(live.size() == 1, "silent guest was not removed");
            // The transport never reported a leave, so the handshake identity still
            // stands: a guest whose game stalled (a slow load, a long hitch) must
            // become a target again, and its checkpoints must keep being stored.
            room.host.onPayload(actor, state());
            check(live.size() == 2 && live.getById(actor) != null, "stalled guest was not readmitted");
            room.host.onPayload(999, state());
            check(live.getById(999) == null, "timeout readmitted an actor that never said hello");
            room.host.stop(null); check(live.size() == 1 && live.get(0).local(), "offline local participant disappeared");
        }
    }

    private static byte[] state() {
        return new PacketBuf().u8(NetProto.X_PLAYER_STATE).f32(0).f32(80).f32(0).f32(0).f32(0).u8(0).toBytes();
    }

    private static void authority() {
        try (Room room = new Room(false)) {
            room.pump(8);
            check(room.host.participants().size() == 1, "dedicated authority spawned a local avatar");
            check(room.guest.participants().size() == 0, "client acquired simulation participants");
        }
    }

    private static void headless() throws Exception {
        Path dir = Path.of("src/main/java/com/mineclone/sim");
        check(Files.isDirectory(dir), "sim sources missing: " + dir.toAbsolutePath());
        String[] forbidden = { "com.mineclone.render.", "com.mineclone.audio.", "com.mineclone.game.", "org.lwjgl." };
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                for (String bad : forbidden)
                    check(!text.contains(bad), file.getFileName() + " references " + bad);
            }
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
