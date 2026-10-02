package com.mineclone.world.behavior;

import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import com.mineclone.world.shape.Shapes;

/** Placement, hinges and wall support for the appended building forms. */
final class BuildingBehavior implements BlockBehavior {
    private final BlockType type;
    BuildingBehavior(BlockType type){this.type=type;}
    public byte placementMeta(PlaceContext c){
        if(c.carriesMeta())return(byte)c.carriedMeta();
        if(Shapes.of(type)==Shapes.SLAB)return(byte)(c.faceY()<0 || c.faceY()==0&&c.hitY()>.5f?1:0);
        if(type==BlockType.LADDER)return TorchBehavior.mount(c.faceX(),0,c.faceZ());
        byte face=StairsBehavior.facing(c.lookX(),c.lookZ());
        return(byte)(face|(type==BlockType.TRAPDOOR&&(c.faceY()<0||c.faceY()==0&&c.hitY()>.5f)?8:0));
    }
    public boolean canPlaceAt(World w,int x,int y,int z,PlaceContext c){
        return type!=BlockType.LADDER || c.faceY()==0 && canSurvive(w,x,y,z,placementMeta(c));
    }
    public boolean needsSupport(){return type==BlockType.LADDER;}
    public boolean canSurvive(World w,int x,int y,int z,byte m){
        if(type!=BlockType.LADDER)return true;
        int sx=x,sz=z;
        switch(m&7){case 1->sx--;case 2->sx++;case 3->sz--;case 4->sz++;default->{return false;}}
        return Shapes.of(w.getBlock(sx,y,sz)).fullCube(w.getBlockMeta(sx,y,sz));
    }
    public boolean interactive(){return type==BlockType.FENCE_GATE||type==BlockType.TRAPDOOR;}
    public UseResult use(World w,int x,int y,int z,byte m,UseContext c){
        if(!interactive())return UseResult.PASS;
        w.setBlock(x,y,z,type,(byte)(m^4));return UseResult.CONSUMED;
    }
}
