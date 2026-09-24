import com.mineclone.core.Input;
import com.mineclone.game.Player;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
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
            System.out.println("PASS: real held-key walking along and into walls, four directions at 20/30/60/144 FPS; no upward teleport");
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
