package com.mineclone.world;

/**
 * Deterministic biome source. Climate and relief noises (derived
 * from the world seed) feed a Whittaker-style climate table. Biomes are
 * regions hundreds of blocks across; nothing is persisted.
 */
public class BiomeProvider {
    static final double CONT_FREQ = 0.0020;
    static final double TEMP_FREQ = 0.0018;
    static final double HUM_FREQ  = 0.0025;

    static final double C_OCEAN = -0.25;
    static final double T_COLD  = -0.22;
    static final double T_HOT   =  0.18;
    static final double H_DRY   =  0.05;
    static final double H_WET   =  0.18;

    /** World-coordinate step of the height-smoothing sample grid. */
    public static final int GRID_STEP = 4;

    private final PerlinNoise continentalness;
    private final PerlinNoise temperature;
    private final PerlinNoise humidity;
    private final PerlinNoise relief;

    public BiomeProvider(long seed) {
        this.continentalness = new PerlinNoise(seed ^ 0xC0FFEE5EED15BADL);
        this.temperature     = new PerlinNoise(seed ^ 0x7E39A21B4C8D55E1L);
        this.humidity        = new PerlinNoise(seed ^ 0x2545F4914F6CDD1DL);
        this.relief          = new PerlinNoise(seed ^ 0x6A09E667F3BCC909L);
    }

    /** Pure climate table; public for tests. Order matters: ocean wins, then cold. */
    public static Biome classify(double cont, double temp, double hum) {
        return classify(cont, temp, hum, 0);
    }

    public static Biome classify(double cont, double temp, double hum, double relief) {
        if (cont < C_OCEAN)  return Biome.OCEAN;
        if (relief > 0.26 && temp < 0.12 && cont > -0.08) return Biome.ALPINE;
        if (relief < -0.32 && temp > 0.04 && hum < 0.18 && cont > -0.08) return Biome.VOLCANIC;
        if (temp < T_COLD)   return hum > 0.04 ? Biome.TAIGA : Biome.TUNDRA;
        if (temp > T_HOT && hum < -0.10 && relief > 0.08) return Biome.BADLANDS;
        if (temp > T_HOT && hum < H_DRY) return Biome.DESERT;
        if (temp > 0.12 && hum < 0.20) return Biome.SAVANNA;
        if (hum > 0.16 && cont < 0.10 && temp > -0.12) return Biome.SWAMP;
        if (hum > H_WET)     return Biome.FOREST;
        return Biome.PLAINS;
    }

    public Biome biomeAt(int wx, int wz) {
        // Offset noise domains: every seed used to have identical climate at (0,0).
        double x = wx + 1937.25, z = wz - 814.75;
        double cont = continentalness.fbm(x * CONT_FREQ, z * CONT_FREQ, 3, 2.0, 0.5);
        double temp = temperature.fbm(x * TEMP_FREQ, z * TEMP_FREQ, 3, 2.0, 0.5);
        double hum  = humidity.fbm(x * HUM_FREQ, z * HUM_FREQ, 3, 2.0, 0.5);
        double elevation = relief.fbm(x * 0.0028, z * 0.0028, 2, 2.0, 0.5);
        return classify(cont, temp, hum, elevation);
    }

    /**
     * Biome at a smoothing-grid point. Grid coords = floorDiv(world, GRID_STEP).
     */
    public Biome biomeAtGrid(int gx, int gz) {
        return biomeAt(gx * GRID_STEP, gz * GRID_STEP);
    }
}
