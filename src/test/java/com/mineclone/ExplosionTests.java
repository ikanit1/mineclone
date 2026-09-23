package com.mineclone;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.Explosion;
import com.mineclone.world.World;

/**
 * Взрыв обязан оставлять укрытие укрытием.
 *
 * Это не косметика: стена — единственная защита от крипера у игрока без
 * брони, и взрыв, пробивающий её насквозь, отнимает у вида всякую
 * контригру.
 */
final class ExplosionTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("an explosion clears the air around it but stops at a wall",
                ExplosionTests::behindWall);
        r.run("tough blocks survive the blast", ExplosionTests::tough);
        r.run("damage falls off to nothing at the edge", ExplosionTests::falloff);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** Каменная коробка с воздушной полостью: пол на 40, воздух с 41. */
    private static World cave() {
        World w = new World(1717L);
        for (int x = -20; x <= 20; x++)
            for (int z = -20; z <= 20; z++) {
                Chunk c = w.getChunk(Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z));
                int lx = Math.floorMod(x, Chunk.SIZE_X), lz = Math.floorMod(z, Chunk.SIZE_Z);
                for (int y = 0; y < Chunk.SIZE_Y; y++)
                    c.set(lx, y, lz, y <= 40 ? BlockType.STONE : BlockType.AIR);
            }
        return w;
    }

    private static boolean listed(java.util.List<int[]> blocks, int x, int y, int z) {
        for (int[] b : blocks)
            if (b[0] == x && b[1] == y && b[2] == z)
                return true;
        return false;
    }

    /**
     * Сплошная стена в два блока: ближняя её сторона выносится, дальняя —
     * нет, и то, что стоит за ней, тоже.
     */
    private static void behindWall() {
        World w = cave();
        for (int y = 41; y <= 44; y++)
            for (int z = -3; z <= 3; z++) {
                w.setBlock(2, y, z, BlockType.STONE);
                w.setBlock(3, y, z, BlockType.STONE);
            }
        // Ещё блок за стеной — он обязан уцелеть.
        w.setBlock(4, 42, 0, BlockType.STONE);

        var gone = Explosion.destroyed(w, 0.5f, 42f, 0.5f, Explosion.RADIUS);
        check(!gone.isEmpty(), "an explosion in the open must break something");
        check(listed(gone, 2, 42, 0), "the near face of the wall goes");
        check(!listed(gone, 4, 42, 0), "but nothing behind it does");
        // Пол под эпицентром — в радиусе и на виду, он тоже должен уйти.
        check(listed(gone, 0, 40, 0), "the floor under the blast goes too");
        // Ничего дальше радиуса.
        for (int[] b : gone) {
            float dx = b[0] + 0.5f - 0.5f, dy = b[1] + 0.5f - 42f, dz = b[2] + 0.5f - 0.5f;
            check(Math.sqrt(dx * dx + dy * dy + dz * dz) <= Explosion.RADIUS + 1e-3,
                    "nothing outside the radius: " + b[0] + "," + b[1] + "," + b[2]);
        }
    }

    /** Обсидиан переживает взрыв — иначе укрыться нельзя нигде. */
    private static void tough() {
        World w = cave();
        w.setBlock(1, 42, 0, BlockType.OBSIDIAN);
        w.setBlock(1, 41, 0, BlockType.STONE);
        var gone = Explosion.destroyed(w, 0.5f, 42f, 0.5f, Explosion.RADIUS);
        check(!listed(gone, 1, 42, 0), "obsidian must survive");
        check(listed(gone, 1, 41, 0), "ordinary stone beside it must not");
        check(!Explosion.survivesNothing(BlockType.OBSIDIAN), "obsidian is tough by the rule too");
        check(Explosion.survivesNothing(BlockType.STONE), "stone is not");
    }

    /** В эпицентре смертельно, у края ничего: отбежать всегда имеет смысл. */
    private static void falloff() {
        float centre = Explosion.damageAt(0f);
        float half = Explosion.damageAt(Explosion.RADIUS * 0.5f);
        float edge = Explosion.damageAt(Explosion.RADIUS);
        check(Math.abs(centre - Explosion.MAX_DAMAGE) < 1e-4, "full damage at the centre");
        check(half < centre && half > 0f, "half way out it still hurts, less");
        check(edge == 0f, "at the edge it is nothing, got " + edge);
        check(Explosion.damageAt(Explosion.RADIUS * 2f) == 0f, "and beyond it, nothing at all");
    }
}
