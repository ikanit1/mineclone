package com.mineclone;

import com.mineclone.data.SectionCodec;
import com.mineclone.item.Components;
import com.mineclone.net.PlayerData;
import com.mineclone.save.LevelData;
import com.mineclone.save.LevelLoad;
import com.mineclone.save.PlayerRecord;
import com.mineclone.save.PlayerRecordCodec;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.sim.WorldClock;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;

/** SAVE-07: one player format for the host's level, guests' files and the v7 adapter. */
final class PlayerRecordTests {
    static void runAll(TestMain.Runner r) {
        r.run("player record round-trips every section and keeps double precision", PlayerRecordTests::roundTrip);
        r.run("player record never exposes or borrows mutable state", PlayerRecordTests::immutable);
        r.run("damaged effects are kept byte for byte and never block the inventory", PlayerRecordTests::damagedEffects);
        r.run("unreadable guest inventory refuses the guest and the file is never replaced", PlayerRecordTests::unreadableGuest);
        r.run("guest record requiring a newer reader is refused and preserved", PlayerRecordTests::tooNewGuest);
        r.run("guest v1 checkpoint loads, is backed up and rewritten as v2", PlayerRecordTests::guestMigration);
        r.run("unknown player sections from a newer build survive five guest saves", PlayerRecordTests::opaqueGuestSections);
        r.run("host level carries the same record bytes as a guest file", PlayerRecordTests::hostLevel);
        r.run("level written without a record opens, migrates behind a backup and keeps progress", PlayerRecordTests::recordlessLevel);
        r.run("protocol v7 checkpoint replaces only the fields it carries", PlayerRecordTests::mergeLegacy);
        r.run("live vitals are clamped instead of failing a save", PlayerRecordTests::liveVitals);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static PlayerRecord sample() {
        ItemStack[] inventory = new ItemStack[Inventory.SIZE];
        inventory[0] = ItemStack.of("diamond", 17);
        inventory[1] = ItemStack.of("iron_pickaxe").set(Components.CUSTOM_NAME, "Кирка");
        inventory[1].setDamage(42);
        inventory[35] = ItemStack.of("coal", 64);
        ItemStack[] equipment = new ItemStack[PlayerRecord.EQUIPMENT_SLOTS];
        equipment[4] = ItemStack.of("torch", 3);
        return PlayerRecord.builder()
                .pose(1.0000001, 72.5, -3.25, .5f, -.25f, 7)
                .vitals(new PlayerRecord.Vitals(7.5f, 3f, 2.25f, 1.5f, 120f))
                .inventory(inventory)
                .equipment(equipment)
                .pending(new ItemStack[] { ItemStack.of("cobblestone", 30), ItemStack.of("stick", 2) })
                .spawn(new PlayerRecord.Spawn(10.5, 64, -20.5))
                .effects(List.of(new PlayerRecord.Effect("mineclone:poison", 200, 1),
                        new PlayerRecord.Effect("mineclone:speed", 40, 0)))
                .advancements(List.of("mineclone:survival/first_log"))
                .recipes(List.of("mineclone:planks", "mineclone:stick"))
                .progress(new byte[] { 2, 5 })
                .section("future:mood", new byte[] { 1, 2, 3 })
                .build();
    }

    /** Every field, compared exactly; stacks by content including components. */
    static void same(PlayerRecord expected, PlayerRecord actual, String where) {
        check(expected.pose().equals(actual.pose()), where + ": pose " + actual.pose());
        check(expected.vitals().equals(actual.vitals()), where + ": vitals " + actual.vitals());
        check(java.util.Objects.equals(expected.spawn(), actual.spawn()), where + ": spawn");
        sameItems(expected.inventory(), actual.inventory(), where + ": inventory");
        sameItems(expected.equipment(), actual.equipment(), where + ": equipment");
        sameItems(expected.pending(), actual.pending(), where + ": pending");
        check(expected.effects().equals(actual.effects()), where + ": effects");
        check(expected.advancements().equals(actual.advancements()), where + ": advancements");
        check(expected.recipes().equals(actual.recipes()), where + ": recipes");
        check(Arrays.equals(expected.progress(), actual.progress()), where + ": progress");
        var a = expected.extraSections();
        var b = actual.extraSections();
        check(a.keySet().equals(b.keySet()), where + ": extra keys " + b.keySet());
        for (String key : a.keySet()) check(Arrays.equals(a.get(key), b.get(key)), where + ": extra " + key);
    }

    private static void sameItems(ItemStack[] a, ItemStack[] b, String where) {
        check(a.length == b.length, where + " length " + b.length);
        for (int i = 0; i < a.length; i++)
            check(a[i] == null ? b[i] == null : b[i] != null && a[i].contentEquals(b[i]), where + " slot " + i);
    }

    private static void roundTrip() throws Exception {
        PlayerRecord record = sample();
        byte[] bytes = PlayerRecordCodec.encode(record);
        PlayerRecord back = PlayerRecordCodec.decode(bytes);
        same(record, back, "round trip");
        check(back.pose().x() == 1.0000001 && back.inventory()[1].damage() == 42
                && "Кирка".equals(back.inventory()[1].get(Components.CUSTOM_NAME)), "precision/components");
        check(Arrays.equals(bytes, PlayerRecordCodec.encode(back)), "rewriting an unchanged record changed its bytes");

        // Only the four required sections: every optional one takes its default.
        Map<String, byte[]> sections = sections(bytes);
        sections.keySet().retainAll(List.of("format", "pose", "vitals", "inventory"));
        PlayerRecord minimal = PlayerRecordCodec.decode(join(sections));
        check(minimal.equipment().length == PlayerRecord.EQUIPMENT_SLOTS && minimal.pending().length == 0
                && minimal.spawn() == null && minimal.effects().isEmpty() && minimal.progress().length == 0
                && minimal.extraSections().isEmpty(), "optional sections did not default");
        for (String required : List.of("format", "pose", "vitals", "inventory")) {
            Map<String, byte[]> missing = sections(bytes);
            missing.remove(required);
            rejected(join(missing), "record without " + required);
        }
        Map<String, byte[]> padded = sections(bytes);
        padded.put("pose", Arrays.copyOf(padded.get("pose"), padded.get("pose").length + 1));
        rejected(join(padded), "trailing pose byte");
        Map<String, byte[]> shortInventory = sections(bytes);
        shortInventory.put("inventory", itemsBytes(new ItemStack[35]));
        rejected(join(shortInventory), "35-slot inventory");
    }

    private static void immutable() {
        ItemStack[] inventory = new ItemStack[Inventory.SIZE];
        inventory[0] = ItemStack.of("diamond", 5);
        byte[] progress = { 2, 3 };
        PlayerRecord record = PlayerRecord.builder().inventory(inventory).progress(progress).build();
        inventory[0].count = 1;
        progress[1] = 9;
        check(record.inventory()[0].count == 5 && record.progress()[1] == 3, "builder input borrowed");
        ItemStack[] out = record.inventory();
        out[0].count = 2;
        out[1] = ItemStack.of("coal");
        record.progress()[0] = 7;
        check(record.inventory()[0].count == 5 && record.inventory()[1] == null && record.progress()[0] == 2,
                "accessor output borrowed");
        var extra = sample().extraSections();
        extra.get("future:mood")[0] = 99;
        check(sample().extraSections().get("future:mood")[0] == 1, "section bytes borrowed");
        boolean readOnly = false;
        try { extra.put("x", new byte[0]); }
        catch (UnsupportedOperationException expected) { readOnly = true; }
        check(readOnly, "extra sections map is mutable");
        for (Runnable bad : new Runnable[] {
                () -> PlayerRecord.builder().inventory(new ItemStack[40]).build(),
                () -> PlayerRecord.builder().pose(Double.NaN, 0, 0, 0, 0, 0),
                () -> PlayerRecord.builder().pose(0, 0, 0, 0, 0, 9),
                () -> new PlayerRecord.Vitals(21, 20, 5, 0, 300),
                () -> new PlayerRecord.Vitals(20, 20, 5, 0, 301),
                () -> PlayerRecord.builder().advancements(List.of("a", "a")).build(),
                () -> PlayerRecord.builder().section("inventory", new byte[0]) }) {
            boolean refused = false;
            try { bad.run(); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "invalid record accepted");
        }
    }

    private static void damagedEffects() throws Exception {
        Map<String, byte[]> sections = sections(PlayerRecordCodec.encode(sample()));
        byte[] damaged = { 5, 0, 1 };
        sections.put("effects", damaged);
        PlayerRecord back = PlayerRecordCodec.decode(join(sections));
        check(back.inventory()[0].count == 17 && back.pending().length == 2, "damaged effects blocked the items");
        check(back.effects().isEmpty() && !back.warnings().isEmpty(), "damage was not reported");
        check(Arrays.equals(damaged, sections(PlayerRecordCodec.encode(back)).get("effects")),
                "damaged effects were not kept verbatim");
        check(Arrays.equals(damaged, sections(PlayerRecordCodec.encode(back.withItems(null, null))).get("effects")),
                "an unrelated change dropped the kept effects");
        PlayerRecord replaced = back.toBuilder().effects(List.of(new PlayerRecord.Effect("mineclone:speed", 5, 0))).build();
        check(replaced.warnings().isEmpty()
                && PlayerRecordCodec.decode(PlayerRecordCodec.encode(replaced)).effects().size() == 1,
                "replacing effects kept the damaged bytes");
        // Items fail closed: a guessed inventory is worse than a refused one.
        for (String itemSection : List.of("inventory", "equipment", "pending")) {
            Map<String, byte[]> broken = sections(PlayerRecordCodec.encode(sample()));
            broken.put(itemSection, new byte[] { 3, 1 });
            rejected(join(broken), "damaged " + itemSection);
        }
    }

    private static void unreadableGuest() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = UUID.randomUUID().toString();
            Map<String, byte[]> sections = sections(PlayerRecordCodec.encode(sample()));
            sections.put("inventory", new byte[] { 36, 7 });
            byte[] original = f.writeGuest(id, SaveFormat.GUEST_VERSION, SaveFormat.GUEST_VERSION, join(sections));
            // A fresh manager's first write must read the file itself and refuse.
            SaveManager writer = new SaveManager(f.saves.toFile());
            writer.saveGuestRecord("world", id, sample());
            writer.flushAndAwait();
            check(Arrays.equals(original, Files.readAllBytes(f.guest(id))), "first write replaced an unread damaged file");
            boolean refused = false;
            try { f.save.loadGuest("world", id); }
            catch (IllegalStateException expected) { refused = true; }
            check(refused, "damaged guest inventory was accepted");
            f.save.saveGuest("world", id, new PlayerData(sample()));
            f.save.flushAndAwait();
            check(Arrays.equals(original, Files.readAllBytes(f.guest(id))), "failed read did not protect the file");
        }
    }

    private static void tooNewGuest() throws Exception {
        try (Fixture f = new Fixture()) {
            String container = UUID.randomUUID().toString(), record = UUID.randomUUID().toString();
            byte[] body = PlayerRecordCodec.encode(sample());
            byte[] newerFile = f.writeGuest(container, 3, 3, body);
            Map<String, byte[]> sections = sections(body);
            sections.put("format", intBytes(PlayerRecordCodec.MAGIC, 3, 3));
            byte[] newerRecord = f.writeGuest(record, SaveFormat.GUEST_VERSION, SaveFormat.GUEST_VERSION, join(sections));
            for (String id : List.of(container, record)) {
                boolean refused = false;
                try { f.save.loadGuestRecord("world", id); }
                catch (IllegalStateException expected) { refused = true; }
                check(refused, "newer player " + id + " accepted");
                f.save.saveGuestRecord("world", id, sample());
            }
            f.save.flushAndAwait();
            check(Arrays.equals(newerFile, Files.readAllBytes(f.guest(container)))
                    && Arrays.equals(newerRecord, Files.readAllBytes(f.guest(record))), "newer player file overwritten");
        }
    }

    private static void guestMigration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.save.saveLevel("world", level(sample()));
            f.save.flushAndAwait();
            String id = UUID.randomUUID().toString();
            ItemStack[] inventory = new ItemStack[Inventory.SIZE];
            inventory[4] = ItemStack.of("iron_ingot", 9);
            var legacy = new PlayerData(inventory, new ItemStack[] { ItemStack.of("coal", 2) },
                    4.5f, 70f, -2f, 1f, .5f, 12f, 8f, 3, new byte[] { 2, 4 });
            byte[] original = f.writeGuest(id, SaveFormat.GUEST_V1, -1, legacy.bytes());

            SaveManager session = new SaveManager(f.saves.toFile());
            PlayerRecord loaded = session.loadGuestRecord("world", id);
            check(loaded.inventory()[4].count == 9 && loaded.pending()[0].count == 2 && loaded.pose().selected() == 3
                    && loaded.vitals().health() == 12f && loaded.vitals().saturation() == 5f
                    && loaded.vitals().air() == PlayerRecord.MAX_AIR && loaded.progress()[1] == 4, "v1 values");
            session.saveGuestRecord("world", id, loaded);
            session.flushAndAwait();
            try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f.guest(id))))) {
                check(in.readInt() == SaveFormat.MAGIC && in.readInt() == SaveFormat.GUEST_VERSION
                        && in.readInt() == SaveFormat.GUEST_VERSION, "rewritten guest header");
            }
            same(loaded, new SaveManager(f.saves.toFile()).loadGuestRecord("world", id), "v2 reload");
            var backups = session.listBackups("world");
            check(!backups.isEmpty(), "no backup before the first guest write");
            try (var zip = new ZipFile(backups.get(0).path().toFile());
                 var in = zip.getInputStream(zip.getEntry("players/" + id + ".dat"))) {
                check(Arrays.equals(original, in.readAllBytes()), "backup does not hold the v1 original");
            }
        }
    }

    private static void opaqueGuestSections() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = UUID.randomUUID().toString();
            Map<String, byte[]> sections = sections(PlayerRecordCodec.encode(sample()));
            sections.put("format", intBytes(PlayerRecordCodec.MAGIC, 3, PlayerRecord.VERSION));
            sections.put("future:hunger_ledger", new byte[] { 9, 9, 9 });
            f.writeGuest(id, 3, SaveFormat.GUEST_VERSION, join(sections));
            for (int i = 0; i < 5; i++) {
                SaveManager session = new SaveManager(f.saves.toFile());
                PlayerRecord record = session.loadGuestRecord("world", id);
                check(Arrays.equals(new byte[] { 9, 9, 9 }, record.extraSections().get("future:hunger_ledger"))
                        && Arrays.equals(new byte[] { 1, 2, 3 }, record.extraSections().get("future:mood")),
                        "opaque section lost after " + i + " saves");
                same(sample().toBuilder().section("future:hunger_ledger", new byte[] { 9, 9, 9 }).build(),
                        record, "cycle " + i);
                session.saveGuestRecord("world", id, record);
                session.flushAndAwait();
            }
        }
    }

    private static void hostLevel() throws Exception {
        try (Fixture f = new Fixture()) {
            PlayerRecord record = sample();
            f.save.saveLevel("world", level(record));
            LevelData data = ((LevelLoad.Loaded) f.save.readLevel("world")).data();
            same(record, data.player, "host level");
            check(data.inventory[0].count == 17 && data.px == 1.0000001 && data.selectedSlot == 7
                    && data.spawnX == 8.25, "compatibility views");
            Map<String, byte[]> level = levelSections(f.level());
            check(Arrays.equals(PlayerRecordCodec.encode(record), level.get("player")),
                    "host and guest do not share one record encoding");
            for (String gone : List.of("inventory", "pending", PlayerRecord.LEGACY_PROGRESS_SECTION))
                check(!level.containsKey(gone), "record also written as legacy " + gone);
            check(level.containsKey(PlayerRecordCodec.LEVEL_MARKER) && level.containsKey("world_spawn"),
                    "record marker or world spawn missing");
        }
    }

    /**
     * The M0 build wrote level v11 with a fixed player section and separate
     * inventory, pending and survival-progress sections.
     */
    private static void recordlessLevel() throws Exception {
        try (Fixture f = new Fixture()) {
            Map<String, byte[]> sections = new LinkedHashMap<>();
            sections.put("world", bytes(o -> { o.writeUTF("M0 world"); o.writeLong(4455); o.writeLong(777); }));
            sections.put("player", bytes(o -> {
                for (double v : new double[] { 1.5, 71, -2.5, 8.5, 80, 8.5 }) o.writeDouble(v);
                o.writeFloat(.25f); o.writeFloat(-.5f); o.writeInt(6); o.writeFloat(13f); o.writeFloat(9f);
            }));
            sections.put("clock", new WorldClock(3.0).encode());
            sections.put("rules", intBytes(GameMode.SURVIVAL.ordinal()));
            sections.put("worldgen", intBytes(1));
            ItemStack[] inventory = new ItemStack[Inventory.SIZE];
            inventory[2] = ItemStack.of("gold_ingot", 6);
            sections.put("inventory", itemsBytes(inventory));
            sections.put("pending", itemsBytes(new ItemStack[] { ItemStack.of("stick", 4) }));
            sections.put(PlayerRecord.LEGACY_PROGRESS_SECTION, new byte[] { 2, 6 });
            sections.put("future:weather", new byte[] { 4, 4 });
            Files.createDirectories(f.level().getParent());
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(f.level())))) {
                out.writeInt(SaveFormat.MAGIC); out.writeInt(11); out.writeInt(11); out.writeUTF("m0");
                SectionCodec.write(out, sections);
            }
            byte[] original = Files.readAllBytes(f.level());
            LevelData data = ((LevelLoad.Loaded) f.save.readLevel("world")).data();
            check(data.player.inventory()[2].count == 6 && data.player.pending()[0].count == 4
                    && data.player.pose().selected() == 6 && data.player.vitals().hunger() == 9f
                    && data.player.vitals().saturation() == 5f && Arrays.equals(new byte[] { 2, 6 }, data.player.progress()),
                    "fixed payload was not assembled into the record");
            check(data.spawnZ == 8.5 && data.extraSections.containsKey("future:weather"), "world fields");
            f.save.saveLevel("world", data);
            f.save.flushAndAwait();
            var backups = f.save.listBackups("world");
            check(backups.size() == 1 && backups.get(0).migration(), "no migration backup before the record write");
            try (var zip = new ZipFile(backups.get(0).path().toFile()); var in = zip.getInputStream(zip.getEntry("level.dat"))) {
                check(Arrays.equals(original, in.readAllBytes()), "migration backup lost the original bytes");
            }
            Map<String, byte[]> rewritten = levelSections(f.level());
            check(rewritten.containsKey(PlayerRecordCodec.LEVEL_MARKER) && !rewritten.containsKey("inventory")
                    && !rewritten.containsKey(PlayerRecord.LEGACY_PROGRESS_SECTION)
                    && Arrays.equals(new byte[] { 4, 4 }, rewritten.get("future:weather")), "rewritten sections");
            LevelData again = ((LevelLoad.Loaded) new SaveManager(f.saves.toFile()).readLevel("world")).data();
            same(data.player, again.player, "record after migration");
        }
    }

    private static void mergeLegacy() {
        PlayerRecord base = sample();
        ItemStack[] inventory = new ItemStack[Inventory.SIZE];
        inventory[8] = ItemStack.of("coal", 3);
        var incoming = new PlayerData(inventory, null, 5f, 70f, 6f, 1f, .5f, 12f, 9f, 2, new byte[] { 2, 7 });
        PlayerRecord merged = base.mergeLegacy(incoming);
        check(merged.inventory()[8].count == 3 && merged.inventory()[0] == null && merged.pending().length == 0,
                "carried items not replaced");
        check(merged.pose().equals(new PlayerRecord.Pose(5, 70, 6, 1f, .5f, 2)), "carried pose");
        check(merged.vitals().health() == 12f && merged.vitals().hunger() == 9f, "carried vitals");
        check(merged.vitals().saturation() == base.vitals().saturation()
                && merged.vitals().exhaustion() == base.vitals().exhaustion()
                && merged.vitals().air() == base.vitals().air(), "uncarried vitals were reset");
        check(Arrays.equals(new byte[] { 2, 7 }, merged.progress()), "carried progress");
        sameItems(base.equipment(), merged.equipment(), "equipment");
        check(merged.spawn().equals(base.spawn()) && merged.effects().equals(base.effects())
                && merged.advancements().equals(base.advancements()) && merged.recipes().equals(base.recipes())
                && Arrays.equals(base.extraSections().get("future:mood"), merged.extraSections().get("future:mood")),
                "what v7 cannot carry was lost");
        PlayerRecord unchanged = base.mergeLegacy(new PlayerData(base));
        check(unchanged.pose().x() == 1.0000001, "a float round trip drifted the saved double");
    }

    private static void liveVitals() {
        var vitals = PlayerRecord.Vitals.live(25f, Float.NaN, -1f, 9f, Float.POSITIVE_INFINITY);
        check(vitals.health() == 20f && vitals.hunger() == 20f && vitals.saturation() == 0f
                && vitals.exhaustion() == PlayerRecord.MAX_EXHAUSTION && vitals.air() == PlayerRecord.MAX_AIR,
                "live vitals " + vitals);
        var legacy = PlayerRecord.Vitals.legacy(20f, 3f);
        check(legacy.saturation() == 3f && legacy.air() == PlayerRecord.MAX_AIR, "legacy saturation above hunger");
    }

    // ---------------------------------------------------------------- helpers

    private static LevelData level(PlayerRecord record) {
        return new LevelData("Record world", 99, 8.25, 80, 8.75, 1.5f, GameMode.SURVIVAL, 1234, record, Map.of());
    }

    private static void rejected(byte[] recordBytes, String what) {
        try {
            PlayerRecordCodec.decode(recordBytes);
        } catch (java.io.IOException expected) {
            return;
        }
        throw new AssertionError(what + " was accepted");
    }

    private static Map<String, byte[]> sections(byte[] recordBytes) throws Exception {
        return new LinkedHashMap<>(SectionCodec.read(new DataInputStream(new ByteArrayInputStream(recordBytes))));
    }

    private static byte[] join(Map<String, byte[]> sections) throws Exception {
        return bytes(o -> SectionCodec.write(o, sections));
    }

    private static Map<String, byte[]> levelSections(Path level) throws Exception {
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(level)))) {
            in.readInt(); in.readInt(); in.readInt(); in.readUTF();
            return SectionCodec.read(in);
        }
    }

    private static byte[] itemsBytes(ItemStack[] items) throws Exception {
        return bytes(o -> {
            com.mineclone.data.VarInt.write(o, items.length);
            for (ItemStack s : items) com.mineclone.save.ItemStackCodec.write(o, s);
        });
    }

    private static byte[] intBytes(int... values) throws Exception {
        return bytes(o -> { for (int v : values) o.writeInt(v); });
    }

    @FunctionalInterface private interface Body { void write(DataOutputStream out) throws Exception; }

    private static byte[] bytes(Body body) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { body.write(out); }
        return bytes.toByteArray();
    }

    private static final class Fixture implements AutoCloseable {
        final Path saves = Files.createTempDirectory("mineclone-player-record-");
        final SaveManager save = new SaveManager(saves.toFile());

        Fixture() throws Exception {}

        Path level() { return saves.resolve("world/level.dat"); }
        Path guest(String id) { return saves.resolve("world/players/" + id + ".dat"); }

        /** A guest file as a given version wrote it; a negative minimum reader is omitted (v1). */
        byte[] writeGuest(String id, int version, int minReader, byte[] body) throws Exception {
            Files.createDirectories(guest(id).getParent());
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(guest(id))))) {
                out.writeInt(SaveFormat.MAGIC);
                out.writeInt(version);
                if (minReader >= 0) out.writeInt(minReader);
                out.write(body);
            }
            return Files.readAllBytes(guest(id));
        }

        @Override
        public void close() throws Exception {
            save.flushAndAwait();
            try (var paths = Files.walk(saves)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
