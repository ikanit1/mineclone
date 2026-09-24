package com.mineclone.world.behavior;

import com.mineclone.world.ItemStack;

/**
 * Where the things a block leaves behind go: a spilled chest, the torch that
 * lost its wall. The world has {@link #NONE} until a session simulates it —
 * a guest's mirror never drops anything, the host does it for everyone.
 */
@FunctionalInterface
public interface DropSink {
    DropSink NONE = (stack, x, y, z) -> { };

    void drop(ItemStack stack, float x, float y, float z);
}
