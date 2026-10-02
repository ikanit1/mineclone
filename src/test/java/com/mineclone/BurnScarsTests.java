package com.mineclone;

import com.mineclone.sim.WorldClock;
import com.mineclone.sim.ecology.BurnScars;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.world.Biome;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.behavior.DropSink;
import com.mineclone.world.behavior.SaplingBehavior;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Bounded fire records, protected opaque bytes and actual loaded daily terrain recovery. */
public final class BurnScarsTests {
    private static final int Y = 32;
    public static void runAll(TestMain.Runner r) {
        r.run("burn scar records preserve exact ground, full-width days and unknown suffixes", BurnScarsTests::roundtrip);
        r.run("known future and malformed burn scars stay distinct from historical unknown records", BurnScarsTests::invalidRecords);
        r.run("burn scar restore refuses duplicate ground ownership and wrong regions", BurnScarsTests::ownership);
        r.run("burn scar slots and suffix bounds refuse excess history before ground changes", BurnScarsTests::bounds);
        r.run("burn scar greenery and damaged ground recover on the documented absolute days", BurnScarsTests::habitat);
        r.run("daily burn recovery and advancement share terrain, planted flags and scheduled dues", BurnScarsTests::dailyParity);
        r.run("absent and protected scar terrain keeps its cursor until a writable arrival", BurnScarsTests::arrival);
        r.run("scar recovery touches only recorded dirt and never clears player obstacles", BurnScarsTests::ownCells);
        r.run("each burned ground cell plants once and a new fire resets only that cell", BurnScarsTests::plantOnce);
        r.run("healed dense scar records can free a bounded slot while opaque and blocked histories survive", BurnScarsTests::reclaim);
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void damaged(Action action) throws Exception {
        try { action.run(); throw new AssertionError("damaged scar was accepted"); }
        catch (BurnScars.NewerVersionException wrong) { throw new AssertionError("damage was called newer", wrong); }
        catch (IOException expected) {}
    }
    private static byte[] concat(byte[] first, byte[] tail) {
        byte[] result = Arrays.copyOf(first, first.length + tail.length); System.arraycopy(tail, 0, result, first.length, tail.length); return result;
    }

    private static final class Fixture {
        final World world;
        final WorldClock clock = new WorldClock(Math.PI / 2);
        final BurnScars scars;
        final EcologyRegion region = new EcologyRegion(0, 0, 0);
        Fixture(long seed) {
            world = new World(seed);
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
                Chunk c = land(cx, cz); world.publish(c);
            }
            world.simulate(DropSink.NONE, 0); world.setLivingWorldEnabled(true);
            scars = new BurnScars(world, clock);
        }
        void burn(int x, int z, long day) {
            check(scars.burned(region, x, Y, z, day), "fixture scar registration refused"); world.setBlock(x, Y, z, BlockType.DIRT);
        }
        void day(int day) {
            clock.addHours(24); scars.stepDay(List.of(region), day);
        }
        byte[] bytes() throws Exception { scars.flush(List.of(region)); return EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of())); }
    }
    private static Chunk land(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) c.set(x, Y, z, BlockType.GRASS);
        c.computeSkyLight(); return c;
    }
    private static long forestSeed() {
        for (long seed = 1; seed < 1000; seed++) if (new World(seed).biomes.biomeAt(8, 8).treeType == Biome.TreeType.OAK) return seed;
        throw new AssertionError("no bounded forest seed found");
    }
    private static byte[] record() throws Exception {
        Fixture f = new Fixture(forestSeed()); f.burn(8, 8, 0); return f.scars.scars(f.region.key()).get(0).encode();
    }

    private static void roundtrip() throws Exception {
        Fixture f = new Fixture(forestSeed()); long large = (1L << 40) + 17;
        f.burn(8, 8, large); f.burn(12, 12, large + 1);
        byte[] suffix = {9, 7, 0, 3}; byte[] raw = concat(f.scars.scars(f.region.key()).get(0).encode(), suffix);
        BurnScars.Scar scar = BurnScars.Scar.decode(raw);
        check(scar.cx() == 0 && scar.cz() == 0 && scar.lastProcessedDay() == large
                && scar.sites().get(1).burnDay() == large + 1, "scar location or full-width day lost");
        check(Arrays.equals(scar.encode(), raw), "known record changed on roundtrip");
        scar.tail()[0] = 0;
        try { scar.sites().clear(); throw new AssertionError("mutable scar sites"); } catch (UnsupportedOperationException expected) {}
        byte[] unknown = {1, 5, 9}; f.region.scars(List.of(raw, unknown)); f.scars.restore(List.of(f.region));
        f.clock.setGameTime(large * Math.PI * 2); f.scars.tickLoaded(List.of(f.region), large + 2); f.scars.flush(List.of(f.region));
        check(Arrays.equals(BurnScars.Scar.decode(f.region.scars().get(0)).tail(), suffix)
                && Arrays.equals(f.region.scars().get(1), unknown), "simulation dropped opaque history");
        byte[] ecology = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(f.region), Map.of(), new byte[]{7}));
        for (int cycle = 0; cycle < 5; cycle++) ecology = EcologyCodec.encode(EcologyCodec.decode(ecology));
        var regions = EcologyCodec.decode(ecology).regions();
        check(Arrays.equals(regions.get(0).scars().get(0), f.region.scars().get(0))
                && Arrays.equals(regions.get(0).scars().get(1), unknown), "outer codec reinterpreted scar blobs");
    }

    private static void invalidRecords() throws Exception {
        check(BurnScars.Scar.decode(null) == null && BurnScars.Scar.decode(new byte[]{1, 2, 3}) == null
                && BurnScars.Scar.decode(new byte[]{0, 1, 2, 3}) == null, "unknown magic must stay opaque");
        byte[] raw = record(); byte[] future = Arrays.copyOf(raw, 5); future[4] = 2;
        try { BurnScars.Scar.decode(future); throw new AssertionError("future scar accepted"); }
        catch (BurnScars.NewerVersionException expected) { check(expected.version() == 2, "future version lost"); }
        damaged(() -> BurnScars.Scar.decode(Arrays.copyOf(raw, 4)));
        damaged(() -> BurnScars.Scar.decode(Arrays.copyOf(raw, 27)));
        byte[] zero = raw.clone(); zero[4] = 0; damaged(() -> BurnScars.Scar.decode(zero));
        byte[] empty = raw.clone(); empty[21] = 0; damaged(() -> BurnScars.Scar.decode(empty));
        byte[] tooMany = raw.clone(); tooMany[21] = 65; damaged(() -> BurnScars.Scar.decode(tooMany));
        byte[] negative = raw.clone(); ByteBuffer.wrap(negative, 24, 8).putLong(-1); damaged(() -> BurnScars.Scar.decode(negative));
        byte[] negativeCursor = raw.clone(); ByteBuffer.wrap(negativeCursor, 13, 8).putLong(-1); damaged(() -> BurnScars.Scar.decode(negativeCursor));
        byte[] topGround = raw.clone(); ByteBuffer.wrap(topGround, 22, 2).putShort((short) Chunk.idx(8, 127, 8));
        damaged(() -> BurnScars.Scar.decode(topGround));
        damaged(() -> BurnScars.Scar.decode(Arrays.copyOf(raw, 1025)));
        byte[] duplicate = Arrays.copyOf(raw, raw.length + 10); duplicate[21] = 2;
        System.arraycopy(raw, 22, duplicate, 32, 10); damaged(() -> BurnScars.Scar.decode(duplicate));
    }

    private static void ownership() throws Exception {
        Fixture f = new Fixture(forestSeed()); byte[] raw = record();
        f.region.scars(List.of(raw, raw)); damaged(() -> f.scars.restore(List.of(f.region)));
        EcologyRegion wrong = new EcologyRegion(-1, 0, 0); wrong.scars(List.of(raw)); damaged(() -> f.scars.restore(List.of(wrong)));
        byte[] other = raw.clone(); ByteBuffer.wrap(other, 5, 4).putInt(Integer.MAX_VALUE);
        f.region.scars(List.of(other)); damaged(() -> f.scars.restore(List.of(f.region)));
    }

    private static void bounds() throws Exception {
        Fixture f = new Fixture(forestSeed());
        for (int n = 0; n < 65; n++) f.burn(n % 16, n / 16, 0);
        check(f.scars.scars(f.region.key()).size() == 2 && f.scars.scars(f.region.key()).get(0).sites().size() == 64,
                "per-slot site bound ignored");
        Fixture suffix = new Fixture(forestSeed()); suffix.burn(8, 8, 0);
        byte[] raw = suffix.scars.scars(suffix.region.key()).get(0).encode();
        byte[] full = Arrays.copyOf(raw, 1024); Arrays.fill(full, raw.length, full.length, (byte) 73);
        List<byte[]> slots = new ArrayList<>(); slots.add(full);
        for (int n = 1; n < 16; n++) slots.add(new byte[]{(byte) n});
        suffix.region.scars(slots); suffix.scars.restore(List.of(suffix.region));
        check(!suffix.scars.burned(suffix.region, 9, Y, 8, 0) && suffix.world.getBlock(9, Y, 8) == BlockType.GRASS,
                "full history accepted a ground change");
        check(suffix.scars.burned(suffix.region, 8, Y, 8, 1), "same-size reburn rejected full suffix");
        suffix.scars.flush(List.of(suffix.region));
        check(suffix.region.scars().get(0).length == 1024 && suffix.region.scars().size() == 16
                && BurnScars.Scar.decode(suffix.region.scars().get(0)).tail()[0] == 73, "reburn lost full opaque suffix");
    }

    private static void habitat() throws Exception {
        Fixture f = new Fixture(forestSeed());
        check(f.scars.greeneryCeiling(0, 0, 0) == 255, "untouched habitat reduced");
        f.burn(8, 8, 0);
        check(f.scars.revision() > 0, "registration did not change its revision");
        check(f.scars.greeneryCeiling(0, 0, 0) == 239 && f.scars.damagedAt(8, Y, 8, 0)
                && !f.scars.damagedAt(9, Y, 8, 0) && f.scars.knownAt(8, Y, 8), "exact scar habitat lost");
        int previous = 0;
        for (int day = 0; day <= 7; day++) {
            int value = f.scars.greeneryCeiling(0, 0, day); check(value >= previous, "habitat failed to recover monotonically"); previous = value;
        }
        check(previous == 255 && !f.scars.damagedAt(8, Y, 8, 3), "recovery days differ from tuning");
        for (int n = 0; n < 20; n++) f.burn(n % 16, n / 16, 0);
        check(f.scars.greeneryCeiling(0, 0, 0) == 32 && f.scars.greeneryCeiling(1, 0, 0) == 255,
                "canopy loss not bounded to its chunk");
    }

    private static void dailyParity() throws Exception {
        Fixture daily = new Fixture(forestSeed()), replay = new Fixture(forestSeed());
        for (int x : new int[]{4, 8, 12}) for (int z : new int[]{4, 8, 12}) { daily.burn(x, z, 0); replay.burn(x, z, 0); }
        for (int day = 1; day <= 7; day++) daily.day(day);
        replay.clock.addHours(24 * 7); replay.scars.tickLoaded(List.of(replay.region), 7);
        check(Arrays.equals(daily.bytes(), replay.bytes()), "ordinary and replay scar records differ");
        for (Chunk c : daily.world.getLoadedChunks()) {
            Chunk other = replay.world.getChunkIfExists(c.cx, c.cz);
            check(Arrays.equals(c.copyBlocks(), other.copyBlocks()) && Arrays.equals(c.copyMeta(), other.copyMeta()), "replay terrain differs");
            check(Arrays.equals(c.scheduledTicks().entries(), other.scheduledTicks().entries()), "replay due times differ: daily="
                    + Arrays.toString(c.scheduledTicks().entries()) + " replay=" + Arrays.toString(other.scheduledTicks().entries()));
        }
        check(daily.scars.scars(daily.region.key()).get(0).sites().stream().anyMatch(BurnScars.Site::plantedOnce),
                "deterministic fixture never planted a natural sapling");
        byte[] before = daily.bytes(); daily.scars.tickLoaded(List.of(daily.region), 7); daily.scars.stepDay(List.of(daily.region), 6);
        check(Arrays.equals(before, daily.bytes()), "repeated or old day rerolled recovery");

        // A seed planted on day three can already have its full crown before
        // the next ordinary day. Advancement must reserve that future crown
        // while the dispatcher still sees only the seedling.
        Fixture grownDaily = new Fixture(forestSeed()), grownReplay = new Fixture(forestSeed());
        for (int x : new int[]{4, 8, 12}) for (int z : new int[]{4, 8, 12}) {
            grownDaily.burn(x, z, 0); grownReplay.burn(x, z, 0);
        }
        for (int day = 1; day <= 7; day++) {
            grownDaily.clock.addHours(24);
            grownDaily.world.runScheduledTicks(grownDaily.clock.worldTicks(), 512, 2 * WorldClock.TICKS_PER_DAY);
            grownDaily.scars.stepDay(List.of(grownDaily.region), day);
            grownDaily.world.runScheduledTicks(grownDaily.clock.worldTicks(), 512, 2 * WorldClock.TICKS_PER_DAY);
        }
        grownReplay.clock.addHours(24 * 7); grownReplay.scars.stepDay(List.of(grownReplay.region), 7);
        grownReplay.world.runScheduledTicks(grownReplay.clock.worldTicks(), 512, 2 * WorldClock.TICKS_PER_DAY);
        check(Arrays.equals(grownDaily.bytes(), grownReplay.bytes()), "grown crown changed replay planted flags");
        boolean youngTrees = false;
        for (Chunk c : grownDaily.world.getLoadedChunks()) {
            Chunk other = grownReplay.world.getChunkIfExists(c.cx, c.cz);
            check(Arrays.equals(c.copyBlocks(), other.copyBlocks()) && Arrays.equals(c.copyMeta(), other.copyMeta()),
                    "actual ordinary and replay trees differ after growth");
            check(Arrays.equals(c.scheduledTicks().entries(), other.scheduledTicks().entries()), "grown tree replay left a different pending seed");
            for (byte block : c.copyBlocks()) youngTrees |= (block & 255) == BlockType.WOOD.ordinal();
        }
        check(youngTrees, "actual parity fixture never grew a tree");
    }

    private static void arrival() throws Exception {
        Fixture f = new Fixture(forestSeed()); f.burn(8, 8, 0); Chunk saved = f.world.removeChunk(0, 0);
        f.clock.addHours(24 * 7); f.scars.tickLoaded(List.of(f.region), 7);
        check(f.scars.scars(f.region.key()).get(0).lastProcessedDay() == 0, "absent record advanced its cursor");
        check(!f.scars.burned(f.region, 9, Y, 8, 7), "unloaded ground was registered");
        f.world.publish(saved); saved.markReadOnly(); byte[] terrain = saved.copyBlocks();
        f.scars.tickLoaded(List.of(f.region), 7);
        check(f.scars.scars(f.region.key()).get(0).lastProcessedDay() == 0 && Arrays.equals(terrain, saved.copyBlocks()),
                "protected arrival was changed or consumed its days");
        check(!f.scars.burned(f.region, 9, Y, 8, 7), "protected ground was registered");
        f.world.removeChunk(0, 0); Chunk writable = land(0, 0); writable.set(8, Y, 8, BlockType.DIRT); f.world.publish(writable);
        f.scars.tickLoaded(List.of(f.region), 7);
        check(f.scars.scars(f.region.key()).get(0).lastProcessedDay() == 7 && f.world.getBlock(8, Y, 8) == BlockType.GRASS,
                "writable arrival did not recover missed grass stages");
        Fixture huge = new Fixture(forestSeed()); huge.burn(8, 8, 0); huge.scars.tickLoaded(List.of(huge.region), Long.MAX_VALUE);
        check(huge.scars.scars(huge.region.key()).get(0).lastProcessedDay() == Long.MAX_VALUE, "bounded large replay overflowed");
    }

    private static void ownCells() throws Exception {
        Fixture f = new Fixture(forestSeed()); f.burn(4, 4, 0); f.burn(8, 8, 0); f.burn(12, 12, 0);
        f.burn(4, 12, 0);
        for (int n = 0; n < 16; n++) f.world.getChunkIfExists(0, 0).trample(0).press(Chunk.idx(4, Y, 12));
        f.world.setBlock(4, Y + 1, 4, BlockType.ASH); f.world.setBlock(8, Y, 8, BlockType.STONE);
        f.world.setBlock(12, Y + 4, 12, BlockType.PLANKS); f.world.setBlock(5, Y, 5, BlockType.DIRT);
        f.clock.addHours(24 * 7); f.scars.tickLoaded(List.of(f.region), 7);
        check(f.world.getBlock(4, Y, 4) == BlockType.DIRT && f.world.getBlock(4, Y + 1, 4) == BlockType.ASH,
                "scar recovery removed ash without rain");
        check(f.world.getBlock(8, Y, 8) == BlockType.STONE && f.world.getBlock(12, Y + 4, 12) == BlockType.PLANKS
                && f.world.getBlock(12, Y, 12) == BlockType.DIRT && f.world.getBlock(5, Y, 5) == BlockType.DIRT,
                "scar rule overwrote a roof, replacement or foreign dirt");
        check(f.world.getBlock(4, Y, 12) == BlockType.DIRT && f.world.getBlock(4, Y + 1, 12) == BlockType.AIR,
                "scar rule planted or reclaimed an active ground path");
        check(f.scars.scars(f.region.key()).get(0).sites().stream().noneMatch(BurnScars.Site::plantedOnce), "blocked site was called planted");
        Fixture occupied = new Fixture(forestSeed()); occupied.burn(8, 8, 0);
        occupied.world.setNaturalGrowthSpace((x, y, z) -> x != 8 || y != Y + 1 || z != 8);
        occupied.clock.addHours(24 * 60); occupied.scars.tickLoaded(List.of(occupied.region), 60);
        check(occupied.world.getBlock(8, Y, 8) == BlockType.GRASS && occupied.world.getBlock(8, Y + 1, 8) == BlockType.AIR
                && !occupied.scars.scars(occupied.region.key()).get(0).sites().get(0).plantedOnce(),
                "occupied natural crown consumed its one planting chance");
    }

    private static void plantOnce() throws Exception {
        Fixture f = new Fixture(forestSeed()); f.burn(8, 8, 0); int born = -1;
        for (int day = 1; day <= 60; day++) {
            f.day(day);
            if (f.world.getBlock(8, Y + 1, 8) == BlockType.SAPLING) { born = day; break; }
        }
        check(born >= 3 && (f.world.getBlockMeta(8, Y + 1, 8) & SaplingBehavior.NATURAL_SCAR) != 0, "natural scar never planted");
        check(f.scars.scars(f.region.key()).get(0).sites().get(0).plantedOnce(), "plant flag not saved");
        f.world.setBlock(8, Y + 1, 8, BlockType.AIR);
        f.clock.addHours(24 * 60); f.scars.tickLoaded(List.of(f.region), born + 60);
        check(f.world.getBlock(8, Y + 1, 8) == BlockType.AIR, "removed seedling recreated from the same scar");
        f.burn(8, 8, born + 60);
        check(!f.scars.scars(f.region.key()).get(0).sites().get(0).plantedOnce()
                && f.scars.damagedAt(8, Y, 8, born + 60), "new fire did not reset only its own history");
    }

    private static void reclaim() throws Exception {
        Fixture f = new Fixture(forestSeed()); byte[] raw = record(); raw[22] |= (byte) 0x80;
        List<byte[]> slots = new ArrayList<>(); slots.add(raw);
        for (int n = 1; n < 16; n++) slots.add(new byte[]{(byte) n});
        f.region.scars(slots); f.scars.restore(List.of(f.region));
        // The existing typed slot can receive sites, so move the new event to a different loaded chunk.
        check(f.scars.burned(f.region, 20, Y, 8, 7), "completed planted record did not free its slot");
        f.scars.flush(List.of(f.region)); check(f.region.scars().size() == 16 && !f.scars.knownAt(8, Y, 8), "healed slot not reclaimed");
        for (int n = 1; n < 16; n++) check(Arrays.equals(f.region.scars().get(n - 1), new byte[]{(byte) n}), "reclaim erased unknown history");
        byte[] withTail = concat(raw, new byte[]{7}); slots.set(0, withTail); f.region.scars(slots); f.scars.restore(List.of(f.region));
        check(!f.scars.burned(f.region, 20, Y, 8, 7), "healed record with unknown suffix was discarded");

        Fixture dense = new Fixture(forestSeed());
        for (int cx = 2; cx < 16; cx++) dense.world.publish(land(cx, 0));
        for (int x = 4; x < 12; x++) for (int z = 4; z < 12; z++) dense.burn(x, z, 0);
        for (int cx = 1; cx < 16; cx++) dense.burn(cx * 16 + 8, 8, 0);
        check(dense.scars.scars(dense.region.key()).size() == 16, "dense fixture did not fill 16 typed histories");
        dense.clock.addHours(24 * 7); dense.scars.tickLoaded(List.of(dense.region), 7);
        var denseSites = dense.scars.scars(dense.region.key()).get(0).sites();
        check(denseSites.stream().anyMatch(BurnScars.Site::plantedOnce)
                && denseSites.stream().anyMatch(site -> !site.plantedOnce()), "dense spacing did not leave healed cells without their own trees");
        check(dense.scars.burned(dense.region, 8, Y, 24, 7) && !dense.scars.knownAt(4, Y, 4),
                "visibly healed dense history blocked the 17th chunk's fire forever");

        Fixture blocked = new Fixture(forestSeed()); blocked.region.scars(slots); blocked.scars.restore(List.of(blocked.region));
        blocked.world.setBlock(8, Y, 8, BlockType.STONE);
        // Remove the suffix so ground replacement alone must protect the known record.
        slots.set(0, raw); blocked.region.scars(slots); blocked.scars.restore(List.of(blocked.region));
        check(!blocked.scars.burned(blocked.region, 20, Y, 8, 7), "player stone was called recovered grass");
        blocked.world.setBlock(8, Y, 8, BlockType.DIRT);
        check(!blocked.scars.burned(blocked.region, 20, Y, 8, 7), "blocked bare dirt history was retired");
    }
}
