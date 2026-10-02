package com.mineclone;

import com.mineclone.item.Items;
import com.mineclone.item.ToolClass;
import com.mineclone.item.loot.LootContext;
import com.mineclone.render.TextureAtlas;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.SaveManager;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.ChunkMesher;
import com.mineclone.world.GameMode;
import com.mineclone.world.World;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.behavior.DropSink;
import com.mineclone.world.behavior.SpiderNestBehavior;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.shape.Shapes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.SplittableRandom;
import javax.imageio.ImageIO;
import org.joml.Vector3f;

/** LW-04/05: real nest blocks, host-only scheduled silk and protected save restoration. */
public final class NestBlockTests {
    private static final int Y=41;
    private static final long DAY=WorldClock.TICKS_PER_DAY;
    public static void runAll(TestMain.Runner r) {
        r.run("nest appends its saved ID and uses its original opaque texture",NestBlockTests::block);
        r.run("breaking a nest gives strings rather than another nest",NestBlockTests::loot);
        r.run("nest silk grows once per world day and waits while living world is OFF",NestBlockTests::daily);
        r.run("late nest ticks recover bounded elapsed growth and discard time spent OFF",NestBlockTests::late);
        r.run("nest silk stays in its connected cavity and never overwrites other blocks",NestBlockTests::cavity);
        r.run("nest silk cannot write protected chunks or generate unloaded ones",NestBlockTests::loadedWritable);
        r.run("removed nests and guest mirrors never spin silk",NestBlockTests::removedAndMirror);
        r.run("a saved nest keeps its block and remaining daily tick after reopening",NestBlockTests::saved);
    }

