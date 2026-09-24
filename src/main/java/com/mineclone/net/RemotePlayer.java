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
public final class RemotePlayer implements com.mineclone.world.entity.Hittable {

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
    public com.mineclone.world.ItemStack heldItem;
    private long equipmentSequence = -1;

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
    public final com.mineclone.render.PlayerAnimation animation = new com.mineclone.render.PlayerAnimation();
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
        // Повреждённый снимок не должен отравить следующую интерполяцию.
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(newYaw) || !Float.isFinite(newPitch))
            return;
        newYaw = com.mineclone.render.BodyRotation.wrap(newYaw);
        newPitch = Math.max(-(float) Math.PI / 2f, Math.min((float) Math.PI / 2f, newPitch));
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
            animation.reset(position, newYaw, (newFlags & F_ON_GROUND) != 0,
                    (newFlags & F_IN_WATER) != 0, (newFlags & F_FLYING) != 0);
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

    public void acceptEquipment(long sequence, String id) {
        if (sequence <= equipmentSequence || id == null || id.length() > 128) return;
        var item = id.isEmpty() ? null : com.mineclone.item.Items.get().get(id);
        if (!id.isEmpty() && item == null) return;
        equipmentSequence = sequence;
        heldItem = item == null ? null : new com.mineclone.world.ItemStack(item, 1);
    }

    public void startSwing() {
        swingTimer = SWING_TIME;
        swingStarted = true;
        animation.startSwing();
    }

    /** Показать последнюю реплику игрока над его моделью. */
    void say(String text) {
        chatText = text == null ? "" : text;
        chatTimer = chatText.isEmpty() ? 0f : CHAT_TIME;
    }

    /** Текст реплики или пустая строка после её исчезновения. */
    /**
     * Куда уходит попадание по этому игроку.
     *
     * Ставит {@code Multiplayer}: сам по себе удалённый игрок — это снимок,
     * он не знает ни о каком протоколе, а урон обязан уехать пакетом тому, в
     * кого попали.
     */
    public Hurt onHurt;

    /** Where a hit on this guest goes: the host sends it to the guest's machine. */
    @FunctionalInterface
    public interface Hurt {
        void send(com.mineclone.world.damage.DamageSource source, float amount);
    }

    @Override
    public float rayHitDistance(Vector3f origin, Vector3f dir) {
        float hw = com.mineclone.game.Player.WIDTH / 2f;
        return com.mineclone.world.entity.EntityPhysics.rayAabbDistance(
                origin.x, origin.y, origin.z, dir.x, dir.y, dir.z,
                position.x - hw, position.y, position.z - hw,
                position.x + hw, position.y + com.mineclone.game.Player.HEIGHT,
                position.z + hw);
    }

    @Override
    public boolean hittable() {
        // Творческий режим и мёртвые не ловят стрел — как и свой игрок.
        return health > 0f && gameMode != com.mineclone.world.GameMode.CREATIVE.ordinal();
    }

    /**
     * The guest owns its health: the hit goes to it as {@code S_PLAYER_HURT},
     * and the guest's own invulnerability window decides whether it lands.
     *
     * @return true when the hit was sent
     */
    @Override
    public boolean damage(com.mineclone.world.damage.DamageSource source, float amount) {
        if (!(amount > 0f) || !Float.isFinite(amount) || onHurt == null)
            return false;
        onHurt.send(source, amount);
        return true;
    }

    /** A guest's arrow remembers the guest's number. */
    @Override
    public int participantId() {
        return actor;
    }

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
        if (!Float.isFinite(dt) || dt <= 0f) return;
        silence += dt;
        if (chatTimer > 0f) {
            chatTimer = Math.max(0f, chatTimer - dt);
            if (chatTimer == 0f)
                chatText = "";
        }
        float px = position.x, pz = position.z;
        if (lerp < 1f) {
            lerp = Math.min(1f, lerp + dt / CATCH_UP);
            // Do not restart an ease-in at each packet: that brakes a constant-speed walker 12 times/s.
            float k = lerp;
            position.set(from).lerp(to, k);
            yaw = com.mineclone.render.BodyRotation.lerpAngle(yawFrom, yawTo, k);
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
        if (placed && !isDead())
            animation.update(dt, position, body.bodyYaw, (flags & F_ON_GROUND) != 0,
                    (flags & F_IN_WATER) != 0, (flags & F_FLYING) != 0, (flags & F_SPRINT) != 0);
        walkAmount = animation.walkAmount();
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

    /**
     * Where the last snapshot put the player, without the catch-up easing.
     *
     * <p>{@link #position} trails the snapshot by up to one network interval so
     * the model glides; the simulation must aim at where the player is, not at
     * where the drawing has got to.
     */
    public org.joml.Vector3fc acceptedPosition() {
        return to;
    }

}
