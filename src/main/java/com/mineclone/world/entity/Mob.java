package com.mineclone.world.entity;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import org.joml.Vector3f;

import java.util.Random;

/**
 * One mob: state plus AI state machine. The class stays independent from GL
 * and Player; Game translates tick flags into sounds, particles and damage.
 */
public class Mob implements Hittable {
    public enum State {
        IDLE, WANDER, FLEE, CHASE, ATTACK, INVESTIGATE, SEEK_SHELTER,
        /** Нежить отступает из-под яркого света в темноту. */
        FLEE_LIGHT,
        /** Нежить держится у границы света и ждёт, не решаясь шагнуть в него. */
        STALK,
        /** Хищник гонит добычу. */
        HUNT,
        /** Птица в воздухе. */
        FLY
    }

    private static final float IDLE_MIN = 2f, IDLE_MAX = 6f;
    private static final float WANDER_MIN = 2f, WANDER_MAX = 5f;
    static final float FLEE_TIME = 5f;
    private static final float FLEE_SPEED_MUL = 1.5f;
    private static final float IDLE_SOUND_MIN = 5f, IDLE_SOUND_MAX = 15f;

    // --- ИИ, зомби ---
    /** Предельная дистанция обнаружения — на ярко освещённой цели. */
    private static final float AGGRO_RANGE = 16f;
    /**
     * Половина угла обзора, радианы. 60 градусов в каждую сторону: зомби не
     * видит спиной, и подкрасться сзади становится осмысленной тактикой.
     */
    private static final float SIGHT_HALF_ANGLE = (float) Math.toRadians(60);
    /**
     * Ближе этого расстояния угол не проверяется: стоящего вплотную замечают
     * и затылком. Без этого зомби можно бесконечно тыкать в спину в упор.
     */
    private static final float SIGHT_CLOSE = 3f;
    /**
     * Во сколько раз падает дальность обнаружения в полной темноте.
     * Факел в руке демаскирует — это и есть цена света под землёй.
     */
    private static final float DARK_SIGHT_FACTOR = 0.7f;
    /** Сколько моб идёт к источнику шума, прежде чем бросить. */
    private static final float INVESTIGATE_TIME = 6f;
    /** На каком расстоянии от точки шума расследование считается законченным. */
    private static final float INVESTIGATE_REACH = 1.5f;
    private static final float LOSE_RANGE = 24f;
    static final float ATTACK_RANGE = 1.5f;
    private static final float ATTACK_RELEASE = 2f;
    private static final float ATTACK_COOLDOWN = 1f;
    /** Урон зомби игроку — 1.5 сердца. */
    public static final float ATTACK_DAMAGE = 3f;

    // --- ярость ---
    /** Доля здоровья, ниже которой нежить впадает в ярость. */
    public static final float ENRAGE_HEALTH = 0.35f;
    /** Во сколько раз быстрее бежит взбешённый моб. */
    public static final float ENRAGE_SPEED_MUL = 1.5f;
    /** Кулдаун удара в ярости: бьёт почти вдвое чаще. */
    private static final float ENRAGE_COOLDOWN = 0.55f;
    /** Урон взбешённого зомби — два сердца. */
    public static final float ENRAGED_DAMAGE = 4f;

    // --- страх света ---
    /** Блочный свет, из-под которого нежить уходит в темноту. */
    public static final int FEAR_LIGHT = 11;
    /** Сколько моб отступает от света, прежде чем передумать. */
    private static final float FEAR_TIME = 2.5f;
    /** В каком радиусе ищется тёмная клетка для отступления. */
    private static final int FEAR_SEARCH = 5;
    /** Ближе этого факел в руке игрока заставляет зомби робеть. */
    public static final float TORCH_SCARE_RANGE = 3.5f;
    /** Сколько зомби робеет перед факелом, прежде чем всё равно броситься. */
    public static final float TORCH_COURAGE = 3f;

    /** Сколько длится замах зомби — рендер поднимает руки на это время. */
    public static final float ATTACK_SWING_TIME = 0.35f;

    public static final float HURT_FLASH_TIME = 0.4f;
    /**
     * Окно неуязвимости после попадания (MC: hurtResistantTime, 10 тиков).
     * Без него урон определяется скоростью мыши: закликать моба можно за кадр.
     */
    public static final float INVULN_TIME = 0.5f;
    private static final float KNOCKBACK = 5f;
    private static final float KNOCKBACK_UP = 4f;

    /** Порог daylight, выше которого светло «как днём» — зомби горит. */
    private static final float BURN_DAYLIGHT = 0.35f;
    /** Урон в секунду от стояния в огне. Такой же, как у игрока. */
    public static final float FIRE_DAMAGE_PER_SECOND = 2f;

    /** Уровень глаз игрока — куда целится проверка видимости. */
    private static final float PLAYER_EYE_HEIGHT = 1.62f;
    /** Сколько моб валится после смерти, прежде чем исчезнуть. */
    public static final float DEATH_TIME = 0.65f;
    /** Сила отлёта трупа от смертельного удара, блоков в секунду. */
    public static final float DEATH_IMPULSE = 4.2f;

    /** Пройденный путь между звуками шагов, метры. */
    public static final float STEP_DISTANCE = 1.4f;
    /** Сколько секунд упора в стену считается «застрял». */
    private static final float STUCK_TIME = 0.8f;
    /** Сколько идти вбок вдоль стены, прежде чем снова ломиться вперёд. */
    private static final float SIDESTEP_TIME = 1f;
    /** Безопасная высота падения в блоках — как у игрока. */
    private static final float SAFE_FALL = 3f;

    // --- маршрут ---
    /** Как часто перестраивается маршрут при живой погоне. */
    private static final float REPATH_INTERVAL = 0.6f;
    /** Пауза после неудачного поиска: дорогой запрос не повторяется каждый кадр. */
    private static final float REPATH_FAIL_DELAY = 1.4f;
    /** Насколько цель должна уехать, чтобы маршрут пересчитали досрочно. */
    private static final float GOAL_DRIFT = 2.2f;
    /** Радиус, в котором путевая точка считается пройденной. */
    private static final float WAYPOINT_REACH = 0.62f;
    /** Насколько выше должна быть путевая точка, чтобы прыгнуть к ней. */
    private static final float WAYPOINT_STEP_UP = 0.6f;
    /** Дальше этого по горизонтали путевая точка — это прыжок через провал. */
    private static final float WAYPOINT_LEAP = 1.45f;
    /** Ближе этого маршрут не строится: в упор он только мешает. */
    private static final float DIRECT_RANGE = 3.5f;
    /** Дальше этого A* не запускается — цель всё равно уйдёт. */
    private static final float PATH_RANGE = 28f;
    /** На сколько блоков вбок заходит фланкирующий моб. */
    private static final float FLANK_OFFSET = 3.5f;
    /** Ближе этого заход вбок бросается и все идут в лоб. */
    private static final float FLANK_RANGE = 6f;

    // --- стадо ---
    /** Ближе этого к центру стада моб гуляет куда хочет. */
    private static final float HERD_COMFORT = 4.5f;
    /** На каком удалении тяга к стаду становится полной. */
    private static final float HERD_PULL_SPAN = 9f;
    /** Во сколько раз короче стоит отбившийся от стада: он спешит вернуться. */
    private static final float HERD_IDLE_MUL = 0.35f;

    // --- бегство в тень ---
    /** В каком радиусе горящий моб ищет, куда спрятаться. */
    private static final int SHELTER_RADIUS = 7;
    /** Как часто идёт поиск укрытия: полный обход дорог. */
    private static final float SHELTER_SEARCH_INTERVAL = 0.8f;

