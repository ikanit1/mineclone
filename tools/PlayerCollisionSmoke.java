import com.mineclone.core.Input;
import com.mineclone.game.Player;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.EntityPhysics;
import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.*;

/** Native held-key input through Player.update, including acceleration, gravity and axis substeps. */
public final class PlayerCollisionSmoke {
    public static void main(String[] args) {
        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        long window = glfwCreateWindow(64, 64, "Wall collision regression", 0, 0);
        if (window == 0) throw new IllegalStateException("No native window");
        try {
            var input = new Input(window);
            input.setGrabAllowed(false);
            for (int fps : new int[] {20, 30, 60, 144}) {
                for (int direction = 0; direction < 4; direction++) {
                    World world = platform();
                    boolean xWall = direction < 2;
                    boolean positive = direction % 2 == 0;
                    int wall = positive ? 9 : 6;
                    Chunk c = world.getChunk(0, 0);
                    for (int across = 0; across < 16; across++) for (int y = 11; y < 16; y++)
                        c.set(xWall ? wall : across, y, xWall ? across : wall, BlockType.STONE);
                    float contact = positive ? 8.7f : 7.3f;
                    Player p = new Player();
                    p.respawn(xWall ? contact : 12.5f, 11.0001f, xWall ? 12.5f : contact);
                    p.camera.yaw = xWall ? 0 : -(float) Math.PI / 2;
                    input.holdKey(GLFW_KEY_W, true);
                    for (int tick = 0; tick < fps; tick++) {
                        input.update(); p.update(1f / fps, world, input);
                        check(Math.abs(p.position.y - 11.0001f) < 0.002f,
                                "walking along wall lifted player at " + fps + " FPS, direction " + direction + ": " + p.position);
                    }
                    check((xWall ? p.position.z : p.position.x) < 9,
                            "the player must actually move along the wall");
                    // Press into the same wall: no climbing, tunnelling or upward correction.
                    p.camera.yaw = xWall ? (positive ? 1 : -1) * (float) Math.PI / 2
                            : positive ? (float) Math.PI : 0;
                    for (int tick = 0; tick < fps; tick++) {
                        input.update(); p.update(1f / fps, world, input);
                        check(Math.abs(p.position.y - 11.0001f) < 0.002f, "pressing into wall lifted player");
                        check(Math.abs((xWall ? p.position.x : p.position.z) - contact) < 0.002f,
                                "the wall must block movement");
                    }
                    input.holdKey(GLFW_KEY_W, false);
                    input.update();
                }
            }
            World room = platform();
            Chunk roomChunk = room.getChunk(0, 0);
            for (int y = 11; y <= 13; y++) for (int n = 6; n <= 8; n++) {
                roomChunk.set(6, y, n, BlockType.STONE);
                roomChunk.set(8, y, n, BlockType.STONE);
                roomChunk.set(n, y, 6, BlockType.STONE);
                roomChunk.set(n, y, 8, BlockType.STONE);
            }
            roomChunk.set(7, 13, 7, BlockType.STONE);
            Player clipped = new Player();
            clipped.respawn(7.2f, 11.0001f, 7.5f);
            clipped.camera.yaw = (float) Math.PI / 2f;
            input.holdKey(GLFW_KEY_W, true);
            for (int i = 0; i < 60; i++) {
                input.update();
                clipped.update(1f / 60f, room, input);
                check(clipped.position.y < 11.01f, "held-key player escaped through the room roof: " + clipped.position);
            }
            input.holdKey(GLFW_KEY_W, false);
            check(clipped.position.x >= 7.3f && clipped.position.x <= 7.7f,
                    "held-key player remained inside the wall: " + clipped.position);
            Vector3f mob = new Vector3f(7.8f, 11.0001f, 7.5f);
            Vector3f mobVelocity = new Vector3f();
            for (int i = 0; i < 60; i++) {
                EntityPhysics.step(room, mob, mobVelocity, 0.6f, 1.8f, 1f / 60f, -30f);
                check(mob.y < 11.01f, "mob escaped through the room roof: " + mob);
            }
            check(mob.x >= 7.3f && mob.x <= 7.7f, "mob remained inside the wall: " + mob);
            System.out.println("PASS: held-key walls at 20/30/60/144 FPS and enclosed player/mob recovery without roof escape");
        } finally {
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    private static World platform() {
        World world = new World(99);
        Chunk c = world.getChunk(0, 0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < Chunk.SIZE_Y; y++)
            c.set(x, y, z, y <= 10 ? BlockType.STONE : BlockType.AIR);
        return world;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
