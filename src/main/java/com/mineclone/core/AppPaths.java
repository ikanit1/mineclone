package com.mineclone.core;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves game data paths for both dev runs and packaged app-image runs. */
public final class AppPaths {
    private AppPaths() { }

    public static Path baseDir() {
        String configured = System.getProperty("mineclone.appDir");
        if (configured != null && !configured.isBlank())
            return Paths.get(configured).toAbsolutePath().normalize();
        return Paths.get("").toAbsolutePath().normalize();
    }

    public static File file(String relativePath) {
        return baseDir().resolve(relativePath).toFile();
    }

    public static String path(String relativePath) {
        return file(relativePath).getPath();
    }
}