    /**
     * Сколько моб пережидает в тени, перестав гореть.
     *
     * Без этой выдержки получается метроном: добежал — перестал гореть —
     * ветка провалилась — побежал за игроком — загорелся — побежал обратно.
     */
    private static final float SHELTER_HOLD = 5f;

    public final MobType type;
    public final Vector3f position = new Vector3f();
    public final Vector3f velocity = new Vector3f();
    /** Facing direction: (-sin yaw, 0, -cos yaw). */
    public float yaw;
    public float health;
    public State state = State.IDLE;

    public boolean onGround;
    public boolean hitWall;
    public boolean inWater;
    public boolean dead;
    /** Обратный отсчёт падения набок; 0 — можно убирать из мира. */
    public float deathTimer;
    /** Game ставит true, отыграв звук и частицы смерти ровно один раз. */
    public boolean deathEffectsDone;
    public boolean burning;
    public float hurtFlash;
    public float walkedDistance;
    public float animationTime;
    public float walkAmount;
    public float lookYaw;
    public float grazeAmount;
    /** Smooth takeoff and landing pose weight. */
    public float airborneAmount;
    /** Client-only replicated mood; authoritative AI uses angryTimer. */
    public boolean visualAngry;

    /** >0 — зомби замахивается, рендер поднимает руки. */
    public float attackSwing;

    public boolean justAttacked;
    public boolean justIdleSound;
    public boolean justStepSound;
    /** Только что вошёл в воду — Game отыгрывает всплеск. */
    public boolean justSplashed;
    /** Impact speed used to scale the splash sound and particle burst. */
    public float splashSpeed;
    /** Только что впал в ярость — Game отыгрывает рык и искры. */
    public boolean justEnraged;
    /** Хищник только что укусил добычу: кого. Game снимает ей здоровье. */
    public Mob justBitMob;
    /** Птица только что взлетела — Game хлопает крыльями. */
    public boolean justTookOff;

    /** Нежить ниже порога здоровья: быстрее, злее, не боится света. */
    public boolean enraged;
    /** Редкий модификатор, выбираемый спавнером; NONE у обычных существ. */
    public MobTactics.Elite elite = MobTactics.Elite.NONE;
    /** Повреждение отдельных частей тела меняет скорость и силу атаки. */
    public final LimbDamage limbs = new LimbDamage();
    public MobTactics.Morale morale = MobTactics.Morale.STEADY;
    public MobTactics.Routine routine = MobTactics.Routine.PATROL;
    float moraleTimer;
    /** Процедурная посадка левой/правой группы ног на ступени. */
    public float legOffsetA, legOffsetB;
    private float coverSearchTimer;
    private final Vector3f tacticalCover = new Vector3f();
    private boolean hasTacticalCover;

    // --- смерть ---
    /** Ось, вокруг которой труп валится (горизонтальная, мировая). */
    public float deathAxisX = 0f, deathAxisZ = 1f;
    /** Угол падения трупа, радианы: 0 — стоит, pi/2 — лежит. */
    public float topple;
    private float toppleVel;

    protected final Random rnd;
    float stateTimer;
    private float idleSoundTimer;
    float moveX, moveZ;
    private float knockX, knockZ;
    float attackCooldown;
    private float stepDistance;
    private float invulnTime;
    private boolean wasOnGround;
    private boolean wasInWater;
    /** Сколько уже упирается в стену — накопитель для бокового обхода. */
    private float blockedTimer;
    private float sidestepTimer;
    /** +1 или -1: в какую сторону обходить (залипает на время обхода). */
    private float sidestepSign = 1f;
    /** Куда идти на шум и сколько ещё его помнить. */
    private float noiseX, noiseY, noiseZ;
    private float investigateTimer;

    /** Текущий маршрут (центры клеток) и позиция в нём. */
    private java.util.List<Vector3f> path;
    private int pathIndex;
    private float repathTimer;
    /** Цель, для которой построен маршрут: уехала — пересчитываем. */
    private final Vector3f pathGoal = new Vector3f();
    /** Скорость прыжка через провал, пока моб в воздухе. */
    private float leapX, leapZ;
    private boolean leaping;
    private float leapGrace;
    /**
     * −1, 0 или +1: с какой стороны этот моб заходит на цель. Постоянен на всю
     * жизнь моба — иначе стая дёргалась бы, меняя сторону каждую секунду.
     */
    private final int flankSide;

    /** Центр стада и число соседей; заполняет {@link MobHerd} перед update. */
    private float herdX, herdZ;
    private int herdCount;

    /** Найденное укрытие и таймер следующего поиска. */
    private final Vector3f shelter = new Vector3f();
    private boolean hasShelter;
    private float shelterSearchTimer;
    private float shelterHold;

    /** Куда отступать от света и сколько ещё. */
    private final Vector3f darkSpot = new Vector3f();
    private float fearTimer;
    /** Держит ли игрок источник света и сколько ещё зомби робеет перед ним. */
    private boolean playerTorch;
    private float courage = TORCH_COURAGE;

    // --- дикие звери: заполняет Wildlife перед update ---
    /** Ближайшая угроза (хищник, нежить) и расстояние до неё; MAX — угрозы нет. */
    float threatX, threatZ, threatDist = Float.MAX_VALUE;
    /** Добыча хищника в поле зрения, или null. */
    Mob prey;
    /** Хищник зол на игрока ещё столько секунд. */
    float angryTimer;
    /** Когда хищник снова проголодается. */
    float hungerTimer;
    /** Куда летит птица и садится ли она туда. */
    final Vector3f flyTarget = new Vector3f();
    boolean landing;
    /** Где зверь появился — птица не улетает от этого места слишком далеко. */
    final float homeX, homeY, homeZ;
    /** Убит волком: съеден, добычи с него нет. */
    public boolean eaten;

    /** Контекст тика для дерева поведения — один на моба, не на кадр. */
    private final MobContext ctx = new MobContext();
    /**
     * Куда уходят выпущенные мобом снаряды.
     *
     * Ставится снаружи — моб не знает, кто и как хранит летящее, а у
     * участника сети он и вовсе не стреляет: там симуляции нет.
     */
    public java.util.function.Consumer<Projectile> shotSink;

    /**
     * Мозг враждебного моба. Порядок веток и есть правило игры: гореть
     * важнее, чем гнаться, а гнаться — чем идти на шум. Свет пугает раньше
     * погони: зомби под факелом сначала уходит в тень, и только взбешённый
     * лезет на свет.
     */
    private static final Behavior HOSTILE_BRAIN = Behavior.selector(
            Behavior.sequence(Behavior.check(Mob::needsShelter),
                              Behavior.act(Mob::runForShelter)),
            Behavior.sequence(Behavior.check(Mob::fearsLight),
                              Behavior.act(Mob::escapeLight)),
            // Стрельба стоит ВЫШЕ погони: стрелок, дошедший до дистанции
            // выстрела, должен стрелять, а не продолжать сближение — иначе
            // лучник ведёт себя как медленный зомби с луком.
            Behavior.sequence(Behavior.check(Mob::wantsShoot),
                              Behavior.act(Mob::shootStep)),
            Behavior.sequence(Behavior.check(Mob::wantsChase),
                              Behavior.act(Mob::chaseStep)),
            Behavior.sequence(Behavior.check(Mob::hasNoiseLead),
                              Behavior.act(Mob::investigateStep)),
            Behavior.act(Mob::wanderStep));

