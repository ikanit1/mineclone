package com.mineclone.world.damage;

/** Localized cause shared by the death screen and replicated player-life announcements. */
public final class DeathMessage {
    private DeathMessage(){}
    public static String text(DamageSource source){if(source==null)return "Вы погибли";String by=source.attackerType()==null?"противником":switch(source.attackerType()){case ZOMBIE->"зомби";case SKELETON->"скелетом";case SPIDER->"пауком";case CREEPER->"крипером";case WOLF->"волком";default->"животным";};return switch(source.type()){
        case FALL->"Упал с высоты";case DROWN->"Утонул";case FIRE,LAVA->"Сгорел";case STARVE->"Умер от голода";case POISON->"Отравился";case CACTUS->"Наткнулся на кактус";case SUFFOCATE->"Задохнулся";case VOID->"Упал за пределы мира";case LIGHTNING->"Поражён молнией";case MAGIC->"Погиб от магии";case EXPLOSION->source.attackerType()==null?"Погиб от взрыва":"Взорван "+by;case MELEE->"Убит "+by;case PROJECTILE->"Застрелен "+by;case GENERIC->"Погиб от ран";};}
}
