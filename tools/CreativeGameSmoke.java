import com.mineclone.core.*;
import com.mineclone.game.*;
import com.mineclone.world.*;
import java.lang.reflect.*;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;

/** Exercises the real Game interaction path with injected mouse input and an isolated world. */
public class CreativeGameSmoke {
    static Object get(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static void call(Game g,String method,Class<?> type,Object value)throws Exception {
        if(method.equals("handleInteraction")){
            // Clicks live in the interaction controller since BLK-03.
            Object c=get(g,"interaction");Method m=c.getClass().getDeclaredMethod("update",float.class);
            m.setAccessible(true);m.invoke(c,value);return;
        }
        Method m=Game.class.getDeclaredMethod(method,type);m.setAccessible(true);m.invoke(g,value);
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        System.setProperty("mineclone.savesDir",Path.of("out-test/creative-game/saves").toAbsolutePath().toString());
        Window window=new Window("Creative gameplay checks",960,540,false);window.init();
        try {
            Game g=new Game(window,false);Input in=(Input)get(g,"input");in.setGrabAllowed(false);
            World w=new World(42);Chunk c=w.getChunk(0,0);
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=0;y<Chunk.SIZE_Y;y++)
                c.set(x,y,z,y<=10?BlockType.STONE:BlockType.AIR);
            set(g,"world",w);Player p=(Player)get(g,"player");p.respawn(8.5f,11.001f,8.5f);
            p.camera.yaw=0;p.camera.pitch=0;
            call(g,"setGameMode",GameMode.class,GameMode.CREATIVE);
            Inventory inv=(Inventory)get(g,"inventory");inv.set(0,ItemStack.of("iron_pickaxe"));
            c.set(8,12,5,BlockType.STONE);c.set(8,12,4,BlockType.BEDROCK);
            in.injectMouseButton(0,true);in.update();call(g,"handleInteraction",float.class,.01f);
            check(w.getBlock(8,12,5)==BlockType.AIR,"creative stone breaks on first press");
            for(int i=0;i<25;i++){in.update();call(g,"handleInteraction",float.class,.01f);}
            check(w.getBlock(8,12,4)==BlockType.AIR,"holding breaks next target including bedrock");
            check(inv.get(0).damage()==0,"creative tool does not wear");
            check(((java.util.List<?>)get(g,"items")).isEmpty(),"creative block breaking creates no drops");
            in.injectMouseButton(0,false);in.update();call(g,"handleInteraction",float.class,.01f);
            c.set(8,12,2,BlockType.STONE);inv.set(0,ItemStack.of("stone",1));
            in.injectMouseButton(1,true);
            for(int i=0;i<90;i++){in.update();call(g,"handleInteraction",float.class,.01f);}
            check(w.getBlock(8,12,3)==BlockType.STONE && w.getBlock(8,12,6)==BlockType.STONE,"held RMB builds successive blocks");
            check(inv.get(0).count==1,"building does not consume creative stock");
            check(w.getBlock(8,12,8)==BlockType.AIR,"cannot place inside player");
            in.injectMouseButton(1,false);in.update();call(g,"handleInteraction",float.class,.01f);
            for(int z=2;z<8;z++)c.set(8,12,z,BlockType.AIR);
            c.set(8,12,5,BlockType.CHEST);in.holdKey(GLFW_KEY_LEFT_SHIFT,true);in.injectMouseButton(1,true);
            in.update();call(g,"handleInteraction",float.class,.01f);
            check(w.getBlock(8,12,6)==BlockType.STONE,"Shift builds against a chest");
            check(get(g,"activeWindow")==null,"Shift does not open chest");
            in.injectMouseButton(1,false);in.holdKey(GLFW_KEY_LEFT_SHIFT,false);in.update();
            call(g,"executeCommand",String.class,"/gm 0");
            check(!p.isCreative() && !p.flying,"numeric survival command updates player immediately");
            c.set(8,12,6,BlockType.BEDROCK);in.injectMouseButton(0,true);in.update();
            call(g,"handleInteraction",float.class,.05f);
            check(w.getBlock(8,12,6)==BlockType.BEDROCK,"survival cannot break bedrock");
            call(g,"executeCommand",String.class,"/gm 1");check(p.isCreative(),"numeric creative command");
            // BLK-03: a door by real clicks — placed whole, opened whole — and a
            // torch placed through the open doorway (BLK-02: the aim misses the panel).
            in.injectMouseButton(0,false);in.update();
            for(int z=0;z<8;z++)for(int y=11;y<14;y++)c.set(8,y,z,BlockType.AIR);
            p.respawn(8.5f,11.001f,8.5f);p.camera.yaw=0;
            inv.set(0,ItemStack.of("door"));
            p.camera.pitch=(float)Math.atan2(12.621-11.0,8.5-6.5);
            in.injectMouseButton(1,true);in.update();call(g,"handleInteraction",float.class,.01f);
            in.injectMouseButton(1,false);in.update();call(g,"handleInteraction",float.class,.01f);
            check(w.getBlock(8,11,6)==BlockType.DOOR_CLOSED && w.getBlock(8,12,6)==BlockType.DOOR_CLOSED
                    && (w.getBlockMeta(8,12,6)&4)!=0,"a right click places a whole door");
            p.camera.pitch=(float)Math.atan2(12.621-11.5,8.5-6.09);
            in.injectMouseButton(1,true);in.update();call(g,"handleInteraction",float.class,.01f);
            in.injectMouseButton(1,false);in.update();call(g,"handleInteraction",float.class,.01f);
            check(w.getBlock(8,11,6)==BlockType.DOOR_OPEN && w.getBlock(8,12,6)==BlockType.DOOR_OPEN,
                    "a right click on the panel opens both halves");
            inv.set(0,ItemStack.of("torch"));
            p.camera.pitch=(float)Math.atan2(12.621-11.0,8.5-4.5);
            in.injectMouseButton(1,true);in.update();call(g,"handleInteraction",float.class,.01f);
            in.injectMouseButton(1,false);in.update();call(g,"handleInteraction",float.class,.01f);
            check(w.getBlock(8,11,4)==BlockType.TORCH && w.getBlockMeta(8,11,4)==0,
                    "a torch goes on the floor beyond the open doorway");
            check(w.getBlock(8,11,6)==BlockType.DOOR_OPEN,"aiming through the doorway left the door alone");
            System.out.println("PASS: Game instant/repeated breaking, bedrock, no tool wear/drops, repeated infinite placement, body collision, Shift against chest, numeric mode commands, whole door by clicks, torch through an open doorway");
        } finally {window.destroy();}
    }
}
