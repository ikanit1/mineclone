package com.mineclone;

import com.mineclone.world.*;
import com.mineclone.item.Items;
import com.mineclone.audio.Sounds;
import com.mineclone.render.TextureAtlas;
import java.util.*;
import javax.imageio.ImageIO;
import java.io.File;

/** Regression tests for generation boundaries, structure sites and conserved falling material. */
final class WorldGenerationTests {
    static void run() throws Exception {
        assetsAndProperties();
        lavaFlowAndReactions();
        structureSpacingAndTemplates();
        generationOrder();
        gravityAndSave();
        gravityBudgetAndObstruction();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void assetsAndProperties() throws Exception {
        BlockType[] added = {BlockType.PODZOL, BlockType.PEAT, BlockType.DRY_GRASS, BlockType.RED_SAND,
                BlockType.TERRACOTTA, BlockType.LIMESTONE, BlockType.BASALT, BlockType.GRAVEL};
        Sounds sounds = new Sounds();
        for (BlockType b : added) {
            check(Items.get().forBlock(b) != null, "no inventory item: " + b);
            check(b.hardness > 0 && b.preferredTool() != null, "no material properties: " + b);
            check(!sounds.dig(b).isEmpty(), "no material sound: " + b);
            for (int tile : new int[]{b.topTile, b.sideTile, b.bottomTile}) {
                var image = ImageIO.read(new File("assets/textures/blocks/" + TextureAtlas.TILE_NAMES[tile] + ".png"));
                check(image != null && image.getWidth() == 32 && image.getHeight() == 32, "bad tile: " + b);
            }
        }
        check(BlockType.PEAT.walkSpeedMultiplier() < 1, "peat should slow walking");
        check(new File("assets/textures/blocks/lava_flow.png").isFile(), "lava flow texture");
        for (String id : new String[]{"gold_pickaxe", "gold_axe", "gold_shovel", "copper_pickaxe", "copper_axe", "copper_shovel", "stick"})
            check(Items.get().require(id).iconTile >= 0, "tool/material icon: " + id);
        check(Smelting.fuelSeconds(new ItemStack(BlockType.PEAT, 1)) == 16, "peat fuel");
        check(Smelting.result(new ItemStack(BlockType.RED_SAND, 1)).block() == BlockType.GLASS, "red sand smelting");
        for (Biome b : Biome.values()) {
            check(Weather.precipitates(b) == !b.isArid(), "biome precipitation: " + b);
            if (b.isCold()) check(Weather.snowsAt(b, 60), "cold biome rain: " + b);
        }
    }

    private static void lavaFlowAndReactions() {
        LavaSimulator.reset();
        World w = emptyWorld(0);
        w.setBlock(8, 12, 8, BlockType.LAVA, (byte) 0);
        w.setBlock(9, 12, 8, BlockType.WATER);
        LavaSimulator.tick(w);
        check(w.getBlock(8, 12, 8) == BlockType.COBBLE, "side contact should make cobble");
        w = emptyWorld(0); LavaSimulator.reset();
        w.setBlock(8, 20, 8, BlockType.LAVA, (byte) 0);
        for (int i = 0; i < 4; i++) LavaSimulator.tick(w);
        check(w.getBlock(8, 16, 8) == BlockType.LAVA, "lava should fall downward");
        check(w.getBlock(8, 20, 8) == BlockType.LAVA, "lava source should remain");
        w.setBlock(9, 20, 8, BlockType.WATER); LavaSimulator.tick(w);
        check(w.getBlock(8, 20, 8) == BlockType.COBBLE, "side contact should solidify as cobble");
    }

    private static Chunk flatChunk(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            for (int y = 0; y <= 64; y++) c.set(x, y, z, y == 0 ? BlockType.BEDROCK : BlockType.STONE);
        return c;
    }

    private static void structureSpacingAndTemplates() {
        long seed = 2211;
        List<int[]> candidates = new ArrayList<>();
        boolean[] seen = new boolean[4];
        for (int cx = -100; cx < 100; cx++) for (int cz = -100; cz < 100; cz++) {
            int kind = Structures.candidateKind(cx, cz, seed);
            if (kind < 0) continue;
            for (int[] other : candidates)
                check(Math.max(Math.abs(cx - other[0]), Math.abs(cz - other[1])) >= Structures.MIN_CHUNK_GAP,
                        "structures too close across a region boundary");
            candidates.add(new int[]{cx, cz});
            if (seen[kind]) continue;
            seen[kind] = true;
            Chunk c = flatChunk(cx, cz);
            int[][] heights = new int[16][16]; for (int[] row : heights) Arrays.fill(row, 64);
            Structures.Site s = Structures.plan(c, heights, seed, 50);
            check(s != null, "flat supported site rejected");
            byte[] before = c.copyBlocks();
            Structures.place(c, s, seed);
            Chunk again = flatChunk(cx, cz); Structures.place(again, heights, seed, 50);
            check(Arrays.equals(c.copyBlocks(), again.copyBlocks()), "structure not deterministic");
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 128; y++) {
                int i = Chunk.idx(x, y, z);
                if (c.getRaw(x, y, z) == before[i]) continue;
                check(x >= 2 && x <= 13 && z >= 2 && z <= 13, "structure clipped on chunk boundary");
                if (kind == 3) check(y < 60, "dungeon leaked onto surface");
            }
            if (kind == 2) {
                check(c.get(s.x() + 1, s.y() + 2, s.z() + 1) == BlockType.STONE, "torch punched obelisk pillar");
                check(c.get(s.x() + 1, s.y() + 6, s.z() + 1) == BlockType.TORCH, "obelisk cap torch missing");
            }
            // No surface building may bridge a hollow foundation.
            if (kind != 3) {
                Chunk unsupported = new Chunk(cx, cz);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) unsupported.set(x, 64, z, BlockType.GRASS);
                check(Structures.plan(unsupported, heights, seed, 50) == null, "floating foundation accepted");
            }
        }
        check(candidates.size() > 180 && candidates.size() < 400, "unexpected structure density: " + candidates.size());
        for (boolean b : seen) check(b, "missing structure kind");
    }

    private static void generationOrder() throws Exception {
        World a = new World(7381), b = new World(7381);
        int[][] coords = {{-2,-1},{-1,-1},{0,-1},{-2,0},{-1,0},{0,0}};
        for (int[] p : coords) a.getChunk(p[0], p[1]);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            List<java.util.concurrent.Future<Chunk>> jobs = new ArrayList<>();
            for (int i = coords.length - 1; i >= 0; i--) {
                int[] p = coords[i]; jobs.add(pool.submit(() -> b.getChunk(p[0], p[1])));
            }
            for (var job : jobs) job.get();
        } finally { pool.shutdownNow(); }
        for (int[] p : coords) {
            Chunk c = a.getChunk(p[0], p[1]);
            check(Arrays.equals(c.copyBlocks(), b.getChunk(p[0], p[1]).copyBlocks()), "generation depends on load order");
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y < 128; y++)
                if (c.get(x, y, z).hasGravity()) check(!FallingBlocks.fallThrough(c.get(x, y - 1, z)), "floating generated powder");
        }
        for (int x = -300; x < 300; x++) {
            int h = a.terrainHeight(x, -19), next = a.terrainHeight(x + 1, -19);
            check(Math.abs(h - next) <= 4, "grid seam at " + x);
        }
    }

    private static World emptyWorld(int cx) {
        World w = new World(81);
        Chunk c = w.getChunk(cx, 0);
        byte[] blocks = new byte[32768];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) blocks[Chunk.idx(x, 0, z)] = (byte) BlockType.BEDROCK.ordinal();
        c.restore(blocks, new byte[32768]);
        return w;
    }
    private static void advance(World w, int frames) { for (int i = 0; i < frames; i++) w.falling.update(0.05f); }

    private static void gravityAndSave() {
        for (BlockType loose : new BlockType[]{BlockType.SAND, BlockType.RED_SAND, BlockType.ASH, BlockType.GRAVEL}) {
            World w = emptyWorld(-1);
            int x = -1, z = 8;
            w.setBlock(x, 12, z, loose, (byte) 3);
            w.setBlock(x, 13, z, loose);
            advance(w, 2);
            check(w.falling.active().size() == 2, "stack should fall together for " + loose);
            check(w.falling.active().get(0).y < 12 && w.falling.active().get(0).y > 11, "fall should move continuously");
            Chunk c = w.getChunk(-1, 0);
            byte[] saved = c.copyBlocks(), meta = c.copyMeta();
            List<DroppedItem> items = new ArrayList<>();
            w.falling.snapshot(c, saved, meta, items);
            World restored = emptyWorld(-1); Chunk rc = restored.getChunk(-1, 0);
            rc.restore(saved, meta); restored.falling.scanRestored(rc);
            advance(w, 120); advance(restored, 160);
            for (World result : new World[]{w, restored}) {
                check(result.getBlock(x, 1, z) == loose && result.getBlock(x, 2, z) == loose, "pile lost during fall/reload");
                check(result.getBlockMeta(x, 1, z) == 3, "fall lost metadata");
                check(result.falling.active().isEmpty(), "fall never settled");
                int count = 0; for (byte id : result.getChunk(-1, 0).copyBlocks()) if (BlockType.byId(id) == loose) count++;
                check(count == 2, "fall duplicated material");
            }
            w.setBlock(x, 1, z, BlockType.AIR);
            advance(w, 50);
            check(w.getBlock(x, 1, z) == loose && w.getBlock(x, 2, z) == BlockType.AIR, "support removal did not trigger gravity");
        }
        World w = emptyWorld(0);
        w.setBlock(8, 2, 8, BlockType.WATER);
        w.setBlock(8, 3, 8, BlockType.WATER);
        w.setBlock(8, 25, 8, BlockType.SAND);
        advance(w, 120);
        check(w.getBlock(8, 1, 8) == BlockType.SAND, "sand floats on water");
        w = emptyWorld(0);
        w.setBlock(8, 35, 8, BlockType.ASH);
        w.setBlock(8, 36, 8, BlockType.SAND);
        for (int i = 0; i < 150; i++) {
            w.falling.update(0.05f);
            if (w.falling.active().size() == 2)
                check(w.falling.active().get(1).y - w.falling.active().get(0).y >= 0.999f,
                        "fast sand passed through slowly falling ash");
        }
        check(w.getBlock(8, 1, 8) == BlockType.ASH && w.getBlock(8, 2, 8) == BlockType.SAND, "mixed stack changed order");
    }

    private static void gravityBudgetAndObstruction() {
        World w = emptyWorld(0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) w.setBlock(x, 100, z, BlockType.GRAVEL);
        w.falling.update(0.05f);
        check(w.falling.active().size() == 4, "unbounded detach work");
        for (int i = 0; i < 140; i++) {
            w.falling.update(0.05f);
            check(w.falling.active().size() <= FallingBlocks.MAX_ACTIVE, "unbounded actor count");
        }
        advance(w, 500);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) check(w.getBlock(x, 1, z) == BlockType.GRAVEL, "budget discarded queued gravel");
        w = emptyWorld(0);
        w.setBlock(8, 100, 8, BlockType.SAND); advance(w, 12);
        w.setBlock(8, 70, 8, BlockType.STONE); advance(w, 120);
        check(w.getBlock(8, 71, 8) == BlockType.SAND, "fast fall tunneled through new floor");
        w.setBlock(8, 110, 8, BlockType.SAND); advance(w, 1);
        Chunk c = w.getChunk(0, 0); byte[] blocks = c.copyBlocks(), meta = c.copyMeta();
        w.falling.snapshot(c, blocks, meta, new ArrayList<>());
        w.removeChunk(0, 0);
        check(w.falling.active().isEmpty(), "eviction leaked falling actor");
        check(BlockType.byId(blocks[Chunk.idx(8, 110, 8)]) == BlockType.SAND, "eviction snapshot lost actor");
    }
}
