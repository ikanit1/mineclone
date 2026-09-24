package com.mineclone.ui.container;

import java.util.List;

/** A single inventory gesture, also executable on the host without a window. */
public record MenuCommand(Kind kind, String group, int index, int argument, List<Target> targets) {
    public enum Kind { LEFT, RIGHT, SHIFT, DOUBLE, NUMBER, DROP, DROP_CURSOR, CLONE, DELETE, DRAG, PICK_ALL }
    public record Target(String group, int index) { }

    public static MenuCommand of(Kind kind, SlotRef slot, int argument) {
        return new MenuCommand(kind,slot==null?"":slot.group().id,slot==null?-1:slot.index(),argument,List.of());
    }

    private static SlotRef resolve(ContainerMenu menu,String group,int index) {
        SlotGroup g=menu.group(group);
        return g==null || index<0 || index>=g.size() ? null : new SlotRef(g,index);
    }

    public void apply(ContainerMenu menu,boolean creative) {
        SlotRef s=resolve(menu,group,index);
        switch(kind) {
            case LEFT -> menu.leftClick(s);
            case RIGHT -> menu.rightClick(s);
            case SHIFT -> menu.shiftClick(s);
            case DOUBLE -> menu.doubleClick(s);
            case PICK_ALL -> menu.pickAll(s);
            case NUMBER -> { if(argument>=0 && argument<9)menu.numberKey(s,argument); }
            case DROP -> menu.drop(s,argument!=0);
            case DROP_CURSOR -> menu.dropCursor(argument!=0);
            case CLONE -> { if(creative)menu.cloneFull(s); }
            case DELETE -> { if(creative)menu.delete(s); }
            case DRAG -> {
                menu.beginDrag(argument!=0);
                for(Target t:targets)menu.dragOver(resolve(menu,t.group,t.index));
                menu.endDrag();
            }
        }
    }
}
