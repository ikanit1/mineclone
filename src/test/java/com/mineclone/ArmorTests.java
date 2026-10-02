package com.mineclone;

import com.mineclone.game.Player;
import com.mineclone.world.*;
import com.mineclone.world.damage.*;
import com.mineclone.ui.container.*;

final class ArmorTests {
    static void runAll(TestMain.Runner r){
        r.run("armor defense table agrees with the specified formula and bypass types",ArmorTests::damage);
        r.run("accepted hits wear armor once and remove broken pieces",ArmorTests::wear);
        r.run("equipment routes validate slots and swaps preserve components",ArmorTests::slots);
    }
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    private static Equipment kit(String material){var e=new Equipment();String[] names={"helmet","chestplate","leggings","boots"};for(int i=0;i<4;i++)e.set(i,ItemStack.of(material+"_"+names[i]));return e;}
    private static void damage(){
        String[] materials={"leather","copper","iron","diamond"};float[] defense={7,10,15,20};
        for(int k=0;k<4;k++)for(float raw:new float[]{1,3,8,15,30}){
            var armor=kit(materials[k]).armor();check(armor.points()==defense[k],"set defense");
            float effective=Math.min(20,Math.max(defense[k]/5,defense[k]-raw/(2+armor.toughness()/4)));
            check(Math.abs(ArmorMath.afterArmor(raw,DamageType.MELEE,armor)-raw*(1-effective/25))<1e-5,"table");
            for(var type:DamageType.values())if(type.bypassesArmor())check(ArmorMath.afterArmor(raw,type,armor)==raw,"bypass "+type);
        }
        check(Math.abs(ArmorMath.reduce(3,15,0)-1.38f)<1e-5,"explicit formula gives 1.38 for iron, not the contradictory acceptance number");
        check(ArmorMath.afterArmor(3,DamageType.MELEE,ArmorView.NONE)==3,"bare");
    }
    private static void wear(){Player p=new Player();p.equipment.restore(kit("iron").snapshot());p.equipment.get(0).setDamage(p.equipment.get(0).item.durability-1);
        check(p.damage(DamageSource.of(DamageType.MELEE),8),"first hit");check(p.equipment.get(0)==null&&p.brokenArmor==1,"break");check(p.equipment.get(1).damage()==2,"wear");
        check(!p.damage(DamageSource.of(DamageType.MELEE),8)&&p.equipment.get(1).damage()==2,"invulnerability");p.damage(DamageSource.of(DamageType.FALL),3);check(p.equipment.get(1).damage()==2,"bypass no wear");}
    private static void slots(){Equipment e=new Equipment();Inventory inv=new Inventory();inv.set(10,ItemStack.of("iron_helmet"));var m=ContainerMenus.inventory(inv,e,null);
        m.shiftClick(new SlotRef(m.group("main"),1));check(e.get(0)!=null&&inv.get(10)==null,"shift armor");check(!e.canPlace(0,ItemStack.of("stone"))&&!e.canPlace(1,ItemStack.of("iron_helmet")),"slot validation");
        inv.set(0,ItemStack.of("iron_pickaxe").set(com.mineclone.item.Components.CUSTOM_NAME,"kept"));e.set(4,ItemStack.of("torch",8));e.swapHands(inv,0);check(inv.get(0).count==8&&e.get(4).displayName().equals("kept"),"swap components");
        m.setCursor(ItemStack.of("torch",2));e.set(4,ItemStack.of("torch",8));m.doubleClick(null);check(e.get(0)!=null,"double click armor retained");
    }
}
