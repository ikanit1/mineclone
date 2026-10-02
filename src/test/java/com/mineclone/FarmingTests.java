package com.mineclone;

import com.mineclone.world.*;
import com.mineclone.world.behavior.*;
import com.mineclone.item.*;
import com.mineclone.item.loot.*;
import java.util.*;

/** Shared farming rules, exercised without renderer, clock acceleration or a player's own saves. */
final class FarmingTests {
    static void runAll(TestMain.Runner r) {
        r.run("hoes till only clear top faces and wear exactly once", FarmingTests::tilling);
        r.run("farmland hydration searches radius four and dries back to dirt", FarmingTests::moisture);
        r.run("jump landings trample farmland while chickens and short falls preserve it", FarmingTests::trampling);
        r.run("crops grow by world ticks and bounded absence, but never in darkness", FarmingTests::growth);
        r.run("planting consumes one seed and unsupported mature crops conserve harvest", FarmingTests::planting);
        r.run("saplings grow a complete deterministic crown across chunks and refuse blocked space", FarmingTests::trees);
    }
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    static World blank(){World w=new World(71);for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++)w.publish(new Chunk(cx,cz));w.simulate((s,x,y,z)->{},0);return w;}
    static void tilling(){
        World w=blank();w.setBlock(6,40,6,BlockType.GRASS);Inventory inv=new Inventory();inv.set(0,ItemStack.of("iron_hoe",1));
        check(!Farming.use(w,6,40,6,0,inv,0,GameMode.SURVIVAL),"side tilled");
        check(Farming.use(w,6,40,6,1,inv,0,GameMode.SURVIVAL),"top not tilled");
        check(w.getBlock(6,40,6)==BlockType.FARMLAND&&inv.get(0).damage()==1,"till/wear");
        check(!Farming.use(w,6,40,6,1,inv,0,GameMode.SURVIVAL)&&inv.get(0).damage()==1,"duplicate hoe wear");
        w.setBlock(7,40,6,BlockType.DIRT);w.setBlock(7,41,6,BlockType.STONE);
        check(!Farming.use(w,7,40,6,1,inv,0,GameMode.SURVIVAL),"buried ground tilled");
        for(String material:List.of("wooden","stone","copper","iron","gold","diamond"))
            check(Items.get().require(material+"_hoe").tool.toolClass()==ToolClass.HOE,"missing hoe "+material);
    }
    static void moisture(){
        World w=blank();w.setBlock(6,40,6,BlockType.FARMLAND);w.setBlock(10,41,6,BlockType.WATER);
        Behaviors.of(BlockType.FARMLAND).onPlaced(w,6,40,6,(byte)0);w.runScheduledTicks(400,512,48000);
        check(w.getBlockMeta(6,40,6)==7,"water at radius four missed");w.setBlock(10,41,6,BlockType.AIR);
        for(int tick=800;tick<=3200;tick+=400)w.runScheduledTicks(tick,512,48000);
        check(w.getBlockMeta(6,40,6)==0&&w.getBlock(6,40,6)==BlockType.FARMLAND,"dry too early");
        w.runScheduledTicks(3600,512,48000);check(w.getBlock(6,40,6)==BlockType.DIRT,"dry empty soil not reverted");
    }
    static void trampling(){
        World w=blank();w.setBlock(6,40,6,BlockType.FARMLAND,(byte)7);
        Farming.trample(w,6.5f,40+15f/16,6.5f,.9f,false);check(w.getBlock(6,40,6)==BlockType.FARMLAND,"short fall");
        Farming.trample(w,6.5f,40+15f/16,6.5f,3,true);check(w.getBlock(6,40,6)==BlockType.FARMLAND,"chicken");
        Farming.trample(w,6.5f,40+15f/16,6.5f,1,false);check(w.getBlock(6,40,6)==BlockType.DIRT,"jump did not trample");
    }
    static void growth(){
        World w=blank();w.setBlock(6,40,6,BlockType.FARMLAND,(byte)7);w.setBlock(6,41,6,BlockType.WHEAT);
        w.getChunk(0,0).computeSkyLight();Behaviors.of(BlockType.WHEAT).onPlaced(w,6,41,6,(byte)0);
        w.runScheduledTicks(3400,512,48000);check(w.getBlockMeta(6,41,6)==1,"first stage");
        w.runScheduledTicks(216000,512,48000);check(w.getBlockMeta(6,41,6)==7,"three-hour absence did not ripen");
        w.setBlock(8,40,6,BlockType.FARMLAND,(byte)7);w.setBlock(8,41,6,BlockType.CARROTS);
        w.setBlock(8,42,6,BlockType.STONE);w.getChunk(0,0).computeSkyLight();
        // Cover the full chunk so skylight cannot reach the crop sideways.
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)w.setBlock(x,42,z,BlockType.STONE);
        w.getChunk(0,0).computeSkyLight();Behaviors.of(BlockType.CARROTS).onPlaced(w,8,41,6,(byte)0);
        w.runScheduledTicks(230000,512,48000);check(w.getBlockMeta(8,41,6)==0,"dark crop grew");
    }
    static void planting(){
        World w=blank();w.setBlock(6,40,6,BlockType.FARMLAND,(byte)7);Inventory inv=new Inventory();inv.set(0,ItemStack.of("wheat_seeds",3));
        check(Farming.use(w,6,40,6,1,inv,0,GameMode.SURVIVAL),"seed not planted");
        check(w.getBlock(6,41,6)==BlockType.WHEAT&&inv.get(0).count==2,"seed conservation");
        check(!Farming.use(w,6,40,6,1,inv,0,GameMode.SURVIVAL)&&inv.get(0).count==2,"occupied seed consumed");
        w.setBlock(6,41,6,BlockType.WHEAT,(byte)7);List<ItemStack> drops=new ArrayList<>();w.simulate((s,x,y,z)->drops.add(s),0);
        w.setBlock(6,40,6,BlockType.AIR);w.processNeighbourUpdates(128);
        check(w.getBlock(6,41,6)==BlockType.AIR,"unsupported crop survived");
        check(drops.stream().anyMatch(s->s.item.id.path().equals("wheat")&&s.count==1),"mature wheat lost");
        check(drops.stream().anyMatch(s->s.item.id.path().equals("wheat_seeds")&&s.count>=1&&s.count<=3),"mature seeds lost");
    }
    static void trees(){
        World w=blank();w.setBlock(15,40,8,BlockType.DIRT);w.setBlock(15,41,8,BlockType.SAPLING);
        w.getChunk(0,0).computeSkyLight();w.getChunk(1,0).computeSkyLight();
        final int[] batches={0};w.setBlockObserver(new World.BlockObserver(){
            public void changed(int x,int y,int z,BlockType old,BlockType now,byte meta){}
            public void batch(java.util.List<World.BlockChange> changes){batches[0]++;}
        });
        Behaviors.of(BlockType.SAPLING).scheduledTick(w,15,41,8,(byte)0,0);
        check(w.getBlock(15,41,8)==BlockType.WOOD,"sapling not grown");
        check(batches[0]==1,"tree not sent as one batch");
        boolean other=false;for(int x=16;x<19;x++)for(int y=44;y<53;y++)for(int z=5;z<12;z++)other|=w.getBlock(x,y,z)==BlockType.LEAVES;
        check(other,"crown stopped at chunk boundary");
        w.setBlock(5,40,8,BlockType.DIRT);w.setBlock(5,41,8,BlockType.SAPLING);w.setBlock(5,43,8,BlockType.STONE);
        Behaviors.of(BlockType.SAPLING).scheduledTick(w,5,41,8,(byte)0,0);
        check(w.getBlock(5,41,8)==BlockType.SAPLING&&w.getBlock(5,43,8)==BlockType.STONE,"blocked tree destroyed obstacle");
    }
}
