package com.mineclone.net;

import com.mineclone.ui.container.*;
import com.mineclone.world.*;
import com.mineclone.world.entity.ItemEntity;
import java.util.*;
import static com.mineclone.net.NetProto.*;

/**
 * Player checkpoints and host-executed container gestures. Runs only on the game thread.
 *
 * <p>Outside a shared menu the client owns its inventory (crafting, consumption and tools).
 * Opening a menu hands that inventory and its cursor to the host until the close response.
 * Drop/pickup requests also prevent local edits until acknowledged, so an older snapshot
 * cannot restore items that have already changed owners. Reconnect always uses the host's
 * checkpoint; resending a possibly stale client inventory there would duplicate items.
 */
final class InventorySync {
    private final Multiplayer net;
    private final NetContext ctx;
    private final String fallbackId=UUID.randomUUID().toString();
    private final Map<Integer,String> identities=new HashMap<>();
    private final Map<String,PlayerData> profiles=new HashMap<>();
    private final Map<Integer,OpenMenu> menus=new HashMap<>();
    private final Map<Integer,Long> dropSequences=new HashMap<>();
    private final Map<Integer,Long> pickupSequences=new HashMap<>();
    private final Deque<ItemEntity> drops=new ArrayDeque<>();
    private float timer;
    private boolean ready,dropPending,pickupPending,closing;
    private long menuId,sequence,awaiting=-1,dropSequence,pickupSequence;
    private boolean opened;
    private ContainerMenu clientMenu;
    private ItemStack[] shown;

    InventorySync(Multiplayer net,NetContext ctx){this.net=net;this.ctx=ctx;}

    String identity(){String id=ctx.playerId();return id==null||id.isBlank()?fallbackId:id;}
    boolean identified(int actor) { return identities.containsKey(actor); }
    boolean busy(){return net.isClient() && (!net.joined() || !ready || dropPending || pickupPending || !drops.isEmpty());}

    void hello(int actor,String id) {
        try { id=UUID.fromString(id).toString(); }
        catch(IllegalArgumentException e){net.rejectPlayer(actor,"Некорректный идентификатор игрока");return;}
        for(var entry:identities.entrySet())if(entry.getValue().equals(id) && entry.getKey()!=actor){
            net.rejectPlayer(actor,"Этот игрок уже в комнате");return;
        }
        PlayerData data=profiles.get(id);
        if(data==null) {
            try { data=ctx.loadGuest(id); }
            catch(RuntimeException e){net.rejectPlayer(actor,"Не удалось прочитать сохранение игрока");return;}
            if(data==null)data=PlayerData.empty(ctx.spawn());
            profiles.put(id,data);
        }
        identities.put(actor,id);
        data.write(net.inventoryPacket(S_PLAYER,actor));
    }

    void update(float dt) {
        timer+=dt;if(timer<.25f)return;timer=0;
        if(net.isHost()) {
            for(var e:new ArrayList<>(menus.entrySet())) {
                if(!e.getValue().valid(ctx.world()))closeHost(e.getKey(),true);
                else sendMenu(e.getKey(),e.getValue(),false);
            }
        } else {
            if(!dropPending && !pickupPending)sendDrop();
            saveNow();
        }
    }

    void saveNow() {
        if(!net.isClient() || busy() || opened)return;
        PlayerData data=ctx.capturePlayerData();
        if(data!=null)data.write(net.inventoryPacket(C_PLAYER,net.hostActor()));
    }

    void stopping() {
        if(net.isClient()) {
            if(opened)net.inventoryPacket(C_CLOSE,net.hostActor()).i64(menuId);
            else {
                while(!drops.isEmpty() && sendDrop()) { }
                saveNow();
            }
        } else if(net.isHost()) {
            for(int actor:new ArrayList<>(menus.keySet()))closeHost(actor,false);
        }
        net.flushInventory();
    }

    void reset() {
        identities.clear();profiles.clear();menus.clear();dropSequences.clear();pickupSequences.clear();
        drops.clear();ready=false;dropPending=false;pickupPending=false;opened=false;closing=false;clientMenu=null;shown=null;awaiting=-1;timer=0;
    }

    void left(int actor){closeHost(actor,false);identities.remove(actor);dropSequences.remove(actor);pickupSequences.remove(actor);}

