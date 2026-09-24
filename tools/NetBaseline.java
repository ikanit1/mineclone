import com.mineclone.net.LoopbackTransport;
import com.mineclone.net.Multiplayer;
import com.mineclone.net.NetContext;
import com.mineclone.net.NetProto;
import com.mineclone.net.NetStats;
import com.mineclone.net.NetTransport;
import com.mineclone.net.PhotonTransport;
import com.mineclone.net.PlayerData;
import com.mineclone.net.RemotePlayer;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.entity.Projectile;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The protocol's steady traffic, measured (NET-01): the real Multiplayer of a
 * host and its guests over the loopback, driven by simulated time, with the
 * mobs and items of the network budget's scenarios. Numbers are payload as the
 * game writes it and the Photon wire size of the same messages (base64 in the
 * event JSON); chunk deltas at entry are a one-time burst and not requested.
 *
 *   java -cp "out;libs/*" tools/NetBaseline.java
 *
 * Prints one block per scenario: per guest download by packet code, Photon
 * wire per guest, guest upload and the room's Photon message count (every
 * message sent plus every delivery, summed over all peers).
 */
public class NetBaseline {
    private static final float FRAME = 1f / 60f;
    private static final float WARMUP = 10f, MEASURE = 60f;

    record Scenario(String name, int guests, int mobs, int items) {}

    public static void main(String[] args) {
        System.out.println("protocol v" + NetProto.VERSION + ", " + (int) MEASURE + " s after " + (int) WARMUP
                + " s of warmup, " + (int) NetProto.TICK_RATE + " Hz network frame");
        for (Scenario s : List.of(
                new Scenario("idle: 1 guest, no mobs, no items", 1, 0, 0),
                new Scenario("typical: 3 guests, 30 mobs, 50 items", 3, 30, 50),
                new Scenario("item peak: 3 guests, 30 mobs, 480 items", 3, 30, 480)))
            run(s);
    }

    private static void run(Scenario s) {
        LoopbackTransport.reset();
        Peer host = new Peer(new Vector3f(8, 80, 8));
        host.world = new World(20260924L);
        Random random = new Random(7);
        for (int i = 0; i < s.mobs; i++)
            host.mobs.add(new Mob(MobType.COW, 8 + random.nextFloat() * 40 - 20, 70, 8 + random.nextFloat() * 40 - 20,
                    new Random(i)));
        for (int i = 0; i < s.items; i++)
            host.items.add(new ItemEntity(ItemStack.of("cobblestone", 1 + i % 8), 8 + random.nextFloat() * 30 - 15, 70,
                    8 + random.nextFloat() * 30 - 15, 0f, i));
        host.net.start(photonWire(host.net), "baseline", true, "Host");
        List<Peer> guests = new ArrayList<>();
        for (int i = 0; i < s.guests; i++) {
            Peer guest = new Peer(new Vector3f(8 + i * 3, 80, 8));
            guest.net.start(photonWire(guest.net), "baseline", false, "Guest" + i);
            guests.add(guest);
        }
        float t = 0;
        for (; t < WARMUP; t += FRAME)
            frame(host, guests, t);
        if (host.net.players().size() != s.guests)
            throw new IllegalStateException("guests did not join: " + host.net.players().size());
        NetStats.Counts hostBefore = host.net.stats().totals();
        List<NetStats.Counts> guestsBefore = new ArrayList<>();
        for (Peer g : guests) guestsBefore.add(g.net.stats().totals());
        for (; t < WARMUP + MEASURE; t += FRAME)
            frame(host, guests, t);
        NetStats.Counts hostAfter = host.net.stats().totals();

        System.out.println();
        System.out.println("== " + s.name);
        double perGuest = s.guests * MEASURE;
        System.out.printf(Locale.ROOT, "  download per guest: %.2f KB/s payload, %.2f KB/s on Photon's wire%n",
                (hostAfter.payloadBytes(NetStats.OUT) - hostBefore.payloadBytes(NetStats.OUT)) / perGuest / 1024,
                (hostAfter.wireBytes(NetStats.OUT) - hostBefore.wireBytes(NetStats.OUT)) / perGuest / 1024);
        for (int code = 0; code < 256; code++) {
            long bytes = hostAfter.bytes(NetStats.OUT, code) - hostBefore.bytes(NetStats.OUT, code);
            long packets = hostAfter.packets(NetStats.OUT, code) - hostBefore.packets(NetStats.OUT, code);
            if (packets > 0)
                System.out.printf(Locale.ROOT, "    %-18s %8.2f KB/s  %6.1f packets/s  %6.0f B/packet%n",
                        NetProto.name(code), bytes / perGuest / 1024, packets / perGuest, (double) bytes / packets);
        }
        long upload = 0, room = 0;
        room += (hostAfter.messages(NetStats.OUT) - hostBefore.messages(NetStats.OUT))
                + (hostAfter.deliveries(NetStats.OUT) - hostBefore.deliveries(NetStats.OUT));
        for (int i = 0; i < guests.size(); i++) {
            NetStats.Counts after = guests.get(i).net.stats().totals(), before = guestsBefore.get(i);
            upload += after.payloadBytes(NetStats.OUT) - before.payloadBytes(NetStats.OUT);
            room += (after.messages(NetStats.OUT) - before.messages(NetStats.OUT))
                    + (after.deliveries(NetStats.OUT) - before.deliveries(NetStats.OUT));
        }
        System.out.printf(Locale.ROOT, "  delivered from each guest: %.2f KB/s (Photon uploads a message once"
                + " and delivers the copies)%n", upload / perGuest / 1024);
        NetStats.Counts first = guests.get(0).net.stats().totals(), firstBefore = guestsBefore.get(0);
        for (int code = 0; code < 256; code++) {
            long bytes = first.bytes(NetStats.OUT, code) - firstBefore.bytes(NetStats.OUT, code);
            long packets = first.packets(NetStats.OUT, code) - firstBefore.packets(NetStats.OUT, code);
            if (packets > 0)
                System.out.printf(Locale.ROOT, "    %-18s %8.2f KB/s  %6.1f packets/s  %6.0f B/packet%n",
                        NetProto.name(code), bytes / MEASURE / 1024, packets / MEASURE, (double) bytes / packets);
        }
        System.out.printf(Locale.ROOT, "  room messages (Photon count, all peers): %.1f /s%n", room / MEASURE);
        for (Peer g : guests) g.net.stop(null);
        host.net.stop(null);
        LoopbackTransport.reset();
    }

