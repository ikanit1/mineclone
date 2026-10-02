package com.mineclone.game;

/** Eye adaptation plus poison, stun and cold screen-state envelopes. */
public final class AdvancedFeedback {
    public enum Effect { POISON, STUN, FREEZE, SLOW }
    private final float[] timers = new float[Effect.values().length];
    private com.mineclone.world.effect.StatusEffects effects;
    public void bind(com.mineclone.world.effect.StatusEffects effects){this.effects=effects;}
    private static com.mineclone.world.effect.StatusEffect real(Effect e){return switch(e){case POISON->com.mineclone.world.effect.StatusEffect.POISON;case STUN->com.mineclone.world.effect.StatusEffect.STUN;case FREEZE->com.mineclone.world.effect.StatusEffect.CHILLED;case SLOW->com.mineclone.world.effect.StatusEffect.SLOWNESS;};}
    private float exposure = 1f;

    public void apply(Effect effect, float seconds) {
        if(effects!=null){effects.apply(real(effect),seconds);return;}
        timers[effect.ordinal()] = Math.max(timers[effect.ordinal()], seconds);
    }

    public void update(float dt, float sceneLuminance) {
        for (int i=0;i<timers.length;i++) timers[i] = Math.max(0f, timers[i]-dt);
        float target = Math.max(0.62f, Math.min(1.35f, 0.92f / (0.32f + sceneLuminance)));
        float rate = target < exposure ? 5.5f : 1.2f;
        exposure += (target - exposure) * (1f - (float)Math.exp(-rate * dt));
    }

    public void clearEffects() { java.util.Arrays.fill(timers, 0f); }

    public boolean active(Effect e) { return effects!=null?effects.active(real(e)):timers[e.ordinal()] > 0f; }
    public float amount(Effect e) { return Math.min(1f, (effects!=null?effects.remaining(real(e)):timers[e.ordinal()]) / 2f); }
    public float exposure() { return exposure; }
    public float movementMultiplier() { return active(Effect.SLOW) || active(Effect.FREEZE) ? 0.62f : 1f; }
}