    private PlayerData profile(int actor){String id=identities.get(actor);return id==null?null:profiles.get(id);}
    private void store(int actor,PlayerData data) {
        String id=identities.get(actor);if(id==null||data==null)return;
        PlayerData previous=profiles.get(id);
        // Protocol v7 omits equipment/effects/personal spawn and opaque sections.
        // Its checkpoint only replaces the fields it actually carries.
        if(previous!=null)data=new PlayerData(previous.record().mergeLegacy(data));
        profiles.put(id,data);ctx.saveGuest(id,data);
    }

    void open(int x,int y,int z) {
        if(!net.isClient() || busy() || opened)return;
        PlayerData data=ctx.capturePlayerData();if(data==null)return;
        opened=true;closing=false;clientMenu=null;shown=null;sequence=0;awaiting=0;menuId++;
        PacketBuf b=net.inventoryPacket(C_OPEN,net.hostActor()).i64(menuId).blockPos(x,y,z);
        data.write(b);net.flushInventory();
    }

    void bind(ContainerMenu menu) {
        if(!net.isClient())return;
        clientMenu=menu;
        menu.setRemote(this::action);
    }

    private void action(MenuCommand command) {
        if(!net.joined() || !opened || closing || awaiting>=0 || shown==null)return;
        awaiting=++sequence;
        PacketBuf b=net.inventoryPacket(C_ACTION,net.hostActor()).i64(menuId).i64(sequence);
        PlayerData.writeItems(b,shown);
        b.u8(command.kind().ordinal()).str(command.group()).varInt(command.index()+1).varInt(command.argument());
        b.varInt(command.targets().size());
        for(var t:command.targets())b.str(t.group()).varInt(t.index());
        net.flushInventory();
    }

    /** Returns true while the server still owns the open window/cursor. */
    boolean close() {
        if(!net.isClient() || !opened)return false;
        closing=true;
        if(!net.joined())return true;
        if(awaiting<0){awaiting=++sequence;net.inventoryPacket(C_CLOSE,net.hostActor()).i64(menuId);net.flushInventory();}
        return true;
    }

    boolean drop(ItemEntity e) {
        if(!net.isClient())return false;
        drops.addLast(e);return true;
    }

    private boolean sendDrop() {
        if(!net.joined() || !ready || pickupPending || opened || drops.isEmpty())return false;
        PlayerData data=ctx.capturePlayerData();
        if(data==null)return false;
        ItemEntity e=drops.removeFirst();
        // A disconnect between queued drops must retain the stacks not yet sent.
        var recovery=new ArrayList<ItemStack>(Arrays.asList(data.pending));
        for(var queued:drops)recovery.add(queued.stack);
        data=data.withItems(data.inventory,recovery.toArray(ItemStack[]::new));
        dropPending=true;
        PacketBuf b=net.inventoryPacket(C_DROP,net.hostActor()).i64(++dropSequence);
        data.write(b);Multiplayer.writeStack(b,e.stack);
        b.f32(e.position.x).f32(e.position.y).f32(e.position.z)
                .f32(e.velocity.x).f32(e.velocity.y).f32(e.velocity.z);
        net.flushInventory();
        return true;
    }

    void pickup(int id) {
        if(!net.isClient() || busy() || opened)return;
        PlayerData data=ctx.capturePlayerData();if(data==null)return;
        pickupPending=true;
        data.write(net.inventoryPacket(C_PICKUP,net.hostActor()).i64(++pickupSequence).varInt(id));
        net.flushInventory();
    }

