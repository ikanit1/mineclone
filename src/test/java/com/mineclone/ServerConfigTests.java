package com.mineclone;

import com.mineclone.server.ServerConfig;
import java.io.File;
import java.nio.file.Files;

final class ServerConfigTests {
    static void runAll(TestMain.Runner r) {
        r.run("server saves-dir preserves pasted Windows separators when joining world path", () -> {
            var config = Files.createTempFile("mineclone-path-", ".properties");
            try {
                String directory = "E:\\mineclone\\out-test\\server\\saves";
                Files.writeString(config, "saves-dir=" + directory + "\nworld=test\n");
                ServerConfig read = ServerConfig.load(config.toFile());
                if (!directory.equals(read.savesDir)) throw new AssertionError("properties removed Windows separators: " + read.savesDir);
                if (File.separatorChar == '\\') {
                    String joined = new File(read.savesDir, read.worldId).getCanonicalPath();
                    if (!joined.equals(directory + "\\test")) throw new AssertionError("world path joined incorrectly: " + joined);
                }
            } finally { Files.delete(config); }
        });
    }
}
