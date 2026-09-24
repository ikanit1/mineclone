package com.mineclone;

import com.mineclone.net.LanTransport;
import com.mineclone.net.LoopbackTransport;
import com.mineclone.net.Multiplayer;
import com.mineclone.net.NetProto;
import com.mineclone.net.NetStats;
import com.mineclone.net.PacketBuf;
import com.mineclone.net.PhotonTransport;
import com.mineclone.net.photon.PhotonCodes;
import com.mineclone.net.photon.PhotonPeer;
import com.mineclone.world.World;
import java.util.Base64;

/** NET-01: traffic by packet code — whole seconds, every byte accounted for, the wire as the transport makes it. */
final class NetStatsTests {
    static void runAll(TestMain.Runner r) {
        r.run("windows sum whole seconds and forget the old minute", NetStatsTests::windows);
        r.run("the heaviest codes come first; rates need no allocation", NetStatsTests::topAndRates);
        r.run("the csv covers each whole second once", NetStatsTests::csv);
        r.run("a loopback session accounts for every byte by code", NetStatsTests::loopback);
        r.run("an unknown packet takes the rest of its message", NetStatsTests::brokenMessage);
        r.run("photon's wire size is the framed event, lan's the frame", NetStatsTests::wire);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final long S = 1_000_000_000L;

    private static void windows() {
        long[] now = { 10 * S };
        NetStats stats = new NetStats(() -> now[0]);
        stats.packet(NetStats.OUT, NetProto.S_MOBS, 100, 2);
        stats.message(NetStats.OUT, 100, 140, 2);
        now[0] = 11 * S + 300;
        stats.packet(NetStats.OUT, NetProto.S_MOBS, 50, 1);
        stats.packet(NetStats.IN, NetProto.X_PLAYER_STATE, 22, 1);
        stats.message(NetStats.IN, 22, 22, 1);
        now[0] = 12 * S + S / 2;
        NetStats.Counts last = stats.window(1);
        check(last.bytes(NetStats.OUT, NetProto.S_MOBS) == 50 && last.packets(NetStats.IN, NetProto.X_PLAYER_STATE) == 1
                && last.messages(NetStats.OUT) == 0, "window(1) is the last whole second");
        NetStats.Counts two = stats.window(2);
        check(two.bytes(NetStats.OUT, NetProto.S_MOBS) == 250 && two.packets(NetStats.OUT, NetProto.S_MOBS) == 3
                && two.deliveries(NetStats.OUT) == 2 && two.payloadBytes(NetStats.OUT) == 200
                && two.wireBytes(NetStats.OUT) == 280, "window(2): " + two.bytes(NetStats.OUT, NetProto.S_MOBS));
        stats.packet(NetStats.OUT, NetProto.S_ITEMS, 7, 1);
        check(stats.window(2).packets(NetStats.OUT, NetProto.S_ITEMS) == 0, "the second in progress counted");
        // A minute later the slot of second 12 holds second 73; nothing old leaks into it.
        now[0] = 73 * S;
        stats.packet(NetStats.OUT, NetProto.S_TIME, 5, 1);
        now[0] = 74 * S;
        NetStats.Counts fresh = stats.window(NetStats.SECONDS);
        check(fresh.packets(NetStats.OUT, NetProto.S_ITEMS) == 0 && fresh.packets(NetStats.OUT, NetProto.S_TIME) == 1
                && fresh.bytes(NetStats.OUT, NetProto.S_MOBS) == 0, "old seconds leaked into the minute");
        NetStats.Counts all = stats.totals();
        check(all.bytes(NetStats.OUT, NetProto.S_MOBS) == 250 && all.packets(NetStats.OUT, NetProto.S_ITEMS) == 1
                && all.packets(NetStats.OUT, NetProto.S_TIME) == 1 && all.messages(NetStats.IN) == 1, "totals");
        for (int bad : new int[] { 0, NetStats.SECONDS + 1 }) {
            boolean refused = false;
            try { stats.window(bad); } catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "window of " + bad + " s");
        }
    }

