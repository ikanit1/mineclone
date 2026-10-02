package com.mineclone;

import com.mineclone.data.ResourceId;
import com.mineclone.data.SectionCodec;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** LW-02: level snapshots keep unknown wildlife and reservations without accepting damaged bounds. */
public final class EcologyCodecTests {
    public static void runAll(TestMain.Runner r) {
        r.run("ecology roundtrip preserves counts, reservations, unknown species and future records", EcologyCodecTests::roundtrip);
        r.run("ecology snapshots detach mutable region state and payload arrays", EcologyCodecTests::detached);
        r.run("missing ecology starts empty and newer layout refuses interpretation", EcologyCodecTests::missingAndFuture);
        r.run("truncated, oversized, duplicate and orphan ecology records are refused", EcologyCodecTests::bounds);
        r.run("ecology region and global species limits are enforced", EcologyCodecTests::limits);
        r.run("ecology reservations preserve full-width world ids", EcologyCodecTests::longIds);
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static EcologyRegion sample() {
        var region = new EcologyRegion(-1, 2, 12345); ResourceId unknown = ResourceId.of("mod:fox");
        region.population(unknown, 37); region.capacity(unknown, 50); region.foodBonus(unknown, 5); region.protectedCount(unknown, 2);
        region.trend(unknown, new int[] { -3, 2, 7 }); byte[] greenery = new byte[256]; Arrays.fill(greenery, (byte) 91); region.greenery(greenery);
        region.lairs(List.of(new byte[] { 1, 3, 5 })); region.scars(List.of(new byte[] { 2, 4, 6 })); region.tail(new byte[] { 9, 11 }); return region;
    }
    private static void roundtrip() throws Exception {
        var region = sample(); var reservation = new EcologyCodec.Reservation(42, -1, 2, ResourceId.of("mod:fox"));
        region.lossCount(EcologyRegion.LossCause.PLAYER, 9); region.addLoss(EcologyRegion.LossCause.PREDATOR, 3);
        region.addLoss(EcologyRegion.LossCause.FIRE); region.lossCount(EcologyRegion.LossCause.OTHER, 2);
        byte[] bytes = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of(42L, reservation), new byte[] { 12, 13 }));
        var restored = EcologyCodec.decode(bytes);
        check(Arrays.equals(bytes, EcologyCodec.encode(restored)), "opaque ecology roundtrip changed bytes");
        check(restored.regions().get(0).population(ResourceId.of("mod:fox")) == 37, "unknown count disappeared");
        check(restored.reservations().get(42L).equals(reservation), "saved-body reservation disappeared");
        check(restored.regions().get(0).lossCount(EcologyRegion.LossCause.PLAYER) == 9
                && restored.regions().get(0).lossCount(EcologyRegion.LossCause.PREDATOR) == 3
                && restored.regions().get(0).lossCount(EcologyRegion.LossCause.FIRE) == 1
                && restored.regions().get(0).lossCount(EcologyRegion.LossCause.OTHER) == 2, "death reasons disappeared");
        check(Arrays.equals(restored.tail(), new byte[] { 12, 13 }), "global opaque tail disappeared");
        for (int cycle = 0; cycle < 5; cycle++) restored = EcologyCodec.decode(EcologyCodec.encode(restored));
        check(Arrays.equals(bytes, EcologyCodec.encode(restored)), "unknown data changed over repeated writes");
    }
    private static void detached() throws Exception {
        var region = sample(); byte[] tail = { 1, 2 };
        var snapshot = new EcologyCodec.Snapshot(List.of(region), Map.of(), tail); byte[] before = EcologyCodec.encode(snapshot);
        region.population(ResourceId.of("mod:fox"), 1); tail[0] = 99;
        snapshot.regions().get(0).population(ResourceId.of("mod:fox"), 2); snapshot.tail()[0] = 88;
        check(Arrays.equals(before, EcologyCodec.encode(snapshot)), "snapshot shared mutable world bytes");
        byte[] greenery = sample().greenery(); greenery[0] = 0;
        check((sample().greenery()[0] & 255) == 91, "region exposes mutable greenery");
    }
    private static void longIds() throws Exception {
        var region = sample(); var species = ResourceId.of("mod:fox");
        long id = (1L << 40) + 17;
        var reservation = new EcologyCodec.Reservation(id, -1, 2, species);
        byte[] bytes = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of(id, reservation)));
        check(EcologyCodec.decode(bytes).reservations().get(id).equals(reservation), "long mob id was truncated");
    }
    private static void missingAndFuture() throws Exception {
        check(EcologyCodec.decode(null).regions().isEmpty(), "missing section must be lazy");
        byte[] bytes = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(sample()), Map.of()));
        ByteBuffer.wrap(bytes).putInt(2).putInt(2); byte[] original = bytes.clone();
        try { EcologyCodec.decode(bytes); throw new AssertionError("newer ecology interpreted"); }
        catch (EcologyCodec.NewerVersionException expected) { check(expected.version() == 2, "newer version missing"); }
        check(Arrays.equals(bytes, original), "future bytes were modified");
    }
    private static void bounds() throws Exception {
        byte[] valid = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(sample()), Map.of()));
        for (int n : new int[] { 0, 4, 8, valid.length - 1 }) rejected(Arrays.copyOf(valid, n));
        rejected(new byte[SectionCodec.MAX_SECTION_BYTES + 1]);
        byte[] bad = valid.clone(); ByteBuffer.wrap(bad).putInt(8, 17); rejected(bad);
        bad = Arrays.copyOf(valid, valid.length + 1); rejected(bad);
        var duplicate = new EcologyCodec.Snapshot(List.of(sample(), sample()), Map.of());
        try { EcologyCodec.encode(duplicate); throw new AssertionError("duplicate regions accepted"); } catch (IOException expected) {}
        var orphan = new EcologyCodec.Reservation(7, 4, 5, ResourceId.of("rabbit"));
        try { EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(sample()), Map.of(7L, orphan))); throw new AssertionError("orphan accepted"); } catch (IOException expected) {}
        var body = new EcologyCodec.Reservation(7, -1, 2, ResourceId.of("rabbit"));
        try { EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(sample()), Map.of(7L, body))); throw new AssertionError("body with zero population accepted"); } catch (IOException expected) {}
        var protectedRegion = sample(); protectedRegion.protectedCount(ResourceId.of("mod:fox"), 38);
        try { EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(protectedRegion), Map.of())); throw new AssertionError("impossible protected population accepted"); } catch (IOException expected) {}
    }
    private static void limits() throws Exception {
        var regions = new java.util.ArrayList<EcologyRegion>();
        for (int i = 0; i <= EcologyCodec.MAX_REGIONS; i++) regions.add(new EcologyRegion(i, 0, 0));
        try { EcologyCodec.encode(new EcologyCodec.Snapshot(regions, Map.of())); throw new AssertionError("region limit accepted"); } catch (IOException expected) {}
        var region = new EcologyRegion(0, 0, 0);
        for (int i = 0; i < 17; i++) region.population(ResourceId.of("mod:animal" + i), 1);
        try { EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of())); throw new AssertionError("species limit accepted"); } catch (IOException expected) {}
        try { sample().lairs(List.of(new byte[0], new byte[0], new byte[0], new byte[0], new byte[0])); throw new AssertionError("lair limit accepted"); } catch (IllegalArgumentException expected) {}
        try { sample().scars(List.of(new byte[1025])); throw new AssertionError("scar payload limit accepted"); } catch (IllegalArgumentException expected) {}
        var reservations = new java.util.LinkedHashMap<Long, EcologyCodec.Reservation>();
        var manyBodies = new EcologyRegion(0, 0, 0); ResourceId rabbits = ResourceId.of("rabbit");
        manyBodies.population(rabbits, EcologyCodec.MAX_RESERVATIONS + 1);
        for (long i = 1; i <= EcologyCodec.MAX_RESERVATIONS + 1; i++)
            reservations.put(i, new EcologyCodec.Reservation(i, 0, 0, rabbits));
        try { EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(manyBodies), reservations)); throw new AssertionError("reservation limit accepted"); } catch (IOException expected) {}
    }
    private static void rejected(byte[] bytes) throws Exception {
        try { EcologyCodec.decode(bytes); throw new AssertionError("damaged ecology accepted"); } catch (IOException expected) {}
    }
}
