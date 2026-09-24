package com.mineclone.net;

import com.mineclone.world.gen.GenPolicy;

/**
 * The host's world as a guest builds it: the seed, the name, the precise clock,
 * the mode, the spawn and the generator policy — the host's settings and the
 * chunks it pinned to another version ({@code S_WELCOME} + {@code S_GEN_MAP}).
 */
public record RemoteWorld(long seed, String name, double gameTime, long worldTicks, int gameMode,
                          float spawnX, float spawnY, float spawnZ, GenPolicy generator) {
}