    boolean handle(int from,int code,PacketBuf b) {
        switch(code) {
            case C_PLAYER -> {
                PlayerData data=PlayerData.read(b);
                if(net.isHost() && data!=null && !menus.containsKey(from))store(from,data);
            }
            case S_PLAYER -> {
                PlayerData data=PlayerData.read(b);
                if(net.isClient() && from==net.hostActor() && data!=null) {
                    // The host checkpoint decides whether the last operation before a disconnect landed.
                    opened=false;closing=false;clientMenu=null;shown=null;awaiting=-1;dropPending=false;pickupPending=false;drops.clear();
                    ctx.restorePlayerData(data);ready=true;
                }
            }
            case C_OPEN -> {
                long id=b.readI64();int[] at=b.readBlockPos();PlayerData data=PlayerData.read(b);
                if(net.isHost() && data!=null && profile(from)!=null)openHost(from,id,at,data);
            }
            case C_ACTION -> actionHost(from,b);
            case C_CLOSE -> {
                long id=b.readI64();OpenMenu menu=menus.get(from);
                if(!b.truncated() && net.isHost() && menu!=null && menu.id==id)closeHost(from,true);
            }
            case S_MENU -> receiveMenu(from,b);
            case C_DROP -> {
                long seq=b.readI64();PlayerData data=PlayerData.read(b);ItemStack stack=Multiplayer.readStack(b);
                float px=b.readF32(),py=b.readF32(),pz=b.readF32(),vx=b.readF32(),vy=b.readF32(),vz=b.readF32();
                if(b.truncated() || !net.isHost() || data==null || stack==null || profile(from)==null
                        || menus.containsKey(from) || !finite(px,py,pz,vx,vy,vz))return true;
                if(seq>dropSequences.getOrDefault(from,0L)) {
                    var item=new ItemEntity(stack,px,py,pz,ItemEntity.THROW_DELAY,0);
                    item.velocity.set(vx,vy,vz);ctx.groundItems().add(item);
                    dropSequences.put(from,seq);store(from,data);
                }
                net.inventoryPacket(S_DROP_ACK,from).i64(seq);
            }
            case S_DROP_ACK -> {
                long seq=b.readI64();
                if(!b.truncated() && net.isClient() && from==net.hostActor() && seq==dropSequence)dropPending=false;
            }
            case C_PICKUP -> {
                long seq=b.readI64();int id=b.readVarInt();PlayerData data=PlayerData.read(b);
                if(!net.isHost() || data==null || profile(from)==null || menus.containsKey(from))return true;
                if(seq>pickupSequences.getOrDefault(from,0L)) {
                    Inventory inv=new Inventory();for(int i=0;i<Inventory.SIZE;i++)inv.set(i,data.inventory[i]);
                    net.pickupInto(id,inv);
                    ItemStack[] items=new ItemStack[Inventory.SIZE];for(int i=0;i<items.length;i++)items[i]=inv.get(i);
                    store(from,data.withItems(items,data.pending));pickupSequences.put(from,seq);
                }
                profile(from).write(net.inventoryPacket(S_PICKUP,from).i64(seq));
            }
            case S_PICKUP -> {
                long seq=b.readI64();PlayerData data=PlayerData.read(b);
                if(net.isClient() && from==net.hostActor() && data!=null && seq==pickupSequence && pickupPending) {
                    pickupPending=false;ctx.containerInventory(data.inventory,null,false);
                }
            }
            default -> { return false; }
        }
        return true;
    }

    private static boolean finite(float... values){for(float f:values)if(!Float.isFinite(f))return false;return true;}

    private void openHost(int actor,long id,int[] at,PlayerData data) {
        closeHost(actor,false);store(actor,data);
        OpenMenu menu=new OpenMenu(id,at[0],at[1],at[2],ctx.world(),data);
        if(menu.menu==null) {
            sendMenu(actor,menu,true);return;
        }
        menus.put(actor,menu);sendMenu(actor,menu,false);
    }

    private void actionHost(int actor,PacketBuf b) {
        long id=b.readI64(),seq=b.readI64();ItemStack[] expected=PlayerData.readItems(b,64);
        int op=b.readU8();String group=b.readStr();int index=b.readVarInt()-1,arg=b.readVarInt(),n=b.readVarInt();
        if(n<0 || n>64){while(b.hasMore())b.readU8();b.readU8();return;}
        List<MenuCommand.Target> targets=new ArrayList<>();
        for(int i=0;i<n;i++)targets.add(new MenuCommand.Target(b.readStr(),b.readVarInt()));
        if(b.truncated() || !net.isHost())return;
        OpenMenu menu=menus.get(actor);if(menu==null || menu.id!=id)return;
        if(!menu.valid(ctx.world())){closeHost(actor,true);return;}
        if(seq>menu.sequence) {
            menu.sequence=seq;
            // Reject a gesture based on a view changed by another player or the furnace.
            // The reply refreshes the view, allowing the next gesture to act on real slots.
            if(op<MenuCommand.Kind.values().length && PlayerData.sameItems(expected,menu.slots())) {
                new MenuCommand(MenuCommand.Kind.values()[op],group,index,arg,targets)
                        .apply(menu.menu,ctx.gameMode()==GameMode.CREATIVE.ordinal());
                ctx.world().markChestDirty(menu.x,menu.z);
                for(ItemStack s:menu.menu.dropped())spawnDrop(actor,s);
                menu.menu.dropped().clear();
                saveMenu(actor,menu);
            }
        }
        sendMenu(actor,menu,false);
    }

    private void spawnDrop(int actor,ItemStack stack) {
        if(stack==null)return;
        var p=net.playerOf(actor);var d=profile(actor);
        float px=p==null?d.x:p.position.x,py=p==null?d.y:p.position.y,pz=p==null?d.z:p.position.z;
        ctx.groundItems().add(new ItemEntity(stack.copy(),px,py+.6f,pz,ItemEntity.THROW_DELAY,0));
    }

