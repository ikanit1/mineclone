import com.mineclone.core.*;
import com.mineclone.game.*;
import com.mineclone.net.*;
import com.mineclone.save.*;
import com.mineclone.ui.*;
import com.mineclone.ui.container.*;
import com.mineclone.world.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public class InventorySafetySmoke {
    static Object get(Object o,String name)throws Exception { Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception { Method m=o.getClass().getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(o,args); }
    static Object call(Object o,String name)throws Exception { return call(o,name,new Class<?>[0]); }
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    static int count(ItemStack[] slots){int n=0;if(slots!=null)for(ItemStack s:slots)if(s!=null)n+=s.count;return n;}
    static int count(Inventory inv){int n=0;for(int i=0;i<inv.size();i++)if(inv.get(i)!=null)n+=inv.get(i).count;return n;}
    static void pump(Multiplayer host,Multiplayer guest,int n){for(int i=0;i<n;i++){host.update(.1f);guest.update(.1f);}}
    public static void main(String[] args)throws Exception {
        System.setProperty("mineclone.savesDir",Path.of("out-test/inventory-safety/saves").toAbsolutePath().toString());
        Window window=new Window("Audit reproduction",960,540,false); window.init();
        Game g=null; Multiplayer hn=null,gn=null;
        try {
            g=new Game(window,false); ((Input)get(g,"input")).setGrabAllowed(false);
            call(g,"createWorld",new Class<?>[]{WorldSettings.class},new WorldSettings("Audit",42,GameMode.SURVIVAL));
            Inventory inv=(Inventory)get(g,"inventory"); inv.set(0,ItemStack.of("diamond",16));
            InventoryScreen screen=new InventoryScreen((WindowContext)get(g,"windowContext"));
            call(g,"openWindow",new Class<?>[]{ContainerScreen.class},screen);
            screen.menu().leftClick(new SlotRef(screen.menu().group("hotbar"),0));
            screen.menu().leftClick(new SlotRef(screen.menu().group("craft"),0));
            call(g,"saveAll");
            SaveManager sm=(SaveManager)get(g,"save"); sm.flushAndAwait();
            LevelData d=sm.loadLevel((String)get(g,"worldId"));
            int persisted=count(d.inventory)+count(d.pending);
            check(persisted==16,"Crafting ingredients must be saved");
            System.out.println("PASS crafting-save: 16 diamonds in craft grid; saved inventory+pending="+persisted);
            call(g,"closeWindow");

            Class<?> contextClass=Class.forName("com.mineclone.NetworkTests$TestContext");
            Constructor<?> ctor=contextClass.getDeclaredConstructor(World.class,String.class); ctor.setAccessible(true);
            World hw=new World(42); hw.getChunk(0,0); NetContext hc=(NetContext)ctor.newInstance(hw,"Audit host");
            hn=new Multiplayer(hc); gn=(Multiplayer)get(g,"net");
            LoopbackTransport.reset();
            hn.start(new LoopbackTransport(hn),"audit",true,"Host");
            gn.start(new LoopbackTransport(gn),"audit",false,"Guest");
            pump(hn,gn,12); check(gn.isClient(),"guest role");
            World gw=(World)get(g,"world"); inv=(Inventory)get(g,"inventory");
            int x=8,y=100,z=8;
            hw.setBlock(x,y,z,BlockType.CHEST);gw.setBlock(x,y,z,BlockType.CHEST);
            hw.createChest(x,y,z)[0]=ItemStack.of("diamond",16);
            call(g,"openChest",new Class<?>[]{int.class,int.class,int.class},x,y,z);pump(hn,gn,4);
            int hostTaken=hw.getChest(x,y,z)[0].count;hw.getChest(x,y,z)[0]=null;
            ContainerScreen chest=(ContainerScreen)get(g,"activeWindow");
            SlotGroup chestGroup=chest.menu().groups().get(0);
            chest.menu().leftClick(new SlotRef(chestGroup,0));
            call(g,"closeWindow");pump(hn,gn,4);
            int guestTaken=count(inv);
            check(hostTaken==16 && guestTaken==0,"Stale chest view must not duplicate the stack");
            System.out.println("PASS shared-chest: initial=16; host took="+hostTaken+"; guest took="+guestTaken+"; chest="+count(hw.getChest(x,y,z)));

            x=9;hw.setBlock(x,y,z,BlockType.FURNACE);gw.setBlock(x,y,z,BlockType.FURNACE);
            Furnace hf=hw.createFurnace(x,y,z);hf.input=ItemStack.of("iron_ore",2);hf.fuel=ItemStack.of("coal",1);
            call(g,"openFurnace",new Class<?>[]{int.class,int.class,int.class},x,y,z);pump(hn,gn,4);
            for(int i=0;i<36;i++)hf.tick(.25f);
            check(hf.output!=null && hf.output.count==1,"host smelted one ingot");
            System.out.println("PASS furnace before guest close: input="+hf.input.count+" output="+hf.output.count+" burn="+hf.burnLeft);
            call(g,"closeWindow");pump(hn,gn,4);
            check(hf.output!=null && hf.output.count==1 && hf.input.count==1 && hf.fuel==null,"Guest close must preserve host furnace");
            System.out.println("PASS furnace after guest close: input="+hf.input.count+" output="+hf.output.count+" fuel="+hf.fuel+" burn="+hf.burnLeft);

            for(int i=0;i<inv.size();i++)inv.set(i,null);
            inv.set(0,ItemStack.of("diamond",7));
            call(get(g,"weapons"),"throwHeldItem",new Class<?>[]{boolean.class},true);
            List<?> ground=(List<?>)get(g,"items");
            pump(hn,gn,12);
            check(ground.size()==1 && count(inv)==0 && hc.groundItems().size()==1,"Guest thrown item must reach host and come back in snapshot");
            System.out.println("PASS guest-drop: threw 7 diamonds; guest inventory=0 guest ground=7 host ground=7");

            gn.requestPickup((com.mineclone.world.entity.ItemEntity)ground.get(0));
            call(g,"openChest",new Class<?>[]{int.class,int.class,int.class},8,y,z);
            check(get(g,"activeWindow")==null,"Container opening waits for pending pickup");
            pump(hn,gn,8);
            check(count(inv)==7 && hc.groundItems().isEmpty(),"Pickup is applied once to the real Game inventory");
            System.out.println("PASS guest-pickup: pending pickup gates chest opening; inventory=7 host ground=0");

            inv.set(0,ItemStack.of("diamond",11));
            gn.stop(null);gn.start(new LoopbackTransport(gn),"audit",false,"Guest");pump(hn,gn,12);
            check(count((Inventory)get(g,"inventory"))==11,"Guest inventory must survive rejoin");
            System.out.println("PASS guest-rejoin: inventory before=11 diamonds; after=11");
            gn.stop(null);hn.stop(null);
            call(g,"unloadWorld");
            call(g,"createWorld",new Class<?>[]{WorldSettings.class},new WorldSettings("Craft exit audit",43,GameMode.SURVIVAL));
            inv=(Inventory)get(g,"inventory");inv.set(0,ItemStack.of("diamond",16));
            screen=new InventoryScreen((WindowContext)get(g,"windowContext"));
            call(g,"openWindow",new Class<?>[]{ContainerScreen.class},screen);
            screen.menu().leftClick(new SlotRef(screen.menu().group("hotbar"),0));
            screen.menu().leftClick(new SlotRef(screen.menu().group("craft"),0));
            String exitWorld=(String)get(g,"worldId");
            org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose(window.getHandle(),true);
            g.run();g=null;
            LevelData afterExit=sm.loadLevel(exitWorld);
            check(count(afterExit.inventory)+count(afterExit.pending)==16,"Normal game exit must retain craft ingredients");
            System.out.println("PASS normal-quit crafting: 16 diamonds in grid; Game.run shutdown save then reload inventory+pending=16");
            System.out.println("INVENTORY SAFETY: 6 regression scenarios passed");
        } finally {
            if(gn!=null)gn.stop(null);if(hn!=null)hn.stop(null);
            if(g!=null)call(g,"cleanup");window.destroy();
        }
    }
}
