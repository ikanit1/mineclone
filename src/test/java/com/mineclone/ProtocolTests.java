package com.mineclone;

import com.mineclone.net.MobSnapshot;
import com.mineclone.net.NetProto;
import com.mineclone.net.PacketBuf;
import com.mineclone.net.PlayerHurt;
import com.mineclone.net.connect.ConnectDiagnosis;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** NET-02: protocol v8 — every changed body round-trips, refuses damage, and the document matches the code. */
final class ProtocolTests {
    static void runAll(TestMain.Runner r) {
        r.run("the protocol document lists exactly NetProto's codes", ProtocolTests::codeTable);
        r.run("S_PLAYER_HURT v8 round-trips every kind of source", ProtocolTests::hurtRoundTrip);
        r.run("a cut S_PLAYER_HURT reads as truncated", ProtocolTests::hurtTruncated);
        r.run("S_PLAYER_HURT refuses numbers no host sends", ProtocolTests::hurtInvalid);
        r.run("a version refusal names both versions, the host's build and who must update", ProtocolTests::refusal);
        r.run("checking a mob snapshot allocates nothing", ProtocolTests::snapshotValidation);
        r.run("S_GEN_MAP round-trips pinned chunks and refuses damage", ProtocolTests::genMap);
        r.run("S_WELCOME v8 carries the generator and the exact clock", ProtocolTests::welcome);
        r.run("C_PLAYER/S_PLAYER v8 carry the whole player record", ProtocolTests::playerRecord);
    }

    private static void playerRecord() throws Exception {
        var record = PlayerRecordTests.sample();
        byte[] bytes = new com.mineclone.net.PlayerData(record).bytes();
        PacketBuf in = PacketBuf.reading(bytes);
        var back = com.mineclone.net.PlayerData.read(in);
        check(back != null && !in.hasMore(), "the record did not come back");
        PlayerRecordTests.same(record, back.record(), "the record over the wire");
        for (int n = 0; n < bytes.length; n += Math.max(1, bytes.length / 40))
            check(com.mineclone.net.PlayerData.read(PacketBuf.reading(bytes, 0, n)) == null, "cut at " + n + " accepted");
        // A record whose minimum reader is past this build is refused, not half-read.
        var out = new java.io.ByteArrayOutputStream();
        try (var data = new java.io.DataOutputStream(out)) {
            java.util.Map<String, byte[]> sections = new java.util.LinkedHashMap<>();
            var format = new java.io.ByteArrayOutputStream();
            try (var f = new java.io.DataOutputStream(format)) {
                f.writeInt(com.mineclone.save.PlayerRecordCodec.MAGIC);
                f.writeInt(com.mineclone.save.PlayerRecord.VERSION + 5);
                f.writeInt(com.mineclone.save.PlayerRecord.VERSION + 5);
            }
            sections.put("format", format.toByteArray());
            com.mineclone.data.SectionCodec.write(data, sections);
        }
        PacketBuf future = new PacketBuf();
        future.bytes(out.toByteArray());
        check(com.mineclone.net.PlayerData.read(PacketBuf.reading(future.toBytes())) == null, "a newer record accepted");
    }

