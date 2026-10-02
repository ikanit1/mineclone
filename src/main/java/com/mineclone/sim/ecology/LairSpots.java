package com.mineclone.sim.ecology;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.shape.BodyCollision;
import com.mineclone.world.shape.Shapes;
import java.io.*;
import java.util.Arrays;

/** Bounded saved cave candidates, discovered only in loaded terrain and rechecked before founding. */
public final class LairSpots {
    public static final String SECTION = "lair_spots";
    public static final int VERSION = 1, MAX_SPOTS = 16, MAX_TAIL = 1024;
    private static final int MAGIC = 0x4C535054;
    private final int[] cells;
    private final byte[] tail;

    public LairSpots(int[] cells) { this(cells, new byte[0]); }
    private LairSpots(int[] cells, byte[] tail) {
        if (cells.length > MAX_SPOTS || tail.length > MAX_TAIL) throw new IllegalArgumentException("lair spots bounds");
        this.cells = cells.clone(); this.tail = tail.clone();
        Arrays.sort(this.cells);
        for (int n = 0; n < this.cells.length; n++)
            if (this.cells[n] < 0 || this.cells[n] >= Chunk.SIZE_X * Chunk.SIZE_Y * Chunk.SIZE_Z
                    || n > 0 && this.cells[n] == this.cells[n - 1]) throw new IllegalArgumentException("invalid lair spot");
    }
    public int count() { return cells.length; }
    public int cellAt(int index) { return cells[index]; }
    public int[] cells() { return cells.clone(); }

    public static final class NewerVersionException extends IOException {
        public NewerVersionException(int version) { super("newer lair spots version " + version); }
    }
    public byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeByte(VERSION); out.writeByte(cells.length);
            for (int cell : cells) out.writeShort(cell);
            out.write(tail);
        }
        return bytes.toByteArray();
    }
    public static LairSpots decode(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("invalid lair spots magic");
            int version = in.readUnsignedByte();
            if (version > VERSION) throw new NewerVersionException(version);
            if (version != VERSION) throw new IOException("invalid lair spots version");
            if (bytes.length > 6 + MAX_SPOTS * 2 + MAX_TAIL) throw new IOException("lair spots too large");
            int count = in.readUnsignedByte();
            if (count > MAX_SPOTS) throw new IOException("too many lair spots");
            int[] cells = new int[count];
            for (int n = 0; n < count; n++) cells[n] = in.readUnsignedShort();
            byte[] tail = in.readAllBytes();
            if (tail.length > MAX_TAIL) throw new IOException("lair spots tail too large");
            try { return new LairSpots(cells, tail); }
            catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
        }
    }

    /** No terrain edits, generation or section writes; the host stores the returned bytes. */
    public static LairSpots discover(World world, Chunk chunk) {
        if (chunk.isReadOnly() || world.getChunkIfExists(chunk.cx, chunk.cz) != chunk)
            return new LairSpots(new int[0]);
        int[] chosen = new int[MAX_SPOTS]; long[] scores = new long[MAX_SPOTS]; int count = 0;
        long seed = world.seed ^ (long) chunk.cx * 0x9e3779b97f4a7c15L ^ (long) chunk.cz * 0xbf58476d1ce4e5b9L;
        for (int y = 1; y < Chunk.SIZE_Y - 1; y++) for (int z = 0; z < 15; z++) for (int x = 0; x < 15; x++) {
            int cell = Chunk.idx(x, y, z);
            if (!eligible(world, chunk, cell)) continue;
            long score = mix(seed ^ cell); int replace = -1;
            for (int n = 0; n < count; n++) {
                int other = chosen[n], dx = x - other % 16, dy = y - other / 256, dz = z - other / 16 % 16;
                if (dx * dx + dy * dy + dz * dz < 16) { replace = n; break; }
            }
            if (replace >= 0) {
                if (Long.compareUnsigned(score, scores[replace]) >= 0) continue;
            } else if (count < MAX_SPOTS) replace = count++;
            else {
                replace = 0;
                for (int n = 1; n < count; n++) if (Long.compareUnsigned(scores[n], scores[replace]) > 0) replace = n;
                if (Long.compareUnsigned(score, scores[replace]) >= 0) continue;
            }
            chosen[replace] = cell; scores[replace] = score;
        }
        return new LairSpots(Arrays.copyOf(chosen, count));
    }

    /** The anchor owns a full supported 2x2x1 cavity; a 1.1-wide spider fits at its centre. */
    public static boolean eligible(World world, Chunk chunk, int cell) {
        if (chunk.isReadOnly() || world.getChunkIfExists(chunk.cx, chunk.cz) != chunk
                || cell < 0 || cell >= 16 * 128 * 16) return false;
        int lx = cell % 16, y = cell / 256, lz = cell / 16 % 16;
        if (lx >= 15 || lz >= 15 || y <= 0 || y >= Chunk.SIZE_Y - 1) return false;
        for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
            int x = lx + dx, z = lz + dz;
            BlockType floor = chunk.get(x, y - 1, z);
            if (!floor.solid || !Shapes.of(floor).fullCube(chunk.getMeta(x, y - 1, z))
                    || chunk.get(x, y, z) != BlockType.AIR
                    || chunk.getSkyRaw(x, y, z) != 0 || chunk.getBlockLight(x, y, z) != 0) return false;
            boolean roof = false;
            for (int top = y + 1; top < Chunk.SIZE_Y; top++) {
                BlockType block = chunk.get(x, top, z);
                if (block.solid && !block.transparent && Shapes.of(block).fullCube(chunk.getMeta(x, top, z))) { roof = true; break; }
            }
            if (!roof) return false;
        }
        return !BodyCollision.overlaps(world, chunk.cx * 16 + lx + 1f, y,
                chunk.cz * 16 + lz + 1f, 1.1f, .8f);
    }
    static long mix(long value) {
        value ^= value >>> 30; value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27; value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
}
