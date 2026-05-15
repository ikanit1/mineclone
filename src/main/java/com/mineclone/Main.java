package com.mineclone;

import com.mineclone.core.Window;
import com.mineclone.game.Game;

public class Main {
    public static void main(String[] args) {
        boolean regenAtlas = false;
        for (String a : args) {
            if ("--regen-atlas".equals(a)) regenAtlas = true;
        }
        Window window = new Window("Mineclone", 1280, 720);
        try {
            window.init();
            Game game = new Game(window, regenAtlas);
            game.run();
        } finally {
            window.destroy();
        }
    }
}