    private static void genMap() throws Exception {
        java.util.Map<Long, com.mineclone.world.gen.WorldGenVersion> pinned = new java.util.HashMap<>();
        for (int x = -22; x < 23; x++)
            for (int z = -22; z < 23; z++)
                pinned.put(com.mineclone.world.World.key(x, z), com.mineclone.world.gen.WorldGenVersion.V1);
        pinned.put(com.mineclone.world.World.key(900, -900), com.mineclone.world.gen.WorldGenVersion.V2);
        for (var map : java.util.List.of(java.util.Map.<Long, com.mineclone.world.gen.WorldGenVersion>of(), pinned)) {
            PacketBuf b = new PacketBuf();
            new com.mineclone.net.GenMap(map).write(b);
            byte[] bytes = b.toBytes();
            com.mineclone.net.GenMap back = com.mineclone.net.GenMap.read(PacketBuf.reading(bytes));
            check(back != null && back.pinned().equals(map), "gen map of " + map.size());
            if (map.size() > 2000)
                check(bytes.length < 1024, "2 026 pinned chunks took " + bytes.length + " bytes");
            PacketBuf cut = PacketBuf.reading(bytes, 0, bytes.length - 1);
            check(com.mineclone.net.GenMap.read(cut) == null && cut.truncated(), "a cut map read");
        }
        // A body that is not gzip, a ledger naming an unknown version, a bomb.
        byte[] unknown = com.mineclone.world.gen.ChunkLedger.of(pinned).encode();
        unknown[unknown.length - 1] = 99;
        byte[] bomb = new byte[com.mineclone.net.GenMap.MAX_INFLATED + 10];
        for (byte[] raw : new byte[][] { { 1, 2, 3 }, gzip(unknown), gzip(bomb) }) {
            PacketBuf b = new PacketBuf();
            b.bytes(raw);
            check(com.mineclone.net.GenMap.read(PacketBuf.reading(b.toBytes())) == null, "damaged map accepted");
        }
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.util.zip.GZIPOutputStream(bytes)) { out.write(raw); }
        return bytes.toByteArray();
    }

    private static void welcome() {
        var v2 = new com.mineclone.world.gen.WorldGenSettings(com.mineclone.world.gen.WorldGenVersion.V2,
                com.mineclone.world.gen.GenFeatures.V2, 1234L);
        double time = 1234.567890123456789;
        com.mineclone.net.Welcome sent = com.mineclone.net.Welcome.of(-42L, "Мир у реки", time, 987_654_321L, 1,
                8.5f, 71f, -3.5f, v2);
        PacketBuf b = new PacketBuf();
        sent.write(b);
        byte[] bytes = b.toBytes();
        PacketBuf in = PacketBuf.reading(bytes);
        com.mineclone.net.Welcome back = com.mineclone.net.Welcome.read(in);
        check(!in.truncated() && !in.hasMore() && back.valid() && back.seed() == -42L && back.name().equals("Мир у реки")
                && back.gameTime() == time && back.worldTicks() == 987_654_321L && back.mode() == 1
                && back.spawnX() == 8.5f && back.spawnZ() == -3.5f && v2.equals(back.settings()), "welcome: " + back);
        for (int n = 0; n < bytes.length; n++) {
            PacketBuf cut = PacketBuf.reading(bytes, 0, n);
            com.mineclone.net.Welcome.read(cut);
            check(cut.truncated(), "welcome cut at " + n + " read as whole");
        }
        byte[] future = v2.encode();
        java.nio.ByteBuffer.wrap(future).putInt(0, 99);
        check(new com.mineclone.net.Welcome(1, "w", 0, 0, 0, 0, 70, 0, future).settings() == null,
                "a generator this build lacks");
        for (com.mineclone.net.Welcome bad : new com.mineclone.net.Welcome[] {
                new com.mineclone.net.Welcome(1, "w", Double.NaN, 0, 0, 0, 70, 0, v2.encode()),
                new com.mineclone.net.Welcome(1, "w", 0, -1, 0, 0, 70, 0, v2.encode()),
                new com.mineclone.net.Welcome(1, "w", 0, 0, 9, 0, 70, 0, v2.encode()),
                new com.mineclone.net.Welcome(1, "w", 0, 0, 0, Float.NaN, 70, 0, v2.encode()),
                new com.mineclone.net.Welcome(1, "w".repeat(300), 0, 0, 0, 0, 70, 0, v2.encode()) })
            check(!bad.valid(), "accepted " + bad);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static void codeTable() throws Exception {
        Map<Integer, String> code = new TreeMap<>();
        for (var field : NetProto.class.getFields()) {
            int m = field.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isFinal(m) && field.getType() == int.class
                    && field.getName().matches("[CSX]_[A-Z_]+")) {
                String clash = code.put(field.getInt(null), field.getName());
                check(clash == null, "two constants share code " + field.getInt(null) + ": " + clash + ", " + field.getName());
                check(!NetProto.name(field.getInt(null)).startsWith("code"), field.getName() + " has no name");
            }
        }
        Map<Integer, String> doc = new TreeMap<>();
        Matcher row = Pattern.compile("^\\| (\\d+) \\| `([CSX]_[A-Z_]+)` \\|", Pattern.MULTILINE)
                .matcher(Files.readString(Path.of("docs/NETWORK_PROTOCOL.md")));
        while (row.find())
            check(doc.put(Integer.parseInt(row.group(1)), row.group(2)) == null, "code listed twice: " + row.group(1));
        check(code.equals(doc), "docs/NETWORK_PROTOCOL.md and NetProto disagree:\n  code " + code + "\n  doc  " + doc);
        check(Files.readString(Path.of("docs/NETWORK_PROTOCOL.md")).startsWith("# Network protocol v" + NetProto.VERSION),
                "the document names another version");
    }

    private static final DamageSource[] SOURCES = {
            DamageSource.byPlayer(DamageType.MELEE, 3, 10.5f, 64f, -3.25f, 1.5f),
            DamageSource.byPlayer(DamageType.PROJECTILE, DamageSource.NO_ATTACKER, 1f, 2f, 3f, 0.6f),
            DamageSource.byMob(DamageType.EXPLOSION, MobType.CREEPER, -100f, 40f, 250f, 1f),
            DamageSource.byMob(DamageType.PROJECTILE, null, 0f, 70f, 0f, 0.6f),
            DamageSource.of(DamageType.FALL),
            DamageSource.of(DamageType.VOID),
    };

    private static byte[] encode(PlayerHurt hurt) {
        PacketBuf b = new PacketBuf();
        hurt.write(b);
        return b.toBytes();
    }

    private static void hurtRoundTrip() {
        for (DamageType kind : DamageType.values())
            check(DamageType.byId(kind.id()) == kind, kind + " id");
        for (DamageSource source : SOURCES) {
            PacketBuf in = PacketBuf.reading(encode(PlayerHurt.of(source, 4.5f)));
            PlayerHurt back = PlayerHurt.read(in);
            check(!in.truncated() && !in.hasMore() && back.valid(), "read " + source);
            check(back.amount() == 4.5f && back.source().equals(source), source + " came back as " + back.source());
        }
        // Without an origin the body is twelve bytes shorter.
        check(encode(PlayerHurt.of(SOURCES[4], 1f)).length + 12 == encode(PlayerHurt.of(SOURCES[0], 1f)).length,
                "origin bytes");
    }

    private static void hurtTruncated() {
        for (DamageSource source : SOURCES) {
            byte[] whole = encode(PlayerHurt.of(source, 2f));
            for (int n = 0; n < whole.length; n++) {
                PacketBuf in = PacketBuf.reading(whole, 0, n);
                PlayerHurt.read(in);
                check(in.truncated(), source + " cut at " + n + " read as whole");
            }
        }
    }

    private static void hurtInvalid() {
        PlayerHurt good = PlayerHurt.of(SOURCES[2], 3f);
        int creeper = good.mob(), kinds = DamageType.values().length;
        PlayerHurt[] bad = {
                new PlayerHurt(Float.NaN, 0, 0, 0, 0, 0, 1, 0, 0),
                new PlayerHurt(0f, 0, 0, 0, 0, 0, 1, 0, 0),
                new PlayerHurt(-2f, 0, 0, 0, 0, 0, 1, 0, 0),
                new PlayerHurt(PlayerHurt.MAX_AMOUNT * 2, 0, 0, 0, 0, 0, 1, 0, 0),
                new PlayerHurt(3f, 99, 0, 0, 0, 0, 1, 0, 0),
                new PlayerHurt(3f, kinds, 0, 0, 0, 0, 1, 0, 0),
                new PlayerHurt(3f, 0, 4, 0, 0, 0, 1, 0, 0),
                new PlayerHurt(3f, 0, 0, 0, 0, 0, Float.NaN, 0, 0),
                new PlayerHurt(3f, 0, 0, 0, 0, 0, -1, 0, 0),
                new PlayerHurt(3f, 0, 0, 0, 0, 0, PlayerHurt.MAX_KNOCKBACK + 1, 0, 0),
                new PlayerHurt(3f, 0, 0, 0, 0, 0, 1, MobType.values().length + 1, 0),
                new PlayerHurt(3f, 0, PlayerHurt.F_PLAYER, 0, 0, 0, 1, creeper, 0),
                new PlayerHurt(3f, 0, 0, 0, 0, 0, 1, 0, 5),
                new PlayerHurt(3f, 0, PlayerHurt.F_ORIGIN, Float.NaN, 0, 0, 1, 0, 0),
                new PlayerHurt(3f, 0, PlayerHurt.F_ORIGIN, 0, Float.POSITIVE_INFINITY, 0, 1, 0, 0),
                new PlayerHurt(3f, 0, PlayerHurt.F_ORIGIN, 0, 0, 2e7f, 1, 0, 0),
        };
        check(good.valid(), "a creeper's blast");
        for (PlayerHurt hurt : bad) {
            check(!hurt.valid(), "accepted " + hurt);
            PlayerHurt back = PlayerHurt.read(PacketBuf.reading(encode(hurt)));
            check(!back.valid(), "accepted on the wire " + hurt);
        }
    }

    private static void refusal() {
        String older = ConnectDiagnosis.versionMismatch(7, 8, "Mineclone 1.1.0 (abc123)");
        check(older.contains("v7") && older.contains("v8") && older.contains("Mineclone 1.1.0 (abc123)")
                && older.contains("Обновите игру"), older);
        String newer = ConnectDiagnosis.versionMismatch(9, 8, "Mineclone 1.1.0 (abc123)");
        check(newer.contains("v9") && newer.contains("обновиться нужно ему"), newer);
        check(older.contains("версия"), "the refusal must still say it is about the version");
    }

    private static void snapshotValidation() {
        Mob cow = new Mob(MobType.COW, 1, 70, 2, new Random(1));
        MobSnapshot snapshot = MobSnapshot.capture(7, cow);
        check(snapshot.valid(), "a live cow's snapshot");
        var memory = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        if (!memory.isThreadAllocatedMemorySupported())
            return;
        memory.setThreadAllocatedMemoryEnabled(true);
        boolean all = true;
        for (int i = 0; i < 20_000; i++) all &= snapshot.valid();
        long thread = Thread.currentThread().getId(), before = memory.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 20_000; i++) all &= snapshot.valid();
        long bytes = memory.getThreadAllocatedBytes(thread) - before;
        check(all && bytes < 4096, "20 000 checks allocated " + bytes + " bytes");
    }
}