    private static void topAndRates() throws Exception {
        long[] now = { 5 * S };
        NetStats stats = new NetStats(() -> now[0]);
        stats.packet(NetStats.OUT, NetProto.S_MOBS, 85, 3);
        stats.packet(NetStats.OUT, NetProto.S_ITEMS, 300, 1);
        stats.packet(NetStats.OUT, NetProto.S_TIME, 5, 3);
        stats.message(NetStats.OUT, 400, 540, 3);
        stats.message(NetStats.IN, 22, 22, 1);
        now[0] = 6 * S;
        var top = stats.window(1).top(NetStats.OUT, 2);
        check(top.size() == 2 && top.get(0)[0] == NetProto.S_ITEMS && top.get(0)[1] == 300
                && top.get(1)[0] == NetProto.S_MOBS && top.get(1)[1] == 255 && top.get(1)[2] == 3, "top");
        double[] rates = new double[5];
        stats.rates(1, rates);
        check(rates[0] == 1200 && rates[1] == 22 && rates[2] == 1 && rates[3] == 1 && rates[4] == 1 + 3 + 2,
                "rates " + java.util.Arrays.toString(rates));
        var memory = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        if (memory.isThreadAllocatedMemorySupported()) {
            memory.setThreadAllocatedMemoryEnabled(true);
            for (int i = 0; i < 20_000; i++) { stats.rates(1, rates); stats.packet(NetStats.OUT, 30, 85, 2); }
            long thread = Thread.currentThread().getId(), before = memory.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 20_000; i++) {
                stats.rates(1, rates);
                stats.packet(NetStats.OUT, 30, 85, 2);
                stats.message(NetStats.OUT, 85, 85, 2);
                stats.peer(3, NetStats.OUT, 85);
            }
            long bytes = memory.getThreadAllocatedBytes(thread) - before;
            check(bytes < 4096, "recording and rates allocated " + bytes + " bytes");
        }
    }

    private static void csv() throws Exception {
        long[] now = { 100 * S };
        NetStats stats = new NetStats(() -> now[0]);
        stats.packet(NetStats.OUT, NetProto.S_MOBS, 85, 2);
        stats.message(NetStats.OUT, 85, 120, 2);
        now[0] = 101 * S;
        stats.packet(NetStats.IN, NetProto.X_CHAT, 9, 1);
        StringBuilder out = new StringBuilder();
        long last = stats.appendCsv(out, Long.MIN_VALUE);
        check(last == 100 && out.toString().equals("100,out,30,MOBS,2,170,,,\n".replace("\n", System.lineSeparator())
                + "100,out,-1,messages,,170,1,2,240\n".replace("\n", System.lineSeparator())), "csv:\n" + out);
        out.setLength(0);
        check(stats.appendCsv(out, last) == last && out.length() == 0, "a second written twice");
        now[0] = 102 * S;
        check(stats.appendCsv(out, last) == 101 && out.toString().startsWith("101,in,50,CHAT,1,9"), "next second: " + out);
        check(NetStats.CSV_HEADER.split(",").length == 9, "header");
    }

    /** Host and guest over the loopback, as the game plays: every byte lands under some code. */
    private static void loopback() {
        LoopbackTransport.reset();
        try {
            var host = new NetworkTests.TestContext(new World(77L), "stats");
            Multiplayer hostNet = new Multiplayer(host);
            LoopbackTransport hostT = new LoopbackTransport(hostNet);
            hostNet.start(hostT, "room", true, "Host");
            var guest = new NetworkTests.TestContext(null, "");
            Multiplayer guestNet = new Multiplayer(guest);
            LoopbackTransport guestT = new LoopbackTransport(guestNet);
            guestNet.start(guestT, "room", false, "Guest");
            var second = new NetworkTests.TestContext(null, "");
            Multiplayer secondNet = new Multiplayer(second);
            LoopbackTransport secondT = new LoopbackTransport(secondNet);
            secondNet.start(secondT, "room", false, "Second");
            host.mobs.add(new com.mineclone.world.entity.Mob(com.mineclone.world.entity.MobType.COW, 9f, 70f, 9f,
                    new java.util.Random(3)));
            for (int i = 0; i < 40; i++) {
                hostNet.update(0.1f);
                guestNet.update(0.1f);
                secondNet.update(0.1f);
            }
            // The last messages are still on their way: take them in without
            // sending anything back (the channel counts only what it flushes).
            hostT.poll();
            guestT.poll();
            secondT.poll();
            // A broadcast counts once per recipient: the host's mob snapshots
            // delivered are exactly those the two guests received.
            long delivered = hostNet.stats().totals().packets(NetStats.OUT, NetProto.S_MOBS);
            long received = guestNet.stats().totals().packets(NetStats.IN, NetProto.S_MOBS)
                    + secondNet.stats().totals().packets(NetStats.IN, NetProto.S_MOBS);
            check(delivered > 0 && delivered == received, "mob snapshots delivered " + delivered + ", received " + received);
            for (Multiplayer side : new Multiplayer[] { hostNet, guestNet, secondNet }) {
                NetStats.Counts all = side.stats().totals();
                for (int dir : new int[] { NetStats.OUT, NetStats.IN }) {
                    long byCode = 0;
                    for (int code = 0; code < 256; code++) byCode += all.bytes(dir, code);
                    check(byCode == all.payloadBytes(dir) && byCode > 0,
                            (side == hostNet ? "host " : "guest ") + dir + ": codes " + byCode + " != messages " + all.payloadBytes(dir));
                    check(all.wireBytes(dir) == all.payloadBytes(dir), "loopback adds nothing on the wire");
                }
            }
            // Each guest's state goes to the host and to the other guest.
            NetStats.Counts got = hostNet.stats().totals();
            long states = guestNet.stats().totals().packets(NetStats.OUT, NetProto.X_PLAYER_STATE)
                    + secondNet.stats().totals().packets(NetStats.OUT, NetProto.X_PLAYER_STATE);
            long arrived = got.packets(NetStats.IN, NetProto.X_PLAYER_STATE)
                    + guestNet.stats().totals().packets(NetStats.IN, NetProto.X_PLAYER_STATE)
                    + secondNet.stats().totals().packets(NetStats.IN, NetProto.X_PLAYER_STATE)
                    - hostNet.stats().totals().packets(NetStats.OUT, NetProto.X_PLAYER_STATE);
            check(states > 0 && arrived == states && got.bytes(NetStats.IN, NetProto.X_PLAYER_STATE)
                    == 22 * got.packets(NetStats.IN, NetProto.X_PLAYER_STATE),
                    "player state: delivered " + states + ", arrived " + arrived);
            // Per peer: what the host delivered and received, split by guest.
            var peers = hostNet.stats().peers();
            long out = 0, in = 0;
            for (long[] sums : peers.values()) { out += sums[0]; in += sums[1]; }
            check(peers.size() == 2 && out == got.payloadBytes(NetStats.OUT) && in == got.payloadBytes(NetStats.IN),
                    "per peer");
            hostNet.stop(null);
            guestNet.stop(null);
            secondNet.stop(null);
        } finally {
            LoopbackTransport.reset();
        }
    }

    private static void brokenMessage() {
        var host = new NetworkTests.TestContext(new World(78L), "broken");
        Multiplayer net = new Multiplayer(host);
        PacketBuf message = new PacketBuf(64);
        message.u8(NetProto.X_PLAYER_STATE).f32(1).f32(2).f32(3).f32(0).f32(0).u8(0);
        message.u8(199).u8(1).u8(2).u8(3).u8(4).u8(5);
        net.onPayload(5, message.toBytes());
        NetStats.Counts all = net.stats().totals();
        check(all.bytes(NetStats.IN, NetProto.X_PLAYER_STATE) == 22 && all.bytes(NetStats.IN, 199) == 6
                && all.payloadBytes(NetStats.IN) == 28, "broken message: " + all.bytes(NetStats.IN, 199));
    }

    private static void wire() {
        PhotonTransport photon = new PhotonTransport("", "", null);
        for (int n : new int[] { 0, 1, 2, 3, 4, 99, 100, 101, 5000, 24_000 }) {
            String base64 = Base64.getEncoder().encodeToString(new byte[n]);
            int framed = PhotonPeer.frame(PhotonPeer.operationJson(PhotonCodes.OP_RAISE_EVENT, PhotonCodes.P_CODE, 1,
                    PhotonCodes.P_DATA, base64)).length();
            check(photon.wireBytes(n) == framed, n + " bytes: " + photon.wireBytes(n) + " != " + framed);
        }
        check(LanTransport.join("127.0.0.1", null).wireBytes(100) == 113, "lan frame");
    }
}
