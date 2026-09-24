import com.mineclone.core.Window;
import com.mineclone.render.*;
import com.mineclone.world.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import static org.lwjgl.opengl.GL11.*;

/** Front/back/side captures of the actual live attack pose and its equipment. */
public final class RenderPlayerHandedness {
    private static final int W = 320, H = 360;
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length == 0 ? "out-test/handedness/after" : args[0]);
        Files.createDirectories(out);
        Window window = new Window("Player hand review", W, H, false); window.init();
        try {
            var atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
            var renderer = new PlayerRenderer();
            var shadow = new Shader(Shaders.SHADOW_MOB_VERTEX, Shaders.SHADOW_FRAGMENT);
            String[] directions = {"Front (right hand = screen left)", "Back (right hand = screen right)", "Right side", "Left side"};
            float[][] eyes = {{0,-3.5f}, {0,3.5f}, {3.5f,0}, {-3.5f,0}};
            String[] phases = {"Rest", "Wind-up", "Strike", "Recovery"};
            int[] frames = {0, 3, 11, 25};
            var sheet = new BufferedImage(W * 4, H * 4, BufferedImage.TYPE_INT_RGB);
            var g = sheet.createGraphics();
            for (int phase = 0; phase < 4; phase++) for (int side = 0; side < 4; side++) {
                var animation = new PlayerAnimation(); animation.reset(new Vector3f(), 0, true, false, false);
                if (phase > 0) animation.startSwing();
                for (int i = 0; i < frames[phase]; i++) animation.update(1f/120, new Vector3f(), 0, true, false, false, false);
                var proj = new Matrix4f().perspective((float)Math.toRadians(43), W/(float)H, .05f, 100);
                var view = new Matrix4f().lookAt(eyes[side][0], 1.2f, eyes[side][1], 0, .95f, 0, 0, 1, 0);
                var light = SceneLighting.firstPerson(1, 1); light.camPos.set(eyes[side][0], 1.2f, eyes[side][1]);
                light.fogStart=50; light.fogEnd=100; light.linearOut=0;
                glViewport(0, 0, W, H); glEnable(GL_DEPTH_TEST); glDepthMask(true);
                glClearColor(.1f,.15f,.19f,1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                var held = ItemStack.of("iron_pickaxe");
                renderer.render(proj, view, new Vector3f(), 0, 0, 0, animation.pose(), light, 1, 0, atlas, held);
                var shot = read();
                g.drawImage(shot, side * W, phase * H, null);
                g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                g.drawString(directions[side], side * W + 7, phase * H + 20);
                g.drawString(phases[phase], side * W + 7, phase * H + 38);
                ImageIO.write(shot, "png", out.resolve("pose-"+phase+"-view-"+side+".png").toFile());
                glClear(GL_DEPTH_BUFFER_BIT); glColorMask(false,false,false,false);
                renderer.renderShadow(shadow, new Matrix4f(proj).mul(view), new Vector3f(), 0, 0, 0, animation.pose(), atlas, held);
                glColorMask(true,true,true,true);
                var depth = BufferUtils.createFloatBuffer(W*H); glReadPixels(0,0,W,H,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
                int covered=0; for(int i=0;i<depth.capacity();i++) if(depth.get(i)<.99999f) covered++;
                if(covered<100)throw new AssertionError("Empty player shadow");
                if(glGetError()!=GL_NO_ERROR)throw new AssertionError("GL error in hand review");
            }
            g.dispose(); ImageIO.write(sheet,"png",out.resolve("handedness.png").toFile());
            renderer.destroy(); shadow.destroy(); atlas.destroy();
            System.out.println("PASS: 16 live-pose colour/shadow captures from front, back and both sides");
        } finally { window.destroy(); }
    }
    private static BufferedImage read() {
        var pixels=BufferUtils.createByteBuffer(W*H*4); glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        var image=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
            int i=(y*W+x)*4; image.setRGB(x,H-y-1,(pixels.get(i)&255)<<16|(pixels.get(i+1)&255)<<8|pixels.get(i+2)&255);
        }
        return image;
    }
}
