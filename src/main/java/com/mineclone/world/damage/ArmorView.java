package com.mineclone.world.damage;

/**
 * The armor a damage calculation sees on a target.
 *
 * <p>A snapshot, not a live inventory view: equipment slots arrive with
 * CMB-01, and until then every participant wears {@link #NONE}.
 */
public record ArmorView(float points, float toughness) {
    public static final ArmorView NONE = new ArmorView(0f, 0f);

    public ArmorView {
        if (!Float.isFinite(points) || points < 0f || !Float.isFinite(toughness) || toughness < 0f)
            throw new IllegalArgumentException("armor must be finite and non-negative");
    }
}
