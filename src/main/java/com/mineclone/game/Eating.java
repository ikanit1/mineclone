package com.mineclone.game;

import com.mineclone.world.ItemStack;

/** Held input consumption: cancellation never consumes or applies effects. */
public final class Eating {
    private ItemStack stack;private int slot;private float seconds,health;
    public float progress(){return stack==null?0:Math.min(1,seconds/stack.food().eatSeconds());}
    public void cancel(){stack=null;seconds=0;}
    public boolean update(Player player,ItemStack held,int selected,boolean down,float dt,java.util.random.RandomGenerator random){
        if(!down||held==null||held.food()==null){cancel();return false;}
        boolean apple=held.item.id.path().equals("apple")||held.item.id.path().equals("golden_apple");
        if(!player.canEat()&&!apple){cancel();return true;}
        if(stack!=held||slot!=selected){stack=held;slot=selected;seconds=0;health=player.health;}
        if(player.health<health){cancel();return true;}
        health=player.health;seconds+=Math.max(0,dt);
        if(seconds>=held.food().eatSeconds()){
            player.eat(held.food(),random);if(!player.isCreative())held.count--;cancel();
        }
        return true;
    }
}
