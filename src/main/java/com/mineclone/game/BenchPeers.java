package com.mineclone.game;

import com.mineclone.net.*;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.world.*;
import com.mineclone.world.entity.*;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/** Three real Multiplayer clients on Loopback; host remains the production Game session. */
final class BenchPeers implements AutoCloseable {
    final NetStats hostStats = new NetStats();
    private final List<Guest> guests = new ArrayList<>();
    private final Multiplayer host;
    private final Vector3f origin;

    BenchPeers(Multiplayer host, Vector3f origin) {
        this.host = host; this.origin = new Vector3f(origin);
        String room = "benchmark-host-three";
        host.start(new CountedTransport(host, hostStats), room, true, "BenchHost");
        for (int i = 0; i < 3; i++) {
            Guest guest = new Guest(i);
            guest.peer.start(new CountedTransport(guest.peer, new NetStats()), room, false, "Guest" + i);
            guests.add(guest);
        }
    }

    void update(float dt, double seconds) {
        for (Guest guest : guests) {
            double angle = seconds * .35 + guest.index * Math.PI * 2 / 3;
            guest.position.set(origin.x + (float) Math.cos(angle) * 14, origin.y,
                    origin.z + (float) Math.sin(angle) * 14);
            guest.yaw = (float) angle;
            guest.peer.update(dt);
        }
    }

    boolean ready() { return host.players().size() == 3 && guests.stream().allMatch(g -> g.world != null && g.peer.joined() && !g.peer.inventoryBusy()); }
    public void close() { for (Guest guest : guests) guest.peer.stop(null); host.stop(null); }

    private static final class CountedTransport implements NetTransport {
        private final LoopbackTransport delegate;
        private final NetStats stats;
        CountedTransport(Listener listener, NetStats stats) {
            this.stats = stats;
            delegate = new LoopbackTransport(new Listener() {
                public void onState(State state, String detail) { listener.onState(state, detail); }
                public void onJoined(int actor, boolean created) { listener.onJoined(actor, created); }
                public void onActorJoin(int actor, String name) { listener.onActorJoin(actor, name); }
                public void onActorLeave(int actor) { listener.onActorLeave(actor); }
                public void onPayload(int from, byte[] data) { stats.received(data.length); listener.onPayload(from, data); }
                public void onRoomList(List<RoomInfo> rooms) { listener.onRoomList(rooms); }
            });
        }
        public void connect(String room, boolean create, String nick) { delegate.connect(room, create, nick); }
        public void send(byte[] payload, boolean reliable, int target) {
            stats.sent(payload.length, target == NetChannel.ALL ? delegate.actors().size() : 1);
            delegate.send(payload, reliable, target);
        }
        public void poll() { delegate.poll(); }
        public void disconnect() { delegate.disconnect(); }
        public State state() { return delegate.state(); }
        public int myActor() { return delegate.myActor(); }
        public int masterActor() { return delegate.masterActor(); }
        public List<Integer> actors() { return delegate.actors(); }
        public String actorName(int actor) { return delegate.actorName(actor); }
        public String describe() { return delegate.describe(); }
    }

    private static final class Guest implements NetContext {
        final int index;
        final Multiplayer peer = new Multiplayer(this);
        final Vector3f position = new Vector3f();
        World world;
        float yaw, time;
        final List<Mob> mobs = new ArrayList<>();
        final List<ItemEntity> items = new ArrayList<>();
        final List<Projectile> projectiles = new ArrayList<>();
        Guest(int index) { this.index = index; }
        public String playerId() { return java.util.UUID.nameUUIDFromBytes(("benchmark-guest-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
        public PlayerData capturePlayerData() { return PlayerData.empty(position); }
        public World world() { return world; }
        public long seed() { return world == null ? 0 : world.seed; }
        public String worldName() { return "Benchmark"; }
        public float timeOfDay() { return time; }
        public void setTimeOfDay(float value) { time = value; }
        public int gameMode() { return GameMode.CREATIVE.ordinal(); }
        public Vector3f spawn() { return position; }
        public void startRemoteWorld(long seed, String name, float time, int mode, float x, float y, float z) {
            world = new World(seed); this.time = time;
            int cx = Math.floorDiv((int) position.x, 16), cz = Math.floorDiv((int) position.z, 16);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                world.getChunk(cx + dx, cz + dz); peer.noteChunkLoaded(cx + dx, cz + dz);
            }
        }
        public void applyRemoteBlock(int x, int y, int z, byte id, byte meta, boolean broke) { world.setBlock(x, y, z, BlockType.byId(id), meta); }
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
        public void hurtByHost(float damage) {}
        public void chatLine(String line) {}
        public void status(String line) {}
        public void give(ItemStack stack) {}
        public void netStopped(String reason) {}
        public void containerFromHost(int x, int y, int z, int kind, ItemStack[] slots, float left, float max, float cook) {}
    }
}