    private static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why); }

    private static void block() throws Exception {
        check(BlockType.FLOWER_RED.ordinal()==63 && BlockType.SPIDER_NEST.ordinal()==64,"existing IDs shifted");
        BlockType nest=BlockType.SPIDER_NEST;
        check(nest.solid && !nest.transparent && !nest.cutout && nest.hardness>=2,"nest is not a sturdy opaque heart");
        check(nest.preferredTool()==ToolClass.AXE && nest.requiredToolLevel()==0,"nest harvest policy");
        check(Shapes.of(nest).fullCube((byte)0),"nest collision must match its cube");
        check(nest.sideTile==205 && nest.topTile==205 && nest.bottomTile==205
                && TextureAtlas.TILE_NAMES[205].equals("spider_nest"),"texture overlaps an existing tile");
        var image=ImageIO.read(Path.of("assets/textures/blocks/spider_nest.png").toFile());
        check(image!=null && image.getWidth()==32 && image.getHeight()==32,"missing 32px authored nest image");
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)check((image.getRGB(x,y)>>>24)==255,"opaque heart has an alpha hole");
        check(Items.get().forBlock(nest)==Items.get().get("spider_nest"),"creative nest block item missing");
    }

    private static void loot() {
        var survival=new LootContext(1,8,Y,8,null,true,GameMode.SURVIVAL,new SplittableRandom(1));
        var drops=Items.get().loot().blockDrops(BlockType.SPIDER_NEST,survival);
        check(drops.size()==1 && drops.get(0).item==Items.get().get("string") && drops.get(0).count==3,"nest must yield three strings by hand");
        var creative=new LootContext(1,8,Y,8,null,true,GameMode.CREATIVE,new SplittableRandom(1));
        check(Items.get().loot().blockDrops(BlockType.SPIDER_NEST,creative).isEmpty(),"creative nest drops loot");
    }

    private static World terrain(boolean roof) {
        return new World(314159) {
            @Override public Chunk generateDetached(int cx,int cz) {
                Chunk c=new Chunk(cx,cz);
                for(int x=0;x<16;x++)for(int z=0;z<16;z++){
                    for(int y=0;y<Y;y++)c.set(x,y,z,BlockType.STONE);
                    if(roof)c.set(x,Y+4,z,BlockType.STONE);
                }
                c.computeSkyLight();
                return c;
            }
        };
    }

    private static World world(boolean roof,boolean simulate,boolean fullRing) {
        World w=terrain(roof);
        for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++)
            if(fullRing || cx==0 || cz==0)w.getChunk(cx,cz);
        if(simulate)w.simulate(DropSink.NONE,0);
        w.setLivingWorldEnabled(true);
        return w;
    }

    private static void plant(World w,int x,int z) {
        w.setBlock(x,Y,z,BlockType.SPIDER_NEST);
        Behaviors.of(BlockType.SPIDER_NEST).onPlaced(w,x,Y,z,(byte)0);
    }

    private static int webs(World w) {
        int count=0;
        for(Chunk c:w.getLoadedChunks())for(byte b:c.copyBlocks())if((b&255)==BlockType.WEB.ordinal())count++;
        return count;
    }

    private static int chunks(World w) { int count=0;for(Chunk c:w.getLoadedChunks())count++;return count; }
    private static void tick(World w,long at) { w.runScheduledTicks(at,512,2*DAY); }

    private static void daily() {
        World w=world(true,true,true);plant(w,8,8);
        check(w.pendingScheduledTicks()==1,"nest did not ask for a day tick");
        tick(w,DAY-1);check(webs(w)==0,"silk grew before the first day");
        tick(w,DAY);check(webs(w)==2 && w.pendingScheduledTicks()==1,"first day should grow two webs and requeue");
        tick(w,DAY);check(webs(w)==2,"same world tick grew silk twice");
        tick(w,2*DAY);check(webs(w)==4,"second day missing silk");
        w.setLivingWorldEnabled(false);tick(w,3*DAY);
        check(webs(w)==4 && w.pendingScheduledTicks()==1,"OFF changed terrain or lost the nest's pending day");
        w.setLivingWorldEnabled(true);tick(w,4*DAY);check(webs(w)==6,"ON did not resume next daily growth");
        World open=world(false,true,true);plant(open,8,8);tick(open,DAY);
        check(webs(open)==0,"nest silk carpeted exposed open terrain");
    }

    private static void cavity() {
        World w=world(true,true,true);
        for(int z=0;z<=16;z++)for(int y=Y;y<Y+4;y++)w.setBlock(10,y,z,BlockType.STONE);
        w.setBlock(7,Y,8,BlockType.GLASS);
        w.setBlock(8,Y,7,BlockType.WATER);
        w.setBlock(8,Y+1,8,BlockType.TORCH);
        plant(w,8,8);
        for(int day=1;day<=24;day++)tick(w,DAY*day);
        check(webs(w)>0 && webs(w)<=SpiderNestBehavior.MAX_WEBS,"unbounded or absent silk");
        check(w.getBlock(7,Y,8)==BlockType.GLASS && w.getBlock(8,Y,7)==BlockType.WATER
                && w.getBlock(8,Y+1,8)==BlockType.TORCH,"nest overwrote a non-air block");
        for(Chunk c:w.getLoadedChunks())for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=Y;y<Y+4;y++) {
            if(c.get(x,y,z)!=BlockType.WEB)continue;
            int wx=c.cx*16+x,wz=c.cz*16+z;
            check((wx-8)*(wx-8)+(y-Y)*(y-Y)+(wz-8)*(wz-8)<=64,"silk escaped the eight-block territory");
            check(wx<10,"silk passed through a solid wall");
        }
    }

    private static void late() {
        World ordinary=world(true,true,true);plant(ordinary,8,8);
        for(int day=1;day<=3;day++)tick(ordinary,DAY*day);
        World late=world(true,true,true);plant(late,8,8);
        // The scheduler services the last forty ticks: its first callback is exactly at day three.
        tick(late,3*DAY+39);
        check(webs(ordinary)==6 && webs(late)==webs(ordinary),"one late callback lost daily silk growth");
        World huge=world(true,true,true);plant(huge,8,8);
        Behaviors.of(BlockType.SPIDER_NEST).scheduledTick(huge,8,Y,8,(byte)0,Long.MAX_VALUE);
        check(webs(huge)==SpiderNestBehavior.MAX_WEBS,"unbounded late growth or missed connected-web cap");
        World off=world(true,true,true);plant(off,8,8);off.setLivingWorldEnabled(false);
        tick(off,8*DAY+39);check(webs(off)==0,"late OFF tick changed terrain");
        off.setLivingWorldEnabled(true);tick(off,9*DAY);
        check(webs(off)==2,"ON replayed days whose scheduled callback ran while OFF");
    }

    private static void loadedWritable() {
        World w=world(true,true,false);
        Chunk protectedChunk=w.getChunkIfExists(1,0);
        byte[] before=protectedChunk.copyBlocks();protectedChunk.markReadOnly();
        plant(w,15,15);
        int loaded=chunks(w);
        for(int day=1;day<=20;day++)tick(w,DAY*day);
        check(webs(w)>0,"writable side of the cave did not grow silk");
        check(chunks(w)==loaded && w.getChunkIfExists(1,1)==null,"silk generated a missing diagonal chunk");
        check(Arrays.equals(before,protectedChunk.copyBlocks()),"silk changed a protected destination chunk");
        World frozen=world(true,true,true);plant(frozen,8,8);
        frozen.getChunkIfExists(0,0).markReadOnly();tick(frozen,DAY);
        check(webs(frozen)==0,"a protected nest heart edited another chunk");
    }

    private static void removedAndMirror() {
        World w=world(true,true,true);plant(w,8,8);
        w.setBlock(8,Y,8,BlockType.AIR);tick(w,DAY);
        check(webs(w)==0 && w.pendingScheduledTicks()==0,"removed nest kept growing");
        World mirror=world(true,false,true);plant(mirror,8,8);tick(mirror,10*DAY);
        check(webs(mirror)==0 && mirror.pendingScheduledTicks()==0,"guest mirror simulated a nest");
    }

    private static WorldSession session(World w,WorldClock clock) {
        WorldSession s=new WorldSession(w,clock,new MobSpawner(1),new EntityStore(),new Participants(),
                ()->new Vector3f(8,Y+1,8),WorldEvents.NONE);
        w.setLivingWorldEnabled(true);
        return s;
    }

    private static void saved() throws Exception {
        Path root=Files.createTempDirectory("mineclone-nest-block-");
        Files.createDirectories(root.resolve("world/chunks"));
        SaveManager save=new SaveManager(root.toFile());
        try {
            World a=world(true,false,true);WorldClock clock=new WorldClock();WorldSession s=session(a,clock);
            plant(a,8,8);clock.advance((DAY/2)/(double)WorldClock.TICKS_PER_SECOND);s.tickScheduled();
            ChunkSnapshot snapshot=s.snapshotChunk(a.getChunkIfExists(0,0));
            check(snapshot!=null && snapshot.ticks.length==3 && snapshot.ticks[1]==64 && snapshot.ticks[2]==DAY,"saved daily tick missing");
            save.saveChunkAsync("world",snapshot);save.flushAndAwait();
            ChunkSnapshot read=save.loadChunk("world",0,0);
            check(read!=null && (read.blocks[Chunk.idx(8,Y,8)]&255)==64 && Arrays.equals(snapshot.ticks,read.ticks),"nest/tick changed in actual chunk save");
            World b=terrain(true);
            ChunkLoader loader=new ChunkLoader(b,new ChunkMesher(b),save,"world");loader.setMeshing(false);
            try {
                for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++)loader.loadNow(cx,cz);
                WorldClock resumed=WorldClock.synced(clock.gameTime(),clock.worldTicks());WorldSession reopened=session(b,resumed);
                resumed.advance((DAY-1-resumed.worldTicks())/(double)WorldClock.TICKS_PER_SECOND);reopened.tickScheduled();
                check(webs(b)==0,"restored nest grew before its remaining delay");
                resumed.advance(1d/WorldClock.TICKS_PER_SECOND);reopened.tickScheduled();
                check(webs(b)==2,"restored nest did not grow exactly once on its saved day");
            } finally { loader.shutdown(); }
        } finally {
            save.flushAndAwait();
            try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        }
    }
}
