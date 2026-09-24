import com.mineclone.core.*;
import com.mineclone.game.Player;
import com.mineclone.render.*;
import com.mineclone.ui.*;
import com.mineclone.ui.container.*;
import com.mineclone.world.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Hidden native input/physics and creative UI regression checks; no saved world is touched. */
public class CreativeSmoke {
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    static void step(Input in,Player p,World w,int frames) {
        for(int i=0;i<frames;i++){in.update();p.update(.05f,w,in);}
    }
    static MenuTheme theme;
    static CreativeScreen screen;
    static float clock;
    static MenuAction frame(UiInput in) {
        glViewport(0,0,1280,720);glClearColor(.24f,.36f,.48f,1);glClear(GL_COLOR_BUFFER_BIT);
        theme.begin(1280,720,in,clock+=.016f,.016f);
        MenuAction action=screen.draw(theme);theme.end();
        check(glGetError()==GL_NO_ERROR,"UI GL error");return action;
    }
    static void shot(String name)throws Exception {
        frame(UiInput.NONE);
        var bytes=BufferUtils.createByteBuffer(1280*720*4);glReadPixels(0,0,1280,720,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
        var image=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<720;y++)for(int x=0;x<1280;x++) {int i=(y*1280+x)*4;
            image.setRGB(x,719-y,(bytes.get(i)&255)<<16|(bytes.get(i+1)&255)<<8|(bytes.get(i+2)&255));}
        Files.createDirectories(Path.of("out-test/previews/creative"));
        ImageIO.write(image,"png",Path.of("out-test/previews/creative/"+name+".png").toFile());
    }
    public static void main(String[] args)throws Exception {
        check(glfwInit(),"GLFW init");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(1280,720,"Creative verification",0,0);check(window!=0,"hidden window");
        glfwMakeContextCurrent(window);GL.createCapabilities();
        Input in=new Input(window);in.setGrabAllowed(false);
        World w=new World(12);Chunk c=w.getChunk(0,0);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=0;y<Chunk.SIZE_Y;y++)
            c.set(x,y,z,y<=10?BlockType.STONE:BlockType.AIR);
        Player p=new Player();p.respawn(8.5f,11.001f,8.5f);p.setGameMode(GameMode.CREATIVE);
        step(in,p,w,2);
        in.inject(GLFW_KEY_SPACE);step(in,p,w,1);step(in,p,w,1);
        in.inject(GLFW_KEY_SPACE);step(in,p,w,1);check(p.flying,"real double Space enables flight");
        // Fast flight against a one-block wall, at the maximum normal game dt.
        for(int x=0;x<16;x++)for(int y=11;y<22;y++)c.set(x,y,4,BlockType.STONE);
        p.position.set(8.5f,15,8.5f);p.camera.yaw=0;
        in.holdKey(GLFW_KEY_W,true);in.holdKey(GLFW_KEY_LEFT_CONTROL,true);step(in,p,w,40);
        check(p.position.z>=5.299f,"sprinting flight collides with one-block wall: "+p.position);
        in.holdKey(GLFW_KEY_W,false);in.holdKey(GLFW_KEY_LEFT_CONTROL,false);
        p.position.set(8.5f,15,8.5f);
        in.holdKey(GLFW_KEY_SPACE,true);step(in,p,w,2);check(p.position.y>15,"Space ascends");
        in.holdKey(GLFW_KEY_SPACE,false);step(in,p,w,1);
        in.holdKey(GLFW_KEY_LEFT_SHIFT,true);step(in,p,w,40);
        check(!p.flying && p.onGround && p.health==20,"landing ends flight without damage");
        in.holdKey(GLFW_KEY_LEFT_SHIFT,false);p.setGameMode(GameMode.SURVIVAL);
        in.inject(GLFW_KEY_F);step(in,p,w,1);check(!p.flying,"F cannot enable survival flight");
        // Restore creative and verify underwater contact is not hidden by flight.
        p.setGameMode(GameMode.CREATIVE);p.flying=true;p.position.set(8.5f,15,8.5f);
        c.set(8,15,8,BlockType.WATER);c.set(8,16,8,BlockType.WATER);step(in,p,w,1);
        check(p.eyeInWater && p.inWater && p.flying,"flying underwater keeps real water contact");
        System.out.println("PASS: native double jump, sprint flight wall collision, ascent, landing, survival, water");
        TextureAtlas atlas=new TextureAtlas(TextureAtlas.DEFAULT_PATH,false);
        Font font=new Font(AppPaths.path("assets/minecraft.ttf"),22f);
        Font small=new Font(AppPaths.path("assets/minecraft.ttf"),14f);
        UiRenderer ui=new UiRenderer();TextRenderer text=new TextRenderer(ui);
        ui.setAtlas(atlas.getTextureId());ui.registerFonts(font,small);
        theme=new MenuTheme(ui,text,font,small,atlas);
        Inventory inv=new Inventory();inv.set(0,ItemStack.of("iron_pickaxe"));inv.set(9,ItemStack.of("stone",64));
        PreviewContext ctx=new PreviewContext(inv);ctx.mode=GameMode.CREATIVE;screen=new CreativeScreen(ctx);
        shot("catalog");
        // Search is 49 pixels above the first catalog row.
        float[] source=screen.slotCenter("source",0);
        frame(UiInput.builder().at(source[0]+35,source[1]-54).click().build());
        MenuAction typed=frame(UiInput.builder().typed("diamond_pickaxe").key(GLFW_KEY_E).build());
        check(typed.kind!=MenuAction.Kind.BACK,"typing E in search must not close inventory");
        check(screen.menu().group("source").size()==1,"typed search filters catalog");
        shot("search");
        // A catalog keyboard shortcut creates a tool in the selected hotbar slot.
        theme.unfocus();frame(UiInput.NONE);source=screen.slotCenter("source",0);
        frame(UiInput.builder().at(source[0],source[1]).key(GLFW_KEY_2).build());
        check(inv.get(1)!=null && inv.get(1).item.id.toString().endsWith("diamond_pickaxe"),"number key equips catalog tool");
        screen.applyCategory(7);shot("backpack");
        check(screen.slotCenter("source",0)==null,"hidden catalog is not clickable in backpack");
        float[] storage=screen.slotCenter("main",0);check(storage!=null,"backpack visible");
        frame(UiInput.builder().at(storage[0],storage[1]).key(GLFW_KEY_3).build());
        check(inv.get(2)!=null && inv.get(2).block()==BlockType.STONE,"backpack hotbar swap");
        check(ctx.thrown.isEmpty(),"search and tabs never throw cursor contents");
        System.out.println("PASS: native catalog/search typing, number keys, backpack slots, three rendered snapshots");
        glfwDestroyWindow(window);glfwTerminate();
    }
}
