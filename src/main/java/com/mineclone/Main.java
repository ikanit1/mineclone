package com.mineclone;

import com.mineclone.core.Window;
import com.mineclone.game.Game;

public class Main {
    public static void main(String[] args) {
        System.out.println(com.mineclone.core.BuildInfo.summary());
        boolean regenAtlas = false;
        for (String a : args) {
            if ("--regen-atlas".equals(a)) regenAtlas = true;
        }
        // Автопилот идёт на экране игрока — окно скрыто и фокус не забирает.
        boolean autopilot = System.getProperty("mineclone.autopilot") != null;
        boolean benchmark = com.mineclone.game.BenchDirector.enabled();
        if (benchmark) com.mineclone.game.BenchDirector.configureLaunch();
        Window window = new Window("Mineclone",
                benchmark ? Integer.getInteger("mineclone.bench.width", 1920) : 1280,
                benchmark ? Integer.getInteger("mineclone.bench.height", 1080) : 720,
                benchmark ? Boolean.getBoolean("mineclone.bench.visible") : !autopilot);
        com.mineclone.render.TextureAtlas.prepareAsync();
        int exitCode = 0;
        try {
            window.init();
            Game game;
            try (var shaders = com.mineclone.render.ShaderPreloader.start(window.getHandle())) {
                game = new Game(window, regenAtlas);
            }
            game.run();
            exitCode = game.exitCode();
        } finally {
            window.destroy();
        }
        if (exitCode != 0)
            System.exit(exitCode);
    }
}
