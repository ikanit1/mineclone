package com.mineclone.sim.ecology;

import com.mineclone.world.BlockTicker;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded, saved pressure on ground cells; an absent section means untouched land. */
public final class Trample {
    public static final String SECTION = "trample";
    public static final int MAX_ENTRIES = 4096;
    public static final int PATH_THRESHOLD = 6;
    public static final int DAILY_FADE = 2;
    private static final int VERSION = 1;

    /** An additive save from a newer build is protected, not treated as damaged land. */
    public static final class UnsupportedVersion extends IOException {
        public UnsupportedVersion(int version) { super("unsupported trample section version " + version); }
    }

    private final TreeMap<Integer, Integer> pressure = new TreeMap<>();
    private long lastDay;

    public Trample(long day) { lastDay = Math.max(0, day); }
    public static long day(double gameTime) {
        return (long) Math.max(0, Math.floor(gameTime / com.mineclone.world.NightSky.CYCLE));
    }
    public long lastDay() { return lastDay; }
    public int size() { return pressure.size(); }
    public int count(int index) { return pressure.getOrDefault(index, 0); }

    /** Fades once per elapsed day, including days while this chunk was unloaded. */
    public List<Integer> advance(long day) {
        if (day <= lastDay) return List.of();
        long elapsed = day - lastDay;
        lastDay = day;
        int fade = elapsed >= 128 ? 255 : (int) elapsed * DAILY_FADE;
        List<Integer> expired = new ArrayList<>();
        for (var it = pressure.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Integer> entry = it.next();
            int next = entry.getValue() - fade;
            if (next <= 0) {
                expired.add(entry.getKey());
                it.remove();
            } else entry.setValue(next);
        }
        return expired;
    }

    /** @return pressure after the step, or zero when the bounded section is full. */
    public int press(int index) {
        int before = count(index);
        if (before == 0 && pressure.size() >= MAX_ENTRIES) return 0;
        int next = Math.min(255, before + 1);
        pressure.put(index, next);
        return next;
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + pressure.size() * 5);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(VERSION);
                out.writeLong(lastDay);
                out.writeShort(pressure.size());
                for (var entry : pressure.entrySet()) {
                    out.writeShort(entry.getKey());
                    out.writeByte(entry.getValue());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static Trample decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new UnsupportedVersion(version);
            long day = in.readLong();
            if (day < 0) throw new IOException("negative trample day");
            int size = in.readUnsignedShort();
            if (size > MAX_ENTRIES) throw new IOException("too many trample cells");
            Trample result = new Trample(day);
            int previous = -1;
            for (int i = 0; i < size; i++) {
                int index = in.readUnsignedShort();
                int count = in.readUnsignedByte();
                if (index >= Chunk.SIZE_X * Chunk.SIZE_Y * Chunk.SIZE_Z || index <= previous || count == 0)
                    throw new IOException("invalid trample cell");
                result.pressure.put(index, count);
                previous = index;
            }
            if (in.read() != -1) throw new IOException("trailing trample bytes");
            return result;
        }
    }

    /** Called on the simulation thread before random block ticks and on a new step. */
    public static void advanceChunk(World world, Chunk chunk, long day) {
        Trample tracks = chunk.trampleOrNull();
        if (tracks == null || day <= tracks.lastDay() || chunk.isReadOnly()) return;
        boolean hadTracks = tracks.size() > 0;
        List<Integer> expired = tracks.advance(day);
        if (hadTracks) chunk.modified = true;
        for (int index : expired) {
            int x = chunk.cx * Chunk.SIZE_X + index % Chunk.SIZE_X;
            int y = index / (Chunk.SIZE_X * Chunk.SIZE_Z);
            int z = chunk.cz * Chunk.SIZE_Z + index / Chunk.SIZE_X % Chunk.SIZE_Z;
            BlockTicker.regrowFootpath(world, x, y, z);
        }
    }

    public static int pressureAt(World world, int x, int y, int z) {
        if (y < 0 || y >= Chunk.SIZE_Y) return 0;
        Chunk c = world.getChunkIfExists(Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z));
        Trample tracks = c == null ? null : c.trampleOrNull();
        return tracks == null ? 0 : tracks.count(Chunk.idx(Math.floorMod(x, Chunk.SIZE_X), y,
                Math.floorMod(z, Chunk.SIZE_Z)));
    }

    /** One cell entered by a grounded participant or animal. */
    public static void step(World world, float fx, float fy, float fz, long day) {
        if (!Float.isFinite(fx) || !Float.isFinite(fy) || !Float.isFinite(fz)) return;
        int x = (int) Math.floor(fx), z = (int) Math.floor(fz);
        int supportY = (int) Math.floor(fy - 0.1f);
        BlockType cover = world.getBlock(x, supportY, z);
        boolean snow = cover == BlockType.SNOW_LAYER;
        int groundY = snow ? supportY - 1 : supportY;
        if (groundY < 0 || groundY >= Chunk.SIZE_Y) return;
        BlockType ground = world.getBlock(x, groundY, z);
        Chunk chunk = world.getChunkIfExists(Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z));
        if (chunk == null || chunk.isReadOnly()) return;
        advanceChunk(world, chunk, day);
        int index = Chunk.idx(Math.floorMod(x, Chunk.SIZE_X), groundY, Math.floorMod(z, Chunk.SIZE_Z));
        Trample tracks = chunk.trampleOrNull();
        if (ground != BlockType.GRASS && ground != BlockType.SNOWY_GRASS
                && !(ground == BlockType.DIRT && tracks != null && tracks.count(index) > 0)
                && !(snow && ground.solid)) return;
        if (tracks == null) tracks = chunk.trample(day);
        int count = tracks.press(index);
        if (count == 0) return;
        chunk.modified = true;
        if (snow && count % 2 == 0) {
            int level = world.getBlockMeta(x, supportY, z) & 7;
            if (level == 0) world.setBlock(x, supportY, z, BlockType.AIR);
            else world.setSnowLevel(x, supportY, z, level - 1);
        }
        if (count >= PATH_THRESHOLD && (ground == BlockType.GRASS || ground == BlockType.SNOWY_GRASS))
            world.setBlock(x, groundY, z, BlockType.DIRT);
    }
}
