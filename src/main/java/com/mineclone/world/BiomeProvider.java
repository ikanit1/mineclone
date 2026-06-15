package com.mineclone.world;

/**
 * Deterministic biome source. Three low-frequency climate noises (derived
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

    public BiomeProvider(long seed) {
        this.continentalness = new PerlinNoise(seed ^ 0xC0FFEE5EED15BADL);
        this.temperature     = new PerlinNoise(seed ^ 0x7E39A21B4C8D55E1L);
        this.humidity        = new PerlinNoise(seed ^ 0x2545F4914F6CDD1DL);
    }

    /** Pure climate table; public for tests. Order matters: ocean wins, then cold. */
    public static Biome classify(double cont, double temp, double hum) {
        if (cont < C_OCEAN)  return Biome.OCEAN;
        if (temp < T_COLD)   return Biome.TUNDRA;
        if (temp > T_HOT && hum < H_DRY) return Biome.DESERT;
        if (hum > H_WET)     return Biome.FOREST;
        return Biome.PLAINS;
    }

    public Biome biomeAt(int wx, int wz) {
        double cont = continentalness.fbm(wx * CONT_FREQ, wz * CONT_FREQ, 3, 2.0, 0.5);
        double temp = temperature.fbm(wx * TEMP_FREQ, wz * TEMP_FREQ, 3, 2.0, 0.5);
        double hum  = humidity.fbm(wx * HUM_FREQ, wz * HUM_FREQ, 3, 2.0, 0.5);
        return classify(cont, temp, hum);
    }

    /**
     * Biome at a smoothing-grid point. Grid coords = floorDiv(world, GRID_STEP).
     */
    public Biome biomeAtGrid(int gx, int gz) {
        return biomeAt(gx * GRID_STEP, gz * GRID_STEP);
    }
}
