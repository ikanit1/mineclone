package com.mineclone.game;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.EntityPhysics;
import org.joml.Vector3f;

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
        r.run("grazing a wall while falling never teleports onto it", PlayerPhysicsTests::grazingWall);
        r.run("a floor collision invalidates the remaining vertical candidates", PlayerPhysicsTests::floorCandidates);
        r.run("falling lands and jumping still stops at ceilings", PlayerPhysicsTests::verticalContacts);
        r.run("a player clipped into a sealed room wall moves into the room, not upward",
                PlayerPhysicsTests::playerWallRecovery);
        r.run("a mob clipped into a sealed room wall stays below its roof",
                PlayerPhysicsTests::mobWallRecovery);
        r.run("crowd pressure cannot force a mob into the sealed room wall",
                PlayerPhysicsTests::crowdAgainstWall);
        r.run("a fully sealed body waits for space instead of crossing the roof",
                PlayerPhysicsTests::noRoofEscape);
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

    private static void grazingWall() {
        World w = ground(40);
        for (int y = 41; y <= 43; y++) {
            w.setBlock(3, y, 0, BlockType.STONE);
            w.setBlock(0, y, 3, BlockType.STONE);
        }
        for (boolean alongX : new boolean[] {true, false}) {
            Player p = new Player();
            p.position.set(alongX ? 2.7f : 0.5f, 41.0001f, alongX ? 0.5f : 2.7f);
            p.moveAxis(w, 0, -0.01f, 0);
            check(Math.abs(p.position.y - 41.0001f) < 0.001f,
                    "touching a wall must land on the floor, not climb the wall: " + p.position);
        }
    }

    private static void floorCandidates() {
        World w = ground(40);
        w.setBlock(2, 42, 0, BlockType.STONE);
        Player p = new Player();
        // The low ceiling is already overlapping the head; gravity must never eject upward onto it.
        p.position.set(2.5f, 41.0001f, 0.5f);
        p.moveAxis(w, 0, -0.02f, 0);
        check(Math.abs(p.position.y - 41.0001f) < 0.001f,
                "downward floor resolution must not climb other blocks in the broad phase: " + p.position);
    }

    private static void verticalContacts() {
        World w = ground(40);
        w.setBlock(2, 43, 0, BlockType.STONE);
        Player p = new Player();
        p.position.set(2.5f, 41, 0.5f);
        p.velocity.y = 8;
        p.moveAxis(w, 0, 0.4f, 0);
        check(Math.abs(p.position.y - (43 - Player.HEIGHT - 0.0001f)) < 0.001f && p.velocity.y == 0,
                "a real upward crossing still hits the ceiling: " + p.position);
        p.position.y = 44.2f;
        p.velocity.y = -8;
        p.moveAxis(w, 0, -0.4f, 0);
        check(Math.abs(p.position.y - 44.0001f) < 0.001f && p.onGround && p.velocity.y == 0,
                "a real downward crossing still lands on top: " + p.position);
    }

    private static World sealedRoom() {
        World w = ground(40);
        for (int y = 41; y <= 43; y++)
            for (int i = 0; i <= 2; i++) {
                w.setBlock(0, y, i, BlockType.STONE);
                w.setBlock(2, y, i, BlockType.STONE);
                w.setBlock(i, y, 0, BlockType.STONE);
                w.setBlock(i, y, 2, BlockType.STONE);
            }
        w.setBlock(1, 43, 1, BlockType.STONE);
        return w;
    }

    private static void playerWallRecovery() {
        World w = sealedRoom();
        Player p = new Player();
        p.position.set(1.2f, 41.0001f, 1.5f); // body overlaps the west wall by 0.1 block
        for (int i = 0; i < 20; i++)
            p.moveAxis(w, 0f, -0.01f, 0f);
        check(p.position.x >= 1.3f && p.position.x < 1.7f,
                "player stayed embedded in the wall: " + p.position);
        check(p.position.y >= 41f && p.position.y < 41.01f,
                "player escaped through the roof: " + p.position);
    }

    private static void mobWallRecovery() {
        World w = sealedRoom();
        Vector3f pos = new Vector3f(1.8f, 41.0001f, 1.5f); // east wall is visited after the floor
        Vector3f vel = new Vector3f();
        for (int i = 0; i < 20; i++)
            EntityPhysics.step(w, pos, vel, 0.6f, 1.8f, 1f / 60f, -30f);
        check(pos.x > 1.3f && pos.x <= 1.7f,
                "mob stayed embedded in the wall: " + pos);
        check(pos.y >= 41f && pos.y < 41.01f,
                "mob escaped through the roof: " + pos);
    }

    private static void crowdAgainstWall() {
        World w = sealedRoom();
        Vector3f left = new Vector3f(1.35f, 41.0001f, 1.5f);
        Vector3f right = new Vector3f(1.5f, 41.0001f, 1.5f);
        EntityPhysics.separate(w, left, 0.6f, 1.8f, 0.5f,
                right, 0.6f, 1.8f, 0.5f);
        check(left.x >= 1.3f && !com.mineclone.world.shape.BodyCollision.overlaps(w,
                left.x, left.y, left.z, 0.6f, 1.8f),
                "crowd pushed the left mob through the wall: " + left);
        check(!com.mineclone.world.shape.BodyCollision.overlaps(w,
                right.x, right.y, right.z, 0.6f, 1.8f),
                "crowd pushed the right mob through the wall: " + right);
    }

    private static void noRoofEscape() {
        World w = sealedRoom();
        w.setBlock(1, 41, 1, BlockType.STONE); // no horizontal body-sized opening exists
        Player player = new Player();
        player.position.set(1.5f, 41.0001f, 1.5f);
        Vector3f mob = new Vector3f(1.5f, 41.0001f, 1.5f);
        Vector3f velocity = new Vector3f();
        for (int i = 0; i < 60; i++) {
            player.moveAxis(w, 0.04f, 0f, 0f);
            player.moveAxis(w, 0f, -0.01f, 0f);
            velocity.x = 2f;
            EntityPhysics.step(w, mob, velocity, 0.6f, 1.8f, 1f / 60f, -30f);
            check(player.position.y < 43f && mob.y < 43f && player.position.x < 1.7f && mob.x < 1.7f,
                    "a sealed body crossed terrain: player=" + player.position + ", mob=" + mob);
        }
        w.setBlock(1, 40, 1, BlockType.AIR);
        Vector3f unsupported = new Vector3f(1.5f, 41.0001f, 1.5f);
        EntityPhysics.Contact contact = EntityPhysics.step(w, unsupported, new Vector3f(),
                0.6f, 1.8f, 1f / 60f, -30f);
        check(!contact.onGround(), "an old wall overlap was mistaken for ground");
    }
}
