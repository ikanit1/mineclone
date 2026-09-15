package com.mineclone;

import com.mineclone.core.Window;
import com.mineclone.game.Game;

public class Main {
    public static void main(String[] args) {
        boolean regenAtlas = false;
        for (String a : args) {
            if ("--regen-atlas".equals(a)) regenAtlas = true;
        }
        // Автопилот идёт на экране игрока — окно скрыто и фокус не забирает.
        boolean autopilot = System.getProperty("mineclone.autopilot") != null;
        Window window = new Window("Mineclone", 1280, 720, !autopilot);
        int exitCode = 0;
        try {
            window.init();
            Game game = new Game(window, regenAtlas);
            game.run();
            exitCode = game.exitCode();
        } finally {
            window.destroy();
        }
        if (exitCode != 0)
            System.exit(exitCode);
    }
}
