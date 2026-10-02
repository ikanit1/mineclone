package com.mineclone.sim.ecology;

import com.mineclone.sim.Participant;
import com.mineclone.sim.Participants;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.EntityPhysics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Daily, deterministic founding decisions over saved candidates in already loaded terrain. */
public final class Colonizer {
    public static final int MAX_TRAILS = 256, TRAIL_DAYS = 3;
    public record Spot(int x, int y, int z) {}
    private record Trail(float x, float y, float z, long day) {}
    private record Candidate(Chunk chunk, int cell, Spot spot, long score) {}
    private final Trail[] trails = new Trail[MAX_TRAILS];
    private int trailCount, nextTrail;

    /** Feet samples are transient; a new session begins observing paths again. */
    public void recordTrail(float x, float y, float z, long day) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || day < 0)
            throw new IllegalArgumentException("invalid colony trail sample");
        if (trailCount > 0) {
            Trail last = trails[(nextTrail + MAX_TRAILS - 1) % MAX_TRAILS];
            if (last.day == day && distance(x, y, z, last.x, last.y - Participant.EYE_HEIGHT, last.z) < 16) return;
        }
        trails[nextTrail] = new Trail(x, y + Participant.EYE_HEIGHT, z, day);
        nextTrail = (nextTrail + 1) % MAX_TRAILS;
        trailCount = Math.min(MAX_TRAILS, trailCount + 1);
    }

    public Spot select(World world, EcologyRegion region, List<Lair> existing, Participants participants, long day) {
        return select(world, region, existing, participants, day, false);
    }

    /** Initial founding may bypass the opportunity delay, but never habitat or exclusion rules. */
    public Spot select(World world, EcologyRegion region, List<Lair> existing, Participants participants,
                       long day, boolean founding) {
        if (day < 0) throw new IllegalArgumentException("negative colony day");
        long seed = LairSpots.mix(world.seed ^ region.key() ^ 0x434f4c4f4e595f31L);
        if (!founding && !opportunity(seed, existing, day)) return null;
        var candidates = new ArrayList<Candidate>();
        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunk.isReadOnly() || Math.floorDiv(chunk.cx, 16) != region.x() || Math.floorDiv(chunk.cz, 16) != region.z()) continue;
            byte[] raw = chunk.extraSection(LairSpots.SECTION);
            if (raw == null) continue;
            LairSpots spots;
            try { spots = LairSpots.decode(raw); }
            catch (IOException protectedOrDamaged) { continue; }
            for (int n = 0; n < spots.count(); n++) {
                int cell = spots.cellAt(n);
                Spot spot = new Spot(chunk.cx * 16 + cell % 16, cell / 256, chunk.cz * 16 + cell / 16 % 16);
                if (occupied(spot, existing) || nearParticipant(spot, participants) || nearBase(world, spot)) continue;
                long key = ((long) spot.x << 32) ^ (spot.z & 0xffffffffL) ^ (long) spot.y * 0x9e3779b97f4a7c15L;
                candidates.add(new Candidate(chunk, cell, spot, LairSpots.mix(seed ^ day ^ key)));
            }
        }
        candidates.sort((a, b) -> {
            int cmp = Long.compareUnsigned(a.score, b.score);
            if (cmp == 0) cmp = Integer.compare(a.spot.x, b.spot.x);
            if (cmp == 0) cmp = Integer.compare(a.spot.y, b.spot.y);
            return cmp != 0 ? cmp : Integer.compare(a.spot.z, b.spot.z);
        });
        for (Candidate candidate : candidates)
            if (LairSpots.eligible(world, candidate.chunk, candidate.cell) && !seenFromTrail(world, candidate.spot, day))
                return candidate.spot;
        return null;
    }

    private static boolean opportunity(long seed, List<Lair> existing, long day) {
        long destroyed = -1;
        for (Lair lair : existing) {
            if (lair.alive()) return false;
            destroyed = Math.max(destroyed, lair.destroyedDay());
        }
        if (destroyed < 0) return (LairSpots.mix(seed ^ day) & 7) == 0;
        if (day < destroyed) return false;
        long delay = 3 + Math.floorMod(LairSpots.mix(seed ^ destroyed), 8);
        return day - destroyed >= delay;
    }

    private static boolean occupied(Spot spot, List<Lair> existing) {
        for (Lair lair : existing) {
            int radius = Math.max(lair.alive() ? 64 : 48, lair.radius() * 2);
            // Territory is horizontal: a different floor above the old nest is still the same place.
            long dx = (long) spot.x - lair.x(), dz = (long) spot.z - lair.z();
            if (Math.abs(dx) < radius && Math.abs(dz) < radius && dx * dx + dz * dz < (long) radius * radius) return true;
        }
        return false;
    }
    private static boolean nearParticipant(Spot spot, Participants participants) {
        for (int n = 0; n < participants.size(); n++) {
            Participant participant = participants.get(n);
            if (participant.alive() && participant.position().distanceSquared(spot.x + 1f, spot.y, spot.z + 1f) < 32 * 32) return true;
        }
        return false;
    }
    private static boolean nearBase(World world, Spot spot) {
        int cx = Math.floorDiv(spot.x, 16), cz = Math.floorDiv(spot.z, 16);
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            Chunk chunk = world.getChunkIfExists(cx + dx, cz + dz);
            if (chunk == null) continue;
            for (int n = 0; n < chunk.baseCellCount(); n++) {
                int cell = chunk.baseCellAt(n), x = cell % 16, y = cell / 256, z = cell / 16 % 16;
                // Validate the live cell as well as the sparse index.
                BlockType type = chunk.get(x, y, z);
                if (type != BlockType.CHEST && type != BlockType.FURNACE && type != BlockType.BEDROLL) continue;
                if (distance(spot.x + 1f, spot.y, spot.z + 1f, chunk.cx * 16 + x + .5f, y, chunk.cz * 16 + z + .5f) < 32 * 32) return true;
            }
        }
        return false;
    }
    private boolean seenFromTrail(World world, Spot spot, long day) {
        for (int n = 0; n < trailCount; n++) {
            Trail trail = trails[n];
            if (day < trail.day || day - trail.day > TRAIL_DAYS
                    || distance(trail.x, trail.y, trail.z, spot.x + 1f, spot.y + .4f, spot.z + 1f) > 96 * 96) continue;
            if (EntityPhysics.lineOfSight(world, trail.x, trail.y, trail.z, spot.x + 1f, spot.y + .4f, spot.z + 1f)) return true;
        }
        return false;
    }
    private static float distance(float x, float y, float z, float x2, float y2, float z2) {
        float dx = x - x2, dy = y - y2, dz = z - z2;
        return dx * dx + dy * dy + dz * dz;
    }
}
