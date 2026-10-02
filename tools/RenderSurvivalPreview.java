import com.mineclone.render.*;
import com.mineclone.world.*;
import com.mineclone.world.entity.*;
import org.joml.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Native color/shadow acceptance captures: all armor directions, eating and the four baby models. */
public final class RenderSurvivalPreview {
    static final int W=360,H=360;
    static final Path OUT=Path.of("out-test/previews/survival");
    static final Matrix4f PROJ=new Matrix4f().perspective((float)java.lang.Math.toRadians(43),1,.05f,100);
    static final Matrix4f VIEW=new Matrix4f().lookAt(0,1.3f,-3.5f,0,.95f,0,0,1,0);
    static final Vector3f ZERO=new Vector3f();
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);if(!glfwInit())throw new IllegalStateException("GLFW failed");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Survival review",0,0);if(window==0)throw new IllegalStateException("GL window failed");glfwMakeContextCurrent(window);GL.createCapabilities();
        var atlas=new TextureAtlas(TextureAtlas.DEFAULT_PATH,false);var renderer=new PlayerRenderer();var hand=new HeldItemRenderer();var mobRenderer=new MobRenderer();var shadow=new Shader(Shaders.SHADOW_MOB_VERTEX,Shaders.SHADOW_FRAGMENT);
        var lighting=SceneLighting.firstPerson(1,1);lighting.camPos.set(0,1.3f,-3.5f);lighting.fogStart=50;lighting.fogEnd=100;lighting.linearOut=0;
        var pose=new PlayerAnimation.Pose();var lightSpace=new Matrix4f().ortho(-2,2,-2,2,.1f,10).mul(new Matrix4f().lookAt(-3,3,-3,0,1,0,0,1,0));
        int frames=0;
        for(String material:List.of("leather","copper","iron","diamond")){
            var equipment=kit(material);var sheet=new BufferedImage(W*4,H*4,BufferedImage.TYPE_INT_RGB);var g=sheet.createGraphics();
            for(int angle=0;angle<16;angle++){float yaw=angle*(float)java.lang.Math.PI/8;clear();renderer.render(PROJ,VIEW,ZERO,yaw,yaw,0,pose,lighting,1,0,atlas,null,equipment);var image=grab();g.drawImage(image,angle%4*W,angle/4*H,null);frames++;}
            g.dispose();ImageIO.write(sheet,"png",OUT.resolve("armor-"+material+"-16.png").toFile());
            glEnable(GL_DEPTH_TEST);glColorMask(false,false,false,false);glClear(GL_DEPTH_BUFFER_BIT);renderer.renderShadow(shadow,lightSpace,ZERO,.6f,.6f,0,pose,atlas,null,null);var before=depth();glClear(GL_DEPTH_BUFFER_BIT);renderer.renderShadow(shadow,lightSpace,ZERO,.6f,.6f,0,pose,atlas,null,equipment);var after=depth();glColorMask(true,true,true,true);int changes=0;for(int i=0;i<W*H;i++)if(java.lang.Math.abs(before.get(i)-after.get(i))>1e-5)changes++;if(changes<100)throw new IllegalStateException("Missing armor shadow: "+material);System.out.println("armor shadow "+material+": "+changes+" changed depth pixels");
            hand.setChestArmor(equipment.get(1));clear();hand.render(atlas,null,1,70,1,0,0,false,false,1,1,1,0,0);save("sleeve-"+material);frames++;
        }
        var animation=new PlayerAnimation();animation.reset(ZERO,0,true,false,false);animation.setEating(.55f);clear();renderer.render(PROJ,VIEW,ZERO,-.8f,-.8f,0,animation.pose(),lighting,1,0,atlas,ItemStack.of("bread"),kit("copper"));save("eating-third");frames++;
        hand.setEating(.55f);clear();hand.render(atlas,ItemStack.of("bread"),1,70,1,0,0,false,false,1,1,1,0,0);save("eating-first");frames++;
        var world=new World(1);for(var type:List.of(MobType.COW,MobType.PIG,MobType.SHEEP,MobType.CHICKEN)){
            var baby=new Mob(type,0,0,0,new java.util.Random(1));baby.age=-1200;baby.onGround=true;baby.yaw=.6f;var adult=new Mob(type,0,0,0,new java.util.Random(1));adult.yaw=.6f;adult.onGround=true;
            clear();mobRenderer.render(PROJ,VIEW,List.of(baby),world,1,lighting);save("baby-"+type.name().toLowerCase());frames++;
            clear();mobRenderer.render(PROJ,VIEW,List.of(adult),world,1,lighting);save("adult-"+type.name().toLowerCase());frames++;
        }
        shadow.destroy();mobRenderer.destroy();hand.destroy();renderer.destroy();glfwDestroyWindow(window);glfwTerminate();System.out.println("PASS: "+frames+" survival GL color captures, four armor depth comparisons; no GL errors");
    }
    static Equipment kit(String material){var equipment=new Equipment();String[] names={"helmet","chestplate","leggings","boots"};for(int i=0;i<4;i++)equipment.set(i,ItemStack.of(material+"_"+names[i]));return equipment;}
    static void clear(){glViewport(0,0,W,H);glClearColor(.13f,.18f,.22f,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glEnable(GL_DEPTH_TEST);}
    static java.nio.FloatBuffer depth(){var data=BufferUtils.createFloatBuffer(W*H);glReadPixels(0,0,W,H,GL_DEPTH_COMPONENT,GL_FLOAT,data);checkError();return data;}
    static void checkError(){if(glGetError()!=GL_NO_ERROR)throw new IllegalStateException("OpenGL error");}
    static void save(String name)throws Exception{ImageIO.write(grab(),"png",OUT.resolve(name+".png").toFile());}
    static BufferedImage grab(){var data=BufferUtils.createByteBuffer(W*H*4);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,data);checkError();var image=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);int visible=0;for(int y=0;y<H;y++)for(int x=0;x<W;x++){int i=(y*W+x)*4;int rgb=(data.get(i)&255)<<16|(data.get(i+1)&255)<<8|data.get(i+2)&255;image.setRGB(x,H-y-1,rgb);if(rgb!=((data.get(0)&255)<<16|(data.get(1)&255)<<8|data.get(2)&255))visible++;}if(visible<100)throw new IllegalStateException("Blank capture");return image;}
}
