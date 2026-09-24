import com.mineclone.net.RemotePlayer;
import com.mineclone.render.PlayerRenderer;
import com.mineclone.render.SceneLighting;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Real GL equipment captures from multiple directions, including the shadow pass. */
public final class RenderHeldPlayerPreview {
    private static final int W=360,H=360;
    public static void main(String[] args) throws Exception {
        Path output=Path.of("out-test/previews/third-person-items"); Files.createDirectories(output);
        if(!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Equipment review",0,0);glfwMakeContextCurrent(window);GL.createCapabilities();
        PlayerRenderer renderer=new PlayerRenderer();
        var atlas=new com.mineclone.render.TextureAtlas(com.mineclone.render.TextureAtlas.DEFAULT_PATH,false);
        var shadow=new com.mineclone.render.Shader(com.mineclone.render.Shaders.SHADOW_MOB_VERTEX,
                com.mineclone.render.Shaders.SHADOW_FRAGMENT);
        var items=new java.util.ArrayList<com.mineclone.world.ItemStack>();
        for(var item:com.mineclone.item.Items.get().all()) if(item.tool!=null) items.add(new com.mineclone.world.ItemStack(item,1));
        items.add(com.mineclone.world.ItemStack.of("stone"));items.add(com.mineclone.world.ItemStack.of("torch"));
        for(var item:com.mineclone.item.Items.get().all()) if(item.food!=null){items.add(new com.mineclone.world.ItemStack(item,1));break;}
        int cols=6,rows=(items.size()+cols-1)/cols;
        BufferedImage gallery=new BufferedImage(W*cols,H*rows,BufferedImage.TYPE_INT_RGB);var g=gallery.createGraphics();
        int checks=0;
        for(int i=0;i<items.size();i++) {
            var held=items.get(i);
            for(float yaw:new float[]{.55f,1.6f}) {
                var empty=render(renderer,yaw,yaw,0,.2f,.7f,.45f,atlas,null);
                var shot=render(renderer,yaw,yaw,0,.2f,.7f,.45f,atlas,held);
                int changed=0;for(int y=0;y<H;y++)for(int x=0;x<W;x++) if(empty.getRGB(x,y)!=shot.getRGB(x,y))changed++;
                if(changed<20)throw new IllegalStateException("Invisible item: "+held.item.id+" at "+yaw);
                checks++;
                if(yaw==.55f){g.drawImage(shot,i%cols*W,i/cols*H,null);g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15));g.drawString(held.item.id.toString(),i%cols*W+10,i/cols*H+22);}
            }
            var ls=new Matrix4f().ortho(-2,2,-2,2,.1f,10)
                    .mul(new Matrix4f().lookAt(-3,3,-3,0,1,0,0,1,0));
            var before=shadowDepth(renderer,shadow,atlas,null,ls);
            var after=shadowDepth(renderer,shadow,atlas,held,ls);
            int shadowChanged=0;
            for(int pixel=0;pixel<W*H;pixel++)if(Math.abs(before.get(pixel)-after.get(pixel))>1e-5)shadowChanged++;
            if(shadowChanged<3)throw new IllegalStateException("Missing item shadow: "+held.item.id);

        }
        g.dispose();ImageIO.write(gallery,"png",output.resolve("all-items.png").toFile());
        var held=com.mineclone.world.ItemStack.of("iron_pickaxe");
        var sheet=new BufferedImage(W*4,H*2,BufferedImage.TYPE_INT_RGB);g=sheet.createGraphics();
        String[] labels={"Empty", "Pickaxe", "Walking", "Swing", "Side", "Back", "Block", "Food"};
        for(int i=0;i<8;i++) {
            float yaw=i==4?1.6f:i==5?3.1f:.55f;
            var item=i==0?null:i==6?com.mineclone.world.ItemStack.of("stone"):i==7?items.get(items.size()-1):held;
            g.drawImage(render(renderer,yaw,yaw,0,.35f,i==2?1:0,i==3?1:0,atlas,item),i%4*W,i/4*H,null);
            g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,18));g.drawString(labels[i],i%4*W+12,i/4*H+25);
        }
        g.dispose();ImageIO.write(sheet,"png",output.resolve("poses.png").toFile());
        shadow.destroy();renderer.destroy();glfwDestroyWindow(window);glfwTerminate();
        System.out.println("PASS: "+items.size()+" items, "+checks+" visible equipment comparisons and shadow draws; no GL errors");
    }

    private static java.nio.FloatBuffer shadowDepth(PlayerRenderer renderer, com.mineclone.render.Shader shader,
            com.mineclone.render.TextureAtlas atlas, com.mineclone.world.ItemStack held, Matrix4f ls) {
        glColorMask(false,false,false,false);glClear(GL_DEPTH_BUFFER_BIT);
        renderer.renderShadow(shader,ls,new Vector3f(),.5f,.5f,0,.3f,1,.6f,atlas,held);
        glColorMask(true,true,true,true);
        var depth=BufferUtils.createFloatBuffer(W*H);
        glReadPixels(0,0,W,H,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
        if(glGetError()!=GL_NO_ERROR)throw new IllegalStateException("Shadow GL error");
        return depth;
    }

    private static BufferedImage render(PlayerRenderer renderer, float body, float head, float pitch,
                                         float distance, float amount, float swing, com.mineclone.render.TextureAtlas atlas, com.mineclone.world.ItemStack held) {
        glViewport(0, 0, W, H);
        glClearColor(0.13f, 0.18f, 0.22f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        SceneLighting lighting = SceneLighting.firstPerson(1, 1);
        lighting.camPos.set(0, 1.3f, -3.5f);
        lighting.fogStart = 50;
        lighting.fogEnd = 100;
        lighting.linearOut = 0;
        renderer.render(new Matrix4f().perspective((float) Math.toRadians(43), 1, 0.05f, 100),
                new Matrix4f().lookAt(0, 1.3f, -3.5f, 0, 0.95f, 0, 0, 1, 0), new Vector3f(),
                body, head, pitch, distance, amount, swing, lighting, 1, 0, atlas, held);
        var pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("OpenGL error");
        BufferedImage result = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int visible = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = (y * W + x) * 4;
            int rgb = (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255);
            result.setRGB(x, H - y - 1, rgb);
            if (rgb != ((pixels.get(0) & 255) << 16 | (pixels.get(1) & 255) << 8 | (pixels.get(2) & 255))) visible++;
        }
        if (visible < 1000) throw new IllegalStateException("Player render is blank");
        return result;
    }
}
