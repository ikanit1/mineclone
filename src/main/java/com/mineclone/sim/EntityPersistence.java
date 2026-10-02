package com.mineclone.sim;

import com.mineclone.save.SavedMob;
import com.mineclone.save.SavedMobCodec;
import com.mineclone.world.Chunk;
import com.mineclone.world.entity.Mob;
import java.io.IOException;
import java.util.*;

/** Compatibility API for the unified, bounded chunk animal records. */
public final class EntityPersistence {
    public static final String SECTION = "entities";
    public static final String SPAWN_RECEIPT = "mineclone:spawn_receipt";
    public static final int MAX = SavedMobCodec.MAX_RECORDS;
    private EntityPersistence() {}

    public static boolean belongs(Mob mob, Chunk chunk) {
        return Math.floorDiv((int) Math.floor(mob.position.x), Chunk.SIZE_X) == chunk.cx
                && Math.floorDiv((int) Math.floor(mob.position.z), Chunk.SIZE_Z) == chunk.cz;
    }

    public static byte[] encode(List<Mob> mobs) {
        var records = new ArrayList<SavedMob>(mobs.size());
        for (Mob mob : mobs) {
            if (mob.dead || !mob.persistent()) throw new IllegalArgumentException("invalid persisted mob");
            records.add(SavedMob.from(mob));
        }
        return encodeRecords(records);
    }

    public static byte[] encodeRecords(List<SavedMob> records) {
        try { return SavedMobCodec.encode(records); }
        catch (IOException invalid) { throw new IllegalArgumentException("invalid persisted entities", invalid); }
    }

    public static List<SavedMob> decodeRecords(byte[] body) {
        try { return SavedMobCodec.decode(body); }
        catch (IOException invalid) { throw new IllegalArgumentException("invalid persisted entities", invalid); }
    }

    public static List<Mob> decode(byte[] body) {
        var result = new ArrayList<Mob>();
        for (SavedMob record : decodeRecords(body)) {
            Mob mob = record.toMob(new Random(record.id()));
            if (mob != null) result.add(mob);
        }
        return List.copyOf(result);
    }

    public static byte[] snapshot(List<Mob> mobs, Chunk chunk) {
        return encode(mobs.stream().filter(m -> m.persistent() && !m.dead && belongs(m, chunk)).toList());
    }
}