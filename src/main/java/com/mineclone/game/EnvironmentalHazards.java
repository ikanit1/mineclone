package com.mineclone.game;

import com.mineclone.world.World;
import com.mineclone.world.entity.EntityHazards;
import com.mineclone.world.effect.StatusEffect;

/** Player presentation adapter for the shared environmental simulation. */
public final class EnvironmentalHazards {
    private final EntityHazards hazards=new EntityHazards();
    public boolean burning(){return hazards.burning();}
    public void clear(){hazards.clear();}
    public void tick(Player player,World world,float dt){if(!player.isDead())hazards.tick(player,player.position,Player.WIDTH,Player.HEIGHT,Player.EYE_HEIGHT,player.inWater,player.effects.active(StatusEffect.FIRE_RESISTANCE),world,dt);}
}
