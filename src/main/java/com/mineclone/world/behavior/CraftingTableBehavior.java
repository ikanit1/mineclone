package com.mineclone.world.behavior;

import com.mineclone.world.World;

/** A crafting table opens the 3x3 grid. */
final class CraftingTableBehavior implements BlockBehavior {
    @Override public boolean interactive() { return true; }

    @Override
    public UseResult use(World world, int x, int y, int z, byte meta, UseContext ctx) {
        return UseResult.open(UseResult.Menu.CRAFTING);
    }
}
