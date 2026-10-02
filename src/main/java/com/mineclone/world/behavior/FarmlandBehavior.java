package com.mineclone.world.behavior;
import com.mineclone.world.*;

/** Scheduled hydration of cultivated soil; dry empty fields return to dirt. */
final class FarmlandBehavior implements BlockBehavior {
    private static final int INTERVAL=20*20;
    public void onPlaced(World w,int x,int y,int z,byte m){w.scheduleTick(x,y,z,BlockType.FARMLAND,INTERVAL);}
    public void scheduledTick(World w,int x,int y,int z,byte meta,long lateBy){
        boolean water=false;
        outer:for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int dy=0;dy<=1;dy++){
            BlockType b=w.getBlock(x+dx,y+dy,z+dz);
            if(b==BlockType.WATER||b==BlockType.WATER_FLOW){water=true;break outer;}
        }
        int moisture=meta&7;
        if(water)w.setBlock(x,y,z,BlockType.FARMLAND,(byte)7);
        else if(moisture>0)w.setBlock(x,y,z,BlockType.FARMLAND,(byte)Math.max(0,moisture-1-(int)Math.min(7,lateBy/INTERVAL)));
        else if(!Farming.crop(w.getBlock(x,y+1,z))){w.setBlock(x,y,z,BlockType.DIRT);return;}
        onPlaced(w,x,y,z,meta);
    }
}
