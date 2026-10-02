package com.mineclone.sim;

import com.mineclone.save.PlayerRecord;
import com.mineclone.world.*;
import com.mineclone.world.entity.ItemEntity;
import java.util.*;

/** One death checkpoint and its inventory/equipment/cursor contents, preserving stack components. */
public final class DeathDrops {
    public record Result(PlayerRecord record,List<ItemEntity> items){}
    private DeathDrops(){}
    public static Result prepare(PlayerRecord before,boolean keepInventory){if(keepInventory)return new Result(before,List.of());var pose=before.pose();var result=new ArrayList<ItemEntity>();
        for(var slots:List.of(before.inventory(),before.equipment(),before.pending()))for(var stack:slots)if(stack!=null&&stack.count>0){var item=ItemEntity.popped(stack.copy(),(float)pose.x(),(float)pose.y()+.5f,(float)pose.z(),new Random(result.size()+31));item.lifetime=600;item.pickupDelay=2;result.add(item);}
        return new Result(before.toBuilder().inventory(new ItemStack[Inventory.SIZE]).equipment(new ItemStack[before.equipment().length]).pending(new ItemStack[0]).build(),List.copyOf(result));}
}
