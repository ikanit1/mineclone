package com.mineclone.ui;

import com.mineclone.world.*;
import java.util.function.*;

/** Live host controls; guests see the same values through a read-only screen. */
public final class WorldRulesScreen implements Screen {
    public static final String[] DIFFICULTIES={"Мирная","Лёгкая","Обычная","Сложная"};
    private final Supplier<WorldRules> current;private final Consumer<WorldRules> changed;private final boolean editable;
    public WorldRulesScreen(Supplier<WorldRules> current,Consumer<WorldRules> changed,boolean editable){this.current=current;this.changed=changed;this.editable=editable;}
    public MenuAction draw(MenuTheme t){
        float w=Math.min(600,t.width()-48),x=(t.width()-w)/2,y=(t.height()-420)/2;t.dim(.3f);t.panel(x,y,w,420);
        t.textCentered("Правила мира",t.width()/2f,y+42,MenuTheme.HEADER,1);x+=24;w-=48;y+=72;
        var before=current.get();var rules=before;
        if(editable){int difficulty=t.segmentedSmall("rules.difficulty",x,y,w,40,DIFFICULTIES,rules.difficulty().ordinal());rules=rules.difficulty(Difficulty.values()[difficulty]);
            boolean keep=t.toggle("rules.keep",x,y+64,w,42,"Сохранять вещи при смерти",rules.keepInventory());rules=rules.set("keepInventory",keep);
            boolean commands=t.toggle("rules.commands",x,y+112,w,42,"Разрешить команды",rules.allowCommands());rules=rules.set("allowCommands",commands);
            boolean grief=t.toggle("rules.grief",x,y+160,w,42,"Взрывы разрушают блоки",rules.mobGriefing());rules=rules.set("mobGriefing",grief);
            boolean regen=t.toggle("rules.regen",x,y+208,w,42,"Естественное восстановление",rules.naturalRegeneration());rules=rules.set("naturalRegeneration",regen);
            if(!rules.equals(before))changed.accept(rules);
        }else{
            t.smallText("Изменять правила может хозяин",x,y+20,MenuTheme.TEXT_DIM,1);
            t.smallText("Сложность: "+DIFFICULTIES[rules.difficulty().ordinal()],x,y+70,MenuTheme.TEXT,1);
            t.smallText("Сохранять вещи: "+(rules.keepInventory()?"да":"нет"),x,y+110,MenuTheme.TEXT,1);
            t.smallText("Команды: "+(rules.allowCommands()?"да":"нет"),x,y+150,MenuTheme.TEXT,1);
            t.smallText("Разрушение взрывами: "+(rules.mobGriefing()?"да":"нет"),x,y+190,MenuTheme.TEXT,1);
            t.smallText("Восстановление здоровья: "+(rules.naturalRegeneration()?"да":"нет"),x,y+230,MenuTheme.TEXT,1);
        }
        if(t.button("rules.back",x,y+282,w,44,"Готово"))return MenuAction.back();return MenuAction.NONE;
    }
}
