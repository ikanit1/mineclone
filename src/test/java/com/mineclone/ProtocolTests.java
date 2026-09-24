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