    public Mob(MobType type, float x, float y, float z, Random rnd) {
        this.type = type;
        this.rnd = rnd;
        this.position.set(x, y, z);
        this.homeX = x;
        this.homeY = y;
        this.homeZ = z;
        this.health = type.maxHealth;
        this.yaw = rnd.nextFloat() * (float) (Math.PI * 2);
        this.animationTime = (float) (x * 0.73 + z * 0.37);
        this.idleSoundTimer = IDLE_SOUND_MIN + rnd.nextFloat() * (IDLE_SOUND_MAX - IDLE_SOUND_MIN);
        this.flankSide = rnd.nextInt(3) - 1;
        // Фаза пересчёта разъезжается по мобам: иначе вся стая думает в один
        // кадр и кадр проседает ровно раз в REPATH_INTERVAL.
        this.repathTimer = rnd.nextFloat() * REPATH_INTERVAL;
        this.hungerTimer = 10f + rnd.nextFloat() * 30f;
        this.deathAxisX = (float) Math.cos(yaw);
        this.deathAxisZ = (float) -Math.sin(yaw);
        enterIdle();
    }

    private float pendingTick;
    public boolean updateLod(World world, Vector3f playerPos, float dt, float daylight, boolean hostileEnabled) {
        float distance = position.distanceSquared(playerPos);
        float interval = dead || hurtFlash > 0f || burning || state == State.CHASE || state == State.ATTACK
                || distance < 32f * 32f ? 0f : distance < 64f * 64f ? 0.10f : 0.25f;
        pendingTick += dt;
        if (pendingTick < interval) return false;
        float elapsed = pendingTick;
        pendingTick = 0;
        update(world, playerPos, elapsed, daylight, hostileEnabled);
        return true;
    }

    public void update(World world, Vector3f playerPos, float dt, float daylight,
                       boolean hostileEnabled) {
        justAttacked = false;
        justIdleSound = false;
        justStepSound = false;
        justSplashed = false;
        justEnraged = false;
        justBitMob = null;
        justTookOff = false;
        if (dead) {
            updateCorpse(world, dt);
            return;
        }
        if (hurtFlash > 0f)
            hurtFlash = Math.max(0f, hurtFlash - dt);
        if (invulnTime > 0f)
            invulnTime = Math.max(0f, invulnTime - dt);

        animationTime += dt;
        idleSoundTimer -= dt;
        if (idleSoundTimer <= 0f) {
            idleSoundTimer = IDLE_SOUND_MIN + rnd.nextFloat() * (IDLE_SOUND_MAX - IDLE_SOUND_MIN);
            justIdleSound = true;
        }

        if (attackCooldown > 0f)
            attackCooldown -= dt;
        if (shootCooldown > 0f)
            shootCooldown -= dt;
        if (angryTimer > 0f)
            angryTimer = Math.max(0f, angryTimer - dt);

        // Ярость — событие, а не состояние: включается один раз, когда
        // здоровье упало за порог, и больше не выключается.
        if (type.hostile && !enraged && health <= type.maxHealth * ENRAGE_HEALTH) {
            enraged = true;
            justEnraged = true;
        }

        float pdx = playerPos.x - position.x;
        float pdy = playerPos.y - position.y;
        float pdz = playerPos.z - position.z;
        float playerDist = (float) Math.sqrt(pdx * pdx + pdy * pdy + pdz * pdz);
        boolean watching = type.temper == MobType.Temper.PASSIVE && state == State.IDLE
                && playerDist < 6f && canSee(world, playerPos);
        float targetLook = watching ? (float) Math.atan2(-pdx, -pdz) - yaw : 0f;
        targetLook = (float) Math.atan2(Math.sin(targetLook), Math.cos(targetLook));
        targetLook = Math.max(-0.65f, Math.min(0.65f, targetLook));
        float poseBlend = 1f - (float) Math.exp(-5f * dt);
        lookYaw += (targetLook - lookYaw) * poseBlend;
        boolean grazing = type.temper == MobType.Temper.PASSIVE && !watching && state == State.IDLE
                && onGround
                && world.getBlock((int) Math.floor(position.x), (int) Math.floor(position.y - 0.02f),
                        (int) Math.floor(position.z)) == com.mineclone.world.BlockType.GRASS
                && Math.sin(animationTime * 0.7f) > 0.3;
        grazeAmount += ((grazing ? 1f : 0f) - grazeAmount) * poseBlend;

        ctx.set(world, playerPos, dt, daylight, pdx, pdz, playerDist);
        ctx.shots = shotSink;
        boolean retreating = type.hostile && hostileEnabled
                && (morale == MobTactics.Morale.RETREAT || morale == MobTactics.Morale.PANIC);
        boolean sleeping = routine == MobTactics.Routine.SLEEP && !burning
                && playerDist > 8f && threatDist > 8f;
        if (retreating) {
            state = State.FLEE;
            setMoveDirection(-pdx, -pdz);
        } else if (sleeping) {
            state = State.IDLE;
            stopMoving();
        } else if (type.hostile && hostileEnabled && elite == MobTactics.Elite.VENOMOUS
                && venomousRangedStep(world, playerPos, pdx, pdz, playerDist, dt)) {
            // Редкий ядовитый элитник — дальний противник: стреляет, затем
            // уходит за найденный воксельный заслон на время перезарядки.
        } else if (type.hostile && hostileEnabled) {
            HOSTILE_BRAIN.tick(this, ctx);
        } else if (type.temper == MobType.Temper.SKITTISH || type.temper == MobType.Temper.NEUTRAL) {
            Wildlife.tick(this, ctx, hostileEnabled);
        } else {
            // Скотина бежит от хищника, не дожидаясь укуса: курица, к которой
            // подбирается волк, стоящая столбом — это не живое стадо.
            if (type.temper == MobType.Temper.PASSIVE && threatDist < Wildlife.PASSIVE_THREAT
                    && state != State.FLEE) {
                setMoveDirection(position.x - threatX, position.z - threatZ);
                state = State.FLEE;
                stateTimer = FLEE_TIME * 0.6f;
            }
            updatePeaceful(dt);
        }

        if (type.flying && state == State.FLY) {
            Wildlife.flyStep(this, world, dt);
        } else {
            walkStep(world, dt);
        }
        updateLegIk(world, dt);
        airborneAmount += ((!onGround && !inWater ? 1f : 0f) - airborneAmount)
                * (1f - (float) Math.exp(-14f * dt));

        if (attackSwing > 0f)
            attackSwing = Math.max(0f, attackSwing - dt);

        if (inWater && !wasInWater) {
            justSplashed = true;
        }
        wasInWater = inWater;

        applySunBurn(world, dt, daylight);
        if (health <= 0f)
            die(0f, 0f, false);
    }

    private boolean venomousRangedStep(World world, Vector3f playerPos, float dx, float dz,
                                       float dist, float dt) {
        if (dist < 3.5f || dist > 14f || !canSee(world, playerPos))
            return false;
        coverSearchTimer -= dt;
        if (attackCooldown > 0f) {
            if (coverSearchTimer <= 0f) {
                coverSearchTimer = 0.8f;
                Vector3f found = MobTactics.findCover(world, position, playerPos, 6);
                hasTacticalCover = found != null;
                if (found != null) tacticalCover.set(found);
            }
            if (hasTacticalCover) navigateTo(world, dt, tacticalCover);
            else setMoveDirection(-dx, -dz);
            state = State.SEEK_SHELTER;
            return true;
        }
        state = State.ATTACK;
        stopMoving();
        yaw = (float) Math.atan2(-dx, -dz);
        justAttacked = true;
        attackSwing = ATTACK_SWING_TIME;
        attackCooldown = 1.8f;
        hasTacticalCover = false;
        return true;
    }

    private void updateLegIk(World world, float dt) {
        if (type.flying || dead) return;
        float rightX = (float) Math.cos(yaw) * type.width * 0.28f;
        float rightZ = (float) -Math.sin(yaw) * type.width * 0.28f;
        float a = groundOffset(world, position.x - rightX, position.z - rightZ);
        float b = groundOffset(world, position.x + rightX, position.z + rightZ);
        float blend = 1f - (float) Math.exp(-12f * dt);
        legOffsetA += (a - legOffsetA) * blend;
        legOffsetB += (b - legOffsetB) * blend;
    }

