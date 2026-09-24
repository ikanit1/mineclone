package com.mineclone;

import com.mineclone.game.*;
import com.mineclone.world.*;
import com.mineclone.item.Items;
import com.mineclone.ui.container.*;

final class CreativeModeTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("creative ignores attacks environmental damage hunger and starvation", CreativeModeTests::immunity);
        r.run("creative flight double tap has timing and mode boundaries", CreativeModeTests::flight);
        r.run("creative break includes bedrock but excludes liquids and air", CreativeModeTests::breaking);
        r.run("held building buttons repeat with bounded cadence and immediate clicks", CreativeModeTests::repeat);
        r.run("creative catalog searches names and IDs and exposes storage", CreativeModeTests::catalog);
        r.run("creative falls are harmless and survival falls still hurt", CreativeModeTests::fall);
    }
    static void check(boolean v,String s) { if(!v)throw new AssertionError(s); }
    private static void immunity() {
        Player p=new Player();p.health=3;p.hunger=0;p.setGameMode(GameMode.CREATIVE);
        p.takeDamage(1000);check(!p.takeAttackDamage(1000),"attack and knockback rejected");
        p.tickHunger(3600,true);
        check(p.health==20 && p.hunger==20 && !p.isDead(),"grounded creative is invulnerable and fed");
        p.flying=true;p.fallDistance=100;p.setGameMode(GameMode.SURVIVAL);
        check(!p.flying && p.fallDistance==0,"switch resets flight and carried fall");
        p.takeDamage(4);check(p.health==16,"survival damage restored");
        p.tickHunger(10,true);check(p.hunger<20,"survival hunger restored");
    }
    private static void flight() {
        Player p=new Player();p.updateFlightControls(0,true,true,true);check(!p.flying,"survival cannot fly");
        p.setGameMode(GameMode.CREATIVE);
        p.updateFlightControls(0,true,true,false);check(!p.flying,"first tap jumps");
        p.updateFlightControls(.15f,true,true,false);check(p.flying,"second tap flies");
        p.updateFlightControls(.02f,true,false,false);check(p.flying,"holding jump does not toggle");
        p.updateFlightControls(.3f,true,true,false);p.updateFlightControls(.1f,true,true,false);
        check(!p.flying,"second double tap ends flight");
        p.updateFlightControls(.3f,true,true,false);p.updateFlightControls(.1f,false,false,false);
        p.updateFlightControls(.1f,true,true,false);check(!p.flying,"menu cancels pending tap");
        p.updateFlightControls(.3f,true,false,true);check(p.flying,"bound F key still works");
    }
    private static void breaking() {
        check(GameMode.CREATIVE.canBreak(BlockType.BEDROCK),"creative bedrock");
        check(!GameMode.SURVIVAL.canBreak(BlockType.BEDROCK),"survival bedrock");
        for(BlockType b:new BlockType[]{BlockType.AIR,BlockType.WATER,BlockType.WATER_FLOW,BlockType.LAVA})
            check(!GameMode.CREATIVE.canBreak(b),"non-target "+b);
        check(GameMode.CREATIVE.canBreak(BlockType.STONE),"instant stone independent of tool");
    }
    private static void repeat() {
        InteractionRepeat a=new InteractionRepeat();
        check(a.update(.01f,true,true,true),"first press immediate");
        int count=0;for(int i=0;i<100;i++)if(a.update(.01f,false,true,true))count++;
        check(count>=4 && count<=5,"held cadence bounded: "+count);
        a.update(.01f,false,false,true);check(a.update(.01f,true,true,true),"release and reclick immediate");
        a.reset();check(!a.update(1,false,true,false),"survival instant-debug does not auto repeat");
    }
    private static void catalog() {
        Inventory inv=new Inventory();PreviewContext ctx=new PreviewContext(inv);ctx.mode=GameMode.CREATIVE;
        CreativeScreen screen=new CreativeScreen(ctx);ContainerMenu m=screen.menu();
        long count=Items.get().all().stream().filter(i->!i.hidden).count();
        check(m.group("source").size()==count,"every visible item available");
        screen.setSearch("DIAMOND_PICKAXE");check(m.group("source").size()==1,"ID search case insensitive");
        check(CreativeScreen.matchesSearch(Items.get().require("diamond_pickaxe"),"кирка"),"Russian search");
        inv.set(0,ItemStack.of("stone",64));
        m.numberKey(new SlotRef(m.group("source"),0),0);
        check(inv.get(0).item==Items.get().require("diamond_pickaxe"),"catalog number key replaces occupied slot");
        for(int i=0;i<9;i++)inv.set(i,ItemStack.of("stone",64));
        m.shiftClick(new SlotRef(m.group("source"),0));
        check(inv.get(9)!=null && inv.get(9).item==Items.get().require("diamond_pickaxe"),"full hotbar routes to backpack");
        screen.applyCategory(7);check(m.group("main").size()==27,"all backpack slots accessible");
        m.numberKey(new SlotRef(m.group("main"),0),0);
        check(inv.get(0).item==Items.get().require("diamond_pickaxe"),"storage can equip hotbar");
    }
    private static void fall() {
        World w=new World(12);Chunk c=w.getChunk(0,0);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=0;y<Chunk.SIZE_Y;y++)
            c.set(x,y,z,y<=10?BlockType.STONE:BlockType.AIR);
        for(GameMode mode:GameMode.values()) {
            Player p=new Player();p.respawn(8.5f,35,8.5f);p.setGameMode(mode);
            for(int i=0;i<600 && !p.onGround;i++)p.update(1f/120,w,null,false);
            check(p.onGround,"lands with collisions");
            check(mode==GameMode.CREATIVE?p.health==20:p.health<20,"fall rule for "+mode);
        }
    }
}
