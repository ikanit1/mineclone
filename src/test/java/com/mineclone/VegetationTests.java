package com.mineclone;
import com.mineclone.world.*;
import com.mineclone.world.gen.*;
import com.mineclone.item.Items;
import com.mineclone.item.loot.LootContext;
import java.util.*;

/** Ground-cover determinism, support and survival seed availability under its persisted feature flag. */
final class VegetationTests {
    static void runAll(TestMain.Runner r){
        r.run("vegetation2 is deterministic on both sides of chunk seams and never floats",VegetationTests::generation);
        r.run("tall grass seeds follow the declared one-in-eight chance",VegetationTests::seeds);
    }
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static boolean plant(BlockType b){return b==BlockType.TALL_GRASS||b==BlockType.FLOWER_RED||b==BlockType.FLOWER_YELLOW;}
    static void generation(){
        World first=new World(20261002L,GenPolicy.fixed(WorldGenVersion.V2)),again=new World(20261002L,GenPolicy.fixed(WorldGenVersion.V2));
        World old=new World(20261002L);int plants=0;
        for(int cx=-4;cx<=4;cx++)for(int cz=-2;cz<=2;cz++){
            Chunk a=first.generateDetached(cx,cz),b=again.generateDetached(cx,cz),v1=old.generateDetached(cx,cz);
            check(Arrays.equals(a.copyBlocks(),b.copyBlocks()),"seed mismatch at "+cx+","+cz);
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=1;y<Chunk.SIZE_Y;y++){
                check(!plant(v1.get(x,y,z)),"vegetation leaked into V1");
                if(!plant(a.get(x,y,z)))continue;plants++;
                check(a.get(x,y-1,z).isSoil(),"floating plant");
                Biome biome=first.biomes.biomeAt(cx*16+x,cz*16+z);
                check(biome!=Biome.TUNDRA&&biome!=Biome.DESERT,"plant in bare biome");
            }
        }
        check(plants>50,"no seeded vegetation: "+plants);
    }
    static void seeds(){
        var random=new SplittableRandom(17);int seeds=0;
        for(int i=0;i<10000;i++)for(var s:Items.get().loot().blockDrops(BlockType.TALL_GRASS,new LootContext(1,i,60,0,null,true,GameMode.SURVIVAL,random)))seeds+=s.count;
        check(seeds>1050&&seeds<1450,"seed rate: "+seeds);
    }
}