    private float groundOffset(World world, float x, float z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int around = (int) Math.floor(position.y);
        for (int y = around + 1; y >= around - 2; y--)
            if (world.getBlock(bx, y - 1, bz).solid && !world.getBlock(bx, y, bz).solid)
                return Math.max(-0.32f, Math.min(0.28f, y - position.y));
        return 0f;
    }

    /** Ходьба и физика на земле: всё, что было движением моба до птиц. */
    private void walkStep(World world, float dt) {
        float speed = currentSpeed();
        // Пока идёт боковой обход — направление повёрнуто на 90°. Это не
        // pathfinding: без этого моб, упёршийся в стену шире одного блока,
        // бесконечно прыгает в неё на месте, пока маршрут не построился.
        float dirX = moveX, dirZ = moveZ;
        if (sidestepTimer > 0f) {
            sidestepTimer -= dt;
            dirX = -moveZ * sidestepSign;
            dirZ = moveX * sidestepSign;
        }
        if (leaping) {
            // В прыжке через провал скорость не управляется: иначе моб
            // тормозит в воздухе и падает в ту самую яму.
            velocity.x = leapX;
            velocity.z = leapZ;
        } else {
            velocity.x = dirX * speed + knockX;
            velocity.z = dirZ * speed + knockZ;
        }
        float decay = (float) Math.pow(0.02, dt);
        knockX *= decay;
        knockZ *= decay;

        // step() обнулит vel.y при посадке — скорость удара нужно снять до него.
        float impactSpeed = -velocity.y;
        float previousX = position.x, previousZ = position.z;
        EntityPhysics.Contact c = EntityPhysics.step(world, position, velocity,
                type.width, type.height, dt, type.maxFallSpeed);
        if (c.inWater() && !inWater)
            splashSpeed = Math.max(0f, impactSpeed);
        onGround = c.onGround();
        hitWall = c.hitWall();
        inWater = c.inWater();

        if (leaping) {
            leapGrace -= dt;
            if (leapGrace <= 0f && (onGround || inWater))
                leaping = false;
        }

        // Накопитель «застрял» тикает по самому упору в стену, а не по контакту с
        // землёй: прыжок сбрасывает onGround в следующем кадре и обнулял бы
        // счётчик тем самым прыжком, который мы и лечим.
        if (hitWall && !leaping) {
            blockedTimer += dt;
            if (onGround)
                velocity.y = EntityPhysics.JUMP_VELOCITY;
            if (blockedTimer > STUCK_TIME && sidestepTimer <= 0f) {
                sidestepTimer = SIDESTEP_TIME;
                sidestepSign = rnd.nextBoolean() ? 1f : -1f;
                blockedTimer = 0f;
            }
        } else {
            blockedTimer = 0f;
        }

        // Урон от падения считается по скорости удара, а не по высоте: у курицы
        // терминальная скорость -3 м/с, и ноль урона получается сам, без
        // спец-случая на вид моба.
        if (onGround && !wasOnGround && impactSpeed > 0f) {
            float fallBlocks = impactSpeed * impactSpeed / (2f * -EntityPhysics.GRAVITY);
            float dmg = fallBlocks - SAFE_FALL;
            if (dmg > 0f) {
                health -= dmg;
                hurtFlash = HURT_FLASH_TIME;
            }
        }
        wasOnGround = onGround;

        float dxMoved = position.x - previousX, dzMoved = position.z - previousZ;
        float hSpeed = dt > 0f ? (float) Math.sqrt(dxMoved * dxMoved + dzMoved * dzMoved) / dt : 0f;
        float targetWalk = Math.min(1f, hSpeed / Math.max(0.1f, type.walkSpeed));
        walkAmount += (targetWalk - walkAmount) * (1f - (float) Math.exp(-10f * dt));
        walkedDistance += hSpeed * dt;
        if (onGround) {
            stepDistance += hSpeed * dt;
            if (stepDistance >= STEP_DISTANCE) {
                stepDistance = 0f;
                justStepSound = true;
            }
        }
        if (!leaping && (dirX != 0f || dirZ != 0f))
            yaw = (float) Math.atan2(-dirX, -dirZ);
    }

    /**
     * Мёртвое тело доигрывает падение: летит по импульсу удара, валится вокруг
     * оси поперёк удара и чуть отскакивает от земли — «рэгдолл», насколько
     * он возможен у коробки из шести кубов.
     */
    private void updateCorpse(World world, float dt) {
        if (deathTimer > 0f)
            deathTimer = Math.max(0f, deathTimer - dt);
        // Пружина с недодемпфированием: тело перелетает горизонталь и
        // оседает обратно — удар о землю, а не плавный поворот.
        float target = 1.5708f;
        toppleVel += ((target - topple) * 90f - toppleVel * 9f) * dt;
        topple = Math.max(0f, Math.min(1.9f, topple + toppleVel * dt));
        EntityPhysics.Contact c = EntityPhysics.step(world, position, velocity,
                type.width, type.height, dt, type.maxFallSpeed);
        float friction = (float) Math.pow(c.onGround() ? 0.004 : 0.5, dt);
        velocity.x *= friction;
        velocity.z *= friction;
        onGround = c.onGround();
    }

    /**
     * Переход в смерть.
     *
     * @param impulseX,impulseZ направление, откуда пришёл смертельный удар;
     *                          нули — умер не от удара (солнце, падение)
     */
    private void die(float impulseX, float impulseZ, boolean struck) {
        if (dead)
            return;
        dead = true;
        deathTimer = DEATH_TIME;
        state = State.IDLE;
        leaping = false;
        float len = (float) Math.sqrt(impulseX * impulseX + impulseZ * impulseZ);
        if (struck && len > 1e-4f) {
            float nx = impulseX / len, nz = impulseZ / len;
            // Тело отлетает от удара и валится вперёд по его направлению:
            // ось падения — поперёк удара.
            velocity.x = nx * DEATH_IMPULSE;
            velocity.z = nz * DEATH_IMPULSE;
            velocity.y = Math.max(velocity.y, 3.2f);
            deathAxisX = nz;
            deathAxisZ = -nx;
            toppleVel = 4.5f;
        } else {
            velocity.x *= 0.2f;
            velocity.z *= 0.2f;
            deathAxisX = (float) Math.cos(yaw);
            deathAxisZ = (float) -Math.sin(yaw);
            toppleVel = 2.5f;
        }
    }

    protected void updatePeaceful(float dt) {
        stateTimer -= dt;
        if (stateTimer > 0f)
            return;
        switch (state) {
            case IDLE -> enterWander();
            default -> enterIdle();
        }
    }

    // ---- ветки дерева поведения -------------------------------------------

    /**
     * Горит на солнце и знает, куда деться.
     *
     * Ветка проваливается, если укрытия рядом нет, — тогда селектор пускает
     * моба гнаться дальше. Так поведение появляется только там, где оно
     * осмысленно: в чистом поле бежать всё равно некуда.
     */
    boolean needsShelter(MobContext c) {
        // Условие с побочным эффектом: таймеры тикают здесь, потому что это
        // первая ветка селектора и она выполняется ровно раз за кадр.
        shelterSearchTimer -= c.dt;
        if (shelterHold > 0f) {
            shelterHold -= c.dt;
            if (hasShelter)
                return true;
        }
        if (!type.burnsInSunlight || !burning || c.daylight <= BURN_DAYLIGHT)
            return false;
        if (hasShelter && sheltered(c.world, (int) shelter.x, (int) shelter.y, (int) shelter.z)) {
            shelterHold = SHELTER_HOLD;
            return true;
        }
        if (shelterSearchTimer > 0f)
            return hasShelter;
        shelterSearchTimer = SHELTER_SEARCH_INTERVAL;
        hasShelter = findShelter(c.world);
        if (hasShelter)
            shelterHold = SHELTER_HOLD;
        return hasShelter;
    }

