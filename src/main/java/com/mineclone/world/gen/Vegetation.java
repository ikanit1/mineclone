package com.mineclone.world.gen;
import com.mineclone.world.*;

/** Flagged deterministic ground cover; no neighbour writes or random stream shared with trees. */
public final class Vegetation {
    private Vegetation(){}
    public static void place(Chunk c,BiomeProvider biomes,long seed,GenFeatures features){
        if(!features.vegetation2())return;
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int wx=c.cx*16+x,wz=c.cz*16+z;Biome biome=biomes.biomeAt(wx,wz);
            int density=switch(biome){case PLAINS,FOREST,SAVANNA->38;case SWAMP->16;case TAIGA->8;default->0;};
            long hash=seed^wx*341873128712L^wz*132897987541L;
            hash=(hash^(hash>>>30))*0xbf58476d1ce4e5b9L;hash=(hash^(hash>>>27))*0x94d049bb133111ebL;hash^=hash>>>31;
            if((hash&127)>=density)continue;
            for(int y=Chunk.SIZE_Y-2;y>=World.SEA_LEVEL;y--){
                BlockType ground=c.get(x,y,z);
                if(ground==BlockType.AIR)continue;
                if((ground==BlockType.GRASS||ground==BlockType.DRY_GRASS||ground==BlockType.PODZOL||ground==BlockType.PEAT)&&c.get(x,y+1,z)==BlockType.AIR){
                    int roll=(int)((hash>>>8)&15);
                    c.set(x,y+1,z,roll==0?BlockType.FLOWER_RED:roll==1?BlockType.FLOWER_YELLOW:BlockType.TALL_GRASS);
                }
                break;
            }
        }
    }
}
