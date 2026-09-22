package com.mineclone.world.entity;

/**
 * Параметры одного вида моба. Добавить нового моба = добавить строку сюда
 * (плюс скин в {@code MobSkins} и части тела в {@code MobRenderer}).
 *
 * maxFallSpeed — терминальная скорость падения (отрицательная, м/с). У курицы
 * она заметно ближе к нулю: MC-курица планирует, а не падает камнем.
 */
public enum MobType {
    //        w     h     hp    walk  chase hostile burns fall   soundDir   particleColor           нрав               летает  голос      шаг
    COW    (0.9f, 1.4f, 10f, 0.8f, 0f,   false, false, -55f, "cow",     0.29f, 0.21f, 0.13f, Temper.PASSIVE,  false, "say",     "step"),
    PIG    (0.9f, 0.9f, 10f, 0.9f, 0f,   false, false, -55f, "pig",     0.94f, 0.65f, 0.64f, Temper.PASSIVE,  false, "say",     "step"),
    SHEEP  (0.9f, 1.3f,  8f, 0.9f, 0f,   false, false, -55f, "sheep",   0.94f, 0.94f, 0.94f, Temper.PASSIVE,  false, "say",     "step"),
    CHICKEN(0.4f, 0.7f,  4f, 0.9f, 0f,   false, false,  -3f, "chicken", 0.97f, 0.97f, 0.97f, Temper.PASSIVE,  false, "say",     "step"),
    ZOMBIE (0.6f, 1.8f, 20f, 0.7f, 1.2f, true,  true,  -55f, "zombie",  0.31f, 0.48f, 0.22f, Temper.HOSTILE,  false, "say",     "step"),
    // Дикие звери. Значения добавляются в конец: порядок enum нигде не
    // сохраняется, но таблицы звуков и скинов удобнее читать без перестановок.
    RABBIT (0.4f, 0.5f,  3f, 1.0f, 4.2f, false, false, -55f, "rabbit",  0.62f, 0.50f, 0.40f, Temper.SKITTISH, false, "idle",    "hop"),
    WOLF   (0.6f, 0.85f, 8f, 1.1f, 3.6f, false, false, -55f, "wolf",    0.55f, 0.54f, 0.52f, Temper.NEUTRAL,  false, "panting", "step"),
    BIRD   (0.35f, 0.35f, 2f, 1.2f, 4.8f, false, false, -2.5f, "parrot", 0.25f, 0.45f, 0.78f, Temper.SKITTISH, true, "idle",    "step");

    /** Как вид относится к игроку и другим мобам. */
    public enum Temper {
        /** Скотина: гуляет стадом, бежит только от удара. */
        PASSIVE,
        /** Дичь: убегает, стоит подойти, и от хищников тоже. */
        SKITTISH,
        /** Хищник: охотится на дичь, игрока трогает, только если тот напал. */
        NEUTRAL,
        /** Нежить: охотится на игрока. */
        HOSTILE
    }

    public final float width;
    public final float height;
    public final float maxHealth;
    public final float walkSpeed;
    /** Скорость погони или бегства; 0 у скотины — она никого не преследует. */
    public final float chaseSpeed;
    public final boolean hostile;
    public final boolean burnsInSunlight;
    public final float maxFallSpeed;
    /** Папка под assets/sounds/mob/. */
    public final String soundDir;
    /** Цвет облака частиц при смерти (RGB 0..1) — как BlockType.particleColor. */
    public final float[] particleColor;
    public final Temper temper;
    /** Летает: гравитация на него в полёте не действует. */
    public final boolean flying;
    /**
     * С чего начинаются файлы голоса и шагов. Библиотека в MC-нейминге, а
     * там у кролика «idle» и «hop», а не «say» и «step».
     */
    public final String sayPrefix, stepPrefix;

    /**
     * Дальность стрельбы в блоках; 0 — вид дерётся только вблизи.
     *
     * Стрелков пока нет: единственный, скелет, придёт вместе с блоком новых
     * врагов. Поле заведено сразу, чтобы снаряды и сеть не пришлось
     * переделывать ради одной строки в этой таблице.
     */
    public final float rangedRange;

    MobType(float width, float height, float maxHealth, float walkSpeed, float chaseSpeed,
            boolean hostile, boolean burnsInSunlight, float maxFallSpeed, String soundDir,
            float pr, float pg, float pb, Temper temper, boolean flying,
            String sayPrefix, String stepPrefix) {
        this(width, height, maxHealth, walkSpeed, chaseSpeed, hostile, burnsInSunlight,
                maxFallSpeed, soundDir, pr, pg, pb, temper, flying, sayPrefix, stepPrefix, 0f);
    }

    MobType(float width, float height, float maxHealth, float walkSpeed, float chaseSpeed,
            boolean hostile, boolean burnsInSunlight, float maxFallSpeed, String soundDir,
            float pr, float pg, float pb, Temper temper, boolean flying,
            String sayPrefix, String stepPrefix, float rangedRange) {
        this.rangedRange = rangedRange;
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
        this.temper = temper;
        this.flying = flying;
        this.sayPrefix = sayPrefix;
        this.stepPrefix = stepPrefix;
    }

    /**
     * Что падает с моба. Мясо — единственная причина вообще нападать на
     * мирных, и единственный источник еды в игре без земледелия.
     *
     * Зомби не даёт ничего: иначе ночь превращается в ферму, и сидеть в
     * темноте становится выгоднее, чем строить дом. Волк и птица тоже пусты:
     * охота на них — не способ прокормиться.
     */
    public String drop() {
        return switch (this) {
            case COW -> "beef";
            case PIG -> "porkchop";
            case CHICKEN, RABBIT -> "chicken";
            case SHEEP -> "mutton";
            default -> null;
        };
    }

    /** Сколько единиц падает. */
    public int dropCount() {
        return this == CHICKEN || this == RABBIT ? 1 : 2;
    }

    /** Добыча ли этот вид для волка. */
    public boolean isPrey() {
        return this == RABBIT || this == CHICKEN || this == SHEEP;
    }

    /** Скотина — то, из чего спавнер выбирает стада на траве. */
    public static final MobType[] PEACEFUL = { COW, PIG, SHEEP, CHICKEN };
    /** Дикие звери — у них свои правила появления по биомам. */
    public static final MobType[] WILDLIFE = { RABBIT, WOLF, BIRD };
}
