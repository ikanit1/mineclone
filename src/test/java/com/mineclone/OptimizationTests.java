package com.mineclone;

import com.mineclone.world.*;
import com.mineclone.render.*;
import com.mineclone.save.RunLengthCodec;
import java.io.*;
import java.util.*;

/** Pure regression checks for the optimization paths, independent of OpenGL. */
public final class OptimizationTests {
    public static void run() {
        try { palette(); runs(); legacySave(); mesh(); dirty(); audio(); distanceTicks(); }
        catch (IOException e) { throw new AssertionError(e); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void palette() {
        PaletteStorage storage = new PaletteStorage(32768);
        require(storage.payloadBytes() == 8, "Uniform sections should not allocate indices");
        byte[] reference = new byte[32768];
        Random random = new Random(715);
        for (int n = 0; n < 50000; n++) {
            int i = random.nextInt(reference.length);
            byte value = (byte)random.nextInt(256);
            storage.set(i, value); reference[i] = value;
        }
        require(Arrays.equals(storage.copy(), reference), "Palette growth must preserve unsigned IDs");
        storage.restore(new byte[32768]);
        require(storage.payloadBytes() == 8, "Restore must compact uniform sections");
        Chunk chunk = new Chunk(-1, -1);
        chunk.set(15, 17, 15, BlockType.STONE);
        require(chunk.isSolid(15,17,15), "Solid mask set");
        chunk.set(15, 17, 15, BlockType.AIR);
        require(!chunk.isSolid(15,17,15), "Solid mask clear");
        reference = new byte[32768]; reference[Chunk.idx(3,99,4)] = (byte)BlockType.STONE.ordinal();
        chunk.restore(reference, new byte[32768]);
        require(chunk.isSolid(3,99,4), "Solid mask restored");
    }
    private static void runs() throws IOException {
        for (int pattern = 0; pattern < 3; pattern++) {
            byte[] input = new byte[131072];
            if (pattern == 1) for (int i = 0; i < input.length; i++) input[i] = (byte)i;
            if (pattern == 2) new Random(1).nextBytes(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            RunLengthCodec.write(new DataOutputStream(bytes), input);
            byte[] output = new byte[input.length];
            RunLengthCodec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), output);
            require(Arrays.equals(input, output), "RLE round trip");
        }
        for (byte[] invalid : new byte[][]{{0,0,1},{0,5,1},{0,1}}) {
            try {
                RunLengthCodec.read(new DataInputStream(new ByteArrayInputStream(invalid)), new byte[4]);
                throw new AssertionError("Malformed RLE accepted");
            } catch (IOException expected) { }
        }
    }
    private static void legacySave() throws IOException {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mineclone-legacy-");
        var saves = new com.mineclone.save.SaveManager(root.toFile());
        byte[] blocks = new byte[32768], meta = new byte[32768];
        Arrays.fill(blocks,(byte)BlockType.STONE.ordinal());
        for (int version=1;version<=4;version++) {
            var dir = root.resolve("legacy" + version).resolve("chunks");
            java.nio.file.Files.createDirectories(dir);
            try (var out = new DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(dir.resolve("c.0.0.dat"))))) {
                out.writeInt(com.mineclone.save.SaveFormat.MAGIC); out.writeInt(version);
                out.write(blocks); out.write(meta);
                if(version>=2) out.writeInt(0);
                if(version>=3) out.writeInt(0);
                if(version>=4) out.writeInt(0);
            }
            var loaded = saves.loadChunk("legacy" + version,0,0);
            require(loaded != null && Arrays.equals(loaded.blocks,blocks), "Legacy chunk version " + version);
        }
        saves.flushAndAwait();
    }
    private static void distanceTicks() {
        World world = new World(1);
        var mob = new com.mineclone.world.entity.Mob(com.mineclone.world.entity.MobType.COW,40,70,0,new Random(1));
        var player = new org.joml.Vector3f(0,70,0);
        for (int i=0;i<3;i++) require(!mob.updateLod(world,player,0.025f,1,false), "Far tick should wait");
        require(mob.updateLod(world,player,0.025f,1,false), "Far tick must consume accumulated time");
        player.set(mob.position);
        require(mob.updateLod(world,player,0.016f,1,false), "Near tick must run immediately");
    }
    private static void mesh() {
        World world = new World(1);
        Chunk chunk = new Chunk(0,0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) chunk.set(x,64,z,BlockType.STONE);
        chunk.computeSkyLight();
        ChunkMesher mesher = new ChunkMesher(world);
        MeshData fine = mesher.buildData(chunk)[0];
        MeshData coarse = mesher.buildData(chunk,2)[0];
        for (MeshData data : new MeshData[]{fine, coarse}) {
            float area = 0;
            require(data.repeat.length == data.positions.length, "Array texture attributes aligned");
            for (int q = 0; q < data.positions.length; q += 12) {
                float ax = data.positions[q+3]-data.positions[q], ay = data.positions[q+4]-data.positions[q+1], az = data.positions[q+5]-data.positions[q+2];
                float bx = data.positions[q+9]-data.positions[q], by = data.positions[q+10]-data.positions[q+1], bz = data.positions[q+11]-data.positions[q+2];
                float nx = ay*bz-az*by, ny = az*bx-ax*bz, nz = ax*by-ay*bx;
                area += (float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                float cx = (data.positions[q]+data.positions[q+6])*0.5f-8;
                float cy = (data.positions[q+1]+data.positions[q+7])*0.5f-64.5f;
                float cz = (data.positions[q+2]+data.positions[q+8])*0.5f-8;
                require(nx*cx + ny*cy + nz*cz > 0, "Outward winding");
            }
            require(area == 576f, "Merged mesh must preserve exposed surface area: " + area);
        }
        require(fine.indices.length < 576*6, "Greedy meshing must reduce the plane");
        require(coarse.indices.length <= fine.indices.length, "LOD must not add triangles");
        System.out.println("Greedy fixture: 576 naive quads -> " + fine.indices.length/6 + ", LOD2 " + coarse.indices.length/6);
    }
    private static void dirty() {
        Chunk c = new Chunk(0,0);
        int version = c.contentVersion();
        c.set(0,0,0,BlockType.AIR);
        require(c.contentVersion() == version, "No-op edit must not invalidate mesh");
        c.setMeta(0,0,0,(byte)1);
        require(c.contentVersion() > version, "Metadata edit must invalidate mesh");
        require(!c.clearDirtyIfCurrent(version), "Stale build must not clear dirty state");
    }
    private static void audio() {
        var queue = new com.mineclone.audio.DeferredAudio();
        var position = new org.joml.Vector3f(1,2,3);
        queue.add(List.of("test"), position, 0.4f, 1);
        queue.add(List.of("test"), position, 0.8f, 1);
        position.set(99);
        int[] count = {0};
        queue.flush((paths, at, volume, pitch) -> {
            count[0]++;
            require(at.x == 1 && volume == 0.8f, "Events must copy positions and coalesce");
        });
        queue.flush((paths, at, volume, pitch) -> { throw new AssertionError("Event replayed"); });
        require(count[0] == 1, "Batch emitted once");
    }
}
