import com.mineclone.render.*;
import com.mineclone.world.*;
import org.joml.*;
import org.lwjgl.opengl.GL;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Isolated CPU submission comparison for eight bodies, with GL completion outside the timed region. */
public final class BenchArmor {
    public static void main(String[] args){
        if(!glfwInit())throw new IllegalStateException("GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(360,360,"Armor CPU benchmark",0,0);if(window==0)throw new IllegalStateException("window");glfwMakeContextCurrent(window);GL.createCapabilities();
        System.out.println("GL renderer: "+glGetString(GL_RENDERER));
        var renderer=new PlayerRenderer();var projection=new Matrix4f().perspective(1,1,.1f,20);var view=new Matrix4f().lookAt(0,1,-4,0,1,0,0,1,0);var pose=new PlayerAnimation.Pose();var lighting=SceneLighting.firstPerson(1,1);var positions=new Vector3f[8];for(int i=0;i<8;i++)positions[i]=new Vector3f((i%4-1.5f)*.6f,0,(i/4)*1.2f);
        var equipment=new Equipment();String[] parts={"helmet","chestplate","leggings","boots"};for(int i=0;i<4;i++)equipment.set(i,ItemStack.of("iron_"+parts[i]));
        var baseline=new double[200];var armored=new double[200];
        for(int i=-60;i<200;i++){for(int pass=0;pass<2;pass++){glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glFinish();long begin=System.nanoTime();for(var position:positions)renderer.render(projection,view,position,0,0,0,pose,lighting,1,0,null,null,pass==0?null:equipment);double elapsed=(System.nanoTime()-begin)/1e6;glFinish();if(i>=0)(pass==0?baseline:armored)[i]=elapsed;}}
        Arrays.sort(baseline);Arrays.sort(armored);double delta=armored[100]-baseline[100];System.out.printf(Locale.ROOT,"Eight-player median CPU submit: bare %.4f ms, armored %.4f ms, delta %.4f ms; target +0.1 ms%n",baseline[100],armored[100],delta);
        if(glGetError()!=GL_NO_ERROR)throw new IllegalStateException("GL error");renderer.destroy();glfwDestroyWindow(window);glfwTerminate();
    }
}
