package com.mineclone.world.behavior;

import com.mineclone.world.BlockType;
import com.mineclone.world.Furnace;
import com.mineclone.world.World;

/** A furnace opens its window and spills its three slots whatever removes it. */
final class FurnaceBehavior implements BlockBehavior {
    @Override public boolean interactive() { return true; }

    @Override
    public UseResult use(World world, int x, int y, int z, byte meta, UseContext ctx) {
        return UseResult.open(UseResult.Menu.FURNACE);
    }

    @Override
    public void onRemoved(World world, int x, int y, int z, byte meta, BlockType replacement, DropSink drops) {
        Furnace f = world.getFurnace(x, y, z);
        if (f == null)
            return;
        if (f.input != null) drops.drop(f.input, x + 0.5f, y + 0.5f, z + 0.5f);
        if (f.fuel != null) drops.drop(f.fuel, x + 0.5f, y + 0.5f, z + 0.5f);
        if (f.output != null) drops.drop(f.output, x + 0.5f, y + 0.5f, z + 0.5f);
        f.input = null;
        f.fuel = null;
        f.output = null;
    }
}