    Behavior.Status runForShelter(MobContext c) {
        state = State.SEEK_SHELTER;
        // Останавливаемся по крыше над головой, а не по радиусу вокруг цели:
        // с радиусом моб парковался в соседней клетке — формально дошёл,
        // фактически на солнце.
        if (sheltered(c.world, (int) Math.floor(position.x), (int) Math.floor(position.y),
                (int) Math.floor(position.z))) {
            stopMoving();
            return Behavior.Status.SUCCESS;
        }
        navigateTo(c.world, c.dt, shelter);
        return Behavior.Status.RUNNING;
    }

    /**
     * Стоит под ярким блочным светом и знает, куда отступить.
     *
     * Взбешённый не боится ничего. Отступление держится {@link #FEAR_TIME}:
     * без выдержки моб дёргался бы на границе света туда-обратно каждый кадр.
     */
    boolean fearsLight(MobContext c) {
        if (enraged)
            return false;
        if (fearTimer > 0f) {
            fearTimer -= c.dt;
            if (fearTimer > 0f)
                return true;
        }
        if (blockLightAt(c.world, position.x, position.y + 0.5f, position.z) < FEAR_LIGHT)
            return false;
        if (!findDarkSpot(c.world))
            return false;
        fearTimer = FEAR_TIME;
        return true;
    }

    Behavior.Status escapeLight(MobContext c) {
        state = State.FLEE_LIGHT;
        float dx = darkSpot.x - position.x, dz = darkSpot.z - position.z;
        if (dx * dx + dz * dz < 0.5f) {
            stopMoving();
            return Behavior.Status.SUCCESS;
        }
        navigateTo(c.world, c.dt, darkSpot);
        return Behavior.Status.RUNNING;
    }

    /**
     * Самая тёмная проходимая клетка в радиусе {@link #FEAR_SEARCH}. Не
     * ближайшая, а тёмная: отступить на шаг внутри того же пятна света
     * бессмысленно.
     */
    private boolean findDarkSpot(World world) {
        int bx = (int) Math.floor(position.x);
        int by = (int) Math.floor(position.y + 0.1f);
        int bz = (int) Math.floor(position.z);
        int height = Math.max(1, (int) Math.ceil(type.height));
        int bestLight = world.getBlockLightWorld(bx, by, bz);
        boolean found = false;
        for (int attempt = 0; attempt < 18; attempt++) {
            int x = bx + rnd.nextInt(FEAR_SEARCH * 2 + 1) - FEAR_SEARCH;
            int z = bz + rnd.nextInt(FEAR_SEARCH * 2 + 1) - FEAR_SEARCH;
            int y = PathFinder.groundNear(world, x, by, z, height);
            if (y == Integer.MIN_VALUE)
                continue;
            int l = world.getBlockLightWorld(x, y, z);
            if (l < bestLight - 2 && l < FEAR_LIGHT) {
                bestLight = l;
                darkSpot.set(x + 0.5f, y, z + 0.5f);
                found = true;
            }
        }
        return found;
    }

    /** Видит игрока или уже гонится и ещё не потерял. */
    boolean wantsChase(MobContext c) {
        if (state == State.CHASE || state == State.ATTACK || state == State.STALK)
            // Уже начатую погоню видимость не обрывает: моб помнит, куда бежал.
            return c.dist <= LOSE_RANGE;
        return spots(c.world, c.dx, c.dz, c.dist, c.playerPos, c.daylight);
    }

    /** Перезарядка стрелка, секунды. */
    public static final float SHOOT_INTERVAL = 2.2f;
    /** Ближе этого стрелок предпочитает отойти, а не стрелять в упор. */
    public static final float SHOOT_MIN_RANGE = 3.5f;
    /** Начальная скорость стрелы моба. */
    public static final float SHOOT_SPEED = 22f;
    /** Урон стрелы моба. */
    public static final float SHOOT_DAMAGE = 4f;
    private float shootCooldown;


    /**
     * Стрелять ли сейчас: вид умеет, цель в вилке дистанций, её видно и
     * оружие перезарядилось.
     */
    boolean wantsShoot(MobContext c) {
        return type.rangedRange > 0f && c.shots != null && !dead
                && shootCooldown <= 0f
                && c.dist <= type.rangedRange && c.dist >= SHOOT_MIN_RANGE
                && wantsChase(c) && canSee(c.world, c.playerPos);
    }

    /**
     * Выстрел с упреждением по высоте.
     *
     * Целится не в ноги, а в грудь, и приподнимает ствол тем сильнее, чем
     * дальше цель: стрела падает, и прямой наводкой моб мазал бы всегда.
     */
    Behavior.Status shootStep(MobContext c) {
        stopMoving();
        yaw = (float) Math.atan2(-c.dx, -c.dz);
        shootCooldown = SHOOT_INTERVAL;
        float ex = position.x, ey = position.y + type.height * 0.75f, ez = position.z;
        float tx = c.playerPos.x, ty = c.playerPos.y + 1.1f, tz = c.playerPos.z;
        org.joml.Vector3f aim = aimWithLead(ex, ey, ez, tx, ty, tz, SHOOT_SPEED);
        if (aim == null)
            return Behavior.Status.FAILURE;
        Projectile shot = new Projectile("arrow", this, false, SHOOT_DAMAGE);
        shot.position.set(ex, ey, ez);
        shot.velocity.set(aim);
        shot.heading.set(aim).normalize();
        c.shots.accept(shot);
        return Behavior.Status.RUNNING;
    }

    /**
     * Скорость выстрела с упреждением по высоте, либо null для вырожденного
     * случая «цель там же, где стрелок».
     *
     * Ствол приподнимается ровно на то, что стрела потеряет за время полёта:
     * прямой наводкой стрелок мазал бы тем сильнее, чем дальше цель. Чистая
     * функция без моба и мира — поэтому «стрела долетает» проверяется
     * симуляцией в тесте, а не наблюдением за боем.
     */
    public static org.joml.Vector3f aimWithLead(float ex, float ey, float ez,
                                                float tx, float ty, float tz, float speed) {
        float dx = tx - ex, dy = ty - ey, dz = tz - ez;
        float flat = (float) Math.sqrt(dx * dx + dz * dz);
        float v = Math.max(0.01f, speed);
        // Время полёта уточняется итерациями, а не берётся как flat/speed:
        // поднятый ствол отбирает у стрелы горизонтальную скорость, поэтому
        // она летит дольше и падает сильнее, чем по первому приближению. На
        // двадцати шести блоках разница — недолёт в пару блоков.
        float flight = flat / v;
        float lift = 0f, len = 0f;
        for (int i = 0; i < 4; i++) {
            lift = -Projectile.GRAVITY * flight * flight * 0.5f;
            len = (float) Math.sqrt(dx * dx + (dy + lift) * (dy + lift) + dz * dz);
            if (len < 1e-3f)
                return null;
            float horizontal = v * flat / len;
            if (horizontal < 1e-3f)
                return null;
            flight = flat / horizontal;
        }
        if (len < 1e-3f)
            return null;
        return new org.joml.Vector3f(dx / len, (dy + lift) / len, dz / len).mul(v);
    }

