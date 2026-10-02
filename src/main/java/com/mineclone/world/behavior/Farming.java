package com.mineclone.world.behavior;

import com.mineclone.item.ToolClass;
import com.mineclone.world.*;

/** Screen-independent hoe, seed and landing actions; callers provide the participant's inventory. */
public final class Farming {
    private Farming(){}
    public static boolean crop(BlockType b){return b==BlockType.WHEAT||b==BlockType.CARROTS||b==BlockType.POTATOES;}
    public static BlockType seed(ItemStack held){
        if(held==null||held.count<=0)return null;
        return switch(held.item.id.toString()){
            case "mineclone:oak_sapling", "mineclone:spruce_sapling", "mineclone:acacia_sapling"->BlockType.SAPLING;
            case "mineclone:wheat_seeds"->BlockType.WHEAT;
            case "mineclone:carrot"->BlockType.CARROTS;
            case "mineclone:potato"->BlockType.POTATOES;
            default->null;
        };
    }
    public static boolean canUse(World w,int x,int y,int z,int faceY,ItemStack held){
        if(faceY!=1||held==null||held.count<=0||w.getBlock(x,y+1,z)!=BlockType.AIR)return false;
        BlockType b=w.getBlock(x,y,z);
        return held.tool()!=null&&held.tool().toolClass()==ToolClass.HOE&&(b==BlockType.DIRT||b==BlockType.GRASS||b==BlockType.DRY_GRASS||b==BlockType.SNOWY_GRASS)
                || b==BlockType.FARMLAND&&crop(seed(held))
                || b.isSoil()&&seed(held)==BlockType.SAPLING;
    }
    public static boolean use(World w,int x,int y,int z,int faceY,Inventory inv,int slot,GameMode mode){
        ItemStack held=inv.get(slot);if(!canUse(w,x,y,z,faceY,held))return false;
        BlockType plant=seed(held);
        if(plant!=null){byte meta=(byte)(held.item.id.path().equals("spruce_sapling")?1:held.item.id.path().equals("acacia_sapling")?2:0);
            w.setBlock(x,y+1,z,plant,meta);Behaviors.of(plant).onPlaced(w,x,y+1,z,meta);if(mode==GameMode.SURVIVAL)inv.removeOne(slot);}
        else {w.setBlock(x,y,z,BlockType.FARMLAND);Behaviors.of(BlockType.FARMLAND).onPlaced(w,x,y,z,(byte)0);if(mode==GameMode.SURVIVAL&&held.wear())inv.set(slot,null);}
        return true;
    }
    public static void trample(World w,float x,float feetY,float z,float fall,boolean chicken){
        if(chicken||fall<1||!Float.isFinite(feetY))return;
        int bx=(int)Math.floor(x),by=(int)Math.floor(feetY-.001f),bz=(int)Math.floor(z);
        if(w.getBlock(bx,by,bz)==BlockType.FARMLAND)w.setBlock(bx,by,bz,BlockType.DIRT);
    }
}
