package com.mineclone.sim.ecology;

import com.mineclone.sim.Participant;
import com.mineclone.sim.Participants;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.shape.BodyCollision;
import java.util.List;
import java.util.Random;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Places finite regional animals on loaded terrain outside every living participant's view. */
public final class Materializer {
    public static final float MIN_DISTANCE = 32f;
    /** Conservative cone also covers wide screens and bodies near a screen edge. */
    public static final float VIEW_HALF_ANGLE = (float) Math.toRadians(80);
    private static final float VIEW_COS = (float) Math.cos(VIEW_HALF_ANGLE);
    private static final int ATTEMPTS = 6;

    /**
     * Accounting belongs to Ecology. Availability never creates animals;
     * claim reserves an existing animal and assigns its persistent identity.
     */
    public interface Populations {
        int available(MobType type, float x, float z);
        boolean claim(Mob body);
        default int groupMin(MobType type) { return type == MobType.WOLF ? MobSpawner.PACK_MIN : 1; }
        default int groupMax(MobType type) { return type == MobType.WOLF ? MobSpawner.PACK_MAX : 1; }
    }

    private final Random random;
    private final Vector3f direction = new Vector3f();

    public Materializer(long seed) { random = new Random(seed); }

    /** One spawn interval; successful claims are appended exactly once. */
    public int tryMaterialize(World world, List<Mob> mobs, Participants participants,
                              float daylight, Populations populations) {
        int alive = 0;
        for (int i = 0; i < participants.size(); i++)
            if (participants.get(i).alive()) alive++;
        if (alive == 0) return 0;
        int scale = Math.min(MobSpawner.MAX_CAP_SCALE, alive);
        int added = 0;
        for (int i = 0; i < participants.size(); i++) {
            Participant participant = participants.get(i);
            if (!participant.alive()) continue;
            for (int category = 0; category < 2; category++) {
                boolean passive = category == 1;
                for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    int count = tryGroup(world, mobs, participants, participant.position(), daylight,
                            populations, passive, scale);
                    if (count > 0) {
                        added += count;
                        break;
                    }
                }
            }
        }
        return added;
    }

    private int tryGroup(World world, List<Mob> mobs, Participants participants, Vector3fc centre,
                         float daylight, Populations populations, boolean passive, int scale) {
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = MIN_DISTANCE + random.nextDouble() * (MobSpawner.MAX_RADIUS - MIN_DISTANCE);
        int x = (int) Math.floor(centre.x() + Math.cos(angle) * distance);
        int z = (int) Math.floor(centre.z() + Math.sin(angle) * distance);
        int surfaceY = surfaceY(world, x, z);
        if (surfaceY < 0) return 0;
        BlockType surface = world.getBlock(x, surfaceY, z);
        MobType type;
        if (passive) {
            if (!MobSpawner.peacefulSurfaceOk(surface)) return 0;
            type = MobType.PEACEFUL[random.nextInt(MobType.PEACEFUL.length)];
        } else {
            type = MobSpawner.wildTypeFor(world.biomes.biomeAt(x, z), surface, daylight, random.nextFloat());
            if (type == null) return 0;
        }
        int available = populations.available(type, x + .5f, z + .5f);
        if (available <= 0) return 0;
        int min = Math.max(1, Math.min(16, populations.groupMin(type)));
        int max = Math.max(min, Math.min(16, populations.groupMax(type)));
        int wanted = Math.min(available, min + random.nextInt(max - min + 1));
        int added = 0;
        for (int n = 0; n < wanted; n++) {
            // Each member needs its own floor, air and reservation: a group can
            // straddle a cliff, a chunk edge or a regional accounting boundary.
            int gx = x + (n == 0 ? 0 : random.nextInt(5) - 2);
            int gz = z + (n == 0 ? 0 : random.nextInt(5) - 2);
            int gy = surfaceY(world, gx, gz);
            if (gy < 0 || !surfaceOk(world, type, passive, gx, gy, gz, daylight)) continue;
            float px = gx + .5f, py = gy + 1.001f, pz = gz + .5f;
            if (capacity(mobs, participants, passive, scale, px, py, pz) <= 0
                    || populations.available(type, px, pz) <= 0
                    || !placementAllowed(world, mobs, participants, type, px, py, pz))
                continue;
            Mob body = new Mob(type, px, py, pz, new Random(random.nextLong()));
            body.persistent = true;
            if (!populations.claim(body)) continue;
            mobs.add(body);
            added++;
        }
        return added;
    }

    private static boolean surfaceOk(World world, MobType type, boolean passive, int x, int y, int z,
                                     float daylight) {
        BlockType surface = world.getBlock(x, y, z);
        if (passive) return MobSpawner.peacefulSurfaceOk(surface);
        // Reuse the biome rules for every member, including group offsets.
        for (float roll = 0f; roll <= .8f; roll += .4f)
            if (MobSpawner.wildTypeFor(world.biomes.biomeAt(x, z), surface, daylight, roll) == type)
                return true;
        return false;
    }

    private static int capacity(List<Mob> mobs, Participants participants, boolean passive, int scale,
                                 float x, float y, float z) {
        int cap = passive ? MobSpawner.PEACEFUL_CAP : MobSpawner.WILDLIFE_CAP;
        int total = 0;
        for (Mob mob : mobs)
            if (category(mob, passive)) total++;
        int remaining = cap * scale - total;
        float rangeSq = MobSpawner.LOCAL_RANGE * MobSpawner.LOCAL_RANGE;
        for (int i = 0; i < participants.size() && remaining > 0; i++) {
            Participant participant = participants.get(i);
            if (!participant.alive() || participant.position().distanceSquared(x, y, z) > rangeSq) continue;
            int local = 0;
            for (Mob mob : mobs)
                if (category(mob, passive) && mob.position.distanceSquared(participant.position()) <= rangeSq)
                    local++;
            remaining = Math.min(remaining, cap - local);
        }
        return remaining;
    }

    private static boolean category(Mob mob, boolean passive) {
        return !mob.type.hostile && mob.health > 0f
                && (mob.type.temper == MobType.Temper.PASSIVE) == passive;
    }

    private boolean placementAllowed(World world, List<Mob> mobs, Participants participants,
                                      MobType type, float x, float y, float z) {
        float half = type.width * .5f;
        for (int bx = (int) Math.floor(x - half); bx <= (int) Math.floor(x + half); bx++)
            for (int bz = (int) Math.floor(z - half); bz <= (int) Math.floor(z + half); bz++) {
                Chunk chunk = world.getChunkIfExists(Math.floorDiv(bx, Chunk.SIZE_X), Math.floorDiv(bz, Chunk.SIZE_Z));
                if (chunk == null || chunk.isReadOnly()
                        || !world.getBlock(bx, (int) Math.floor(y - .01f), bz).solid
                        || world.burnDamaged(bx, (int) Math.floor(y - .01f), bz))
                    return false;
                for (int by = (int) Math.floor(y); by <= (int) Math.floor(y + type.height); by++) {
                    BlockType block = world.getBlock(bx, by, bz);
                    if (block == BlockType.WATER || block == BlockType.LAVA || block == BlockType.FIRE) return false;
                }
            }
        if (BodyCollision.overlaps(world, x, y, z, type.width, type.height)) return false;
        for (Mob mob : mobs) {
            if (mob.health <= 0f) continue;
            float width = (type.width + mob.type.width) * .5f;
            if (Math.abs(x - mob.position.x) < width && Math.abs(z - mob.position.z) < width
                    && y + type.height > mob.position.y && y < mob.position.y + mob.type.height)
                return false;
        }
        float minSq = MIN_DISTANCE * MIN_DISTANCE;
        for (int i = 0; i < participants.size(); i++) {
            Participant participant = participants.get(i);
            if (!participant.alive()) continue;
            if (participant.position().distanceSquared(x, y, z) < minSq
                    || looksToward(participant, x, y + type.height * .5f, z))
                return false;
        }
        return true;
    }

    private boolean looksToward(Participant participant, float x, float y, float z) {
        participant.viewDirection(direction);
        float lengthSq = direction.lengthSquared();
        if (!Float.isFinite(lengthSq) || lengthSq < 1e-6f) return true;
        Vector3fc eye = participant.eye();
        if (!Float.isFinite(eye.x()) || !Float.isFinite(eye.y()) || !Float.isFinite(eye.z())) return true;
        float dx = x - eye.x(), dy = y - eye.y(), dz = z - eye.z();
        float distanceSq = dx * dx + dy * dy + dz * dz;
        if (distanceSq < 1e-6f) return true;
        float dot = dx * direction.x + dy * direction.y + dz * direction.z;
        return dot >= VIEW_COS * (float) Math.sqrt(distanceSq * lengthSq);
    }

    /** Existing chunks only: inspecting a spawn site never generates terrain. */
    private static int surfaceY(World world, int x, int z) {
        if (world.getChunkIfExists(Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z)) == null)
            return -1;
        for (int y = Chunk.SIZE_Y - 3; y > 0; y--)
            if (world.getBlock(x, y, z).solid) return y;
        return -1;
    }
}