    Behavior.Status chaseStep(MobContext c) {
        // Робость перед светом: игрок стоит в ярком свете или держит факел в
        // упор. Зомби не лезет в свет, а ходит у его границы — и только
        // терпение кончается, как он всё равно бросается. Взбешённому всё равно.
        if (!enraged && c.dist > ATTACK_RANGE && hesitates(c)) {
            state = State.STALK;
            float step = 0.9f / Math.max(0.5f, c.dist);
            float nextLight = blockLightAt(c.world, position.x + c.dx * step, position.y + 0.5f,
                    position.z + c.dz * step);
            if (nextLight >= FEAR_LIGHT || (playerTorch && c.dist < TORCH_SCARE_RANGE)) {
                // Шаг к игроку — это шаг в свет. Стоим к нему лицом.
                stopMoving();
                yaw = (float) Math.atan2(-c.dx, -c.dz);
                return Behavior.Status.RUNNING;
            }
        } else if (state == State.STALK) {
            state = State.CHASE;
        }
        if (state != State.CHASE && state != State.ATTACK && state != State.STALK)
            state = State.CHASE;
        steerToTarget(c.world, c.dt, c.playerPos, c.dx, c.dz, c.dist);
        if (c.dist <= ATTACK_RANGE) {
            state = State.ATTACK;
            stopMoving();
            // moveX/Z обнулены — держим лицо к игроку вручную.
            yaw = (float) Math.atan2(-c.dx, -c.dz);
            if (attackCooldown <= 0f) {
                justAttacked = true;
                attackSwing = ATTACK_SWING_TIME;
                attackCooldown = enraged ? ENRAGE_COOLDOWN : ATTACK_COOLDOWN;
                courage = TORCH_COURAGE;
            }
        } else if (state == State.ATTACK && c.dist > ATTACK_RELEASE) {
            state = State.CHASE;
        }
        return Behavior.Status.RUNNING;
    }

    /**
     * Робеет ли моб сейчас. Терпение тратится, пока он стоит у света, и
     * восстанавливается вдали от него: у факела нельзя отсидеться вечно.
     */
    private boolean hesitates(MobContext c) {
        boolean scared = blockLightAt(c.world, c.playerPos.x, c.playerPos.y + 1f, c.playerPos.z) >= FEAR_LIGHT
                || (playerTorch && c.dist < TORCH_SCARE_RANGE);
        if (!scared) {
            courage = Math.min(TORCH_COURAGE, courage + c.dt * 0.5f);
            return false;
        }
        courage -= c.dt;
        return courage > 0f;
    }

    /** Game сообщает, держит ли игрок в руке источник света. */
    public void setPlayerTorch(boolean torch) {
        this.playerTorch = torch;
    }

    boolean hasNoiseLead(MobContext c) {
        return investigateTimer > 0f;
    }

    Behavior.Status investigateStep(MobContext c) {
        investigate(c.dt);
        return Behavior.Status.RUNNING;
    }

    /** Последняя ветка: делать нечего. Брошенную погоню закрываем здесь. */
    Behavior.Status wanderStep(MobContext c) {
        if (state == State.CHASE || state == State.ATTACK || state == State.SEEK_SHELTER
                || state == State.STALK || state == State.FLEE_LIGHT) {
            enterIdle();
            return Behavior.Status.SUCCESS;
        }
        updatePeaceful(c.dt);
        return Behavior.Status.SUCCESS;
    }

    /**
     * Есть ли над этой клеткой крыша: ровно то же условие, по которому
     * {@link #applySunBurn} решает, горит моб или нет.
     */
    private boolean sheltered(World world, int x, int y, int z) {
        int startY = y + (int) Math.ceil(type.height);
        for (int yy = startY; yy < Chunk.SIZE_Y; yy++)
            if (world.getBlock(x, yy, z).solid)
                return true;
        return false;
    }

