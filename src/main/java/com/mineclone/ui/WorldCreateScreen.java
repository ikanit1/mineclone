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
    private int mode = 0,difficulty=2;
    private boolean keepInventory=true,allowCommands=true,more;
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
        float pw=Math.min(600f,sw-48f),ph=Math.min(674f,sh-24f);
        float px=(sw-pw)/2,py=Math.max(12,(sh-ph)/2);t.panel(px,py,pw,ph);
        t.textCentered("Новый мир",sw/2f,py+38,MenuTheme.HEADER,1);
        float x=px+24,w=pw-48,y=py+68;
        t.smallText("Название мира",x,y,MenuTheme.TEXT_DIM,1);
        boolean submit=t.textField("create.name",x,y+7,w,38,name,defaultName);y+=66;
        t.smallText("Сид — пусто для случайного мира",x,y,MenuTheme.TEXT_DIM,1);
        submit|=t.textField("create.seed",x,y+7,w-150,38,seed,"Случайный");
        if(t.button("create.dice",x+w-140,y+7,140,38,"Другой")){seed.setText(Long.toString(random.nextLong()));t.focus("create.seed");}y+=66;
        mode=t.segmented("create.mode",x,y,w,38,MODES,mode);
        t.smallText(MODE_HINTS[mode],x,y+59,MenuTheme.TEXT_DIM,1);y+=78;
        t.smallText("Сложность",x,y,MenuTheme.TEXT_DIM,1);
        difficulty=t.segmentedSmall("create.difficulty",x,y+8,w,36,WorldRulesScreen.DIFFICULTIES,difficulty);y+=65;
        if(t.button("create.more",x,y,w,34,more?"Меньше настроек":"Ещё настройки"))more=!more;y+=42;
        if(more){keepInventory=t.toggle("create.keep",x,y,w,36,"Сохранять вещи при смерти",keepInventory);y+=40;
            allowCommands=t.toggle("create.commands",x,y,w,36,"Разрешить команды",allowCommands);y+=40;}
        float rt=t.slider("create.radius",x,y,w,MenuTheme.ROW_H,"Дальность прорисовки",MenuText.count(settings.renderRadius,"чанк","чанка","чанков"),SettingsScreen.radiusT(settings.renderRadius));
        int radius=SettingsScreen.radiusAt(rt);if(radius!=settings.renderRadius){settings.renderRadius=radius;settings.changed();}

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
        return MenuAction.create(new WorldSettings(n.isEmpty() ? defaultName : n,s,m,new com.mineclone.world.WorldRules(com.mineclone.world.Difficulty.values()[difficulty],keepInventory,allowCommands,true,true)));
    }

    @Override
    public void closed() {
        // Дальность могли сдвинуть и здесь — запись та же, что у экрана настроек.
        settings.commit();
    }
}
