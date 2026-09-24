package com.mineclone;

import com.mineclone.net.*;
import com.mineclone.save.*;
import com.mineclone.ui.container.*;
import com.mineclone.world.*;
import com.mineclone.world.entity.ItemEntity;
import java.nio.file.*;
import java.util.*;

final class InventoryNetworkTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("saving an open craft keeps ingredients and cursor without mutating either",InventoryNetworkTests::pendingCraft);
        r.run("player identity and inventory checkpoint survive a fresh SaveManager",InventoryNetworkTests::disk);
        r.run("guest inventory survives reconnect with a changed nickname",InventoryNetworkTests::rejoin);
        r.run("host and client restart restore the player from disk",InventoryNetworkTests::restart);
        r.run("guest drop exists on the host and is not erased by snapshots",InventoryNetworkTests::drop);
        r.run("queued guest drops retain every stack",InventoryNetworkTests::drops);
        r.run("pickup acknowledgement precedes container snapshots",InventoryNetworkTests::pickupThenOpen);
        r.run("partial pickup leaves overflow on the host and survives reconnect",InventoryNetworkTests::partialPickup);
        r.run("disconnect before pickup acknowledgement recovers exactly once",InventoryNetworkTests::pickupDisconnect);
        r.run("simultaneous chest withdrawals cannot duplicate a stack",InventoryNetworkTests::simultaneous);
        r.run("stale chest click after host withdrawal is rejected",InventoryNetworkTests::hostWithdrawal);
        r.run("remote chest gestures preserve split drag hotbar and cursor ownership",InventoryNetworkTests::gestures);
        r.run("furnace keeps cooking while a guest watches and closing cannot rewind it",InventoryNetworkTests::furnace);
        r.run("disconnect with a container cursor returns it exactly once",InventoryNetworkTests::disconnectCursor);
        r.run("closing a remote chest with a full inventory drops overflow on the host",InventoryNetworkTests::fullInventory);
        r.run("container transfers and guest drops work over actual TCP sockets",InventoryNetworkTests::sockets);
        r.run("old whole-container commits cannot replace authoritative contents",InventoryNetworkTests::oldCommit);
    }
    private static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    private static int count(ItemStack[] a){int n=0;for(var s:a)if(s!=null)n+=s.count;return n;}
    private static int count(Inventory a){int n=0;for(int i=0;i<a.size();i++)if(a.get(i)!=null)n+=a.get(i).count;return n;}
    private static int cursor(C c){return c.menu==null||c.menu.cursor()==null?0:c.menu.cursor().count;}
    private static int ground(C c){return c.items.stream().mapToInt(e->e.stack.count).sum();}
    private static ItemStack[] slots(Inventory inv){ItemStack[] a=new ItemStack[Inventory.SIZE];for(int i=0;i<a.length;i++)a[i]=inv.get(i);return a;}
    private static SlotRef ref(C c,String group,int slot){return new SlotRef(c.menu.group(group),slot);}

    private static void pendingCraft() {
        var crafting=new CraftingGrid(3);crafting.storage().set(0,ItemStack.of("diamond",16));
        var menu=new ContainerMenu(List.of(new SlotGroup("craft",SlotRole.CRAFT_GRID,crafting.storage(),3)));
        menu.setCursor(ItemStack.of("iron_ingot",5));
        var pending=menu.pendingItems();check(pending.stream().mapToInt(s->s.count).sum()==21,"all temporary items captured");
        pending.get(0).count=1;
        check(menu.cursor().count==5 && crafting.slots()[0].count==16,"snapshot is detached, grid unchanged");
        check(menu.pendingItems().stream().mapToInt(s->s.count).sum()==21,"repeated save does not duplicate");
    }

    private static void disk() throws Exception {
        Path root=Files.createTempDirectory("mineclone-player-checkpoint");
        SaveManager a=new SaveManager(root.resolve("saves").toFile());String id=a.playerId();
        C c=new C(new World(42),"host");c.inv.set(7,ItemStack.of("diamond",11));
        var data=c.capturePlayerData();a.saveGuest("world-a",id,data);a.flushAndAwait();
        var b=new SaveManager(root.resolve("saves").toFile());
        check(id.equals(b.playerId()),"identity persisted outside worlds");
        check(count(b.loadGuest("world-a",id).inventory)==11,"inventory persisted to disk");
        check(b.loadGuest("world-b",id)==null,"worlds have separate checkpoints");
        byte[] encoded=data.bytes();
        check(PlayerData.read(PacketBuf.reading(Arrays.copyOf(encoded,encoded.length-1)))==null,"truncated player snapshot rejected");
    }

    private static void rejoin() {
        try(Room r=new Room()){
            r.a.inv.set(3,ItemStack.of("diamond",11));r.aNet.savePlayerNow();r.pump(3);
            r.aNet.stop(null);r.pump(3);
            r.aNet.start(new LoopbackTransport(r.aNet),"inv",false,"New name");r.pump(8);
            check(count(r.a.inv)==11,"same identity restores with different name");
            check(r.a.inv.get(3).count==11,"slot preserved");
        }
    }

    private static void drop() {
        try(Room r=new Room()){
            r.a.inv.set(0,ItemStack.of("diamond",7));r.aNet.savePlayerNow();r.pump(2);
            ItemStack s=r.a.inv.get(0);r.a.inv.set(0,null);
            r.aNet.requestDrop(new ItemEntity(s,8,81,8,ItemEntity.THROW_DELAY,0));r.pump(16);
            check(count(r.a.inv)==0 && ground(r.host)==7 && ground(r.a)==7,"one real drop visible on both sides");
            r.aNet.stop(null);r.pump(3);r.aNet.start(new LoopbackTransport(r.aNet),"inv",false,"Again");r.pump(8);
            check(count(r.a.inv)==0 && ground(r.host)==7,"rejoin cannot restore a stack already dropped");
        }
    }

    private static void restart() throws Exception {
        Path root=Files.createTempDirectory("mineclone-guest-restart");
        String identity=UUID.randomUUID().toString();
        for(int attempt=0;attempt<2;attempt++) {
            LoopbackTransport.reset();
            C host=new C(new World(42),"host"),guest=new C(null,"guest",identity);
            host.persistence=new SaveManager(root.resolve("saves").toFile());
            Multiplayer hn=new Multiplayer(host),gn=new Multiplayer(guest);
            hn.start(new LoopbackTransport(hn),"restart",true,"host");
            gn.start(new LoopbackTransport(gn),"restart",false,"guest");
            for(int i=0;i<10;i++){hn.update(.1f);gn.update(.1f);}
            if(attempt==0)guest.inv.set(5,ItemStack.of("diamond",23));
            else check(guest.inv.get(5)!=null && guest.inv.get(5).count==23,"new instances restore disk checkpoint");
            gn.stop(null);hn.update(.1f);hn.stop(null);host.persistence.flushAndAwait();
        }
        LoopbackTransport.reset();
    }

    private static void drops() {
        try(Room r=new Room()){
            r.aNet.requestDrop(new ItemEntity(ItemStack.of("diamond",7),8,81,8,2,0));
            r.aNet.requestDrop(new ItemEntity(ItemStack.of("iron_ingot",9),8,81,8,2,0));
            r.pump(20);check(ground(r.host)==16,"both queued stacks delivered");
            check(count(r.host.saved.get(r.a.id).pending)==0,"no dropped stack remains recoverable twice");
        }
    }

    private static void pickupThenOpen() {
        try(Room r=new Room()) {
            r.chest(16);
            r.host.items.add(new ItemEntity(ItemStack.of("diamond",7),8,81,8,0,0));r.pump(4);
            r.aNet.requestPickup(r.a.items.get(0));
            check(r.aNet.inventoryBusy(),"inventory waits for pickup ownership");
            r.aNet.requestContainer(8,100,8);r.pump(5);
            check(count(r.a.inv)==7 && ground(r.host)==0,"pickup applied before another snapshot");
            r.open(r.a,r.aNet,8,BlockType.CHEST);
            r.a.menu.shiftClick(ref(r.a,"chest",0));r.pump(5);
            check(count(r.a.inv)==23,"opening and using chest cannot erase pickup");
        }
    }

    private static void partialPickup() {
        try(Room r=new Room()) {
            for(int i=0;i<Inventory.SIZE;i++)r.a.inv.set(i,ItemStack.of("stone",64));
            r.a.inv.set(0,ItemStack.of("diamond",62));
            r.host.items.add(new ItemEntity(ItemStack.of("diamond",7),8,81,8,0,0));r.pump(4);
            r.aNet.requestPickup(r.a.items.get(0));r.pump(5);
            check(r.a.inv.get(0).count==64 && ground(r.host)==5,"only available capacity picked up");
            r.aNet.stop(null);r.pump(3);r.aNet.start(new LoopbackTransport(r.aNet),"inv",false,"again");r.pump(8);
            check(r.a.inv.get(0).count==64 && ground(r.host)==5,"partial pickup checkpoint restored");
        }
    }

    private static void pickupDisconnect() {
        try(Room r=new Room()) {
            r.host.items.add(new ItemEntity(ItemStack.of("diamond",7),8,81,8,0,0));r.pump(4);
            r.aNet.requestPickup(r.a.items.get(0));
            // Process ownership on the host, but disconnect before the client polls the reply.
            r.hostNet.update(.1f);r.aNet.stop(null);r.pump(3);
            r.aNet.start(new LoopbackTransport(r.aNet),"inv",false,"again");r.pump(8);
            check(count(r.a.inv)==7 && ground(r.host)==0,"host checkpoint wins over stale client state");
        }
    }

    private static void simultaneous() {
        try(Room r=new Room()){
            r.chest(16);r.open(r.a,r.aNet,8,BlockType.CHEST);r.open(r.b,r.bNet,8,BlockType.CHEST);
            r.a.menu.leftClick(ref(r.a,"chest",0));r.b.menu.leftClick(ref(r.b,"chest",0));r.pump(5);
            check(cursor(r.a)+cursor(r.b)==16 && count(r.host.world.getChest(8,100,8))==0,"only one withdrawal accepted");
            r.aNet.closeContainer();r.bNet.closeContainer();r.pump(6);
            check(count(r.a.inv)+count(r.b.inv)==16,"closing conserves the original stack");
        }
    }

    private static void hostWithdrawal() {
        try(Room r=new Room()){
            r.chest(16);r.open(r.a,r.aNet,8,BlockType.CHEST);
            r.host.world.getChest(8,100,8)[0]=null;
            r.a.menu.leftClick(ref(r.a,"chest",0));r.pump(5);
            check(cursor(r.a)==0 && count(r.a.inv)==0,"stale view cannot give a second copy");
        }
    }

    private static void gestures() {
        try(Room r=new Room()){
            r.chest(16);r.open(r.a,r.aNet,8,BlockType.CHEST);
            r.a.menu.rightClick(ref(r.a,"chest",0));r.pump(4);
            check(cursor(r.a)==8 && r.host.world.getChest(8,100,8)[0].count==8,"right-click splits on host");
            r.a.menu.beginDrag(false);r.a.menu.dragOver(ref(r.a,"hotbar",0));r.pump(4);
            check(r.a.menu.dragging(),"unchanged network refresh does not cancel drag");
            r.a.menu.dragOver(ref(r.a,"hotbar",1));r.a.menu.endDrag();r.pump(4);
            check(r.a.inv.get(0).count==4 && r.a.inv.get(1).count==4 && cursor(r.a)==0,"drag distribution applied on host");
            r.a.menu.numberKey(ref(r.a,"chest",0),2);r.pump(4);
            check(r.a.inv.get(2).count==8,"number key transfers to hotbar");
            r.a.menu.drop(ref(r.a,"hotbar",2),true);r.pump(5);
            check(ground(r.host)==8 && count(r.a.inv)==8,"container drop creates one authoritative stack");
        }
    }

    private static void furnace() {
        try(Room r=new Room()){
            r.host.world.setBlock(9,100,8,BlockType.FURNACE);
            var f=r.host.world.createFurnace(9,100,8);f.input=ItemStack.of("iron_ore",2);f.fuel=ItemStack.of("coal");
            r.open(r.a,r.aNet,9,BlockType.FURNACE);
            for(int i=0;i<36;i++){f.tick(.25f);r.pump(3);}
            check(f.output!=null && f.output.count==1,"host smelted ingot");
            var shown=r.a.world.getFurnace(9,100,8);check(shown.output!=null && shown.output.count==1,"guest sees live smelting");
            float burn=f.burnLeft;r.aNet.closeContainer();r.pump(4);
            check(f.input.count==1 && f.output.count==1 && f.fuel==null && f.burnLeft==burn,"closing cannot overwrite furnace");
        }
    }

    private static void disconnectCursor() {
        try(Room r=new Room()){
            r.chest(16);r.open(r.a,r.aNet,8,BlockType.CHEST);
            r.a.menu.leftClick(ref(r.a,"chest",0));r.pump(4);check(cursor(r.a)==16,"cursor holds stack");
            r.aNet.stop(null);r.pump(4);
            r.aNet.start(new LoopbackTransport(r.aNet),"inv",false,"again");r.pump(8);
            check(count(r.a.inv)==16 && r.a.menu==null && count(r.host.world.getChest(8,100,8))==0,"cursor recovered exactly once");
        }
    }

    private static void oldCommit() {
        try(Room r=new Room()){
            r.chest(16);
            PacketBuf b=new PacketBuf().u8(NetProto.C_CONTAINER_COMMIT).blockPos(8,100,8)
                    .u8(Multiplayer.CONTAINER_CHEST).varInt(0).f32(0).f32(0).f32(0);
            r.hostNet.onPayload(r.aActor(),b.toBytes());
            check(count(r.host.world.getChest(8,100,8))==16,"retired protocol cannot replace chest");
        }
    }

    private static void fullInventory() {
        try(Room r=new Room()) {
            for(int i=0;i<Inventory.SIZE;i++)r.a.inv.set(i,ItemStack.of("stone",64));
            r.chest(16);r.open(r.a,r.aNet,8,BlockType.CHEST);
            r.a.menu.leftClick(ref(r.a,"chest",0));r.pump(4);
            r.aNet.closeContainer();r.pump(6);
            check(count(r.a.inv)==Inventory.SIZE*64 && ground(r.host)==16,"overflow is neither lost nor duplicated");
        }
    }

    private static void sockets() throws Exception {
        try(Room r=new Room(true)) {
            r.chest(16);r.open(r.a,r.aNet,8,BlockType.CHEST);
            r.await(()->r.a.world.getChest(8,100,8)[0]!=null);
            r.a.menu.shiftClick(ref(r.a,"chest",0));r.await(()->count(r.a.inv)==16);
            r.aNet.closeContainer();r.await(()->r.a.menu==null);
            ItemStack stack=null;
            for(int i=0;i<Inventory.SIZE;i++)if(r.a.inv.get(i)!=null){stack=r.a.inv.get(i);r.a.inv.set(i,null);break;}
            r.aNet.requestDrop(new ItemEntity(stack,8,81,8,2,0));r.await(()->ground(r.host)==16 && ground(r.a)==16);
            check(count(r.a.inv)==0 && count(r.host.world.getChest(8,100,8))==0,"TCP transfer and drop conserved");
        }
    }

    private static final class C extends NetworkTests.TestContext {
        final String id;
        final Inventory inv=new Inventory();ContainerMenu menu;
        final Map<String,PlayerData> saved=new HashMap<>();
        SaveManager persistence;
        C(World w,String name){this(w,name,UUID.randomUUID().toString());}
        C(World w,String name,String id){super(w,name);this.id=id;if(w!=null)w.getChunk(0,0);}
        @Override public String playerId(){return id;}
        @Override public PlayerData capturePlayerData(){return new PlayerData(slots(inv),menu==null?null:menu.pendingItems().toArray(ItemStack[]::new),position.x,position.y,position.z,yaw,pitch,health,20,0,null);}
        @Override public void restorePlayerData(PlayerData data){menu=null;for(int i=0;i<inv.size();i++)inv.set(i,data.inventory[i]);for(var s:data.pending)inv.add(s);}
        @Override public PlayerData loadGuest(String id){return persistence==null?saved.get(id):persistence.loadGuest("test",id);}
        @Override public void saveGuest(String id,PlayerData data){saved.put(id,data);if(persistence!=null)persistence.saveGuest("test",id,data);}
        @Override public void containerInventory(ItemStack[] slots,ItemStack cursor,boolean closed){for(int i=0;i<inv.size();i++)inv.set(i,slots[i]);if(closed)menu=null;else if(menu!=null)menu.setCursor(cursor);}
        @Override public void startRemoteWorld(long seed,String name,float time,int mode,float x,float y,float z){super.startRemoteWorld(seed,name,time,mode,x,y,z);world.getChunk(0,0);for(int i=0;i<inv.size();i++)inv.set(i,null);menu=null;}
        @Override public void containerFromHost(int x,int y,int z,int kind,ItemStack[] slots,float left,float max,float cook){
            if(kind==Multiplayer.CONTAINER_CHEST){ItemStack[] a=world.createChest(x,y,z);if(a!=null)for(int i=0;i<a.length;i++)a[i]=i<slots.length?slots[i]:null;}
            else{Furnace f=world.createFurnace(x,y,z);if(f!=null){f.input=slots[0];f.fuel=slots[1];f.output=slots[2];f.burnLeft=left;f.burnMax=max;f.cook=cook;}}
        }
    }
    private static final class Room implements AutoCloseable {
        final C host=new C(new World(42),"host"),a=new C(null,"a"),b=new C(null,"b");
        final Multiplayer hostNet=new Multiplayer(host),aNet=new Multiplayer(a),bNet=new Multiplayer(b);
        final LoopbackTransport at=new LoopbackTransport(aNet);
        final boolean sockets;
        Room(){this(false);}
        Room(boolean sockets){
            this.sockets=sockets;LoopbackTransport.reset();
            if(sockets){
                int port;try(var probe=new java.net.ServerSocket(0)){port=probe.getLocalPort();}catch(java.io.IOException e){throw new RuntimeException(e);}
                hostNet.start(LanTransport.host(port,hostNet),"inv",true,"host");
                aNet.start(LanTransport.join("127.0.0.1:"+port,aNet),"inv",false,"a");
                bNet.start(LanTransport.join("127.0.0.1:"+port,bNet),"inv",false,"b");
                await(()->a.world!=null && b.world!=null && !aNet.inventoryBusy() && !bNet.inventoryBusy());
            }else{
                hostNet.start(new LoopbackTransport(hostNet),"inv",true,"host");aNet.start(at,"inv",false,"a");bNet.start(new LoopbackTransport(bNet),"inv",false,"b");pump(10);
            }
        }
        int aActor(){return hostNet.players().stream().filter(p->p.name.equals("a")).findFirst().orElseThrow().actor;}
        void pump(int n){for(int i=0;i<n;i++){hostNet.update(.1f);aNet.update(.1f);bNet.update(.1f);if(sockets)try{Thread.sleep(5);}catch(InterruptedException e){throw new RuntimeException(e);}}}
        void await(java.util.function.BooleanSupplier condition){for(int i=0;i<1000;i++){pump(1);if(condition.getAsBoolean())return;}throw new AssertionError("Timed out waiting for inventory over TCP");}
        void chest(int count){host.world.setBlock(8,100,8,BlockType.CHEST);host.world.createChest(8,100,8)[0]=ItemStack.of("diamond",count);}
        void open(C c,Multiplayer net,int x,BlockType type){c.world.setBlock(x,100,8,type);net.requestContainer(x,100,8);c.menu=type==BlockType.CHEST?ContainerMenus.chest(c.inv,c.world.createChest(x,100,8),i->{ }):ContainerMenus.furnace(c.inv,c.world.createFurnace(x,100,8));net.bindContainer(c.menu);pump(5);}
        public void close(){aNet.stop(null);bNet.stop(null);hostNet.update(.1f);hostNet.stop(null);LoopbackTransport.reset();}
    }
}
