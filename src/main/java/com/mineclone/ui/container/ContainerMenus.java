package com.mineclone.ui.container;

import com.mineclone.world.*;
import java.util.List;
import java.util.function.IntConsumer;

/** Shared slot rules for the local UI and authoritative multiplayer operations. No rendering. */
public final class ContainerMenus {
    private ContainerMenus() { }
    private static SlotGroup main(Inventory inv) {
        return new SlotGroup("main",SlotRole.MAIN,new InventoryStorage(inv,Inventory.HOTBAR,Inventory.SIZE-Inventory.HOTBAR),9);
    }
    private static SlotGroup hotbar(Inventory inv) {
        return new SlotGroup("hotbar",SlotRole.HOTBAR,new InventoryStorage(inv,0,Inventory.HOTBAR),9);
    }
    public static ContainerMenu chest(Inventory inv,ItemStack[] chest,IntConsumer changed) {
        ContainerMenu m=new ContainerMenu(List.of(new SlotGroup("chest",SlotRole.CONTAINER,
                new ArrayStorage(chest,null,changed),9),main(inv),hotbar(inv)));
        m.route(SlotRole.MAIN,SlotRole.CONTAINER);m.route(SlotRole.HOTBAR,SlotRole.CONTAINER);
        m.route(SlotRole.CONTAINER,SlotRole.MAIN,SlotRole.HOTBAR);
        return m;
    }
    public static ContainerMenu furnace(Inventory inv,Furnace furnace) {
        ContainerMenu m=new ContainerMenu(List.of(
                new SlotGroup("input",SlotRole.FURNACE_INPUT,new Cell(furnace,0),1),
                new SlotGroup("fuel",SlotRole.FURNACE_FUEL,new Cell(furnace,1),1),
                new SlotGroup("output",SlotRole.FURNACE_OUTPUT,new Cell(furnace,2),1),main(inv),hotbar(inv)));
        m.route(SlotRole.MAIN,SlotRole.FURNACE_FUEL,SlotRole.FURNACE_INPUT);
        m.route(SlotRole.HOTBAR,SlotRole.FURNACE_FUEL,SlotRole.FURNACE_INPUT);
        m.route(SlotRole.FURNACE_INPUT,SlotRole.MAIN,SlotRole.HOTBAR);
        m.route(SlotRole.FURNACE_FUEL,SlotRole.MAIN,SlotRole.HOTBAR);
        m.route(SlotRole.FURNACE_OUTPUT,SlotRole.MAIN,SlotRole.HOTBAR);
        return m;
    }
    private record Cell(Furnace furnace,int slot) implements SlotStorage {
        public int size(){return 1;}
        public ItemStack get(int i){return slot==0?furnace.input:slot==1?furnace.fuel:furnace.output;}
        public void set(int i,ItemStack s){if(slot==0)furnace.input=s;else if(slot==1)furnace.fuel=s;else furnace.output=s;}
        public boolean canPlace(int i,ItemStack s){return s==null || (slot==0?Smelting.result(s)!=null:slot==1&&Smelting.isFuel(s));}
    }
}
