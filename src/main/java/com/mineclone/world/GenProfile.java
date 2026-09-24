package com.mineclone.world;

/**
 * Debug generation profile, independent of the world's generator version.
 * FLAT is selected only by the benchmark launcher.
 */
public enum GenProfile {
    NORMAL, FLAT;

    public static GenProfile benchmarkProfile() {
        return "flat-idle".equals(System.getProperty("mineclone.bench")) ? FLAT : NORMAL;
    }
}
