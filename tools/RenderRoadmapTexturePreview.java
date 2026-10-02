import com.mineclone.core.Window;
import com.mineclone.render.TextureAtlas;
import com.mineclone.render.UiRenderer;
import org.lwjgl.BufferUtils;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.HashSet;
import static org.lwjgl.opengl.GL33.*;

/** RND-09: real GPU atlas and inventory UI pass for every newly imported sprite. */
public final class RenderRoadmapTexturePreview {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length == 0 ? "out-test/roadmap-textures/native.png" : args[0]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        int width = 960, height = 650;
        Window window = new Window("Roadmap textures", width, height, false);
        window.init();
        try {
            TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
            UiRenderer ui = new UiRenderer();
            ui.setAtlas(atlas.getTextureId());
            glViewport(0, 0, width, height);
            glClearColor(.06f, .075f, .09f, 1);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            ui.begin(width, height);
            for (int tile = 139; tile <= 173; tile++) {
                int n = tile - 139, x = 10 + n % 8 * 120, y = 10 + n / 8 * 130;
                ui.quad(x, y, 108, 100, .12f, .15f, .18f, 1);
                float[] uv = TextureAtlas.uv(tile);
                ui.texQuad(x + 6, y + 2, 96, 96, atlas.getTextureId(), uv[0], uv[1], uv[2], uv[3], 1, 1, 1, 1);
            }
            ui.end();
            var pixels = BufferUtils.createByteBuffer(width * height * 4);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("atlas/UI GL error");
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var colours = new HashSet<Integer>();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int i = ((height - 1 - y) * width + x) * 4;
                int rgb = (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255);
                image.setRGB(x, y, rgb); colours.add(rgb);
            }
            if (colours.size() < 100) throw new AssertionError("empty atlas preview");
            var text = image.createGraphics();
            text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10)); text.setColor(Color.WHITE);
            for (int tile = 139; tile <= 173; tile++) {
                int n = tile - 139;
                text.drawString(tile + " " + TextureAtlas.TILE_NAMES[tile], 10 + n % 8 * 120, 124 + n / 8 * 130);
            }
            text.dispose();
            ImageIO.write(image, "png", out.toFile());
            ui.destroy(); atlas.destroy();
            System.out.println("PASS: 35 new tiles through production atlas and UI, " + colours.size() + " colours, no GL errors; " + out);
        } finally { window.destroy(); }
    }
}
