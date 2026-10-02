package com.mineclone.world;

import com.mineclone.ui.container.SlotStorage;
import com.mineclone.item.Components;
import com.mineclone.world.damage.ArmorView;

/** Four armor slots and an independent offhand; never changes the 36 inventory slots. */
public final class Equipment implements SlotStorage {
    public static final int HEAD=0,CHEST=1,LEGS=2,FEET=3,OFFHAND=4,SIZE=5;
    private final ItemStack[] slots=new ItemStack[SIZE];
    public int size(){return SIZE;}
    public ItemStack get(int i){return i>=0&&i<SIZE?slots[i]:null;}
    public void set(int i,ItemStack s){if(i<0||i>=SIZE)throw new IndexOutOfBoundsException(i);if(!canPlace(i,s))throw new IllegalArgumentException("wrong equipment slot");slots[i]=s;}
    public boolean canPlace(int i,ItemStack s){return i>=0&&i<SIZE&&(s==null||i==OFFHAND||(s.item.armor!=null&&s.item.armor.slot().ordinal()==i));}
    public int maxCount(int i,ItemStack s){return s==null?0:i==OFFHAND?s.maxStack():1;}
    /** Preserve missing/future items on loading; interaction still rejects new invalid placements. */
    public void restore(ItemStack[] a){for(int i=0;i<SIZE;i++)slots[i]=i<a.length&&a[i]!=null?a[i].copy():null;}
    public ItemStack[] snapshot(){ItemStack[] a=new ItemStack[SIZE];for(int i=0;i<SIZE;i++)a[i]=slots[i]==null?null:slots[i].copy();return a;}
    public ArmorView armor(){float points=0,toughness=0;for(int i=0;i<4;i++){var s=slots[i];if(s!=null&&s.item.armor!=null){points+=s.item.armor.defense();toughness+=s.item.armor.toughness();}}return new ArmorView(points,toughness);}
    public float knockbackResistance(){float r=0;for(int i=0;i<4;i++){var s=slots[i];if(s!=null&&s.item.armor!=null)r+=s.item.armor.knockbackResistance();}return Math.min(1,r);}
    /** Accepted non-bypassing hits wear armor; returns the number of broken pieces. */
    public int wear(float amount){int broken=0,n=Math.max(1,(int)(amount/4));for(int i=0;i<4;i++){var s=slots[i];if(s==null||s.item.armor==null)continue;for(int j=0;j<n;j++)if(s.wear()){slots[i]=null;broken++;break;}}return broken;}
    public void swapHands(Inventory inv,int selected){var main=inv.get(selected);inv.set(selected,slots[OFFHAND]);slots[OFFHAND]=main;}
    public SlotStorage cell(int slot){return new SlotStorage(){public int size(){return 1;}public ItemStack get(int i){return Equipment.this.get(slot);}public void set(int i,ItemStack s){Equipment.this.set(slot,s);}public boolean canPlace(int i,ItemStack s){return Equipment.this.canPlace(slot,s);}public int maxCount(int i,ItemStack s){return Equipment.this.maxCount(slot,s);}};}
}