    /**
     * Ближайшая клетка под крышей в радиусе {@link #SHELTER_RADIUS}.
     *
     * Сначала грубый отсев по небесному свету — одна выборка на колонну:
     * под открытым небом он максимален, и перебирать над ней сто двадцать
     * блоков незачем. Дорогая проверка достаётся только кандидатам.
     */
    private boolean findShelter(World world) {
        int height = Math.max(1, (int) Math.ceil(type.height));
        int bx = (int) Math.floor(position.x);
        int by = (int) Math.floor(position.y + 0.1f);
        int bz = (int) Math.floor(position.z);
        int bestD2 = Integer.MAX_VALUE;
        boolean found = false;
        for (int dx = -SHELTER_RADIUS; dx <= SHELTER_RADIUS; dx++) {
            for (int dz = -SHELTER_RADIUS; dz <= SHELTER_RADIUS; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 == 0 || d2 > SHELTER_RADIUS * SHELTER_RADIUS || d2 >= bestD2)
                    continue;
                int x = bx + dx, z = bz + dz;
                int y = PathFinder.groundNear(world, x, by, z, height);
                if (y == Integer.MIN_VALUE)
                    continue;
                if (world.getSkyLight(x, y, z) >= Chunk.MAX_LIGHT)
                    continue;              // открытое небо — не укрытие
                if (!sheltered(world, x, y, z))
                    continue;
                bestD2 = d2;
                shelter.set(x + 0.5f, y, z + 0.5f);
                found = true;
            }
        }
        return found;
    }

    /**
     * Заметил ли моб игрока прямо сейчас.
     *
     * Три условия подряд, от дешёвого к дорогому: дальность (зависит от того,
     * насколько игрок освещён), конус зрения, и только потом луч видимости.
     */
    private boolean spots(World world, float dx, float dz, float dist, Vector3f playerPos,
                          float daylight) {
        float light = playerLight(world, playerPos, daylight);
        float range = sightRange(light) * (enraged ? 1.3f : 1f);
        if (dist > range)
            return false;
        if (dist > SIGHT_CLOSE && !inSightCone(yaw, dx, dz))
            return false;
        return canSee(world, playerPos);
    }

    /**
     * Насколько освещён игрок: 0 — темнота, 1 — полный свет.
     *
     * Небо умножается на время суток, как в шейдере: хранимый небесный свет
     * ночью всё ещё 15, и без множителя механика скрытности не работала бы
     * под открытым небом вообще.
     */
    private static float playerLight(World world, Vector3f playerPos, float daylight) {
        int x = (int) Math.floor(playerPos.x);
        int y = (int) Math.floor(playerPos.y + 1f);
        int z = (int) Math.floor(playerPos.z);
        float sky = world.getSkyLight(x, y, z) / (float) Chunk.MAX_LIGHT * daylight;
        float block = world.getBlockLightWorld(x, y, z) / (float) Chunk.MAX_LIGHT;
        return Math.max(sky, block);
    }

    static int blockLightAt(World world, float x, float y, float z) {
        return world.getBlockLightWorld((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * Наведение на цель: по маршруту, если он есть, иначе напрямую.
     *
     * Прямое наведение упирается в любую стену шире одного блока — моб
     * толчётся в неё и выглядит сломанным. A* эту стену обходит, но стоит
     * заметно дороже, поэтому запускается по таймеру, не ближе
     * {@link #DIRECT_RANGE} и не дальше {@link #PATH_RANGE}.
     */
    void steerToTarget(World world, float dt, Vector3f target,
                       float dx, float dz, float dist) {
        // Издалека моб целится не в игрока, а в свою точку сбоку от него.
        // Стая из-за этого берёт в кольцо, а не выстраивается в колонну по
        // одному за спиной у первого.
        Vector3f goal = new Vector3f(target);
        if (flankSide != 0 && dist > FLANK_RANGE) {
            float len = Math.max(1e-4f, (float) Math.sqrt(dx * dx + dz * dz));
            goal.x -= dz / len * FLANK_OFFSET * flankSide;
            goal.z += dx / len * FLANK_OFFSET * flankSide;
        }

        navigateTo(world, dt, goal);
    }

    /**
     * Идти к точке: по маршруту, если он нужен и построился, иначе напрямую.
     *
     * Одна дорога на все поводы куда-то идти — погоню, бегство в тень и что
     * появится дальше. Развести их означало бы завести две почти одинаковые
     * копии логики маршрута, которые разъедутся при первой же правке.
     */
    void navigateTo(World world, float dt, Vector3f goal) {
        repathTimer -= dt;
        float dx = goal.x - position.x, dz = goal.z - position.z;
        float flat = (float) Math.sqrt(dx * dx + dz * dz);

        boolean wantPath = flat > DIRECT_RANGE && flat < PATH_RANGE;
        boolean goalMoved = pathGoal.distanceSquared(goal) > GOAL_DRIFT * GOAL_DRIFT;
        if (wantPath && (repathTimer <= 0f || goalMoved || path == null))
            rebuildPath(world, goal);
        if (!wantPath)
            path = null;

        if (followPath())
            return;
        setMoveDirection(dx, dz);
    }

    private void rebuildPath(World world, Vector3f goal) {
        repathTimer = REPATH_INTERVAL;
        int height = Math.max(1, (int) Math.ceil(type.height));
        // Нежить, пока не взбешена, прокладывает путь в обход факелов.
        path = PathFinder.find(world,
                (int) Math.floor(position.x), (int) Math.floor(position.y + 0.1f),
                (int) Math.floor(position.z),
                (int) Math.floor(goal.x), (int) Math.floor(goal.y + 0.1f),
                (int) Math.floor(goal.z), height, type.hostile && !enraged);
        pathIndex = 0;
        pathGoal.set(goal);
        if (path == null)
            repathTimer = REPATH_FAIL_DELAY;
    }

    /**
     * Шаг по маршруту. Возвращает false, если маршрута нет или он кончился —
     * тогда наводимся напрямую.
     */
    private boolean followPath() {
        if (path == null)
            return false;
        // Проскакиваем уже пройденные точки: физика могла протащить моба
        // мимо нескольких клеток за кадр на спуске.
        while (pathIndex < path.size()) {
            Vector3f wp = path.get(pathIndex);
            float wx = wp.x - position.x, wz = wp.z - position.z;
            if (wx * wx + wz * wz > WAYPOINT_REACH * WAYPOINT_REACH
                    || Math.abs(wp.y - position.y) > 1.2f)
                break;
            pathIndex++;
        }
        if (pathIndex >= path.size()) {
            path = null;
            return false;
        }
        Vector3f wp = path.get(pathIndex);
        float wx = wp.x - position.x, wz = wp.z - position.z;
        setMoveDirection(wx, wz);
        float flat = (float) Math.sqrt(wx * wx + wz * wz);
        if (onGround && !leaping) {
            if (flat > WAYPOINT_LEAP && Math.abs(wp.y - position.y) < 1.2f) {
                // Точка за провалом: прыгаем сразу к ней. Скорость подобрана
                // под время полёта прыжка — моб долетает до края, а не
                // приземляется посреди ямы.
                float air = 2f * EntityPhysics.JUMP_VELOCITY / -EntityPhysics.GRAVITY;
                float speed = Math.min(6.5f, flat / (air * 0.92f));
                leapX = wx / flat * speed;
                leapZ = wz / flat * speed;
                leaping = true;
                leapGrace = 0.12f;
                velocity.y = EntityPhysics.JUMP_VELOCITY;
            } else if (wp.y > position.y + WAYPOINT_STEP_UP) {
                // Прыжок по маршруту, а не по удару в стену: ступенька видна заранее.
                velocity.y = EntityPhysics.JUMP_VELOCITY;
            }
        }
        return true;
    }

    /** Есть ли сейчас построенный маршрут — для тестов и отладки. */
    public boolean hasPath() {
        return path != null && pathIndex < path.size();
    }

    /** Сколько точек осталось пройти. */
    public int remainingWaypoints() {
        return path == null ? 0 : Math.max(0, path.size() - pathIndex);
    }

    /** Сторона захода: −1 слева, 0 в лоб, +1 справа. */
    public int flankSide() {
        return flankSide;
    }

    /** Шаг расследования: идём к точке шума, пока не дошли или не надоело. */
    private void investigate(float dt) {
        investigateTimer -= dt;
        float dx = noiseX - position.x, dz = noiseZ - position.z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        if (investigateTimer <= 0f || d < INVESTIGATE_REACH) {
            investigateTimer = 0f;
            enterIdle();
            return;
        }
        state = State.INVESTIGATE;
        setMoveDirection(dx, dz);
    }

    /**
     * Куда сместился центр сородичей рядом. Ставит {@link MobHerd} раз в тик;
     * {@code count == 0} значит «соседей нет», и тогда стадо не учитывается.
     */
    public void setHerd(float x, float z, int count) {
        herdX = x;
        herdZ = z;
        herdCount = count;
    }

    /** Насколько моб отбился от своих; 0, если стада нет. */
    public float herdDistance() {
        if (herdCount == 0)
            return 0f;
        float dx = herdX - position.x, dz = herdZ - position.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    protected void enterIdle() {
        state = State.IDLE;
        stateTimer = IDLE_MIN + rnd.nextFloat() * (IDLE_MAX - IDLE_MIN);
        // Отбившийся от стада не стоит столбом: пауза перед следующей
        // прогулкой у него короче, и он быстрее возвращается к своим.
        if (herdDistance() > HERD_COMFORT)
            stateTimer *= HERD_IDLE_MUL;
        investigateTimer = 0f;
        path = null;
        hasShelter = false;
        shelterHold = 0f;
        fearTimer = 0f;
        moveX = 0f;
        moveZ = 0f;
    }

    /**
     * Прогулка. Направление случайное, но чем дальше моб от своих, тем
     * сильнее оно подмешано к направлению на стадо.
     *
     * Не «идти строго к центру»: стадо тогда схлопывается в одну точку и
     * перестаёт выглядеть живым. Случайность остаётся, тяга её только гнёт.
     */
    protected void enterWander() {
        state = State.WANDER;
        stateTimer = WANDER_MIN + rnd.nextFloat() * (WANDER_MAX - WANDER_MIN);
        float angle = rnd.nextFloat() * (float) (Math.PI * 2);
        moveX = (float) Math.sin(angle);
        moveZ = (float) Math.cos(angle);

        float d = herdDistance();
        if (d > HERD_COMFORT) {
            float pull = Math.min(1f, (d - HERD_COMFORT) / HERD_PULL_SPAN);
            float hx = (herdX - position.x) / d, hz = (herdZ - position.z) / d;
            moveX += (hx - moveX) * pull;
            moveZ += (hz - moveZ) * pull;
            float len = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
            if (len > 1e-4f) {
                moveX /= len;
                moveZ /= len;
            }
        }
    }

    protected void setMoveDirection(float dx, float dz) {
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4f)
            return;
        moveX = dx / len;
        moveZ = dz / len;
    }

    protected void stopMoving() {
        moveX = 0f;
        moveZ = 0f;
    }

    protected float currentSpeed() {
        float s = switch (state) {
            case IDLE, STALK -> 0f;
            case FLEE -> Math.max(type.walkSpeed * FLEE_SPEED_MUL, type.chaseSpeed);
            case CHASE, ATTACK, HUNT -> type.chaseSpeed;
            // На шум идут настороженно, а не бегут: иначе расследование
            // неотличимо от погони и теряет смысл как отдельное состояние.
            case INVESTIGATE -> type.walkSpeed * 1.15f;
            // Горящий бежит быстрее, чем гонится: тень важнее добычи.
            case SEEK_SHELTER -> type.chaseSpeed * 1.2f;
            case FLEE_LIGHT -> type.chaseSpeed;
            case WANDER, FLY -> type.walkSpeed;
        };
        s *= limbs.speedMultiplier();
        if (elite == MobTactics.Elite.FROST)
            s *= 1.12f;
        return enraged ? s * ENRAGE_SPEED_MUL : s;
    }

    private void applySunBurn(World world, float dt, float daylight) {
        burning = false;
        // Стоять в костре больно всем, а не только нежити на солнце.
        // Проверяем оба занимаемых блока: иначе высокий моб «горит ногами»,
        // но урона не получает.
        int fx = (int) Math.floor(position.x);
        int fz = (int) Math.floor(position.z);
        int fy = (int) Math.floor(position.y);
        if (world.getBlock(fx, fy, fz) == BlockType.FIRE
                || world.getBlock(fx, fy + 1, fz) == BlockType.FIRE) {
            burning = true;
            health -= FIRE_DAMAGE_PER_SECOND * dt;
            return;
        }
        if (!type.burnsInSunlight || daylight <= BURN_DAYLIGHT)
            return;
        int bx = (int) Math.floor(position.x);
        int bz = (int) Math.floor(position.z);
        int startY = (int) Math.floor(position.y + type.height);
        for (int y = startY; y < Chunk.SIZE_Y; y++)
            if (world.getBlock(bx, y, bz).solid)
                return;
        burning = true;
        health -= 2f * dt;
    }

    /** Удар с обычным отбросом. */
    public boolean hurt(float amount, float fromX, float fromZ) {
        return hurt(amount, fromX, fromZ, 1f);
    }

    /**
     * Получить урон от точки (fromX, fromZ): отбрасывание, вспышка, а мирный
     * моб убегает.
     *
     * Попадание в окне неуязвимости игнорируется целиком — иначе урон
     * определяется тем, как быстро игрок щёлкает мышью.
     *
     * @param knockbackMul множитель отброса (спринт-удар бьёт сильнее)
     * @return true, если урон прошёл
     */
    public boolean hurt(float amount, float fromX, float fromZ, float knockbackMul) {
        return hurt(amount, fromX, fromZ, knockbackMul, true);
    }

    /**
     * @param byPlayer удар нанёс игрок: хищник злится на него и зовёт стаю;
     *                 укус другого моба злит только в сторону укусившего
     */
    public boolean hurt(float amount, float fromX, float fromZ, float knockbackMul, boolean byPlayer) {
        if (invulnTime > 0f || dead)
            return false;
        invulnTime = INVULN_TIME;
        health -= amount;
        hurtFlash = HURT_FLASH_TIME;
        float dx = position.x - fromX;
        float dz = position.z - fromZ;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len > 1e-4f) {
            knockX = dx / len * KNOCKBACK * knockbackMul;
            knockZ = dz / len * KNOCKBACK * knockbackMul;
            if (onGround)
                velocity.y = KNOCKBACK_UP;
            if (type.temper == MobType.Temper.PASSIVE || type.temper == MobType.Temper.SKITTISH) {
                setMoveDirection(dx, dz);
                state = State.FLEE;
                stateTimer = FLEE_TIME;
                path = null;
            }
        }
        if (type.temper == MobType.Temper.NEUTRAL && byPlayer)
            angryTimer = Wildlife.ANGER_TIME;
        if (health <= 0f) {
            eaten = !byPlayer;
            die(dx, dz, true);
        }
        return true;
    }

    /** Видит ли моб игрока: луч от своих глаз к глазам игрока. */
    boolean canSee(World world, Vector3f playerPos) {
        return EntityPhysics.lineOfSight(world,
                position.x, position.y + type.height * 0.85f, position.z,
                playerPos.x, playerPos.y + PLAYER_EYE_HEIGHT, playerPos.z);
    }

    /**
     * Попадает ли точка в конус зрения моба.
     *
     * Чистая функция — вынесена, чтобы её можно было проверить тестом без
     * мира: это ровно та геометрия, которая тихо ломается при смене
     * соглашения о направлении yaw.
     *
     * @param yaw курс моба; вперёд — это (-sin yaw, -cos yaw)
     */
    public static boolean inSightCone(float yaw, float dx, float dz) {
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4f)
            return true;
        float fx = (float) -Math.sin(yaw), fz = (float) -Math.cos(yaw);
        float cos = (fx * dx + fz * dz) / len;
        return cos >= (float) Math.cos(SIGHT_HALF_ANGLE);
    }

    /**
     * Дальность обнаружения при данной освещённости цели.
     *
     * @param light 0..1 — max(небо*день, блочный свет) там, где стоит игрок
     */
    public static float sightRange(float light) {
        float k = Math.max(0f, Math.min(1f, light));
        return AGGRO_RANGE * (DARK_SIGHT_FACTOR + (1f - DARK_SIGHT_FACTOR) * k);
    }

    /**
     * Услышать шум. Моб бросает патруль и идёт проверять — но только если он
     * ещё не гонится за игроком: погоня важнее любого хлопка.
     *
     * @param loudness радиус слышимости в блоках
     */
    public void hearNoise(float x, float y, float z, float loudness) {
        if (!type.hostile || dead)
            return;
        if (state == State.CHASE || state == State.ATTACK || state == State.STALK)
            return;
        float dx = x - position.x, dy = y - position.y, dz = z - position.z;
        if (dx * dx + dy * dy + dz * dz > loudness * loudness)
            return;
        noiseX = x;
        noiseY = y;
        noiseZ = z;
        investigateTimer = INVESTIGATE_TIME;
        state = State.INVESTIGATE;
    }

    /** Идёт ли моб сейчас проверять шум. */
    public boolean isInvestigating() {
        return state == State.INVESTIGATE;
    }

    /** Урон удара этого моба по игроку. */
    public float attackDamage() {
        float base = type == MobType.WOLF ? Wildlife.BITE_DAMAGE
                : (enraged ? ENRAGED_DAMAGE : ATTACK_DAMAGE);
        if (elite == MobTactics.Elite.VENOMOUS)
            base *= 1.18f;
        return base * limbs.attackMultiplier();
    }

    /** Targeted hit used by melee/ranged weapons after the AABB intersection. */
    public boolean hurtLimb(float amount, LimbDamage.Limb limb, float fromX, float fromZ,
                            float knockbackMul) {
        if (limb != null)
            limbs.hit(limb, Math.min(0.45f, amount / Math.max(1f, type.maxHealth)));
        float scaled = limb == LimbDamage.Limb.HEAD ? amount * limbs.headDamageMultiplier() : amount;
        return hurt(scaled, fromX, fromZ, knockbackMul, true);
    }

    /** Зол ли хищник на игрока. */
    public boolean isAngry() {
        return angryTimer > 0f || visualAngry;
    }

    /** Попасть в моба можно, пока он жив: труп стрела прошивает насквозь. */
    @Override
    public boolean hittable() {
        return !dead;
    }

    /** Попадание снарядом — тот же урон и отброс, что от удара. */
    @Override
    public void takeProjectile(float damage, float fromX, float fromZ, float knockback,
                               boolean fromPlayer) {
        hurt(damage, fromX, fromZ, knockback, fromPlayer);
    }

    /** Дистанция до попадания луча в AABB моба, либо -1. */
    @Override
    public float rayHitDistance(Vector3f origin, Vector3f dir) {
        float hw = type.width / 2f;
        return EntityPhysics.rayAabbDistance(origin.x, origin.y, origin.z, dir.x, dir.y, dir.z,
                position.x - hw, position.y, position.z - hw,
                position.x + hw, position.y + type.height, position.z + hw);
    }

    public Vector3f soundPosition() {
        return new Vector3f(position.x, position.y + type.height * 0.6f, position.z);
    }
}
