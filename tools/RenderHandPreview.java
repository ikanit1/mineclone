import com.mineclone.core.Window;
import com.mineclone.render.HeldItemRenderer;
import com.mineclone.render.TextureAtlas;
import com.mineclone.world.ItemStack;
import org.lwjgl.BufferUtils;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.lwjgl.opengl.GL11.*;

/** Draws the actual first-person pass in a hidden window for visual pose checks. */
public final class RenderHandPreview {
    public static void main(String[] args) throws Exception {
        Window window = new Window("Hand preview", 1200, 675, false);
        window.init();
        try {
            TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
            HeldItemRenderer hand = new HeldItemRenderer();
            try {
                Path output = Path.of("out-test/previews");
                Files.createDirectories(output);
                String prefix = args.length == 0 ? "hand" : args[0];
                if (prefix.equals("--all")) {
                    renderTemplates(hand, atlas, output);
                    return;
                }
                for (int frame = 0; frame < 6; frame++) {
                    glViewport(0, 0, 1200, 675);
                    glClearColor(0.38f, 0.52f, 0.66f, 1f);
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    hand.render(atlas, frame >= 2 ? ItemStack.of(args.length > 1 ? args[1] : "iron_pickaxe") : null,
                            1200f / 675f, 70f, frame == 4 ? 0.5f : 1f,
                            frame == 1 || frame == 3 ? 0.5f : 0f,
                            0f, false, false, 1f, 1f, 1f, 0f, 0f, frame == 5 ? 1f : 0f, 0.7f);
                    var pixels = BufferUtils.createByteBuffer(1200 * 675 * 4);
                    glReadPixels(0, 0, 1200, 675, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                    BufferedImage image = new BufferedImage(1200, 675, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < 675; y++) for (int x = 0; x < 1200; x++) {
                        int i = (y * 1200 + x) * 4;
                        image.setRGB(x, 674 - y, ((pixels.get(i) & 255) << 16)
                                | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
                    }
                    Path path = output.resolve(prefix + "-" + frame + ".png");
                    ImageIO.write(image, "png", path.toFile());
                    if (glGetError() != GL_NO_ERROR) throw new AssertionError("Hand render GL error");
                    System.out.println(path.toAbsolutePath());
                }
            } finally { hand.destroy(); atlas.destroy(); }
        } finally { window.destroy(); }
    }

    private static void renderTemplates(HeldItemRenderer hand, TextureAtlas atlas, Path output) throws Exception {
        int width = 480, height = 270;
        String[] materials = {"wooden", "stone", "iron", "diamond", "gold", "copper"};
        String[] kinds = {"pickaxe", "axe", "shovel"};
        // Седьмой ряд — оружие: меч и лук на двух стадиях натяжения. Лук
        // держат как инструмент, хотя инструментальной части у него нет, и
        // увидеть это можно только кадром.
        String[][] weapons = {
                { "iron_sword", "0" }, { "bow", "0" }, { "bow", "1" }
        };
        BufferedImage gallery = new BufferedImage(width * 3, height * 7, BufferedImage.TYPE_INT_RGB);
        var graphics = gallery.createGraphics();
        int cells = materials.length * kinds.length + weapons.length;
        for (int cell = 0; cell < cells; cell++) {
            int row = cell / kinds.length, col = cell % kinds.length;
            boolean weapon = cell >= materials.length * kinds.length;
            String item = weapon
                    ? weapons[cell - materials.length * kinds.length][0]
                    : materials[row] + "_" + kinds[col];
            hand.setBowDraw(weapon
                    ? Float.parseFloat(weapons[cell - materials.length * kinds.length][1]) : 0f);
            glViewport(0, 0, width, height);
            glClearColor(0.26f, 0.36f, 0.43f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            hand.render(atlas, ItemStack.of(item), width / (float) height, 70, 1, 0,
                    0, false, false, 1, 1, 1, 0, 0);
            var pixels = BufferUtils.createByteBuffer(width * height * 4);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            BufferedImage shot = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                shot.setRGB(x, height - y - 1, (pixels.get(i) & 255) << 16
                        | (pixels.get(i + 1) & 255) << 8 | pixels.get(i + 2) & 255);
            }
            graphics.drawImage(shot, col * width, row * height, null);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 17));
            graphics.drawString(item, col * width + 12, row * height + 24);
            graphics.drawLine(col * width + width / 2 - 4, row * height + height / 2,
                    col * width + width / 2 + 4, row * height + height / 2);
            graphics.drawLine(col * width + width / 2, row * height + height / 2 - 4,
                    col * width + width / 2, row * height + height / 2 + 4);
            if (glGetError() != GL_NO_ERROR) throw new AssertionError(item + " OpenGL error");
        }
        graphics.dispose();
        ImageIO.write(gallery, "png", output.resolve("tool-templates.png").toFile());
        System.out.println("PASS: 18 tool templates; " + output.resolve("tool-templates.png").toAbsolutePath());
    }
}
