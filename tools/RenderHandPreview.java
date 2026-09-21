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
                for (int frame = 0; frame < 3; frame++) {
                    glViewport(0, 0, 1200, 675);
                    glClearColor(0.38f, 0.52f, 0.66f, 1f);
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    hand.render(atlas, frame == 2 ? ItemStack.of(args.length > 1 ? args[1] : "iron_pickaxe") : null,
                            1200f / 675f, 70f, 1f, frame == 1 ? 0.5f : 0f,
                            0f, false, false, 1f, 1f, 1f, 0f, 0f);
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
}
