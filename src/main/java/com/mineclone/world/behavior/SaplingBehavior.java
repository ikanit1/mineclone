package com.mineclone.world.behavior;
import com.mineclone.world.*;
import com.mineclone.world.gen.TreeShapes;

/** Replenishable trees; all crown chunks must be loaded and clear before any block changes. */
final class SaplingBehavior implements BlockBehavior {
    public boolean needsSupport(){return true;}
    public boolean canSurvive(World w,int x,int y,int z,byte m){return w.getBlock(x,y-1,z).isSoil();}
    public boolean canPlaceAt(World w,int x,int y,int z,PlaceContext c){return canSurvive(w,x,y,z,(byte)0);}
    public void onPlaced(World w,int x,int y,int z,byte m){w.scheduleTick(x,y,z,BlockType.SAPLING,6000+Math.floorMod(hash(w,x,y,z),6001));}
    private static long hash(World w,int x,int y,int z){return w.seed^x*341873128712L^z*132897987541L^y*524287L;}
    public void scheduledTick(World w,int x,int y,int z,byte meta,long lateBy){
        if(!canSurvive(w,x,y,z,meta)){collapse(w,x,y,z,meta,w.dropSink());return;}
        if(w.plantLight(x,y,z)<9){onPlaced(w,x,y,z,meta);return;}
        var cells=TreeShapes.cells(Math.min(2,meta&3),x,y-1,z,hash(w,x,y,z));
        if(cells.isEmpty()){onPlaced(w,x,y,z,meta);return;}
        for(var cell:cells){
            if(w.getChunkIfExists(Math.floorDiv(cell.x(),16),Math.floorDiv(cell.z(),16))==null){onPlaced(w,x,y,z,meta);return;}
            BlockType b=w.getBlock(cell.x(),cell.y(),cell.z());
            if(b!=BlockType.AIR&&b!=BlockType.SAPLING&&b!=BlockType.LEAVES){onPlaced(w,x,y,z,meta);return;}
        }
        w.batchBlocks(()->{for(var cell:cells)w.setBlock(cell.x(),cell.y(),cell.z(),cell.block(),(byte)(cell.block()==BlockType.LEAVES?meta&3:0));});
    }
}
