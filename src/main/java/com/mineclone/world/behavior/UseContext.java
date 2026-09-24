package com.mineclone.world.behavior;

/**
 * Who uses a block.
 *
 * @param actor the participant's network number; the local player of a
 *              single-player game is {@link com.mineclone.world.damage.DamageSource#PLAYER}
 */
public record UseContext(int actor) {
}
