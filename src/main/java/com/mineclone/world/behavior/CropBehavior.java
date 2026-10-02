package com.mineclone.world.behavior;
import com.mineclone.world.*;

/** Crop stages advance only on a lit field and catch up using the bounded scheduler lateness. */
final class CropBehavior implements BlockBehavior {
    static final int BASE=170*20;
    private final BlockType type;
    CropBehavior(BlockType type){this.type=type;}
    public boolean needsSupport(){return true;}
    public boolean canPlaceAt(World w,int x,int y,int z,PlaceContext c){return canSurvive(w,x,y,z,(byte)0);}
    public boolean canSurvive(World w,int x,int y,int z,byte m){return w.getBlock(x,y-1,z)==BlockType.FARMLAND;}
    private int interval(World w,int x,int y,int z){return BASE*((w.getBlockMeta(x,y-1,z)&7)==0?2:1);}
    public void onPlaced(World w,int x,int y,int z,byte m){if((m&7)<7)w.scheduleTick(x,y,z,type,interval(w,x,y,z));}
    public void scheduledTick(World w,int x,int y,int z,byte m,long lateBy){
        if(!canSurvive(w,x,y,z,m)){collapse(w,x,y,z,m,w.dropSink());return;}
        if(w.plantLight(x,y,z)>=9){
            int stage=Math.min(7,(m&7)+1+(int)Math.min(7,lateBy/interval(w,x,y,z)));
            w.setBlock(x,y,z,type,(byte)stage);m=(byte)stage;
        }
        onPlaced(w,x,y,z,m);
    }
}
