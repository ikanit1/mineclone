package com.mineclone.ui;

import com.mineclone.save.SaveManager;
import com.mineclone.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Создание мира: имя, сид, режим, дальность прорисовки.
 *
 * <p>Раньше «Новый мир» создавался молча, без единого вопроса. Здесь спрошено
 * ровно то, что потом не поменять: сид и режим. Дальность — общая настройка,
 * она здесь потому, что про неё вспоминают именно перед первым входом.
 */
public final class WorldCreateScreen implements Screen {

    static final String[] MODES = { "Выживание", "Творчество" };
    static final String[] MODE_HINTS = {
            "Добывайте ресурсы, следите за здоровьем и сытостью.",
            "Все блоки сразу и полёт — стройте без ограничений.",
    };
    /** Сид длиннее не нужен: long — это девятнадцать цифр со знаком. */
    static final int SEED_MAX = 32;

    private final SettingsModel settings;
    private final String defaultName;
    private final TextField name;
    private final TextField seed = new TextField("", SEED_MAX, TextField.ANY);
    private final Random random = new Random();
    private int mode = 0;
    private boolean first = true;

    public WorldCreateScreen(SaveManager save, SettingsModel settings) {
        this.settings = settings;
        List<String> names = new ArrayList<>();
        for (SaveManager.WorldInfo w : save.listWorlds(false))
            names.add(w.displayName);
        this.defaultName = WorldSettings.defaultName(names);
        this.name = new TextField(defaultName, WorldSelectScreen.NAME_MAX, TextField.ANY);
    }

    @Override
    public MenuAction draw(MenuTheme t) {
        if (first) {
            t.focus("create.name");
            first = false;
        }
        int sw = t.width(), sh = t.height();
        t.dim(0.30f);
        float pw = Math.min(660f, sw - 48f), ph = Math.min(560f, sh - 40f);
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f;
        t.panel(px, py, pw, ph);
        float x = px + 24f, w = pw - 48f;
        float y = t.header("Новый мир", x, py + 14f, w);

        y += 30f;
        t.smallText("Название мира", x + 2f, y, MenuTheme.TEXT_DIM, 1f);
        boolean submit = t.textField("create.name", x, y + 8f, w, 42f, name, defaultName);

        y += 82f;
        t.smallText("Сид", x + 2f, y, MenuTheme.TEXT_DIM, 1f);
        t.smallRight("пусто — случайный мир", x + w, y, MenuTheme.TEXT_FAINT, 1f);
        float diceW = 170f;
        submit |= t.textField("create.seed", x, y + 8f, w - diceW - 10f, 42f, seed, "Случайный");
        if (t.button("create.dice", x + w - diceW, y + 8f, diceW, 42f, "Случайный")) {
            seed.setText(Long.toString(random.nextLong()));
            t.focus("create.seed");
        }

        y += 82f;
        t.smallText("Режим игры", x + 2f, y, MenuTheme.TEXT_DIM, 1f);
        mode = t.segmented("create.mode", x, y + 8f, w, 42f, MODES, mode);
        t.smallText(MODE_HINTS[mode], x + 2f, y + 72f, MenuTheme.TEXT_DIM, 1f);

        y += 96f;
        float rt = t.slider("create.radius", x, y, w, MenuTheme.ROW_H, "Дальность прорисовки",
                MenuText.count(settings.renderRadius, "чанк", "чанка", "чанков"),
                SettingsScreen.radiusT(settings.renderRadius));
        int r = SettingsScreen.radiusAt(rt);
        if (r != settings.renderRadius) {
            settings.renderRadius = r;
            settings.changed();
        }
        t.smallText("Общая настройка для всех миров.", x + 2f, y + MenuTheme.ROW_H + 18f, MenuTheme.TEXT_FAINT, 1f);

        float bw = (w - 12f) / 2f, by = py + ph - 70f;
        MenuAction result = MenuAction.NONE;
        if (t.button("create.go", x, by, bw, 48f, "Создать мир", MenuTheme.Style.PRIMARY, true) || submit)
            result = create();
        if (t.button("create.cancel", x + bw + 12f, by, bw, 48f, "Отмена"))
            result = MenuAction.back();
        return result;
    }

    private MenuAction create() {
        String n = name.text().trim();
        long s = WorldSettings.parseSeed(seed.text(), random::nextLong);
        GameMode m = mode == 1 ? GameMode.CREATIVE : GameMode.SURVIVAL;
        return MenuAction.create(new WorldSettings(n.isEmpty() ? defaultName : n, s, m));
    }

    @Override
    public void closed() {
        // Дальность могли сдвинуть и здесь — запись та же, что у экрана настроек.
        settings.commit();
    }
}
