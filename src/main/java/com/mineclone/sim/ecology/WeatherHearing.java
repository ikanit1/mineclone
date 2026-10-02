package com.mineclone.sim.ecology;

import com.mineclone.world.Biome;
import com.mineclone.world.Chunk;
import com.mineclone.world.Weather;
import com.mineclone.world.World;

/** Rain at an exposed sound source shortens the radius a mob can hear, without changing the weather. */
public final class WeatherHearing {
    /** Radius lost to rain at full precipitation, before the extra thunder mask. */
    public static final float RAIN_MASK = 0.35f;
    /** Additional radius lost to full thunder, multiplied by the actual rain. */
    public static final float THUNDER_MASK = 0.40f;
    /** Nearby sounds remain audible even in the strongest rain storm. */
    public static final float MIN_RADIUS_FACTOR = 0.25f;

    private WeatherHearing() {}

    /**
     * The host supplies its authoritative, already blended front or command override.
     * Desert dust, falling snow and a roof over the source do not create rain masking.
     * Only a loaded source column is inspected; this query never generates terrain.
     */
    public static float atSource(World world, Weather.State weather, float x, float y, float z, float loudness) {
        float rain = unit(weather.precipitation());
        if (rain == 0f || loudness <= 0f) return loudness;
        int wx = (int) Math.floor(x), wz = (int) Math.floor(z);
        Biome biome = world.biomes.biomeAt(wx, wz);
        if (!Weather.precipitates(biome) || Weather.snowsAt(biome, y)) return loudness;
        Chunk source = world.getChunkIfExists(Math.floorDiv(wx, Chunk.SIZE_X), Math.floorDiv(wz, Chunk.SIZE_Z));
        if (source == null || y < 0f) return loudness;
        int lx = Math.floorMod(wx, Chunk.SIZE_X), lz = Math.floorMod(wz, Chunk.SIZE_Z);
        for (int roofY = Math.max(0, (int) Math.floor(y) + 1); roofY < Chunk.SIZE_Y; roofY++)
            if (source.get(lx, roofY, lz).solid) return loudness;
        float factor = Math.max(MIN_RADIUS_FACTOR, 1f - rain * (RAIN_MASK + THUNDER_MASK * unit(weather.storm())));
        return loudness * factor;
    }

    private static float unit(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }
}
