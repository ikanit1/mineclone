package com.mineclone.net;

import org.joml.Vector3f;

/**
 * Чужой игрок: что о нём известно и где его рисовать.
 *
 * <p>Состояние приходит двенадцать раз в секунду, а кадров в секунду в разы
 * больше, поэтому положение не подставляется как есть, а догоняется: модель
 * едет от прошлого снимка к новому за один сетевой интервал. Без этого чужой
 * игрок дёргался бы ровно с частотой сети, и никакая плавность кадра этого бы
 * не спрятала.
 *
 * <p>Размах шага не передаётся вовсе — он считается из пройденного пути между
 * снимками. Так дешевле, и так анимация не может разойтись с движением:
 * стоящий игрок не машет ногами, потому что расстояние не растёт, а не потому,
 * что кто-то прислал ноль.
 */
public final class RemotePlayer {

    /** Флаги состояния в пакете {@link NetProto#X_PLAYER_STATE}. */
    public static final int F_ON_GROUND = 1;
    public static final int F_SPRINT = 2;
    public static final int F_IN_WATER = 4;
    public static final int F_FLYING = 8;
    public static final int F_DEAD = 16;

    /** За сколько модель догоняет новый снимок; чуть больше интервала сети. */
    private static final float CATCH_UP = 1.25f / NetProto.TICK_RATE;
    /** Сколько держится замах рукой. */
    private static final float SWING_TIME = 0.28f;
    /** Быстрее этого шага размах уже не растёт, м/с. */
    private static final float FULL_STRIDE_SPEED = 4.0f;
    /** Как быстро гаснет размах, когда игрок встал. */
    private static final float STRIDE_DECAY = 6f;
    /** Перенос дальше этого радиуса — телепорт, а не прогулка по воздуху. */
    private static final float TELEPORT_DISTANCE = 8f;
    /** Длина одного слышимого шага. */
    private static final float FOOTSTEP_DISTANCE = 1.9f;
    /** Реплика над моделью держится достаточно долго, чтобы её успели прочитать. */
    private static final float CHAT_TIME = 5f;

    public final int actor;
    public String name;
    public float health = 20f;
    public int gameMode;
    public int flags;

    /** Где рисовать. */
    public final Vector3f position = new Vector3f();
    /** Откуда и куда едет модель между снимками. */
    private final Vector3f from = new Vector3f();
    private final Vector3f to = new Vector3f();
    private float lerp = 1f;
    private boolean placed;

    public float yaw;
    public float pitch;
    /**
     * Корпус и голова по отдельности.
     *
     * <p>По сети едет только курс взгляда: курс корпуса выводится из движения
     * теми же правилами, что и у своего игрока, поэтому его не надо ни
     * передавать, ни хранить у хозяина — у всех наблюдателей он получается
     * одинаковым.
     */
    private final com.mineclone.render.BodyRotation body = new com.mineclone.render.BodyRotation();
    private float yawFrom, yawTo;
    private float pitchFrom, pitchTo;

    /** Монотонный путь — им заведены синусы шага, как у своего игрока. */
    public float walkedDistance;
    /** Амплитуда шага 0..1: на месте гаснет. */
    public float walkAmount;
    public float swing;
    private float swingTimer;
    private boolean swingStarted;
    private float footstepDistance;
    private int pendingFootsteps;
    private String chatText = "";
    private float chatTimer;
    /** Сколько секунд от последнего снимка — по этому отваливаются молчащие. */
    public float silence;

    public RemotePlayer(int actor, String name) {
        this.actor = actor;
        this.name = name == null ? "" : name;
    }

    /** Пришёл снимок. */
    public void accept(float x, float y, float z, float newYaw, float newPitch, int newFlags) {
        boolean teleport = placed && position.distanceSquared(x, y, z)
                > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
        if (!placed || teleport) {
            placed = true;
            position.set(x, y, z);
            from.set(position);
            to.set(position);
            yaw = yawFrom = yawTo = newYaw;
            pitch = pitchFrom = pitchTo = newPitch;
            walkAmount = 0f;
            footstepDistance = 0f;
            pendingFootsteps = 0;
            body.snap(newYaw, newPitch);
        } else {
            from.set(position);
            yawFrom = yaw;
            pitchFrom = pitch;
        }
        to.set(x, y, z);
        yawTo = newYaw;
        pitchTo = newPitch;
        lerp = 0f;
        flags = newFlags;
        silence = 0f;
    }

