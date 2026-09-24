import com.mineclone.render.*;
import com.mineclone.world.World;
import com.mineclone.world.entity.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import java.nio.file.*;
import java.util.List;
import java.util.Random;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Reproducible real-GL animation review for every registered mob, not mocked silhouettes. */
public class RenderMobAnimations {
    static final int W=300,H=280;
    static final String[] MODES={"Idle / look", "Walk / hop", "Flight / attack", "Swim", "Death"};
    public static void main(String[] args) throws Exception {
        if(!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Mob animation review",0,0);
        if(window==0) throw new IllegalStateException("No GL window");
        glfwMakeContextCurrent(window); GL.createCapabilities();
        MobRenderer renderer=new MobRenderer(); World world=new World(1);
        Shader floor=new Shader("#version 330 core\nlayout(location=0) in vec3 a; uniform mat4 p,v; out vec3 w; void main(){w=a; gl_Position=p*v*vec4(a,1);}",
                "#version 330 core\nin vec3 w; out vec4 c; void main(){float k=mod(floor(w.x*4)+floor(w.z*4),2); c=vec4(vec3(.19,.24,.26)+k*.025,1);}");
        int vao=glGenVertexArrays(), vbo=glGenBuffers(); glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER,vbo);
        glBufferData(GL_ARRAY_BUFFER,new float[]{-4,-.002f,-4, 4,-.002f,-4, 4,-.002f,4, -4,-.002f,-4, 4,-.002f,4, -4,-.002f,4},GL_STATIC_DRAW);
        glVertexAttribPointer(0,3,GL_FLOAT,false,12,0); glEnableVertexAttribArray(0);
        Path output=Path.of("out-test/previews/mob-animations"); Files.createDirectories(output);
        Mob[] mobs=new Mob[MobType.values().length];
        for(int i=0;i<mobs.length;i++) mobs[i]=new Mob(MobType.values()[i],0,0,0,new Random(i));
        var writer=ImageIO.getImageWritersByFormatName("gif").next();
        try(var bytes=Files.newOutputStream(output.resolve("all-mobs.gif"));
            var stream=ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream); writer.prepareWriteSequence(null);
            for(int frame=0;frame<150;frame++) {
                int mode=frame/30; float t=(frame%30)/20f;
                BufferedImage sheet=new BufferedImage(W*4,H*2,BufferedImage.TYPE_INT_RGB);
                Graphics2D g=sheet.createGraphics();
                for(int i=0;i<mobs.length;i++) {
                    Mob m=mobs[i]; configure(m,mode,t);
                    g.drawImage(render(renderer,floor,vao,world,m),i%4*W,i/4*H,null);
                    g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,16));
                    g.drawString(m.type.name(),i%4*W+12,i/4*H+22);
                    g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));
                    g.drawString(MODES[mode],i%4*W+12,i/4*H+41);
                }
                g.dispose();
                if(frame%30==(mode==2?5:15)) ImageIO.write(sheet,"png",output.resolve("pose-"+mode+".png").toFile());
                var meta=writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(sheet),null);
                var root=(IIOMetadataNode)meta.getAsTree(meta.getNativeMetadataFormatName());
                var ctrl=(IIOMetadataNode)root.getElementsByTagName("GraphicControlExtension").item(0);
                ctrl.setAttribute("delayTime","5");
                if(frame==0) {
                    var ex=new IIOMetadataNode("ApplicationExtensions"); var loop=new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID","NETSCAPE");loop.setAttribute("authenticationCode","2.0");
                    loop.setUserObject(new byte[]{1,0,0});ex.appendChild(loop);root.appendChild(ex);
                }
                meta.setFromTree(meta.getNativeMetadataFormatName(),root);
                writer.writeToSequence(new javax.imageio.IIOImage(sheet,null,meta),null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();renderer.destroy();floor.destroy();glDeleteBuffers(vbo);glDeleteVertexArrays(vao);
            glfwDestroyWindow(window);glfwTerminate();
        }
        System.out.println("PASS: 1200 real-GL mob renders, 5 state sheets, 150 animation frames, no GL errors");
    }
    static void configure(Mob m,int mode,float t) {
        m.position.zero();m.yaw=-.55f;m.animationTime=t;m.walkedDistance=t*m.type.walkSpeed;
        m.walkAmount=mode==1?1:0;m.lookYaw=mode==0?(float)Math.sin(t*2)*.55f:0;
        m.grazeAmount=mode==0?(float)(.5-.5*Math.cos(t*3)):0;
        m.inWater=mode==3;m.onGround=mode!=3;m.airborneAmount=0;m.velocity.zero();
        m.attackSwing=0;m.dead=mode==4;m.topple=m.dead?Math.min(1.57f,t*3):0;
        if(mode==1 && m.type==MobType.RABBIT) {
            float phase=t%.55f;
            m.position.y=Math.max(0,3*phase-5.5f*phase*phase);m.velocity.y=3-11*phase;
            m.airborneAmount=Math.min(1,m.position.y*16);m.onGround=m.position.y<.001f;
        }
        if(mode==2) {
            if(m.type==MobType.BIRD || m.type==MobType.CHICKEN) {
                m.onGround=false;m.position.y=m.type.height*.55f;m.airborneAmount=1;
                m.velocity.y=(float)Math.sin(t*2)*1.5f;
            } else if(m.type==MobType.WOLF || m.type==MobType.ZOMBIE) {
                m.attackSwing=Math.max(0,Mob.ATTACK_SWING_TIME-t%.8f);
            } else {m.walkAmount=1;m.walkedDistance=t*2;}
        }
        if(mode==3) {m.position.y=.12f;m.walkAmount=.5f;}
    }
    static BufferedImage render(MobRenderer renderer,Shader floor,int vao,World world,Mob m) {
        glViewport(0,0,W,H);glClearColor(.13f,.18f,.22f,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);
        float size=Math.max(m.type.height*1.35f,m.type.width*1.8f);
        if(m.type==MobType.RABBIT) size=.95f;
        var proj=new Matrix4f().perspective((float)Math.toRadians(42),(float)W/H,.02f,100);
        var view=new Matrix4f().lookAt(0,size*.85f,-size*2.5f,0,size*.48f,0,0,1,0);
        floor.bind();floor.setMat4("p",proj);floor.setMat4("v",view);glBindVertexArray(vao);glDrawArrays(GL_TRIANGLES,0,6);floor.unbind();
        SceneLighting light=SceneLighting.firstPerson(1,1);light.camPos.set(0,size*.85f,-size*2.5f);
        light.fogStart=50;light.fogEnd=100;light.linearOut=0;
        renderer.render(proj,view,List.of(m),world,1,light);
        var pixels=BufferUtils.createByteBuffer(W*H*4);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        if(glGetError()!=GL_NO_ERROR) throw new IllegalStateException("GL error for "+m.type);
        BufferedImage result=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
            int at=(y*W+x)*4;result.setRGB(x,H-y-1,(pixels.get(at)&255)<<16|(pixels.get(at+1)&255)<<8|pixels.get(at+2)&255);
        }
        return result;
    }
}

