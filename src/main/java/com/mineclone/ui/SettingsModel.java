package com.mineclone.ui;

import com.mineclone.core.KeyBindings;

/**
 * Настройки, которые правят экраны меню.
 *
 * <p>Экран меняет поля и зовёт {@link #changed()} — игра применяет изменение
 * сразу: громкость слышна на ползунке, полный экран включается по щелчку. При
 * закрытии экрана настроек зовётся {@link #commit()} — игра пишет options.dat.
 * Писать файл на каждый сдвиг ползунка незачем.
 */
public final class SettingsModel {

    public interface Listener {
        void changed(SettingsModel m);

        void committed(SettingsModel m);
    }

    public static final int RADIUS_MIN = 2, RADIUS_MAX = 16;
    public static final int FOV_MIN = 50, FOV_MAX = 120;
    public static final int FPS_MIN = 30, FPS_UNLIMITED = 260;

    public int renderRadius = 6;
    public int fov = 75;
    /** 0..2 — 0 %..200 %. */
    public float brightness = 1f;
    /** 0 — без ограничения. */
    public int maxFps = 0;
    /** 0 — авто, 1..3 — множитель. */
    public int guiScale = 0;
    /** 0 — быстро, 1 — красиво, 2 — ультра. */
    public int shaderQuality = 1;
    public boolean vsync = true;
    public boolean fullscreen = false;
    public boolean viewBobbing = true;
    public float sensitivity = 1f;
    public boolean invertY = false;
    public float masterVolume = 1f;
    public float musicVolume = 1f;
    public float effectsVolume = 1f;
    /** Та же раскладка, что читает Input: назначенная клавиша работает сразу. */
    public final KeyBindings keys;

    private Listener listener;

    public SettingsModel(KeyBindings keys) {
        this.keys = keys != null ? keys : new KeyBindings();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void changed() {
        if (listener != null)
            listener.changed(this);
    }

    public void commit() {
        if (listener != null)
            listener.committed(this);
    }
}