    public void startSwing() {
        swingTimer = SWING_TIME;
        swingStarted = true;
    }

    /** Показать последнюю реплику игрока над его моделью. */
    void say(String text) {
        chatText = text == null ? "" : text;
        chatTimer = chatText.isEmpty() ? 0f : CHAT_TIME;
    }

    /** Текст реплики или пустая строка после её исчезновения. */
    public String chatText() {
        return chatText;
    }

    /** Прозрачность реплики: последний миг она мягко растворяется. */
    public float chatAlpha() {
        return Math.min(1f, chatTimer / 1f);
    }

    /** Один звук шага, если модель успела пройти нужную дистанцию. */
    public boolean consumeFootstep() {
        if (pendingFootsteps <= 0)
            return false;
        pendingFootsteps--;
        return true;
    }

    /** Начало чужого замаха — для короткого пространственного свиста. */
    public boolean consumeSwingStart() {
        if (!swingStarted)
            return false;
        swingStarted = false;
        return true;
    }

    /** Довести модель до кадра: положение, поворот, анимация. */
    public void update(float dt) {
        silence += dt;
        if (chatTimer > 0f) {
            chatTimer = Math.max(0f, chatTimer - dt);
            if (chatTimer == 0f)
                chatText = "";
        }
        float px = position.x, pz = position.z;
        if (lerp < 1f) {
            lerp = Math.min(1f, lerp + dt / CATCH_UP);
            float k = lerp * lerp * (3f - 2f * lerp);
            position.set(from).lerp(to, k);
            yaw = lerpAngle(yawFrom, yawTo, k);
            pitch = pitchFrom + (pitchTo - pitchFrom) * k;
        } else {
            position.set(to);
            yaw = yawTo;
            pitch = pitchTo;
        }
        // Размах — из горизонтальной скорости. В воздухе и воде ноги не
        // должны «идти»: горизонталь там означает полёт или плавание.
        float dx = position.x - px, dz = position.z - pz;
        float moved = (float) Math.hypot(dx, dz);
        // Корпус доворачивается к движению, голова смотрит за взглядом.
        body.update(dt, yaw, pitch, dt > 1e-4f ? dx / dt : 0f, dt > 1e-4f ? dz / dt : 0f);
        boolean walking = (flags & F_ON_GROUND) != 0
                && (flags & (F_IN_WATER | F_FLYING)) == 0;
        if (walking)
            walkedDistance += moved;
        float speed = dt > 1e-4f ? moved / dt : 0f;
        float target = walking ? Math.min(1f, speed / FULL_STRIDE_SPEED) : 0f;
        if (!walking)
            // В воздухе анимация шага не имеет физического смысла. Плавное
            // затухание оставляло на нескольких кадрах «кривую» позу после
            // прыжка или переключения полёта.
            walkAmount = 0f;
        else if (target > walkAmount)
            walkAmount = target;
        else
            walkAmount = Math.max(target, walkAmount - STRIDE_DECAY * dt);
        if (walking) {
            footstepDistance += moved;
            while (footstepDistance >= FOOTSTEP_DISTANCE) {
                footstepDistance -= FOOTSTEP_DISTANCE;
                pendingFootsteps = Math.min(3, pendingFootsteps + 1);
            }
        } else {
            footstepDistance = 0f;
        }
        if (swingTimer > 0f) {
            swingTimer = Math.max(0f, swingTimer - dt);
            // Дуга, а не пила: замах разгоняется и затухает.
            swing = (float) Math.sin(Math.PI * (1.0 - swingTimer / SWING_TIME));
        } else {
            swing = 0f;
        }
    }

    /** Курс корпуса: им повёрнута вся модель. */
    public float bodyYaw() {
        return body.bodyYaw;
    }

    /** На сколько голова отвёрнута от корпуса — для проверок. */
    public float headOffset() {
        return body.headOffset();
    }

    public boolean isDead() {
        return (flags & F_DEAD) != 0;
    }

    public boolean placed() {
        return placed;
    }

    /** Кратчайший путь между углами: иначе разворот на 359° шёл бы через круг. */
    private static float lerpAngle(float a, float b, float k) {
        float d = b - a;
        while (d > Math.PI)
            d -= (float) (Math.PI * 2);
        while (d < -Math.PI)
            d += (float) (Math.PI * 2);
        return a + d * k;
    }
}
