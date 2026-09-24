package com.mineclone.world.behavior;

import com.mineclone.world.BlockType;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;

/**
 * A chest opens its window and, whatever removes it, spills what it holds
 * first. Before BLK-03 each removal path had to remember to spill — the
 * player's pickaxe did, a guest's edit and an explosion did not (TD-05).
 */
final class ChestBehavior implements BlockBehavior {
    @Override public boolean interactive() { return true; }

    @Override
    public UseResult use(World world, int x, int y, int z, byte meta, UseContext ctx) {
        return UseResult.open(UseResult.Menu.CHEST);
    }

    @Override
    public void onRemoved(World world, int x, int y, int z, byte meta, BlockType replacement, DropSink drops) {
        ItemStack[] slots = world.getChest(x, y, z);
        if (slots == null)
            return;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null)
                continue;
            drops.drop(slots[i], x + 0.5f, y + 0.5f, z + 0.5f);
            slots[i] = null;
        }
    }
}
