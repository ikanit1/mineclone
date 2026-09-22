package com.mineclone.game;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;

/**
 * Ходьба не должна поднимать игрока — ни на блок, ни на ступень.
 *
 * Прежде ступень ставила его на свой верх одним присваиванием, и это
 * читалось как телепорт: у одного из направлений подъём выходил на целый
 * блок за два кадра подряд.
 *
 * Тест живёт в пакете игры, а не рядом с остальными: ему нужен {@code
 * moveAxis}, а выставлять шаг коллизии наружу ради проверки — хуже, чем
 * положить проверку рядом.
 */
public final class PlayerPhysicsTests {

    public interface Check { void run() throws Exception; }
    public interface Runner { void run(String name, Check check); }

    private PlayerPhysicsTests() {}

    public static void runAll(Runner r) {
        r.run("walking into a block or a stair never lifts the player",
                PlayerPhysicsTests::noAutoStep);
        r.run("but you can still stand on a stair", PlayerPhysicsTests::standOnStair);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** Пол на {@code floorY}, воздух выше. */
    private static World ground(int floorY) {
        World w = new World(99L);
        for (int x = -10; x <= 20; x++)
            for (int z = -10; z <= 10; z++) {
                Chunk c = w.getChunk(Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z));
                int lx = Math.floorMod(x, Chunk.SIZE_X), lz = Math.floorMod(z, Chunk.SIZE_Z);
                for (int y = 0; y < Chunk.SIZE_Y; y++)
                    c.set(lx, y, lz, y <= floorY ? BlockType.STONE : BlockType.AIR);
            }
        return w;
    }

    /** Три секунды ходьбы вправо в препятствие на x=3. */
    private static Player walkInto(World w, int floorY) {
        Player p = new Player();
        p.position.set(0.5f, floorY + 1f, 0.5f);
        p.onGround = true;
        for (int i = 0; i < 180; i++) {
            p.velocity.set(4f, 0f, 0f);
            p.moveAxis(w, 4f / 60f, 0f, 0f);
            p.moveAxis(w, 0f, 0f, 0f);
        }
        return p;
    }

    /**
     * Главное свойство: упереться можно, подняться нельзя. Ступень берётся
     * прыжком, как любой другой блок.
     */
    private static void noAutoStep() {
        int floorY = 40;
        World stone = ground(floorY);
        stone.setBlock(3, floorY + 1, 0, BlockType.STONE);
        Player onStone = walkInto(stone, floorY);
        check(onStone.position.y <= floorY + 1.01f,
                "a stone block must not lift, got y=" + onStone.position.y);
        check(onStone.position.x < 3f, "and must stop you, got x=" + onStone.position.x);

        // Все четыре направления ступени: раньше поднимали два из них.
        for (byte facing = 0; facing < 4; facing++) {
            World w = ground(floorY);
            w.setBlock(3, floorY + 1, 0, BlockType.STAIRS, facing);
            Player p = walkInto(w, floorY);
            check(p.position.y <= floorY + 1.01f,
                    "stair facing " + facing + " lifted the player to y=" + p.position.y);
            check(p.position.x < 3f,
                    "stair facing " + facing + " let the player through to x=" + p.position.x);
        }
    }

    /** Убрав подъём, нельзя заодно провалить игрока сквозь ступень. */
    private static void standOnStair() {
        int floorY = 40;
        World w = ground(floorY);
        w.setBlock(2, floorY + 1, 0, BlockType.STAIRS, (byte) 0);
        Player p = new Player();
        p.position.set(2.5f, floorY + 4f, 0.5f);
        for (int i = 0; i < 240; i++) {
            p.velocity.y -= 28f / 60f;
            p.moveAxis(w, 0f, p.velocity.y / 60f, 0f);
        }
        check(p.onGround, "the player must land, not keep falling");
        check(p.position.y > floorY + 1f,
                "and land on the stair rather than through it, got y=" + p.position.y);
    }
}
