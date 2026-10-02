package com.mineclone.world.behavior;
import com.mineclone.world.*;
/** Ground vegetation collapses when its soil is removed. */
final class PlantBehavior implements BlockBehavior {
    public boolean needsSupport(){return true;}
    public boolean canPlaceAt(World w,int x,int y,int z,PlaceContext c){return canSurvive(w,x,y,z,(byte)0);}
    public boolean canSurvive(World w,int x,int y,int z,byte meta){return w.getBlock(x,y-1,z).isSoil();}
}
