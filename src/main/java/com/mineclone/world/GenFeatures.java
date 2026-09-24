package com.mineclone.world;

/** Explicit debug generation profile. FLAT is selected only by the benchmark launcher. */
public enum GenFeatures {
    NORMAL, FLAT;

    public static GenFeatures benchmarkProfile() {
        return "flat-idle".equals(System.getProperty("mineclone.bench")) ? FLAT : NORMAL;
    }
}