    private void saveMenu(int actor,OpenMenu menu) {
        PlayerData data=profile(actor);if(data==null)return;
        ItemStack cursor=menu.menu.cursor();
        var pending=new ArrayList<ItemStack>(Arrays.asList(menu.recovery));
        if(cursor!=null)pending.add(cursor);
        store(actor,data.withItems(menu.inventory(),pending.toArray(ItemStack[]::new)));
    }

    private void closeHost(int actor,boolean reply) {
        OpenMenu menu=menus.remove(actor);if(menu==null)return;
        for(ItemStack s:menu.menu.closeAll()) {
            int left=menu.inv.add(s);if(left>0)spawnDrop(actor,s.copyWithCount(left));
        }
        saveMenu(actor,menu);
        if(reply)sendMenu(actor,menu,true);
    }

    private void sendMenu(int actor,OpenMenu menu,boolean closed) {
        PacketBuf b=net.inventoryPacket(S_MENU,actor).i64(menu.id).i64(menu.sequence).bool(closed)
                .blockPos(menu.x,menu.y,menu.z).u8(menu.kind);
        PlayerData.writeItems(b,menu.slots());PlayerData.writeItems(b,menu.inventory());
        Multiplayer.writeStack(b,menu.menu==null?null:menu.menu.cursor());
        Furnace f=menu.furnace;
        b.f32(f==null?0:f.burnLeft).f32(f==null?0:f.burnMax).f32(f==null?0:f.cook);
    }

    private void receiveMenu(int from,PacketBuf b) {
        long id=b.readI64(),ack=b.readI64();boolean closed=b.readBool();int[] at=b.readBlockPos();int type=b.readU8();
        ItemStack[] slots=PlayerData.readItems(b,64),inv=PlayerData.readItems(b,Inventory.SIZE);
        ItemStack cursor=Multiplayer.readStack(b);float left=b.readF32(),max=b.readF32(),cook=b.readF32();
        if(b.truncated() || slots==null || inv==null || inv.length!=Inventory.SIZE || !net.isClient()
                || from!=net.hostActor() || !opened || id!=menuId)return;
        if(!closed && ack<sequence)return;
        boolean changed=!PlayerData.sameItems(shown,slots);
        shown=PlayerData.copy(slots);
        ctx.containerFromHost(at[0],at[1],at[2],type,slots,left,max,cook);
        if(clientMenu!=null){if(changed || awaiting>=0)clientMenu.cancelDrag();clientMenu.setCursor(cursor);}
        if(closed){opened=false;closing=false;awaiting=-1;clientMenu=null;}
        else if(ack>=awaiting)awaiting=-1;
        ctx.containerInventory(inv,cursor,closed);
        if(closing && !closed && awaiting<0)close();
    }

    private static final class OpenMenu {
        final long id;final int x,y,z,kind;long sequence;
        final Inventory inv=new Inventory();final ContainerMenu menu;final ItemStack[] chest,recovery;final Furnace furnace;
        OpenMenu(long id,int x,int y,int z,World world,PlayerData data) {
            this.id=id;this.x=x;this.y=y;this.z=z;
            for(int i=0;i<Inventory.SIZE;i++)inv.set(i,data.inventory[i]==null?null:data.inventory[i].copy());
            recovery=PlayerData.copy(data.pending);
            BlockType block=world==null?BlockType.AIR:world.getBlock(x,y,z);
            kind=block==BlockType.FURNACE?Multiplayer.CONTAINER_FURNACE:Multiplayer.CONTAINER_CHEST;
            chest=block==BlockType.CHEST?world.createChest(x,y,z):null;
            furnace=block==BlockType.FURNACE?world.createFurnace(x,y,z):null;
            menu=chest!=null?ContainerMenus.chest(inv,chest,i->world.markChestDirty(x,z))
                    :furnace!=null?ContainerMenus.furnace(inv,furnace):null;
        }
        ItemStack[] inventory(){ItemStack[] a=new ItemStack[Inventory.SIZE];for(int i=0;i<a.length;i++)a[i]=inv.get(i);return a;}
        ItemStack[] slots(){return chest!=null?chest:furnace!=null?new ItemStack[]{furnace.input,furnace.fuel,furnace.output}:new ItemStack[0];}
        boolean valid(World world){return world!=null && (chest!=null?world.getChest(x,y,z)==chest:world.getFurnace(x,y,z)==furnace && furnace!=null);}
    }
}
