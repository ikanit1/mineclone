import com.mineclone.core.Window;
import com.mineclone.render.*;
import com.mineclone.world.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

/** Actual chunk, first-person, dropped-item and inventory passes in one controlled scene. */
public final class RenderEquipmentReview {
    private static final int W = 960, H = 540;
    private static final String[] MATERIALS = {"wooden", "stone", "iron", "copper", "gold", "diamond"};
    private static final String[] KINDS = {"pickaxe", "axe", "shovel", "sword"};

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length == 0 ? "out-test/texture-review" : args[0]);
        Files.createDirectories(out);
        Window window = new Window("Equipment texture review", W, H, false);
        window.init();
        try {
            TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
            Shader shader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
            HeldItemRenderer hand = new HeldItemRenderer();
            ItemRenderer drops = new ItemRenderer();
            UiRenderer ui = new UiRenderer();
            ui.setAtlas(atlas.getTextureId());
            ItemIcons icons = new ItemIcons(ui, atlas);
            World world = new World(7381);
            Chunk chunk = world.getChunk(0, 0);
            for (int y = 0; y < Chunk.SIZE_Y; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                chunk.set(x, y, z, y == 8 ? BlockType.PLANKS : BlockType.AIR);
            BlockType[] blocks = {BlockType.STONE, BlockType.COBBLE, BlockType.MOSSY_COBBLE, BlockType.BEDROCK,
                    BlockType.COAL_ORE, BlockType.IRON_ORE, BlockType.GOLD_ORE, BlockType.DIAMOND_ORE};
            for (int n = 0; n < blocks.length; n++)
                for (int x = n % 4 * 4; x < n % 4 * 4 + 4; x++)
                    for (int y = 12 - n / 4 * 2; y < 14 - n / 4 * 2; y++) chunk.set(x, y, 4, blocks[n]);
            chunk.computeSkyLight();
            List<Mesh> meshes = new ArrayList<>();
            for (MeshData data : new ChunkMesher(world).buildData(chunk))
                if (!data.isEmpty()) meshes.add(data.upload());
            SceneLighting light = new SceneLighting();
            light.linearOut = 0; light.fogStart = 100; light.fogEnd = 200;
            light.lightDir.set(-.4f, 1, .5f).normalize();
            light.camPos.set(8, 12.5f, 12);
            Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70), W / (float)H, .05f, 100);
            Matrix4f view = new Matrix4f().lookAt(light.camPos.x, light.camPos.y, light.camPos.z, 8, 11.6f, 4, 0, 1, 0);
            BufferedImage gallery = new BufferedImage(W / 2 * 4, H / 2 * 6, BufferedImage.TYPE_INT_RGB);
            var graphics = gallery.createGraphics();
            int captures = 0;
            for (int row = 0; row < MATERIALS.length; row++) for (int col = 0; col < KINDS.length; col++) {
                String id = MATERIALS[row] + "_" + KINDS[col];
                ItemStack held = ItemStack.of(id);
                List<com.mineclone.world.entity.ItemEntity> items = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    var drop = new com.mineclone.world.entity.ItemEntity(ItemStack.of(MATERIALS[row] + "_" + KINDS[i]),
                            6.7f + i * .8f, 9.3f, 7.5f, 0, 0);
                    drop.onGround = true; items.add(drop);
                }
                scene(atlas, shader, meshes, projection, view, light);
                drops.render(projection, view, items, world, atlas, light, 1);
                hand.render(atlas, held, W / (float)H, 70, 1, 0, 0, false, false, 1, 1, 1, 0, 0);
                ui.begin(W, H);
                ui.quad(14, 16, 210, 338, .055f, .07f, .085f, .9f);
                for (int r = 0; r < MATERIALS.length; r++) for (int c = 0; c < KINDS.length; c++) {
                    int x = 22 + c * 50, y = 24 + r * 54;
                    ui.quad(x, y, 46, 46, r == row && c == col ? .50f : .16f, .24f, .28f, 1);
                    icons.draw(ItemStack.of(MATERIALS[r] + "_" + KINDS[c]), x + 3, y + 3, 40, 1);
                }
                ui.quad(W / 2 - 5, H / 2, 11, 1, 1, 1, 1, .8f);
                ui.quad(W / 2, H / 2 - 5, 1, 11, 1, 1, 1, .8f);
                ui.end();
                BufferedImage shot = read();
                var caption = shot.createGraphics();
                caption.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18)); caption.setColor(Color.WHITE);
                caption.drawString(id + " / inventory + held + dropped", 18, H - 18); caption.dispose();
                ImageIO.write(shot, "png", out.resolve("scene-" + id + ".png").toFile());
                graphics.drawImage(shot, col * W / 2, row * H / 2, W / 2, H / 2, null);
                captures++;
            }
            graphics.dispose();
            ImageIO.write(gallery, "png", out.resolve("equipment-in-world.png").toFile());
            scene(atlas, shader, meshes, projection, view, light);
            ImageIO.write(read(), "png", out.resolve("stone-wall.png").toFile());
            // This production path used to substitute the same wooden rod for
            // every material and food item; the eight captures must differ.
            BufferedImage materialGallery = new BufferedImage(W / 2 * 4, H / 2 * 2, BufferedImage.TYPE_INT_RGB);
            var mg = materialGallery.createGraphics();
            Set<Integer> silhouettes = new HashSet<>();
            String[] materials = {"stick", "copper_ingot", "iron_ingot", "gold_ingot", "coal", "diamond", "beef", "cooked_beef"};
            for (int i = 0; i < materials.length; i++) {
                glViewport(0, 0, W, H); glClearColor(.16f, .22f, .27f, 1);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                hand.render(atlas, ItemStack.of(materials[i]), W / (float)H, 70, 1, 0,
                        0, false, false, 1, 1, 1, 0, 0);
                BufferedImage shot = read();
                int hash = Arrays.hashCode(shot.getRGB(0, 0, W, H, null, 0, W));
                if (!silhouettes.add(hash)) throw new AssertionError("Material still uses a shared placeholder: " + materials[i]);
                var label = shot.createGraphics(); label.setColor(Color.WHITE); label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
                label.drawString(materials[i], 20, 36); label.dispose();
                ImageIO.write(shot, "png", out.resolve("material-" + materials[i] + ".png").toFile());
                mg.drawImage(shot, i % 4 * W / 2, i / 4 * H / 2, W / 2, H / 2, null);
                captures++;
            }
            mg.dispose(); ImageIO.write(materialGallery, "png", out.resolve("materials-held.png").toFile());
            // Check animation/equip/inspection at three actual display aspect ratios.
            for (float aspect : new float[]{4f/3, 16f/9, 21f/9}) for (int i = 0; i < 6; i++) {
                glViewport(0, 0, W, H); glClearColor(.16f, .22f, .27f, 1);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                ItemStack held = ItemStack.of("copper_shovel");
                if (i == 5) held.setDamage(150);
                hand.render(atlas, held, aspect, 70, i == 4 ? .5f : 1,
                        i < 4 ? i / 4f : 0, 0, false, false, 1, 1, 1, 0, 0, i == 5 ? 1 : 0, .7f);
                ImageIO.write(read(), "png", out.resolve("pose-" + Math.round(aspect * 100) + "-" + i + ".png").toFile());
                captures++;
            }
            ui.destroy(); drops.destroy(); hand.destroy(); shader.destroy(); meshes.forEach(Mesh::destroy); atlas.destroy();
            checkGl();
            System.out.println("PASS: " + captures + " equipment/pose captures, 24 inventory variants, actual chunk/dropped-item passes; no GL errors");
        } finally { window.destroy(); }
    }

    private static void scene(TextureAtlas atlas, Shader shader, List<Mesh> meshes, Matrix4f projection,
                              Matrix4f view, SceneLighting light) {
        glViewport(0, 0, W, H); glClearColor(.34f, .47f, .59f, 1);
        glDepthMask(true); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE); glDisable(GL_BLEND);
        atlas.bind(0); shader.bind(); light.apply(shader);
        shader.setInt("uAtlas", 0); shader.setInt("uBlockArray", TextureAtlas.ARRAY_UNIT);
        shader.setMat4("uProjection", projection); shader.setMat4("uView", view); shader.setMat4("uModel", new Matrix4f());
        meshes.forEach(Mesh::render); shader.unbind();
    }

    private static BufferedImage read() {
        var pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels); checkGl();
        BufferedImage shot = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = (y * W + x) * 4;
            shot.setRGB(x, H - y - 1, (pixels.get(p) & 255) << 16 | (pixels.get(p + 1) & 255) << 8 | pixels.get(p + 2) & 255);
        }
        return shot;
    }

    private static void checkGl() {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new AssertionError("Equipment review GL error: " + error);
    }
}