    private static void frame(Peer host, List<Peer> guests, float t) {
        host.net.update(FRAME);
        for (int i = 0; i < guests.size(); i++) {
            Peer g = guests.get(i);
            double angle = t * 0.35 + i * Math.PI * 2 / Math.max(1, guests.size());
            g.position.set(8 + (float) Math.cos(angle) * 14, 80, 8 + (float) Math.sin(angle) * 14);
            g.yaw = (float) angle;
            g.net.update(FRAME);
        }
    }

    /** The loopback, costed as Photon would carry each message. */
    private static NetTransport photonWire(NetTransport.Listener listener) {
        PhotonTransport photon = new PhotonTransport("", "", null);
        LoopbackTransport loop = new LoopbackTransport(listener);
        return new NetTransport() {
            public void connect(String room, boolean create, String nick) { loop.connect(room, create, nick); }
            public void send(byte[] payload, boolean reliable, int target) { loop.send(payload, reliable, target); }
            public void poll() { loop.poll(); }
            public void disconnect() { loop.disconnect(); }
            public State state() { return loop.state(); }
            public int myActor() { return loop.myActor(); }
            public int masterActor() { return loop.masterActor(); }
            public List<Integer> actors() { return loop.actors(); }
            public String actorName(int actor) { return loop.actorName(actor); }
            public String describe() { return "baseline " + loop.describe(); }
            public int wireBytes(int payloadBytes) { return photon.wireBytes(payloadBytes); }
        };
    }

    /** A host or a guest without a window: a position, a world, lists. */
    private static final class Peer implements NetContext {
        final Multiplayer net = new Multiplayer(this);
        final Vector3f position;
        final List<Mob> mobs = new ArrayList<>();
        final List<ItemEntity> items = new ArrayList<>();
        final List<Projectile> projectiles = new ArrayList<>();
        World world;
        float yaw, time = 0.3f;

        Peer(Vector3f position) { this.position = position; }

        public String playerId() {
            return java.util.UUID.nameUUIDFromBytes(("baseline-" + System.identityHashCode(this)).getBytes()).toString();
        }
        public PlayerData capturePlayerData() { return PlayerData.empty(position); }
        public World world() { return world; }
        public long seed() { return world == null ? 0 : world.seed; }
        public String worldName() { return "Baseline"; }
        public float timeOfDay() { return time; }
        public void setTimeOfDay(float value) { time = value; }
        public int gameMode() { return GameMode.SURVIVAL.ordinal(); }
        public Vector3f spawn() { return position; }
        public void startRemoteWorld(com.mineclone.net.RemoteWorld remote) {
            world = new World(remote.seed(), remote.generator());
            time = (float) remote.gameTime();
        }
        public void applyRemoteBlock(int x, int y, int z, byte id, byte meta, boolean broke) {
            world.setBlock(x, y, z, BlockType.byId(id), meta);
        }
        public void remoteBlockAction(int actor, int x, int y, int z, byte id, boolean broke) {}
        public ChunkSnapshot loadSavedChunk(int cx, int cz) { return null; }
        public Vector3f playerPosition() { return position; }
        public float playerYaw() { return yaw; }
        public float playerPitch() { return 0; }
        public int playerFlags() { return RemotePlayer.F_ON_GROUND; }
        public float playerHealth() { return 20; }
        public List<Mob> mobs() { return mobs; }
        public List<ItemEntity> groundItems() { return items; }
        public List<Projectile> projectiles() { return projectiles; }
        public void shootFor(int actor, float x, float y, float z, float vx, float vy, float vz, float damage) {}
        public void hurtByHost(com.mineclone.world.damage.DamageSource source, float damage) {}
        public void chatLine(String line) {}
        public void status(String line) {}
        public void give(ItemStack stack) {}
        public void netStopped(String reason) {}
        public void containerFromHost(int x, int y, int z, int kind, ItemStack[] slots, float left, float max, float cook) {}
    }
}
