package com.mineclone.net.direct;

import java.util.List;

/**
 * Миры, объявившиеся в своей сети.
 *
 * <p>Тот же приём, что у {@code RoomBrowser} с лобби Photon: приёмник держит
 * игра, а экран только читает. Экран живёт один кадр и рисуется заново —
 * сокету внутри него было бы негде храниться.
 *
 * <p>Открывается при входе на вкладку прямого соединения и закрывается, как
 * только началась игра: слушать широковещательный порт из мира незачем, а
 * занятый порт мешал бы второму экземпляру игры на той же машине.
 */
public class LanBrowser {

    private LanBeacon.Listener listener;

    /** Начать слушать, если ещё не слушаем. */
    public void open() {
        if (listener == null)
            listener = new LanBeacon.Listener();
    }

    public void close() {
        LanBeacon.Listener l = listener;
        listener = null;
        if (l != null)
            l.close();
    }

    public boolean listening() {
        return listener != null;
    }

    /** Найденные миры; пустой список — пока никто не объявился. */
    public List<LanBeacon.Announcement> worlds() {
        LanBeacon.Listener l = listener;
        return l == null ? List.of() : l.worlds();
    }

    /** Строка состояния для экрана. */
    public String status() {
        LanBeacon.Listener l = listener;
        if (l == null)
            return "поиск не запущен";
        String error = l.error();
        if (!error.isEmpty())
            return error;
        int n = l.worlds().size();
        return n == 0 ? "ищем миры в вашей сети…" : n + " мир(ов) рядом";
    }
}
