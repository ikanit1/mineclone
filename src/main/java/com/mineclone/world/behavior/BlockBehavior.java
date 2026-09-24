package com.mineclone.world.behavior;

import com.mineclone.item.Items;
import com.mineclone.item.loot.LootContext;
import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;

/**
 * What a block does, as opposed to what it is made of ({@code BlockShape}):
 * how it is placed, what a right click does, what holds it up and what it
 * leaves when it goes (AC-06, BLK-03). {@link Behaviors} gives every block
 * one; a block with no rules of its own shares the default.
 *
 * <p>Nothing here knows about the screen or the network: the host, the
 * dedicated server and the tests run the same code.
 */
public interface BlockBehavior {

    /** Whether the block may be placed in this cell: its support, its room. */
    default boolean canPlaceAt(World world, int x, int y, int z, PlaceContext ctx) {
        return true;
    }

    /** The meta a placement gives: facing, mount, height. */
    default byte placementMeta(PlaceContext ctx) {
        return ctx.carriesMeta() ? (byte) ctx.carriedMeta() : 0;
    }

    /** After the block went in: a door adds its upper half. */
    default void onPlaced(World world, int x, int y, int z, byte meta) {
    }

    /**
     * Whether a right click belongs to the block rather than to placement.
     * A held button repeats placement but never repeats a use: a door held
     * under the cursor would flap.
     */
    default boolean interactive() {
        return false;
    }

    /** A right click on the block. */
    default UseResult use(World world, int x, int y, int z, byte meta, UseContext ctx) {
        return UseResult.PASS;
    }

    /**
     * Whether the block can fall for want of support. Only these are checked
     * when a neighbour changes: water moves hundreds of cells a tick, and
     * everything else would stay where it is anyway.
     */
    default boolean needsSupport() {
        return false;
    }

    /** Whether the block may stay where it is. */
    default boolean canSurvive(World world, int x, int y, int z, byte meta) {
        return true;
    }

    /** The block lost its support: it goes, and drops what breaking it would. */
    default void collapse(World world, int x, int y, int z, byte meta, DropSink drops) {
        BlockType type = world.getBlock(x, y, z);
        world.setBlock(x, y, z, BlockType.AIR);
        dropLoot(world, type, x, y, z, drops);
    }

    /**
     * The block is leaving its cell — whoever removed it: a pickaxe, a guest,
     * a creeper, a command. Called before the new block is written, so what
     * the block holds is still there to spill.
     */
    default void onRemoved(World world, int x, int y, int z, byte meta, BlockType replacement, DropSink drops) {
    }

    /** A tick the block asked for (BLK-04). */
    default void scheduledTick(World world, int x, int y, int z, byte meta, long lateBy) {
    }

    /**
     * What the block drops when the world, not a tool, removes it: its own
     * loot table, rolled as for bare hands in survival, so a fallen torch is a
     * torch in any game mode.
     */
    static void dropLoot(World world, BlockType type, int x, int y, int z, DropSink drops) {
        var context = new LootContext(world.seed, x, y, z, null, false, GameMode.SURVIVAL, null);
        for (ItemStack stack : Items.get().loot().blockDrops(type, context))
            drops.drop(stack, x + 0.5f, y + 0.3f, z + 0.5f);
    }
}
