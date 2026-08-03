package com.mineclone.world.entity;

/**
 * Параметры одного вида моба. Добавить нового моба = добавить строку сюда
 * (плюс скин в {@code MobSkins} и части тела в {@code MobRenderer}).
 *
 * maxFallSpeed — терминальная скорость падения (отрицательная, м/с). У курицы
 * она заметно ближе к нулю: MC-курица планирует, а не падает камнем.
 */
public enum MobType {
    //        w     h     hp    walk  chase hostile burns fall   soundDir   particleColor
    COW    (0.9f, 1.4f, 10f, 0.8f, 0f,   false, false, -55f, "cow",     0.29f, 0.21f, 0.13f),
    PIG    (0.9f, 0.9f, 10f, 0.9f, 0f,   false, false, -55f, "pig",     0.94f, 0.65f, 0.64f),
    SHEEP  (0.9f, 1.3f,  8f, 0.9f, 0f,   false, false, -55f, "sheep",   0.94f, 0.94f, 0.94f),
    CHICKEN(0.4f, 0.7f,  4f, 0.9f, 0f,   false, false,  -3f, "chicken", 0.97f, 0.97f, 0.97f),
    ZOMBIE (0.6f, 1.8f, 20f, 0.7f, 1.2f, true,  true,  -55f, "zombie",  0.31f, 0.48f, 0.22f);

    public final float width;
    public final float height;
    public final float maxHealth;
    public final float walkSpeed;
    /** Скорость преследования; 0 у мирных (они никого не преследуют). */
    public final float chaseSpeed;
    public final boolean hostile;
    public final boolean burnsInSunlight;
    public final float maxFallSpeed;
    /** Папка под assets/sounds/mob/. */
    public final String soundDir;
    /** Цвет облака частиц при смерти (RGB 0..1) — как BlockType.particleColor. */
    public final float[] particleColor;

    MobType(float width, float height, float maxHealth, float walkSpeed, float chaseSpeed,
            boolean hostile, boolean burnsInSunlight, float maxFallSpeed, String soundDir,
            float pr, float pg, float pb) {
        this.width = width;
        this.height = height;
        this.maxHealth = maxHealth;
        this.walkSpeed = walkSpeed;
        this.chaseSpeed = chaseSpeed;
        this.hostile = hostile;
        this.burnsInSunlight = burnsInSunlight;
        this.maxFallSpeed = maxFallSpeed;
        this.soundDir = soundDir;
        this.particleColor = new float[] { pr, pg, pb };
    }

    /** Мирные виды — то, из чего спавнер выбирает днём и ночью. */
    public static final MobType[] PEACEFUL = { COW, PIG, SHEEP, CHICKEN };
}
